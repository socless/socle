package socle.probe

import chisel3._
import chisel3.aop.Select
import chisel3.experimental.{BaseModule, SourceLine}
import chisel3.reflect.DataMirror
import socle.capture._

import scala.collection.mutable
import scala.reflect.runtime.{universe => ru}
import scala.util.control.NonFatal

/** Walks an elaborated Chisel design (the live Scala objects) into a
  * [[CapturedModel]]. Uses only public Chisel API plus plain reflection; what
  * cannot be read is reported (`"<unreadable>"`, a `problems` entry), never guessed.
  */
object ModelWalk {

  val Unreadable = "<unreadable>"

  def capture(dut: RawModule, chirrtl: String): CapturedModel = {
    val problems = mutable.ArrayBuffer.empty[String]
    val seen     = mutable.LinkedHashMap.empty[String, CModule]

    // Depth-first from the DUT; one CModule per distinct module name, top first.
    def visit(m: BaseModule): Unit = if (!seen.contains(m.name)) {
      val children = attempt(m, "instances", problems) {
        Select.instances(m).map(c => c -> CInstance(c.instanceName, c.name))
      }.getOrElse(Nil)
      // `wires` stays empty: Chisel's public Select.connectionsTo throws on
      // literal-driven connects, returns unnamed temporaries the CHIRRTL
      // pipeline traces through, and never sees a port read by logic, so the
      // consumer derives wiring from `chirrtl` (socle.capture.WiresFromChirrtl).
      seen(m.name) = CModule(
        name       = m.name,
        scalaClass = m.getClass.getName,
        params     = attempt(m, "params", problems)(paramsOf(m, problems)).getOrElse(Nil),
        source     = attempt(m, "source", problems)(sourceOf(m)).flatten,
        ports      = attempt(m, "ports", problems)(portsOf(m)).getOrElse(Nil),
        instances  = children.map(_._2),
      )
      children.foreach { case (c, _) => visit(c) }
    }
    visit(dut)

    CapturedModel(
      status   = "ok",
      top      = dut.name,
      modules  = seen.values.toSeq,
      chirrtl  = chirrtl,
      problems = problems.toSeq,
    )
  }

  /** Run one per-module read; on failure record it under the module's name. */
  private def attempt[A](m: BaseModule, what: String, problems: mutable.Buffer[String])(body: => A): Option[A] =
    try Some(body)
    catch { case NonFatal(e) => problems += s"${m.name}: cannot read $what: $e"; None }

  // ── Constructor parameters ────────────────────────────────────────────────
  // Names and types come from the primary constructor (Scala runtime
  // reflection). A value comes from the arguments the agent recorded for that
  // constructor (CtorArgs, origin "ctor"). Without them (no agent, or recorded
  // arguments that do not line up with the parameters) it is read back only
  // through a same-named zero-arg method declared by the module's own class,
  // i.e. when the parameter is a val (origin "val"; an inherited member like
  // BaseModule.name must not stand in for a plain `name` parameter).
  // Otherwise it is "<unreadable>".
  //
  // An anonymous subclass (`new Fifo(4) { ... }`) shows the parameters and
  // arguments of its nearest named superclass: its own constructor only
  // passes captured values.
  private def paramsOf(m: BaseModule, problems: mutable.Buffer[String]): Seq[CParam] = {
    val mirror = ru.runtimeMirror(m.getClass.getClassLoader)
    val named  = namedClass(m.getClass)
    CtorArgs.of(m, named).flatMap(fromArgs(mirror, named, _)) match {
      case Some(params) => capParams(m.name, params, problems)
      case None =>
        val ctor = mirror.classSymbol(m.getClass).primaryConstructor
        if (ctor == ru.NoSymbol) Nil
        else ctor.asMethod.paramLists.flatten.map { p =>
          val name = p.name.decodedName.toString
          valueOf(m, p.name.encodedName.toString) match {
            case Some(v) =>
              CParam(name, p.typeSignature.toString, truncate(String.valueOf(v)), "val", ParamValues.digest(v, name))
            case None => CParam(name, p.typeSignature.toString, Unreadable)
          }
        }
    }
  }

  /** The class itself, or its nearest superclass that is not anonymous. */
  private def namedClass(c: Class[_]): Class[_] =
    if ((c.isAnonymousClass || c.getName.contains("$anon")) && c.getSuperclass != null) namedClass(c.getSuperclass)
    else c

