package socless.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient

/** `socless/openPanel` params: where the live UI is served. */
final class OpenPanelParams(var url: String) {
  def this() = this(null)
  def getUrl: String = url
}

/** The client side of the server's custom notifications. Sent only when the
  * client opted in with `initializationOptions = {"panel": "client"}`. */
trait SoclessClient extends LanguageClient {
  @JsonNotification("socless/openPanel")
  def openPanel(params: OpenPanelParams): Unit
}
