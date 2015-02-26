package scala.meta
package internal
package parsers

import scala.collection.{ mutable, immutable }
import mutable.{ ListBuffer, StringBuilder }
import scala.{Seq => _}
import scala.collection.immutable._
import scala.reflect.ClassTag
import scala.meta.internal.ast._
import scala.meta.internal.{ast => impl}
import scala.meta.internal.tokenizers.Chars.{isOperatorPart, isScalaLetter}
import scala.meta.syntactic.Token._
import org.scalameta.tokens._
import org.scalameta.unreachable
import org.scalameta.invariants._

private[meta] object SyntacticInfo {
  private[meta] val unaryOps = Set("-", "+", "~", "!")
  private[meta] def isUnaryOp(s: String): Boolean = unaryOps contains s
  implicit class XtensionSyntacticTermName(name: Term.Name) {
    import name._
    def isLeftAssoc: Boolean = value.last != ':'
    def isUnaryOp: Boolean = SyntacticInfo.isUnaryOp(value)
    def isAssignmentOp = value match {
      case "!=" | "<=" | ">=" | "" => false
      case _                       => (value.last == '=' && value.head != '='
                                       && isOperatorPart(value.head))
    }
    // opPrecedence?
    def precedence: Int =
      if (isAssignmentOp) 0
      else if (isScalaLetter(value.head)) 1
      else (value.head: @scala.annotation.switch) match {
        case '|'             => 2
        case '^'             => 3
        case '&'             => 4
        case '=' | '!'       => 5
        case '<' | '>'       => 6
        case ':'             => 7
        case '+' | '-'       => 8
        case '*' | '/' | '%' => 9
        case _               => 10
      }
  }
  implicit class XtensionTermOps(tree: Term) {
    def isCtorCall: Boolean = tree match {
      case _: Ctor.Ref => true
      case Term.ApplyType(callee, _) => callee.isCtorCall
      case Term.Apply(callee, _) => callee.isCtorCall
      case Term.Annotate(annottee, _) => annottee.isCtorCall
      case _ => false
    }
    def ctorTpe: Type = {
      def loop(tree: Tree): Type = tree match {
        case Ctor.Name(value) => Type.Name(value)
        case Ctor.Ref.Select(qual, name) => Type.Select(qual, Type.Name(name.value))
        case Ctor.Ref.Project(qual, name) => Type.Project(qual, Type.Name(name.value))
        case Ctor.Ref.Function(_) => unreachable(debug(XtensionTermOps.this.tree, XtensionTermOps.this.tree.show[Raw]))
        case Term.ApplyType(Ctor.Ref.Function(_), targs) => Type.Function(targs.init, targs.last)
        case Term.ApplyType(callee, targs) => Type.Apply(loop(callee), targs)
        case Term.Apply(callee, _) => callee.ctorTpe
        case Term.Annotate(annottee, annots) => Type.Annotate(loop(annottee), annots)
        case _ => unreachable(debug(XtensionTermOps.this.tree, XtensionTermOps.this.tree.show[Raw], tree, tree.show[Raw]))
      }
      loop(tree)
    }
    def ctorArgss: Seq[Seq[Term.Arg]] = {
      def loop(tree: Tree): Seq[Seq[Term.Arg]] = tree match {
        case _: Ctor.Ref => Nil
        case Term.ApplyType(callee, _) => callee.ctorArgss
        case Term.Apply(callee, args) => callee.ctorArgss :+ args
        case Term.Annotate(annottee, _) => annottee.ctorArgss
        case _ => unreachable(debug(XtensionTermOps.this.tree, XtensionTermOps.this.tree.show[Raw]))
      }
      loop(tree)
    }
    def isCtorBody: Boolean = {
      def isSuperCall(tree: Tree): Boolean = tree match {
        case _: Ctor.Name => true
        case Term.Apply(fn, _) => isSuperCall(fn)
        case _ => false // you can't write `this[...](...)`
      }
      tree match {
        case Term.Block(superCall +: _) => isSuperCall(superCall)
        case superCall => isSuperCall(superCall)
      }
    }
  }
  implicit class XtensionTermRefOps(tree: Term.Ref) {
    def isPath: Boolean = tree.isStableId || tree.isInstanceOf[Term.This]
    def isQualId: Boolean = tree match {
      case _: Term.Name                   => true
      case Term.Select(qual: Term.Ref, _) => qual.isQualId
      case _                              => false
    }
    def isStableId: Boolean = tree match {
      case _: Term.Name | Term.Select(_: Term.Super, _) => true
      case Term.Select(qual: Term.Ref, _)               => qual.isPath
      case _                                            => false
    }
  }
  implicit class XtensionMod(mod: Mod) {
    def hasAccessBoundary: Boolean = mod match {
      case _: Mod.Private         => true
      case _: Mod.Protected       => true
      case _                      => false
    }
  }
  implicit class XtensionMods(mods: List[Mod]) {
    def has[T <: Mod](implicit tag: ClassTag[T]): Boolean =
      mods.exists { _.getClass == tag.runtimeClass }
    def getAll[T <: Mod](implicit tag: ClassTag[T]): List[T] =
      mods.collect { case m if m.getClass == tag.runtimeClass => m.require[T] }
    def accessBoundary: Option[Name.AccessBoundary] = mods.collectFirst{ case Mod.Private(name) => name; case Mod.Protected(name) => name }
  }
  implicit class XtensionStat(stat: Stat) {
    def isTopLevelStat: Boolean = stat match {
      case _: Import => true
      case _: Pkg => true
      case _: Defn.Class => true
      case _: Defn.Trait => true
      case _: Defn.Object => true
      case _: Pkg.Object => true
      case _ => false
    }
    def isTemplateStat: Boolean = stat match {
      case _: Import => true
      case _: Term => true
      case _: Decl => true
      case _: Defn => true
      case _: Ctor.Secondary => true
      case _ => false
    }
    def isBlockStat: Boolean = stat match {
      case _: Import => true
      case _: Term => true
      case stat: Defn.Var => stat.rhs.isDefined
      case _: Defn => true
      case _ => false
    }
    def isRefineStat: Boolean = stat match {
      case _: Decl => true
      case _: Defn.Type => true
      case _ => false
    }
    def isExistentialStat: Boolean = stat match {
      case _: Decl.Val => true
      case _: Decl.Type => true
      case _ => false
    }
    def isEarlyStat: Boolean = stat match {
      case _: Defn.Val => true
      case _: Defn.Var => true
      case _ => false
    }
  }
}
import SyntacticInfo._

private[meta] class Parser(val input: Input)(implicit val dialect: Dialect) extends AbstractParser {
  def this(code: String)(implicit dialect: Dialect) = this(Input.String(code))

  // implementation restrictions wrt various dialect properties
  require(Set("@", ":").contains(dialect.bindToSeqWildcardDesignator))

  /** The parse starting point depends on whether the input is self-contained:
   *  if not, the AST will be supplemented.
   */
  def parseStartRule = () => compilationUnit()

  // NOTE: Scala's parser isn't ready to accept whitespace and comment tokens,
  // so we have to filter them out, because otherwise we'll get errors like `expected blah, got whitespace`
  // however, in certain tricky cases some whitespace tokens (namely, newlines) do have to be emitted
  // this leads to extremely dirty and seriously crazy code, which I'd like to replace in the future
  private class CrazyTokenIterator(
    var pos: Int = -1,
    var prevTokenPos: Int = -1,
    var tokenPos: Int = -1,
    var token: Token = null,
    var sepRegions: List[Char] = Nil
  ) extends TokenIterator {
    require(tokens.nonEmpty)
    if (pos == -1) next() // TODO: only do next() if we've been just created. forks can't go for next()
    def isTrivia(token: Token) = !nonTrivia(token)
    def nonTrivia(token: Token) = token.isNot[Whitespace] && token.isNot[Comment]
    def hasNext: Boolean = tokens.drop(pos + 1).exists(nonTrivia)
    def next(): Token = {
      if (!hasNext) throw new NoSuchElementException()
      pos += 1
      prevTokenPos = tokenPos
      val prev = token
      val curr = tokens(pos)
      val nextPos = {
        var i = pos + 1
        while (i < tokens.length && isTrivia(tokens(i))) i += 1
        if (i == tokens.length) i = -1
        i
      }
      val next = if (nextPos != -1) tokens(nextPos) else null
      if (nonTrivia(curr)) {
        if (curr.is[`(`]) sepRegions = ')' :: sepRegions
        else if (curr.is[`[`]) sepRegions = ']' :: sepRegions
        else if (curr.is[`{`]) sepRegions = '}' :: sepRegions
        else if (curr.is[`case`] && !next.is[`class `] && !next.is[`object`]) sepRegions = '\u21d2' :: sepRegions
        else if (curr.is[`}`]) {
          while (!sepRegions.isEmpty && sepRegions.head != '}') sepRegions = sepRegions.tail
          if (!sepRegions.isEmpty) sepRegions = sepRegions.tail
        } else if (curr.is[`]`]) { if (!sepRegions.isEmpty && sepRegions.head == ']') sepRegions = sepRegions.tail }
        else if (curr.is[`)`]) { if (!sepRegions.isEmpty && sepRegions.head == ')') sepRegions = sepRegions.tail }
        else if (curr.is[`=>`]) { if (!sepRegions.isEmpty && sepRegions.head == '\u21d2') sepRegions = sepRegions.tail }
        else () // do nothing for other tokens
        tokenPos = pos
        token = curr
        token
      } else {
        var i = prevTokenPos + 1
        var lastNewlinePos = -1
        var newlineStreak = false
        var newlines = false
        while (i < nextPos) {
          if (tokens(i).is[`\n`] || tokens(i).is[`\f`]) {
            lastNewlinePos = i
            if (newlineStreak) newlines = true
            newlineStreak = true
          }
          newlineStreak &= tokens(i).is[Whitespace]
          i += 1
        }
        if (lastNewlinePos != -1 &&
            prev != null && prev.is[CanEndStat] &&
            next != null && next.isNot[CantStartStat] &&
            (sepRegions.isEmpty || sepRegions.head == '}')) {
          tokenPos = lastNewlinePos
          token = tokens(tokenPos)
          if (newlines) token = `\n\n`(token.input, token.start)
          token
        } else {
          pos = nextPos - 1
          this.next()
        }
      }
    }
    def fork: TokenIterator = new CrazyTokenIterator(pos, prevTokenPos, tokenPos, token, sepRegions)
  }

  val tokens = input.tokens
  var in: TokenIterator = new CrazyTokenIterator()

  /** the markup parser */
  // private[this] lazy val xmlp = new MarkupParser(this, preserveWS = true)
  // object symbXMLBuilder extends SymbolicXMLBuilder(this, preserveWS = true)
  // TODO: implement XML support
  def xmlLiteral(): Term = ??? //xmlp.xLiteral
  def xmlLiteralPattern(): Pat = ??? // xmlp.xLiteralPattern
}

private[meta] class Location private(val value: Int) extends AnyVal
private[meta] object Location {
  val Local      = new Location(0)
  val InBlock    = new Location(1)
  val InTemplate = new Location(2)
}
import Location.{ Local, InBlock, InTemplate }

