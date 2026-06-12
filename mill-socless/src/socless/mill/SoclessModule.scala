package socless.mill

import mill._
import mill.scalalib._

/** Mill plugin trait -- mix into your Chisel ScalaModule to get `socless`,
  * `soclessUI`, and `soclessServe` tasks.
  *
  * Minimum `build.mill`:
  * {{{
  *   //| mill-version: 1.1.6
  *   //| mvnDeps: ["io.socless::mill-socless:0.1.0-SNAPSHOT"]
  *
  *   import mill._, scalalib._
  *   import socless.mill.SoclessModule
  *
  *   object myDesign extends ScalaModule with SoclessModule {
  *     def scalaVersion = "2.13.18"
  *     def mvnDeps      = Seq(mvn"org.chipsalliance::chisel:7.11.0")
  *     def soclessTop   = "mypkg.MyTop"
  *   }
  * }}}
  *
  *   mill myDesign.socless          # elaborate + emit design.json
  *   mill myDesign.soclessUI        # elaborate + serve UI at localhost:8765
  *   mill myDesign.soclessUI --port 9000
  *   mill myDesign.soclessServe     # serve UI (no prior elaborate needed; load .fir via browser)
  *   mill myDesign.soclessServe --port 9000
  */
trait SoclessModule extends ScalaModule {

  /** Fully-qualified class name of the top-level Chisel module to elaborate.
    * Must have a no-argument constructor; wrap parameterised modules:
    * {{{
    *   class MyTopForSocless extends MyTop(defaults)
    * }}}
    */
  def soclessTop: T[String]

  /** Version of the `socless` runtime library to pull in. */
  def soclessVersion: T[String] = Task { "0.1.0-SNAPSHOT" }

  /** Output filename inside `Task.dest`. */
  def soclessOutputName: T[String] = Task { "design.json" }

  /** Runtime dependencies. Override to swap in an in-tree module:
    * {{{
    *   override def soclessRuntimeDeps = Task { Seq.empty }
    *   override def moduleDeps         = super.moduleDeps ++ Seq(soclessLib)
    * }}}
    */
  def soclessRuntimeDeps: T[Seq[Dep]] = Task {
    Seq(mvn"io.socless::socless::${soclessVersion()}")
  }

  /** Working directory for the elaboration JVM.
    * Override when your project resolves config paths relative to cwd:
    * {{{
    *   override def soclessWorkingDir = Task { mill.api.BuildCtx.workspaceRoot }
    * }}}
    */
  def soclessWorkingDir: T[os.Path] = Task { Task.dest }

  /** TCP port used by `soclessUI` and `soclessServe`. */
  def soclessUiPort: T[Int] = Task { 8765 }

  override def mvnDeps = Task {
    super.mvnDeps() ++ soclessRuntimeDeps()
  }

  /** Elaborate the design and write `design.json`. Forks a fresh JVM with
    * the module's `runClasspath` so the exporter sees your exact Chisel version. */
  def socless: T[PathRef] = Task {
    val out   = Task.dest / soclessOutputName()
    val cpStr = runClasspath().map(_.path.toString).mkString(java.io.File.pathSeparator)
    val rc = os.proc(
      "java", "-cp", cpStr,
      "socless.Launcher",
      "--top", soclessTop(),
      "--output", out.toString,
      // Workspace root -> exporter resolves SourceRefs (`src/main/scala/...`)
      // against it and embeds the file contents in design.json so the UI's
      // Source tab can show real code instead of just file:line:col.
      "--source-root", mill.api.BuildCtx.workspaceRoot.toString,
    ).call(
      stdout = os.Inherit,
      stderr = os.Inherit,
      cwd    = soclessWorkingDir(),
      check  = false,
    )
    if (rc.exitCode != 0)
      throw new RuntimeException(
        s"socless launcher exited with code ${rc.exitCode}. " +
        s"Re-run with `--log-level debug` for full stderr."
      )
    Task.log.info(s"[socless] design.json: $out")
    PathRef(out)
  }

