package socless.lsp

import com.google.gson.JsonObject
import scala.util.Try
import scala.util.control.NonFatal

/** One test to simulate: the source file it was asked from, its suite and full name. */
case class SimRequest(file: os.Path, suite: String, test: String)

/** The Simulate settings: the ChiselSim ScalaTest option that turns waves on
  * (`-D<waveFlag>=1`) and the executable that opens a trace. */
case class SimSettings(waveFlag: String = SimSettings.DefaultWaveFlag, surferCommand: String = SimSettings.DefaultSurfer)

object SimSettings {
  val DefaultWaveFlag = "emitVcd" // ChiselSim's own; Cli.EmitVcd
  val DefaultSurfer   = "surfer"

  private val FlagName = "[A-Za-z0-9_.-]+"

  private def str(o: JsonObject, k: String): Option[String] =
    Option(o.get(k)).filter(_.isJsonPrimitive).map(_.getAsString.trim).filter(_.nonEmpty)

  /** From an LSP settings object: `waveFlag` and `surferCommand`, each
    * defaulted when absent, blank or (the flag) not an option name. */
  private def fromObject(o: JsonObject): SimSettings =
    SimSettings(str(o, "waveFlag").filter(_.matches(FlagName)).getOrElse(DefaultWaveFlag),
      str(o, "surferCommand").getOrElse(DefaultSurfer))

  /** A non-blank `waveFlag` in a flat settings object that is not an option
    * name (so [[fromInit]] ignored it), for a warning. */
  def invalidFlag(opts: Any): Option[String] = opts match {
    case o: JsonObject => str(o, "waveFlag").filterNot(_.matches(FlagName))
    case _             => None
  }

  /** The `socless` section of `workspace/didChangeConfiguration` settings. */
  def section(settings: Any): Option[JsonObject] = settings match {
    case o: JsonObject => Option(o.get("socless")).filter(_.isJsonObject).map(_.getAsJsonObject)
    case _             => None
  }

  /** `initializationOptions` (flat: `{"waveFlag": ..., "surferCommand": ...}`). */
  def fromInit(opts: Any): SimSettings = opts match {
    case o: JsonObject => fromObject(o)
    case _             => SimSettings()
  }

  /** `workspace/didChangeConfiguration` settings: only a `socless` section
    * counts (VS Code's synchronized section); anything else (Neovim sends `{}`)
    * is None, so the settings from `initialize` are kept. */
  def fromConfig(settings: Any): Option[SimSettings] = section(settings).map(fromObject)
}

/** What a simulation ended with: pass or fail (the test JVM's exit code), the
  * trace found, and the text the editor shows. */
final case class SimOutcome(passed: Boolean, waves: Option[os.Path], message: String)

/** Runs one ChiselSim test with waves on and opens the trace in Surfer
  * (docs/ide-vscode.md, "Simulate").
  *
  * The test JVM is `java <fork.jvmArgs> -Dchisel.project.root=<dir> -cp <cp>
  * org.scalatest.tools.Runner -oW -D<waveFlag>=1 -s <suite> -t <test>`, without
  * the probe agent. The wave flag is a ScalaTest config-map entry (a Runner
  * argument), which is where ChiselSim reads its options; a JVM `-D` would be
  * ignored. `chisel.project.root` comes after the user's JVM args, so it wins,
  * and points svsim's `build/chiselsim` at `<dir>`: Mill's `MILL_WORKSPACE_ROOT`
  * (kept, tests read their config through it) would otherwise send the trace
  * to the workspace root. `<dir>` is [[SimRunner.dirFor]], emptied at the
  * start of each run so the last run of each test is kept; it is the cwd too.
  * env is this process's plus `fork.env`; output goes to `<dir>/sim.log`.
  *
  * After the JVM exits (pass or fail) the newest `*.vcd` / `*.fst` under
  * `<dir>` is opened with `<surferCommand> <trace>`, detached, its output in
  * `<dir>/surfer.log`. One simulation at a time ([[start]] refuses a second);
  * a run past `timeoutMs` has its process tree killed.
  */
