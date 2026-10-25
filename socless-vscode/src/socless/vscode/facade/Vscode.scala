package socless.vscode.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// Minimal facade over the `vscode` module VS Code provides at runtime: only what
// the extension uses. Never loaded by the Node tests.

@js.native
trait Disposable extends js.Object {
  def dispose(): Unit = js.native
}

@js.native
trait ExtensionContext extends js.Object {
  val subscriptions: js.Array[Disposable] = js.native
  val extensionPath: String = js.native
}

@js.native
@JSImport("vscode", "Uri")
object Uri extends js.Object {
  def parse(value: String): Uri = js.native
}

@js.native
trait Uri extends js.Object {
  val scheme: String = js.native
  override def toString(): String = js.native
}

@js.native
@JSImport("vscode", "ViewColumn")
object ViewColumn extends js.Object {
  val Beside: Int = js.native
}

@js.native
trait Position extends js.Object {
  val line: Int = js.native
}

@js.native
trait Selection extends js.Object {
  val active: Position = js.native
}

@js.native
trait TextDocument extends js.Object {
  val uri: Uri = js.native
  val languageId: String = js.native
}

@js.native
trait TextEditor extends js.Object {
  val document: TextDocument = js.native
  val selection: Selection = js.native
}

@js.native
trait TextEditorSelectionChangeEvent extends js.Object {
  val textEditor: TextEditor = js.native
  val selections: js.Array[Selection] = js.native
}

@js.native
trait Webview extends js.Object {
  var html: String = js.native
  val cspSource: String = js.native
}

@js.native
trait WebviewPanel extends Disposable {
  val webview: Webview = js.native
  var title: String = js.native
  def reveal(viewColumn: js.UndefOr[Int] = js.undefined, preserveFocus: js.UndefOr[Boolean] = js.undefined): Unit = js.native
  def onDidDispose(listener: js.Function0[Unit]): Disposable = js.native
}

@js.native
trait WorkspaceConfiguration extends js.Object {
  def get[T](section: String): js.UndefOr[T] = js.native
}

@js.native
@JSImport("vscode", "window")
object window extends js.Object {
  def activeTextEditor: js.UndefOr[TextEditor] = js.native
  def createWebviewPanel(
      viewType: String,
      title: String,
      showOptions: js.Any,
      options: js.UndefOr[js.Object] = js.undefined,
  ): WebviewPanel = js.native
  def showErrorMessage(message: String): js.Promise[js.UndefOr[String]] = js.native
  def showInformationMessage(message: String): js.Promise[js.UndefOr[String]] = js.native
  def onDidChangeTextEditorSelection(listener: js.Function1[TextEditorSelectionChangeEvent, Unit]): Disposable = js.native
  def activeColorTheme: ColorTheme = js.native
}

@js.native
trait ColorTheme extends js.Object {
  /** `vscode.ColorThemeKind`: 1 Light, 2 Dark, 3 HighContrast, 4 HighContrastLight. */
  val kind: Int = js.native
}

@js.native
@JSImport("vscode", "workspace")
object workspace extends js.Object {
  def getConfiguration(section: String): WorkspaceConfiguration = js.native
}

@js.native
@JSImport("vscode", "commands")
object commands extends js.Object {
  def registerCommand(command: String, callback: js.Function): Disposable = js.native
}

@js.native
@JSImport("vscode", "env")
object env extends js.Object {
  def asExternalUri(target: Uri): js.Promise[Uri] = js.native
}
