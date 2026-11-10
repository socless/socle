package socle.lsp

import com.google.gson.JsonObject
import scala.util.Try
import scala.util.control.NonFatal
import socle.schema.SchematicSettings
import socle.wave.WaveSettings

/** One test to simulate: the source file it was asked from, its suite and full name. */
case class SimRequest(file: os.Path, suite: String, test: String)

/** The Simulate settings: the ChiselSim ScalaTest option that turns waves on
  * (`-D<waveFlag>=1`), the executable that opens a trace, and the wave viewer
  * choice (`surfer` | `surfer-panel` | `custom`, with its command) and
  * [[WaveSettings]]; with them, from the same settings object, the panel's
  * schematic defaults ([[SchematicSettings]]). */
case class SimSettings(
    waveFlag: String = SimSettings.DefaultWaveFlag,
    surferCommand: String = SimSettings.DefaultSurfer,
    waveViewer: String = SimSettings.DefaultViewer,
    waveViewerCommand: String = "",
    wave: WaveSettings = WaveSettings(),
    schematic: SchematicSettings = SchematicSettings()
)

object SimSettings {
  val DefaultWaveFlag = "emitVcd" // ChiselSim's own; Cli.EmitVcd
  val DefaultSurfer   = "surfer"
  val DefaultViewer   = "surfer"
  val Viewers         = Set("surfer", "surfer-panel", "custom")

  private val FlagName = "[A-Za-z0-9_.-]+"

  private def str(o: JsonObject, k: String): Option[String] =
    Option(o.get(k)).filter(_.isJsonPrimitive).map(_.getAsString.trim).filter(_.nonEmpty)

  private def bool(o: JsonObject, k: String, default: Boolean): Boolean =
    Option(o.get(k)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isBoolean)
      .map(_.getAsBoolean).getOrElse(default)

  private def nonNegInt(o: JsonObject, k: String, default: Int): Int =
    Option(o.get(k)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isNumber)
      .flatMap(e => Try(e.getAsBigDecimal.intValueExact).toOption).filter(_ >= 0).getOrElse(default)

  /** From an LSP settings object: every key defaulted when absent or invalid
    * (blank text, a `waveFlag` that is not an option name, an unknown
    * `waveViewer`, a negative or non-integer delay, a non-boolean flag). */
  private def fromObject(o: JsonObject): SimSettings = {
    val d = WaveSettings()
    SimSettings(
      str(o, "waveFlag").filter(_.matches(FlagName)).getOrElse(DefaultWaveFlag),
      str(o, "surferCommand").getOrElse(DefaultSurfer),
      str(o, "waveViewer").filter(Viewers).getOrElse(DefaultViewer),
      str(o, "waveViewerCommand").getOrElse(""),
      WaveSettings(
        bool(o, "wavesFollowSelection", d.followSelection),
        bool(o, "wavesShowClockReset", d.showClockReset),
        bool(o, "wavesFoldBundles", d.foldBundles),
        bool(o, "wavesShowWires", d.showWires),
        bool(o, "wavesColorByDirection", d.colorByDirection),
        nonNegInt(o, "wavesFollowDelayMs", d.followDelayMs)),
      schematic(o))
  }

  /** The `schematic*` keys and `followCursor`; an unknown density, label
    * size or kind is left out (its default kept). */
  private def schematic(o: JsonObject): SchematicSettings = {
    val d = SchematicSettings()
    val hide = Option(o.get("schematicHide")).filter(_.isJsonArray).fold(d.hide) { a =>
      (0 until a.getAsJsonArray.size).map(a.getAsJsonArray.get).filter(_.isJsonPrimitive)
        .map(_.getAsString.trim).filter(SchematicSettings.Kinds.contains).distinct
    }
    SchematicSettings(
      colorizeComponents = bool(o, "schematicColorizeComponents", d.colorizeComponents),
      colorizeWires      = bool(o, "schematicColorizeWires", d.colorizeWires),
      density            = str(o, "schematicDensity").filter(SchematicSettings.Densities.contains).getOrElse(d.density),
      labelSize          = str(o, "schematicLabelSize").filter(SchematicSettings.LabelSizes.contains).getOrElse(d.labelSize),
      autoOptimize       = bool(o, "schematicAutoOptimize", d.autoOptimize),
      hintBar            = bool(o, "schematicHintBar", d.hintBar),
      showLayers         = bool(o, "schematicShowLayers", d.showLayers),
      hide               = hide,
      followCursor       = bool(o, "followCursor", d.followCursor),
    )
  }

