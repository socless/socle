package socle.lsp

import java.io.{InputStream, OutputStream}
import java.lang.ProcessBuilder.Redirect
import org.eclipse.lsp4j.launch.LSPLauncher
import scala.util.Try
import scala.util.control.NonFatal

/** `socle.lsp.Main`: the language server over stdin/stdout.
  *
  * stdout is the JSON-RPC channel, so the real stream is captured for lsp4j
  * first and then both `System.out` and Scala's `Console.out` are pointed at
  * stderr: any stray `println` (ours, a library's, os-lib's) lands in the log
  * instead of corrupting the protocol.
  */
object Main {
  def main(args: Array[String]): Unit = {
    val lspOut = System.out
    System.setOut(System.err)
    // Scala 2.13 has no Console.setOut. Console.out is an inheritable
    // DynamicVariable whose default is System.out read when Console is first
    // loaded, so stdout safety relies on nothing touching Console before the
    // setOut above; withOut also covers this thread and every thread started
    // inside it (lsp4j's, the HTTP server's, the run queue's).
    val code = Console.withOut(System.err)(serve(System.in, lspOut))
    sys.exit(code)
  }

  /** Serves one client until it disconnects or sends `exit`; returns the exit
    * code: 0 only when `shutdown` came first (LSP), else 1. */
  def serve(in: InputStream, out: OutputStream): Int = {
    // The probe is extracted on the first view (the server builds the runner
    // lazily); a missing probe keeps the server alive and is reported on every command.
    def probe = Try(ViewRunner.extractProbeJar()).toEither.left
      .map(e => s"Socle cannot view designs: the probe agent is unavailable (${e.getMessage})")

    val exited = new java.util.concurrent.CountDownLatch(1)
    val log: String => Unit = m => Console.err.println(s"[socle-lsp] $m")
    lazy val http: LiveHttp = new LiveHttp((file, line) => server.openInEditor(file, line),
      onSelect = req => server.waveSelect(req))
    lazy val server: SocleLanguageServer = new SocleLanguageServer(
      root => MillBridge.default(root, log),
      root => probe.map(jar => new ViewRunner(jar, root)),
      http,
      openBrowser,
      log = log,
      onExit = () => exited.countDown(),
      simFor = root => new SimRunner(root, log = log),
    )
    val launcher = new org.eclipse.lsp4j.jsonrpc.Launcher.Builder[SocleClient]()
      .setLocalService(server).setRemoteInterface(classOf[SocleClient])
      .setInput(in).setOutput(out).create()
    server.connect(launcher.getRemoteProxy)
    val listening = launcher.startListening()
    // TEST HOOK ONLY (StdioSmokeTest sets SOCLE_LSP_STRAY_OUTPUT; never set
    // in normal use): print after startup, as a stray library println would,
    // from this thread and a new one; both must reach stderr, not stdout.
    if (sys.env.contains("SOCLE_LSP_STRAY_OUTPUT")) {
      println("socle-stray-console-out")
      val t = new Thread(() => System.out.println("socle-stray-system-out"))
      t.start(); t.join()
    }
    // Returns when the client sends `exit` or closes stdin, whichever comes first.
    val watcher = new Thread(() => { Try(listening.get()); exited.countDown() }, "socle-lsp-eof")
    watcher.setDaemon(true)
    watcher.start()
    exited.await()
    server.stopAll()
    if (server.shutdownWasRequested) 0 else 1
  }

  /** The desktop's URL opener, with its output discarded (never our stdout). */
  private def openBrowser(url: String): Unit = {
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val cmd =
      if (os.contains("mac")) Seq("open", url)
      else if (os.contains("win")) Seq("rundll32", "url.dll,FileProtocolHandler", url)
      else Seq("xdg-open", url)
    try {
      new ProcessBuilder(cmd: _*).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD)
        .start()
      ()
    } catch { case NonFatal(e) => Console.err.println(s"[socle-lsp] cannot open a browser on $url: $e") }
  }
}
