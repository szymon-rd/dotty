package dotty.tools.dotc
package printing

import core._
import Texts._, Types._, Flags._, Names._, Symbols._, NameOps._, Constants._, Denotations._
import StdNames._
import Contexts._
import Scopes.Scope, Denotations.Denotation, Annotations.Annotation
import StdNames.nme
import ast.Trees._
import typer.Implicits._
import typer.ImportInfo
import Variances.varianceSign
import util.SourcePosition
import scala.util.control.NonFatal
import scala.annotation.switch

class PlainPrinter(_ctx: Context) extends Printer {
  /** The context of all public methods in Printer and subclasses.
   *  Overridden in RefinedPrinter.
   */
  protected def curCtx: Context = _ctx.addMode(Mode.Printing)
  protected given [DummyToEnforceDef]: Context = curCtx

  protected def printDebug = ctx.settings.YprintDebug.value

  private var openRecs: List[RecType] = Nil

  protected def maxToTextRecursions: Int = 100

  protected def showUniqueIds = ctx.settings.uniqid.value || Printer.debugPrintUnique

  protected final def limiter: MessageLimiter = ctx.property(MessageLimiter).get

  protected def controlled(op: => Text): Text = limiter.controlled(op)

  def Str(str: String, lineRange: LineRange = EmptyLineRange): Str =
    limiter.register(str)
    Texts.Str(str, lineRange)

  given stringToText: Conversion[String, Text] = Str(_)

  /** If true, tweak output so it is the same before and after pickling */
  protected def homogenizedView: Boolean = ctx.settings.YtestPickler.value
  protected def debugPos: Boolean = ctx.settings.YdebugPos.value

  def homogenize(tp: Type): Type =
    if (homogenizedView)
      tp match {
        case tp: ThisType if tp.cls.is(Package) && !tp.cls.isEffectiveRoot =>
          requiredPackage(tp.cls.fullName).termRef
        case tp: TypeVar if tp.isInstantiated =>
          homogenize(tp.instanceOpt)
        case AndType(tp1, tp2) =>
          homogenize(tp1) & homogenize(tp2)
        case OrType(tp1, tp2) =>
          homogenize(tp1) | homogenize(tp2)
        case AnnotatedType(parent, annot)
        if !ctx.mode.is(Mode.Type) && annot.symbol == defn.UncheckedVarianceAnnot =>
          homogenize(parent)
        case tp: SkolemType =>
          homogenize(tp.info)
        case tp: LazyRef =>
          homogenize(tp.ref)
        case tp @ AppliedType(tycon, args) =>
          if (defn.isCompiletimeAppliedType(tycon.typeSymbol)) tp.tryCompiletimeConstantFold
          else tycon.dealias.appliedTo(args)
        case _ =>
          tp
      }
    else tp

  private def sameBound(lo: Type, hi: Type): Boolean =
    try lo frozen_=:= hi catch { case NonFatal(ex) => false }

  private def homogenizeArg(tp: Type) = tp match {
    case TypeBounds(lo, hi) if homogenizedView && sameBound(lo, hi) => homogenize(hi)
    case _ => tp
  }

  private def selfRecName(n: Int) = s"z$n"

  /** If the name of the symbol's owner should be used when you care about
   *  seeing an interesting name: in such cases this symbol is e.g. a method
   *  parameter with a synthetic name, a constructor named "this", an object
   *  "package", etc.  The kind string, if non-empty, will be phrased relative
   *  to the name of the owner.
   */
  protected def hasMeaninglessName(sym: Symbol): Boolean = (
       sym.is(Param) && sym.owner.isSetter    // x$1
    || sym.isClassConstructor                  // this
    || (sym.name == nme.PACKAGE)               // package
  )

  def nameString(name: Name): String =
    if (name eq tpnme.FromJavaObject) && !printDebug
    then nameString(tpnme.Object)
    else name.toString

  def toText(name: Name): Text = Str(nameString(name))

