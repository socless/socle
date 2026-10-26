package socless.ui.data

import org.scalajs.dom
import scala.scalajs.js
import socless.wave.SurferWire

/** Embedded mode: the page runs inside an editor's panel (the VS Code
  * extension loads it with `?host=vscode&theme=<kind>`). The editor already
  * shows the code and runs simulations, so the UI drops its own Source /
  * Waveform tabs, logo, design picker, theme controls, drop zone and share
  * button, and its theme follows the editor's.
  *
  * Read once from the page URL; without `host=vscode` nothing changes. */
object Embedded {
  private val params = new dom.URLSearchParams(dom.window.location.search)

  val on: Boolean = params.get("host") == "vscode"

  /** The VS Code Surfer tab page (`view=surfer`): only Surfer, driven by
    * `socless-surfer-cmd` messages from the parent ([[socless.ui.wave.SurferTab]]). */
  val surferView: Boolean = on && params.get("view") == "surfer"

  /** The UI theme id for an editor theme kind (`light` / `dark` / `hc`). */
  def themeFor(kind: String): Option[String] = kind match {
    case "light" => Some("light")
    case "dark"  => Some("dark")
    case "hc"    => Some("contrast")
    case _       => None
  }

  /** The theme from the URL's `theme` kind, when embedded. */
  def initialTheme: Option[String] =
    if (on) Option(params.get("theme")).flatMap(themeFor) else None

  /** Embedded there is no drop zone; cancel window drags and drops so a file
    * dropped on the panel never navigates the frame to it. */
  def blockFileDrops(): Unit =
    if (on) for (t <- Seq("dragover", "drop"))
      dom.window.addEventListener(t, (e: dom.DragEvent) => e.preventDefault())

  private val TrustedParent = """^(vscode-webview://[^/]+|https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?)$""".r

  /** The origin of the page framing this one, when it is a VS Code webview
    * (`vscode-webview:`) or a loopback page; None at the top level, without
    * `location.ancestorOrigins` (Chromium has it, VS Code is Chromium), or for
    * any other parent: the side panel channel then stays closed. */
  lazy val parentOrigin: Option[String] =
    if (!on || js.special.strictEquals(dom.window.parent, dom.window)) None
    else {
      val ao = dom.window.location.asInstanceOf[js.Dynamic].ancestorOrigins
      if (js.isUndefined(ao) || ao == null || ao.length.asInstanceOf[Int] < 1) None
      else Option(ao.item(0).asInstanceOf[String]).filter(o => TrustedParent.pattern.matcher(o).matches())
    }

  /** Posts `msg` to the parent page (the side panel channel), to its exact origin only. */
  def postToParent(msg: js.Any): Unit =
    parentOrigin.foreach(o => dom.window.parent.postMessage(msg, o))

  /** A `{type: "socless-navigate", path, select}` message: `path` a non-empty
    * array of strings, `select` absent, null or a string. None otherwise. */
  def parseNavigate(data: js.Any): Option[(List[String], Option[String])] =
    if (data == null || js.typeOf(data) != "object") None
    else {
      val d = data.asInstanceOf[js.Dynamic]
      val path = d.selectDynamic("path")
      val sel  = d.selectDynamic("select")
      val okType = d.selectDynamic("type").asInstanceOf[Any] == "socless-navigate"
      val okPath = js.Array.isArray(path) && path.asInstanceOf[js.Array[Any]].nonEmpty &&
        path.asInstanceOf[js.Array[Any]].forall(p => js.typeOf(p) == "string")
      val okSel = js.isUndefined(sel) || sel == null || js.typeOf(sel) == "string"
      if (!(okType && okPath && okSel)) None
      else Some((path.asInstanceOf[js.Array[String]].toList,
        if (js.isUndefined(sel) || sel == null) None else Some(sel.asInstanceOf[String])))
    }

  /** Calls `apply` with every valid `socless-navigate` message from the parent
    * page (same window and origin rules as [[postToParent]]). */
  def onNavigateMessage(apply: (List[String], Option[String]) => Unit): Unit =
    parentOrigin.foreach { origin =>
      dom.window.addEventListener("message", (e: dom.MessageEvent) =>
        if (js.special.strictEquals(e.source, dom.window.parent) && e.origin == origin)
          parseNavigate(e.data.asInstanceOf[js.Any]).foreach { case (p, s) => apply(p, s) })
    }

