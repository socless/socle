package socless.lsp

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import socless.cli.Serve
import socless.schema.Design
import socless.wave.{SurferWire, WaveSettings}
import upickle.default.{ReadWriter, macroRW}

/** What the live panel shows about the IDE side. The field names are a
  * contract with the Scala.js UI (it mirrors this case class).
  */
case class IdeState(
    status: String = "idle", // "idle" | "running" | "simulating" | "ok" | "error"
    message: String = "",
    focusClass: String = "",
    test: String = "",
    stamp: Long = 0L,
    // FQCN of `test`'s suite: for the editor (`socless/status`) and the panel,
    // which pauses wave following unless `<suite>#<test>` is `traceTest`.
    suite: String = "",
    // Wave viewer (docs/ide-vscode.md): the chosen viewer, the current trace's
    // URL path (`/wave/<id>/trace`, empty when none), its `<suite>#<test>`, and
    // the wave settings the UI follows the selection with.
    waveViewer: String = "",
    trace: String = "",
    traceTest: String = "",
    waveSettings: Option[WaveSettings] = None,
    // The Surfer app's last Go to declaration, for the panel to show (each seq once).
    reveal: Option[SurferWire.Reveal] = None
)
object IdeState { implicit val rw: ReadWriter[IdeState] = macroRW }

/** Loopback HTTP endpoint the language server exposes to the SoCLess UI page.
  * Binds 127.0.0.1 on an ephemeral port. Never writes to stdout (it is the
  * LSP channel): everything is logged to stderr.
  *
  * `/design.json` carries a `Last-Modified` the UI poller compares; HTTP dates
  * have 1 s resolution, so the stamp is kept strictly increasing by at least
  * one second per publish.
  *
  * Only requests addressed to `127.0.0.1:<port>` or `localhost:<port>` are
  * served (DNS-rebinding guard), and `POST /open` additionally needs a JSON
  * content type and a same-origin `Origin` when one is sent (CSRF guard).
  * `POST /open` takes `{"file": string, "line": int}` where `line` is the
  * 1-based source line exactly as in `SourceRef`; `onOpen(file, line)` gets
  * it unchanged (the language server converts it to a 0-based LSP position).
  * `onOpen` runs on the HTTP thread, so it must return fast.
  *
  * `GET /wave/<id>/trace` streams the current simulation's trace and
  * `GET /wave/<id>/names` gives `{"prefix": ..., "names": [...]}` (sorted) of
  * it; any other id (an old run's, or not lowercase hex) is a 404, and the id
  * never reaches the filesystem.
  *
  * `GET /surfer/surfer.js` and `GET /surfer/surfer_bg.wasm` serve the vendored
  * Surfer WASM build (socless-lsp/vendor/surfer/SOURCE.md) for the panel's
  * Waveform tab; any other `/surfer/` path is a 404.
  *
  * `POST /wave/select` takes a [[SelectRequest]] (`{"traceTest": ...,
  * "instPath": [...], "selection": string | null}`) under the same guards as
  * `POST /open` and hands it to `onSelect`, on the HTTP thread: it must only
  * schedule work.
  */