  /** String representation of a name used in a refinement
   *  In refined printing this undoes type parameter expansion
   */
  protected def refinementNameString(tp: RefinedType): String = nameString(tp.refinedName)

  /** String representation of a refinement */
  protected def toTextRefinement(rt: RefinedType): Closed =
    (refinementNameString(rt) ~ toTextRHS(rt.refinedInfo)).close

  protected def argText(arg: Type): Text = homogenizeArg(arg) match {
    case arg: TypeBounds => "?" ~ toText(arg)
    case arg => toText(arg)
  }

  /** Pretty-print comma-separated type arguments for a constructor to be inserted among parentheses or brackets
    * (hence with `GlobalPrec` precedence).
    */
  protected def argsText(args: List[Type]): Text =
    atPrec(GlobalPrec) { Text(args.map(arg => argText(arg) ), ", ") }

  /** The longest sequence of refinement types, starting at given type
   *  and following parents.
   */
  private def refinementChain(tp: Type): List[Type] =
    tp :: (tp match {
      case tp: RefinedType => refinementChain(tp.parent.stripTypeVar)
      case _ => Nil
    })

  /** Direct references to these symbols are printed without their prefix for convenience.
   *  They are either aliased in scala.Predef or in the scala package object, as well as `Object`
   */
  private lazy val printWithoutPrefix: Set[Symbol] =
    (defn.ScalaPredefModule.termRef.typeAliasMembers
      ++ defn.ScalaPackageObject.termRef.typeAliasMembers).map(_.info.classSymbol).toSet
    + defn.ObjectClass
    + defn.FromJavaObjectSymbol

