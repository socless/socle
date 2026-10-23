package socless.cli

import socless._
import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors

/** Standalone, dependency-free UI server (issue #115): `socless.cli.Launcher serve`
  * extracts the embedded SoCLess.html and serves it, converting dropped `.fir`
  * files in-process via `POST /convert`. No Mill and no Python required, so it
  * works from an sbt project (or any build): dump CHIRRTL from your own build,
  * then point this at it.
  *
  * Binds loopback (`127.0.0.1`) by default, but auto-binds `0.0.0.0` under WSL:
  * WSL2's localhost-forwarding relay does not forward to a `127.0.0.1`-only
  * service inside the VM, so the Windows-side browser would otherwise get
  * ERR_CONNECTION_REFUSED and a blank page. `--host` overrides either way.
  *
  * Usage:
  * {{{
  *   socless.cli.Launcher serve [--port N] [--host <addr>] [--dir <static-dir>] [--watch <file.fir>] [--no-open]
  * }}}
  * `--dir` additionally serves static files from a directory (drop a
  * `design.json` / `designs.json` there to have the UI auto-load it).
  *
  * `--watch <file.fir>` re-converts a CHIRRTL file in-process whenever it
  * changes and serves the result at `/design.json`; combined with the UI's
  * live poller (#120) this gives a save-and-see dev loop. Point it at a `.fir`
  * your own build dumps on every edit (e.g. `sbt ~runMain ...Dump`).
  *
  * `--source-root <dir>` embeds source text into every converted design (both
  * drag-drop `/convert` and `--watch`), so the UI's Source tab shows code
  * instead of a bare file:line locator (issue #154).
  */
object Serve {

  private val MaxBody = 128 * 1024 * 1024 // 128 MB of CHIRRTL is plenty

  // The current converted design.json + the time it was produced, for
  // --watch. Volatile so the watcher thread's writes are seen by request
  // threads. None until the first conversion succeeds.
  @volatile private var live: Option[(String, Long)] = None

  // Optional --source-root: embeds source text into converted designs (#154),
  // for both POST /convert and --watch. Set once at startup, read by request
  // and watcher threads.
  @volatile private var sourceRoot: Option[os.Path] = None

  private val Usage =
    "usage: socless.cli.Launcher serve [--port N] [--host <addr>] [--dir <dir>] [--watch <file.fir>] [--source-root <dir>] [--no-open]"

