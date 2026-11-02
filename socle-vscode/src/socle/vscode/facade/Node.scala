package socle.vscode.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// Minimal facade over the Node built-ins the extension uses.

@js.native
@JSImport("child_process", JSImport.Namespace)
object childProcess extends js.Object {

  /** Node's `child_process.execFile`; with `encoding: "utf8"` the callback gets strings. `error` is set
    * when the command could not run, exited non-zero or was killed (for example by `timeout`).
    */
  def execFile(
      file: String,
      args: js.Array[String],
      options: js.Object,
      callback: js.Function3[js.UndefOr[js.Error], String, String, Unit],
  ): js.Object = js.native
}
