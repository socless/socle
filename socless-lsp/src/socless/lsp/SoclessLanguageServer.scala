package socless.lsp

import com.google.gson.{JsonObject, JsonPrimitive}
import java.net.URI
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, Executors, RejectedExecutionException, ScheduledFuture, ThreadFactory, TimeUnit}
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** The SoCLess language server: "View design" code lenses on ChiselSim tests,
  * the `socless.viewDesign` command, save-triggered refresh, `socless/cursor`
  * (cursor to focused class) and box-to-source via `window/showDocument`.
  * It never duplicates Metals: no diagnostics, completion, hover or definition.
  *
  * Threading: lsp4j's reader thread only records text, hands parse work to a
  * single parser thread (lenses, cursor) and queues view runs; it never waits
  * on the client. Mill and the probe run only on the [[RunQueue]] worker,
  * because [[MillBridge]] holds a lock across multi-second Mill calls.
  *
  * `runnerFor` and `cpFor` get the workspace root from `initialize`.
  * `runnerFor` is called once, on the first view (it extracts the probe jar);
  * a `Left` runner (the probe jar could not be extracted) is reported on
  * every command.
  * `onExit` runs on the `exit` notification (Main exits the process there).
  */
final class SoclessLanguageServer(
    cpFor: os.Path => ClasspathProvider,
    runnerFor: os.Path => Either[String, ViewRunner],
    http: LiveHttp,
    openBrowser: String => Unit,
    log: String => Unit = m => Console.err.println(s"[socless-lsp] $m"),
    debounceMs: Long = 500,
    onExit: () => Unit = () => (),
) extends LanguageServer with LanguageClientAware {
  import SoclessLanguageServer._

  private final class Workspace(val root: os.Path, val cp: ClasspathProvider) {
    lazy val runner: Either[String, ViewRunner] = runnerFor(root) // first view
    val queue = new RunQueue(req => exec(this, req), onResult)
    val index = new SimBaseIndex(root) // base traits reaching ChiselSim, used by lenses
  }

  @volatile private var client: LanguageClient = null
  @volatile private var clientPanel                = false // client sent initializationOptions.panel == "client"
  @volatile private var ws: Option[Workspace]  = None
  @volatile private var stopped                = false // after shutdown/exit every request is a no-op
  @volatile private var shutdownRequested      = false

  /** Whether `shutdown` came before `exit`/EOF (LSP: exit code 0, else 1). */
  def shutdownWasRequested: Boolean = shutdownRequested

  private val texts  = new ConcurrentHashMap[String, String] // open documents, full sync
  private val parser = Executors.newSingleThreadExecutor(daemon("socless-lsp-parse"))
  private val timer  = Executors.newSingleThreadScheduledExecutor(daemon("socless-lsp-debounce"))

  // Guarded by lock.
  private val lock          = new Object
  private var latest        = Option.empty[ViewRequest] // most recently submitted, compared by identity
  private var port          = Option.empty[Int]
  private var browserOpened = false
  private var pendingSave   = Option.empty[ScheduledFuture[_]]
  @volatile private var publishedSources = Set.empty[String] // keys of the published design's `sources`

  def connect(c: LanguageClient): Unit = client = c

  // ── lifecycle ──────────────────────────────────────────────────────────

  def initialize(p: InitializeParams): CompletableFuture[InitializeResult] = {
    val folder = Option(p.getWorkspaceFolders).flatMap(_.asScala.headOption).map(_.getUri)
    val root   = folder.orElse(Option(p.getRootUri)).flatMap(uriToPath)
    if (root.isEmpty) log("no workspace root in initialize: views are disabled")
    clientPanel = panelIsClient(p.getInitializationOptions)
    ws = root.map(r => new Workspace(r, cpFor(r)))
    warmIndex() // the first lens should not pay the cold parse of the workspace

    val caps = new ServerCapabilities
    caps.setCodeLensProvider(new CodeLensOptions(false))
    caps.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of(ViewCommand)))
    val sync = new TextDocumentSyncOptions
    sync.setOpenClose(true)
    sync.setChange(TextDocumentSyncKind.Full)
    sync.setSave(LspEither.forRight[java.lang.Boolean, SaveOptions](new SaveOptions(false)))
    caps.setTextDocumentSync(LspEither.forRight[TextDocumentSyncKind, TextDocumentSyncOptions](sync))
    CompletableFuture.completedFuture(new InitializeResult(caps, new ServerInfo("socless-lsp")))
  }

  def shutdown(): CompletableFuture[Object] = {
    shutdownRequested = true
    stopAll()
    CompletableFuture.completedFuture(null)
  }

  def exit(): Unit = { stopAll(); onExit() }

  /** Stops the run queue, the helper threads and the HTTP endpoint. */
  def stopAll(): Unit = {
    stopped = true
    ws.foreach(_.queue.close())
    parser.shutdownNow()
    timer.shutdownNow()
    http.stop()
  }

  // ── documents ──────────────────────────────────────────────────────────

  object textDocuments extends TextDocumentService {
    def didOpen(p: DidOpenTextDocumentParams): Unit =
      texts.put(p.getTextDocument.getUri, p.getTextDocument.getText)

    def didChange(p: DidChangeTextDocumentParams): Unit =
      p.getContentChanges.asScala.lastOption.foreach(c => texts.put(p.getTextDocument.getUri, c.getText))

    def didClose(p: DidCloseTextDocumentParams): Unit = texts.remove(p.getTextDocument.getUri)

    def didSave(p: DidSaveTextDocumentParams): Unit =
      if (!stopped) for {
        w    <- ws
        file <- uriToPath(p.getTextDocument.getUri)
        if file.ext == "scala" && file.startsWith(w.root)
      } lock.synchronized {
        w.index.invalidate() // a base trait may have changed: the next lens re-stats
        warmIndex()
        pendingSave.foreach(_.cancel(false))
        try pendingSave = Some(timer.schedule((() => rerun()): Runnable, debounceMs, TimeUnit.MILLISECONDS))
        catch { case _: RejectedExecutionException => () } // shut down meanwhile
      }

    override def codeLens(p: CodeLensParams): CompletableFuture[java.util.List[_ <: CodeLens]] = {
      val uri = p.getTextDocument.getUri
      val none = CompletableFuture.completedFuture[java.util.List[_ <: CodeLens]](java.util.List.of())
      if (stopped) none
      else
        try CompletableFuture.supplyAsync(() => lenses(uri): java.util.List[_ <: CodeLens], parser)
        catch { case _: RejectedExecutionException => none }
    }
  }

  def getTextDocumentService: TextDocumentService = textDocuments

  /** Builds the base index on the parser thread, off the reader thread. */
  private def warmIndex(): Unit =
    for (w <- ws) try parser.execute(() => { w.index.bases(); () })
    catch { case _: RejectedExecutionException => () } // shut down meanwhile

  /** Open text, else the file on disk, else nothing. */
  private def textOf(uri: String): Option[String] =
    Option(texts.get(uri)).orElse(uriToPath(uri).filter(os.isFile).flatMap(f => Try(os.read(f)).toOption))

  /** Lenses only in a Mill project (a `build.mill` above the file: a cheap
    * filesystem walk, never a Mill call). */
  private def lenses(uri: String): java.util.List[CodeLens] =
    uriToPath(uri).filter(MillBridge.findRoot(_).isDefined).flatMap(_ => textOf(uri))
      .toSeq.flatMap { text =>
        val (sim, subject) = ws.fold((Set.empty[String], Set.empty[String]))(_.index.both())
        TestDiscovery.find(text, sim, subject)
      }.map { t =>
      val at = new Position(t.line, 0)
      val args = java.util.List.of[Object](uri, t.suite, t.name)
      new CodeLens(new Range(at, at), new Command("View design", ViewCommand, args), null)
    }.asJava

  /** `socless/cursor`: the class around the editor's cursor becomes the panel's focus. */
  @JsonNotification("socless/cursor")
  def cursor(params: JsonObject): Unit = {
    val parsed = Try((params.get("uri").getAsString, params.get("line").getAsInt)).toOption
    if (!stopped) parsed.foreach { case (uri, line) =>
      try parser.execute { () =>
        val focus = textOf(uri).flatMap(TestDiscovery.enclosingClass(_, line)).getOrElse("")
        http.update(_.copy(focusClass = focus))
      }
      catch { case _: RejectedExecutionException => () } // shut down meanwhile
    }
  }

  // ── commands and runs ──────────────────────────────────────────────────

  object workspaceService extends WorkspaceService {
    def didChangeConfiguration(p: DidChangeConfigurationParams): Unit = ()
    def didChangeWatchedFiles(p: DidChangeWatchedFilesParams): Unit = ()

    override def executeCommand(p: ExecuteCommandParams): CompletableFuture[Object] = {
      if (stopped) ()
      else if (p.getCommand == ViewCommand) {
        val args = Option(p.getArguments).map(_.asScala.toSeq).getOrElse(Nil).map {
          case j: JsonPrimitive => j.getAsString
          case o                => String.valueOf(o)
        }
        try view(args)
        catch { case NonFatal(e) => log(s"view command failed: $e"); fail(String.valueOf(e.getMessage)) }
      } else log(s"unknown command ${p.getCommand}")
      CompletableFuture.completedFuture(null)
    }
  }

  def getWorkspaceService: WorkspaceService = workspaceService

  private def view(args: Seq[String]): Unit = (args, ws) match {
    case (_, None) => fail("SoCLess: no workspace root (the editor sent neither workspaceFolders nor rootUri)")
    case (Seq(uri, suite, test), Some(w)) =>
      (w.runner, uriToPath(uri)) match {
        case (Left(msg), _) => fail(msg)
        case (_, None)      => fail(s"SoCLess: not a file URI: $uri")
        case (Right(_), Some(file)) =>
          openPanel()
          submit(w, ViewRequest(file, suite, test))
      }
    case _ => fail(s"$ViewCommand expects [uri, suite, test], got ${args.mkString("[", ", ", "]")}")
  }

  /** Starts the HTTP endpoint on first use and opens the panel once. */
  private def openPanel(): Unit = {
    val url = lock.synchronized {
      if (port.isEmpty) port = Some(http.start())
      if (clientPanel) { sendOpenPanel(s"http://127.0.0.1:${port.get}/"); None }
      else if (browserOpened) None
      else { browserOpened = true; Some(s"http://127.0.0.1:${port.get}/") }
    }
    url.foreach { u =>
      val c = client
      if (c == null) browse(u)
      else {
        val sd = new ShowDocumentParams(u)
        sd.setExternal(true)
        // never wait here: the response arrives on this (reader) thread
        c.showDocument(sd).whenComplete { (r, e) =>
          if (e != null || r == null || !r.isSuccess) browse(u)
        }
      }
    }
  }

  /** Client-hosted panel: tell the client where the UI is, on every view. */
  private def sendOpenPanel(url: String): Unit = client match {
    case c: SoclessClient => c.openPanel(new OpenPanelParams(url))
    case _                => log("panel=client but the client connection has no socless/openPanel")
  }

  private def browse(url: String): Unit =
    try openBrowser(url) catch { case NonFatal(e) => log(s"cannot open a browser on $url: $e") }

  private def submit(w: Workspace, req: ViewRequest): Unit = lock.synchronized {
    latest = Some(req)
    http.update(_.copy(status = "running", message = "", test = req.test))
    w.queue.submit(req)
  }

  /** Re-runs the last view (a fresh request, so its result is the latest). */
  private def rerun(): Unit =
    for (w <- ws) lock.synchronized { latest.foreach(r => submit(w, r.copy())) }

  // Runs on the RunQueue worker: the only place Mill and the probe are called.
  private def exec(w: Workspace, req: ViewRequest): RunResult =
    w.cp.testFork(req.file) match {
      case Left(msg)   => Failed("classpath", msg)
      case Right(fork) => w.runner.fold(Failed("probe-failure", _), _.run(fork, req.suite, req.test))
    }

  private def onResult(req: ViewRequest, res: RunResult): Unit = {
    // serialized outside the lock, published under it so a stale result can never win
    val json = res match { case Viewed(d) => upickle.default.write(d); case _ => "" }
    val error = lock.synchronized {
      if (!latest.exists(_ eq req)) None // superseded, or a later command failed
      else res match {
        case Viewed(d) =>
          http.publishJson(json)
          publishedSources = d.sources.keySet
          http.update(_.copy(status = "ok", message = ""))
          None
        case Failed(_, msg) =>
          http.update(_.copy(status = "error", message = msg))
          Some(msg)
      }
    }
    error.foreach(showError)
  }

  /** A command-level failure: panel state plus an editor message; the design stays. */
  private def fail(msg: String): Unit = {
    lock.synchronized {
      latest = None // a run still in flight must not overwrite this error
      http.update(_.copy(status = "error", message = msg))
    }
    showError(msg)
  }

  private def showError(msg: String): Unit = {
    val c = client
    if (c != null) c.showMessage(new MessageParams(MessageType.Error, msg)) else log(msg)
  }

  // ── panel to editor ────────────────────────────────────────────────────

  /** `POST /open` from the panel: show `file` (absolute, workspace-relative or
    * a `file:` URI) at the 1-based source `line` (as in `SourceRef`; converted
    * to LSP's 0-based position) in the editor. Only a file under the workspace
    * root or a key of the published design's `sources` is opened
    * ([[SoclessLanguageServer.openTarget]]); anything else is ignored and
    * logged. Runs on the HTTP thread, never waits. */
  def openInEditor(file: String, line: Int): Unit = {
    val c = client
    ws.flatMap(w => openTarget(file, w.root, publishedSources)) match {
      case None => log(s"ignored /open of $file: not under the workspace root nor a source of the design")
      case Some(p) if c != null =>
        val at = new Position(math.max(line - 1, 0), 0)
        val sd = new ShowDocumentParams(p.toNIO.toUri.toString)
        sd.setTakeFocus(true)
        sd.setSelection(new Range(at, at))
        c.showDocument(sd)
        ()
      case _ => log(s"cannot open $file:$line in the editor")
    }
  }
}