  def toText(tp: Type): Text = controlled {
    homogenize(tp) match {
      case tp: TypeType =>
        toTextRHS(tp)
      case tp: TermRef
      if !tp.denotationIsCurrent && !homogenizedView || // always print underlying when testing picklers
         tp.symbol.is(Module) || tp.symbol.name == nme.IMPORT =>
        toTextRef(tp) ~ ".type"
      case tp: TermRef if tp.denot.isOverloaded =>
        "<overloaded " ~ toTextRef(tp) ~ ">"
      case tp: TypeRef =>
        if (printWithoutPrefix.contains(tp.symbol))
          toText(tp.name)
        else
          toTextPrefix(tp.prefix) ~ selectionString(tp)
      case tp: TermParamRef =>
        ParamRefNameString(tp) ~ lambdaHash(tp.binder) ~ ".type"
      case tp: TypeParamRef =>
        ParamRefNameString(tp) ~ lambdaHash(tp.binder)
      case tp: SingletonType =>
        toTextSingleton(tp)
      case AppliedType(tycon, args) =>
        (toTextLocal(tycon) ~ "[" ~ argsText(args) ~ "]").close
      case tp: RefinedType =>
        val parent :: (refined: List[RefinedType @unchecked]) =
          refinementChain(tp).reverse
        toTextLocal(parent) ~ "{" ~ Text(refined map toTextRefinement, "; ").close ~ "}"
      case tp: RecType =>
        try {
          openRecs = tp :: openRecs
          "{" ~ selfRecName(openRecs.length) ~ " => " ~ toTextGlobal(tp.parent) ~ "}"
        }
        finally openRecs = openRecs.tail
      case AndType(tp1, tp2) =>
        changePrec(AndTypePrec) { toText(tp1) ~ " & " ~ atPrec(AndTypePrec + 1) { toText(tp2) } }
      case OrType(tp1, tp2) =>
        changePrec(OrTypePrec) { toText(tp1) ~ " | " ~ atPrec(OrTypePrec + 1) { toText(tp2) } }
      case MatchType(bound, scrutinee, cases) =>
        changePrec(GlobalPrec) {
          def caseText(tp: Type): Text = tp match {
            case tp: HKTypeLambda => caseText(tp.resultType)
            case defn.MatchCase(pat, body) => "case " ~ toText(pat) ~ " => " ~ toText(body)
            case _ => "case " ~ toText(tp)
          }
          def casesText = Text(cases.map(caseText), "\n")
          atPrec(InfixPrec) { toText(scrutinee) } ~
          keywordStr(" match ") ~ "{" ~ casesText ~ "}" ~
          (" <: " ~ toText(bound) provided !bound.isAny)
        }.close
      case tp: PreviousErrorType if ctx.settings.XprintTypes.value =>
        "<error>" // do not print previously reported error message because they may try to print this error type again recuresevely
      case tp: ErrorType =>
        s"<error ${tp.msg.rawMessage}>"
      case tp: WildcardType =>
        if (tp.optBounds.exists) "<?" ~ toTextRHS(tp.bounds) ~ ">" else "<?>"
      case NoType =>
        "<notype>"
      case NoPrefix =>
        "<noprefix>"
      case tp: MethodType =>
        changePrec(GlobalPrec) {
          "("
          ~ keywordText("using ").provided(tp.isContextualMethod)
          ~ keywordText("erased ").provided(tp.isErasedMethod)
          ~ keywordText("implicit ").provided(tp.isImplicitMethod && !tp.isContextualMethod)
          ~ paramsText(tp)
          ~ (if tp.resultType.isInstanceOf[MethodType] then ")" else "): ")
          ~ toText(tp.resultType)
        }
      case tp: ExprType =>
        changePrec(GlobalPrec) { "=> " ~ toText(tp.resultType) }
      case tp: HKTypeLambda =>
        changePrec(GlobalPrec) {
          "[" ~ paramsText(tp) ~ "]" ~ lambdaHash(tp) ~ Str(" =>> ") ~ toTextGlobal(tp.resultType)
        }
      case tp: PolyType =>
        changePrec(GlobalPrec) {
          "[" ~ paramsText(tp) ~ "]" ~ lambdaHash(tp) ~
          (Str(" => ") provided !tp.resultType.isInstanceOf[MethodType]) ~
          toTextGlobal(tp.resultType)
        }
      case AnnotatedType(tpe, annot) =>
        if annot.symbol == defn.InlineParamAnnot || annot.symbol == defn.ErasedParamAnnot then toText(tpe)
        else toTextLocal(tpe) ~ " " ~ toText(annot)
      case tp: TypeVar =>
        if (tp.isInstantiated)
          toTextLocal(tp.instanceOpt) ~ (Str("^") provided printDebug)
        else {
          val constr = ctx.typerState.constraint
          val bounds =
            if constr.contains(tp) then
              withMode(Mode.Printing)(TypeComparer.fullBounds(tp.origin))
            else
              TypeBounds.empty
          if (bounds.isTypeAlias) toText(bounds.lo) ~ (Str("^") provided printDebug)
          else if (ctx.settings.YshowVarBounds.value) "(" ~ toText(tp.origin) ~ "?" ~ toText(bounds) ~ ")"
          else toText(tp.origin)
        }
      case tp: LazyRef =>
        def refTxt =
          try toTextGlobal(tp.ref)
          catch {
            case ex: Throwable => Str("...")
          }
        "LazyRef(" ~ refTxt ~ ")"
      case _ =>
        tp.fallbackToText(this)
    }
  }.close

  def toTextSingleton(tp: SingletonType): Text =
    "(" ~ toTextRef(tp) ~ " : " ~ toTextGlobal(tp.underlying) ~ ")"

  protected def paramsText(lam: LambdaType): Text = {
    def paramText(name: Name, tp: Type) =
      toText(name) ~ lambdaHash(lam) ~ toTextRHS(tp, isParameter = true)
    Text(lam.paramNames.lazyZip(lam.paramInfos).map(paramText), ", ")
  }

  protected def ParamRefNameString(name: Name): String = nameString(name)

  protected def ParamRefNameString(param: ParamRef): String =
    ParamRefNameString(param.binder.paramNames(param.paramNum))

  /** The name of the symbol without a unique id. */
  protected def simpleNameString(sym: Symbol): String = nameString(sym.name)

  /** If -uniqid is set, the hashcode of the lambda type, after a # */
  protected def lambdaHash(pt: LambdaType): Text =
    if (showUniqueIds)
      try "#" + pt.hashCode
      catch { case ex: NullPointerException => "" }
    else ""

