package socle.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient

/** `socle/openPanel` params: where the live UI is served, and the test
  * whose design it shows (for the panel title; clients may ignore it). */
final class OpenPanelParams(var url: String, var test: String) {
  def this() = this(null, null)
  def getUrl: String  = url
  def getTest: String = test
}

/** `socle/status` params: the panel's run state ([[IdeState]]) whenever its
  * status, test or message changes. `suite` is the FQCN of the test's suite
  * (empty when unknown), so a client can tell same-named tests apart. */
final class StatusParams(var status: String, var suite: String, var test: String, var message: String) {
  def this() = this(null, null, null, null)
  def getStatus: String  = status
  def getSuite: String   = suite
  def getTest: String    = test
  def getMessage: String = message
}

/** The client side of the server's custom notifications. Sent only when the
  * client opted in with `initializationOptions = {"panel": "client"}`. */
trait SocleClient extends LanguageClient {
  @JsonNotification("socle/openPanel")
  def openPanel(params: OpenPanelParams): Unit

  /** The run state changed (see [[StatusParams]]). */
  @JsonNotification("socle/status")
  def status(params: StatusParams): Unit

  /** The workspace's ChiselSim tests (`socle/tests`) changed after a save. */
  @JsonNotification("socle/testsChanged")
  def testsChanged(): Unit
}