  /** Calls `apply` with the command of every valid
    * `{type: "socless-surfer-cmd", cmd}` message from the parent page (same
    * window and origin rules as [[postToParent]]); a `cmd` that is not a
    * [[SurferWire.Cmd]] is ignored. */
  def onSurferCmd(apply: SurferWire.Cmd => Unit): Unit =
    parentOrigin.foreach { origin =>
      dom.window.addEventListener("message", (e: dom.MessageEvent) =>
        if (js.special.strictEquals(e.source, dom.window.parent) && e.origin == origin &&
            e.data != null && js.typeOf(e.data) == "object") {
          val d = e.data.asInstanceOf[js.Dynamic]
          val cmd = d.selectDynamic("cmd")
          if (d.selectDynamic("type").asInstanceOf[Any] == "socless-surfer-cmd" && cmd != null && js.typeOf(cmd) == "object")
            SurferWire.parseCmd(js.JSON.stringify(cmd)).foreach(apply)
        })
    }

  /** The SoCLess panel in VS Code: framed by the editor's webview (the side
    * panel channel is open) and not the Surfer tab. Its waveforms go to the
    * Surfer tab ([[socless.ui.wave.RemotePane]]); unframed, there is no one to
    * relay them, so Surfer stays in the Waveform tab. */
  lazy val surferRemote: Boolean = on && !surferView && parentOrigin.isDefined

  /** Calls `apply` with the status of every valid
    * `{type: "socless-surfer-status", status}` message from the parent page
    * (same window and origin rules as [[postToParent]]). */
  def onSurferStatus(apply: SurferWire.Status => Unit): Unit =
    onParentMessage("socless-surfer-status") { d =>
      val st = d.selectDynamic("status")
      if (st != null && js.typeOf(st) == "object") SurferWire.parseStatus(js.JSON.stringify(st)).foreach(apply)
    }

  /** Calls `apply` on every `{type: "socless-surfer-closed"}` message from the
    * parent page (same window and origin rules as [[postToParent]]). */
  def onSurferClosed(apply: () => Unit): Unit =
    onParentMessage("socless-surfer-closed")(_ => apply())

  private def onParentMessage(tpe: String)(apply: js.Dynamic => Unit): Unit =
    parentOrigin.foreach { origin =>
      dom.window.addEventListener("message", (e: dom.MessageEvent) =>
        if (js.special.strictEquals(e.source, dom.window.parent) && e.origin == origin &&
            e.data != null && js.typeOf(e.data) == "object") {
          val d = e.data.asInstanceOf[js.Dynamic]
          if (d.selectDynamic("type").asInstanceOf[Any] == tpe) apply(d)
        })
    }

  /** Posts `{type: "socless-surfer-status", status}` to the parent page. */
  def postSurferStatus(s: SurferWire.Status): Unit =
    postToParent(js.Dynamic.literal(`type` = "socless-surfer-status", status = js.JSON.parse(SurferWire.writeStatus(s))))

  /** Calls `apply` with the theme of every `{type: "socless-theme", kind}`
    * message posted by the parent page (the editor's webview). Messages from
    * any other window, or any message to a top-level page (it has no parent),
    * are ignored. Embedded mode only. */
  def onThemeMessage(apply: String => Unit): Unit =
    if (on) dom.window.addEventListener("message", (e: dom.MessageEvent) => {
      // Identity with ===: Scala's == would call equals on the parent, a
      // cross-origin window whose properties throw when read.
      val parent = dom.window.parent
      if (!js.special.strictEquals(parent, dom.window) && js.special.strictEquals(e.source, parent) &&
          e.data != null && js.typeOf(e.data) == "object") {
        val d = e.data.asInstanceOf[js.Dynamic]
        if (d.selectDynamic("type").asInstanceOf[Any] == "socless-theme" && js.typeOf(d.selectDynamic("kind")) == "string")
          themeFor(d.selectDynamic("kind").asInstanceOf[String]).foreach(apply)
      }
    })
}