private[meta] abstract class AbstractParser { parser =>
  trait TokenIterator extends Iterator[Token] { def prevTokenPos: Int; def tokenPos: Int; def token: Token; def fork: TokenIterator }
  var in: TokenIterator
  def token = in.token
  def next() = in.next()
  def nextOnce() = next()
  def nextTwice() = { next(); next() }
  def nextThrice() = { next(); next(); next() }
  val input: Input
  implicit val dialect: Dialect

  import scala.language.implicitConversions
  sealed trait Pos
  case class TokenPos(tokenPos: Int) extends Pos
  implicit def intToTokenPos(tokenPos: Int): TokenPos = TokenPos(tokenPos)
  case class TreePos(tree: Tree) extends Pos
  implicit def treeToTreePos(tree: Tree): TreePos = TreePos(tree)
  implicit def optionTreeToPos(tree: Option[Tree]): Pos = tree.map(TreePos).getOrElse(AutoPos)
  implicit def modsToPos(mods: List[Mod]): Pos = mods.headOption.map(TreePos).getOrElse(AutoPos)
  case object AutoPos extends Pos
  def auto = AutoPos

  def atPos[T <: Tree](start: Pos, end: Pos)(body: => T): T = {
    val startTokenPos = start match {
      case TokenPos(tokenPos) => tokenPos
      case TreePos(tree) => tree.origin.require[Origin.Parsed].startTokenPos
      case AutoPos => in.tokenPos
    }
    val result = body
    var endTokenPos = end match {
      case TokenPos(tokenPos) => tokenPos
      case TreePos(tree) => tree.origin.require[Origin.Parsed].endTokenPos
      case AutoPos => in.prevTokenPos
    }
    if (endTokenPos < startTokenPos) endTokenPos = startTokenPos - 1
    result.origin match {
      case Origin.Parsed(_, _, startTokenPos0, endTokenPos0) if startTokenPos == startTokenPos0 && endTokenPos == endTokenPos0 => result
      case _ => result.internalCopy(origin = Origin.Parsed(input, dialect, startTokenPos, endTokenPos)).asInstanceOf[T]
    }
  }
  def autoPos[T <: Tree](body: => T): T = atPos(start = auto, end = auto)(body)

  val reporter = Reporter()
  import reporter._

  /** Scoping operator used to temporarily look into the future.
   *  Backs up token iterator before evaluating a block and restores it after.
   */
  @inline final def ahead[T](body: => T): T = {
    val forked = in.fork
    next()
    try body finally in = forked
  }

  /** Perform an operation while peeking ahead.
   *  Recover to inputal state in case of exception.
   */
  @inline def peekingAhead[T](tree: => T): T = {
    val forked = in.fork
    next()
    // try it, in case it is recoverable
    try tree catch { case e: Exception => in = forked ; throw e }
  }

  def parseStartRule: () => Source

  def parseRule[T <: Tree](rule: this.type => T): T = {
    // NOTE: can't require in.tokenPos to be at -1, because TokIterator auto-rewinds when created
    // require(in.tokenPos == -1 && debug(in.tokenPos))
    val start = 0
    val t = rule(this)
    // NOTE: can't have in.prevTokenPos here
    // because we need to subsume all the trailing trivia
    val end = in.tokenPos - 1
    accept[EOF]
    atPos(start, end)(t)
  }

  // Entry points for Parse[T]
  // Used for quasiquotes as well as for ad-hoc parsing
  def parseStat(): Stat = parseRule(parser => parser.statSeq(parser.templateStat.orElse(parser.topStat)) match {
    case Nil => syntaxError("unexpected end of input", at = token)
    case stat :: Nil => stat
    case stats if stats.forall(_.isBlockStat) => Term.Block(stats)
    case stats if stats.forall(_.isTopLevelStat) => Source(stats)
    case other => syntaxError("these statements can't be mixed together", at = other.head)
  })
  def parseTerm(): Term = parseRule(_.expr())
  def parseTermArg(): Term.Arg = ???
  def parseTermParam(): Term.Param = ???
  def parseType(): Type = parseRule(_.typ())
  def parseTypeArg(): Type.Arg = parseRule(_.paramType())
  def parseTypeParam(): Type.Param = ???
  def parsePat(): Pat = ???
  def parsePatArg(): Pat.Arg = parseRule(_.pattern())
  def parsePatType(): Pat.Type = ???
  def parseCase(): Case = ???
  def parseCtorRef(): Ctor.Ref = ???
  def parseTemplate(): Template = ???
  def parseMod(): Mod = ???
  def parseEnumerator(): Enumerator = ???
  def parseImportee(): Importee = ???
  def parseSource(): Source = parseRule(_.parseStartRule())

/* ------------- PARSER COMMON -------------------------------------------- */

  /** Methods inParensOrError and similar take a second argument which, should
   *  the next token not be the expected opener (e.g. token.`(`) will be returned
   *  instead of the contents of the groupers.  However in all cases accept[`(`]
   *  will be called, so a parse error will still result.  If the grouping is
   *  optional, token should be tested before calling these methods.
   */
  @inline final def inParens[T](body: => T): T = {
    accept[`(`]
    val ret = body
    accept[`)`]
    ret
  }
  @inline final def inParensOrError[T](body: => T, alt: T): T =
    if (token.is[`(`]) inParens(body)
    else { accept[`(`]; alt }

  @inline final def inParensOrUnit[T, Ret >: Lit](body: => Ret): Ret = inParensOrError(body, Lit.Unit())
  @inline final def inParensOrNil[T](body: => List[T]): List[T] = inParensOrError(body, Nil)

  @inline final def inBraces[T](body: => T): T = {
    accept[`{`]
    val ret = body
    accept[`}`]
    ret
  }
  @inline final def inBracesOrError[T](body: => T, alt: T): T =
    if (token.is[`{`]) inBraces(body)
    else { accept[`{`]; alt }

  @inline final def inBracesOrNil[T](body: => List[T]): List[T] = inBracesOrError(body, Nil)
  @inline final def inBracesOrUnit[T](body: => Term): Term = inBracesOrError(body, Lit.Unit())
  @inline final def dropAnyBraces[T](body: => T): T =
    if (token.is[`{`]) inBraces(body)
    else body

  @inline final def inBrackets[T](body: => T): T = {
    accept[`[`]
    val ret = body
    accept[`]`]
    ret
  }

/* ------------- ERROR HANDLING ------------------------------------------- */

  private var inFunReturnType = false
  @inline private def fromWithinReturnType[T](body: => T): T = {
    val saved = inFunReturnType
    inFunReturnType = true
    try body
    finally inFunReturnType = saved
  }

  def syntaxErrorExpected[T: TokenMetadata]: Nothing = syntaxError(s"${implicitly[TokenMetadata[T]].name} expected but ${token.name} found.", at = token)

  /** Consume one token of the specified type, or signal an error if it is not there. */
  def accept[T: TokenMetadata]: Unit =
    if (token.is[T]) {
      if (token.isNot[EOF]) next()
    } else syntaxErrorExpected[T]

  /** If current token is T consume it. */
  def acceptOpt[T: TokenMetadata]: Unit =
    if (token.is[T]) next()

  /** {{{
   *  semi = nl {nl} | `;`
   *  nl  = `\n' // where allowed
   *  }}}
   */
  def acceptStatSep(): Unit = token match {
    case _: `\n` | _: `\n\n` => next()
    case _                   => accept[`;`]
  }
  def acceptStatSepOpt() =
    if (!token.is[StatSeqEnd])
      acceptStatSep()

/* -------------- TOKEN CLASSES ------------------------------------------- */

  def isIdentAnd(pred: String => Boolean) = token match {
    case id: Ident if pred(id.code.stripPrefix("`").stripSuffix("`")) => true
    case _                                                            => false
  }
  def isUnaryOp: Boolean            = isIdentAnd(SyntacticInfo.isUnaryOp)
  def isIdentExcept(except: String) = isIdentAnd(_ != except)
  def isIdentOf(name: String)       = isIdentAnd(_ == name)
  def isRawStar: Boolean            = isIdentOf("*")
  def isRawBar: Boolean             = isIdentOf("|")
  def isColonWildcardStar: Boolean  = token.is[`:`] && ahead(token.is[`_ `] && ahead(isIdentOf("*")))

/* ---------- TREE CONSTRUCTION ------------------------------------------- */

  /** Convert tree to formal parameter list. */
  def convertToParams(tree: Term): List[Term.Param] = tree match {
    case Term.Tuple(ts) => ts.toList flatMap convertToParam
    case _              => List(convertToParam(tree)).flatten
  }

  /** Convert tree to formal parameter. */
  def convertToParam(tree: Term): Option[Term.Param] = tree match {
    case name: Term.Name =>
      Some(atPos(tree, tree)(Term.Param(Nil, name, None, None)))
    case name: Term.Placeholder =>
      Some(atPos(tree, tree)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), None, None)))
    case Term.Ascribe(name: Term.Name, tpt) =>
      Some(atPos(tree, tree)(Term.Param(Nil, name, Some(tpt), None)))
    case Term.Ascribe(name: Term.Placeholder, tpt) =>
      Some(atPos(tree, tree)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), Some(tpt), None)))
    case Lit.Unit() =>
      None
    case other =>
      syntaxError(s"not a legal formal parameter", at = other)
  }

  def convertToTypeId(ref: Term.Ref): Option[Type] = ref match {
    case Term.Select(qual: Term.Ref, name) =>
      Some(atPos(ref, ref)(Type.Select(qual, atPos(name, name)(Type.Name(name.value)))))
    case name: Term.Name =>
      Some(atPos(name, name)(Type.Name(name.value)))
    case _ =>
      None
  }

  /** {{{ part { `sep` part } }}},or if sepFirst is true, {{{ { `sep` part } }}}. */
  final def tokenSeparated[Sep: TokenMetadata, T](sepFirst: Boolean, part: => T): List[T] = {
    val ts = new ListBuffer[T]
    if (!sepFirst)
      ts += part

    while (token.is[Sep]) {
      next()
      ts += part
    }
    ts.toList
  }

  @inline final def commaSeparated[T](part: => T): List[T] =
    tokenSeparated[`,`, T](sepFirst = false, part)

  @inline final def caseSeparated[T](part: => T): List[T] =
    tokenSeparated[`case`, T](sepFirst = true, part)

  def readAnnots(part: => Mod.Annot): List[Mod.Annot] =
    tokenSeparated[`@`, Mod.Annot](sepFirst = true, part)

  def makeTuple[T <: Tree](body: List[T], zero: () => T, tuple: List[T] => T): T = body match {
    case Nil         => zero()
    case only :: Nil => only
    case _           => tuple(body)
  }

  def makeTupleTerm(body: List[Term]): Term = {
    // TODO: we can't make this autoPos, unlike makeTupleTermParens
    // see comments to makeTupleType for discussion
    makeTuple[Term](body, () => Lit.Unit(), Term.Tuple(_))
  }

  def makeTupleTermParens(bodyf: => List[Term]) = autoPos {
    makeTupleTerm(inParens(if (token.is[`)`]) Nil else bodyf))
  }

  // TODO: make zero tuple for types Lit.Unit() too?
  def makeTupleType(body: List[Type]): Type = {
    // TODO: we can't make this autoPos, unlike makeTuplePatParens
    // because, by the time control reaches this method, we're already past the closing parenthesis
    // therefore, we'll rely on our callers to assign positions to the tuple we return
    // we can't do atPos(body.first, body.last) either, because that wouldn't account for parentheses
    makeTuple[Type](body, () => unreachable, Type.Tuple(_))
  }

  def makeTuplePatParens(bodyf: => List[Pat.Arg]): Pat = autoPos {
    val body = inParens(if (token.is[`)`]) Nil else bodyf.map(_.require[Pat]))
    makeTuple[Pat](body, () => Lit.Unit(), Pat.Tuple(_))
  }

/* --------- OPERAND/OPERATOR STACK --------------------------------------- */

  /** Modes for infix types. */
  object InfixMode extends Enumeration {
    val FirstOp, LeftOp, RightOp = Value
  }

  // TODO: couldn't make this final, because of a patmat warning
  case class OpInfo[T: OpCtx](lhs: T, operator: Term.Name, targs: List[Type]) {
    def precedence = operator.precedence
  }

  sealed abstract class OpCtx[T] {
    def opinfo(tree: T): OpInfo[T]
    def binop(opinfo: OpInfo[T], rhs: T): T
    var stack: List[OpInfo[T]] = Nil
    def head = stack.head
    def push(top: T): Unit = stack ::= opinfo(top)
    def pop(): OpInfo[T] = try head finally stack = stack.tail
  }
  object OpCtx {
    implicit object `List Term.Arg Context` extends OpCtx[List[Term.Arg]] {
      def opinfo(tree: List[Term.Arg]): OpInfo[List[Term.Arg]] = {
        val name = termName()
        val targs = if (token.is[`[`]) exprTypeArgs() else Nil
        OpInfo(tree, name, targs)
      }
      def binop(opinfo: OpInfo[List[Term.Arg]], rhs: List[Term.Arg]): List[Term.Arg] = {
        // TODO: figure out the position here
        val lhs = makeTupleTerm(opinfo.lhs map {
          case t: Term => t
          case other   => unreachable(debug(other, other.show[Raw]))
        })
        // TODO: figure out the position here
        Term.ApplyInfix(lhs, opinfo.operator, opinfo.targs, rhs) :: Nil
      }
    }
    implicit object `Pat Context` extends OpCtx[Pat] {
      def opinfo(tree: Pat): OpInfo[Pat] = {
        val name = termName()
        if (token.is[`[`]) syntaxError("infix patterns cannot have type arguments", at = token)
        OpInfo(tree, name, Nil)
      }
      def binop(opinfo: OpInfo[Pat], rhs: Pat): Pat = {
        val args = rhs match {
          case Pat.Tuple(args) => args.toList
          case _               => List(rhs)
        }
        // TODO: figure out the position here
        Pat.ExtractInfix(opinfo.lhs, opinfo.operator, args)
      }
    }
  }

  def opctx[T](implicit ctx: OpCtx[T]) = ctx

  def checkHeadAssoc[T: OpCtx](leftAssoc: Boolean) = checkAssoc(opctx.head.operator, leftAssoc)

  def checkAssoc(op: Term.Name, leftAssoc: Boolean): Unit = (
    if (op.isLeftAssoc != leftAssoc)
      syntaxError("left- and right-associative operators with same precedence may not be mixed", at = op)
  )

  def finishPostfixOp(base: List[OpInfo[List[Term.Arg]]], opinfo: OpInfo[List[Term.Arg]]): List[Term.Arg] =
    // TODO: figure out the position here
    Term.Select(reduceStack(base, opinfo.lhs) match {
      case (t: Term) :: Nil => t
      case other            => unreachable(debug(other))
    }, opinfo.operator) :: Nil

  def finishBinaryOp[T: OpCtx](opinfo: OpInfo[T], rhs: T): T = opctx.binop(opinfo, rhs)

  def reduceStack[T: OpCtx](base: List[OpInfo[T]], top: T): T = {
    val opPrecedence = if (token.is[Ident]) termName(advance = false).precedence else 0
    val leftAssoc    = !token.is[Ident] || termName(advance = false).isLeftAssoc

    reduceStack(base, top, opPrecedence, leftAssoc)
  }

  def reduceStack[T: OpCtx](base: List[OpInfo[T]], top: T, opPrecedence: Int, leftAssoc: Boolean): T = {
    def isDone          = opctx.stack == base
    def lowerPrecedence = !isDone && (opPrecedence < opctx.head.precedence)
    def samePrecedence  = !isDone && (opPrecedence == opctx.head.precedence)
    def canReduce       = lowerPrecedence || leftAssoc && samePrecedence

    if (samePrecedence)
      checkHeadAssoc(leftAssoc)

    def loop(top: T): T =
      if (!canReduce) top
      else {
        val info = opctx.pop()
        loop(finishBinaryOp(info, top))
      }

    loop(top)
  }