  /** Elaborate, extract SoCLess.html from the runtime JAR, copy design.json
    * next to it, and serve the UI over HTTP.
    *
    * Usage:
    *   mill soclessUI              # default port from soclessUiPort (8765)
    *   mill soclessUI --port 9000  # custom port
    *
    * Blocks until Ctrl-C.
    */
  def soclessUI(args: Args) = Task.Command {
    val port  = SoclessModule.parsePort(args.value, soclessUiPort())
    val json  = socless().path
    val uiDir = Task.dest / "ui"
    os.makeDir.all(uiDir)

    extractHtml(runClasspath(), uiDir)
    os.copy.over(json, uiDir / "design.json")
    serve(uiDir, runClasspath(), port, Task.log)
  }

  /** Extract SoCLess.html, start a server, and open the browser -- without
    * elaborating a design first.  You can then drag-and-drop a `.fir`
    * (CHIRRTL) file onto the browser window: the server converts it on the
    * fly via `POST /convert` and the UI displays the result.
    *
    * Useful for:
    *   - Quick UI testing without a Chisel project
    *   - Inspecting CHIRRTL files produced by other tools
    *   - Standalone demos
    *
    * Usage:
    *   mill soclessServe              # default port from soclessUiPort (8765)
    *   mill soclessServe --port 9000  # custom port
    *
    * Blocks until Ctrl-C.
    */
  def soclessServe(args: Args) = Task.Command {
    val port  = SoclessModule.parsePort(args.value, soclessUiPort())
    val uiDir = Task.dest / "ui"
    os.makeDir.all(uiDir)

    extractHtml(runClasspath(), uiDir)
    // No design.json to copy -- the user will load one via the browser.
    serve(uiDir, runClasspath(), port, Task.log)
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private def extractHtml(cp: Seq[PathRef], uiDir: os.Path): Unit = {
    val urls = cp.map(_.path.toNIO.toUri.toURL).toArray
    val cl   = new java.net.URLClassLoader(urls, null)
    try {
      val stream = cl.getResourceAsStream("SoCLess.html")
      if (stream == null)
        throw new RuntimeException(
          "[socless] SoCLess.html not found in JAR -- re-run publishLocal after rebuilding socless"
        )
      os.write.over(uiDir / "SoCLess.html", stream.readAllBytes())
      stream.close()
    } finally cl.close()
  }

  /** Write the dynamic Python server, start it, open the browser. Blocks. */
  private def serve(uiDir: os.Path, cp: Seq[PathRef], port: Int, log: mill.api.Logger): Unit = {
    val cpStr      = cp.map(_.path.toString).mkString(java.io.File.pathSeparator)
    val serverScript = uiDir / "socless_server.py"
    os.write.over(serverScript, SoclessModule.pythonServer(cpStr))

    val url = s"http://localhost:$port/SoCLess.html"
    log.info(s"[socless] UI at $url (drag-drop .fir or .json) -- Ctrl-C to stop")
    SoclessModule.openBrowser(url, msg => log.info(msg))
    os.proc("python3", serverScript, port.toString)
      .call(stdout = os.Inherit, stderr = os.Inherit)
  }
}

object SoclessModule {

  /** Parse the leftover task arguments as a port: `--port N` or a bare `N`.
    * Mill passes flag-looking tokens through positionally, so the documented
    * `--port 9000` form arrives here as Seq("--port", "9000").
    */
  def parsePort(args: Seq[String], default: Int): Int = {
    def toPort(s: String): Int =
      s.toIntOption.filter(p => p > 0 && p <= 65535).getOrElse(
        sys.error(s"[socless] invalid port '$s' (expected 1-65535)")
      )
    args match {
      case Seq()            => default
      case Seq("--port", n) => toPort(n)
      case Seq(n)           => toPort(n)
      case other            => sys.error(
        s"[socless] unrecognized arguments: ${other.mkString(" ")} (usage: soclessUI [--port] <port>)"
      )
    }
  }