  /** True when running inside WSL (any version). WSL2 runs services in a NAT'd
    * VM; a server bound only to `127.0.0.1` inside that VM is unreachable from
    * the Windows-side browser, so we widen the default bind there (see
    * [[resolveBindHost]]). Detected via the interop env vars Microsoft sets, or
    * the "microsoft" marker in the kernel release string. */
  private[socless] def isWsl: Boolean =
    sys.env.contains("WSL_DISTRO_NAME") || sys.env.contains("WSL_INTEROP") || {
      try new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("/proc/sys/kernel/osrelease")), UTF_8)
            .toLowerCase.contains("microsoft")
      catch { case _: Throwable => false }
    }

  /** Resolve the address to bind. An explicit `--host` always wins. Otherwise
    * default to `0.0.0.0` under WSL (so the Windows-side browser reaches the
    * server through the WSL2 localhost-forwarding relay, which ignores
    * `127.0.0.1`-only services), and to loopback-only `127.0.0.1` on a normal
    * machine (the server should not be exposed beyond the host). */
  private[socless] def resolveBindHost(explicit: Option[String], wsl: Boolean): String =
    explicit.getOrElse(if (wsl) "0.0.0.0" else "127.0.0.1")

  /** Per-flag explanations for the `serve` subcommand. Shared so the top-level
    * `--help` and `serve --help` describe the flags identically (issue #157). */
  val FlagsHelp: String =
    """  --port N             TCP port to listen on (default 8765)
      |  --host <addr>        address to bind (default 127.0.0.1; auto 0.0.0.0 under WSL so the
      |                       Windows-side browser can reach it). Use 0.0.0.0 to expose on the LAN.
      |  --dir <dir>          also serve static files from <dir>. Drop a design.json there
      |                       (or designs.json for multiple tops) and the UI auto-loads it
      |                       on open, instead of you drag-dropping a .fir
      |  --watch <file.fir>   re-convert <file.fir> whenever it changes and serve the result
      |                       at /design.json; the open UI hot-reloads it (save-and-see loop)
      |  --source-root <dir>  embed source text into converted designs, so the Source tab
      |                       shows code (resolves the .fir's @[file ...] markers)
      |  --no-open            do not open a browser window on start""".stripMargin

  /** Full `serve --help` output: usage line, one-line summary, then the flags. */
  val Help: String =
    Usage + "\n\n" +
    "serve - standalone loopback UI server. Serves the bundled UI and converts\n" +
    "dropped .fir files in process (POST /convert); no Mill and no Python.\n\n" +
    "Options:\n" + FlagsHelp

  def run(args: Array[String]): Unit = {
    var port    = 8765
    var host    = Option.empty[String]
    var dir     = Option.empty[os.Path]
    var watch   = Option.empty[os.Path]
    var srcRoot = Option.empty[os.Path]
    var open    = true
    var i       = 0
    while (i < args.length) {
      args(i) match {
        case "--help" | "-h" =>
          println(Help); return
        case "--port" if i + 1 < args.length =>
          port = args(i + 1).toIntOption.filter(p => p > 0 && p <= 65535).getOrElse {
            Console.err.println(s"[socless] serve: invalid port '${args(i + 1)}'"); sys.exit(2)
          }
          i += 2
        case "--host" if i + 1 < args.length =>
          host = Some(args(i + 1)); i += 2
        case "--dir" if i + 1 < args.length =>
          dir = Some(os.Path(args(i + 1), os.pwd)); i += 2
        case "--watch" if i + 1 < args.length =>
          watch = Some(os.Path(args(i + 1), os.pwd)); i += 2
        case "--source-root" if i + 1 < args.length =>
          srcRoot = Some(os.Path(args(i + 1), os.pwd)); i += 2
        case "--no-open" => open = false; i += 1
        case other =>
          Console.err.println(s"[socless] serve: unknown argument '$other'")
          Console.err.println(Usage)
          sys.exit(2)
      }
    }
    sourceRoot = srcRoot
    watch.foreach(startWatch)

    val html = readResource("/SoCLess.html").getOrElse {
      Console.err.println("[socless] SoCLess.html not found on the classpath; is the socless JAR complete?")
      sys.exit(1)
    }

    val bindHost = resolveBindHost(host, isWsl)
    val server =
      try HttpServer.create(new InetSocketAddress(bindHost, port), 0)
      catch {
        case e: java.io.IOException =>
          Console.err.println(s"[socless] cannot listen on $bindHost:$port: ${e.getMessage}")
          Console.err.println("[socless] pick another port: serve --port <N>")
          sys.exit(1)
      }
    server.createContext("/", (ex: HttpExchange) => handle(ex, html, dir))
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.start()

    // `localhost` works for both binds: loopback on a normal machine, and via
    // the WSL2 relay when bound to 0.0.0.0 (a 127.0.0.1-only bind is invisible
    // to that relay, which was the WSL "blank page" bug).
    val url = s"http://localhost:$port/SoCLess.html"
    Console.err.println(s"[socless] serving on $url (bound $bindHost) (drag-drop a .fir, or POST /convert) -- Ctrl-C to stop")
    if (bindHost == "0.0.0.0")
      Console.err.println(s"[socless] also reachable on this host's LAN/VM IP, e.g. http://<ip>:$port/SoCLess.html")
    if (open) openBrowser(url)
    // Block forever; the JVM exits on Ctrl-C.
    try Thread.currentThread.join()
    catch { case _: InterruptedException => server.stop(0) }
  }

  private def handle(ex: HttpExchange, html: String, dir: Option[os.Path]): Unit = {
    try {
      val method = ex.getRequestMethod
      val path   = ex.getRequestURI.getPath
      if (method == "POST" && path == "/convert") { convert(ex); return }
      if (method != "GET" && method != "HEAD") { send(ex, 405, "text/plain", "method not allowed".getBytes(UTF_8)); return }
      path match {
        case "/" | "/SoCLess.html" =>
          send(ex, 200, "text/html; charset=utf-8", html.getBytes(UTF_8))
        // When --watch is active, the freshest design.json lives in memory; the
        // UI's live poller (#120) reads it via Last-Modified. Falls through to
        // --dir static when no conversion has happened yet.
        case "/design.json" if live.isDefined =>
          val (json, stamp) = live.get
          ex.getResponseHeaders.set("Last-Modified", httpDate(stamp))
          send(ex, 200, "application/json", json.getBytes(UTF_8))
        case p =>
          serveStatic(ex, dir, p.stripPrefix("/"))
      }
    } catch {
      case e: Throwable =>
        send(ex, 500, "text/plain", s"error: ${e.getMessage}".getBytes(UTF_8))
    } finally ex.close()
  }

  /** Serve a file from `--dir`, guarding against path traversal. */
  private def serveStatic(ex: HttpExchange, dir: Option[os.Path], rel: String): Unit = {
    val file =
      try dir.map(_ / os.RelPath(rel)) catch { case _: Throwable => None }
    file match {
      case Some(f) if dir.exists(d => f.startsWith(d)) && os.exists(f) && os.isFile(f) =>
        // Last-Modified lets the UI's live watch (#120) detect re-elaboration.
        ex.getResponseHeaders.set("Last-Modified", httpDate(os.mtime(f)))
        send(ex, 200, contentType(rel), os.read.bytes(f))
      case _ =>
        send(ex, 404, "text/plain", "not found".getBytes(UTF_8))
    }
  }

  /** Re-convert a `.fir` file into design.json in-process whenever it changes
    * (issue #120). Runs on a daemon thread polling mtime ~1x/sec; the result is
    * published into `live` for the GET /design.json handler. A failed
    * conversion (mid-write file, transient parse error) is logged and the last
    * good design is kept, so the UI never flips to a broken state. */
  private def startWatch(fir: os.Path): Unit = {
    def convertOnce(): Unit =
      try {
        val design = SoclessExporter.designFromChirrtl(os.read(fir), sourceRoot)
        // A 0-module parse means the file was empty, mid-write, or not CHIRRTL.
        // The parser degrades gracefully rather than throwing, so guard here and
        // keep the last good design instead of flipping the UI to "Unknown".
        if (design.modules.isEmpty)
          Console.err.println("[socless] watch: parsed 0 modules (incomplete or invalid CHIRRTL?), keeping last good")
        else
          live = Some((upickle.default.write(design, indent = 2), System.currentTimeMillis()))
      } catch {
        case e: Throwable =>
          val msg = Option(e.getMessage).getOrElse(e.toString).linesIterator.next()
          Console.err.println(s"[socless] watch: conversion failed (keeping last good): $msg")
      }
    // Initial conversion so the UI has something the moment it loads.
    if (os.exists(fir)) convertOnce()
    else Console.err.println(s"[socless] watch: $fir does not exist yet; waiting for it")
    val t = new Thread(() => {
      var lastMtime = -1L
      while (true) {
        try {
          if (os.exists(fir)) {
            val m = os.mtime(fir)
            if (m != lastMtime) { lastMtime = m; convertOnce() }
          }
        } catch { case _: InterruptedException => return; case _: Throwable => () }
        try Thread.sleep(1000) catch { case _: InterruptedException => return }
      }
    }, "socless-watch")
    t.setDaemon(true)
    t.start()
    Console.err.println(s"[socless] watching $fir for changes -- re-converting on save")
  }

  private[socless] def httpDate(millis: Long): String =
    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
      java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC))

  private def convert(ex: HttpExchange): Unit = {
    val declared = Option(ex.getRequestHeaders.getFirst("Content-Length")).flatMap(_.toIntOption).getOrElse(-1)
    if (declared > MaxBody) { send(ex, 413, "text/plain", "body too large".getBytes(UTF_8)); return }
    val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
    try {
      val json = SoclessExporter.designJson(body, sourceRoot = sourceRoot)
      send(ex, 200, "application/json", json.getBytes(UTF_8))
    } catch {
      case e: Throwable =>
        // First line only: a multi-line message would be unhelpful to the UI.
        val msg = Option(e.getMessage).getOrElse(e.toString).linesIterator.next()
        send(ex, 500, "text/plain", s"conversion failed: $msg".getBytes(UTF_8))
    }
  }

  private[socless] def send(ex: HttpExchange, code: Int, ctype: String, body: Array[Byte]): Unit = {
    ex.getResponseHeaders.set("Content-Type", ctype)
    val head = ex.getRequestMethod == "HEAD"
    ex.sendResponseHeaders(code, if (head) -1L else body.length.toLong)
    if (!head) {
      val out = ex.getResponseBody
      try out.write(body) finally out.close()
    }
  }

  private[socless] def contentType(name: String): String =
    if (name.endsWith(".json")) "application/json"
    else if (name.endsWith(".html")) "text/html; charset=utf-8"
    else if (name.endsWith(".js")) "text/javascript"
    else "text/plain"

  private[socless] def readResource(name: String): Option[String] = {
    val s = getClass.getResourceAsStream(name)
    if (s == null) None
    else try Some(new String(s.readAllBytes(), UTF_8)) finally s.close()
  }

  private def openBrowser(url: String): Unit = {
    val opener =
      if (sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")) "open"
      else "xdg-open"
    try { os.proc(opener, url).spawn(stdout = os.Pipe, stderr = os.Pipe); () }
    catch { case _: Throwable => () } // best-effort
  }
}