/* -------- IDENTIFIERS AND LITERALS ------------------------------------------- */

  /** Methods which implicitly propagate the context in which they were
   *  called: either in a pattern context or not.  Formerly, this was
   *  threaded through numerous methods as boolean isPattern.
   */
  trait PatternContextSensitive {
    /** {{{
     *  ArgType       ::=  Type
     *  }}}
     */
    def argType(): Type
    def functionArgType(): Type.Arg

    private def tupleInfixType(): Type = autoPos {
      require(token.is[`(`] && debug(token))
      val openParenPos = in.tokenPos
      next()
      if (token.is[`)`]) {
        next()
        accept[`=>`]
        Type.Function(Nil, typ())
      }
      else {
        val ts = functionTypes()
        val closeParenPos = in.tokenPos
        accept[`)`]
        if (token.is[`=>`]) {
          next()
          Type.Function(ts, typ())
        } else {
          val tuple = atPos(openParenPos, closeParenPos)(makeTupleType(ts map {
            case t: Type              => t
            case p: Type.Arg.ByName   => syntaxError("by name type not allowed here", at = p)
            case p: Type.Arg.Repeated => syntaxError("repeated type not allowed here", at = p)
          }))
          infixTypeRest(
            compoundTypeRest(Some(
              annotTypeRest(
                simpleTypeRest(
                  tuple)))),
            InfixMode.FirstOp
          )
        }
      }
    }

    /** {{{
     *  Type ::= InfixType `=>' Type
     *         | `(' [`=>' Type] `)' `=>' Type
     *         | InfixType [ExistentialClause]
     *  ExistentialClause ::= forSome `{' ExistentialDcl {semi ExistentialDcl}} `}'
     *  ExistentialDcl    ::= type TypeDcl | val ValDcl
     *  }}}
     */
    def typ(): Type = autoPos {
      val t: Type =
        if (token.is[`(`]) tupleInfixType()
        else infixType(InfixMode.FirstOp)

      token match {
        case _: `=>`      => next(); Type.Function(List(t), typ())
        case _: `forSome` => next(); Type.Existential(t, existentialStats())
        case _            => t
      }
    }

    /** {{{
     *  TypeArgs    ::= `[' ArgType {`,' ArgType} `]'
     *  }}}
     */
    def typeArgs(): List[Type] = inBrackets(types())

    /** {{{
     *  ModType        ::=  SimpleType {Annotation}
     *  }}}
     */
    def annotType(): Type = annotTypeRest(simpleType())

    /** {{{
     *  SimpleType       ::=  SimpleType TypeArgs
     *                     |  SimpleType `#' Id
     *                     |  StableId
     *                     |  Path `.' type
     *                     |  `(' Types `)'
     *                     |  WildcardType
     *  }}}
     */
    def simpleType(): Type = {
      simpleTypeRest(autoPos(token match {
        case _: `(`  => autoPos(makeTupleType(inParens(types())))
        case _: `_ ` => next(); atPos(in.prevTokenPos, auto)(Type.Placeholder(typeBounds()))
        case _       =>
          val ref: Term.Ref = path()
          if (token.isNot[`.`])
            convertToTypeId(ref) getOrElse { syntaxError("identifier expected", at = ref) }
          else {
            next()
            accept[`type`]
            Type.Singleton(ref)
          }
      }))
    }

    def simpleTypeRest(t: Type): Type = token match {
      case _: `#` => next(); simpleTypeRest(atPos(t, auto)(Type.Project(t, typeName())))
      case _: `[` => simpleTypeRest(atPos(t, auto)(Type.Apply(t, typeArgs())))
      case _      => t
    }


    /** {{{
     *  CompoundType ::= ModType {with ModType} [Refinement]
     *                |  Refinement
     *  }}}
     */
    def compoundType(): Type = compoundTypeRest(
      if (token.is[`{`]) None
      else Some(annotType())
    )

    // TODO: warn about def f: Unit { } case?
    def compoundTypeRest(t: Option[Type]): Type = atPos(t, auto) {
      val ts = new ListBuffer[Type] ++ t
      while (token.is[`with`]) {
        next()
        ts += annotType()
      }
      newLineOptWhenFollowedBy[`{`]
      val types = ts.toList
      if (token.is[`{`]) {
        val refinements = refinement()
        (types, refinements) match {
          case (typ :: Nil, Nil) => typ
          case _  => Type.Compound(types, refinements)
        }
      } else {
        if (types.length == 1) types.head
        else Type.Compound(types, Nil)
      }
    }

    def infixTypeRest(t: Type, mode: InfixMode.Value): Type = atPos(t, auto) {
      if (isIdentExcept("*")) {
        val name = termName(advance = false)
        val leftAssoc = name.isLeftAssoc
        if (mode != InfixMode.FirstOp) checkAssoc(name, leftAssoc = mode == InfixMode.LeftOp)
        val op = typeName()
        newLineOptWhenFollowing(_.is[TypeIntro])
        def mkOp(t1: Type) = atPos(t, t1)(Type.ApplyInfix(t, op, t1))
        if (leftAssoc)
          infixTypeRest(mkOp(compoundType()), InfixMode.LeftOp)
        else
          mkOp(infixType(InfixMode.RightOp))
      } else t
    }

    /** {{{
     *  InfixType ::= CompoundType {id [nl] CompoundType}
     *  }}}
     */
    def infixType(mode: InfixMode.Value): Type =
      infixTypeRest(compoundType(), mode)

    /** {{{
     *  Types ::= Type {`,' Type}
     *  }}}
     */
    def types(): List[Type] = commaSeparated(argType())
    def functionTypes(): List[Type.Arg] = commaSeparated(functionArgType())
  }

  private trait AllowedName[T]
  private object AllowedName { implicit object Term extends AllowedName[impl.Term.Name]; implicit object Type extends AllowedName[impl.Type.Name] }
  private def name[T <: Tree : AllowedName](ctor: String => T, advance: Boolean): T = token match {
    case token: Ident =>
      val name = token.code.stripPrefix("`").stripSuffix("`")
      val res = atPos(in.tokenPos, in.tokenPos)(ctor(name))
      if (advance) next()
      res
    case _ =>
      syntaxErrorExpected[Ident]
  }
  def termName(advance: Boolean = true): Term.Name = name(Term.Name(_), advance)
  def typeName(advance: Boolean = true): Type.Name = name(Type.Name(_), advance)

  /** {{{
   *  Path       ::= StableId
   *              |  [Ident `.'] this
   *  ModType ::= Path [`.' type]
   *  }}}
   */
  // TODO: this has to be rewritten
  def path(thisOK: Boolean = true): Term.Ref = {
    def stop = token.isNot[`.`] || ahead { token.isNot[`this`] && token.isNot[`super`] && !token.is[Ident] }
    if (token.is[`this`]) {
      next()
      val thisnone = atPos(in.prevTokenPos, auto)(Term.This(None))
      if (stop && thisOK) thisnone
      else {
        accept[`.`]
        selectors(thisnone)
      }
    } else if (token.is[`super`]) {
      next()
      val superp = atPos(in.prevTokenPos, auto)(Term.Super(None, mixinQualifierOpt()))
      accept[`.`]
      val supersel = atPos(superp, auto)(Term.Select(superp, termName()))
      if (stop) supersel
      else {
        next()
        selectors(supersel)
      }
    } else {
      val name = termName()
      if (stop) name
      else {
        next()
        if (token.is[`this`]) {
          next()
          val thisid = atPos(name, auto)(Term.This(Some(name.value)))
          if (stop && thisOK) thisid
          else {
            accept[`.`]
            selectors(thisid)
          }
        } else if (token.is[`super`]) {
          next()
          val superp = atPos(name, auto)(Term.Super(Some(name.value), mixinQualifierOpt()))
          accept[`.`]
          val supersel = atPos(superp, auto)(Term.Select(superp, termName()))
          if (stop) supersel
          else {
            next()
            selectors(supersel)
          }
        } else {
          selectors(name)
        }
      }
    }
  }

  def selector(t: Term): Term.Select = atPos(t, auto)(Term.Select(t, termName()))
  def selectors(t: Term.Ref): Term.Ref ={
    val t1 = selector(t)
    if (token.is[`.`] && ahead { token.is[Ident] }) {
      next()
      selectors(t1)
    }
    else t1
  }

  /** {{{
  *   MixinQualifier ::= `[' Id `]'
  *   }}}
  */
  def mixinQualifierOpt(): Option[String] =
    if (token.is[`[`]) Some(inBrackets(typeName().value))
    else None

  /** {{{
   *  StableId ::= Id
   *            |  Path `.' Id
   *            |  [id `.'] super [`[' id `]']`.' id
   *  }}}
   */
  def stableId(): Term.Ref =
    path(thisOK = false)

  /** {{{
  *   QualId ::= Id {`.' Id}
  *   }}}
  */
  def qualId(): Term.Ref = {
    val name = termName()
    if (token.isNot[`.`]) name
    else {
      next()
      selectors(name)
    }
  }

  /** {{{
   *  SimpleExpr    ::= literal
   *                  | symbol
   *                  | null
   *  }}}
   */
  def literal(isNegated: Boolean = false): Lit = autoPos {
    val res = token match {
      case token: Literal.Char   => Lit.Char(token.value)
      case token: Literal.Int    => Lit.Int(token.value(isNegated))
      case token: Literal.Long   => Lit.Long(token.value(isNegated))
      case token: Literal.Float  => Lit.Float(token.value(isNegated))
      case token: Literal.Double => Lit.Double(token.value(isNegated))
      case token: Literal.String => Lit.String(token.value)
      case token: Literal.Symbol => Lit.Symbol(token.value)
      case token: `true`         => Lit.Bool(true)
      case token: `false`        => Lit.Bool(false)
      case token: `null`         => Lit.Null()
      case _                     => unreachable(debug(token))
    }
    next()
    res
  }

  def interpolate[Ctx <: Tree, Ret <: Tree](arg: () => Ctx, result: (Term.Name, List[Lit.String], List[Ctx]) => Ret): Ret = autoPos {
    val interpolator = atPos(in.tokenPos, auto)(Term.Name(token.require[Interpolation.Id].code)) // termName() for INTERPOLATIONID
    next()
    val partsBuf = new ListBuffer[Lit.String]
    val argsBuf = new ListBuffer[Ctx]
    def loop(): Unit = token match {
      case token: Interpolation.Start => next(); loop()
      case token: Interpolation.Part => partsBuf += Lit.String(token.code); next(); loop()
      case token: Interpolation.SpliceStart => next(); argsBuf += arg(); loop()
      case token: Interpolation.SpliceEnd => next(); loop()
      case token: Interpolation.End => next(); // just return
      case _ => unreachable(debug(token, token.show[Raw]))
    }
    loop()
    result(interpolator, partsBuf.toList, argsBuf.toList)
  }

  def interpolateTerm(): Term.Interpolate = {
    def dropTrivialBlock(term: Term): Term = term match {
      case Term.Block((stat: Term) :: Nil) => stat
      case _ => term
    }
    interpolate[Term, Term.Interpolate](arg = { () =>
      token match {
        case _: Ident   => termName()
        // case _: `_ ` => freshPlaceholder()       // ifonly etapolation
        case _: `{`     => dropTrivialBlock(expr()) // dropAnyBraces(expr0(Local))
        case _: `this`  => next(); atPos(in.prevTokenPos, auto)(Term.This(None))
        case _          => syntaxError("error in interpolated string: identifier or block expected", at = token)
      }
    }, result = Term.Interpolate(_, _, _))
  }

  def interpolatePat(): Pat.Interpolate =
    interpolate[Pat, Pat.Interpolate](arg = () => dropAnyBraces(pattern().require[Pat]), result = Pat.Interpolate(_, _, _))

/* ------------- NEW LINES ------------------------------------------------- */

  def newLineOpt(): Unit = {
    if (token.is[`\n`]) next()
  }

  def newLinesOpt(): Unit = {
    if (token.is[`\n`] || token.is[`\n\n`])
      next()
  }

  def newLineOptWhenFollowedBy[T: TokenMetadata]: Unit = {
    // note: next is defined here because current is token.`\n`
    if (token.is[`\n`] && ahead { token.is[T] }) newLineOpt()
  }

  def newLineOptWhenFollowing(p: Token => Boolean): Unit = {
    // note: next is defined here because current is token.`\n`
    if (token.is[`\n`] && ahead { p(token) }) newLineOpt()
  }