  /** The recorded arguments of `cls`'s primary constructor, one per parameter
    * of all its lists (implicit ones included), after the outer instance an
    * inner class takes first. None when they do not line up. */
  private def fromArgs(mirror: ru.Mirror, cls: Class[_], args: Array[AnyRef]): Option[Seq[CParam]] = {
    val ctor = mirror.classSymbol(cls).primaryConstructor
    if (ctor == ru.NoSymbol) None
    else {
      val params    = ctor.asMethod.paramLists.flatten
      val enclosing = cls.getEnclosingClass
      // An outer module (a module class declared in another) is held weakly.
      def outer = args(0) match {
        case a: CtorArgs.ModuleArg => a.get
        case a                     => a
      }
      val values =
        if (args.length == params.length) Some(args.toSeq)
        else if (args.length == params.length + 1 && enclosing != null && enclosing.isInstance(outer)) Some(args.toSeq.tail)
        else None
      values.map(_.zip(params).map { case (v, p) =>
        val name = p.name.decodedName.toString
        CParam(name, p.typeSignature.toString, truncate(ParamValues.render(v, name)), "ctor", ParamValues.digest(v, name))
      })
    }
  }

  private def valueOf(m: AnyRef, member: String): Option[Any] =
    try {
      val getter = m.getClass.getMethod(member)
      if (getter.getDeclaringClass != m.getClass) None
      else Some(getter.invoke(m))
    } catch { case NonFatal(_) => None }

  val MaxValueChars       = 200
  val MaxModuleParamChars = 4096
  private val Truncated   = "...(truncated)"

  /** Cap a parameter value, saying so explicitly when it was cut. */
  private def truncate(s: String): String =
    if (s.length <= MaxValueChars) s else s.take(MaxValueChars) + Truncated

  /** Cap a module's parameter values at [[MaxModuleParamChars]] in all: past
    * it a value is only "...(truncated)" (its digest stays), and a problem says so. */
  private[probe] def capParams(module: String, params: Seq[CParam], problems: mutable.Buffer[String]): Seq[CParam] = {
    var used = 0
    var hit  = false
    params.map { p =>
      used += p.value.length
      if (used <= MaxModuleParamChars) p
      else {
        if (!hit) problems += s"$module: parameter values exceed $MaxModuleParamChars characters, the rest are cut"
        hit = true
        p.copy(value = Truncated)
      }
    }
  }

  // ── Module source ─────────────────────────────────────────────────────────
  // The chisel plugin overrides the protected `_sourceInfo` in each user
  // module; protected Scala members are public in bytecode, so getMethod
  // finds it and dispatch is virtual.
  private def sourceOf(m: BaseModule): Option[CSource] = {
    val meth = m.getClass.getMethod("_sourceInfo")
    meth.setAccessible(true)
    meth.invoke(m) match {
      case SourceLine(file, line, col) => Some(CSource(file, line, col))
      case _                           => None
    }
  }

  // ── Ports and types ───────────────────────────────────────────────────────
  private def portsOf(m: BaseModule): Seq[CPort] =
    DataMirror.modulePorts(m).map { case (name, d) => CPort(name, typeOf(d, name)) }

  /** `name` is the field or port name, used as the label of an anonymous bundle. */
  private[probe] def typeOf(d: Data, name: String): CType = {
    val cls = d.getClass.getName
    val base = CType(
      scalaClass = cls,
      label      = if (cls.contains("$anon")) name else simpleName(cls),
      dir        = dirOf(d),
      width      = widthOf(d),
    )
    d match {
      case v: Vec[_] =>
        // Element type read once, from the head (sample_element is not public).
        val elem = if (v.length > 0) Seq("0" -> typeOf(v.head, name)) else Nil
        base.copy(fields = elem, vecLen = v.length)
      case b: Bundle =>
        // Bundle.elements lists fields in reverse declaration order.
        base.copy(fields = b.elements.toSeq.reverse.map { case (n, f) => n -> typeOf(f, n) })
      case r: Record =>
        base.copy(fields = r.elements.toSeq.map { case (n, f) => n -> typeOf(f, n) })
      case _ => base
    }
  }

  private def simpleName(cls: String): String = {
    val last = cls.substring(cls.lastIndexOf('.') + 1)
    last.split('$').filter(_.nonEmpty).lastOption.getOrElse(last)
  }

  private def dirOf(d: Data): String = DataMirror.specifiedDirectionOf(d) match {
    case SpecifiedDirection.Input  => "in"
    case SpecifiedDirection.Output => "out"
    case SpecifiedDirection.Flip   => "flip"
    case _                         => "unspecified"
  }

  private def widthOf(d: Data): Int =
    try DataMirror.widthOf(d) match {
      case chisel3.KnownWidth(w) => w
      case _                     => -1
    } catch { case NonFatal(_) => -1 }
}
