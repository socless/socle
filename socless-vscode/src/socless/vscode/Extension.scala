package socless.vscode

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.timers.{clearTimeout, setTimeout, SetTimeoutHandle}
import socless.vscode.facade._

/** The extension entry points VS Code calls. Decisions live in `Logic`; this only wires vscode. */
object Extension {
  private var client: Option[LanguageClient] = None

  /** Why the server was not started (Java missing or too old), for `socless.showPanel`. */
  private var javaProblem: Option[String] = None

  /** Set by `deactivate`, so a late `java -version` answer does not start a client. */
  private var deactivated = false

  @JSExportTopLevel("activate")
  def activate(ctx: ExtensionContext): Unit = {
    ctx.subscriptions.push(commands.registerCommand("socless.showPanel", (() => showPanel()): js.Function0[Unit]))
    ctx.subscriptions.push(
      commands.registerCommand("socless._panelUrl", (() => Panel.lastUrl.orUndefined): js.Function0[js.UndefOr[String]])
    )

    val config  = workspace.getConfiguration("socless")
    val javaCmd = Logic.javaCommand(
      config.get[String]("javaPath").toOption,
      js.Dynamic.global.process.env.JAVA_HOME.asInstanceOf[js.UndefOr[String]].toOption,
    )
    val jar = Logic.serverJar(config.get[String]("serverJar").toOption, ctx.extensionPath)
    deactivated = false
    // Asynchronous, so activation returns at once; the server starts once Java is known to be fine.
    javaMajor(javaCmd) { major =>
      if (!deactivated) Logic.javaProblem(javaCmd, major) match {
        case Some(problem) =>
          javaProblem = Some(problem)
          window.showErrorMessage(problem)
        case None =>
          val init = Logic.initializationOptions(config.get[String]("waveFlag").toOption,
            config.get[String]("surferCommand").toOption)
          start(ctx, javaCmd, jar, init)
      }
    }
  }

  @JSExportTopLevel("deactivate")
  def deactivate(): js.UndefOr[js.Promise[Unit]] = {
    deactivated = true
    Panel.dispose()
    // Only a running client stops cleanly; a starting or failed one would reject, so swallow that too.
    client.filter(_.isRunning()).map { c =>
      c.stop().asInstanceOf[js.Dynamic].`catch`((_: js.Any) => ()).asInstanceOf[js.Promise[Unit]]
    }.orUndefined
  }

  /** Runs `java -version` once (10 s at most) and calls `k` with the major version: None when it cannot
    * run, times out or prints no version. The output is parsed whatever the exit status.
    */
  private def javaMajor(javaCmd: String)(k: Option[Int] => Unit): Unit = {
    childProcess.execFile(
      javaCmd,
      js.Array("-version"),
      js.Dynamic.literal(encoding = "utf8", timeout = 10000),
      (_: js.UndefOr[js.Error], stdout: String, stderr: String) =>
        k(Logic.parseJavaMajor(Option(stderr).getOrElse("") + "\n" + Option(stdout).getOrElse(""))),
    )
    ()
  }

  private def start(ctx: ExtensionContext, javaCmd: String, jar: String, init: js.Object): Unit = {
    val exe = new Executable {
      val command       = javaCmd
      override val args = js.defined(js.Array("-jar", jar))
    }
    val restarts = new RestartBudget(3, 3 * 60 * 1000)
    val handler = new ErrorHandler {
      def error(error: js.Any, message: js.Any, count: js.UndefOr[Int]): js.Object =
        js.Dynamic.literal(action = ErrorAction.Continue)
      def closed(): js.Object =
        if (restarts.allow(js.Date.now())) js.Dynamic.literal(action = CloseAction.Restart)
        else {
          window.showErrorMessage(
            "The SoCLess language server stopped 3 times in 3 minutes and will not be restarted. Reload the window to try again."
          )
          js.Dynamic.literal(action = CloseAction.DoNotRestart, handled = true)
        }
    }
    val options = new LanguageClientOptions {
      override val documentSelector = js.defined(js.Array[DocumentFilter](new DocumentFilter {
        override val scheme   = js.defined("file")
        override val language = js.defined("scala")
      }))
      override val initializationOptions = js.defined(init: js.Any)
      override val errorHandler          = js.defined(handler)
      // later changes of the socless.* settings reach the server (waveFlag, surferCommand)
      override val synchronize = js.defined(js.Dynamic.literal(configurationSection = "socless"))
    }
    val c = new LanguageClient("socless", "SoCLess", new ServerOptions { val run = exe; val debug = exe }, options)
    client = Some(c)

    ctx.subscriptions.push(
      c.onNotification("socless/openPanel", (params: js.Dynamic) =>
        Panel.show(params.url.asInstanceOf[String], params.test.asInstanceOf[js.UndefOr[String]].toOption.flatMap(Option(_))))
    )

    val debounce = new Debouncer(
      300,
      (ms, f) => setTimeout(ms.toDouble)(f()),
      h => clearTimeout(h.asInstanceOf[SetTimeoutHandle]),
    )
    ctx.subscriptions.push(window.onDidChangeTextEditorSelection { (e: TextEditorSelectionChangeEvent) =>
      val doc = e.textEditor.document
      if (doc.languageId == "scala" && doc.uri.scheme == "file" && e.selections.nonEmpty) {
        val params = Logic.cursorParams(doc.uri.toString(), e.selections(0).active.line)
        debounce(() => ignoreFailure(c.sendNotification("socless/cursor", params)))
      }
    })

    ignoreFailure(c.start())
  }

  /** Swallows a rejection (the client already reports server failures) so it is not an unhandled rejection. */
  private def ignoreFailure(p: js.Promise[Unit]): Unit =
    p.asInstanceOf[js.Dynamic].`catch`((_: js.Any) => ())

  private def showPanel(): Unit = Panel.lastUrl match {
    case Some(url) => Panel.show(url, None)
    case None =>
      javaProblem match {
        case Some(problem) => window.showErrorMessage(problem)
        case None          => window.showInformationMessage("SoCLess: no design yet. Use a \"View design\" lens on a ChiselSim test first.")
      }
  }
}
