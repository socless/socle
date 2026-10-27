package socless.wave

import scala.util.Try
import upickle.default.{ReadWriter, macroRW, read, readwriter, write, writeJs}
import upickle.implicits.key

/** The messages between the SoCLess panel and the Surfer tab page
  * (`?host=vscode&view=surfer`), relayed by the VS Code extension. A command
  * travels as `{type: "socless-surfer-cmd", cmd}`, the tab's state as
  * `{type: "socless-surfer-status", status}`; `cmd` and `status` are the JSON
  * of [[Cmd]] and [[Status]]. Shared by the JVM and the Scala.js UI. */
object SurferWire {

  /** A command for the Surfer tab, an object tagged with its case name: `{"$type": "Load", ...}`.
    * `Hello` has its own writer: upickle writes a case object as a bare string (`"Hello"`). */
  sealed trait Cmd
  /** Load the trace at `url` (absolute). */
  @key("Load") final case class Load(url: String) extends Cmd
  /** Show `plan` (of the module at `path` in `trace`); `seq` names it in the [[Status]] that completes it. */
  @key("Show") final case class Show(seq: Int, plan: WavePlan, path: List[String], trace: String) extends Cmd
  /** Focus the signal `trace` of the plan shown. */
  @key("Focus") final case class Focus(trace: String) extends Cmd
  /** Add the signals `traces` not shown (Surfer's Add drivers or Add loads),
    * below the signal `after` when it is shown (the one right-clicked). */
  @key("Add") final case class Add(traces: Seq[String], after: Option[String] = None) extends Cmd
  /** Report the tab's state (a [[Status]]). */
  @key("Hello") case object Hello extends Cmd

  object Load  { implicit val rw: ReadWriter[Load]  = macroRW }
  object Show  { implicit val rw: ReadWriter[Show]  = macroRW }
  object Focus { implicit val rw: ReadWriter[Focus] = macroRW }
  object Add   { implicit val rw: ReadWriter[Add]   = macroRW }
  object Cmd {
    private val tagged: ReadWriter[Cmd] = ReadWriter.merge(Load.rw, Show.rw, Focus.rw, Add.rw, macroRW[Hello.type])
    implicit val rw: ReadWriter[Cmd] = readwriter[ujson.Value].bimap[Cmd](
      {
        case Hello => ujson.Obj("$type" -> "Hello")
        case c     => writeJs(c)(tagged)
      },
      j => read[Cmd](j)(tagged),
    )
  }

  /** The tab's state: `state` is idle, booting, loading, ready or error;
    * `loadedTrace` the trace it was last asked to load ("" for none). Posted
    * on every state change (no `shownSeq`) and when a Show completes:
    * `shownSeq` its seq with `dropped` (the paths Surfer lacked), or
    * `shownSeqFailed` when it was not shown. `event` carries a right-click
    * action chosen in the tab's Surfer, posted on its own. */
  final case class Status(state: String, loadedTrace: String, shownSeq: Option[Int], dropped: Option[Int],
      shownSeqFailed: Boolean = false, event: Option[Event] = None)
  object Status { implicit val rw: ReadWriter[Status] = macroRW }

  /** A right-click action Surfer sends over WCP (its `event`):
    * `goto_declaration`, `add_drivers` or `add_loads`, on the signal
    * `variable` (its full trace path). */
  final case class Event(kind: String, variable: String)
  object Event {
    implicit val rw: ReadWriter[Event] = macroRW
    /** The WCP events this asks Surfer for in its greeting (Surfer shows the menu items only then). */
    val Kinds: Seq[String] = Seq("goto_declaration", "add_drivers", "add_loads")
  }

  /** Go to declaration chosen in the Surfer app (the language server's): the
    * panel shows the module at `instPath` and selects `leaf` there (a port
    * leaf or a signal, [[WaveLinks.locate]]). `seq` grows with each one, so
    * the panel applies each once (`/ide.json` carries the last). */
  final case class Reveal(seq: Int, instPath: List[String], leaf: String)
  object Reveal { implicit val rw: ReadWriter[Reveal] = macroRW }

  def writeCmd(c: Cmd): String = write(c)
  def writeStatus(s: Status): String = write(s)

  /** The command in `json`; None when it is not one. */
  def parseCmd(json: String): Option[Cmd] = Try(read[Cmd](json)).toOption.filter(_ != null)

  /** The status in `json`; None when it is not one. */
  def parseStatus(json: String): Option[Status] = Try(read[Status](json)).toOption.filter(_ != null)
}
