package socle.lsp

import scala.util.control.NonFatal

import socle.SocleExporter
import socle.capture.CapturedModel

/** One test to view: the source file it was asked from, its suite and full name. */
case class ViewRequest(file: os.Path, suite: String, test: String)

sealed trait RunResult
final case class Viewed(design: socle.schema.Design) extends RunResult
/** `status`: "no-design" | "elaboration-error" | "probe-failure" | "classpath". */
final case class Failed(status: String, message: String) extends RunResult

/** Runs one ChiselSim test in view mode (the probe run contract in
  * docs/how-it-works.md) and adapts what the probe captured to a laid-out
  * [[socle.schema.Design]].
  *
  * Each run forks `java -javaagent:<probe>` with the test module's fork
  * settings ([[TestFork]]): its JDK when it pins one (else `java`), its
  * classpath, its `forkArgs` as JVM options, this
  * process's environment plus its `env`, and its working directory when it has
  * one; otherwise (Mill's default test sandbox) a fresh temp directory, so
  * `build/chiselsim` never lands in the user's workspace. The probe's own
  * options come after the user's: on the JVM command line the last
  * `-Dname=value` wins, so `forkArgs` cannot turn the probe off or redirect
  * its output (a second `-javaagent` would load as well, ours still runs).
  * Output goes to a log file, never to this process's stdout (the LSP
  * channel). Only the probe's out file counts, never the exit code. A run
  * never throws: every failure is a [[Failed]].
  */
final class ViewRunner(probeJar: os.Path, sourceRoot: os.Path, java: String = ViewRunner.defaultJava, timeoutMs: Long = 120000) {

  /** Kept across views: unchanged files are not re-parsed. */
  private val sourceIndex = new ScalaSourceIndex(sourceRoot)

  def run(fork: TestFork, suite: String, test: String): RunResult = {
    val tmp = os.temp.dir(prefix = "socle-view-") // probe output and log, and the cwd unless the fork has one
    try {
      val out = tmp / "captured.json"
      val log = tmp / "run.log"
      val sub = os.proc(
        fork.java(java), fork.jvmArgs, s"-javaagent:$probeJar",
        "-Dsocle.probe.mode=view", s"-Dsocle.probe.out=$out",
        "-cp", fork.classpath.mkString(_root_.java.io.File.pathSeparator),
        "org.scalatest.tools.Runner", "-oW", "-s", suite, "-t", test,
      ).spawn(cwd = fork.workingDir.getOrElse(tmp), env = fork.env, stdin = "", stdout = log, mergeErrIntoOut = true)
      // stdout/stderr are a file redirect (no pumper thread), so once the
      // process has exited the log is complete.
      if (!ProcessTree.waitOrKill(sub.wrapped, timeoutMs))
        Failed("probe-failure", s"timed out after ${timeoutMs / 1000} s")
      else if (!os.exists(out)) Failed("probe-failure", tail(log))
      else captured(os.read(out), log)
    } catch {
      case NonFatal(e) => Failed("probe-failure", String.valueOf(e.getMessage))
    } finally os.remove.all(tmp)
  }

  private def captured(json: String, log: os.Path): RunResult =
    (try Right(CapturedModel.fromJson(json)) catch { case NonFatal(e) => Left(e) }) match {
      case Left(e) => Failed("probe-failure", s"unreadable probe output: ${e.getMessage}\n${tail(log)}")
      case Right(m) => m.status match {
        case "ok" =>
          sourceIndex.refresh() // pick up files added or removed since the last view
          val d = SocleExporter.designFromCaptured(m, Some(sourceRoot), Some(sourceIndex))
          if (d.modules.isEmpty)
            Failed("probe-failure", ("the probe captured no modules" +: m.problems).mkString("\n"))
          else Viewed(d) // m.problems are already in d.warnings (kind "probe-problem")
        case "no-design" =>
          Failed("no-design", if (m.error.nonEmpty) m.error else "the test never elaborated a design")
        case "elaboration-error" => Failed("elaboration-error", m.error)
        case other               => Failed("probe-failure", s"unknown probe status '$other'")
      }
    }

  /** The last lines of the forked JVM's output. */
  private def tail(log: os.Path): String =
    if (!os.exists(log)) "" else os.read.lines(log).takeRight(40).mkString("\n")
}

object ViewRunner {
  /** The server's own JVM (21+): forked test JVMs never depend on PATH. A
    * module that pins a JDK (`javaHome`) runs on that one instead. */
  val defaultJava: String = (os.Path(sys.props("java.home")) / "bin" / "java").toString

  private lazy val extracted: os.Path =
    extractTo(cacheDir(sys.env.get("XDG_CACHE_HOME"), os.home), () => getClass.getResourceAsStream("/socle-probe.jar"))

  /** The embedded probe agent, extracted on first use to a content-hashed file
    * in the per-user cache dir; later server starts reuse it. A failure is
    * rethrown on every call. */
  def extractProbeJar(): os.Path = extracted

  /** `$XDG_CACHE_HOME/socle`, or `~/.cache/socle` when it is unset, empty
    * or relative (the XDG Base Directory spec says to ignore relative paths).
    * Per-user on purpose: a shared temp dir would let another local user
    * plant the jar that runs as `-javaagent` in our test JVMs. */
  private[lsp] def cacheDir(xdgCacheHome: Option[String], home: os.Path): os.Path =
    xdgCacheHome.filter(p => p.nonEmpty && java.nio.file.Paths.get(p).isAbsolute)
      .map(os.Path(_)).getOrElse(home / ".cache") / "socle"

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

  /** Makes `dir` exist with mode 0700, refusing a directory owned by someone
    * else (its contents could have been planted). */
  private def privateDir(dir: os.Path): Unit = {
    import java.nio.file.attribute.PosixFilePermissions
    val owner = PosixFilePermissions.fromString("rwx------")
    if (!os.exists(dir)) os.makeDir.all(dir, perms = os.PermSet.fromSet(owner))
    val me = java.nio.file.Files.getOwner(os.home.toNIO)
    if (java.nio.file.Files.getOwner(dir.toNIO) != me)
      sys.error(s"refusing probe cache dir $dir: not owned by ${me.getName}")
    java.nio.file.Files.setPosixFilePermissions(dir.toNIO, owner)
  }

  /** Writes the jar from `open` to `dir/socle-probe-<sha256 prefix>.jar`
    * unless that file already holds exactly these bytes (full hash check, so
    * a same-size substitute is replaced); the write is atomic (temp file plus
    * rename), so concurrent servers never see a partial jar. */
  private[lsp] def extractTo(dir: os.Path, open: () => java.io.InputStream): os.Path = {
    val in = open()
    if (in == null) sys.error("socle-probe.jar resource missing from the socle-lsp jar")
    val bytes  = try in.readAllBytes() finally in.close()
    val digest = sha256(bytes)
    val target = dir / s"socle-probe-${digest.take(8).map("%02x".format(_)).mkString}.jar"
    privateDir(dir)
    if (!(os.isFile(target) && java.util.Arrays.equals(sha256(os.read.bytes(target)), digest))) {
      val part = os.temp(dir = dir, prefix = "socle-probe-", suffix = ".part", deleteOnExit = false)
      os.write.over(part, bytes)
      java.nio.file.Files.move(part.toNIO, target.toNIO,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    target
  }
}
