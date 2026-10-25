package socless.vscode

import scala.scalajs.js
import socless.vscode.facade._

/** The single SoCLess webview: an iframe on the language server's loopback UI, in embedded mode. */
object Panel {
  private var current: Option[(WebviewPanel, PanelPage)] = None

  /** The last URL the server asked to show, for `socless.showPanel` and `socless._panelUrl`. */
  var lastUrl: Option[String] = None

  /** The test of the last view, for the panel title. */
  private var lastTest: Option[String] = None

  /** Creates the panel beside the editor (or reveals the existing one), points it at `url` in embedded
    * mode with VS Code's current theme kind, and titles it after `test` (None keeps the last one).
    */
  def show(url: String, test: Option[String]): Unit = {
    lastUrl = Some(url)
    test.foreach(t => lastTest = Some(t))
    env.asExternalUri(Uri.parse(url)).`then`[Unit] { (external: Uri) =>
      val (panel, page) = current.getOrElse(create())
      panel.title = Logic.panelTitle(lastTest)
      panel.reveal(ViewColumn.Beside, preserveFocus = true)
      // Same URL, same html: leave it alone so the iframe (and the UI state in it) is not reloaded.
      val html = page.html(external.toString(), panel.webview.cspSource)
      if (panel.webview.html != html) panel.webview.html = html
    }
  }

  /** A CSP nonce: 16 bytes from the Web Crypto API (global in the extension host's Node), as hex. */
  private def nonce(): String = {
    val bytes = new js.typedarray.Uint8Array(16)
    js.Dynamic.global.crypto.getRandomValues(bytes)
    Logic.hex(bytes.toSeq.map(_.toInt))
  }

  /** A new panel and its page: one nonce and the current VS Code theme kind for the panel's life. */
  private def create(): (WebviewPanel, PanelPage) = {
    val panel = window.createWebviewPanel(
      "socless.panel",
      Logic.panelTitle(lastTest),
      js.Dynamic.literal(viewColumn = ViewColumn.Beside, preserveFocus = true),
      js.Dynamic.literal(enableScripts = true, retainContextWhenHidden = true),
    )
    // The listener lives and dies with this panel, not in ctx.subscriptions (one per panel ever made).
    var listener: Option[Disposable] = None
    listener = Some(panel.onDidDispose { () =>
      current = None
      listener.foreach(_.dispose())
    })
    val made = (panel, new PanelPage(nonce(), Logic.themeKind(window.activeColorTheme.kind)))
    current = Some(made)
    made
  }

  /** Closes the panel, if any (extension deactivation); its dispose listener cleans up after it. */
  def dispose(): Unit = current.foreach(_._1.dispose())
}
