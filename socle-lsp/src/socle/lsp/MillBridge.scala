package socle.lsp

import scala.util.Try

/** How to fork the test JVM of a source file's test module, as `./mill <m>.test`
  * does: its run classpath, its `forkArgs`, its environment (Mill's
  * `allForkEnv`, or just `MILL_WORKSPACE_ROOT` when that is unavailable) and,
  * when the module turns Mill's test sandbox off, its `forkWorkingDir` (None:
  * a fresh directory, standing in for the sandbox). */
case class TestFork(
    classpath: Seq[os.Path],
    jvmArgs: Seq[String] = Nil,
    env: Map[String, String] = Map.empty,
    workingDir: Option[os.Path] = None,
)

/** Where the forked ChiselSim test JVM gets its classpath and fork settings for a source file. */
trait ClasspathProvider {
  def testClasspath(file: os.Path): Either[String, Seq[os.Path]]
  def testFork(file: os.Path): Either[String, TestFork] = testClasspath(file).map(TestFork(_))
}

/** Asks Mill (a separate process per call, output captured) which test module
  * owns a source file and what that module's run classpath is.
  *
  * Discovery runs `resolve __.test.sources` then `show` of those tasks and caches
  * the file-to-module map until a `*.mill` file under `root` changes. A failed
  * discovery is never cached: its Mill error is reported and the next call
  * retries. The classpath comes from `show <m>.runClasspath`, which compiles the
  * module as a side effect; [[testFork]] gets it together with the module's
  * fork settings in the same call. A module without those tasks (Mill answers
  * "Cannot resolve") falls back to the classpath alone, with a `log` line.
  *
  * Every Mill call is bounded by `timeoutMs` (10 minutes by default): past it
  * the Mill process tree is killed and the call reports a `Left`. A file with
  * no `build.mill` above it never spawns Mill.
  */