  /** A non-blank `waveFlag` in a flat settings object that is not an option
    * name (so [[fromInit]] ignored it), for a warning. */
  def invalidFlag(opts: Any): Option[String] = opts match {
    case o: JsonObject => str(o, "waveFlag").filterNot(_.matches(FlagName))
    case _             => None
  }

  /** The `socle` section of `workspace/didChangeConfiguration` settings. */
  def section(settings: Any): Option[JsonObject] = settings match {
    case o: JsonObject => Option(o.get("socle")).filter(_.isJsonObject).map(_.getAsJsonObject)
    case _             => None
  }

  /** `initializationOptions` (flat: `{"waveFlag": ..., "surferCommand": ...}`). */
  def fromInit(opts: Any): SimSettings = opts match {
    case o: JsonObject => fromObject(o)
    case _             => SimSettings()
  }

  /** `workspace/didChangeConfiguration` settings: only a `socle` section
    * counts (VS Code's synchronized section); anything else (Neovim sends `{}`)
    * is None, so the settings from `initialize` are kept. */
  def fromConfig(settings: Any): Option[SimSettings] = section(settings).map(fromObject)
}

/** What a simulation ended with: pass or fail (the test JVM's exit code), the
  * trace found, the text the editor shows, and why the viewer this runner
  * started on the trace could not start (also in `message`). */
final case class SimOutcome(passed: Boolean, waves: Option[os.Path], message: String,
    viewerProblem: Option[String] = None)

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
  * `<dir>` is opened in the viewer [[SimRunner.viewerCommand]] names, detached,
  * its output in `<dir>/surfer.log`; in `surfer` mode the language server
  * starts Surfer itself, over WCP ([[WcpSession]]). One simulation at a time ([[start]]
  * refuses a second); a run past `timeoutMs` has its process tree killed.
  *
  * A run that leaves a trace (pass or fail, not timed out) gets a random hex
  * id ([[currentTrace]]), the name the live panel serves it under; the
  * previous id is dropped as soon as the next run starts (its directory may
  * be wiped).
  */
final class SimRunner(
    root: os.Path,
    java: String = ViewRunner.defaultJava,
    timeoutMs: Long = SimRunner.DefaultTimeoutMs,
    log: String => Unit = m => Console.err.println(s"[socle-lsp] $m"),
) {
  private val lock    = new Object
  private var running = Option.empty[SimRequest] // guarded by lock
  private var current = Option.empty[Process]    // the test JVM, guarded by lock
  private var closed  = false                    // guarded by lock
  private var trace   = Option.empty[(String, os.Path, String)] // guarded by lock

  /** The last run's trace (pass or fail, not timed out): its id (random hex), file and
    * `<suite>#<test>`; None from the start of the next run. */
  def currentTrace: Option[(String, os.Path, String)] = lock.synchronized(trace)

  /** The simulation running, if any. */
  def busy: Option[SimRequest] = lock.synchronized(running)

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
        trace = None
        started()
        val t = new Thread(() => {
          val out =
            try run(req, settings, Try(fork()).toEither.left.map(e => String.valueOf(e.getMessage)).flatten)
            catch { case NonFatal(e) => SimOutcome(passed = false, None, SimRunner.head(req, passed = false) + s" $e") }
            finally lock.synchronized { running = None }
          try done(out) catch { case NonFatal(e) => log(s"simulation result handler failed: $e") }
        }, "socle-sim-runner")
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

  /** One simulation, synchronously; drops the previous trace id first. Never
    * throws for a run that fails. */
  def run(req: SimRequest, settings: SimSettings, fork: Either[String, TestFork]): SimOutcome =
    run(req, settings, fork, Nil)

  /** `runnerArgs`: more ScalaTest Runner arguments after the wave flag (tests
    * pass `-DfirtoolOpts=...` through it; no setting gives users this yet). */
  private[lsp] def run(req: SimRequest, settings: SimSettings, fork: Either[String, TestFork], runnerArgs: Seq[String]): SimOutcome = {
    lock.synchronized { trace = None }
    simulate(req, settings, fork, runnerArgs)
  }

  private def simulate(req: SimRequest, settings: SimSettings, fork: Either[String, TestFork],
      runnerArgs: Seq[String] = Nil): SimOutcome = fork match {
    case Left(msg) => SimOutcome(passed = false, None, s"Simulation not run: ${req.suite} ${req.test}. $msg")
    case Right(f) =>
      val dir = dirFor(req.suite, req.test)
      val base = root / "out" / "socle" / "sim"
      require(dir.startsWith(base) && dir != base, s"refusing simulation dir $dir")
      os.remove.all(dir)
      os.makeDir.all(dir)
      val simLog = dir / "sim.log"
      val cmd = Seq(java) ++ f.jvmArgs ++ Seq(s"-Dchisel.project.root=$dir",
        "-cp", f.classpath.mkString(_root_.java.io.File.pathSeparator),
        "org.scalatest.tools.Runner", "-oW", s"-D${settings.waveFlag}=1") ++ runnerArgs ++ Seq("-s", req.suite, "-t", req.test)
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
      val viewerProblem = waves.filter(_ => !lock.synchronized(closed)) // shut down meanwhile: no viewer
        .flatMap(openWaves(settings, _, dir))
      val tail = waves match {
        case None if noTest => s"""No test named "${req.test}" ran in ${req.suite}."""
        case None => s"No waveform found (is waves support enabled? flag -D${settings.waveFlag}=1)"
        case Some(w) => s"Waves: $w" + viewerProblem.fold("")(e => s" ($e)")
      }
      // a failing test's waves are the ones to look at; a killed run's trace is cut short
      if (!timedOut) waves.foreach(w => lock.synchronized { if (!closed) trace = Some((SimRunner.newId(), w, s"${req.suite}#${req.test}")) })
      SimOutcome(passed, waves, s"${SimRunner.head(req, passed)}$timeout $tail", viewerProblem)
  }

  /** Starts the viewer detached (never waited on, output to a log file, never
    * our stdout); none for `surfer` and `surfer-panel`. Some(problem) when it cannot be started. */
  private def openWaves(settings: SimSettings, trace: os.Path, dir: os.Path): Option[String] =
    SimRunner.viewerCommand(settings, trace).flatMap(openWith(_, trace, dir))

  private def openWith(cmd: Seq[String], trace: os.Path, dir: os.Path): Option[String] =
    SimRunner.launch(cmd, trace, dir, log, append = false)
}