final class SimRunner(
    root: os.Path,
    java: String = ViewRunner.defaultJava,
    timeoutMs: Long = SimRunner.DefaultTimeoutMs,
    log: String => Unit = m => Console.err.println(s"[socless-lsp] $m"),
) {
  private val lock    = new Object
  private var running = Option.empty[SimRequest] // guarded by lock
  private var current = Option.empty[Process]    // the test JVM, guarded by lock
  private var closed  = false                    // guarded by lock

  def dirFor(suite: String, test: String): os.Path = SimRunner.dirFor(root, suite, test)

  /** Starts `req` on its own daemon thread, unless a simulation is running:
    * then returns it (and starts nothing). `started` runs before the thread
    * starts, so it always precedes `done`; `fork` (Mill) runs on that thread. */
  def start(req: SimRequest, settings: SimSettings, fork: () => Either[String, TestFork],
      started: () => Unit, done: SimOutcome => Unit): Option[SimRequest] = lock.synchronized {
    if (closed) None
    else running match {
      case busy @ Some(_) => busy
      case None =>
        running = Some(req)
        started()
        val t = new Thread(() => {
          val out =
            try run(req, settings, Try(fork()).toEither.left.map(e => String.valueOf(e.getMessage)).flatten)
            catch { case NonFatal(e) => SimOutcome(passed = false, None, SimRunner.head(req, passed = false) + s" $e") }
            finally lock.synchronized { running = None }
          try done(out) catch { case NonFatal(e) => log(s"simulation result handler failed: $e") }
        }, "socless-sim-runner")
        t.setDaemon(true)
        t.start()
        None
    }
  }

  /** Kills a running simulation's process tree; later [[start]]s do nothing. */
  def close(): Unit = {
    val p = lock.synchronized { closed = true; current }
    p.foreach(p => ProcessTree.kill(p.toHandle))
  }

  /** One simulation, synchronously. Never throws for a run that fails. */
  def run(req: SimRequest, settings: SimSettings, fork: Either[String, TestFork]): SimOutcome = fork match {
    case Left(msg) => SimOutcome(passed = false, None, s"Simulation not run: ${req.suite} ${req.test}. $msg")
    case Right(f) =>
      val dir = dirFor(req.suite, req.test)
      val base = root / "out" / "socless" / "sim"
      require(dir.startsWith(base) && dir != base, s"refusing simulation dir $dir")
      os.remove.all(dir)
      os.makeDir.all(dir)
      val simLog = dir / "sim.log"
      val cmd = Seq(java) ++ f.jvmArgs ++ Seq(s"-Dchisel.project.root=$dir",
        "-cp", f.classpath.mkString(_root_.java.io.File.pathSeparator),
        "org.scalatest.tools.Runner", "-oW", s"-D${settings.waveFlag}=1", "-s", req.suite, "-t", req.test)
      log(s"simulate: ${cmd.mkString(" ")} (cwd $dir, log $simLog)")
      val (exit, timedOut) =
        try {
          val sub = os.proc(cmd).spawn(cwd = dir, env = f.env, stdin = "", stdout = simLog, mergeErrIntoOut = true)
          if (lock.synchronized { current = Some(sub.wrapped); closed }) ProcessTree.kill(sub.wrapped.toHandle)
          try {
            if (ProcessTree.waitOrKill(sub.wrapped, timeoutMs)) (sub.wrapped.exitValue, false) else (-1, true)
          } finally lock.synchronized { current = None }
        } catch {
          case NonFatal(e) =>
            return SimOutcome(passed = false, None, s"${SimRunner.head(req, passed = false)} cannot start $java: ${e.getMessage}")
        }
      val waves = SimRunner.newestTrace(dir)
      // ScalaTest exits 0 when `-t` matches no test: that is not a pass
      val noTest = waves.isEmpty && !timedOut && SimRunner.ranNoTest(simLog)
      val passed = exit == 0 && !noTest
      val timeout = if (timedOut) s" Timed out after ${timeoutMs / 1000} s." else ""
      val tail = waves match {
        case None if noTest => s"""No test named "${req.test}" ran in ${req.suite}."""
        case None => s"No waveform found (is waves support enabled? flag -D${settings.waveFlag}=1)"
        case Some(w) if lock.synchronized(closed) => s"Waves: $w" // shut down meanwhile: no viewer
        case Some(w) => s"Waves: $w" + openWaves(settings.surferCommand, w, dir).fold("")(e => s" ($e)")
      }
      SimOutcome(passed, waves, s"${SimRunner.head(req, passed)}$timeout $tail")
  }

  /** Starts the viewer detached (never waited on, output to a log file, never
    * our stdout). Some(problem) when it cannot be started. */
  private def openWaves(surfer: String, trace: os.Path, dir: os.Path): Option[String] = {
    val viewerLog = dir / "surfer.log"
    try {
      new ProcessBuilder(surfer, trace.toString).directory(dir.toIO)
        .redirectOutput(viewerLog.toIO).redirectErrorStream(true).start()
        .getOutputStream.close() // empty stdin, never ours (the LSP channel)
      log(s"simulate: opened $trace with $surfer (log $viewerLog)")
      None
    } catch {
      case NonFatal(e) =>
        log(s"simulate: cannot start $surfer: $e")
        Some(s"cannot start $surfer: ${e.getMessage}")
    }
  }
}

object SimRunner {
  val DefaultTimeoutMs: Long = 30L * 60 * 1000

  private[lsp] def head(req: SimRequest, passed: Boolean): String =
    s"Simulation ${if (passed) "passed" else "failed"}: ${req.suite} ${req.test}."

  /** `<root>/out/socless/sim/<suite>/<test>`, each a [[segment]]. */
  def dirFor(root: os.Path, suite: String, test: String): os.Path =
    root / "out" / "socless" / "sim" / segment(suite) / segment(test)

  private val Max = 80

  /** A safe single path segment for `name`: kept as is when it is already
    * plain (`[-_.A-Za-z0-9]`, not starting with `.`, at most 80 chars); else
    * ChiselSim's sanitizing (`[^-_.A-Za-z0-9]` to `-`, a leading `.` too),
    * cut, plus `-` and 8 hex digits of the name's SHA-256, so two names that
    * sanitize alike stay apart. */
  private[lsp] def segment(name: String): String =
    if (name.matches("[-_.A-Za-z0-9]+") && !name.startsWith(".") && name.length <= Max) name
    else {
      val safe = name.replaceAll("[^-_.A-Za-z0-9]", "-").replaceFirst("^\\.", "-")
      val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.getBytes("UTF-8"))
        .take(4).map("%02x".format(_)).mkString
      s"${safe.take(Max - 9)}-$hash"
    }

  /** ScalaTest's (3.2.x) run summary says no test ran. */
  private[lsp] def ranNoTest(log: os.Path): Boolean =
    os.isFile(log) && os.read.lines.stream(log).exists(l =>
      l.contains("Total number of tests run: 0") || l.contains("No tests were executed"))

  /** The most recently modified `*.vcd` / `*.fst` under `dir`. */
  private[lsp] def newestTrace(dir: os.Path): Option[os.Path] =
    if (!os.isDir(dir)) None
    else {
      val traces = os.walk(dir).filter(p => (p.ext == "vcd" || p.ext == "fst") && os.isFile(p))
      if (traces.isEmpty) None else Some(traces.maxBy(os.mtime(_)))
    }
}
