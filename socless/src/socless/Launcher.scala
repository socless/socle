package socless

import chisel3.RawModule
import os.Path

/** Generic entrypoint the Mill plugin (and direct CLI users) invoke.
  *
  * Args: `--top <fully-qualified-class> [--output <path>]`
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
  */
object Launcher {

  def main(args: Array[String]): Unit = {
    val top = arg(args, "--top").getOrElse {
      Console.err.println("usage: socless.Launcher --top <fqcn> [--output <path>] [--source-root <path>]")
      sys.exit(2)
    }
    val output     = arg(args, "--output").map(Path(_, os.pwd)).getOrElse(os.pwd / "design.json")
    // `--source-root <dir>` enables source-text embedding in the design.json
    // (Mill plugin passes this; direct CLI users can omit to skip).
    val sourceRoot = arg(args, "--source-root").map(Path(_, os.pwd))

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
    SoclessExporter.export(gen, output, sourceRoot = sourceRoot)
  }

  private def arg(args: Array[String], name: String): Option[String] = {
    val i = args.indexOf(name)
    if (i < 0 || i + 1 >= args.length) None else Some(args(i + 1))
  }
}
