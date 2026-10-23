package socless.lsp

import scala.util.Try

/** Where the forked ChiselSim test JVM gets its classpath for a source file. */
trait ClasspathProvider {
  def testClasspath(file: os.Path): Either[String, Seq[os.Path]]
}

/** Asks Mill (a separate process per call, output captured) which test module
  * owns a source file and what that module's run classpath is.
  *
  * Discovery runs `resolve __.test.sources` then `show` of those tasks and caches
  * the file-to-module map until a `*.mill` file under `root` changes. A failed
  * discovery is never cached: its Mill error is reported and the next call
  * retries. The classpath comes from `show <m>.runClasspath`, which compiles the
  * module as a side effect.
  *
  * Every Mill call is bounded by `timeoutMs` (10 minutes by default): past it
  * the Mill process tree is killed and the call reports a `Left`. A file with
  * no `build.mill` above it never spawns Mill.
  */
final class MillBridge(root: os.Path, millCmd: Seq[String], timeoutMs: Long = 600000) extends ClasspathProvider {
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
    val dir = os.temp.dir(prefix = "socless-mill-")
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

  def testClasspath(file: os.Path): Either[String, Seq[os.Path]] =
    if (MillBridge.findRoot(file).isEmpty) Left(s"no build.mill found for $file")
    else moduleFor(file).flatMap {
      case None => Left(s"not in a Mill test module: $file")
      case Some(m) =>
        mill("show", s"$m.runClasspath").flatMap { r =>
          if (r.exit != 0) Left(r.errorText)
          else Try(ujson.read(r.out).arr.toSeq.map(v => refPath(v.str))).toEither
            .left.map(e => s"cannot parse Mill classpath output: ${e.getMessage}")
        }
    }
}

object MillBridge {
  /** The nearest ancestor of `from` (or `from` itself) holding a `build.mill`. */
  def findRoot(from: os.Path): Option[os.Path] =
    Iterator.iterate(from)(_ / os.up).takeWhile(_ != os.root)
      .find(d => os.isFile(d / "build.mill"))

  /** Uses the project's `./mill` launcher when present, else `mill` on PATH. */
  def default(root: os.Path): MillBridge = {
    val launcher = root / "mill"
    val exe = if (os.isFile(launcher)) launcher.toString else "mill"
    new MillBridge(root, Seq(exe, "--no-daemon"))
  }
}
