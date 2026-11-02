package socle.vscode

import scala.scalajs.js
import socle.vscode.facade._

/** The single Socle webview: an iframe on the language server's loopback UI, in embedded mode. */
object Panel {
  private var current: Option[(WebviewPanel, PanelPage)] = None

  /** The last URL the server asked to show, for `socle.showPanel` and `socle._panelUrl`. */
  var lastUrl: Option[String] = None

  /** The test of the last view, for the panel title. */
  private var lastTest: Option[String] = None

  /** Whether the UI in the current page has posted its state (it listens for navigate messages). */
  private var uiReady = false

  /** A navigate message waiting for the UI to be ready (the last one wins). */
  private var pendingNavigate: Option[js.Any] = None

  /** Creates the panel beside the editor (or reveals the existing one in its own column), points it at
    * `url` in embedded mode with VS Code's current theme kind, and titles it after `test` (None keeps
    * the last one). The promise resolves once the panel exists (so [[column]] is known).
    */
  def show(url: String, test: Option[String]): js.Promise[Unit] = {
    lastUrl = Some(url)
    test.foreach(t => lastTest = Some(t))
    env.asExternalUri(Uri.parse(url)).`then`[Unit] { (external: Uri) =>
      val existing      = column
      val (panel, page) = current.getOrElse(create())
      panel.title = Logic.panelTitle(lastTest)
      panel.reveal(Logic.panelRevealColumn(existing), preserveFocus = true)
      // Same URL, same html: leave it alone so the iframe (and the UI state in it) is not reloaded.
      val html = page.html(external.toString(), panel.webview.cspSource)
      if (panel.webview.html != html) { panel.webview.html = html; uiReady = false }
      SurferTab.retarget(external.toString()) // a restarted server: the tab follows it
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
      "socle.panel",
      Logic.panelTitle(lastTest),
      js.Dynamic.literal(viewColumn = ViewColumn.Beside, preserveFocus = true),
      js.Dynamic.literal(enableScripts = true, retainContextWhenHidden = true),
    )
    // The listeners live and die with this panel, not in ctx.subscriptions (one per panel ever made).
    val messages = panel.webview.onDidReceiveMessage { (msg: js.Any) =>
      Logic.msgType(msg) match {
        case Some("socle-state") =>
          // The UI listens from its first state on.
          uiReady = true
          pendingNavigate.foreach(m => panel.webview.postMessage(m))
          pendingNavigate = None
          Sidebar.onUiState(msg)
        case Some("socle-surfer-open") => SurferTab.onOpenRequest(msg, lastTest)
        case _ => Logic.route("panel", msg).foreach { case (_, m) => SurferTab.post(m) }
      }
    }
    var listener: Option[Disposable] = None
    listener = Some(panel.onDidDispose { () =>
      current = None
      uiReady = false
      pendingNavigate = None
      messages.dispose()
      listener.foreach(_.dispose())
      Sidebar.onPanelClosed()
    })
    uiReady = false
    val made = (panel, new PanelPage(nonce(), Logic.themeKind(window.activeColorTheme.kind)))
    current = Some(made)
    made
  }

  /** Sends a socle-navigate message to the UI (once it is ready) and reveals the panel. */
  def navigate(msg: js.Any): Unit = current.foreach { case (panel, _) =>
    panel.reveal(js.undefined, preserveFocus = true) // where it is
    if (uiReady) panel.webview.postMessage(msg) else pendingNavigate = Some(msg)
  }

  /** Posts a Surfer channel message (the tab's status, or its close) to the UI, if the panel is open. */
  def post(msg: js.Any): Unit = current.foreach { case (panel, _) => panel.webview.postMessage(msg) }

  /** The panel's editor column, when it is open (the Surfer tab opens after it). */
  def column: Option[Int] = current.flatMap(_._1.viewColumn.toOption)

  /** The test of the last view (for the Surfer tab's title). */
  def test: Option[String] = lastTest

  /** The editor column for a source opened from the side panel: the first one, or the one
    * beside when the panel is there (so the source never covers the panel). */
  def sourceColumn: Int =
    if (current.exists(_._1.viewColumn.toOption.contains(1))) ViewColumn.Beside else 1

  /** Closes the panel, if any (extension deactivation); its dispose listener cleans up after it. */
  def dispose(): Unit = current.foreach(_._1.dispose())
}
