package socle.cli

import socle._
import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import chisel3.RawModule
import _root_.circt.stage.ChiselStage
import os.Path

/** Generic entrypoint the Mill plugin (and direct CLI users) invoke.
  *
  * Args (Chisel mode):
  *   `--top <fqcn> [--output <path>] [--source-root <path>] [--chirrtl-out <path>]`
  *
  * Args (CHIRRTL file mode):
  *   `--fir-input <path> [--output <path>]`
  *
  * In Chisel mode the launcher reflection-resolves `--top` and runs Chisel
  * elaboration.  In CHIRRTL mode it reads a `.fir` file directly and pipes it
  * through `ChirrtlParser` + `IrWalk` (no Chisel on the classpath is
  * required).
  *
  * `--top` accepts three forms (issue #116), tried in this order:
  * {{{
  *   mypkg.MyTop          // class with a no-arg constructor
  *   mypkg.MyGen          // Scala object with a zero-arg apply() or build()
  *   mypkg.Gens.smallSoC  // zero-arg factory method on a Scala object
  * }}}
  * Generator objects are the natural form for parameterized designs - the
  * object can close over any config or implicits it needs:
  * {{{
  *   object MyTopForSocle { def apply() = new MyTop(defaultParams) }
  * }}}
  *
  * The plugin's `socle` task forks a `java -cp <runClasspath>` calling
  * this main, so the elaborated design is built against the user module's
  * exact run classpath.
  *
  * `--chirrtl-out <path>`: when present, writes raw CHIRRTL text to `<path>`
  * and returns immediately (skips the normal design.json export). Used to
  * capture test fixtures without requiring Chisel at test time.
  */
object Launcher {

  val UsageLine: String =
    "usage: socle.cli.Launcher (--top <fqcn> | --fir-input <path>) [--output <path>] [--source-root <path>] [--chirrtl-out <path>] [--strict]\n" +
    "       socle.cli.Launcher serve [--port N] [--host <addr>] [--dir <dir>] [--watch <file.fir>] [--source-root <dir>] [--no-open]   # standalone UI server (no Mill/Python)"

  /** Fuller help printed for `--help`/`-h`; errors print the terse [[UsageLine]].
    * Spells out the four modes one main supports and explains every flag, since
    * they are otherwise only discoverable by reading the source (issue #157).
    * The serve flags are shared with `serve --help` via [[Serve.FlagsHelp]]. */
  val HelpText: String =
    s"""socle.cli.Launcher - export a Chisel/CHIRRTL design to design.json, or serve the UI.
      |
      |Modes (pick one):
      |  elaborate   --top <fqcn> ...        Elaborate a Chisel design (needs Chisel on the classpath).
      |  convert     --fir-input <path> ...  Convert a CHIRRTL .fir file (no Chisel needed).
      |  dump        --top <fqcn> --chirrtl-out <path>   Emit raw CHIRRTL and stop.
      |  serve       serve [server options]  Standalone loopback UI server (no Mill, no Python).
      |  debug-layout <design.json> ...     Render baked layout geometry to SVG + text (no UI).
      |  from-capture <captured.json> [--output <path>] [--source-root <dir>]
      |                                     Adapt a socle-probe capture to design.json.
      |
      |Export flags (elaborate / convert / dump):
      |  --top <fqcn>         design to elaborate: a no-arg class, an object's apply()/build(),
      |                       or a zero-arg factory method (mypkg.Gens.smallSoC)
      |  --fir-input <path>   convert this CHIRRTL .fir instead of elaborating (no Chisel needed)
      |  --output <path>      design.json destination (default: ./design.json)
      |  --source-root <dir>  embed source text by resolving the @[file ...] markers, so the
      |                       UI's Source tab shows code instead of a bare file:line locator
      |  --chirrtl-out <path> with --top: write raw CHIRRTL to <path> and stop (capture a .fir)
      |  --strict             exit nonzero (3) if the export produced warnings
      |
      |Server flags (serve):
      |${Serve.FlagsHelp}
      |
      |  --help, -h           this message""".stripMargin

  /** Parsed representation of valid CLI arguments. */
  final case class LauncherConfig(
    top:        Option[String],
    firInput:   Option[String],
    output:     Option[String],
    sourceRoot: Option[String],
    chirrtlOut: Option[String],
    strict:     Boolean = false,
  )