object SimRunner {
  /** Starts `cmd` on `trace` detached in `dir` (never waited on, empty stdin,
    * output to `dir/surfer.log`, appended to when `append`, never our stdout).
    * Some(problem) when it cannot be started. */
  private[lsp] def launch(cmd: Seq[String], trace: os.Path, dir: os.Path, log: String => Unit, append: Boolean): Option[String] = {
    val surfer = cmd.head
    val viewerLog = dir / "surfer.log"
    try {
      val out = if (append) ProcessBuilder.Redirect.appendTo(viewerLog.toIO) else ProcessBuilder.Redirect.to(viewerLog.toIO)
      new ProcessBuilder(cmd: _*).directory(dir.toIO)
        .redirectOutput(out).redirectErrorStream(true).start()
        .getOutputStream.close() // empty stdin, never ours (the LSP channel)
      log(s"simulate: opened $trace with $surfer (log $viewerLog)")
      None
    } catch {
      case NonFatal(e) =>
        log(s"simulate: cannot start $surfer: $e")
        Some(s"cannot start $surfer: ${e.getMessage}")
    }
  }

  val DefaultTimeoutMs: Long = 30L * 60 * 1000

  private val random = new java.security.SecureRandom

  /** 128 random bits, in hex: a trace id nobody can guess. */
  private def newId(): String = {
    val b = new Array[Byte](16)
    random.nextBytes(b)
    b.map("%02x".format(_)).mkString
  }

  /** The viewer to open `trace` with, by `waveViewer`: `surfer` runs nothing
    * here (the language server starts it over WCP); `surfer-panel` runs nothing
    * (the panel shows it); `custom` runs `waveViewerCommand` split on
    * whitespace, each `{file}` replaced by the trace path, or the path appended
    * when no word has `{file}` (no shell); `<surferCommand> <trace>` when it is empty. */
  private[lsp] def viewerCommand(s: SimSettings, trace: os.Path): Option[Seq[String]] = s.waveViewer match {
    case "surfer" | "surfer-panel" => None
    case "custom" if s.waveViewerCommand.trim.nonEmpty =>
      val words = s.waveViewerCommand.trim.split("\\s+").toSeq
      Some(if (words.exists(_.contains("{file}"))) words.map(_.replace("{file}", trace.toString)) else words :+ trace.toString)
    case _ => Some(Seq(s.surferCommand, trace.toString))
  }

  private[lsp] def head(req: SimRequest, passed: Boolean): String =
    s"Simulation ${if (passed) "passed" else "failed"}: ${req.suite} ${req.test}."

  /** `<root>/out/socle/sim/<suite>/<test>`, each a [[segment]]. */
  def dirFor(root: os.Path, suite: String, test: String): os.Path =
    root / "out" / "socle" / "sim" / segment(suite) / segment(test)

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
