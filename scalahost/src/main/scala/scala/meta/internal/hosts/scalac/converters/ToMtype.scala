package scala.meta
package internal.hosts.scalac
package converters

import org.scalameta.invariants._
import org.scalameta.unreachable
import scala.{Seq => _}
import scala.collection.immutable.Seq
import scala.reflect.internal.Flags._
import scala.tools.nsc.{Global => ScalaGlobal}
import scala.meta.internal.{ast => m}
import scala.meta.internal.{semantic => s}
import scala.meta.internal.hosts.scalac.reflect._
import java.util.UUID.randomUUID

// This module exposes a method that can convert scala.reflect types into equivalent scala.meta types.
// See comments to ToMtree to learn more about how this conversion preserves the original syntax of those types.
trait ToMtype extends GlobalToolkit with MetaToolkit {
  self: Api =>

  protected implicit class XtensionGtypeToMtype(gtpe: g.Type) {
    def toMtype: m.Type = gtpe.toMtypeArg.require[m.Type]
    def toMtypeArg: m.Type.Arg = tpeCache.getOrElseUpdate(gtpe, {
      val result = gtpe match {
        case g.NoPrefix =>
          unreachable
        case g.NoType =>
          unreachable
        case g.SuperType(thistpe, supertpe) =>
          require(thistpe.isInstanceOf[g.ThisType] && thistpe.typeSymbol.isType && supertpe.typeSymbol.isType)
          val supersym = if (supertpe.isInstanceOf[g.RefinedType]) g.NoSymbol else supertpe.typeSymbol
          val superqual = m.Name.Indeterminate(g.Ident(thistpe.typeSymbol).displayName).withDenot(thistpe.typeSymbol)
          val supermix = ({
            if (supersym == g.NoSymbol) m.Name.Anonymous()
            else m.Name.Indeterminate(g.Ident(supertpe.typeSymbol).displayName)
          }).withDenot(thistpe, supersym)
          m.Type.Singleton(m.Term.Super(superqual, supermix))
        case g.ThisType(sym) =>
          require(sym.isClass)
          if (sym.isModuleClass) m.Type.Singleton(sym.module.asTerm.rawcvt(g.Ident(sym.module)).withTyping(gtpe))
          else m.Type.Singleton(m.Term.This(m.Name.Indeterminate(g.Ident(sym).displayName).withDenot(sym)).withTyping(gtpe))
        case g.SingleType(pre, sym) =>
          require(sym.isTerm)
          val ref = (pre match {
            case g.NoPrefix =>
              sym.asTerm.precvt(pre, g.Ident(sym))
            case pre if pre.typeSymbol.isStaticOwner =>
              sym.asTerm.precvt(pre, g.Ident(sym))
            case pre: g.SingletonType =>
              val m.Type.Singleton(preref) = pre.toMtype
              m.Term.Select(preref, sym.asTerm.precvt(pre, g.Ident(sym)).withTyping(gtpe))
            case pre @ g.TypeRef(g.NoPrefix, quant, Nil) if quant.hasFlag(DEFERRED | EXISTENTIAL) =>
              require(quant.name.endsWith(g.nme.SINGLETON_SUFFIX))
              val prename = g.Ident(quant.name.toString.stripSuffix(g.nme.SINGLETON_SUFFIX)).displayName
              val preref = m.Term.Name(prename).withDenot(quant).withTyping(quant.tpe)
              m.Term.Select(preref, sym.asTerm.precvt(pre, g.Ident(sym)).withTyping(gtpe))
            case pre: g.TypeRef =>
              // TODO: wow, so much for the hypothesis that all post-typer types are representable with syntax
              // here's one for you: take a look at `context.unit.synthetics.get` in Typers.scala
              // the prefix of the selection is typed as `Typers.this.global.CompilationUnit#synthetics.type`
              // from what I can see, we should represent this type as an existential, i.e.
              // `_1.synthetics.type forSome { val _1: Typers.this.global.CompilationUnit }`
              // however that representation would require non-trivial effort to pull off
              // (because we'll have to carry around that m.Type.Existential and unwrap it when anyone wants to use it)
              // therefore for now I'm just putting a stub here
              sym.asTerm.precvt(pre, g.Ident(sym))
            case _ =>
              throw new ConvertException(gtpe, s"unsupported type $gtpe, prefix = ${pre.getClass}, structure = ${g.showRaw(gtpe, printIds = true, printTypes = true)}")
          }).withTyping(gtpe)
          // NOTE: we can't just emit m.Type.Singleton(m.Term.Name(...).withDenot(pre, sym))
          // because in some situations (when the prefix is not stable) that will be a lie
          // because naked names are supposed to be usable without a prefix
          m.Type.Singleton(ref)
        case g.TypeRef(pre, sym, args) =>
          require(sym.isType)
          if (sym == g.definitions.RepeatedParamClass) {
            m.Type.Arg.Repeated(args.head.toMtype)
          } else if (sym == g.definitions.ByNameParamClass) {
            m.Type.Arg.ByName(args.head.toMtype)
          } else {
            val mref = ({
              if (sym.isModuleClass) {
                g.SingleType(pre, sym.module).toMtype
              } else {
                pre match {
                  case g.NoPrefix =>
                    sym.asType.precvt(pre, g.Ident(sym))
                  case pre if pre.typeSymbol.isStaticOwner =>
                    sym.asType.precvt(pre, g.Ident(sym))
                  case pre: g.SingletonType =>
                    val m.Type.Singleton(preref) = pre.toMtype
                    m.Type.Select(preref, sym.asType.precvt(pre, g.Ident(sym)))
                  case _ =>
                    m.Type.Project(pre.toMtype, sym.asType.precvt(pre, g.Ident(sym)))
                }
              }
            })
            if (args.isEmpty) mref
            else {
              if (g.definitions.FunctionClass.seq.contains(sym) && args.nonEmpty) {
                val funargs :+ funret = args
                m.Type.Function(funargs.map(_.toMtypeArg), funret.toMtype)
              } else if (g.definitions.TupleClass.seq.contains(sym) && args.length > 1) {
                m.Type.Tuple(args.map(_.toMtype))
              } else if (sym.name.looksLikeInfix && mref.isInstanceOf[m.Type.Name] && args.length == 2) {
                m.Type.ApplyInfix(args(0).toMtype, mref.require[m.Type.Name], args(1).toMtype)
              } else {
                m.Type.Apply(mref, args.map(_.toMtype))
              }
            }
          }
        case g.RefinedType(parents, decls) =>
          // TODO: detect `val x, y: Int`
          val pdecls = decls.toLogical.map(_.toMmember(g.NoPrefix)).toList // TODO: actually, prefix here is not empty
          m.Type.Compound(parents.map(_.toMtype), pdecls.map(_.stat))
        case g.ExistentialType(quants, underlying) =>
          // TODO: infer type placeholders where they were specified explicitly
          require(quants.forall(quant => quant.isType && quant.hasFlag(DEFERRED | EXISTENTIAL)))
          val mquants = quants.toLogical.map(_.toMmember(g.NoPrefix)).toList // TODO: actually, prefix here is not empty
          m.Type.Existential(underlying.toMtype, mquants.map(_.stat))
        case g.AnnotatedType(annots, underlying) =>
          m.Type.Annotate(underlying.toMtype, annots.toMannots)
        case g.ConstantType(const @ g.Constant(tpe: g.Type)) =>
          tpe.widen.toMtype
        case g.ConstantType(const) =>
          const.rawcvt
        case tpe @ g.PolyType(tparams, ret) =>
          // NOTE: it turns out that we can't avoid polytypes here
          // even though we never need to carry around type signatures of our members (those members are their own type signatures!)
          // there are legitimate polytypes, namely type lambdas
          tpe match {
            case EtaReduce(tpe) =>
              tpe.toMtype
            case _ =>
              val mquants = tparams.toLogical.map(_.toMmember(g.NoPrefix).require[m.Type.Param])
              m.Type.Lambda(mquants, ret.toMtype)
          }
        case tpe @ g.MethodType(params, ret) =>
          m.Type.Function(params.map(_.tpe.toMtypeArg), ret.toMtype)
        case _ =>
          throw new ConvertException(gtpe, s"unsupported type $gtpe, designation = ${gtpe.getClass}, structure = ${g.showRaw(gtpe, printIds = true, printTypes = true)}")
      }
      result
    })
  }
}