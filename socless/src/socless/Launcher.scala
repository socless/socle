package socless

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
  *   object MyTopForSocless { def apply() = new MyTop(defaultParams) }
  * }}}
  *
  * The plugin's `socless` task forks a `java -cp <runClasspath>` calling
  * this main, so the elaborated design is built against the user module's
  * exact run classpath.
  *
  * `--chirrtl-out <path>`: when present, writes raw CHIRRTL text to `<path>`
  * and returns immediately (skips the normal design.json export). Used to
  * capture test fixtures without requiring Chisel at test time.
  */
object Launcher {

  val UsageLine: String =
    "usage: socless.Launcher (--top <fqcn> | --fir-input <path>) [--output <path>] [--source-root <path>] [--chirrtl-out <path>] [--strict]\n" +
    "       socless.Launcher serve [--port N] [--dir <dir>] [--watch <file.fir>] [--source-root <dir>] [--no-open]   # standalone UI server (no Mill/Python)"

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
  private[socless] def resolveTop(top: String): Either[String, GenSpec] = {
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
        if (asClass.isDefined) s"[socless] $top exists but has no accessible no-argument constructor."
        else s"[socless] $top was not found on the classpath (check spelling + that it is on the runClasspath)."
      reason + "\n" +
      "            --top accepts any of:\n" +
      "              mypkg.MyTop          (class with a no-arg constructor)\n" +
      "              mypkg.MyGen          (object with a zero-arg apply() or build())\n" +
      "              mypkg.Gens.smallSoC  (zero-arg factory method on an object)\n" +
      "            For parameterized designs, a generator object is the natural wrapper:\n" +
      "              object MyTopForSocless { def apply() = new MyTop(defaultParams) }"
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
      Console.err.println(s"[socless] --strict: ${design.warnings.size} warning kind(s); failing.")
      design.warnings.foreach(w => Console.err.println(s"            [${w.kind}] ${w.message}"))
      sys.exit(3)
    }

  def main(args: Array[String]): Unit = {
    // `serve` subcommand: a standalone, Mill/Python-free UI server (issue
    // #115). Anything else is the flag-based elaborate / convert path.
    if (args.headOption.contains("serve")) { Serve.run(args.tail); return }
    parseArgs(args) match {
      case Left("help") =>
        println(UsageLine)
        sys.exit(0)

      case Left(err) =>
        Console.err.println(s"[socless] $err")
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
          { Console.err.println(s"[socless] file not found: $firFile"); sys.exit(1) }
        val chirrtl = os.read(firFile)
        // --source-root embeds source text from the .fir's @[file …] markers,
        // same as the Chisel path (issue #154).
        enforceStrict(cfg, SoclessExporter.fromChirrtl(chirrtl, output, sourceRoot = sourceRoot))

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
              Console.err.println(s"[socless] failed to instantiate $top (${spec.describe}): $cause")
              sys.exit(1)
            case _: ClassCastException =>
              Console.err.println(s"[socless] $top (${spec.describe}) did not return a chisel3.RawModule")
              sys.exit(1)
          }

        chirrtlOut match {
          case Some(dest) =>
            val chirrtl = ChiselStage.emitCHIRRTL(gen(), args = Array("--chisel-output-file", (os.temp.dir() / "chirrtl-dump.fir").toString))
            os.write.over(dest, chirrtl, createFolders = true)
            Console.err.println(s"[socless] CHIRRTL written to $dest (${chirrtl.length} chars)")
          case None =>
            enforceStrict(cfg, SoclessExporter.export(gen, output, sourceRoot = sourceRoot))
        }
    }
  }
}