  /** If -uniqid is set, the unique id of symbol, after a # */
  protected def idString(sym: Symbol): String =
    if (showUniqueIds || Printer.debugPrintUnique) "#" + sym.id else ""

  def nameString(sym: Symbol): String =
    simpleNameString(sym) + idString(sym) // + "<" + (if (sym.exists) sym.owner else "") + ">"

  def fullNameString(sym: Symbol): String =
    if (sym eq defn.FromJavaObjectSymbol) && !printDebug then
      fullNameString(defn.ObjectClass)
    else if sym.isRoot || sym == NoSymbol || sym.owner.isEffectiveRoot then
      nameString(sym)
    else
      fullNameString(fullNameOwner(sym)) + "." + nameString(sym)

  protected def fullNameOwner(sym: Symbol): Symbol = sym.effectiveOwner.enclosingClass

  protected def objectPrefix: String = "object "
  protected def packagePrefix: String = "package "

  protected def trimPrefix(text: Text): Text =
    text.stripPrefix(objectPrefix).stripPrefix(packagePrefix)

  protected def selectionString(tp: NamedType): String = {
    val sym = if (homogenizedView) tp.symbol else tp.currentSymbol
    if (sym.exists) nameString(sym) else nameString(tp.name)
  }

  /** The string representation of this type used as a prefix */
  def toTextRef(tp: SingletonType): Text = controlled {
    tp match {
      case tp: TermRef =>
        toTextPrefix(tp.prefix) ~ selectionString(tp)
      case tp: ThisType =>
        nameString(tp.cls) + ".this"
      case SuperType(thistpe: SingletonType, _) =>
        toTextRef(thistpe).map(_.replaceAll("""\bthis$""", "super"))
      case SuperType(thistpe, _) =>
        "Super(" ~ toTextGlobal(thistpe) ~ ")"
      case tp @ ConstantType(value) =>
        toText(value)
      case pref: TermParamRef =>
        nameString(pref.binder.paramNames(pref.paramNum))
      case tp: RecThis =>
        val idx = openRecs.reverse.indexOf(tp.binder)
        if (idx >= 0) selfRecName(idx + 1)
        else "{...}.this" // TODO move underlying type to an addendum, e.g. ... z3 ... where z3: ...
      case tp: SkolemType =>
        if (homogenizedView) toText(tp.info)
        else if (ctx.settings.XprintTypes.value) "<" ~ toText(tp.repr) ~ ":" ~ toText(tp.info) ~ ">"
        else toText(tp.repr)
    }
  }

  /** The string representation of this type used as a prefix, including separator */
  def toTextPrefix(tp: Type): Text = controlled {
    homogenize(tp) match {
      case NoPrefix => ""
      case tp: SingletonType => toTextRef(tp) ~ "."
      case tp => trimPrefix(toTextLocal(tp)) ~ "#"
    }
  }

  protected def isOmittablePrefix(sym: Symbol): Boolean =
    defn.unqualifiedOwnerTypes.exists(_.symbol == sym) || isEmptyPrefix(sym)

  protected def isEmptyPrefix(sym: Symbol): Boolean =
    sym.isEffectiveRoot || sym.isAnonymousClass || sym.name.isReplWrapperName

  /** String representation of a definition's type following its name,
   *  if symbol is completed, "?" otherwise.
   */
  protected def toTextRHS(optType: Option[Type]): Text = optType match {
    case Some(tp) => toTextRHS(tp)
    case None => "?"
  }