  /** Open `url` in the default browser, best-effort: a missing opener must
    * never kill the server task (macOS has no xdg-open, headless Linux may
    * lack xdg-utils).
    */
  def openBrowser(url: String, log: String => Unit): Unit = {
    val opener =
      if (sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")) "open"
      else "xdg-open"
    try { os.proc(opener, url).spawn(stdout = os.Pipe, stderr = os.Pipe); () }
    catch { case _: Exception => log(s"[socless] could not open a browser, visit $url manually") }
  }

  /** Inline Python HTTP server script.
    *
    * Serves static files from the directory it lives in and additionally
    * handles `POST /convert`: accepts a CHIRRTL body, converts it to
    * design.json via `socless.Launcher --fir-input`, and returns the JSON.
    *
    * The `classpath` arg is baked into the script so no env vars are needed
    * at runtime.
    */
  def pythonServer(classpath: String): String = {
    // Triple-quote, embedded safely in a triple-quoted Scala string via
    // string interpolation (cannot write """ literally here).
    val q3        = "\"\"\""
    val cpEscaped = classpath.replace("\\", "\\\\")
    s"""#!/usr/bin/env python3
# SoCLess standalone UI server.
# Serves static files and converts CHIRRTL to design.json on POST /convert.
import http.server
import os
import subprocess
import sys
import tempfile

CLASSPATH = $q3$cpEscaped$q3
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
# Loopback only: /convert runs a JVM on the request body, that must not be
# reachable from the LAN. Set SOCLESS_BIND to override deliberately.
HOST = os.environ.get("SOCLESS_BIND", "127.0.0.1")
MAX_BODY = 128 * 1024 * 1024  # 128 MB of CHIRRTL is enough for anyone
SERVE_DIR = os.path.dirname(os.path.abspath(__file__))


class Handler(http.server.SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=SERVE_DIR, **kwargs)

    def log_message(self, fmt, *args):
        print("[socless]", fmt % args, file=sys.stderr)

    def do_POST(self):
        if self.path != "/convert":
            self.send_error(404, "Only POST /convert is supported")
            return

        length = int(self.headers.get("Content-Length", 0))
        if length > MAX_BODY:
            self.send_error(413, "Body too large (%d bytes, max %d)" % (length, MAX_BODY))
            return
        body = self.rfile.read(length).decode("utf-8", errors="replace")

        with tempfile.TemporaryDirectory() as tmp:
            fir_path = os.path.join(tmp, "input.fir")
            json_path = os.path.join(tmp, "design.json")
            with open(fir_path, "w", encoding="utf-8") as f:
                f.write(body)

            result = subprocess.run(
                ["java", "-cp", CLASSPATH, "socless.Launcher",
                 "--fir-input", fir_path,
                 "--output", json_path],
                capture_output=True,
                text=True,
            )
            if result.returncode != 0:
                msg = (result.stderr or result.stdout or "launcher failed").strip()
                # send_error puts the message into the status line: it must
                # be a single line or the HTTP response is malformed.
                first = msg.splitlines()[0] if msg else "launcher failed"
                self.send_error(500, "Conversion failed: " + first[:200])
                print("[socless] /convert error:", msg, file=sys.stderr)
                return

            with open(json_path, "rb") as f:
                data = f.read()

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    # ThreadingHTTPServer: a slow /convert (JVM startup + elaboration) must
    # not block static file serving.
    try:
        server = http.server.ThreadingHTTPServer((HOST, PORT), Handler)
    except OSError as e:
        print("[socless] cannot listen on %s:%d: %s" % (HOST, PORT, e), file=sys.stderr)
        print("[socless] pick another port: mill <module>.soclessUI --port <N>", file=sys.stderr)
        sys.exit(1)
    print("[socless] serving on http://" + HOST + ":" + str(PORT) + "/SoCLess.html -- Ctrl-C to stop",
          file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
"""
  }
}