/* ------------- TYPES ---------------------------------------------------- */

  /** {{{
   *  TypedOpt ::= [`:' Type]
   *  }}}
   */
  def typedOpt(): Option[Type] =
    if (token.is[`:`]) { next(); Some(typ()) }
    else None

  def typeOrInfixType(location: Location): Type =
    if (location == Local) typ()
    else startInfixType()

  def annotTypeRest(t: Type): Type = atPos(t, auto) {
    val annots = this.annots(skipNewLines = false)
    if (annots.isEmpty) t
    else Type.Annotate(t, annots)
  }

/* ----------- EXPRESSIONS ------------------------------------------------ */

  def condExpr(): Term = {
    accept[`(`]
    val r = expr()
    accept[`)`]
    r
  }

  /** {{{
   *  Expr       ::= (Bindings | [`implicit'] Id | `_')  `=>' Expr
   *               | Expr1
   *  ResultExpr ::= (Bindings | Id `:' CompoundType) `=>' Block
   *               | Expr1
   *  Expr1      ::= if `(' Expr `)' {nl} Expr [[semi] else Expr]
   *               | try (`{' Block `}' | Expr) [catch `{' CaseClauses `}'] [finally Expr]
   *               | while `(' Expr `)' {nl} Expr
   *               | do Expr [semi] while `(' Expr `)'
   *               | for (`(' Enumerators `)' | `{' Enumerators `}') {nl} [yield] Expr
   *               | throw Expr
   *               | return [Expr]
   *               | [SimpleExpr `.'] Id `=' Expr
   *               | SimpleExpr1 ArgumentExprs `=' Expr
   *               | PostfixExpr Ascription
   *               | PostfixExpr match `{' CaseClauses `}'
   *  Bindings   ::= `(' [Binding {`,' Binding}] `)'
   *  Binding    ::= (Id | `_') [`:' Type]
   *  Ascription ::= `:' CompoundType
   *               | `:' Annotation {Annotation}
   *               | `:' `_' `*'
   *  }}}
   */
  def expr(): Term = expr(Local)

  def expr(location: Location): Term = autoPos(token match {
    case _: `if` =>
      next()
      val cond = condExpr()
      newLinesOpt()
      val thenp = expr()
      if (token.is[`else`]) { next(); Term.If(cond, thenp, expr()) }
      else if (token.is[`;`] && ahead { token.is[`else`] }) { next(); next(); Term.If(cond, thenp, expr()) }
      else { Term.If(cond, thenp, Lit.Unit()) }
    case _: `try` =>
      next()
      val body: Term = token match {
        case _: `{` => autoPos(inBracesOrUnit(block()))
        case _: `(` => inParensOrUnit(expr())
        case _      => expr()
      }
      val catchopt =
        if (token.isNot[`catch`]) None
        else {
          next()
          if (token.isNot[`{`]) Some(expr())
          else inBraces {
            if (token.is[`case`] && !ahead(token.is[`class `] || token.is[`object`])) Some(caseClauses())
            else Some(expr())
          }
        }
      val finallyopt = token match {
        case _: `finally` => next(); Some(expr())
        case _            => None
      }
      catchopt match {
        case None => Term.TryWithCases(body, Nil, finallyopt)
        case Some(cases: List[_]) => Term.TryWithCases(body, cases.require[List[Case]], finallyopt)
        case Some(term: Term) => Term.TryWithTerm(body, term, finallyopt)
        case _ => unreachable(debug(catchopt))
      }
    case _: `while` =>
      next()
      val cond = condExpr()
      newLinesOpt()
      val body = expr()
      Term.While(cond, body)
    case _: `do` =>
      next()
      val body = expr()
      if (token.is[StatSep]) next()
      accept[`while`]
      val cond = condExpr()
      Term.Do(body, cond)
    case _: `for` =>
      next()
      val enums =
        if (token.is[`{`]) inBracesOrNil(enumerators())
        else inParensOrNil(enumerators())
      newLinesOpt()
      if (token.is[`yield`]) {
        next()
        Term.ForYield(enums, expr())
      } else {
        Term.For(enums, expr())
      }
    case _: `return` =>
      next()
      if (token.is[ExprIntro]) Term.Return(expr())
      else Term.Return(atPos(in.tokenPos, auto)(Lit.Unit()))
    case _: `throw` =>
      next()
      Term.Throw(expr())
    case _: `implicit` =>
      next()
      implicitClosure(location)
    case _ =>
      var t: Term = postfixExpr()
      if (token.is[`=`]) {
        t match {
          case ref: Term.Ref =>
            next()
            t = Term.Assign(ref, expr())
          case app: Term.Apply =>
            def decompose(term: Term): (Term, List[List[Term.Arg]]) = term match {
              case Term.Apply(fun, args) =>
                val (core, otherArgss) = decompose(fun)
                (core, args.toList +: otherArgss)
              case term =>
                (term, Nil)
            }
            val (core, argss) = decompose(app)
            next()
            t = Term.Update(core, argss, expr())
          case _ =>
        }
      } else if (token.is[`:`]) {
        next()
        if (token.is[`@`]) {
          t = Term.Annotate(t, annots(skipNewLines = false))
        } else {
          t = {
            val tpt = typeOrInfixType(location)
            // this does not correspond to syntax, but is necessary to
            // accept closures. We might restrict closures to be between {...} only.
            Term.Ascribe(t, tpt)
          }
        }
      } else if (token.is[`match`]) {
        next()
        t = Term.Match(t, inBracesOrNil(caseClauses()))
      }
      // in order to allow anonymous functions as statements (as opposed to expressions) inside
      // templates, we have to disambiguate them from self type declarations - bug #1565
      // The case still missed is unparenthesized single argument, like "x: Int => x + 1", which
      // may be impossible to distinguish from a self-type and so remains an error.  (See #1564)
      def lhsIsTypedParamList() = t match {
        case Term.Tuple(xs) if xs.forall(_.isInstanceOf[Term.Ascribe]) => true
        case _ => false
      }
      if (token.is[`=>`] && (location != InTemplate || lhsIsTypedParamList)) {
        next()
        t = Term.Function(convertToParams(t), if (location != InBlock) expr() else block())
      }
      t
  })

  /** {{{
   *  Expr ::= implicit Id => Expr
   *  }}}
   */

  def implicitClosure(location: Location): Term.Function = {
    require(token.isNot[`implicit`] && debug(token))
    val implicitPos = in.prevTokenPos
    val paramName = termName()
    val paramTpt = if (token.is[`:`]) Some(typeOrInfixType(location)) else None
    val param = atPos(implicitPos, auto)(Term.Param(List(atPos(implicitPos, implicitPos)(Mod.Implicit())), paramName, paramTpt, None))
    accept[`=>`]
    atPos(implicitPos, auto)(Term.Function(List(param), if (location != InBlock) expr() else block()))
  }

  /** {{{
   *  PostfixExpr   ::= InfixExpr [Id [nl]]
   *  InfixExpr     ::= PrefixExpr
   *                  | InfixExpr Id [nl] InfixExpr
   *  }}}
   */
  def postfixExpr(): Term = {
    val ctx  = opctx[List[Term.Arg]]
    val base = ctx.stack

    def loop(top: List[Term.Arg]): List[Term.Arg] =
      if (!token.is[Ident]) top
      else {
        ctx.push(reduceStack(base, top))
        newLineOptWhenFollowing(_.is[ExprIntro])
        if (token.is[ExprIntro]) loop(argumentExprsOrPrefixExpr())
        else finishPostfixOp(base, ctx.pop())
      }

    reduceStack(base, loop(prefixExpr() :: Nil)) match {
      case (t: Term) :: Nil => t
      case other            => unreachable(debug(other))
    }
  }

  /** {{{
   *  PrefixExpr   ::= [`-' | `+' | `~' | `!' | `&'] SimpleExpr
   *  }}}
   */
  def prefixExpr(): Term =
    if (!isUnaryOp) simpleExpr()
    else {
      val op = termName()
      if (op.value == "-" && token.is[NumericLiteral])
        simpleExprRest(literal(isNegated = true), canApply = true)
      else
        atPos(op, auto)(Term.ApplyUnary(op, simpleExpr()))
    }

  def xmlLiteral(): Term

  /** {{{
   *  SimpleExpr    ::= new (ClassTemplate | TemplateBody)
   *                  |  BlockExpr
   *                  |  SimpleExpr1 [`_']
   *  SimpleExpr1   ::= literal
   *                  |  xLiteral
   *                  |  Path
   *                  |  `(' [Exprs] `)'
   *                  |  SimpleExpr `.' Id
   *                  |  SimpleExpr TypeArgs
   *                  |  SimpleExpr1 ArgumentExprs
   *  }}}
   */
  def simpleExpr(): Term = autoPos {
    var canApply = true
    val t: Term = {
      token match {
        case _: Literal =>
          literal()
        case _: Interpolation.Id =>
          interpolateTerm()
        case _: XMLStart =>
          xmlLiteral()
        case _: Ident | _: `this` | _: `super` =>
          path()
        case _: `_ ` =>
          next()
          Term.Placeholder()
        case _: `(` =>
          makeTupleTermParens(commaSeparated(expr()))
        case _: `{` =>
          canApply = false
          blockExpr()
        case _: `new` =>
          canApply = false
          next()
          Term.New(template())
        case _ =>
          syntaxError(s"illegal start of simple expression", at = token)
      }
    }
    simpleExprRest(t, canApply = canApply)
  }

  def simpleExprRest(t: Term, canApply: Boolean): Term = atPos(t, auto) {
    if (canApply) newLineOptWhenFollowedBy[`{`]
    token match {
      case _: `.` =>
        next()
        simpleExprRest(selector(t), canApply = true)
      case _: `[` =>
        t match {
          case _: Term.Name | _: Term.Select | _: Term.Apply =>
            var app: Term = t
            while (token.is[`[`])
              app = atPos(t, auto)(Term.ApplyType(app, exprTypeArgs()))

            simpleExprRest(app, canApply = true)
          case _ =>
            t
        }
      case _: `(` | _: `{` if (canApply) =>
        simpleExprRest(atPos(t, auto)(Term.Apply(t, argumentExprs())), canApply = true)
      case _: `_ ` =>
        next()
        Term.Eta(t)
      case _ =>
        t
    }
  }

  def argumentExprsOrPrefixExpr(): List[Term.Arg] = {
    if (token.isNot[`{`] && token.isNot[`(`]) prefixExpr() :: Nil
    else {
      def argsToTerm(args: List[Term.Arg], openParenPos: Int, closeParenPos: Int): Term = {
        def loop(args: List[Term.Arg]): List[Term] = args match {
          case Nil                              => Nil
          case (t: Term) :: rest                => t :: loop(rest)
          case (nmd: Term.Arg.Named) :: rest    => atPos(nmd, nmd)(Term.Assign(nmd.name, nmd.rhs)) :: loop(rest)
          case (rep: Term.Arg.Repeated) :: rest => syntaxError("repeated argument not allowed here", at = rep)
        }
        atPos(openParenPos, closeParenPos)(makeTupleTerm(loop(args)))
      }
      val openParenPos = in.tokenPos
      val args = argumentExprs()
      val closeParenPos = in.prevTokenPos
      token match {
        case _: `.` | _: `[` | _: `(` | _: `{` | _: `_ ` =>
          simpleExprRest(argsToTerm(args, openParenPos, closeParenPos), canApply = true) :: Nil
        case _ =>
          args
      }
    }
  }

  /** {{{
   *  ArgumentExprs ::= `(' [Exprs] `)'
   *                  | [nl] BlockExpr
   *  }}}
   */
  def argumentExprs(): List[Term.Arg] = {
    def args(): List[Term.Arg] = commaSeparated {
      expr() match {
        case Term.Ascribe(t, Type.Placeholder(Type.Bounds(None, None))) if isIdentOf("*") =>
          next()
          atPos(t, auto)(Term.Arg.Repeated(t))
        case Term.Assign(t: Term.Name, rhs) =>
          atPos(t, auto)(Term.Arg.Named(t, rhs))
        case other =>
          other
      }
    }
    token match {
      case _: `{` => List(blockExpr())
      case _: `(` => inParens(if (token.is[`)`]) Nil else args())
      case _      => Nil
    }
  }

  /** {{{
   *  BlockExpr ::= `{' (CaseClauses | Block) `}'
   *  }}}
   */
  def blockExpr(): Term = autoPos {
    inBraces {
      if (token.is[`case`] && !ahead(token.is[`class `] || token.is[`object`])) Term.PartialFunction(caseClauses())
      else block()
    }
  }

  /** {{{
   *  Block ::= BlockStatSeq
   *  }}}
   *  @note  Return tree does not carry position.
   */
  def block(): Term = autoPos {
    Term.Block(blockStatSeq())
  }

  def caseClause(): Case = atPos(in.prevTokenPos, auto) {
    require(token.isNot[`case`] && debug(token))
    Case(pattern().require[Pat], guard(), {
      accept[`=>`]
      autoPos(blockStatSeq() match {
        case List(blk: Term.Block) => blk
        case stats => Term.Block(stats)
      })
    })
  }

  /** {{{
   *  CaseClauses ::= CaseClause {CaseClause}
   *  CaseClause  ::= case Pattern [Guard] `=>' Block
   *  }}}
   */
  def caseClauses(): List[Case] = {
    val cases = caseSeparated { caseClause() }
    if (cases.isEmpty)  // trigger error if there are no cases
      accept[`case`]
    cases
  }

  /** {{{
   *  Guard ::= if PostfixExpr
   *  }}}
   */
  def guard(): Option[Term] =
    if (token.is[`if`]) { next(); Some(postfixExpr()) }
    else None

  /** {{{
   *  Enumerators ::= Generator {semi Enumerator}
   *  Enumerator  ::=  Generator
   *                |  Guard
   *                |  val Pattern1 `=' Expr
   *  }}}
   */
  def enumerators(): List[Enumerator] = {
    val enums = new ListBuffer[Enumerator]
    enums ++= enumerator(isFirst = true)
    while (token.is[StatSep]) {
      next()
      enums ++= enumerator(isFirst = false)
    }
    enums.toList
  }

  def enumerator(isFirst: Boolean, allowNestedIf: Boolean = true): List[Enumerator] =
    if (token.is[`if`] && !isFirst) autoPos(Enumerator.Guard(guard().get)) :: Nil
    else generator(!isFirst, allowNestedIf)

  /** {{{
   *  Generator ::= Pattern1 (`<-' | `=') Expr [Guard]
   *  }}}
   */
  def generator(eqOK: Boolean, allowNestedIf: Boolean = true): List[Enumerator] = {
    val startPos = in.tokenPos
    val hasVal = token.is[`val`]
    if (hasVal)
      next()

    val pat   = noSeq.pattern1()
    val point = token.start
    val hasEq = token.is[`=`]

    if (hasVal) {
      if (hasEq) deprecationWarning("val keyword in for comprehension is deprecated", at = token)
      else syntaxError("val in for comprehension must be followed by assignment", at = token)
    }

    if (hasEq && eqOK) next()
    else accept[`<-`]
    val rhs = expr()

    val head = {
      if (hasEq) atPos(startPos, auto)(Enumerator.Val(pat.require[Pat], rhs))
      else atPos(startPos, auto)(Enumerator.Generator(pat.require[Pat], rhs))
    }
    val tail = {
      def loop(): List[Enumerator] = {
        if (token.isNot[`if`]) Nil
        else autoPos(Enumerator.Guard(guard().get)) :: loop()
      }
      if (allowNestedIf) loop()
      else Nil
    }
    head :: tail
  }