  protected def decomposeLambdas(bounds: TypeBounds): (String, TypeBounds) =
    def decompose(tp: Type) = tp.stripTypeVar match
      case lam: HKTypeLambda =>
        val names =
          if lam.isDeclaredVarianceLambda then
            lam.paramNames.lazyZip(lam.declaredVariances).map((name, v) =>
              varianceSign(v) + name)
          else lam.paramNames
        (names.mkString("[", ", ", "]"), lam.resType)
      case _ =>
        ("", tp)
    bounds match
      case bounds: AliasingBounds =>
        val (tparamStr, aliasRhs) = decompose(bounds.alias)
        (tparamStr, bounds.derivedAlias(aliasRhs))
      case TypeBounds(lo, hi) =>
        val (_, loRhs) = decompose(lo)
        val (tparamStr, hiRhs) = decompose(hi)
        (tparamStr, bounds.derivedTypeBounds(loRhs, hiRhs))
  end decomposeLambdas

  /** String representation of a definition's type following its name */
  protected def toTextRHS(tp: Type, isParameter: Boolean = false): Text = controlled {
    homogenize(tp) match {
      case tp: TypeBounds =>
        val (tparamStr, rhs) = decomposeLambdas(tp)
        val binder = rhs match
          case tp: AliasingBounds =>
            " = " ~ toText(tp.alias)
          case TypeBounds(lo, hi) =>
            (if (lo isRef defn.NothingClass) Text() else " >: " ~ toText(lo))
            ~ (if hi.isAny || (!printDebug && hi.isFromJavaObject) then Text() else " <: " ~ toText(hi))
        tparamStr ~ binder
      case tp @ ClassInfo(pre, cls, cparents, decls, selfInfo) =>
        val preText = toTextLocal(pre)
        val (tparams, otherDecls) = decls.toList partition treatAsTypeParam
        val tparamsText =
          if (tparams.isEmpty) Text() else ("[" ~ dclsText(tparams) ~ "]").close
        val selfText: Text = selfInfo match {
          case NoType => Text()
          case sym: Symbol if !sym.isCompleted => "this: ? =>"
          case _ => "this: " ~ atPrec(InfixPrec) { toText(tp.selfType) } ~ " =>"
        }
        val trueDecls = otherDecls.filterNot(treatAsTypeArg)
        val declsText =
          if (trueDecls.isEmpty || !ctx.settings.Ydebug.value) Text()
          else dclsText(trueDecls)
        tparamsText ~ " extends " ~ toTextParents(tp.parents) ~~ "{" ~ selfText ~ declsText ~
          "} at " ~ preText
      case mt: MethodType =>
        toTextGlobal(mt)
      case tp: ExprType =>
        // parameterless methods require special treatment, see #11201
        (if (isParameter) ": => " else ": ") ~ toTextGlobal(tp.widenExpr)
      case tp =>
        ": " ~ toTextGlobal(tp)
    }
  }

  protected def toTextParents(parents: List[Type]): Text = Text(parents.map(toTextLocal), " with ")

  protected def treatAsTypeParam(sym: Symbol): Boolean = false
  protected def treatAsTypeArg(sym: Symbol): Boolean = false

  /** String representation of symbol's kind. */
  def kindString(sym: Symbol): String = {
    val flags = sym.flagsUNSAFE
    if (flags.is(PackageClass)) "package class"
    else if (flags.is(PackageVal)) "package"
    else if (sym.isPackageObject)
      if (sym.isClass) "package object class"
      else "package object"
    else if (sym.isAnonymousClass) "anonymous class"
    else if (flags.is(ModuleClass)) "object class"
    else if (flags.is(ModuleVal)) "object"
    else if (flags.is(Trait)) "trait"
    else if (sym.isClass) "class"
    else if (sym.isType) "type"
    else if (sym.isGetter) "getter"
    else if (sym.isSetter) "setter"
    else if sym.is(Param) then "parameter"
    else if sym.is(Given) then "given instance"
    else if (flags.is(Lazy)) "lazy value"
    else if (flags.is(Mutable)) "variable"
    else if (sym.isClassConstructor && sym.isPrimaryConstructor) "primary constructor"
    else if (sym.isClassConstructor) "constructor"
    else if (sym.is(Method)) "method"
    else if (sym.isTerm) "value"
    else ""
  }

