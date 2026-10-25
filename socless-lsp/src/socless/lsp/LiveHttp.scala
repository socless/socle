package socless.lsp

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import socless.cli.Serve
import socless.schema.Design
import upickle.default.{ReadWriter, macroRW}

/** What the live panel shows about the IDE side. The field names are a
  * contract with the Scala.js UI (it mirrors this case class).
  */
case class IdeState(
    status: String = "idle", // "idle" | "running" | "simulating" | "ok" | "error"
    message: String = "",
    focusClass: String = "",
    test: String = "",
    stamp: Long = 0L
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
  */
final class LiveHttp(onOpen: (String, Int) => Unit, clock: () => Long = () => System.currentTimeMillis) {
  private case class Published(json: String, stamp: Long)

  private val MaxOpenBody = 64 * 1024

  private val lock = new Object
  private var server: HttpServer = null
  private var design: Option[Published] = None
  private var ide: IdeState = IdeState()
  private var lastStamp = 0L

  /** Bind 127.0.0.1:0 and return the port. */
  def start(): Int = lock.synchronized {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", (ex: HttpExchange) => handle(ex))
    s.setExecutor(Executors.newFixedThreadPool(2, (r: Runnable) => {
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

  def update(f: IdeState => IdeState): Unit = lock.synchronized {
    ide = f(ide).copy(stamp = clock())
  }

  private[lsp] def state: IdeState = lock.synchronized(ide)

  def stop(): Unit = lock.synchronized {
    if (server != null) { server.stop(0); server = null }
  }

  // Explicit so every field is present even when it equals its default
  // (upickle omits defaults); the UI mirrors these names.
  private def ideJson(s: IdeState): String =
    ujson.write(ujson.Obj("status" -> s.status, "message" -> s.message,
      "focusClass" -> s.focusClass, "test" -> s.test, "stamp" -> s.stamp.toDouble))

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
            case Some(html) => Serve.send(ex, 200, "text/html; charset=utf-8", html.getBytes(UTF_8))
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
        case ("POST", "/open") =>
          val ctype  = Option(ex.getRequestHeaders.getFirst("Content-Type")).map(_.toLowerCase).getOrElse("")
          val origin = Option(ex.getRequestHeaders.getFirst("Origin"))
          if (!(ctype == "application/json" || ctype.startsWith("application/json;")))
            err(415, ex, "content-type must be application/json")
          else if (origin.exists(o => !hosts.map("http://" + _).contains(o))) err(403, ex, "forbidden origin")
          else open(ex)
        case _                 => err(404, ex, "not found")
      }
    } catch {
      case e: Throwable =>
        Console.err.println(s"[socless-lsp] http error: $e")
        try err(500, ex, "internal error") catch { case _: Throwable => () }
    } finally ex.close()

  private def open(ex: HttpExchange): Unit = {
    val declared = Option(ex.getRequestHeaders.getFirst("Content-Length")).flatMap(_.toLongOption).getOrElse(-1L)
    if (declared > MaxOpenBody) { err(413, ex, "body too large"); return }
    val bytes = ex.getRequestBody.readNBytes(MaxOpenBody + 1)
    if (bytes.length > MaxOpenBody) { err(413, ex, "body too large"); return }
    val body = new String(bytes, UTF_8)
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