object SoclessLanguageServer {
  val ViewCommand = "socless.viewDesign"

  private def daemon(name: String): ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, name); t.setDaemon(true); t
  }

  /** Where `POST /open` may go: `file` (a `file:` URI, or a path absolute or
    * relative to `root`) after normalizing `..`, when it lies under `root`
    * or `file` is exactly a key of the published design's `sources`. */
  private[lsp] def openTarget(file: String, root: os.Path, sources: Set[String]): Option[os.Path] = {
    val raw =
      if (file.startsWith("file:")) Try(java.nio.file.Paths.get(new URI(file))).toOption
      else Try(root.toNIO.resolve(file)).toOption
    raw.map(_.normalize()).filter(_.isAbsolute)
      .filter(p => p.startsWith(root.toNIO) || sources.contains(file))
      .map(os.Path(_))
  }

  /** `initializationOptions` is a Gson element (or null): only `{"panel":"client"}` opts in. */
  private[lsp] def panelIsClient(opts: Any): Boolean = opts match {
    case o: JsonObject =>
      Option(o.get("panel")).filter(_.isJsonPrimitive).exists(_.getAsString == "client")
    case _ => false
  }

  private def uriToPath(uri: String): Option[os.Path] =
    Try(os.Path(java.nio.file.Paths.get(new URI(uri)))).toOption
}