  /** String representation of symbol's definition keyword */
  protected def keyString(sym: Symbol): String = {
    val flags = sym.flagsUNSAFE
    if (flags.isAllOf(JavaInterface)) "interface"
    else if (flags.is(Trait)) "trait"
    else if (flags.is(Module)) "object"
    else if (sym.isClass) "class"
    else if (sym.isType) "type"
    else if (flags.is(Mutable)) "var"
    else if (flags.is(Package)) "package"
    else if (sym.is(Method)) "def"
    else if (sym.isTerm && !flags.is(Param)) "val"
    else ""
  }

  protected def privateWithinString(sym: Symbol): String =
    if (sym.exists && sym.privateWithin.exists)
      nameString(sym.privateWithin.name.stripModuleClassSuffix)
    else ""

  /** String representation of symbol's flags */
  protected def toTextFlags(sym: Symbol): Text = toTextFlags(sym, sym.flagsUNSAFE)

  protected def toTextFlags(sym: Symbol, flags: FlagSet): Text =
    Text(flags.flagStrings(privateWithinString(sym)).map(flag => stringToText(keywordStr(flag))), " ")

  def annotsText(sym: Symbol): Text = Text(sym.annotations.map(toText))

  def dclText(sym: Symbol): Text = dclTextWithInfo(sym, sym.unforcedInfo)

  def dclText(d: SingleDenotation): Text = dclTextWithInfo(d.symbol, Some(d.info))

  private def dclTextWithInfo(sym: Symbol, info: Option[Type]): Text =
    (toTextFlags(sym) ~~ keyString(sym) ~~
      (varianceSign(sym.variance) ~ nameString(sym)) ~ toTextRHS(info)).close

  def toText(sym: Symbol): Text =
    (kindString(sym) ~~ {
      if (sym.isAnonymousClass) toTextParents(sym.info.parents) ~~ "{...}"
      else if (hasMeaninglessName(sym) && !printDebug) simpleNameString(sym.owner) + idString(sym)
      else if sym.is(Package) then fullNameString(sym)
      else nameString(sym)
    }).close

  def locationText(sym: Symbol): Text =
    if (!sym.exists) ""
    else {
      val ownr = sym.effectiveOwner
      if (ownr.isClass && !isEmptyPrefix(ownr)) " in " ~ toText(ownr) else Text()
    }

  def locatedText(sym: Symbol): Text =
    (toText(sym) ~ locationText(sym)).close

  def extendedLocationText(sym: Symbol): Text =
    if (!sym.exists) ""
    else {
      def recur(ownr: Symbol, innerLocation: String): Text = {
        def nextOuter(innerKind: String): Text =
          recur(ownr.effectiveOwner,
            if (!innerLocation.isEmpty) innerLocation
            else s" in an anonymous $innerKind")
        def showLocation(ownr: Symbol, where: String): Text =
          innerLocation ~ " " ~ where ~ " " ~ toText(ownr)
        if (ownr.isAnonymousClass) nextOuter("class")
        else if (ownr.isAnonymousFunction) nextOuter("function")
        else if (isEmptyPrefix(ownr)) ""
        else if (ownr.isLocalDummy) showLocation(ownr.owner, "locally defined in")
        else if (ownr.isTerm && !ownr.isOneOf(Module | Method)) showLocation(ownr, "in the initializer of")
        else showLocation(ownr, "in")
      }
      recur(sym.owner, "")
    }

  def toText(denot: Denotation): Text = toText(denot.symbol) ~ "/D"

  private def escapedChar(ch: Char): String = (ch: @switch) match {
    case '\b' => "\\b"
    case '\t' => "\\t"
    case '\n' => "\\n"
    case '\f' => "\\f"
    case '\r' => "\\r"
    case '"' => "\\\""
    case '\'' => "\\\'"
    case '\\' => "\\\\"
    case _ => if (ch.isControl) f"\u${ch.toInt}%04x" else String.valueOf(ch)
  }