/* -------- PATTERNS ------------------------------------------- */

  /** Methods which implicitly propagate whether the initial call took
   *  place in a context where sequences are allowed.  Formerly, this
   *  was threaded through methods as boolean seqOK.
   */
  trait SeqContextSensitive extends PatternContextSensitive {
    // is a sequence pattern _* allowed?
    def isSequenceOK: Boolean

    // are we in an XML pattern?
    def isXML: Boolean = false

    def functionArgType(): Type.Arg = paramType()
    def argType(): Type = typ()

    /** {{{
     *  Patterns ::= Pattern { `,' Pattern }
     *  SeqPatterns ::= SeqPattern { `,' SeqPattern }
     *  }}}
     */
    def patterns(): List[Pat.Arg] = commaSeparated(pattern())

    /** {{{
     *  Pattern  ::=  Pattern1 { `|' Pattern1 }
     *  SeqPattern ::= SeqPattern1 { `|' SeqPattern1 }
     *  }}}
     */
    def pattern(): Pat.Arg = {
      def loop(pat: Pat.Arg): Pat.Arg =
        if (!isRawBar) pat
        else { next(); atPos(pat, auto)(Pat.Alternative(pat.require[Pat], pattern().require[Pat])) }
      loop(pattern1())
    }

    /** {{{
     *  Pattern1    ::= varid `:' TypePat
     *                |  `_' `:' TypePat
     *                |  Pattern2
     *  SeqPattern1 ::= varid `:' TypePat
     *                |  `_' `:' TypePat
     *                |  [SeqPattern2]
     *  }}}
     */
    def pattern1(): Pat.Arg = autoPos {
      def patternType(): Pat.Type = {
        def convert(tpe: Type): Pat.Type = tpe match {
          case tpe: Type.Name => atPos(tpe, tpe)(Pat.Var.Type(tpe))
          case Type.Placeholder(Type.Bounds(None, None)) => atPos(tpe, tpe)(Pat.Type.Wildcard())
          case _ => loop(tpe)
        }
        def loop(tpe: Type): Pat.Type = tpe match {
          case tpe: Type.Name => tpe
          case tpe: Type.Select => tpe
          case Type.Project(qual, name) => atPos(tpe, tpe)(Pat.Type.Project(loop(qual), name))
          case tpe: Type.Singleton => tpe
          case Type.Apply(underlying, args) => atPos(tpe, tpe)(Pat.Type.Apply(loop(underlying), args.map(convert)))
          case Type.ApplyInfix(lhs, op, rhs) => atPos(tpe, tpe)(Pat.Type.ApplyInfix(loop(lhs), op, loop(rhs)))
          case Type.Function(params, res) => atPos(tpe, tpe)(Pat.Type.Function(params.map(p => loop(p.require[Type])), loop(res)))
          case Type.Tuple(elements) => atPos(tpe, tpe)(Pat.Type.Tuple(elements.map(loop)))
          case Type.Compound(tpes, refinement) => atPos(tpe, tpe)(Pat.Type.Compound(tpes.map(loop), refinement))
          case Type.Existential(underlying, quants) => atPos(tpe, tpe)(Pat.Type.Existential(loop(underlying), quants))
          case Type.Annotate(underlying, annots) => atPos(tpe, tpe)(Pat.Type.Annotate(loop(underlying), annots))
          case tpe: Type.Placeholder => tpe
          case tpe: Lit => tpe
        }
        loop(compoundType())
      }
      val p = pattern2()
      if (token.isNot[`:`]) p
      else {
        p match {
          case p: Term.Name =>
            syntaxError("Pattern variables must start with a lower-case letter. (SLS 8.1.1.)", at = p)
          case p: Pat.Var.Term if dialect.bindToSeqWildcardDesignator == ":" && isColonWildcardStar =>
            nextOnce()
            val wildcard = autoPos({ nextTwice(); Pat.Arg.SeqWildcard() })
            Pat.Bind(p, wildcard)
          case p: Pat.Var.Term =>
            nextOnce()
            Pat.Typed(p, patternType())
          case p: Pat.Wildcard if dialect.bindToSeqWildcardDesignator == ":" && isColonWildcardStar =>
            nextThrice()
            Pat.Arg.SeqWildcard()
          case p: Pat.Wildcard =>
            nextOnce()
            Pat.Typed(p, patternType())
          case p =>
            p
        }
      }
    }

    /** {{{
     *  Pattern2    ::=  varid [ @ Pattern3 ]
     *                |   Pattern3
     *  SeqPattern2 ::=  varid [ @ SeqPattern3 ]
     *                |   SeqPattern3
     *  }}}
     */
    def pattern2(): Pat.Arg = autoPos {
      val p = pattern3()
      if (token.isNot[`@`]) p
      else p match {
        case p: Term.Name =>
          syntaxError("Pattern variables must start with a lower-case letter. (SLS 8.1.1.)", at = p)
        case p: Pat.Var.Term =>
          next()
          Pat.Bind(p, pattern3())
        case p: Pat.Wildcard =>
          next()
          pattern3()
        case p =>
          p
      }
    }

    /** {{{
     *  Pattern3    ::= SimplePattern
     *                |  SimplePattern {Id [nl] SimplePattern}
     *  }}}
     */
    def pattern3(): Pat.Arg = {
      val top: Pat = simplePattern(badPattern3)
      val ctx = opctx[Pat]
      val base = ctx.stack
      // See SI-3189, SI-4832 for motivation. Cf SI-3480 for counter-motivation.
      def isCloseDelim = token match {
        case _: `}` => isXML
        case _: `)` => !isXML
        case _      => false
      }
      def checkWildStar: Option[Pat.Arg.SeqWildcard] = top match {
        case Pat.Wildcard() if isSequenceOK && isRawStar && dialect.bindToSeqWildcardDesignator == "@" => peekingAhead (
          // TODO: used to be Star(top) | EmptyTree, why start had param?
          if (isCloseDelim) Some(atPos(top, auto)(Pat.Arg.SeqWildcard()))
          else None
        )
        case _ => None
      }
      def loop(top: Pat): Pat = reduceStack(base, top) match {
        case next if isIdentExcept("|") => ctx.push(next); loop(simplePattern(badPattern3))
        case next                      => next
      }
      checkWildStar getOrElse loop(top)
    }

    def badPattern3(token: Token): Nothing = {
      def isComma                = token.is[`,`]
      def isDelimiter            = token.is[`)`] || token.is[`}`]
      def isCommaOrDelimiter     = isComma || isDelimiter
      val (isUnderscore, isStar) = opctx[Pat].stack match {
        case OpInfo(Pat.Wildcard(), Term.Name("*"), _) :: _ => (true,   true)
        case OpInfo(_, Term.Name("*"), _) :: _              => (false,  true)
        case _                                              => (false, false)
      }
      def isSeqPatternClose = isUnderscore && isStar && isSequenceOK && isDelimiter
      val preamble = "bad simple pattern:"
      val subtext = (isUnderscore, isStar, isSequenceOK) match {
        case (true,  true, true)  if isComma            => "bad use of _* (a sequence pattern must be the last pattern)"
        case (true,  true, true)  if isDelimiter        => "bad brace or paren after _*"
        case (true,  true, false) if isDelimiter        => "bad use of _* (sequence pattern not allowed)"
        case (false, true, true)  if isDelimiter        => "use _* to match a sequence"
        case (false, true, _)     if isCommaOrDelimiter => "trailing * is not a valid pattern"
        case _                                          => null
      }
      val msg = if (subtext != null) s"$preamble $subtext" else "illegal start of simple pattern"
      // better recovery if don't skip delims of patterns
      val skip = !isCommaOrDelimiter || isSeqPatternClose
      syntaxError(msg, at = token)
    }

    /** {{{
     *  SimplePattern    ::= varid
     *                    |  `_'
     *                    |  literal
     *                    |  XmlPattern
     *                    |  StableId  /[TypeArgs]/ [`(' [Patterns] `)']
     *                    |  StableId  [`(' [Patterns] `)']
     *                    |  StableId  [`(' [Patterns] `,' [varid `@'] `_' `*' `)']
     *                    |  `(' [Patterns] `)'
     *  }}}
     *
     * XXX: Hook for IDE
     */
    def simplePattern(): Pat =
      // simple diagnostics for this entry point
      simplePattern(token => syntaxError("illegal start of simple pattern", at = token))
    def simplePattern(onError: Token => Nothing): Pat = autoPos(token match {
      case _: Ident | _: `this` =>
        val isBackquoted = token.code.endsWith("`")
        val sid = stableId()
        val isVarPattern = sid match {
          case Term.Name(value) => !isBackquoted && value.head.isLower && value.head.isLetter
          case _ => false
        }
        if (token.is[NumericLiteral]) {
          sid match {
            case Term.Name("-") =>
              return literal(isNegated = true)
            case _ =>
          }
        }
        val targs = token match {
          case _: `[` => typeArgs()
          case _        => Nil
        }
        (token, sid) match {
          case (_: `(`, _)                          => Pat.Extract(sid, targs, argumentPatterns())
          case (_, _) if targs.nonEmpty             => syntaxError("pattern must be a value", at = token)
          case (_, name: Term.Name) if isVarPattern => Pat.Var.Term(name)
          case (_, name: Term.Name)                 => name
          case (_, select: Term.Select)             => select
          case _                                    => unreachable(debug(token, token.show[Raw], sid, sid.show[Raw]))
        }
      case _: `_ ` =>
        next()
        Pat.Wildcard()
      case _: Literal =>
        literal()
      case _: Interpolation.Id =>
        interpolatePat()
      case _: `(` =>
        makeTuplePatParens(noSeq.patterns())
      case _: XMLStart =>
        xmlLiteralPattern()
      case _ =>
        onError(token)
    })
  }
  /** The implementation of the context sensitive methods for parsing outside of patterns. */
  object outPattern extends PatternContextSensitive {
    def argType(): Type = typ()
    def functionArgType(): Type.Arg = paramType()
  }
  /** The implementation for parsing inside of patterns at points where sequences are allowed. */
  object seqOK extends SeqContextSensitive {
    val isSequenceOK = true
  }
  /** The implementation for parsing inside of patterns at points where sequences are disallowed. */
  object noSeq extends SeqContextSensitive {
    val isSequenceOK = false
  }
  /** For use from xml pattern, where sequence is allowed and encouraged. */
  object xmlSeqOK extends SeqContextSensitive {
    val isSequenceOK = true
    override val isXML = true
  }
  /** These are default entry points into the pattern context sensitive methods:
   *  they are all initiated from non-pattern context.
   */
  def typ(): Type      = outPattern.typ()
  def startInfixType() = outPattern.infixType(InfixMode.FirstOp)
  def startModType() = outPattern.annotType()
  def exprTypeArgs()   = outPattern.typeArgs()
  def exprSimpleType() = outPattern.simpleType()

  /** Default entry points into some pattern contexts. */
  def pattern(): Pat.Arg = noSeq.pattern()
  def seqPatterns(): List[Pat.Arg] = seqOK.patterns()
  def xmlSeqPatterns(): List[Pat.Arg] = xmlSeqOK.patterns() // Called from xml parser
  def argumentPatterns(): List[Pat.Arg] = inParens {
    if (token.is[`)`]) Nil
    else seqPatterns()
  }
  def xmlLiteralPattern(): Pat

