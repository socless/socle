package socless.lsp

import com.google.gson.{JsonArray, JsonObject, JsonPrimitive}
import java.net.URI
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, Executors, RejectedExecutionException, ScheduledFuture, ThreadFactory, TimeUnit}
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import org.eclipse.lsp4j.jsonrpc.services.{JsonNotification, JsonRequest}
import org.eclipse.lsp4j.services._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal
import socless.schema.Design

/** The SoCLess language server: "View design" and "Simulate" code lenses on
  * ChiselSim tests, the `socless.viewDesign` and `socless.simulate` commands
  * (simulation: [[SimRunner]], independent of view runs), save-triggered refresh, `socless/cursor`
  * (cursor to focused class) and box-to-source via `window/showDocument`.
  * For a client-hosted panel (VS Code) it also answers `socless/tests` (the
  * workspace's ChiselSim tests, [[WorkspaceTests]]) and sends
  * `socless/testsChanged` when a save changes them and `socless/status` when
  * the run state changes.
  * It never duplicates Metals: no diagnostics, completion, hover or definition.
  *
  * Threading: lsp4j's reader thread only records text, hands parse work to a
  * single parser thread (lenses, cursor), queues view runs and starts
  * simulations; it never waits on the client. Mill runs only on the
  * [[RunQueue]] worker (view runs, with the probe) and on the
  * [[SimRunner]]'s `socless-sim-runner` thread, never on the reader thread,
  * because [[MillBridge]] holds a lock across multi-second Mill calls (which
  * also serializes those two).
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
    simFor: os.Path => SimRunner = root => new SimRunner(root),
) extends LanguageServer with LanguageClientAware {
  import SoclessLanguageServer._

  private final class Workspace(val root: os.Path, val cp: ClasspathProvider) {
    lazy val runner: Either[String, ViewRunner] = runnerFor(root) // first view
    val queue = new RunQueue(req => exec(this, req), onResult)
    val index = new SimBaseIndex(root) // base traits reaching ChiselSim, used by lenses
    val sim   = simFor(root)
    val tests = new WorkspaceTests(root, index)
  }

  @volatile private var client: LanguageClient = null
  @volatile private var clientPanel                = false // client sent initializationOptions.panel == "client"
  @volatile private var ws: Option[Workspace]  = None
  @volatile private var stopped                = false // after shutdown/exit every request is a no-op
  @volatile private var shutdownRequested      = false
  @volatile private var simSettings            = SimSettings() // initializationOptions, then didChangeConfiguration
  @volatile private var warnedFlag             = Option.empty[String] // last invalid waveFlag shown

  /** Whether `shutdown` came before `exit`/EOF (LSP: exit code 0, else 1). */
  def shutdownWasRequested: Boolean = shutdownRequested

  private val texts  = new ConcurrentHashMap[String, String] // open documents, full sync
  private val parser = Executors.newSingleThreadExecutor(daemon("socless-lsp-parse"))
  private val timer  = Executors.newSingleThreadScheduledExecutor(daemon("socless-lsp-debounce"))
  // Custom notifications to the client, in order, never sent under `lock`.
  private val notifier = Executors.newSingleThreadExecutor(daemon("socless-lsp-notify"))

  // Guarded by lock.
  private val lock          = new Object
  private var latest        = Option.empty[ViewRequest] // most recently submitted, compared by identity
  private var port          = Option.empty[Int]
  private var browserOpened = false
  private var pendingSave   = Option.empty[ScheduledFuture[_]]
  private var pendingTests  = Option.empty[ScheduledFuture[_]]
  @volatile private var lastTests = Option.empty[JsonObject] // last `socless/tests` result computed
  @volatile private var publishedSources = Set.empty[String] // keys of the published design's `sources`
  @volatile private[lsp] var publishedPorts = Set.empty[String] // the published design's top-level port names
  // A Simulate waiting for its capture (guarded by lock): started by that
  // capture's result; when a newer request replaces the capture in the queue
  // (so it never runs), it waits for the newer one instead.
  private var pendingSim = Option.empty[(Workspace, SimRequest, ViewRequest)]
  private var simGen     = 0L // guarded by lock: simulations started so far
  // The published design and its `<suite>#<test>`, for the wave follower.
  @volatile private var publishedDesign = Option.empty[(Design, String)]
  // `surfer` viewer (guarded by lock): the WCP session with the Surfer of the
  // last simulation, its trace and `<suite>#<test>`; replaced by each Simulate.
  private var wcp = Option.empty[(WcpSession, LiveHttp.Trace, String)]
  private val follower = new WaveFollower(() => followContext, log)

  def connect(c: LanguageClient): Unit = client = c

  // ── lifecycle ──────────────────────────────────────────────────────────

  def initialize(p: InitializeParams): CompletableFuture[InitializeResult] = {
    val folder = Option(p.getWorkspaceFolders).flatMap(_.asScala.headOption).map(_.getUri)
    val root   = folder.orElse(Option(p.getRootUri)).flatMap(uriToPath)
    if (root.isEmpty) log("no workspace root in initialize: views are disabled")
    clientPanel = panelIsClient(p.getInitializationOptions)
    configureSim(p.getInitializationOptions)
    ws = root.map(r => new Workspace(r, cpFor(r)))
    warmIndex() // the first lens should not pay the cold parse of the workspace

    val caps = new ServerCapabilities
    caps.setCodeLensProvider(new CodeLensOptions(false))
    caps.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of(ViewCommand, SimulateCommand)))
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
    ws.foreach { w => w.queue.close(); w.sim.close() }
    follower.close()
    lock.synchronized { val s = wcp; wcp = None; s }.foreach(_._1.close())
    parser.shutdownNow()
    timer.shutdownNow()
    notifier.shutdownNow()
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
        pendingTests.foreach(_.cancel(false))
        try {
          pendingSave = Some(timer.schedule((() => rerun()): Runnable, debounceMs, TimeUnit.MILLISECONDS))
          if (clientPanel) pendingTests = Some(timer.schedule((() => checkTests(w)): Runnable, debounceMs, TimeUnit.MILLISECONDS))
        } catch { case _: RejectedExecutionException => () } // shut down meanwhile
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
      Seq(new CodeLens(new Range(at, at), new Command("View design", ViewCommand, args), null),
        new CodeLens(new Range(at, at), new Command("Simulate", SimulateCommand, args), null))
    }.flatten.asJava

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

  // ── workspace tests (client-hosted panel) ─────────────────────────────

  /** `socless/tests`: `{suites: [{suite, file (uri), tests: [{name, line}]}]}`,
    * `line` 0-based as in lenses. Computed on the parser thread. */
  @JsonRequest("socless/tests")
  def tests(): CompletableFuture[JsonObject] = {
    val none = CompletableFuture.completedFuture(testsJson(Nil))
    ws match {
      case Some(w) if !stopped =>
        try CompletableFuture.supplyAsync(() => { val j = testsJson(w.tests.suites()); lastTests = Some(j); j }, parser)
        catch { case _: RejectedExecutionException => none }
      case _ => none
    }
  }

  /** After a save (debounced): `socless/testsChanged` when the tests differ from the last computed. */
  private def checkTests(w: Workspace): Unit =
    try parser.execute { () =>
      val j       = testsJson(w.tests.suites())
      val changed = lastTests.exists(_ != j)
      lastTests = Some(j)
      if (changed) toClient(_.testsChanged())
    } catch { case _: RejectedExecutionException => () } // shut down meanwhile

  private def testsJson(suites: Seq[(String, os.Path, Seq[TestCase])]): JsonObject = {
    val arr = new JsonArray
    for ((suite, file, cases) <- suites) {
      val o = new JsonObject
      o.addProperty("suite", suite)
      o.addProperty("file", file.toNIO.toUri.toString)
      val ts = new JsonArray
      for (c <- cases) {
        val t = new JsonObject
        t.addProperty("name", c.name)
        t.addProperty("line", c.line)
        ts.add(t)
      }
      o.add("tests", ts)
      arr.add(o)
    }
    val out = new JsonObject
    out.add("suites", arr)
    out
  }

  /** Updates the panel state; a client-hosted panel gets `socless/status` when
    * the status, suite, test or message changed (not the cursor focus). */
  private def ideUpdate(f: IdeState => IdeState): Unit = {
    val (b, a) = http.update(f)
    if (clientPanel && (b.status, b.suite, b.test, b.message) != (a.status, a.suite, a.test, a.message))
      toClient(_.status(new StatusParams(a.status, a.suite, a.test, a.message)))
  }

  /** Sends a custom notification on the notifier thread (in call order); nothing for a plain client. */
  private def toClient(send: SoclessClient => Unit): Unit = client match {
    case c: SoclessClient =>
      try notifier.execute { () =>
        try send(c) catch { case NonFatal(e) => log(s"cannot notify the client: $e") }
      } catch { case _: RejectedExecutionException => () } // shut down meanwhile
    case _ => ()
  }

  // ── commands and runs ──────────────────────────────────────────────────

  object workspaceService extends WorkspaceService {
    def didChangeConfiguration(p: DidChangeConfigurationParams): Unit =
      SimSettings.section(p.getSettings).foreach(configureSim)
    def didChangeWatchedFiles(p: DidChangeWatchedFilesParams): Unit = ()

    override def executeCommand(p: ExecuteCommandParams): CompletableFuture[Object] = {
      lazy val args = Option(p.getArguments).map(_.asScala.toSeq).getOrElse(Nil).map {
        case j: JsonPrimitive => j.getAsString
        case o                => String.valueOf(o)
      }
      if (stopped) ()
      else if (p.getCommand == ViewCommand) {
        try view(args)
        catch { case NonFatal(e) => log(s"view command failed: $e"); fail(String.valueOf(e.getMessage)) }
      } else if (p.getCommand == SimulateCommand) {
        try simulate(args)
        catch { case NonFatal(e) => log(s"simulate command failed: $e"); showError(String.valueOf(e.getMessage)) }
      } else log(s"unknown command ${p.getCommand}")
      CompletableFuture.completedFuture(null)
    }
  }

  def getWorkspaceService: WorkspaceService = workspaceService

  private def view(args: Seq[String]): Unit = (args, ws) match {
    case (_, None) => fail("SoCLess: no workspace root (the editor sent neither workspaceFolders nor rootUri)")
    case (Seq(uri, suite, test), Some(w)) => capture(w, uri, suite, test)
    case _ => fail(s"$ViewCommand expects [uri, suite, test], got ${args.mkString("[", ", ", "]")}")
  }

  /** View design of one test: opens the panel and queues the capture. `then`
    * (a Simulate) waits for that capture's result; it runs at once when the
    * capture cannot be queued (the failure is reported). */
  private def capture(w: Workspace, uri: String, suite: String, test: String,
      `then`: Option[SimRequest] = None): Unit =
    (w.runner, uriToPath(uri)) match {
      case (Left(msg), _) => fail(msg, Some((suite, test))); `then`.foreach(startSim(w, _))
      case (_, None)      => fail(s"SoCLess: not a file URI: $uri", Some((suite, test))); `then`.foreach(startSim(w, _))
      case (Right(_), Some(file)) =>
        openPanel(test)
        val req = ViewRequest(file, suite, test)
        val replaced = lock.synchronized {
          val old = `then`.flatMap(_ => pendingSim.map(_._2))
          `then`.foreach(s => pendingSim = Some((w, s, req)))
          submit(w, req)
          old
        }
        replaced.foreach(r => show(MessageType.Info, s"replaced the pending simulation of ${r.suite} ${r.test}"))
    }

  /** Simulate: first a View design capture of the same test (the same queue:
    * it publishes the design and opens the panel), then the simulation, whatever
    * the capture gave (a capture error stays on the panel). One simulation at a
    * time, beside view runs: a second one is refused (Info), the running one
    * goes on. The editor gets the outcome (Info on pass, Warning otherwise).
    * The panel never hides a view's status: a simulation started while it
    * shows `error` or `running` leaves it alone; otherwise it shows
    * `simulating`, and at the end the state captured at the start comes back
    * with the outcome as message, unless a view run changed the status
    * meanwhile (then the view's status stays). The trace (`trace`,
    * `traceTest`, `/wave/<id>`) is dropped when the simulation starts and set
    * after a run that left one (pass or fail). With the `surfer` viewer, the
    * previous Surfer and its WCP session end when the simulation starts, and a
    * run that left a trace starts a new one ([[WcpSession]]) showing the top
    * module's signals; panel selections then drive it ([[waveSelect]]). A
    * Surfer that exits before connecting gets a Warning and is started again
    * plainly on the trace ([[surferExited]]). A Simulate that replaces one
    * still waiting for its capture says so (Info). */
  private def simulate(args: Seq[String]): Unit = (args, ws) match {
    case (_, None) => showError("SoCLess: no workspace root (the editor sent neither workspaceFolders nor rootUri)")
    case (Seq(uri, suite, test), Some(w)) =>
      uriToPath(uri) match {
        case None => showError(s"SoCLess: not a file URI: $uri")
        case Some(file) =>
          w.sim.busy match {
            case Some(r) => refused(r)
            case None => capture(w, uri, suite, test, Some(SimRequest(file, suite, test)))
          }
      }
    case _ => showError(s"$SimulateCommand expects [uri, suite, test], got ${args.mkString("[", ", ", "]")}")
  }

  private def refused(r: SimRequest): Unit =
    show(MessageType.Info, s"a simulation is already running: ${r.suite} ${r.test}")

  /** Starts the pending simulation when `done` is its capture. */
  private def startPendingSim(done: ViewRequest): Unit = {
    val next = lock.synchronized {
      val n = pendingSim.filter(_._3 eq done)
      if (n.isDefined) pendingSim = None
      n
    }
    next.foreach { case (w, sim, _) => startSim(w, sim) }
  }

  private def startSim(w: Workspace, req: SimRequest): Unit = {
    val before = new java.util.concurrent.atomic.AtomicReference[Option[IdeState]](None)
    val gen    = new java.util.concurrent.atomic.AtomicLong
    val old    = new java.util.concurrent.atomic.AtomicReference[Option[WcpSession]](None)
    // runs under the SimRunner's lock: the previous session is only taken here, closed below
    val started = () => {
      old.set(lock.synchronized {
        simGen += 1
        gen.set(simGen)
        http.serveTrace(None) // the previous trace stops resolving now
        ideUpdate { s0 =>
          val s = s0.copy(trace = "", traceTest = "")
          if (s.status == "error" || s.status == "running") s
          else { before.set(Some(s)); s.copy(status = "simulating", message = "", test = req.test, suite = req.suite) }
        }
        val o = wcp
        wcp = None
        o.map(_._1)
      })
    }
    try w.sim.start(req, simSettings, () => w.cp.testFork(req.file), started, o => simDone(w, req, o, before.get, gen.get))
      .foreach(refused)
    // the previous Surfer, outside the SimRunner's lock (killing it may wait); the
    // run thread asks Mill for the test fork first, so this precedes the run dir's wipe
    finally old.getAndSet(None).foreach(_.close())
  }

  /** `gen`: this simulation's [[simGen]]. Never takes the SimRunner's lock
    * under `lock` (its `start` calls `started` the other way round). */
  private def simDone(w: Workspace, req: SimRequest, o: SimOutcome, before: Option[IdeState], gen: Long): Unit = {
    // this run's trace (a passing or failing run's; none when it left none or
    // timed out), names read from its header
    val trace = w.sim.currentTrace.map { case (id, file, test) =>
      (LiveHttp.Trace(id, file, TraceNames.read(file, publishedPorts)), test)
    }
    // `surfer` viewer: a new Surfer on the trace, driven over WCP (started outside the lock)
    val current = lock.synchronized(simGen == gen) // a superseded run starts no Surfer
    val dir = w.sim.dirFor(req.suite, req.test)
    val command = simSettings.surferCommand
    val surfer = trace.filter(_ => current && simSettings.waveViewer == "surfer" && !stopped).map { case (t, _) =>
      try Right(WcpSession.start(command, t.file, Some(dir / "surfer.log"), log, () => surferExited(gen, command, t.file, dir)))
      catch { case NonFatal(e) => Left(s"cannot start $command: ${e.getMessage}") }
    }
    val wcpProblem = surfer.flatMap(_.left.toOption)
    val message = o.message + wcpProblem.fold("")(e => s" ($e)")
    // a viewer that cannot start is easy to miss in a pass's Info: a Warning, and logged
    val viewerProblem = o.viewerProblem.orElse(wcpProblem)
    viewerProblem.foreach(e => log(s"simulate: $e"))
    val attached = lock.synchronized {
      val fresh = trace.filter(_ => simGen == gen && !stopped) // none when another simulation started meanwhile
      fresh.foreach { case (t, _) => http.serveTrace(Some(t)) }
      ideUpdate { s =>
        val restored = before match {
          case Some(b) if s.status == "simulating" && simGen == gen => // not a newer simulation's status
            s.copy(status = b.status, test = b.test, suite = b.suite, message = message)
          case _ => s
        }
        fresh.fold(restored) { case (t, test) => restored.copy(trace = s"/wave/${t.id}/trace", traceTest = test) }
      }
      (fresh, surfer.flatMap(_.toOption)) match {
        case (Some((t, test)), Some(session)) => wcp = Some((session, t, test)); true
        case _ => false
      }
    }
    if (attached) follower.showTop() // the first plan: the test's top module
    else surfer.flatMap(_.toOption).foreach(_.close()) // stale: a newer simulation started
    show(if (o.passed && viewerProblem.isEmpty) MessageType.Info else MessageType.Warning, message)
  }

  /** The WCP Surfer of simulation `gen` exited before connecting (one too old
    * for `--wcp-initiate`): unless a newer simulation started, a Warning, and
    * the plain `<command> <trace>` so the waves still show (its output appended
    * to surfer.log, after the first one's error). */
  private def surferExited(gen: Long, command: String, trace: os.Path, dir: os.Path): Unit =
    if (!stopped && lock.synchronized(simGen == gen)) {
      val problem = SimRunner.launch(Seq(command, trace.toString), trace, dir, log, append = true)
      show(MessageType.Warning, s"Surfer exited before connecting (it may be too old for --wcp-initiate); see ${dir / "surfer.log"}." +
        problem.fold(" Opened the trace without following the panel.")(p => s" ($p)"))
    }

  /** What following needs: a live session, the trace, and the published
    * design of the trace's test (it pauses while the panel shows another one). */
  private def followContext: Option[WaveFollower.Context] =
    for {
      (session, trace, test) <- lock.synchronized(wcp)
      if session.alive
      (design, designTest) <- publishedDesign
      if designTest == test
    } yield WaveFollower.Context(session, test, design, trace.names.prefix, trace.names.names, simSettings.wave)

  /** `POST /wave/select` from the panel: acts only with the `surfer` viewer
    * ([[WaveFollower.select]]), without a second debounce (the panel waits
    * `wavesFollowDelayMs` before it posts). Runs on the HTTP thread, never waits. */
  def waveSelect(req: SelectRequest): Unit =
    if (!stopped && simSettings.waveViewer == "surfer") follower.select(req, debounce = false)

  /** Simulate settings from a flat settings object; an invalid `waveFlag` is
    * logged and shown once (Warning), and the default is used. */
  private def configureSim(opts: Any): Unit = {
    simSettings = SimSettings.fromInit(opts)
    ideUpdate(_.copy(waveViewer = simSettings.waveViewer, waveSettings = Some(simSettings.wave)))
    SimSettings.invalidFlag(opts).filterNot(warnedFlag.contains).foreach { bad =>
      warnedFlag = Some(bad)
      val msg = s"""SoCLess: invalid waveFlag "$bad" (expected an option name such as emitFst); using ${SimSettings.DefaultWaveFlag}"""
      log(msg)
      show(MessageType.Warning, msg)
    }
  }

  /** Starts the HTTP endpoint on first use and opens the panel once. */
  private def openPanel(test: String): Unit = {
    val url = lock.synchronized {
      if (port.isEmpty) port = Some(http.start())
      if (clientPanel) { sendOpenPanel(s"http://127.0.0.1:${port.get}/", test); None }
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
  private def sendOpenPanel(url: String, test: String): Unit = client match {
    case c: SoclessClient => c.openPanel(new OpenPanelParams(url, test))
    case _                => log("panel=client but the client connection has no socless/openPanel")
  }

  private def browse(url: String): Unit =
    try openBrowser(url) catch { case NonFatal(e) => log(s"cannot open a browser on $url: $e") }

  private def submit(w: Workspace, req: ViewRequest): Unit = lock.synchronized {
    latest = Some(req)
    ideUpdate(_.copy(status = "running", message = "", test = req.test, suite = req.suite))
    // a capture replaced in the queue never runs: its Simulate waits for this request
    for (old <- w.queue.submit(req); (pw, sim, view) <- pendingSim if view eq old) pendingSim = Some((pw, sim, req))
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
          publishedPorts = d.modules.find(_.name == d.top).fold(Set.empty[String])(_.ports.map(_.name).toSet)
          publishedDesign = Some((d, s"${req.suite}#${req.test}"))
          ideUpdate(_.copy(status = "ok", message = ""))
          None
        case Failed(_, msg) =>
          ideUpdate(_.copy(status = "error", message = msg))
          Some(msg)
      }
    }
    error.foreach(showError)
    startPendingSim(req) // a Simulate waiting for this capture
  }

  /** A command-level failure: panel state plus an editor message; the design
    * stays. `key` (suite, test) names the test it was for, when known. */
  private def fail(msg: String, key: Option[(String, String)] = None): Unit = {
    lock.synchronized {
      latest = None // a run still in flight must not overwrite this error
      ideUpdate { s =>
        key.fold(s) { case (suite, test) => s.copy(suite = suite, test = test) }.copy(status = "error", message = msg)
      }
    }
    showError(msg)
  }

  private def showError(msg: String): Unit = show(MessageType.Error, msg)

  private def show(kind: MessageType, msg: String): Unit = {
    val c = client
    if (c != null) c.showMessage(new MessageParams(kind, msg)) else log(msg)
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
  val ViewCommand     = "socless.viewDesign"
  val SimulateCommand = "socless.simulate"

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
