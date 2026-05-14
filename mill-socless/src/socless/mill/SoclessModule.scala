package socless.mill

import mill._
import mill.scalalib._

/** Mill plugin trait — mix into your Chisel ScalaModule to get `socless` and
  * `soclessUI` tasks.
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
  *   mill myDesign.soclessUI        # serve UI at localhost:8765
  *   mill myDesign.soclessUI --port 9000
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

  /** TCP port used by `soclessUI`. */
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
    val port  = args.value.headOption.fold(soclessUiPort())(_.toInt)
    val json  = socless().path
    val uiDir = Task.dest / "ui"
    os.makeDir.all(uiDir)

    val cp = runClasspath().map(_.path.toNIO.toUri.toURL).toArray
    val cl = new java.net.URLClassLoader(cp, null)
    try {
      val stream = cl.getResourceAsStream("SoCLess.html")
      if (stream == null)
        throw new RuntimeException(
          "[socless] SoCLess.html not found in JAR — re-run publishLocal after rebuilding socless"
        )
      os.write.over(uiDir / "SoCLess.html", stream.readAllBytes())
      stream.close()
    } finally cl.close()

    os.copy.over(json, uiDir / "design.json")
    val url = s"http://localhost:$port/SoCLess.html"
    Task.log.info(s"[socless] UI at $url — Ctrl-C to stop")
    os.proc("xdg-open", url).spawn()
    os.proc("python3", "-m", "http.server", port.toString, "--directory", uiDir.toString)
      .call(stdout = os.Inherit, stderr = os.Inherit)
  }
}