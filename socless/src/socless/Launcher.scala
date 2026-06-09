package socless

import chisel3.RawModule
import _root_.circt.stage.ChiselStage
import os.Path

/** Generic entrypoint the Mill plugin (and direct CLI users) invoke.
  *
  * Args: `--top <fully-qualified-class> [--output <path>] [--source-root <path>] [--chirrtl-out <path>]`
  *
  * Reflection-instantiates the named class via its no-argument constructor
  * and runs the exporter. Parameterized Chisel modules need a thin no-arg
  * wrapper:
  * {{{
  *   class MyTopForSocless extends MyTop(defaultParams)
  * }}}
  *
  * The plugin's `socless` task forks a `java -cp <runClasspath>` calling
  * this main, so the elaborated design is built against the user module's
  * exact classpath.
  *
  * `--chirrtl-out <path>`: when present, writes raw CHIRRTL text to `<path>`
  * and returns immediately (skips the normal design.json export). Used to
  * capture test fixtures without requiring Chisel at test time.
  */
object Launcher {

  val UsageLine: String =
    "usage: socless.Launcher --top <fqcn> [--output <path>] [--source-root <path>] [--chirrtl-out <path>]"

  /** Parsed representation of valid CLI arguments. */
  final case class LauncherConfig(
    top:        String,
    output:     Option[String],
    sourceRoot: Option[String],
    chirrtlOut: Option[String],
  )

  /** Pure argument parser - no reflection, no I/O. Returns Right(config) on
    * success or Left(errorMessage) on failure.
    *
    * Recognised flags:
    *   --top <fqcn>          (required)
    *   --output <path>
    *   --source-root <path>
    *   --chirrtl-out <path>
    *   --help / -h           (special: Left("help") signals caller to print usage and exit 0)
    */
  def parseArgs(args: Array[String]): Either[String, LauncherConfig] = {
    val known = Set("--top", "--output", "--source-root", "--chirrtl-out", "--help", "-h")

    var i          = 0
    var top        = Option.empty[String]
    var output     = Option.empty[String]
    var sourceRoot = Option.empty[String]
    var chirrtlOut = Option.empty[String]

    while (i < args.length) {
      args(i) match {
        case "--help" | "-h" =>
          return Left("help")

        case flag @ ("--top" | "--output" | "--source-root" | "--chirrtl-out") =>
          if (i + 1 >= args.length)
            return Left(s"flag $flag requires a value but none was given")
          val value = args(i + 1)
          if (known.contains(value))
            return Left(s"flag $flag requires a value but got another flag: $value")
          flag match {
            case "--top"          => top        = Some(value)
            case "--output"       => output     = Some(value)
            case "--source-root"  => sourceRoot = Some(value)
            case "--chirrtl-out"  => chirrtlOut = Some(value)
          }
          i += 2

        case unknown =>
          return Left(s"unknown flag: $unknown\n$UsageLine")
      }
    }

    top match {
      case None    => Left(s"required flag --top is missing\n$UsageLine")
      case Some(t) => Right(LauncherConfig(t, output, sourceRoot, chirrtlOut))
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

    val cls =
      try Class.forName(cfg.top)
      catch { case _: ClassNotFoundException =>
        Console.err.println(s"[socless] class not found: ${cfg.top}")
        Console.err.println("            check spelling + that it is on the runClasspath")
        sys.exit(1)
      }

    val ctor =
      try { val c = cls.getDeclaredConstructor(); c.setAccessible(true); c }
      catch { case _: NoSuchMethodException =>
        Console.err.println(s"[socless] ${cfg.top} has no accessible no-argument constructor.")
        Console.err.println( "            Parameterized Chisel modules must be wrapped:")
        Console.err.println(s"              class ${cls.getSimpleName}ForSocless extends ${cls.getSimpleName}(defaultParams)")
        Console.err.println( "            See: https://github.com/e-ban/SoCless#wrapping-parameterized-modules")
        sys.exit(1)
      }

    val gen: () => RawModule =
      try () => ctor.newInstance().asInstanceOf[RawModule]
      catch { case e: Exception =>
        Console.err.println(s"[socless] failed to instantiate ${cfg.top}: ${e.getMessage}")
        Console.err.println( "            ensure the class has an accessible no-argument constructor:")
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