final class LiveHttp(onOpen: (String, Int) => Unit, clock: () => Long = () => System.currentTimeMillis,
    onSelect: SelectRequest => Unit = _ => ()) {
  private case class Published(json: String, stamp: Long)

  private val MaxOpenBody = 64 * 1024

  private val lock = new Object
  private var server: HttpServer = null
  private var design: Option[Published] = None
  private var ide: IdeState = IdeState()
  private var lastStamp = 0L
  private var trace: Option[LiveHttp.Trace] = None

  /** Bind 127.0.0.1:0 and return the port. */
  def start(): Int = lock.synchronized {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", (ex: HttpExchange) => handle(ex))
    // four: the panel's poll, a trace or Surfer download and the POSTs never wait on each other
    s.setExecutor(Executors.newFixedThreadPool(4, (r: Runnable) => {
      val t = new Thread(r, "socless-live-http"); t.setDaemon(true); t
    }))
    s.start()
    server = s
    s.getAddress.getPort
  }

  private[lsp] def boundAddress: InetSocketAddress = lock.synchronized(server.getAddress)

  def publish(d: Design): Unit = publishJson(upickle.default.write(d))

  /** Publishes an already serialized [[Design]]. */
  private[lsp] def publishJson(json: String): Unit = {
    lock.synchronized {
      lastStamp = math.max(clock(), lastStamp + 1000)
      design = Some(Published(json, lastStamp))
    }
  }

  /** Applies `f` to the state; returns the state before and after, atomically. */
  def update(f: IdeState => IdeState): (IdeState, IdeState) = lock.synchronized {
    val before = ide
    ide = f(ide).copy(stamp = clock())
    (before, ide)
  }

  private[lsp] def state: IdeState = lock.synchronized(ide)

  /** The trace `/wave/<id>/...` serves; None serves nothing. */
  def serveTrace(t: Option[LiveHttp.Trace]): Unit = lock.synchronized { trace = t }

  def stop(): Unit = lock.synchronized {
    if (server != null) { server.stop(0); server = null }
  }

  // Explicit so every field is present even when it equals its default
  // (upickle omits defaults); the UI mirrors these names.
  private def ideJson(s: IdeState): String =
    ujson.write(ujson.Obj("status" -> s.status, "message" -> s.message,
      "focusClass" -> s.focusClass, "test" -> s.test, "suite" -> s.suite, "stamp" -> s.stamp.toDouble,
      "waveViewer" -> s.waveViewer, "trace" -> s.trace, "traceTest" -> s.traceTest,
      // the object or null, as the UI's macroRW reads (upickle 4 writes an Option that way)
      "waveSettings" -> upickle.default.writeJs(s.waveSettings),
      "reveal" -> upickle.default.writeJs(s.reveal)))

  private def err(code: Int, ex: HttpExchange, msg: String): Unit =
    Serve.send(ex, code, "text/plain", msg.getBytes(UTF_8))

  private def handle(ex: HttpExchange): Unit =
    try {
      val method = ex.getRequestMethod
      val path   = ex.getRequestURI.getPath
      val read   = method == "GET" || method == "HEAD"
      val port   = lock.synchronized(server.getAddress.getPort)
      val hosts  = Set(s"127.0.0.1:$port", s"localhost:$port")
      if (!Option(ex.getRequestHeaders.getFirst("Host")).exists(hosts)) err(403, ex, "forbidden host")
      else (method, path) match {
        case (_, "/") | (_, "/SoCLess.html") if read =>
          Serve.readResource("/SoCLess.html") match {
            case Some(html) =>
              // Framing: the page itself, or VS Code's webview (whose own ancestor is the
              // workbench, vscode-file:); no other site may embed the UI.
              ex.getResponseHeaders.set("Content-Security-Policy", LiveHttp.FrameAncestors)
              Serve.send(ex, 200, "text/html; charset=utf-8", html.getBytes(UTF_8))
            case None       => err(500, ex, "SoCLess.html not found on the classpath")
          }
        case (_, "/design.json") if read =>
          lock.synchronized(design) match {
            case Some(p) =>
              ex.getResponseHeaders.set("Last-Modified", Serve.httpDate(p.stamp))
              ex.getResponseHeaders.set("Cache-Control", "no-store")
              Serve.send(ex, 200, "application/json", p.json.getBytes(UTF_8))
            case None => err(404, ex, "no design yet")
          }
        case (_, "/ide.json") if read =>
          val s = lock.synchronized(ide)
          ex.getResponseHeaders.set("Cache-Control", "no-store")
          Serve.send(ex, 200, "application/json", ideJson(s).getBytes(UTF_8))
        case (_, LiveHttp.WavePath(id, what)) if read =>
          lock.synchronized(trace).filter(_.id == id) match {
            case None => err(404, ex, "no such trace")
            case Some(t) if what == "names" =>
              ex.getResponseHeaders.set("Cache-Control", "no-store")
              Serve.send(ex, 200, "application/json", t.namesJson.getBytes(UTF_8))
            case Some(t) => sendTrace(ex, t.file)
          }
        case (_, p) if read && LiveHttp.SurferFiles.contains(p) => sendSurfer(ex, p)
        case ("POST", p @ ("/open" | "/wave/select")) =>
          val ctype  = Option(ex.getRequestHeaders.getFirst("Content-Type")).map(_.toLowerCase).getOrElse("")
          val origin = Option(ex.getRequestHeaders.getFirst("Origin"))
          if (!(ctype == "application/json" || ctype.startsWith("application/json;")))
            err(415, ex, "content-type must be application/json")
          else if (origin.exists(o => !hosts.map("http://" + _).contains(o))) err(403, ex, "forbidden origin")
          else if (p == "/open") open(ex)
          else select(ex)
        case _                 => err(404, ex, "not found")
      }
    } catch {
      case e: Throwable =>
        Console.err.println(s"[socless-lsp] http error: $e")
        try err(500, ex, "internal error") catch { case _: Throwable => () }
    } finally ex.close()

  /** Streams `file` (a trace can be large); 404 when it is gone. */
  private def sendTrace(ex: HttpExchange, file: os.Path): Unit =
    if (!os.isFile(file)) err(404, ex, "trace gone")
    else {
      ex.getResponseHeaders.set("Content-Type", "application/octet-stream")
      ex.getResponseHeaders.set("Cache-Control", "no-store")
      if (ex.getRequestMethod == "HEAD") ex.sendResponseHeaders(200, -1L)
      else {
        ex.sendResponseHeaders(200, os.size(file))
        val out = ex.getResponseBody
        try java.nio.file.Files.copy(file.toNIO, out) finally out.close()
      }
    }

  /** Streams a vendored Surfer file from the classpath (the wasm is large); 500 when the jar lacks it.
    * `no-cache` with the build's ETag: the browser revalidates each load and,
    * on a 304, keeps its copy (and its compiled wasm). */
  private def sendSurfer(ex: HttpExchange, path: String): Unit =
    Option(getClass.getResourceAsStream(path)) match {
      case None => err(500, ex, s"$path not found on the classpath")
      case Some(in) =>
        try {
          val etag = LiveHttp.surferETag
          ex.getResponseHeaders.set("Cache-Control", "no-cache")
          ex.getResponseHeaders.set("ETag", etag)
          val tags = Option(ex.getRequestHeaders.getFirst("If-None-Match")).toSeq
            .flatMap(_.split(",")).map(_.trim.stripPrefix("W/"))
          ex.getResponseHeaders.set("Content-Type", LiveHttp.SurferFiles(path))
          if (tags.contains(etag) || tags.contains("*")) ex.sendResponseHeaders(304, -1L)
          else if (ex.getRequestMethod == "HEAD") ex.sendResponseHeaders(200, -1L)
          else {
            ex.sendResponseHeaders(200, 0L) // chunked: the resource's length is not known up front
            val out = ex.getResponseBody
            try in.transferTo(out) finally out.close()
          }
        } finally in.close()
    }

  /** The request body, at most [[MaxOpenBody]] bytes; None (413 sent) past it. */
  private def body(ex: HttpExchange): Option[String] = {
    val declared = Option(ex.getRequestHeaders.getFirst("Content-Length")).flatMap(_.toLongOption).getOrElse(-1L)
    val bytes = if (declared > MaxOpenBody) Array.emptyByteArray else ex.getRequestBody.readNBytes(MaxOpenBody + 1)
    if (declared > MaxOpenBody || bytes.length > MaxOpenBody) { err(413, ex, "body too large"); None }
    else Some(new String(bytes, UTF_8))
  }

  private def select(ex: HttpExchange): Unit = body(ex).foreach { b =>
    SelectRequest.parse(b) match {
      case None => err(400, ex, "expected {\"traceTest\": string, \"instPath\": [string], \"selection\": string | null}")
      case Some(req) =>
        try onSelect(req)
        catch { case e: Throwable => Console.err.println(s"[socless-lsp] select callback failed: $e") }
        ex.sendResponseHeaders(204, -1L)
    }
  }

  private def open(ex: HttpExchange): Unit = body(ex).foreach { body =>
    val parsed =
      try {
        val o = ujson.read(body).obj
        Some((o("file").str, o("line").num.toInt))
      } catch { case _: Throwable => None }
    parsed match {
      case None => err(400, ex, "expected {\"file\": string, \"line\": int}")
      case Some((file, line)) =>
        try {
          onOpen(file, line)
          ex.sendResponseHeaders(204, -1L)
        } catch {
          case e: Throwable =>
            Console.err.println(s"[socless-lsp] open callback failed: $e")
            err(500, ex, "open failed")
        }
    }
  }
}

