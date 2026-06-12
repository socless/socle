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
  * In Chisel mode the launcher reflection-instantiates the named class and
  * runs Chisel elaboration.  In CHIRRTL mode it reads a `.fir` file directly
  * and pipes it through `ChirrtlParser` + `IrWalk` (no Chisel on the
  * classpath is required).
  *
  * Parameterized Chisel modules need a thin no-arg wrapper:
  * {{{
  *   class MyTopForSocless extends MyTop(defaultParams)
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
    "usage: socless.Launcher (--top <fqcn> | --fir-input <path>) [--output <path>] [--source-root <path>] [--chirrtl-out <path>]"

  /** Parsed representation of valid CLI arguments. */
  final case class LauncherConfig(
    top:        Option[String],
    firInput:   Option[String],
    output:     Option[String],
    sourceRoot: Option[String],
    chirrtlOut: Option[String],
  )

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
    val known = Set("--top", "--fir-input", "--output", "--source-root", "--chirrtl-out", "--help", "-h")

    var i          = 0
    var top        = Option.empty[String]
    var firInput   = Option.empty[String]
    var output     = Option.empty[String]
    var sourceRoot = Option.empty[String]
    var chirrtlOut = Option.empty[String]

    while (i < args.length) {
      args(i) match {
        case "--help" | "-h" =>
          return Left("help")

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
        Right(LauncherConfig(top, firInput, output, sourceRoot, chirrtlOut))
    }
  }

  def main(args: Array[String]): Unit = {
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
        SoclessExporter.fromChirrtl(chirrtl, output)

      case None =>
        val top = cfg.top.get   // safe: parseArgs guarantees one of the two is set

        val cls =
          try Class.forName(top)
          catch { case _: ClassNotFoundException =>
            Console.err.println(s"[socless] class not found: $top")
            Console.err.println("            check spelling + that it is on the runClasspath")
            sys.exit(1)
          }

        val ctor =
          try { val c = cls.getDeclaredConstructor(); c.setAccessible(true); c }
          catch { case _: NoSuchMethodException =>
            Console.err.println(s"[socless] $top has no accessible no-argument constructor.")
            Console.err.println( "            Parameterized Chisel modules must be wrapped:")
            Console.err.println(s"              class ${cls.getSimpleName}ForSocless extends ${cls.getSimpleName}(defaultParams)")
            Console.err.println( "            See: https://github.com/e-ban/SoCless#wrapping-parameterized-modules")
            sys.exit(1)
          }

        // The catch must live INSIDE the thunk: newInstance() only runs later,
        // during Chisel elaboration (emitCHIRRTL calls gen() in a builder
        // context; Chisel modules cannot be constructed outside one). A try
        // around the lambda creation alone can never fire.
        val gen: () => RawModule = () =>
          try ctor.newInstance().asInstanceOf[RawModule]
          catch { case e: java.lang.reflect.InvocationTargetException =>
            val cause = Option(e.getCause).getOrElse(e)
            Console.err.println(s"[socless] failed to instantiate $top: $cause")
            Console.err.println( "            if the constructor needs parameters, wrap it:")
            Console.err.println(s"              class ${cls.getSimpleName}ForSocless extends ${cls.getSimpleName}(defaultParams)")
            sys.exit(1)
          }

        chirrtlOut match {
          case Some(dest) =>
            val chirrtl = ChiselStage.emitCHIRRTL(gen(), args = Array("--chisel-output-file", (os.temp.dir() / "chirrtl-dump.fir").toString))
            os.write.over(dest, chirrtl, createFolders = true)
            Console.err.println(s"[socless] CHIRRTL written to $dest (${chirrtl.length} chars)")
          case None =>
            SoclessExporter.export(gen, output, sourceRoot = sourceRoot)
        }
    }
  }
}