/* -------- MODIFIERS and ANNOTATIONS ------------------------------------------- */

  /** {{{
   *  AccessModifier ::= (private | protected) [AccessQualifier]
   *  AccessQualifier ::= `[' (Id | this) `]'
   *  }}}
   */
  def accessModifierOpt(): Option[Mod] = {
    if (in.token.is[`private`] || in.token.is[`protected`]) {
      Some(autoPos {
        val mod = in.token match {
          case _: `private` => (name: Name.AccessBoundary) => Mod.Private(name)
          case _: `protected` => (name: Name.AccessBoundary) => Mod.Protected(name)
          case other => unreachable(debug(other, other.show[Raw]))
        }
        next()
        if (in.token.isNot[`[`]) mod(autoPos(Name.Anonymous()))
        else {
          next()
          val result = {
            if (in.token.is[`this`]) { next(); mod(atPos(in.prevTokenPos, auto)(Term.This(None))) }
            else { val name = termName(); mod(atPos(name, name)(Name.Indeterminate(name.value))) }
          }
          accept[`]`]
          result
        }
      })
    } else {
      None
    }
  }

  /** {{{
   *  Modifiers ::= {Modifier}
   *  Modifier  ::= LocalModifier
   *              |  AccessModifier
   *              |  override
   *  }}}
   */
  def modifiers(isLocal: Boolean = false): List[Mod] = {
    def addMod(mods: List[Mod], mod: Mod, advance: Boolean = true): List[Mod] = {
      mods.foreach { m => if (m.productPrefix == mod.productPrefix) syntaxError("repeated modifier", at = mod) }
      if (advance) next()
      mods :+ mod
    }
    def acceptable = if (isLocal) token.is[LocalModifier] else true
    def loop(mods: List[Mod]): List[Mod] =
      if (!acceptable) mods
      else (token match {
        case _: `abstract`  => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Abstract())))
        case _: `final`     => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Final())))
        case _: `sealed`    => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Sealed())))
        case _: `implicit`  => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Implicit())))
        case _: `lazy`      => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Lazy())))
        case _: `override`  => loop(addMod(mods, atPos(in.tokenPos, in.tokenPos)(Mod.Override())))
        case _: `private`
           | _: `protected` =>
          mods.filter(_.hasAccessBoundary).foreach(mod => syntaxError("duplicate access qualifier", at = mod))
          val optmod = accessModifierOpt()
          optmod.map { mod => loop(addMod(mods, mod, advance = false)) }.getOrElse(mods)
        case _: `\n` if !isLocal => next(); loop(mods)
        case _                   => mods
      })
    loop(Nil)
  }

  /** {{{
   *  LocalModifiers ::= {LocalModifier}
   *  LocalModifier  ::= abstract | final | sealed | implicit | lazy
   *  }}}
   */
  def localModifiers(): List[Mod] = modifiers(isLocal = true)

  /** {{{
   *  Annotations      ::= {`@' SimpleType {ArgumentExprs}}
   *  ConsrAnnotations ::= {`@' SimpleType ArgumentExprs}
   *  }}}
   */
  def annots(skipNewLines: Boolean): List[Mod.Annot] = readAnnots {
    require(token.isNot[`@`] && debug(token))
    val t = atPos(in.prevTokenPos, auto)(Mod.Annot(constructorCall(exprSimpleType, allowArgss = true)))
    if (skipNewLines) newLineOpt()
    t
  }

  def constructorAnnots(): List[Mod.Annot] = readAnnots {
    require(token.isNot[`@`] && debug(token))
    atPos(in.prevTokenPos, auto)(Mod.Annot(constructorCall(exprSimpleType(), allowArgss = false)))
  }

/* -------- PARAMETERS ------------------------------------------- */

  /** {{{
   *  ParamClauses      ::= {ParamClause} [[nl] `(' implicit Params `)']
   *  ParamClause       ::= [nl] `(' [Params] `)'
   *  Params            ::= Param {`,' Param}
   *  Param             ::= {Annotation} Id [`:' ParamType] [`=' Expr]
   *  ClassParamClauses ::= {ClassParamClause} [[nl] `(' implicit ClassParams `)']
   *  ClassParamClause  ::= [nl] `(' [ClassParams] `)'
   *  ClassParams       ::= ClassParam {`,' ClassParam}
   *  ClassParam        ::= {Annotation}  [{Modifier} (`val' | `var')] Id [`:' ParamType] [`=' Expr]
   *  }}}
   */
  def paramClauses(ownerIsType: Boolean, ownerIsCase: Boolean = false): List[List[Term.Param]] = {
    var parsedImplicits = false
    def paramClause(): List[Term.Param] = {
      if (token.is[`)`])
        return Nil

      if (token.is[`implicit`]) {
        next()
        parsedImplicits = true
      }
      commaSeparated(param(ownerIsCase, ownerIsType, isImplicit = parsedImplicits))
    }
    val paramss = new ListBuffer[List[Term.Param]]
    newLineOptWhenFollowedBy[`(`]
    while (!parsedImplicits && token.is[`(`]) {
      next()
      paramss += paramClause()
      accept[`)`]
      newLineOptWhenFollowedBy[`(`]
    }
    paramss.toList
  }

  /** {{{
   *  ParamType ::= Type | `=>' Type | Type `*'
   *  }}}
   */
  def paramType(): Type.Arg = autoPos(token match {
    case _: `=>` =>
      next()
      Type.Arg.ByName(typ())
    case _ =>
      val t = typ()
      if (!isRawStar) t
      else {
        next()
        Type.Arg.Repeated(t)
      }
  })

  def param(ownerIsCase: Boolean, ownerIsType: Boolean, isImplicit: Boolean): Term.Param = autoPos {
    var mods: List[Mod] = annots(skipNewLines = false)
    if (isImplicit) mods ++= List(atPos(in.tokenPos, in.prevTokenPos)(Mod.Implicit()))
    if (ownerIsType) {
      mods ++= modifiers()
      mods.getAll[Mod.Lazy].foreach { m =>
        syntaxError("lazy modifier not allowed here. Use call-by-name parameters instead", at = m)
      }
    }
    val (isValParam, isVarParam) = (ownerIsType && token.is[`val`], ownerIsType && token.is[`var`])
    if (isValParam) { mods :+= atPos(in.tokenPos, in.tokenPos)(Mod.ValParam()); next() }
    if (isVarParam) { mods :+= atPos(in.tokenPos, in.tokenPos)(Mod.VarParam()); next() }
    val name = termName()
    val tpt = {
      accept[`:`]
      val tpt = paramType()
      if (tpt.isInstanceOf[Type.Arg.ByName]) {
        def mayNotBeByName(subj: String) =
          syntaxError(s"$subj parameters may not be call-by-name", at = name)
        val isLocalToThis: Boolean = {
          val isExplicitlyLocal = mods.accessBoundary.map(_.isInstanceOf[Term.This]).getOrElse(false)
          if (ownerIsCase) isExplicitlyLocal
          else isExplicitlyLocal || (!isValParam && !isVarParam)
        }
        if (ownerIsType && !isLocalToThis) {
          if (isVarParam)
            mayNotBeByName("`var'")
          else
            mayNotBeByName("`val'")
        } else if (isImplicit)
          mayNotBeByName("implicit")
      }
      tpt
    }
    val default = {
      if (token.isNot[`=`]) None
      else {
        next()
        Some(expr())
      }
    }
    Term.Param(mods, name, Some(tpt), default)
  }

  /** {{{
   *  TypeParamClauseOpt    ::= [TypeParamClause]
   *  TypeParamClause       ::= `[' VariantTypeParam {`,' VariantTypeParam} `]']
   *  VariantTypeParam      ::= {Annotation} [`+' | `-'] TypeParam
   *  FunTypeParamClauseOpt ::= [FunTypeParamClause]
   *  FunTypeParamClause    ::= `[' TypeParam {`,' TypeParam} `]']
   *  TypeParam             ::= Id TypeParamClauseOpt TypeBounds {<% Type} {":" Type}
   *  }}}
   */
  def typeParamClauseOpt(ownerIsType: Boolean, ctxBoundsAllowed: Boolean): List[Type.Param] = {
    newLineOptWhenFollowedBy[`[`]
    if (token.isNot[`[`]) Nil
    else inBrackets(commaSeparated(typeParam(ownerIsType, ctxBoundsAllowed)))
  }

  def typeParam(ownerIsType: Boolean, ctxBoundsAllowed: Boolean): Type.Param = autoPos {
    var mods: List[Mod] = annots(skipNewLines = true)
    if (ownerIsType && token.is[Ident]) {
      if (isIdentOf("+")) {
        next()
        mods ++= List(atPos(in.prevTokenPos, in.prevTokenPos)(Mod.Covariant()))
      } else if (isIdentOf("-")) {
        next()
        mods ++= List(atPos(in.prevTokenPos, in.prevTokenPos)(Mod.Contravariant()))
      }
    }
    val nameopt =
      if (token.is[Ident]) typeName()
      else if (token.is[`_ `]) { next(); atPos(in.prevTokenPos, in.prevTokenPos)(Name.Anonymous()) }
      else syntaxError("identifier or `_' expected", at = token)
    val tparams = typeParamClauseOpt(ownerIsType = true, ctxBoundsAllowed = false)
    val typeBounds = this.typeBounds()
    val viewBounds = new ListBuffer[Type]
    val contextBounds = new ListBuffer[Type]
    if (ctxBoundsAllowed) {
      while (token.is[`<%`]) {
        // TODO: dialect?
        // if (settings.future) {
        //   val msg = ("Use an implicit parameter instead.\n" +
        //              "Example: Instead of `def f[A <% Int](a: A)` " +
        //              "use `def f[A](a: A)(implicit ev: A => Int)`.")
        //   deprecationWarning(s"View bounds are deprecated. $msg")
        // }
        next()
        viewBounds += typ()
      }
      while (token.is[`:`]) {
        next()
        contextBounds += typ()
      }
    }
    Type.Param(mods, nameopt, tparams, typeBounds, viewBounds.toList, contextBounds.toList)
  }

  /** {{{
   *  TypeBounds ::= [`>:' Type] [`<:' Type]
   *  }}}
   */
  def typeBounds() =
    autoPos(Type.Bounds(bound[`>:`], bound[`<:`]))

  def bound[T: TokenMetadata]: Option[Type] =
    if (token.is[T]) { next(); Some(typ()) } else None

