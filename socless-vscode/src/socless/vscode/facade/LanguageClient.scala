package socless.vscode.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// Minimal facade over `vscode-languageclient/node` (v9), staged from npm at
// packaging time. Only what the extension uses.

/** `{ command, args }`: the server is launched as a child process over stdio. */
trait Executable extends js.Object {
  val command: String
  val args: js.UndefOr[js.Array[String]] = js.undefined
}

/** `ServerOptions` as the extension uses it: one `Executable` for run and debug. */
trait ServerOptions extends js.Object {
  val run: Executable
  val debug: Executable
}

trait DocumentFilter extends js.Object {
  val scheme: js.UndefOr[String] = js.undefined
  val language: js.UndefOr[String] = js.undefined
  val pattern: js.UndefOr[String] = js.undefined
}

trait LanguageClientOptions extends js.Object {
  val documentSelector: js.UndefOr[js.Array[DocumentFilter]] = js.undefined
  val initializationOptions: js.UndefOr[js.Any] = js.undefined
  val errorHandler: js.UndefOr[ErrorHandler] = js.undefined
  /** `{ configurationSection }`: the client sends that settings section in `workspace/didChangeConfiguration`. */
  val synchronize: js.UndefOr[js.Object] = js.undefined
}

/** v9 `ErrorHandler`: each method returns `{ action, message?, handled? }`. */
trait ErrorHandler extends js.Object {
  def error(error: js.Any, message: js.Any, count: js.UndefOr[Int]): js.Object
  def closed(): js.Object
}

/** v9 `ErrorAction`: `Continue` = 1, `Shutdown` = 2. */
@js.native
@JSImport("vscode-languageclient/node", "ErrorAction")
object ErrorAction extends js.Object {
  val Continue: Int = js.native
  val Shutdown: Int = js.native
}

/** v9 `CloseAction`: `DoNotRestart` = 1, `Restart` = 2. */
@js.native
@JSImport("vscode-languageclient/node", "CloseAction")
object CloseAction extends js.Object {
  val DoNotRestart: Int = js.native
  val Restart: Int = js.native
}

@js.native
@JSImport("vscode-languageclient/node", "LanguageClient")
class LanguageClient(
    id: String,
    name: String,
    serverOptions: ServerOptions,
    clientOptions: LanguageClientOptions,
) extends js.Object {
  def start(): js.Promise[Unit] = js.native
  def stop(): js.Promise[Unit] = js.native
  def isRunning(): Boolean = js.native
  def sendNotification(method: String, params: js.Any): js.Promise[Unit] = js.native
  def onNotification(method: String, handler: js.Function1[js.Dynamic, Unit]): Disposable = js.native
}