  /** How a resolved `--top` produces a design instance (issue #116). */
  sealed trait GenSpec {
    /** Construct the design. Must be called inside a Chisel builder context
      * (i.e. from within emitCHIRRTL's generator thunk). */
    def instantiate(): AnyRef
    /** Human-readable form for error messages. */
    def describe: String
  }
  object GenSpec {
    final case class Ctor(ctor: java.lang.reflect.Constructor[_]) extends GenSpec {
      def instantiate(): AnyRef = ctor.newInstance().asInstanceOf[AnyRef]
      def describe: String = "no-arg constructor"
    }
    final case class ObjectMethod(instance: AnyRef, method: java.lang.reflect.Method) extends GenSpec {
      def instantiate(): AnyRef = method.invoke(instance)
      def describe: String = s"object method ${method.getName}()"
    }
  }

  private def loadClass(name: String): Option[Class[_]] =
    try Some(Class.forName(name))
    catch { case _: ClassNotFoundException | _: NoClassDefFoundError => None }

  /** The singleton instance of a Scala `object` with the given FQCN, if any. */
  private def objectInstance(name: String): Option[AnyRef] =
    loadClass(name + "$").flatMap { c =>
      try Option(c.getField("MODULE$").get(null))
      catch { case _: ReflectiveOperationException => None }
    }

  private def zeroArgMethod(target: AnyRef, names: Seq[String]): Option[java.lang.reflect.Method] =
    names.iterator.flatMap { n =>
      try {
        val m = target.getClass.getMethod(n)
        if (m.getParameterCount == 0) Some(m) else None
      } catch { case _: NoSuchMethodException => None }
    }.take(1).toSeq.headOption

  /** Resolve `--top` into a generator. Accepted forms, tried in order
    * (issue #116):
    *
    *   1. `mypkg.MyTop`         - class with an accessible no-arg constructor
    *   2. `mypkg.MyGen`         - Scala object with a zero-arg apply()/build()
    *   3. `mypkg.Gens.smallSoC` - zero-arg factory method on a Scala object
    *
    * Pure resolution (no instantiation), so it is unit-testable; the actual
    * construction happens inside the elaboration thunk.
    */
  private[socle] def resolveTop(top: String): Either[String, GenSpec] = {
    val asClass = loadClass(top)
    val viaCtor = asClass.flatMap { c =>
      try { val k = c.getDeclaredConstructor(); k.setAccessible(true); Some(GenSpec.Ctor(k): GenSpec) }
      catch { case _: NoSuchMethodException => None }
    }
    def viaObject = objectInstance(top).flatMap { o =>
      zeroArgMethod(o, Seq("apply", "build")).map(m => GenSpec.ObjectMethod(o, m): GenSpec)
    }
    def viaFactory = {
      val i = top.lastIndexOf('.')
      if (i <= 0) None
      else objectInstance(top.substring(0, i)).flatMap { o =>
        zeroArgMethod(o, Seq(top.substring(i + 1))).map(m => GenSpec.ObjectMethod(o, m): GenSpec)
      }
    }
    viaCtor.orElse(viaObject).orElse(viaFactory).toRight {
      val reason =
        if (asClass.isDefined) s"[socle] $top exists but has no accessible no-argument constructor."
        else s"[socle] $top was not found on the classpath (check spelling + that it is on the runClasspath)."
      reason + "\n" +
      "            --top accepts any of:\n" +
      "              mypkg.MyTop          (class with a no-arg constructor)\n" +
      "              mypkg.MyGen          (object with a zero-arg apply() or build())\n" +
      "              mypkg.Gens.smallSoC  (zero-arg factory method on an object)\n" +
      "            For parameterized designs, a generator object is the natural wrapper:\n" +
      "              object MyTopForSocle { def apply() = new MyTop(defaultParams) }"
    }
  }

  /** Pure argument parser - no reflection, no I/O. Returns Right(config) on
    * success or Left(errorMessage) on failure.
    *
    * Recognised flags:
    *   --top <fqcn>          (required unless --fir-input is given)
    *   --fir-input <path>    (required unless --top is given; CHIRRTL file mode)
    *   --output <path>
    *   --source-root <path>
    *   --chirrtl-out <path>
    *   --help / -h           (special: Left("help") signals caller to print usage and exit 0)
    */
  def parseArgs(args: Array[String]): Either[String, LauncherConfig] = {
    val known = Set("--top", "--fir-input", "--output", "--source-root", "--chirrtl-out", "--strict", "--help", "-h")

    var i          = 0
    var top        = Option.empty[String]
    var firInput   = Option.empty[String]
    var output     = Option.empty[String]
    var sourceRoot = Option.empty[String]
    var chirrtlOut = Option.empty[String]
    var strict     = false

    while (i < args.length) {
      args(i) match {
        case "--help" | "-h" =>
          return Left("help")

        case "--strict" =>
          strict = true
          i += 1

        case flag @ ("--top" | "--fir-input" | "--output" | "--source-root" | "--chirrtl-out") =>
          if (i + 1 >= args.length)
            return Left(s"flag $flag requires a value but none was given")
          val value = args(i + 1)
          if (known.contains(value))
            return Left(s"flag $flag requires a value but got another flag: $value")
          flag match {
            case "--top"          => top        = Some(value)
            case "--fir-input"    => firInput   = Some(value)
            case "--output"       => output     = Some(value)
            case "--source-root"  => sourceRoot = Some(value)
            case "--chirrtl-out"  => chirrtlOut = Some(value)
          }
          i += 2

        case unknown =>
          return Left(s"unknown flag: $unknown\n$UsageLine")
      }
    }

    (top, firInput) match {
      case (None, None) =>
        Left(s"one of --top or --fir-input is required\n$UsageLine")
      case (Some(_), Some(_)) =>
        Left(s"--top and --fir-input are mutually exclusive\n$UsageLine")
      case _ =>
        Right(LauncherConfig(top, firInput, output, sourceRoot, chirrtlOut, strict))
    }
  }