/* -------- DEFS ------------------------------------------- */


  /** {{{
   *  Import  ::= import ImportExpr {`,' ImportExpr}
   *  }}}
   */
  def importStmt(): Import = autoPos {
    accept[`import`]
    Import(commaSeparated(importClause()))
  }

  /** {{{
   *  ImportExpr ::= StableId `.' (Id | `_' | ImportSelectors)
   *  }}}
   */
  def importClause(): Import.Clause = autoPos {
    val sid = stableId()
    def dotselectors = { accept[`.`]; Import.Clause(sid, importSelectors()) }
    sid match {
      case Term.Select(sid: Term.Ref, name: Term.Name) if sid.isStableId =>
        if (token.is[`.`]) dotselectors
        else Import.Clause(sid, atPos(name, name)(Import.Selector.Name(atPos(name, name)(Name.Imported(name.value)))) :: Nil)
      case _ => dotselectors
    }
  }

  /** {{{
   *  ImportSelectors ::= `{' {ImportSelector `,'} (ImportSelector | `_') `}'
   *  }}}
   */
  def importSelectors(): List[Import.Selector] =
    if (token.isNot[`{`]) List(importWildcardOrName())
    else inBraces(commaSeparated(importSelector()))

  def importWildcardOrName(): Import.Selector = autoPos {
    if (token.is[`_ `]) { next(); Import.Selector.Wildcard() }
    else { val name = termName(); Import.Selector.Name(atPos(name, name)(Name.Imported(name.value))) }
  }

  /** {{{
   *  ImportSelector ::= Id [`=>' Id | `=>' `_']
   *  }}}
   */
  def importSelector(): Import.Selector = autoPos {
    importWildcardOrName() match {
      case from: Import.Selector.Name if token.is[`=>`] =>
        next()
        importWildcardOrName() match {
          case to: Import.Selector.Name     => Import.Selector.Rename(from.value, to.value)
          case to: Import.Selector.Wildcard => Import.Selector.Unimport(from.value)
          case other                        => unreachable(debug(other, other.show[Raw]))
        }
      case other => other
    }
  }

  def nonLocalDefOrDcl: Stat = {
    val anns = annots(skipNewLines = true)
    val mods = anns ++ modifiers()
    defOrDclOrSecondaryCtor(mods) match {
      case s if s.isTemplateStat => s
      case other                 => syntaxError("is not a valid template statement", at = other)
    }
  }

  /** {{{
   *  Def    ::= val PatDef
   *           | var PatDef
   *           | def FunDef
   *           | type [nl] TypeDef
   *           | TmplDef
   *  Dcl    ::= val PatDcl
   *           | var PatDcl
   *           | def FunDcl
   *           | type [nl] TypeDcl
   *  }}}
   */
  def defOrDclOrSecondaryCtor(mods: List[Mod]): Stat = {
    mods.getAll[Mod.Lazy].foreach { m =>
      if (token.isNot[`val`]) syntaxError("lazy not allowed here. Only vals can be lazy", at = m)
    }
    token match {
      case _: `val` | _: `var` =>
        patDefOrDcl(mods)
      case _: `def` =>
        funDefOrDclOrSecondaryCtor(mods)
      case _: `type` =>
        typeDefOrDcl(mods)
      case _ =>
        tmplDef(mods)
    }
  }

  /** {{{
   *  PatDef ::= Pattern2 {`,' Pattern2} [`:' Type] `=' Expr
   *  ValDcl ::= Id {`,' Id} `:' Type
   *  VarDef ::= PatDef | Id {`,' Id} `:' Type `=' `_'
   *  }}}
   */
  def patDefOrDcl(mods: List[Mod]): Stat = atPos(mods, auto) {
    val isMutable = token.is[`var`]
    next()
    val lhs: List[Pat] = commaSeparated(noSeq.pattern2().require[Pat]).map {
      // TODO: val `x`: Int should be parsed as Decl.Val(..., Pat.Var.Term(Term.Name("x")), ...)
      case name: Term.Name => atPos(name, name)(Pat.Var.Term(name))
      case pat => pat
    }
    val tp: Option[Type] = typedOpt()

    if (tp.isEmpty || token.is[`=`]) {
      accept[`=`]
      val rhs =
        if (token.is[`_ `] && tp.nonEmpty && isMutable && lhs.forall(_.isInstanceOf[Pat.Var.Term])) {
          next()
          None
        } else Some(expr())

      if (isMutable) Defn.Var(mods, lhs, tp, rhs)
      else Defn.Val(mods, lhs, tp, rhs.get)
    } else {
      mods.getAll[Mod.Lazy].foreach { m => syntaxError("lazy values may not be abstract", at = m) }
      val ids = lhs.map {
        case name: Pat.Var.Term => name
        case other              => syntaxError("pattern definition may not be abstract", at = other)
      }

      if (isMutable) Decl.Var(mods, ids, tp.get)
      else Decl.Val(mods, ids, tp.get)
    }
  }

  /** {{{
   *  FunDef ::= FunSig [`:' Type] `=' [`macro'] Expr
   *          |  FunSig [nl] `{' Block `}'
   *          |  `this' ParamClause ParamClauses
   *                 (`=' ConstrExpr | [nl] ConstrBlock)
   *  FunDcl ::= FunSig [`:' Type]
   *  FunSig ::= id [FunTypeParamClause] ParamClauses
   *  }}}
   */
  def funDefOrDclOrSecondaryCtor(mods: List[Mod]): Stat = {
    if (ahead(token.isNot[`this`])) funDefRest(mods)
    else secondaryCtor(mods)
  }

  def funDefRest(mods: List[Mod]): Stat = atPos(mods, auto) {
    accept[`def`]
    val name = termName()
    def warnProcedureDeprecation =
      deprecationWarning(s"Procedure syntax is deprecated. Convert procedure `$name` to method by adding `: Unit`.", at = name)
    val tparams = typeParamClauseOpt(ownerIsType = false, ctxBoundsAllowed = true)
    val paramss = paramClauses(ownerIsType = false).require[Seq[Seq[Term.Param]]]
    newLineOptWhenFollowedBy[`{`]
    var restype = fromWithinReturnType(typedOpt())
    if (token.is[StatSep] || token.is[`}`]) {
      if (restype.isEmpty) {
        warnProcedureDeprecation
        Decl.Def(mods, name, tparams, paramss, Type.Name("Unit")) // TODO: hygiene!
      } else
        Decl.Def(mods, name, tparams, paramss, restype.get)
    } else if (restype.isEmpty && token.is[`{`]) {
      warnProcedureDeprecation
      Defn.Def(mods, name, tparams, paramss, Some(Type.Name("Unit")), expr()) // TODO: hygiene!
    } else {
      var isMacro = false
      val rhs = {
        if (token.is[`=`]) {
          next()
          isMacro = token.is[`macro`]
          if (isMacro) next()
        } else {
          accept[`=`]
        }
        expr()
      }
      if (isMacro) restype match {
        case Some(restype) => Defn.Macro(mods, name, tparams, paramss, restype, rhs)
        case None          => syntaxError("macros must have explicitly specified return types", at = name)
      } else Defn.Def(mods, name, tparams, paramss, restype, rhs)
    }
  }

  /** {{{
   *  TypeDef ::= type Id [TypeParamClause] `=' Type
   *            | FunSig `=' Expr
   *  TypeDcl ::= type Id [TypeParamClause] TypeBounds
   *  }}}
   */
  def typeDefOrDcl(mods: List[Mod]): Member.Type with Stat = atPos(mods, auto) {
    accept[`type`]
    newLinesOpt()
    val name = typeName()
    val tparams = typeParamClauseOpt(ownerIsType = true, ctxBoundsAllowed = false)
    token match {
      case _: `=` =>
        next()
        Defn.Type(mods, name, tparams, typ())
      case _: `>:` | _: `<:` | _: `,` | _: `}` | _: StatSep =>
        Decl.Type(mods, name, tparams, typeBounds())
      case _ =>
        syntaxError("`=', `>:', or `<:' expected", at = token)
    }
  }

  /** Hook for IDE, for top-level classes/objects. */
  def topLevelTmplDef: Member with Stat =
    tmplDef(annots(skipNewLines = true) ++ modifiers())

  /** {{{
   *  TmplDef ::= [case] class ClassDef
   *            |  [case] object ObjectDef
   *            |  [override] trait TraitDef
   *  }}}
   */
  def tmplDef(mods: List[Mod]): Member with Stat = {
    mods.getAll[Mod.Lazy].foreach { m => syntaxError("classes cannot be lazy", at = m) }
    token match {
      case _: `trait` =>
        traitDef(mods)
      case _: `class ` =>
        classDef(mods)
      case _: `case` if ahead(token.is[`class `]) =>
        val casePos = in.tokenPos
        next()
        classDef(mods :+ atPos(casePos, casePos)(Mod.Case()))
      case _: `object` =>
        objectDef(mods)
      case _: `case` if ahead(token.is[`object`])=>
        val casePos = in.tokenPos
        next()
        objectDef(mods :+ atPos(casePos, casePos)(Mod.Case()))
      case _ =>
        syntaxError(s"expected start of definition", at = token)
    }
  }

  /** {{{
   *  TraitDef ::= Id [TypeParamClause] RequiresTypeOpt TraitTemplateOpt
   *  }}}
   */
  def traitDef(mods: List[Mod]): Defn.Trait = atPos(mods, auto) {
    accept[`trait`]
    Defn.Trait(mods, typeName(),
               typeParamClauseOpt(ownerIsType = true, ctxBoundsAllowed = false),
               primaryCtor(OwnedByTrait),
               templateOpt(OwnedByTrait))
  }

  /** {{{
   *  ClassDef ::= Id [TypeParamClause] {Annotation}
   *               [AccessModifier] ClassParamClauses RequiresTypeOpt ClassTemplateOpt
   *  }}}
   */
  def classDef(mods: List[Mod]): Defn.Class = atPos(mods, auto) {
    accept[`class `]
    // TODO:
    // if (ofCaseClass && token.isNot[`(`])
    //  syntaxError(token.offset, "case classes without a parameter list are not allowed;\n"+
    //                             "use either case objects or case classes with an explicit `()' as a parameter list.")
    // TODO:
    // if (owner == nme.CONSTRUCTOR && (result.isEmpty || (result.head take 1 exists (_.mods.isImplicit)))) {
    //  token match {
    //    case _: `[` => syntaxError("no type parameters allowed here")
    //    case _: EOF => incompleteInputError("auxiliary constructor needs non-implicit parameter list")
    //    case _      => syntaxError(start, "auxiliary constructor needs non-implicit parameter list")
    //  }
    // }
    Defn.Class(mods, typeName(),
               typeParamClauseOpt(ownerIsType = true, ctxBoundsAllowed = true),
               primaryCtor(if (mods.has[Mod.Case]) OwnedByCaseClass else OwnedByClass), templateOpt(OwnedByClass))
  }

  /** {{{
   *  ObjectDef       ::= Id ClassTemplateOpt
   *  }}}
   */
  def objectDef(mods: List[Mod]): Defn.Object = atPos(mods, auto) {
    accept[`object`]
    Defn.Object(mods, termName(), primaryCtor(OwnedByObject), templateOpt(OwnedByObject))
  }

/* -------- CONSTRUCTORS ------------------------------------------- */

  // TODO: we need to store some string in Ctor.Name in order to represent constructor calls (which also use Ctor.Name)
  // however, when representing constructor defns, we can't easily figure out that name
  // a natural desire would be to have this name equal to the name of the enclosing class/trait/object
  // but unfortunately we can't do that, because we can create ctors in isolation from their enclosures
  // therefore, I'm going to use `Term.Name("this")` here for the time being

  def primaryCtor(owner: TemplateOwner): Ctor.Primary = autoPos {
    if (!owner.isClass) return Ctor.Primary(Nil, atPos(in.tokenPos, in.prevTokenPos)(Ctor.Name("this")), Nil)
    val mods = constructorAnnots() ++ accessModifierOpt()
    val name = atPos(in.tokenPos, in.prevTokenPos)(Ctor.Name("this"))
    val paramss = paramClauses(ownerIsType = true, owner == OwnedByCaseClass)
    Ctor.Primary(mods, name, paramss)
  }

  def secondaryCtor(mods: List[Mod]): Ctor.Secondary = atPos(mods, auto) {
    accept[`def`]
    val thisPos = in.tokenPos
    accept[`this`]
    // TODO: ownerIsType = true is most likely a bug here
    // secondary constructors can't have val/var parameters
    val paramss = paramClauses(ownerIsType = true).require[Seq[Seq[Term.Param]]]
    newLineOptWhenFollowedBy[`{`]
    val body = token match {
      case _: `{` => constrBlock()
      case _      => accept[`=`]; constrExpr()
    }
    Ctor.Secondary(mods, atPos(thisPos, thisPos)(Ctor.Name("this")), paramss, body)
  }

  /** {{{
   *  ConstrBlock    ::=  `{' SelfInvocation {semi BlockStat} `}'
   *  }}}
   */
  def constrBlock(): Term.Block = autoPos {
    accept[`{`]
    val supercall = selfInvocation()
    val stats =
      if (!token.is[StatSep]) Nil
      else { next(); blockStatSeq() }
    accept[`}`]
    Term.Block(supercall +: stats)
  }

  /** {{{
   *  ConstrExpr      ::=  SelfInvocation
   *                    |  ConstrBlock
   *  }}}
   */
  def constrExpr(): Term =
    if (token.is[`{`]) constrBlock()
    else selfInvocation()

  /** {{{
   *  SelfInvocation  ::= this ArgumentExprs {ArgumentExprs}
   *  }}}
   */
  def selfInvocation(): Term = {
    var result: Term = autoPos(Ctor.Name("this"))
    accept[`this`]
    newLineOptWhenFollowedBy[`{`]
    while (token.is[`(`] || token.is[`{`]) {
      result = atPos(result, auto)(Term.Apply(result, argumentExprs()))
      newLineOptWhenFollowedBy[`{`]
    }
    result
  }

  def constructorCall(tpe: Type, allowArgss: Boolean = true): Term = {
    object Types {
      def unapply(tpes: Seq[Type.Arg]): Option[Seq[Type]] = {
        if (tpes.forall(_.isInstanceOf[Type])) Some(tpes.map(_.require[Type]))
        else None
      }
    }
    def convert(tpe: Type): Term = {
      // TODO: we should really think of a different representation for constructor invocations...
      // if anything, this mkCtorRefFunction is a testament to how problematic our current encoding is
      // the Type.ApplyInfix => Term.ApplyType conversion is weird as well
      def mkCtorRefFunction(tpe: Type) = {
        val origin = tpe.origin.require[Origin.Parsed]
        val allTokens = origin.input.tokens(dialect).zipWithIndex
        val arrowPos = allTokens.indexWhere{ case (el, i) => el.is[`=>`] && origin.startTokenPos <= i && i <= origin.endTokenPos }
        atPos(arrowPos, arrowPos)(Ctor.Ref.Function(atPos(arrowPos, arrowPos)(Ctor.Name("=>"))))
      }
      atPos(tpe, tpe)(tpe match {
        case Type.Name(value) => Ctor.Name(value)
        case Type.Select(qual, name) => Ctor.Ref.Select(qual, atPos(name, name)(Ctor.Name(name.value)))
        case Type.Project(qual, name) => Ctor.Ref.Project(qual, atPos(name, name)(Ctor.Name(name.value)))
        case Type.Function(Types(params), ret) => Term.ApplyType(mkCtorRefFunction(tpe), params :+ ret)
        case Type.Annotate(tpe, annots) => Term.Annotate(convert(tpe), annots)
        case Type.Apply(tpe, args) => Term.ApplyType(convert(tpe), args)
        case Type.ApplyInfix(lhs, op, rhs) => Term.ApplyType(convert(op), List(lhs, rhs))
        case _ => syntaxError("this type can't be used in a constructor call", at = tpe)
      })
    }
    var result: Term = convert(tpe)
    var done = false
    while (token.is[`(`] && !done) {
      result = atPos(result, auto)(Term.Apply(result, argumentExprs()))
      if (!allowArgss) done = true
    }
    result
  }

