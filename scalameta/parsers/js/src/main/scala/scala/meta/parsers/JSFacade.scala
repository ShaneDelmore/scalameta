package scala.meta
package parsers

import scala.scalajs.js
import js.JSConverters._
import js.annotation._

import prettyprinters._

object JSFacade {

  // https://stackoverflow.com/a/36573183/846273
  private[this] def mergeJSObjects(objs: js.Dynamic*): js.Dynamic = {
    val result = js.Dictionary.empty[Any]
    for (source <- objs) {
      for ((key, value) <- source.asInstanceOf[js.Dictionary[Any]])
        result(key) = value
    }
    result.asInstanceOf[js.Dynamic]
  }

  // https://github.com/scala-js/scala-js/issues/2170#issuecomment-176795604
  @js.native
  private[this] sealed trait JSLong extends js.Any
  implicit private[this] class LongJSOps(val x: Long) extends AnyVal {
    def toJSLong: JSLong = {
      if (x >= 0) (x & ((1L << 53) - 1)).toDouble
      else -((-x) & ((1L << 53) - 1)).toDouble
    }.asInstanceOf[JSLong]
  }

  private[this] def toNode(t: Any): js.Any = t match {
    case t: Tree => toNode(t)
    case tt: Seq[_] => tt.map(toNode).toJSArray
    case t: Option[_] => t.map(toNode).orUndefined
    case _ => ()
  }

  private[this] def toNode(t: Tree): js.Dynamic = {
    val base = js.Dynamic.literal(
      "type" -> t.productPrefix,
      "pos" -> js.Dynamic.literal(
        "start" -> t.pos.start.offset,
        "end" -> t.pos.end.offset
      )
    )

    val fields = js.Dictionary(t.productFields.zip(t.productIterator.toList).collect {
      case (name, value) => name -> toNode(value)
    }: _*).asInstanceOf[js.Dynamic]

    def v[A](a: A): js.Dynamic =
      js.Dynamic.literal("value" -> a.asInstanceOf[js.Any])

    val value = t match {
      case Lit.Char(value) => v(value.toString)
      case Lit.Long(value) => v(value.toJSLong)
      case Lit.Symbol(value) => v(value.name)
      case Lit(value) => v(value)
      case Name(value) => v(value)
      case _ => js.Dynamic.literal()
    }

    val syntax = t match {
      case _: Lit => js.Dynamic.literal("syntax" -> t.syntax)
      case _ => js.Dynamic.literal()
    }

    mergeJSObjects(base, fields, value, syntax)
  }

  private[this] def parse[A <: Tree: Parse](code: String): js.Dictionary[Any] =
    code.parse[A] match {
      case Parsed.Success(t) => toNode(t).asInstanceOf[js.Dictionary[Any]]
      case Parsed.Error(_, message, _) => js.Dictionary(
        "error" -> message
      )
    }

  @JSExportTopLevel("default")
  @JSExportTopLevel("parseSource")
  def parseSource(code: String): js.Dictionary[Any] = parse[Source](code)

  @JSExportTopLevel("parseStat")
  def parseStat(code: String): js.Dictionary[Any] = parse[Stat](code)

}
