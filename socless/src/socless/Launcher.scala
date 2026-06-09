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

  def main(args: Array[String]): Unit = {
    val top = arg(args, "--top").getOrElse {
      Console.err.println("usage: socless.Launcher --top <fqcn> [--output <path>] [--source-root <path>] [--chirrtl-out <path>]")
      sys.exit(2)
    }
    val output      = arg(args, "--output").map(Path(_, os.pwd)).getOrElse(os.pwd / "design.json")
    // `--source-root <dir>` enables source-text embedding in the design.json
    // (Mill plugin passes this; direct CLI users can omit to skip).
    val sourceRoot  = arg(args, "--source-root").map(Path(_, os.pwd))
    // `--chirrtl-out <path>` dumps raw CHIRRTL and exits; no design.json is written.
    val chirrtlOut  = arg(args, "--chirrtl-out").map(Path(_, os.pwd))

    val cls =
      try Class.forName(top)
      catch { case _: ClassNotFoundException =>
        Console.err.println(s"[socless] class not found: $top")
        Console.err.println("            check spelling + that it's on the runClasspath")
        sys.exit(1)
      }

    val ctor =
      try { val c = cls.getDeclaredConstructor(); c.setAccessible(true); c }
      catch { case _: NoSuchMethodException =>
        Console.err.println(s"[socless] $top has no no-argument constructor.")
        Console.err.println("            wrap it: `class TopForSocless extends YourTop(defaults)`")
        sys.exit(1)
      }

    val gen: () => RawModule = () => ctor.newInstance().asInstanceOf[RawModule]

    chirrtlOut match {
      case Some(dest) =>
        val chirrtl = ChiselStage.emitCHIRRTL(gen(), args = Array("--chisel-output-file", (os.temp.dir() / "chirrtl-dump.fir").toString))
        os.write.over(dest, chirrtl, createFolders = true)
        Console.err.println(s"[socless] CHIRRTL written to $dest (${chirrtl.length} chars)")
      case None =>
        SoclessExporter.export(gen, output, sourceRoot = sourceRoot)
    }
  }

  private def arg(args: Array[String], name: String): Option[String] = {
    val i = args.indexOf(name)
    if (i < 0 || i + 1 >= args.length) None else Some(args(i + 1))
  }
}