  /** Exit nonzero under --strict when the emitted design carried warnings, for
    * CI that wants a clean export to be a hard gate (issue #118). */
  private def enforceStrict(cfg: LauncherConfig, design: Design): Unit =
    if (cfg.strict && design.warnings.nonEmpty) {
      Console.err.println(s"[socle] --strict: ${design.warnings.size} warning kind(s); failing.")
      design.warnings.foreach(w => Console.err.println(s"            [${w.kind}] ${w.message}"))
      sys.exit(3)
    }

  /** `debug-layout <design.json> [--out DIR] [--module NAME] [--format svg|text|both]`
    * Render the baked layout geometry of an already-emitted design.json to
    * per-module debug SVG + text, with no UI. Returns a process exit code.
    * Free of sys.exit so tests can call it directly. */
  private[socle] def runDebugLayout(args: Array[String]): Int = {
    if (args.headOption.exists(a => a == "--help" || a == "-h")) {
      println("usage: socle.cli.Launcher debug-layout <design.json> " +
              "[--out DIR] [--module NAME] [--format svg|text|both] [--max-px N]")
      return 0
    }
    var json    = Option.empty[String]
    var out     = Option.empty[String]
    var module  = Option.empty[String]
    var format  = "both"
    var maxPxStr = Option.empty[String]
    var i = 0
    var err = 0
    while (i < args.length && err == 0) {
      args(i) match {
        case f @ ("--out" | "--module" | "--format" | "--max-px") =>
          if (i + 1 >= args.length || args(i + 1).startsWith("-")) {
            Console.err.println(s"[socle] debug-layout: $f requires a value"); err = 2
          } else {
            val v = args(i + 1)
            f match {
              case "--out"    => out      = Some(v)
              case "--module" => module   = Some(v)
              case "--format" => format   = v
              case "--max-px" => maxPxStr = Some(v)
            }
            i += 2
          }
        case a if !a.startsWith("-") && json.isEmpty => json = Some(a); i += 1
        case a => Console.err.println(s"[socle] debug-layout: unrecognized argument '$a'"); err = 2
      }
    }
    if (err != 0) return err
    val jsonStr = json.getOrElse {
      Console.err.println("[socle] debug-layout: a <design.json> path is required"); return 2
    }
    if (!Set("svg", "text", "both").contains(format)) {
      Console.err.println(s"[socle] debug-layout: --format must be svg|text|both (got '$format')"); return 2
    }
    val maxPx = maxPxStr match {
      case None    => 0
      case Some(s) => s.toIntOption.filter(_ >= 0).getOrElse {
        Console.err.println(s"[socle] debug-layout: --max-px must be a non-negative integer (got '$s')"); return 2
      }
    }
    val jsonPath = Path(jsonStr, os.pwd)
    if (!os.exists(jsonPath)) { Console.err.println(s"[socle] debug-layout: file not found: $jsonPath"); return 1 }
    val design =
      try upickle.default.read[Design](os.read(jsonPath))
      catch { case e: Exception =>
        Console.err.println(s"[socle] debug-layout: could not parse $jsonPath: ${e.getMessage}"); return 1
      }
    if (design.layouts.isEmpty) {
      Console.err.println(s"[socle] debug-layout: $jsonPath has no baked layouts " +
                          "(re-export with a current socle)"); return 1
    }
    module match {
      case Some(mn) if !design.modules.exists(_.name == mn) =>
        Console.err.println(s"[socle] debug-layout: no module '$mn'. Available: " +
                            design.modules.map(_.name).mkString(", "))
        return 2
      case _ =>
    }
    val outDir  = out.map(Path(_, os.pwd)).getOrElse(jsonPath / os.up / "layout-debug")
    val written = LayoutDebug.dump(design, outDir, module, format, maxPx)
    if (module.isDefined && written.isEmpty) {
      Console.err.println(s"[socle] debug-layout: module '${module.get}' has no baked layout; nothing written")
      return 1
    }
    Console.err.println(s"[socle] debug-layout: wrote ${written.size} file(s) to $outDir")
    0
  }

