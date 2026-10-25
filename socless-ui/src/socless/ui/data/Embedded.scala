package socless.ui.data

import org.scalajs.dom
import scala.scalajs.js

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
