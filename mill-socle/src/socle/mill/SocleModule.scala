package socle.mill

import mill._
import mill.scalalib._

/** Mill plugin trait -- mix into your Chisel ScalaModule to get `socle`,
  * `socleUI`, and `socleServe` tasks.
  *
  * Minimum `build.mill`:
  * {{{
  *   //| mill-version: 1.1.6
  *   //| mvnDeps: ["io.socle::mill-socle:0.1.0-SNAPSHOT"]
  *
  *   import mill._, scalalib._
  *   import socle.mill.SocleModule
  *
  *   object myDesign extends ScalaModule with SocleModule {
  *     def scalaVersion = "2.13.18"
  *     def mvnDeps      = Seq(mvn"org.chipsalliance::chisel:7.16.0")
  *     def socleTop   = "mypkg.MyTop"
  *   }
  * }}}
  *
  *   mill myDesign.socle          # elaborate + emit design.json
  *   mill myDesign.socleUI        # elaborate + serve UI at localhost:8765
  *   mill myDesign.socleUI --port 9000
  *   mill myDesign.socleServe     # serve UI (no prior elaborate needed; load .fir via browser)
  *   mill myDesign.socleServe --port 9000
  */
trait SocleModule extends ScalaModule {

  /** Fully-qualified class name of the top-level Chisel module to elaborate.
    * Must have a no-argument constructor; wrap parameterised modules:
    * {{{
    *   class MyTopForSocle extends MyTop(defaults)
    * }}}
    */
  def socleTop: T[String]

  /** Tops to elaborate. Defaults to the single `socleTop`. Set this to view
    * several designs from one build (the whole SoC, a single core, the NoC,
    * ...) and switch between them in the UI's design picker (issue #117):
    * {{{
    *   def socleTops = Seq("mypkg.SoC", "mypkg.Core", "mypkg.NoC")
    * }}}
    * Each top elaborates in its own JVM fork; one broken top is skipped with
    * an error and does not hide the others. The first top is the default
    * `design.json`; the rest become `design-<top>.json`, indexed by a
    * `designs.json` the UI reads. */
  def socleTops: T[Seq[String]] = Task { Seq(socleTop()) }

  /** Version of the `socle` runtime library to pull in. Defaults to the
    * version this plugin was built as (generated from V.publishVer in
    * build.mill), so the two artifacts cannot drift (issue #104). Override
    * to pin a different runtime.
    */
  def socleVersion: T[String] = Task { SocleBuildInfo.version }

  /** Output filename inside `Task.dest`. */
  def socleOutputName: T[String] = Task { "design.json" }

  /** Runtime dependencies. Override to swap in an in-tree module:
    * {{{
    *   override def socleRuntimeDeps = Task { Seq.empty }
    *   override def moduleDeps         = super.moduleDeps ++ Seq(socleLib)
    * }}}
    */
  def socleRuntimeDeps: T[Seq[Dep]] = Task {
    Seq(mvn"io.socle::socle::${socleVersion()}")
  }

  /** Working directory for the elaboration JVM.
    * Override when your project resolves config paths relative to cwd:
    * {{{
    *   override def socleWorkingDir = Task { mill.api.BuildCtx.workspaceRoot }
    * }}}
    */
  def socleWorkingDir: T[os.Path] = Task { Task.dest }

  /** TCP port used by `socleUI` and `socleServe`. */
  def socleUiPort: T[Int] = Task { 8765 }

  override def mvnDeps = Task {
    super.mvnDeps() ++ socleRuntimeDeps()
  }

  /** Elaborate each top in `socleTops` and write the design JSON(s). Forks a
    * fresh JVM per top with the module's `runClasspath` so the exporter sees
    * your exact Chisel version. The first top is `design.json` (the default the
    * UI auto-loads); additional tops are `design-<top>.json` plus a
    * `designs.json` index for the picker (issue #117). Returns the primary
    * `design.json`. */
  def socle: T[PathRef] = Task {
    val tops  = socleTops()
    require(tops.nonEmpty, "socleTops must not be empty")
    val cpStr = runClasspath().map(_.path.toString).mkString(java.io.File.pathSeparator)
    val root  = mill.api.BuildCtx.workspaceRoot.toString

    def fileFor(i: Int, top: String): String =
      if (i == 0) socleOutputName() else s"design-${top.replace('.', '_')}.json"

    var failed = 0
    val ok = tops.zipWithIndex.flatMap { case (top, i) =>
      val out = Task.dest / fileFor(i, top)
      val rc = os.proc(
        "java", "-cp", cpStr,
        "socle.cli.Launcher",
        "--top", top,
        "--output", out.toString,
        // Workspace root -> exporter resolves SourceRefs (`src/main/scala/...`)
        // against it and embeds the file contents so the UI's Source tab can
        // show real code instead of just file:line:col.
        "--source-root", root,
      ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = socleWorkingDir(), check = false)
      if (rc.exitCode != 0) {
        Task.log.error(s"[socle] top '$top' failed (exit ${rc.exitCode}); skipping it")
        failed += 1
        None
      } else {
        Task.log.info(s"[socle] $top -> $out")
        Some((top, fileFor(i, top)))
      }
    }

    val primary = Task.dest / socleOutputName()
    if (!os.exists(primary))
      throw new RuntimeException(s"socle: the first top '${tops.head}' failed; no $primary was produced.")

    // Multi-top index for the UI picker (issue #117). A single top keeps the
    // plain 'live' behavior - no index emitted.
    if (ok.size > 1) {
      def esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
      val items = ok.map { case (top, file) =>
        s"""    { "id": "${esc(top)}", "label": "${esc(top.split('.').last)}", "path": "${esc(file)}" }"""
      }.mkString(",\n")
      os.write.over(Task.dest / "designs.json", s"""{\n  "designs": [\n$items\n  ]\n}\n""")
    }
    if (failed > 0) Task.log.error(s"[socle] $failed of ${tops.size} top(s) failed to elaborate.")
    Task.log.info(s"[socle] design.json: $primary")
    PathRef(primary)
  }