/* -------- TEMPLATES ------------------------------------------- */

  sealed trait TemplateOwner {
    def isTerm = this eq OwnedByObject
    def isClass = (this eq OwnedByCaseClass) || (this eq OwnedByClass)
    def isTrait = this eq OwnedByTrait
  }
  object OwnedByTrait extends TemplateOwner
  object OwnedByCaseClass extends TemplateOwner
  object OwnedByClass extends TemplateOwner
  object OwnedByObject extends TemplateOwner

  /** {{{
   *  ClassParents       ::= ModType {`(' [Exprs] `)'} {with ModType}
   *  TraitParents       ::= ModType {with ModType}
   *  }}}
   */
  def templateParents(): List[Term] = {
    val parents = new ListBuffer[Term]
    def readAppliedParent() = parents += constructorCall(startModType())
    readAppliedParent()
    while (token.is[`with`]) { next(); readAppliedParent() }
    parents.toList
  }

  /** {{{
   *  ClassTemplate ::= [EarlyDefs with] ClassParents [TemplateBody]
   *  TraitTemplate ::= [EarlyDefs with] TraitParents [TemplateBody]
   *  EarlyDefs     ::= `{' [EarlyDef {semi EarlyDef}] `}'
   *  EarlyDef      ::= Annotations Modifiers PatDef
   *  }}}
   */
  def template(): Template = autoPos {
    newLineOptWhenFollowedBy[`{`]
    if (token.is[`{`]) {
      // @S: pre template body cannot stub like post body can!
      val (self, body) = templateBody(isPre = true)
      if (token.is[`with`] && self.name.isInstanceOf[Name.Anonymous] && self.decltpe.isEmpty) {
        val edefs = body.map(ensureEarlyDef)
        next()
        val parents = templateParents()
        val (self1, body1) = templateBodyOpt(parenMeansSyntaxError = false)
        Template(edefs, parents, self1, body1)
      } else {
        Template(Nil, Nil, self, Some(body))
      }
    } else {
      val parents = templateParents()
      val (self, body) = templateBodyOpt(parenMeansSyntaxError = false)
      Template(Nil, parents, self, body)
    }
  }

  def ensureEarlyDef(tree: Stat): Stat = tree match {
    case v: Defn.Val => v
    case v: Defn.Var => v
    case t: Defn.Type =>
      syntaxError("early type members are not allowed any longer. " +
                  "Move them to the regular body: the semantics are the same.",
                  at = t)
    case other =>
      syntaxError("not a valid early definition", at = other)
  }

  /** {{{
   *  ClassTemplateOpt ::= `extends' ClassTemplate | [[`extends'] TemplateBody]
   *  TraitTemplateOpt ::= TraitExtends TraitTemplate | [[`extends'] TemplateBody] | `<:' TemplateBody
   *  TraitExtends     ::= `extends' | `<:'
   *  }}}
   */
  def templateOpt(owner: TemplateOwner): Template = {
    if (token.is[`extends`] /* || token.is[`<:`] && mods.isTrait */) {
      next()
      template()
    } else {
      val startPos = in.tokenPos
      val (self, body) = templateBodyOpt(parenMeansSyntaxError = !owner.isClass)
      atPos(startPos, auto)(Template(Nil, Nil, self, body))
    }
  }

  /** {{{
   *  TemplateBody ::= [nl] `{' TemplateStatSeq `}'
   *  }}}
   * @param isPre specifies whether in early initializer (true) or not (false)
   */
  def templateBody(isPre: Boolean): (Term.Param, List[Stat]) =
    inBraces(templateStatSeq(isPre = isPre))

  def templateBodyOpt(parenMeansSyntaxError: Boolean): (Term.Param, Option[List[Stat]]) = {
    newLineOptWhenFollowedBy[`{`]
    if (token.is[`{`]) {
      val (self, body) = templateBody(isPre = false)
      (self, Some(body))
    } else {
      if (token.is[`(`]) {
        if (parenMeansSyntaxError) syntaxError("traits or objects may not have parameters", at = token)
        else syntaxError("unexpected opening parenthesis", at = token)
      }
      (autoPos(Term.Param(Nil, autoPos(Name.Anonymous()), None, None)), None)
    }
  }

  /** {{{
   *  Refinement ::= [nl] `{' RefineStat {semi RefineStat} `}'
   *  }}}
   */
  def refinement(): List[Stat] = inBraces(refineStatSeq())

  def existentialStats(): List[Stat] = refinement() map {
    case stat if stat.isExistentialStat => stat
    case other                          => syntaxError("not a legal existential clause", at = other)
  }

/* -------- STATSEQS ------------------------------------------- */

  def statSeq[T <: Tree](statpf: PartialFunction[Token, T],
                         errorMsg: String = "illegal start of definition"): List[T] = {
    val stats = new ListBuffer[T]
    while (!token.is[StatSeqEnd]) {
      if (statpf.isDefinedAt(token)) stats += statpf(token)
      else if (!token.is[StatSep]) syntaxError(errorMsg, at = token)
      acceptStatSepOpt()
    }
    stats.toList
  }

  /** {{{
   *  TopStatSeq ::= TopStat {semi TopStat}
   *  TopStat ::= Annotations Modifiers TmplDef
   *            | Packaging
   *            | package object objectDef
   *            | Import
   *            |
   *  }}}
   */
  def topStatSeq(): List[Stat] = statSeq(topStat, errorMsg = "expected class or object definition")
  def topStat: PartialFunction[Token, Stat] = {
    case _: `package `  =>
      packageOrPackageObjectDef()
    case _: `import` =>
      importStmt()
    case _: `@` | _: TemplateIntro | _: Modifier =>
      topLevelTmplDef
  }

  /** {{{
   *  TemplateStatSeq  ::= [id [`:' Type] `=>'] TemplateStats
   *  }}}
   * @param isPre specifies whether in early initializer (true) or not (false)
   */
  def templateStatSeq(isPre : Boolean): (Term.Param, List[Stat]) = {
    var self: Term.Param = autoPos(Term.Param(Nil, autoPos(Name.Anonymous()), None, None))
    var firstOpt: Option[Term] = None
    if (token.is[ExprIntro]) {
      val first = expr(InTemplate) // @S: first statement is potentially converted so cannot be stubbed.
      if (token.is[`=>`]) {
        first match {
          case name: Term.Placeholder =>
            self = atPos(first, first)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), None, None))
          case name @ Term.This(None) =>
            self = atPos(first, first)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), None, None))
          case name: Term.Name =>
            self = atPos(first, first)(Term.Param(Nil, name, None, None))
          case Term.Ascribe(name: Term.Placeholder, tpt) =>
            self = atPos(first, first)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), Some(tpt), None))
          case Term.Ascribe(name @ Term.This(None), tpt) =>
            self = atPos(first, first)(Term.Param(Nil, atPos(name, name)(Name.Anonymous()), Some(tpt), None))
          case Term.Ascribe(name: Term.Name, tpt) =>
            self = atPos(first, first)(Term.Param(Nil, name, Some(tpt), None))
          case _ =>
        }
        next()
      } else {
        firstOpt = Some(first)
        acceptStatSepOpt()
      }
    }
    (self, firstOpt ++: templateStats())
  }

  /** {{{
   *  TemplateStats    ::= TemplateStat {semi TemplateStat}
   *  TemplateStat     ::= Import
   *                     | Annotations Modifiers Def
   *                     | Annotations Modifiers Dcl
   *                     | Expr1
   *                     | super ArgumentExprs {ArgumentExprs}
   *                     |
   *  }}}
   */
  def templateStats(): List[Stat] = statSeq(templateStat)
  def templateStat: PartialFunction[Token, Stat] = {
    case _: `import` =>
      importStmt()
    case _: DefIntro | _: Modifier | _: `@` =>
      nonLocalDefOrDcl
    case _: ExprIntro =>
      expr(InTemplate)
  }

  /** {{{
   *  RefineStatSeq    ::= RefineStat {semi RefineStat}
   *  RefineStat       ::= Dcl
   *                     | type TypeDef
   *                     |
   *  }}}
   */
  def refineStatSeq(): List[Stat] = {
    val stats = new ListBuffer[Stat]
    while (!token.is[StatSeqEnd]) {
      stats ++= refineStat()
      if (token.isNot[`}`]) acceptStatSep()
    }
    stats.toList
  }

  def refineStat(): Option[Stat] =
    if (token.is[DclIntro]) {
      defOrDclOrSecondaryCtor(Nil) match {
        case stat if stat.isRefineStat => Some(stat)
        case other                     => syntaxError("is not a valid refinement declaration", at = other)
      }
    } else if (!token.is[StatSep]) {
      syntaxError(
        "illegal start of declaration"+
        (if (inFunReturnType) " (possible cause: missing `=' in front of current method body)"
         else ""), at = token)
    } else None

  def localDef(implicitMod: Option[Mod.Implicit]): Stat = {
    val mods = (implicitMod ++: annots(skipNewLines = true)) ++ localModifiers()
    if (mods forall { case _: Mod.Implicit | _: Mod.Lazy | _: Mod.Annot => true; case _ => false })
      (defOrDclOrSecondaryCtor(mods) match {
        case stat if stat.isBlockStat => stat
        case other                    => syntaxError("is not a valid block statement", at = other)
      })
    else
      (tmplDef(mods) match {
        case stat if stat.isBlockStat => stat
        case other                    => syntaxError("is not a valid block statement", at = other)
      })
  }

  /** {{{
   *  BlockStatSeq ::= { BlockStat semi } [ResultExpr]
   *  BlockStat    ::= Import
   *                 | Annotations [implicit] [lazy] Def
   *                 | Annotations LocalModifiers TmplDef
   *                 | Expr1
   *                 |
   *  }}}
   */
  def blockStatSeq(): List[Stat] = {
    val stats = new ListBuffer[Stat]
    while ((!token.is[StatSeqEnd] && !token.is[CaseDefEnd]) ||
           (token.is[`case`] && ahead(token.is[`class `] || token.is[`object`]))) {
      if (token.is[`import`]) {
        stats += importStmt()
        acceptStatSepOpt()
      }
      else if (token.is[DefIntro] || token.is[LocalModifier] || token.is[`@`]) {
        if (token.is[`implicit`]) {
          val implicitPos = in.tokenPos
          next()
          if (token.is[Ident]) stats += implicitClosure(InBlock)
          else stats += localDef(Some(atPos(implicitPos, implicitPos)(Mod.Implicit())))
        } else {
          stats += localDef(None)
        }
        acceptStatSepOpt()
      }
      else if (token.is[ExprIntro]) {
        stats += expr(InBlock)
        if (!token.is[CaseDefEnd]) acceptStatSep()
      }
      else if (token.is[StatSep]) {
        next()
      }
      else {
        val addendum = if (token.is[Modifier]) " (no modifiers allowed here)" else ""
        syntaxError("illegal start of statement" + addendum, at = token)
      }
    }
    stats.toList
  }


  def packageOrPackageObjectDef(): Stat = autoPos {
    require(token.is[`package `] && debug(token))
    if (ahead(token.is[`object`])) packageObjectDef()
    else packageDef()
  }

  def packageDef(): Pkg = autoPos {
    accept[`package `]
    Pkg(qualId(), inBracesOrNil(topStatSeq()))
  }

  def packageObjectDef(): Pkg.Object = autoPos {
    accept[`package `]
    accept[`object`]
    Pkg.Object(Nil, termName(), primaryCtor(OwnedByObject), templateOpt(OwnedByObject))
  }

  /** {{{
   *  CompilationUnit ::= {package QualId semi} TopStatSeq
   *  }}}
   */
  def compilationUnit(): Source = autoPos {
    def bracelessPackageStats(): Seq[Stat] = {
      if (token.is[EOF]) {
        Nil
      } else if (token.is[StatSep]) {
        next()
        bracelessPackageStats()
      } else if (token.is[`package `] && ahead(token.is[`object`])) {
        val startPos = in.tokenPos
        accept[`package `]
        val qid = qualId()
        if (token.is[`{`]) {
          val pkg = atPos(startPos, auto)(Pkg(qid, inBraces(topStatSeq())))
          acceptStatSepOpt()
          pkg +: bracelessPackageStats()
        } else {
          List(atPos(startPos, auto)(Pkg(qid, bracelessPackageStats())))
        }
      } else if (token.is[`{`]) {
        inBraces(topStatSeq())
      } else {
        topStatSeq()
      }
    }
    if (token.is[`package `] && !ahead(token.is[`object`])) {
      val startPos = in.tokenPos
      accept[`package `]
      Source(List(atPos(startPos, auto)(Pkg(qualId(), bracelessPackageStats()))))
    } else {
      Source(topStatSeq())
    }
  }
}
