package socless.ui.data

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.js.timers._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.util.{Success, Failure}
import upickle.default.{ReadWriter, macroRW}

/** The UI mirror of the language server's `IdeState` (socless-lsp
  * `LiveHttp`): same field names, parsed from `/ide.json`. `status` is
  * "idle" | "running" | "ok" | "error". `stamp` is a Double because the server
  * writes it as a JSON number. */
case class IdeState(
  status:     String = "idle",
  message:    String = "",
  focusClass: String = "",
  test:       String = "",
  stamp:      Double = 0d,
)
object IdeState {
  implicit val rw: ReadWriter[IdeState] = macroRW

  /** The top-bar badge text for an error `message`: its first line containing
    * `[error]` (else its first non-empty line), cut to `max` code points (never
    * inside a surrogate pair) with a trailing "…" when cut. */
  def errorSummary(message: String, max: Int = 80): String = {
    val lines = message.linesIterator.toSeq
    val line  = lines.find(_.contains("[error]")).orElse(lines.find(_.trim.nonEmpty)).getOrElse("").trim
    var i = 0
    var n = 0
    while (i < line.length && n < max) {
      val pair = Character.isHighSurrogate(line.charAt(i)) && i + 1 < line.length &&
        Character.isLowSurrogate(line.charAt(i + 1))
      i += (if (pair) 2 else 1)
      n += 1
    }
    if (i < line.length) line.substring(0, i) + "…" else line
  }
}

/** Link to the language server that serves this page (plan 2): polls
  * `/ide.json` for the run status and the class under the editor cursor, and
  * asks the editor to open a source via `POST /open`.
  *
  * When the page is not served by the language server (plain `soclessUI`,
  * `serve`, a file:// snapshot) the first `/ide.json` answers 404 or fails, and
  * the link disables itself for good: no further polling, no UI change. */
final class IdeLink {
  private val enabledVar = Var(false)
  private val stateVar   = Var[Option[IdeState]](None)
  private var started    = false
  private var timer      = Option.empty[SetIntervalHandle]
  private var failures   = 0 // consecutive failed polls once enabled

  val enabledSig:    Signal[Boolean]          = enabledVar.signal
  val stateSig:      Signal[Option[IdeState]] = stateVar.signal
  val focusClassSig: Signal[String]           = stateVar.signal.map(_.map(_.focusClass).getOrElse(""))

  /** Probe `/ide.json` once, then poll it every 1500 ms while the tab is
    * visible (same cadence as the design live watch). */
  def start(): Unit = {
    if (started) return
    started = true
    poll()
    timer = Some(setInterval(1500d) {
      if (enabledVar.now() && dom.document.visibilityState.asInstanceOf[String] == "visible") poll()
    })
  }

  /** Not served by the language server: stop polling for good. */
  private def disable(): Unit = { timer.foreach(clearInterval); timer = None }

  /** A failed poll. Before the first good answer it disables the link; once
    * enabled, three in a row drop a stale run status and focus highlight
    * (polling continues). */
  private def failed(): Unit =
    if (!enabledVar.now()) disable()
    else {
      failures += 1
      if (failures >= 3) stateVar.now().foreach { s =>
        if (s.status != "idle" || s.message.nonEmpty || s.focusClass.nonEmpty)
          stateVar.set(Some(s.copy(status = "idle", message = "", focusClass = "")))
      }
    }

  private def poll(): Unit = {
    val init = js.Dynamic.literal(cache = "no-store").asInstanceOf[dom.RequestInit]
    dom.fetch("ide.json", init).flatMap { r =>
      if (r.ok) r.text().map(Some(_))
      else if (r.status == 404) Future.successful(None)
      else Future.failed(new RuntimeException(s"HTTP ${r.status}"))
    }.onComplete {
      case Success(Some(txt)) =>
        try {
          val s = upickle.default.read[IdeState](txt)
          failures = 0
          if (!stateVar.now().contains(s)) stateVar.set(Some(s))
          if (!enabledVar.now()) enabledVar.set(true)
        } catch { case _: Throwable => failed() } // malformed: keeps the last state
      case Success(None) => failed() // 404: not served by the language server
      case Failure(_)    => failed() // no server (file://), or a transient error once enabled
    }
  }

  /** Ask the editor to show `file` at the 1-based `line` (sent as-is; the
    * server converts it). Same-origin relative URL with a JSON content type,
    * as the server requires. No-op while the link is disabled. */
  def open(file: String, line: Int): Unit =
    if (enabledVar.now()) {
      val init = js.Dynamic.literal(
        method  = "POST",
        headers = js.Dynamic.literal("Content-Type" -> "application/json"),
        body    = ujson.write(ujson.Obj("file" -> file, "line" -> line)),
      ).asInstanceOf[dom.RequestInit]
      dom.fetch("open", init).onComplete(_ => ())
    }
}
