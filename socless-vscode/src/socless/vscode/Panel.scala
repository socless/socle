package socless.vscode

import scala.scalajs.js
import socless.vscode.facade._

/** The single SoCLess webview: an iframe on the language server's loopback UI. */
object Panel {
  private var current: Option[WebviewPanel] = None

  /** The last URL the server asked to show, for `socless.showPanel` and `socless._panelUrl`. */
  var lastUrl: Option[String] = None

  /** Creates the panel beside the editor (or reveals the existing one) and points it at `url`. */
  def show(url: String): Unit = {
    lastUrl = Some(url)
    env.asExternalUri(Uri.parse(url)).`then`[Unit] { (external: Uri) =>
      val panel = current.getOrElse(create())
      panel.reveal(ViewColumn.Beside, preserveFocus = true)
      panel.webview.html = Logic.webviewHtml(external.toString(), panel.webview.cspSource)
    }
  }

  private def create(): WebviewPanel = {
    val panel = window.createWebviewPanel(
      "socless.panel",
      "SoCLess design",
      js.Dynamic.literal(viewColumn = ViewColumn.Beside, preserveFocus = true),
      js.Dynamic.literal(enableScripts = true, retainContextWhenHidden = true),
    )
    // The listener lives and dies with this panel, not in ctx.subscriptions (one per panel ever made).
    var listener: Option[Disposable] = None
    listener = Some(panel.onDidDispose { () =>
      current = None
      listener.foreach(_.dispose())
    })
    current = Some(panel)
    panel
  }

  /** Closes the panel, if any (extension deactivation); its dispose listener cleans up after it. */
  def dispose(): Unit = current.foreach(_.dispose())
}
