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
        params     = attempt(m, "params", problems)(paramsOf(m)).getOrElse(Nil),
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
  // reflection); a value is read back only through a same-named zero-arg
  // method declared by the module's own class, i.e. when the parameter is a
  // val (an inherited member like BaseModule.name must not stand in for a
  // plain `name` parameter). Otherwise it is "<unreadable>".
  private def paramsOf(m: BaseModule): Seq[CParam] = {
    val mirror = ru.runtimeMirror(m.getClass.getClassLoader)
    val ctor   = mirror.classSymbol(m.getClass).primaryConstructor
    if (ctor == ru.NoSymbol) Nil
    else ctor.asMethod.paramLists.flatten.map { p =>
      val name = p.name.decodedName.toString
      CParam(name, p.typeSignature.toString, valueOf(m, p.name.encodedName.toString))
    }
  }

  private def valueOf(m: AnyRef, member: String): String =
    try {
      val getter = m.getClass.getMethod(member)
      if (getter.getDeclaringClass != m.getClass) Unreadable
      else truncate(String.valueOf(getter.invoke(m)))
    } catch { case NonFatal(_) => Unreadable }

  val MaxValueChars = 200

  /** Cap a parameter value, saying so explicitly when it was cut. */
  private def truncate(s: String): String =
    if (s.length <= MaxValueChars) s else s.take(MaxValueChars) + "...(truncated)"

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
  private def typeOf(d: Data, name: String): CType = {
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