/** The CSP of the UI page: who may frame it. */
object LiveHttp {
  val FrameAncestors = "frame-ancestors 'self' vscode-webview: vscode-file:"

  /** A simulation's trace as `/wave/<id>/...` serves it. */
  final case class Trace(id: String, file: os.Path, names: TraceNames) {
    lazy val namesJson: String =
      ujson.write(ujson.Obj("prefix" -> names.prefix, "names" -> ujson.Arr.from(names.names.toSeq.sorted)))
  }

  private val WavePath = "/wave/([0-9a-f]{1,64})/(trace|names)".r

  /** The vendored Surfer build (socless-lsp/vendor/surfer, resources under `surfer/`) the panel
    * imports, path to content type. Only these are served: not its VERSION, SOURCE.md or licence. */
  private val SurferFiles = Map(
    "/surfer/surfer.js"       -> "text/javascript",
    "/surfer/surfer_bg.wasm"  -> "application/wasm")

  /** The vendored build's ETag: a hash of its VERSION file (the release tag and
    * commit it was built from), else of the served files. */
  private lazy val surferETag: String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val parts = Option(getClass.getResourceAsStream("/surfer/VERSION")).map(Seq(_))
      .getOrElse(SurferFiles.keys.toSeq.sorted.flatMap(p => Option(getClass.getResourceAsStream(p))))
    parts.foreach { in => try md.update(in.readAllBytes()) finally in.close() }
    "\"" + md.digest().take(12).map("%02x".format(_)).mkString + "\""
  }
}