  def toText(const: Constant): Text = const.tag match {
    case StringTag => stringText("\"" + escapedString(const.value.toString) + "\"")
    case ClazzTag => "classOf[" ~ toText(const.typeValue) ~ "]"
    case CharTag => literalText(s"'${escapedChar(const.charValue)}'")
    case LongTag => literalText(const.longValue.toString + "L")
    case DoubleTag => literalText(const.doubleValue.toString + "d")
    case FloatTag => literalText(const.floatValue.toString + "f")
    case _ => literalText(String.valueOf(const.value))
  }

  def toText(annot: Annotation): Text = s"@${annot.symbol.name}" // for now

  def toText(param: LambdaParam): Text =
    varianceSign(param.paramVariance)
    ~ toText(param.paramName)
    ~ (if param.isTypeParam then "" else ": ")
    ~ toText(param.paramInfo)

  protected def escapedString(str: String): String = str flatMap escapedChar

  def dclsText(syms: List[Symbol], sep: String): Text = Text(syms map dclText, sep)

  def toText(sc: Scope): Text =
    ("Scope{" ~ dclsText(sc.toList) ~ "}").close

  def toText[T >: Untyped](tree: Tree[T]): Text = {
    def toTextElem(elem: Any): Text = elem match {
      case elem: Showable => elem.toText(this)
      case elem: List[?] => "List(" ~ Text(elem map toTextElem, ",") ~ ")"
      case elem => elem.toString
    }
    val nodeName = tree.productPrefix
    val elems =
      Text(tree.productIterator.map(toTextElem).toList, ", ")
    val tpSuffix =
      if (ctx.settings.XprintTypes.value && tree.hasType)
        " | " ~ toText(tree.typeOpt)
      else
        Text()

    nodeName ~ "(" ~ elems ~ tpSuffix ~ ")" ~ (Str(tree.sourcePos.toString) provided printDebug)
  }.close // todo: override in refined printer

  def toText(pos: SourcePosition): Text =
    if (!pos.exists) "<no position>"
    else if (pos.source.exists) s"${pos.source.file.name}:${pos.line + 1}"
    else s"(no source file, offset = ${pos.span.point})"

  def toText(result: SearchResult): Text = result match {
    case result: SearchSuccess =>
      "SearchSuccess: " ~ toText(result.ref) ~ " via " ~ toText(result.tree)
    case result: SearchFailure =>
      result.reason match {
        case _: NoMatchingImplicits => "No Matching Implicit"
        case _: DivergingImplicit => "Diverging Implicit"
        case result: AmbiguousImplicits =>
          "Ambiguous Implicit: " ~ toText(result.alt1.ref) ~ " and " ~ toText(result.alt2.ref)
        case _ =>
          "Search Failure: " ~ toText(result.tree)
      }
  }

  def toText(importInfo: ImportInfo): Text =
    val siteStr = importInfo.site.show
    val exprStr = if siteStr.endsWith(".type") then siteStr.dropRight(5) else siteStr
    val selectorStr = importInfo.selectors match
      case sel :: Nil if sel.renamed.isEmpty && sel.bound.isEmpty =>
        if sel.isGiven then "given" else sel.name.show
      case _ => "{...}"
    s"import $exprStr.$selectorStr"

  def plain: PlainPrinter = this

  protected def keywordStr(text: String): String = coloredStr(text, SyntaxHighlighting.KeywordColor)
  protected def keywordText(text: String): Text = coloredStr(text, SyntaxHighlighting.KeywordColor)
  protected def valDefText(text: Text): Text = coloredText(text, SyntaxHighlighting.ValDefColor)
  protected def typeText(text: Text): Text = coloredText(text, SyntaxHighlighting.TypeColor)
  protected def literalText(text: Text): Text = coloredText(text, SyntaxHighlighting.LiteralColor)
  protected def stringText(text: Text): Text = coloredText(text, SyntaxHighlighting.StringColor)

  protected def coloredStr(text: String, color: String): String =
    if (ctx.useColors) color + text + SyntaxHighlighting.NoColor else text
  protected def coloredText(text: Text, color: String): Text =
    if (ctx.useColors) color ~ text ~ SyntaxHighlighting.NoColor else text
}

