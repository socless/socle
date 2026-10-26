package socless.lsp

import com.google.gson.{JsonObject, JsonPrimitive}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.channels.{Channels, Pipe}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.{LanguageClient, LanguageServer}
import scala.jdk.CollectionConverters._
import socless.schema.Design
import utest._

/** The language server in-process, over lsp4j launchers on in-memory pipes,
  * with a stub classpath (the example test classpath from build.mill), so no
  * nested Mill. View runs fork the real probe. */
object LanguageServerTest extends TestSuite {
  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private val root    = os.Path(prop("socless.root"))
  private val exampleCp = prop("example.cp").split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(os.Path(_))
  private val javaBin = (os.Path(sys.props("java.home")) / "bin" / "java").toString

  private val gcdSpec = root / "example" / "test" / "src" / "gcd" / "GcdSpec.scala"
  private val gcdSrc  = root / "example" / "src" / "gcd" / "GCD.scala"
  private val deepSoc = root / "example" / "src" / "deephier" / "DeepSoc.scala"
  private def uri(p: os.Path) = p.toNIO.toUri.toString

  /** The server as the editor sees it, with the custom request (lsp4j drops
    * the result of a request its remote interface does not declare). */
  trait SoclessServer extends LanguageServer {
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("socless/tests")
    def tests(): CompletableFuture[JsonObject]
  }

  /** Records what the server sends to the editor. */
  final class RecordingClient(showDocumentOk: Boolean) extends SoclessClient {
    val panels    = new ConcurrentLinkedQueue[OpenPanelParams]
    override def openPanel(p: OpenPanelParams): Unit = panels.add(p)
    val statuses     = new ConcurrentLinkedQueue[StatusParams]
    override def status(p: StatusParams): Unit = statuses.add(p)
    val testsChangedCount = new java.util.concurrent.atomic.AtomicInteger
    override def testsChanged(): Unit = { testsChangedCount.incrementAndGet(); () }
    val documents = new ConcurrentLinkedQueue[ShowDocumentParams]
    val messages  = new ConcurrentLinkedQueue[MessageParams]
    def telemetryEvent(o: Object): Unit = ()
    def publishDiagnostics(p: PublishDiagnosticsParams): Unit = ()
    def showMessage(p: MessageParams): Unit = messages.add(p)
    def showMessageRequest(p: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
      CompletableFuture.completedFuture(null)
    def logMessage(p: MessageParams): Unit = ()
    override def showDocument(p: ShowDocumentParams): CompletableFuture[ShowDocumentResult] = {
      documents.add(p)
      CompletableFuture.completedFuture(new ShowDocumentResult(showDocumentOk))
    }
  }

  /** A server wired to a recording client; `cp` is what the stub classpath returns. */
  final class Fixture(showDocumentOk: Boolean = true, panel: Option[String] = None, ws: os.Path = root,
      simJava: String = javaBin, initOpts: Seq[(String, String)] = Nil) {
    @volatile var cp: Either[String, Seq[os.Path]] = Right(exampleCp)
    @volatile var cpFn: () => Either[String, Seq[os.Path]] = () => cp
    val browser = new ConcurrentLinkedQueue[String]
    val log     = new ConcurrentLinkedQueue[String]
    private val stub = new ClasspathProvider {
      def testClasspath(file: os.Path) = cpFn()
    }
    lazy val http: LiveHttp = new LiveHttp((f, l) => server.openInEditor(f, l), onSelect = r => server.waveSelect(r))
    lazy val server: SoclessLanguageServer = new SoclessLanguageServer(
      _ => stub,
      r => Right(new ViewRunner(ViewRunner.extractProbeJar(), r, javaBin)),
      http,
      u => { browser.add(u); () },
      log = m => { log.add(m); () },
      simFor = r => new SimRunner(r, simJava, log = m => { log.add(m); () }),
    )
    val client = new RecordingClient(showDocumentOk)

    private val toServer = Pipe.open()
    private val toClient = Pipe.open()
    private val serverLauncher = new Launcher.Builder[SoclessClient]()
      .setLocalService(server).setRemoteInterface(classOf[SoclessClient])
      .setInput(Channels.newInputStream(toServer.source)).setOutput(Channels.newOutputStream(toClient.sink)).create()
    server.connect(serverLauncher.getRemoteProxy)
    serverLauncher.startListening()
    private val clientLauncher = new Launcher.Builder[SoclessServer]()
      .setLocalService(client).setRemoteInterface(classOf[SoclessServer])
      .setInput(Channels.newInputStream(toClient.source)).setOutput(Channels.newOutputStream(toServer.sink)).create()
    clientLauncher.startListening()
    val remote: SoclessServer = clientLauncher.getRemoteProxy

    val init = new InitializeParams
    init.setWorkspaceFolders(java.util.List.of(new WorkspaceFolder(uri(ws), "socless")))
    private val opts = panel.map("panel" -> _).toSeq ++ initOpts
    if (opts.nonEmpty) {
      val o = new JsonObject
      opts.foreach { case (k, v) => o.addProperty(k, v) }
      init.setInitializationOptions(o)
    }
    val capabilities = remote.initialize(init).get()
    remote.initialized(new InitializedParams)

    def open(p: os.Path): Unit =
      remote.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(uri(p), "scala", 1, os.read(p))))

    def lenses(p: os.Path): Seq[CodeLens] =
      remote.getTextDocumentService.codeLens(new CodeLensParams(new TextDocumentIdentifier(uri(p)))).get().asScala.toSeq

    def view(args: String*): Unit =
      remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.viewDesign", args.map(a => a: Object).asJava)).get()