  /** Elaborate, extract socle.html from the runtime JAR, copy design.json
    * next to it, and serve the UI over HTTP.
    *
    * Usage:
    *   mill socleUI              # default port from socleUiPort (8765)
    *   mill socleUI --port 9000  # custom port
    *
    * Blocks until Ctrl-C.
    */
  def socleUI(args: Args) = Task.Command {
    val port  = SocleModule.parsePort(args.value, socleUiPort())
    val json  = socle().path
    val uiDir = Task.dest / "ui"
    os.makeDir.all(uiDir)

    // Copy every emitted JSON (design.json, design-<top>.json, designs.json)
    // so the multi-top picker has them all in the served dir (issue #117). The
    // UI HTML itself is served from the JAR by the forked server, not copied.
    for (p <- os.list(json / os.up) if p.last.endsWith(".json")) os.copy.over(p, uiDir / p.last)
    serve(uiDir, runClasspath(), port, Task.log)
  }

  /** Extract socle.html, start a server, and open the browser -- without
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
    *   mill socleServe              # default port from socleUiPort (8765)
    *   mill socleServe --port 9000  # custom port
    *
    * Blocks until Ctrl-C.
    */
  def socleServe(args: Args) = Task.Command {
    val port  = SocleModule.parsePort(args.value, socleUiPort())
    val uiDir = Task.dest / "ui"
    os.makeDir.all(uiDir)

    // No design.json to copy -- the user will load one via the browser. The UI
    // HTML is served from the JAR by the forked server.
    serve(uiDir, runClasspath(), port, Task.log)
  }

  /** Render the elaborated design's baked layout geometry to per-module debug
    * SVG + text (no UI/browser). Forks `Launcher debug-layout` on the emitted
    * design.json. Returns the output directory.
    *
    *   mill myDesign.socleDebugLayout            # writes design.json's layout to SVG + text
    *   mill show myDesign.socleDebugLayout       # print the output directory path
    */
  def socleDebugLayout: T[PathRef] = Task {
    val json   = socle().path
    val outDir = Task.dest / "layout-debug"
    val cpStr  = runClasspath().map(_.path.toString).mkString(java.io.File.pathSeparator)
    val rc = os.proc(
      "java", "-cp", cpStr, "socle.cli.Launcher", "debug-layout",
      json.toString, "--out", outDir.toString,
    ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    if (rc.exitCode != 0) throw new RuntimeException(s"socle debug-layout exited ${rc.exitCode}")
    Task.log.info(s"[socle] layout-debug -> $outDir")
    PathRef(outDir)
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  /** Fork the standalone JVM UI server (`socle.cli.Launcher serve`) on the run
    * classpath and block until Ctrl-C. This is the exact same server the
    * standalone CLI uses, so there is one implementation and no `python3`
    * dependency (issue #155): it serves the UI HTML embedded in the JAR, the
    * JSONs from `uiDir` via `--dir`, and converts dropped `.fir` files in
    * process via `POST /convert`. */
  private def serve(uiDir: os.Path, cp: Seq[PathRef], port: Int, log: mill.api.Logger): Unit = {
    val cpStr = cp.map(_.path.toString).mkString(java.io.File.pathSeparator)
    val url   = s"http://localhost:$port/socle.html"
    log.info(s"[socle] UI at $url (drag-drop .fir or .json) -- Ctrl-C to stop")
    SocleModule.openBrowser(url, msg => log.info(msg))
    os.proc(
      "java", "-cp", cpStr, "socle.cli.Launcher", "serve",
      "--port", port.toString, "--dir", uiDir.toString, "--no-open",
    ).call(stdout = os.Inherit, stderr = os.Inherit)
  }
}

object SocleModule {

  /** Parse the leftover task arguments as a port: `--port N` or a bare `N`.
    * Mill passes flag-looking tokens through positionally, so the documented
    * `--port 9000` form arrives here as Seq("--port", "9000").
    */
  def parsePort(args: Seq[String], default: Int): Int = {
    def toPort(s: String): Int =
      s.toIntOption.filter(p => p > 0 && p <= 65535).getOrElse(
        sys.error(s"[socle] invalid port '$s' (expected 1-65535)")
      )
    args match {
      case Seq()            => default
      case Seq("--port", n) => toPort(n)
      case Seq(n)           => toPort(n)
      case other            => sys.error(
        s"[socle] unrecognized arguments: ${other.mkString(" ")} (usage: socleUI [--port] <port>)"
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
    catch { case _: Exception => log(s"[socle] could not open a browser, visit $url manually") }
  }
}