final class MillBridge(
    root: os.Path,
    millCmd: Seq[String],
    timeoutMs: Long = 600000,
    log: String => Unit = m => Console.err.println(s"[socle-lsp] $m"),
) extends ClasspathProvider {
  private case class Result(exit: Int, out: String, err: String) {
    /** The last 40 lines of Mill's output, without its `NNN-NNN] ` task tags. */
    def errorText: String =
      (err + out).linesIterator.map(_.replaceFirst("^[0-9][0-9-]*\\] ", ""))
        .toSeq.takeRight(40).mkString("\n")
  }

  private type SourceMap = Seq[(os.Path, String)] // source dir -> test module

  // (stamp of the *.mill files, map); only successful discoveries are kept
  private var cache: Option[(Seq[(os.Path, Long)], SourceMap)] = None
  private var lastStampCheck = 0L

  /** One Mill call. Output goes to files (a pipe read only after exit could
    * fill and block Mill on a large classpath); stdin is empty, never ours
    * (it is the LSP channel). */
  private def mill(args: String*): Either[String, Result] = {
    val dir = os.temp.dir(prefix = "socle-mill-")
    try {
      val (out, err) = (dir / "out", dir / "err")
      val sub = os.proc(millCmd ++ Seq("--color", "false", "--ticker", "false") ++ args)
        .spawn(cwd = root, stdin = "", stdout = out, stderr = err)
      if (!ProcessTree.waitOrKill(sub.wrapped, timeoutMs)) Left(s"Mill did not finish in ${timeoutMs / 1000} s")
      else Right(Result(sub.wrapped.exitValue, os.read(out), os.read(err)))
    } finally os.remove.all(dir)
  }

  private val skipDirs = Set("out", "node_modules", "target")
  private def buildStamp(): Seq[(os.Path, Long)] =
    os.walk(root, skip = p => skipDirs(p.last) || p.last.startsWith("."))
      .filter(p => p.ext == "mill" && os.isFile(p))
      .map(p => p -> os.mtime(p))
      .sorted

  /** `ref:v0:<hash>:<path>` / `qref:v1:<hash>:<path>` (a Mill PathRef) to a path. */
  private def refPath(s: String): os.Path = os.Path(s.replaceFirst("^q?ref:v\\d+:[0-9a-f]+:", ""))

  private def discover(): Either[String, SourceMap] = mill("resolve", "__.test.sources").flatMap { resolved =>
    if (resolved.exit != 0)
      Left(
        if (resolved.errorText.contains("Cannot resolve __.test.sources")) s"no Mill test module found in $root"
        else resolved.errorText)
    else discover(resolved)
  }

  private def discover(resolved: Result): Either[String, SourceMap] = {
    val modules = resolved.out.linesIterator.map(_.trim).filter(_.nonEmpty)
      .map(_.stripSuffix(".sources")).toSeq
    if (modules.isEmpty) return Right(Nil)
    val target =
      if (modules.size == 1) s"${modules.head}.sources"
      else modules.mkString("{", ",", "}") + ".sources"
    val shown = mill("show", target) match {
      case Left(timedOut) => return Left(timedOut)
      case Right(r)       => r
    }
    if (shown.exit != 0) return Left(shown.errorText)
    Try {
      // One task prints a bare array; several print an object keyed by task.
      val byModule: Seq[(String, ujson.Value)] = ujson.read(shown.out) match {
        case obj: ujson.Obj => obj.value.toSeq.map { case (k, v) => k.stripSuffix(".sources") -> v }
        case arr            => Seq(modules.head -> arr)
      }
      for ((m, dirs) <- byModule; d <- dirs.arr.toSeq) yield refPath(d.str) -> m
    }.toEither.left.map(e => s"cannot parse Mill sources output: ${e.getMessage}")
  }

  private def sourceMap(): Either[String, SourceMap] = synchronized {
    val now = System.currentTimeMillis()
    cache match {
      case Some((_, map)) if now - lastStampCheck < 1000 => Right(map)
      case _ =>
        lastStampCheck = now
        val stamp = buildStamp()
        cache match {
          case Some((s, map)) if s == stamp => Right(map)
          case _ =>
            val found = discover()
            cache = found.toOption.map(stamp -> _)
            found
        }
    }
  }

  private def moduleFor(file: os.Path): Either[String, Option[String]] =
    if (!file.startsWith(root)) Right(None)
    else sourceMap().map { map =>
      val owners = map.filter { case (dir, _) => file.startsWith(dir) }
      if (owners.isEmpty) None else Some(owners.maxBy(_._1.segmentCount)._2)
    }

  def testModuleFor(file: os.Path): Option[String] = moduleFor(file).toOption.flatten

  private def testModule(file: os.Path): Either[String, String] =
    if (MillBridge.findRoot(file).isEmpty) Left(s"no build.mill found for $file")
    else moduleFor(file).flatMap(_.toRight(s"not in a Mill test module: $file"))

  private def parseClasspath(v: ujson.Value): Seq[os.Path] = v.arr.toSeq.map(c => refPath(c.str))

  private def runClasspath(m: String): Either[String, Seq[os.Path]] =
    mill("show", s"$m.runClasspath").flatMap { r =>
      if (r.exit != 0) Left(r.errorText)
      else Try(parseClasspath(ujson.read(r.out))).toEither
        .left.map(e => s"cannot parse Mill classpath output: ${e.getMessage}")
    }

  def testClasspath(file: os.Path): Either[String, Seq[os.Path]] = testModule(file).flatMap(runClasspath)

  /** One `show` of the run classpath and the fork settings Mill's test runner
    * uses (Mill 1.1: `forkArgs` array, `allForkEnv` object, `forkWorkingDir`
    * path, `testSandboxWorkingDir` boolean; several tasks print an object keyed
    * by task). `allForkEnv` is the env Mill gives the test JVM, as is: `forkEnv`
    * plus `MILL_WORKSPACE_ROOT` (Mill's value wins), `MILL_TEST_RESOURCE_DIR`
    * and `PATH` with the module's JDK first. Only Mill's "Cannot resolve <m>."
    * (a module lacking one of these tasks) falls back to the classpath alone;
    * any other failure (a compile error, a timeout) is reported, never retried. */
  override def testFork(file: os.Path): Either[String, TestFork] = testModule(file).flatMap { m =>
    val tasks = Seq("runClasspath", "forkArgs", "allForkEnv", "forkWorkingDir", "testSandboxWorkingDir")
    mill("show", tasks.mkString(s"$m.{", ",", "}")).flatMap { r =>
      if (r.exit == 0)
        Try {
          val shown = ujson.read(r.out).obj
          def task(name: String) = shown(s"$m.$name")
          TestFork(
            parseClasspath(task("runClasspath")),
            task("forkArgs").arr.toSeq.map(_.str),
            task("allForkEnv").obj.map { case (k, v) => k -> v.str }.toMap,
            if (task("testSandboxWorkingDir").bool) None else Some(refPath(task("forkWorkingDir").str)),
          )
        }.toEither.left.map(e => s"cannot parse Mill fork settings output: ${e.getMessage}")
      else if (r.errorText.linesIterator.exists(_.contains(s"Cannot resolve $m."))) {
        val workspace = Map("MILL_WORKSPACE_ROOT" -> root.toString)
        log(s"$m: fork settings unavailable, using the classpath only (${r.errorText.linesIterator.mkString(" ").trim})")
        runClasspath(m).map(TestFork(_, env = workspace))
      } else Left(r.errorText)
    }
  }
}

object MillBridge {
  /** The nearest ancestor of `from` (or `from` itself) holding a `build.mill`. */
  def findRoot(from: os.Path): Option[os.Path] =
    Iterator.iterate(from)(_ / os.up).takeWhile(_ != os.root)
      .find(d => os.isFile(d / "build.mill"))

  /** Uses the project's `./mill` launcher when present, else `mill` on PATH. */
  def default(root: os.Path, log: String => Unit = m => Console.err.println(s"[socle-lsp] $m")): MillBridge = {
    val launcher = root / "mill"
    val exe = if (os.isFile(launcher)) launcher.toString else "mill"
    new MillBridge(root, Seq(exe, "--no-daemon"), log = log)
  }
}