    def save(p: os.Path): Unit =
      remote.getTextDocumentService.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri(p))))

    def cursor(p: os.Path, line: Int): Unit = {
      val o = new JsonObject
      o.addProperty("uri", uri(p))
      o.addProperty("line", line)
      clientLauncher.getRemoteEndpoint.notify("socless/cursor", o)
    }

    /** `socless/tests`, as JSON. */
    def tests(): ujson.Value = ujson.read(remote.tests().get().toString)

    def close(): Unit = { server.stopAll(); toServer.sink.close(); toClient.sink.close() }
  }

  private val httpClient = HttpClient.newHttpClient()
  private def get(base: String, path: String): HttpResponse[String] =
    httpClient.send(HttpRequest.newBuilder(URI.create(base + path.stripPrefix("/"))).build(),
      HttpResponse.BodyHandlers.ofString())
  private def post(base: String, path: String, body: String): HttpResponse[String] =
    httpClient.send(HttpRequest.newBuilder(URI.create(base + path.stripPrefix("/")))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
  private def ide(base: String): IdeState = upickle.default.read[IdeState](get(base, "/ide.json").body)

  private def eventually[T](what: String, timeoutMs: Long = 60000)(f: => Option[T]): T = {
    val end = System.currentTimeMillis() + timeoutMs
    var r   = Option.empty[T]
    while (r.isEmpty && System.currentTimeMillis() < end) {
      r = try f catch { case _: Exception => None }
      if (r.isEmpty) Thread.sleep(100)
    }
    r.getOrElse(sys.error(s"timed out waiting for $what"))
  }

  private val gcdArgs = Seq(uri(gcdSpec), "gcd.GcdSpec", "GCD computes gcd(48, 18)")

  // Shared by the tests below, which run in order: view, refresh, error, ...
  private lazy val fx = new Fixture()
  private lazy val base: String = fx.client.documents.asScala.find(_.getExternal == true)
    .map(_.getUri).getOrElse(sys.error("browser never opened"))

  override def utestAfterAll(): Unit = fx.close()

  val tests = Tests {
    test("capabilities") {
      val c = fx.capabilities.getCapabilities
      assert(c.getCodeLensProvider != null)
      assert(c.getExecuteCommandProvider.getCommands.asScala == Seq("socless.viewDesign", "socless.simulate"))
      val sync = c.getTextDocumentSync.getRight
      assert(sync.getChange == TextDocumentSyncKind.Full, sync.getOpenClose == true, sync.getSave != null)
    }
    test("lenses") {
      fx.open(gcdSpec)
      val ls = fx.lenses(gcdSpec)
      assert(ls.size == 2)
      def args(l: CodeLens) = l.getCommand.getArguments.asScala.map {
        case p: JsonPrimitive => p.getAsString
        case o                => String.valueOf(o)
      }
      assert(ls.map(l => (l.getCommand.getTitle, l.getCommand.getCommand)) ==
        Seq(("View design", "socless.viewDesign"), ("Simulate", "socless.simulate")))
      assert(ls.forall(args(_) == gcdArgs))
      assert(ls.forall(_.getRange.getStart.getLine == 8))
      fx.open(gcdSrc)
      assert(fx.lenses(gcdSrc).isEmpty)
    }
    test("no lens outside a Mill project") {
      val dir = os.temp.dir(prefix = "socless-nomill-")
      try {
        val f = dir / "GcdSpec.scala"
        os.write(f, os.read(gcdSpec))
        assert(MillBridge.findRoot(f).isEmpty) // precondition: no build.mill above the temp dir
        fx.open(f)
        assert(fx.lenses(f).isEmpty)
      } finally os.remove.all(dir)
    }
    test("lenses on suites inheriting ChiselSim refresh on save") {
      val dir  = os.temp.dir(prefix = "socless-simbase-ws-")
      val base = dir / "src" / "SimBase.scala"
      val spec = dir / "test" / "S.scala"
      os.write(dir / "build.mill", "")
      os.write(base, "package p\ntrait SimBase extends AnyFlatSpec with ChiselSim", createFolders = true)
      os.write(spec, "package q\nclass S extends p.SimBase {\n  behavior of \"A\"\n  it should \"x\" in { }\n}",
        createFolders = true)
      val f = new Fixture(ws = dir)
      try {
        f.open(spec)
        val ls = f.lenses(spec)
        assert(ls.map(_.getCommand.getArguments.asScala.map {
          case p: JsonPrimitive => p.getAsString
          case o                => String.valueOf(o)
        }.toSeq) == Seq.fill(2)(Seq(uri(spec), "q.S", "A should x")))
        assert(ls.head.getRange.getStart.getLine == 3)
        os.write.over(base, "package p\ntrait SimBase extends AnyFlatSpec")
        os.mtime.set(base, os.mtime(base) + 5000)
        f.save(base)
        assert(f.lenses(spec).isEmpty)
      } finally { f.close(); os.remove.all(dir) }
    }
    test("lenses follow unsaved edits") {
      val f = gcdSpec / os.up / "Unsaved.scala"
      val u = uri(f)
      fx.remote.getTextDocumentService.didOpen(new DidOpenTextDocumentParams(
        new TextDocumentItem(u, "scala", 1, "package x\nclass A")))
      assert(fx.lenses(f).isEmpty)
      val change = new TextDocumentContentChangeEvent(os.read(gcdSpec))
      fx.remote.getTextDocumentService.didChange(new DidChangeTextDocumentParams(
        new VersionedTextDocumentIdentifier(u, 2), java.util.List.of(change)))
      assert(fx.lenses(f).size == 2)
    }
    test("socless/tests lists the example suites") {
      val suites = fx.tests()("suites").arr.toSeq
      val names  = suites.map(_("suite").str)
      assert(names == names.sorted, names.distinct == names)
      val gcd = suites.find(_("suite").str == "gcd.GcdSpec").getOrElse(sys.error(s"no gcd.GcdSpec in $names"))
      assert(gcd("file").str == uri(gcdSpec))
      assert(gcd("tests").arr.map(t => (t("name").str, t("line").num.toInt)).toSeq == Seq(("GCD computes gcd(48, 18)", 8)))
      val deep = suites.find(_("suite").str == "deephier.DeepSocSpec").getOrElse(sys.error(s"no DeepSocSpec in $names"))
      assert(deep("tests").arr.map(_("name").str).contains("DeepSoc elaborates and steps"))
      // no suite from a non-test file, no suite without tests
      assert(!names.contains("gcd.GCD"), suites.forall(_("tests").arr.nonEmpty))
    }
    test("socless/tests on a base-trait workspace; socless/testsChanged after a save") {
      val dir  = os.temp.dir(prefix = "socless-tests-ws-")
      val base = dir / "src" / "SimBase.scala"
      val spec = dir / "test" / "S.scala"
      os.write(dir / "build.mill", "")
      os.write(base, "package p\ntrait SimBase extends AnyFlatSpec with ChiselSim", createFolders = true)
      val one = "package q\nclass S extends p.SimBase {\n  behavior of \"A\"\n  it should \"x\" in { }\n"
      os.write(spec, one + "}", createFolders = true)
      val f = new Fixture(ws = dir, panel = Some("client"))
      try {
        def tests(r: ujson.Value) = r("suites").arr.toSeq.map(s =>
          (s("suite").str, s("file").str, s("tests").arr.toSeq.map(t => (t("name").str, t("line").num.toInt))))
        assert(tests(f.tests()) == Seq(("q.S", uri(spec), Seq(("A should x", 3)))))
        // a save that changes no test sends nothing
        f.save(base)
        Thread.sleep(1500)
        assert(f.client.testsChangedCount.get == 0)
        os.write.over(spec, one + "  it should \"y\" in { }\n}")
        os.mtime.set(spec, os.mtime(spec) + 5000)
        f.save(spec)
        eventually("testsChanged")(Some(f.client.testsChangedCount.get).filter(_ == 1))
        assert(tests(f.tests()) == Seq(("q.S", uri(spec), Seq(("A should x", 3), ("A should y", 4)))))
        Thread.sleep(1000)
        assert(f.client.testsChangedCount.get == 1) // one burst, one notification
      } finally { f.close(); os.remove.all(dir) }
    }
    test("view") {
      val t0 = System.nanoTime()
      fx.view(gcdArgs: _*)
      val d = eventually("design.json") {
        val r = get(base, "/design.json")
        if (r.statusCode == 200) Some(upickle.default.read[Design](r.body)) else None
      }
      println(s"[LanguageServerTest] view took ${(System.nanoTime() - t0) / 1000000} ms")
      assert(d.top == "GCD")
      val st = eventually("status ok")(Some(ide(base)).filter(_.status == "ok"))
      assert(st.test == "GCD computes gcd(48, 18)")
      val opened = fx.client.documents.asScala.filter(_.getExternal == true).toSeq
      assert(opened.size == 1)
      assert(opened.head.getUri.matches("http://127\\.0\\.0\\.1:[0-9]+/"))
      assert(fx.browser.isEmpty)
    }
    test("refresh on save") {
      val lm0 = get(base, "/design.json").headers.firstValue("Last-Modified").get
      fx.save(gcdSpec)
      eventually("new Last-Modified") {
        Some(get(base, "/design.json").headers.firstValue("Last-Modified").get).filter(_ != lm0)
      }
      eventually("status ok")(Some(ide(base)).filter(_.status == "ok"))
      // a second command does not open the browser again
      fx.view(gcdArgs: _*)
      eventually("status ok")(Some(ide(base)).filter(_.status == "ok"))
      assert(fx.client.documents.asScala.count(_.getExternal == true) == 1)
    }
    test("error keeps design") {
      val before = get(base, "/design.json").body
      fx.cp = Left("[error] Gcd.scala:3: boom")
      fx.client.messages.clear()
      fx.save(gcdSpec)
      val st = eventually("status error")(Some(ide(base)).filter(_.status == "error"))
      assert(st.message.contains("boom"))
      val msgs = fx.client.messages.asScala.toSeq
      assert(msgs.exists(m => m.getType == MessageType.Error && m.getMessage.contains("boom")))
      val after = get(base, "/design.json")
      assert(after.statusCode == 200, after.body == before)
      assert(upickle.default.read[Design](after.body).top == "GCD")
    }
    test("not in a test module") {
      val msg = s"not in a Mill test module: $gcdSpec"
      fx.cp = Left(msg)
      fx.client.messages.clear()
      fx.log.clear()
      fx.view(gcdArgs: _*)
      eventually("showMessage")(fx.client.messages.asScala.find(_.getMessage.contains(msg)))
      assert(ide(base).status == "error")
      assert(!fx.log.asScala.exists(l => l.contains("Exception") || l.contains("failed")))
    }
    test("open") {
      // /open carries the 1-based SourceRef line; LSP positions are 0-based
      fx.client.documents.clear()
      val r = post(base, "/open", ujson.write(ujson.Obj("file" -> gcdSrc.toString, "line" -> 24)))
      assert(r.statusCode == 204)
      val d = eventually("showDocument")(fx.client.documents.asScala.headOption)
      assert(d.getUri == uri(gcdSrc), d.getSelection.getStart.getLine == 23, d.getTakeFocus == true)
      assert(d.getExternal != true)
      // a path relative to the workspace root resolves under it
      fx.client.documents.clear()
      post(base, "/open", ujson.write(ujson.Obj("file" -> "example/src/gcd/GCD.scala", "line" -> 3)))
      val rel = eventually("showDocument")(fx.client.documents.asScala.headOption)
      assert(rel.getUri == uri(gcdSrc), rel.getSelection.getStart.getLine == 2)
    }
    test("open outside the workspace is ignored") {
      fx.client.documents.clear()
      fx.log.clear()
      val outside = Seq("/etc/passwd", "example/../../../../../etc/passwd", "file:///etc/passwd")
      for (f <- outside) {
        val r = post(base, "/open", ujson.write(ujson.Obj("file" -> f, "line" -> 1)))
        assert(r.statusCode == 204)
      }
      eventually("three log lines")(Some(fx.log.asScala.count(_.contains("ignored /open"))).filter(_ == 3))
      Thread.sleep(300)
      assert(fx.client.documents.isEmpty)
    }
    test("open target rule") {
      import SoclessLanguageServer.openTarget
      val ws = os.Path("/work/proj")
      assert(openTarget("/work/proj/src/A.scala", ws, Set.empty) == Some(ws / "src" / "A.scala"))
      assert(openTarget("src/../src/A.scala", ws, Set.empty) == Some(ws / "src" / "A.scala"))
      assert(openTarget("file:///work/proj/A.scala", ws, Set.empty) == Some(ws / "A.scala"))
      assert(openTarget("../other/B.scala", ws, Set.empty) == None)
      assert(openTarget("/opt/lib/C.scala", ws, Set.empty) == None)
      assert(openTarget("/work/proj/../proj2/D.scala", ws, Set.empty) == None)
      // a key of the published design's sources is allowed even outside the root
      assert(openTarget("/opt/lib/C.scala", ws, Set("/opt/lib/C.scala")) == Some(os.Path("/opt/lib/C.scala")))
    }
    test("cursor") {
      val lines = os.read.lines(deepSoc)
      val inCluster = lines.indexWhere(_.startsWith("class Cluster")) + 2
      fx.cursor(deepSoc, inCluster)
      eventually("focusClass")(Some(ide(base)).filter(_.focusClass == "deephier.Cluster"))
      fx.cursor(deepSoc, 0) // the package line: no enclosing class
      eventually("focusClass cleared")(Some(ide(base)).filter(_.focusClass == ""))
    }
    test("browser fallback when the editor cannot open urls") {
      val f = new Fixture(showDocumentOk = false)
      try {
        f.cp = Left("nope")
        f.view(gcdArgs: _*)
        val url = eventually("openBrowser")(Option(f.browser.peek()))
        assert(url.matches("http://127\\.0\\.0\\.1:[0-9]+/"))
        f.view(gcdArgs: _*)
        eventually("second error")(Some(f.client.messages.size).filter(_ >= 2))
        assert(f.browser.size == 1)
      } finally f.close()
    }
    test("a superseded result is not published") {
      val f = new Fixture()
      try {
        val started = new java.util.concurrent.CountDownLatch(1)
        val release = new java.util.concurrent.CountDownLatch(1)
        f.cpFn = () => { started.countDown(); release.await(); Left("first run") }
        f.view(gcdArgs: _*)
        assert(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        f.cpFn = () => Left("second run")
        f.view(gcdArgs: _*) // queued behind the running one
        release.countDown()
        eventually("second result")(f.client.messages.asScala.find(_.getMessage == "second run"))
        Thread.sleep(300)
        assert(!f.client.messages.asScala.exists(_.getMessage == "first run"))
      } finally f.close()
    }
    test("Main.serve exit codes") {
      /** Runs Main.serve on pipes; `drive` gets the client proxy and a closer for stdin. */
      def serve(drive: (LanguageServer, () => Unit) => Unit): Int = {
        val toServer = Pipe.open()
        val toClient = Pipe.open()
        val done = new java.util.concurrent.CompletableFuture[Int]
        val t = new Thread(() => {
          done.complete(Main.serve(Channels.newInputStream(toServer.source), Channels.newOutputStream(toClient.sink))); ()
        })
        t.setDaemon(true)
        t.start()
        val cl = LSPLauncher.createClientLauncher(new RecordingClient(true),
          Channels.newInputStream(toClient.source), Channels.newOutputStream(toServer.sink))
        cl.startListening()
        val init = new InitializeParams
        init.setRootUri(uri(root))
        val caps = cl.getRemoteProxy.initialize(init).get().getCapabilities
        assert(caps.getExecuteCommandProvider.getCommands.asScala == Seq("socless.viewDesign", "socless.simulate"))
        try {
          drive(cl.getRemoteProxy, () => toServer.sink.close())
          done.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally { toServer.sink.close(); toClient.sink.close() }
      }
      val clean = serve { (s, _) => s.shutdown().get(); s.exit() }
      val noShutdown = serve { (s, _) => s.exit() }
      val eof = serve { (_, closeStdin) => closeStdin() }
      assert(clean == 0, noShutdown == 1, eof == 1)
    }
    test("requests after shutdown are no-ops") {
      val f = new Fixture()
      try {
        f.remote.shutdown().get()
        f.open(gcdSpec)
        assert(f.lenses(gcdSpec).isEmpty)
        f.cursor(deepSoc, 120)
        f.save(gcdSpec)
        f.view(gcdArgs: _*)
        assert(f.client.messages.isEmpty)
        assert(!f.log.asScala.exists(_.contains("Rejected")))
      } finally f.close()
    }
    test("a failing command while a run is in flight keeps its error") {
      val f = new Fixture()
      try {
        val started = new java.util.concurrent.CountDownLatch(1)
        val release = new java.util.concurrent.CountDownLatch(1)
        f.cpFn = () => { started.countDown(); release.await(); Left("late run") }
        f.view(gcdArgs: _*)
        assert(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        f.view("only-one-arg") // fails at once
        eventually("bad args message")(f.client.messages.asScala.find(_.getMessage.contains("expects")))
        release.countDown()
        Thread.sleep(500)
        assert(!f.client.messages.asScala.exists(_.getMessage == "late run"))
      } finally f.close()
    }
    test("edit, save, see") {
      // view; a save that breaks the build keeps the design; a save that fixes it shows the new one
      val f = new Fixture()
      try {
        f.view(gcdArgs: _*)
        val b = eventually("browser url")(f.client.documents.asScala.find(_.getExternal == true).map(_.getUri))
        eventually("status ok")(Some(ide(b)).filter(_.status == "ok"))
        val good = get(b, "/design.json")
        assert(upickle.default.read[Design](good.body).top == "GCD")
        val lm0 = good.headers.firstValue("Last-Modified").get

        f.cp = Left("[error] GcdSpec.scala:12: boom")
        f.save(gcdSpec)
        val st = eventually("status error")(Some(ide(b)).filter(_.status == "error"))
        assert(st.message.contains("boom"))
        val kept = get(b, "/design.json")
        assert(kept.body == good.body, kept.headers.firstValue("Last-Modified").get == lm0)

        f.cp = Right(exampleCp)
        f.save(gcdSpec)
        eventually("status ok again")(Some(ide(b)).filter(_.status == "ok"))
        val fresh = get(b, "/design.json")
        assert(upickle.default.read[Design](fresh.body).top == "GCD")
        assert(fresh.headers.firstValue("Last-Modified").get != lm0)
      } finally f.close()
    }
    test("missing probe is reported on every command") {
      val c = new RecordingClient(true)
      val toServer = Pipe.open()
      val toClient = Pipe.open()
      val runnerCalls = new java.util.concurrent.atomic.AtomicInteger
      val server = new SoclessLanguageServer(_ => (_ => Left("unused")),
        _ => { runnerCalls.incrementAndGet(); Left("probe missing") },
        new LiveHttp((_, _) => ()), _ => ())
      val sl = LSPLauncher.createServerLauncher(server, Channels.newInputStream(toServer.source), Channels.newOutputStream(toClient.sink))
      server.connect(sl.getRemoteProxy)
      sl.startListening()
      val cl = LSPLauncher.createClientLauncher(c, Channels.newInputStream(toClient.source), Channels.newOutputStream(toServer.sink))
      cl.startListening()
      val init = new InitializeParams
      init.setRootUri(uri(root))
      cl.getRemoteProxy.initialize(init).get()
      try {
        // the probe is extracted on the first view, not at start, and only once
        assert(runnerCalls.get == 0)
        val p = new ExecuteCommandParams("socless.viewDesign", gcdArgs.map(a => a: Object).asJava)
        cl.getRemoteProxy.getWorkspaceService.executeCommand(p).get()
        cl.getRemoteProxy.getWorkspaceService.executeCommand(p).get()
        eventually("two messages")(Some(c.messages.asScala.count(_.getMessage.contains("probe missing"))).filter(_ == 2))
        assert(runnerCalls.get == 1)
      } finally { server.stopAll(); toServer.sink.close(); toClient.sink.close() }
    }
    test("client-hosted panel") {
      val f = new Fixture(panel = Some("client"))
      try {
        f.view(gcdArgs: _*)
        f.view(gcdArgs: _*)
        val ps = eventually("two openPanel")(Some(f.client.panels.asScala.toSeq).filter(_.size == 2))
        assert(ps.map(_.getUrl).distinct.size == 1, ps.head.getUrl.matches("http://127\\.0\\.0\\.1:\\d+/"))
        assert(ps.forall(_.getTest == "GCD computes gcd(48, 18)"))
        Thread.sleep(300)
        assert(f.client.panels.size == 2)
        assert(f.client.documents.asScala.count(_.getExternal == true) == 0)
        assert(f.browser.isEmpty)
        // socless/status: each change of status, test or message, with the suite
        def statuses = f.client.statuses.asScala.toSeq.map(s => (s.getStatus, s.getSuite, s.getTest))
        val key = ("gcd.GcdSpec", "GCD computes gcd(48, 18)")
        eventually("status ok")(Some(statuses).filter(_.lastOption.contains(("ok", key._1, key._2))))
        assert(statuses.take(2) == Seq(("running", key._1, key._2), ("ok", key._1, key._2)))
        f.client.statuses.clear()
        f.cursor(deepSoc, 0) // focus changes only: no status
        f.cp = Left("broken build")
        f.view(gcdArgs: _*)
        val err = eventually("status error")(f.client.statuses.asScala.find(_.getStatus == "error"))
        assert(err.getSuite == key._1, err.getTest == key._2, err.getMessage == "broken build")
        assert(f.client.statuses.asScala.map(_.getStatus).toSeq == Seq("running", "error"))
      } finally f.close()
    }
    test("panel option absent or not client keeps the default") {
      val f = new Fixture(panel = Some("browser"))
      try {
        f.view(gcdArgs: _*)
        eventually("browser doc")(f.client.documents.asScala.find(_.getExternal == true))
        assert(f.client.panels.isEmpty)
        f.save(gcdSpec)
        Thread.sleep(1500)
        assert(f.client.statuses.isEmpty, f.client.testsChangedCount.get == 0) // client-hosted panel only
      } finally f.close()
    }
    test("simulate: status, refusal while running, settings, completion message") {
      val ws = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val (release, argv, surferArgv) = (bin / "release", bin / "argv", bin / "surfer-argv")
      // a "java" that waits for `release`, records its argv and writes a trace in its cwd
      val java = bin / "java.sh"
      os.write(java, s"""#!/bin/sh
        |printf '%s\\n' "$$@" >> $argv
        |echo --- >> $argv
        |while [ ! -e $release ]; do sleep 0.05; done
        |mkdir -p build/chiselsim/gcd
        |echo '$$timescale 1ns $$end' > build/chiselsim/gcd/trace.vcd
        |""".stripMargin, perms = os.PermSet.fromString("rwx------"))
      val surfer = bin / "surfer.sh"
      os.write(surfer, s"""#!/bin/sh\nprintf '%s\\n' "$$@" >> $surferArgv\n""", perms = os.PermSet.fromString("rwx------"))
      val f = new Fixture(ws = ws, simJava = java.toString,
        initOpts = Seq("waveFlag" -> "emitFst", "surferCommand" -> surfer.toString))
      def simulate(): Unit = f.remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
      def messages = f.client.messages.asScala.toSeq
      try {
        simulate()
        eventually("simulating")(Some(f.http.state).filter(_.status == "simulating"))
        eventually("java started")(Some(argv).filter(os.exists))
        simulate()
        val refused = eventually("refusal")(messages.find(_.getMessage.startsWith("a simulation is already running")))
        assert(refused.getType == MessageType.Info)
        assert(refused.getMessage == "a simulation is already running: gcd.GcdSpec GCD computes gcd(48, 18)")
        assert(f.http.state.status == "simulating") // the running one goes on
        os.write(release, "")
        val done = eventually("completion")(messages.find(_.getMessage.startsWith("Simulation")))
        val dir  = ws / "out" / "socless" / "sim"
        val vcd  = os.walk(dir).filter(_.last == "trace.vcd")
        assert(vcd.size == 1)
        assert(done.getType == MessageType.Info)
        assert(done.getMessage == s"Simulation passed: gcd.GcdSpec GCD computes gcd(48, 18). Waves: ${vcd.head}")
        val st = eventually("status restored")(Some(f.http.state).filter(_.status != "simulating"))
        assert(st.status == "ok", st.message == done.getMessage) // Simulate's own capture viewed the design
        assert(os.read.lines(argv).contains("-DemitFst=1"))
        // the `surfer` viewer (the default): started by the server, connecting back over WCP
        val surferArgs = eventually("surfer argv")(Some(surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.size >= 3))
        assert(surferArgs(0) == "--wcp-initiate", surferArgs(1).matches("[0-9]+"), surferArgs(2) == vcd.head.toString)
        // this Surfer exits without connecting (one too old for --wcp-initiate): a
        // Warning, then the plain `<surferCommand> <trace>` so the waves still show
        val early = eventually("early exit warning")(messages.find(_.getMessage.startsWith("Surfer exited before connecting")))
        assert(early.getType == MessageType.Warning, early.getMessage.contains("--wcp-initiate"), early.getMessage.contains("surfer.log"))
        val fallback = eventually("fallback launch")(Some(surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.size >= 4))
        assert(fallback(3) == vcd.head.toString)
        assert(f.log.asScala.exists(m => m.contains("org.scalatest.tools.Runner") && m.contains(java.toString)))

        def configure(flag: String): Unit = {
          val cfg = new JsonObject
          val sec = new JsonObject
          sec.addProperty("waveFlag", flag)
          sec.addProperty("surferCommand", surfer.toString)
          cfg.add("socless", sec)
          f.remote.getWorkspaceService.didChangeConfiguration(new DidChangeConfigurationParams(cfg))
        }
        def run(n: Int): Seq[String] = {
          simulate()
          eventually(s"completion $n")(Some(messages.count(_.getMessage.startsWith("Simulation"))).filter(_ == n))
          os.read(argv).split("---\n").toSeq(n - 1).linesIterator.toSeq
        }
        // didChangeConfiguration under "socless" changes the next run's flag (neither
        // the init value emitFst nor the default emitVcd), and settings without a
        // "socless" section (Neovim sends {}) change nothing
        configure("emitWaves")
        f.remote.getWorkspaceService.didChangeConfiguration(new DidChangeConfigurationParams(new JsonObject))
        assert(run(2).contains("-DemitWaves=1"))

        // an invalid flag: one Warning (even when sent twice), a log line, the default flag
        configure("emit Fst=2")
        configure("emit Fst=2")
        assert(run(3).contains("-DemitVcd=1"))
        val warned = messages.filter(_.getMessage.contains("invalid waveFlag"))
        assert(warned.size == 1, warned.head.getType == MessageType.Warning)
        assert(warned.head.getMessage.contains("\"emit Fst=2\""), warned.head.getMessage.contains("emitVcd"))
        assert(f.log.asScala.exists(_.contains("invalid waveFlag")))
      } finally { f.close(); os.remove.all(ws); os.remove.all(bin) }
    }
    test("simulate: capture and panel first, then the trace and its names") {
      val ws  = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val (release, started, failFlag, failTrace, surferArgv) =
        (bin / "release", bin / "started", bin / "fail", bin / "fail-trace", bin / "surfer-argv")
      val header = root / "socless-lsp" / "test" / "resources" / "wave" / "gcd-header.vcd"
      // a "java" that says it started, waits for `release` and consumes it, then
      // fails without a trace when `fail` exists, else writes the GCD trace (and
      // then fails when `fail-trace` exists)
      val java = bin / "java.sh"
      os.write(java, s"""#!/bin/sh
        |touch $started
        |while [ ! -e $release ]; do sleep 0.05; done
        |rm -f $release $started
        |if [ -e $failFlag ]; then exit 1; fi
        |mkdir -p build/chiselsim/gcd
        |cp $header build/chiselsim/gcd/trace.vcd
        |if [ -e $failTrace ]; then exit 1; fi
        |""".stripMargin, perms = os.PermSet.fromString("rwx------"))
      val surfer = bin / "surfer.sh"
      os.write(surfer, s"""#!/bin/sh\nprintf '%s\\n' "$$@" >> $surferArgv\n""", perms = os.PermSet.fromString("rwx------"))
      val f = new Fixture(ws = ws, simJava = java.toString, panel = Some("client"),
        initOpts = Seq("surferCommand" -> surfer.toString, "waveViewer" -> "surfer-panel"))
      def simulate(): Unit = f.remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
      def done = f.client.messages.asScala.count(_.getMessage.startsWith("Simulation"))
      try {
        simulate()
        val panel = eventually("openPanel")(f.client.panels.asScala.headOption)
        assert(panel.getTest == "GCD computes gcd(48, 18)")
        val base = panel.getUrl
        eventually("java started")(Some(started).filter(os.exists))
        // the design of the same test is published before the simulation starts
        val d = get(base, "/design.json")
        assert(d.statusCode == 200, upickle.default.read[Design](d.body).top == "GCD")
        assert(f.server.publishedPorts == Set("clock", "reset", "io")) // what prefix detection matches
        val during = ide(base)
        assert(during.status == "simulating", during.trace == "", during.traceTest == "", during.waveViewer == "surfer-panel")
        os.write(release, "")
        eventually("first completion")(Some(done).filter(_ == 1))
        val st = eventually("trace set")(Some(ide(base)).filter(_.trace.nonEmpty))
        assert(st.trace.matches("/wave/[0-9a-f]+/trace"), st.traceTest == "gcd.GcdSpec#GCD computes gcd(48, 18)")
        assert(st.status == "ok", st.message.startsWith("Simulation passed"))
        val vcd = os.walk(ws / "out" / "socless" / "sim").filter(_.last == "trace.vcd").head
        val t = get(base, st.trace)
        assert(t.statusCode == 200, t.body == os.read(vcd))
        val names = ujson.read(get(base, st.trace.stripSuffix("trace") + "names").body)
        // detected from the trace with the captured top's ports, not assumed
        assert(names("prefix").str == "TOP.svsimTestbench.dut")
        assert(names("names").arr.map(_.str).contains("TOP.svsimTestbench.dut.x"))
        Thread.sleep(300)
        assert(!os.exists(surferArgv)) // surfer-panel: nothing external
        assert(f.client.panels.size == 1)

        // a failing run: neither field, and the old trace stops resolving
        os.write(failFlag, "")
        simulate()
        eventually("second java started")(Some(started).filter(os.exists))
        assert(get(base, st.trace).statusCode == 404, ide(base).trace == "", ide(base).traceTest == "")
        assert(f.client.panels.size == 2) // the capture opened the panel again
        os.write(release, "")
        eventually("second completion")(Some(done).filter(_ == 2))
        Thread.sleep(200)
        val after = ide(base)
        assert(after.trace == "", after.traceTest == "", after.message.startsWith("Simulation failed"))

        // a failing test with a trace: its waves are served all the same
        os.remove(failFlag)
        os.write(failTrace, "")
        simulate()
        eventually("third java started")(Some(started).filter(os.exists))
        os.write(release, "")
        eventually("third completion")(Some(done).filter(_ == 3))
        val failed = eventually("trace set after a failure")(Some(ide(base)).filter(_.trace.nonEmpty))
        assert(failed.traceTest == "gcd.GcdSpec#GCD computes gcd(48, 18)", failed.message.startsWith("Simulation failed"))
        assert(get(base, failed.trace).statusCode == 200)
      } finally { f.close(); os.remove.all(ws); os.remove.all(bin) }
    }
    test("simulate with the surfer viewer: a WCP Surfer shows the top, follows selections, a new run replaces it") {
      val ws  = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val (release, started, surferArgv) = (bin / "release", bin / "started", bin / "surfer-argv")
      val header = root / "socless-lsp" / "test" / "resources" / "wave" / "gcd-header.vcd"
      val java = bin / "java.sh"
      os.write(java, s"""#!/bin/sh
        |touch $started
        |while [ ! -e $release ]; do sleep 0.05; done
        |rm -f $release $started
        |mkdir -p build/chiselsim/gcd
        |cp $header build/chiselsim/gcd/trace.vcd
        |""".stripMargin, perms = os.PermSet.fromString("rwx------"))
      // a Surfer that records its pid and argv and stays up (the test connects in its place)
      val surfer = bin / "surfer.sh"
      os.write(surfer, s"""#!/bin/sh\nprintf '%s %s\\n' "$$$$" "$$*" >> $surferArgv\nexec sleep 600\n""",
        perms = os.PermSet.fromString("rwx------"))
      val f = new Fixture(ws = ws, simJava = java.toString, panel = Some("client"),
        initOpts = Seq("surferCommand" -> surfer.toString))
      def simulate(): Unit = f.remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
      def run(n: Int): (Long, Int) = {
        simulate()
        eventually(s"java $n started")(Some(started).filter(os.exists))
        os.write(release, "")
        val line = eventually(s"surfer $n")(Some(surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.size == n)).last
        val Array(pid, flag, port, trace) = line.split(" ")
        assert(flag == "--wcp-initiate", trace.endsWith("trace.vcd"))
        (pid.toLong, port.toInt)
      }
      def focusIds(fake: FakeSurfer) = fake.frames.filter(_.value.get("command").exists(_.str == "focus_item")).map(_("id").num.toInt)
      val fakes = scala.collection.mutable.Buffer.empty[FakeSurfer]
      try {
        val (pid1, port1) = run(1)
        val fake = new FakeSurfer(port1)
        fakes += fake
        // the first plan: the test's top module, flat
        eventually("top plan")(Some(fake.commands).filter(_.count(_ == "set_item_color") == 10))
        assert(fake.commands.take(4) == Seq("greeting", "get_item_list", "clear", "add_items"))
        assert(fake.commands.count(_ == "add_items") == 5)
        val base = f.client.panels.asScala.head.getUrl
        val traceTest = eventually("trace")(Some(ide(base)).filter(_.trace.nonEmpty)).traceTest
        assert(traceTest == "gcd.GcdSpec#GCD computes gcd(48, 18)")
        def select(test: String, sel: String) = post(base, "/wave/select",
          ujson.write(ujson.Obj("traceTest" -> test, "instPath" -> ujson.Arr("GCD"), "selection" -> sel))).statusCode
        // a register of the shown module: focus on its id (x, y, busy are 8, 9, 10)
        assert(select(traceTest, "y") == 204)
        eventually("focus y")(Some(focusIds(fake)).filter(_ == Seq(9)))
        // another trace's selection, or another viewer: nothing reaches Surfer
        assert(select("other.Spec#t", "x") == 204)
        val cfg = new JsonObject
        val sec = new JsonObject
        sec.addProperty("surferCommand", surfer.toString)
        sec.addProperty("waveViewer", "surfer-panel")
        cfg.add("socless", sec)
        f.remote.getWorkspaceService.didChangeConfiguration(new DidChangeConfigurationParams(cfg))
        eventually("viewer switched")(Some(ide(base)).filter(_.waveViewer == "surfer-panel"))
        assert(select(traceTest, "busy") == 204)
        Thread.sleep(600)
        assert(focusIds(fake) == Seq(9))
        sec.addProperty("waveViewer", "surfer")
        f.remote.getWorkspaceService.didChangeConfiguration(new DidChangeConfigurationParams(cfg))
        eventually("viewer back")(Some(ide(base)).filter(_.waveViewer == "surfer"))

        // a new Simulate: the old Surfer is killed, a new one on a new port gets the top plan
        val (pid2, port2) = run(2)
        assert(pid2 != pid1, port2 != port1)
        eventually("old surfer gone")(Some(ProcessHandle.of(pid1)).filter(h => !h.isPresent || !h.get.isAlive))
        val fake2 = new FakeSurfer(port2)
        fakes += fake2
        eventually("second top plan")(Some(fake2.commands).filter(_.count(_ == "set_item_color") == 10))
        assert(select(traceTest, "x") == 204)
        eventually("focus x")(Some(focusIds(fake2)).filter(_ == Seq(8)))
        f.close()
        eventually("surfer killed at shutdown")(Some(ProcessHandle.of(pid2)).filter(h => !h.isPresent || !h.get.isAlive))
        ()
      } finally { fakes.foreach(_.close()); f.close(); os.remove.all(ws); os.remove.all(bin) }
    }
    test("simulate: a second Simulate before the first starts replaces it, and says so") {
      val ws  = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val runs = bin / "runs"
      val java = bin / "java.sh"
      os.write(java, s"""#!/bin/sh\necho run >> $runs\necho x > trace.vcd\n""", perms = os.PermSet.fromString("rwx------"))
      val f = new Fixture(ws = ws, simJava = java.toString, initOpts = Seq("waveViewer" -> "surfer-panel"))
      def simulate(): Unit = f.remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
      def messages = f.client.messages.asScala.toSeq
      try {
        simulate() // waits for its capture (the probe takes seconds)
        simulate()
        val replaced = eventually("replaced")(messages.find(_.getMessage.startsWith("replaced the pending simulation")))
        assert(replaced.getType == MessageType.Info)
        assert(replaced.getMessage == "replaced the pending simulation of gcd.GcdSpec GCD computes gcd(48, 18)")
        eventually("completion")(Some(messages.count(_.getMessage.startsWith("Simulation"))).filter(_ == 1))
        Thread.sleep(500)
        assert(messages.count(_.getMessage.startsWith("Simulation")) == 1, os.read.lines(runs).size == 1)
      } finally { f.close(); os.remove.all(ws); os.remove.all(bin) }
    }
    test("simulate never hides a view's status") {
      val ws  = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val (release, started) = (bin / "release", bin / "started")
      // a "java" that says it started, waits for `release` and consumes it, then writes a trace
      val java = bin / "java.sh"
      os.write(java, s"""#!/bin/sh
        |touch $started
        |while [ ! -e $release ]; do sleep 0.05; done
        |rm -f $release $started
        |echo x > trace.vcd
        |""".stripMargin, perms = os.PermSet.fromString("rwx------"))
      val surfer = bin / "surfer.sh"
      os.write(surfer, "#!/bin/sh\n", perms = os.PermSet.fromString("rwx------"))
      val f = new Fixture(ws = ws, simJava = java.toString, initOpts = Seq("surferCommand" -> surfer.toString))
      def simulate(): Unit = f.remote.getWorkspaceService.executeCommand(
        new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
      def done = f.client.messages.asScala.count(_.getMessage.startsWith("Simulation"))
      try {
        // 1. a capture error stays on the panel through the simulation, which still runs
        val calls = new _root_.java.util.concurrent.atomic.AtomicInteger
        f.cpFn = () => if (calls.getAndIncrement() == 0) Left("view broke") else Right(exampleCp)
        simulate()
        eventually("view error")(Some(f.http.state).filter(_.status == "error"))
        eventually("java started")(Some(started).filter(os.exists))
        assert(f.http.state.status == "error", f.http.state.message == "view broke")
        os.write(release, "")
        eventually("first completion")(Some(done).filter(_ == 1))
        assert(f.http.state.status == "error", f.http.state.message == "view broke")

        // 2. a view during a simulation keeps its own status after the simulation ends
        f.view(gcdArgs: _*)
        eventually("view ok")(Some(f.http.state).filter(_.status == "ok"))
        simulate()
        eventually("simulating")(Some(f.http.state).filter(_.status == "simulating"))
        eventually("java started")(Some(started).filter(os.exists))
        f.view(gcdArgs: _*)
        eventually("view ok again")(Some(f.http.state).filter(s => s.status == "ok" && s.message.isEmpty))
        os.write(release, "")
        eventually("second completion")(Some(done).filter(_ == 2))
        Thread.sleep(200)
        assert(f.http.state.status == "ok", f.http.state.message.isEmpty)

        // 3. otherwise the status captured at the start comes back, with the outcome as message
        simulate()
        eventually("simulating")(Some(f.http.state).filter(_.status == "simulating"))
        os.write(release, "")
        eventually("third completion")(Some(done).filter(_ == 3))
        val st = eventually("restored")(Some(f.http.state).filter(_.status != "simulating"))
        assert(st.status == "ok", st.message.startsWith("Simulation passed"))
      } finally { f.close(); os.remove.all(ws); os.remove.all(bin) }
    }
    test("simulate: a viewer that cannot start is a Warning, even on a pass, and is logged") {
      val ws  = os.temp.dir(prefix = "socless-sim-ws-")
      val bin = os.temp.dir(prefix = "socless-sim-bin-")
      val java = bin / "java.sh"
      os.write(java, "#!/bin/sh\necho x > trace.vcd\n", perms = os.PermSet.fromString("rwx------"))
      val missing = bin / "no-such-surfer"
      def messages(f: Fixture) = f.client.messages.asScala.toSeq
      // `surfer` (WCP, started by the server) and `custom` (SimRunner's plain launch)
      for (viewer <- Seq("surfer", "custom")) {
        val f = new Fixture(ws = ws, simJava = java.toString,
          initOpts = Seq("surferCommand" -> missing.toString, "waveViewer" -> viewer))
        try {
          f.remote.getWorkspaceService.executeCommand(
            new ExecuteCommandParams("socless.simulate", gcdArgs.map(a => a: Object).asJava)).get()
          val done = eventually(s"$viewer completion")(messages(f).find(_.getMessage.startsWith("Simulation")))
          assert(done.getMessage.startsWith("Simulation passed"), done.getMessage.contains(s"cannot start $missing"))
          assert(done.getType == MessageType.Warning)
          assert(f.log.asScala.exists(_.contains(s"cannot start $missing")))
        } finally f.close()
      }
      os.remove.all(ws); os.remove.all(bin)
    }
  }
}