  /** `from-capture <captured.json> [--output <path>] [--source-root <dir>]`:
    * adapt a socle-probe capture to design.json. Returns the exit code. */
  def runFromCapture(args: Array[String]): Int = {
    def usage(msg: String): Int = {
      Console.err.println(s"[socle] from-capture: $msg (usage: from-capture <captured.json> [--output <path>] [--source-root <dir>])")
      2
    }
    var in: Option[String]   = None
    var out                  = "design.json"
    var root: Option[String] = None
    val it = args.iterator
    while (it.hasNext) it.next() match {
      case "--output" if it.hasNext                 => out = it.next()
      case "--source-root" if it.hasNext            => root = Some(it.next())
      case a if !a.startsWith("--") && in.isEmpty   => in = Some(a)
      case a                                        => return usage(s"unrecognized argument '$a'")
    }
    if (in.isEmpty) return usage("a <captured.json> path is required")
    try {
      SocleExporter.fromCaptured(os.read(Path(in.get, os.pwd)), Path(out, os.pwd), root.map(Path(_, os.pwd)))
      0
    } catch {
      case e: IllegalArgumentException =>
        Console.err.println(s"[socle] from-capture: ${e.getMessage}")
        1
    }
  }

  def main(args: Array[String]): Unit = {
    // `serve` subcommand: a standalone, Mill/Python-free UI server (issue
    // #115). Anything else is the flag-based elaborate / convert path.
    if (args.headOption.contains("serve")) { Serve.run(args.tail); return }
    if (args.headOption.contains("debug-layout")) sys.exit(runDebugLayout(args.tail))
    if (args.headOption.contains("from-capture")) sys.exit(runFromCapture(args.tail))
    parseArgs(args) match {
      case Left("help") =>
        println(HelpText)
        sys.exit(0)

      case Left(err) =>
        Console.err.println(s"[socle] $err")
        sys.exit(2)

      case Right(cfg) =>
        run(cfg)
    }
  }

  private def run(cfg: LauncherConfig): Unit = {
    val output     = cfg.output.map(Path(_, os.pwd)).getOrElse(os.pwd / "design.json")
    val sourceRoot = cfg.sourceRoot.map(Path(_, os.pwd))
    val chirrtlOut = cfg.chirrtlOut.map(Path(_, os.pwd))

    cfg.firInput match {
      case Some(firPath) =>
        // CHIRRTL file mode: parse .fir directly, no Chisel elaboration needed.
        val firFile = Path(firPath, os.pwd)
        if (!os.exists(firFile))
          { Console.err.println(s"[socle] file not found: $firFile"); sys.exit(1) }
        val chirrtl = os.read(firFile)
        // --source-root embeds source text from the .fir's @[file …] markers,
        // same as the Chisel path (issue #154).
        enforceStrict(cfg, SocleExporter.fromChirrtl(chirrtl, output, sourceRoot = sourceRoot))

      case None =>
        val top = cfg.top.get   // safe: parseArgs guarantees one of the two is set

        val spec = resolveTop(top) match {
          case Right(s) => s
          case Left(msg) =>
            Console.err.println(msg)
            sys.exit(1)
        }

        // The catch must live INSIDE the thunk: instantiation only runs later,
        // during Chisel elaboration (emitCHIRRTL calls gen() in a builder
        // context; Chisel modules cannot be constructed outside one). A try
        // around the lambda creation alone can never fire.
        val gen: () => RawModule = () =>
          try spec.instantiate().asInstanceOf[RawModule]
          catch {
            case e: java.lang.reflect.InvocationTargetException =>
              val cause = Option(e.getCause).getOrElse(e)
              Console.err.println(s"[socle] failed to instantiate $top (${spec.describe}): $cause")
              sys.exit(1)
            case _: ClassCastException =>
              Console.err.println(s"[socle] $top (${spec.describe}) did not return a chisel3.RawModule")
              sys.exit(1)
          }

        chirrtlOut match {
          case Some(dest) =>
            val chirrtl = ChiselStage.emitCHIRRTL(gen(), args = Array("--chisel-output-file", (os.temp.dir() / "chirrtl-dump.fir").toString))
            os.write.over(dest, chirrtl, createFolders = true)
            Console.err.println(s"[socle] CHIRRTL written to $dest (${chirrtl.length} chars)")
          case None =>
            enforceStrict(cfg, SocleExporter.export(gen, output, sourceRoot = sourceRoot))
        }
    }
  }
}
