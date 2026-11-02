package socle.lsp

import socle.SocleExporter

/** `FromCapture <captured.json> --output <path> --source-root <dir>`: adapt a
  * socle-probe capture to design.json like `Launcher from-capture`, but with
  * the scalameta [[ScalaSourceIndex]] so FSM and generator blocks carry their
  * source diagram. It lives here because `socle` has no scalameta.
  */
object FromCapture {
  private val Usage = "usage: FromCapture <captured.json> --output <path> --source-root <dir>"

  /** Returns the exit code. */
  def run(args: Array[String]): Int = {
    var in: Option[String]   = None
    var out: Option[String]  = None
    var root: Option[String] = None
    val it = args.iterator
    while (it.hasNext) it.next() match {
      case "--output" if it.hasNext               => out = Some(it.next())
      case "--source-root" if it.hasNext          => root = Some(it.next())
      case a if !a.startsWith("--") && in.isEmpty => in = Some(a)
      case a =>
        Console.err.println(s"[socle] from-capture: unrecognized argument '$a' ($Usage)")
        return 2
    }
    if (in.isEmpty || out.isEmpty || root.isEmpty) {
      Console.err.println(s"[socle] from-capture: input, --output and --source-root are required ($Usage)")
      return 2
    }
    val sourceRoot = os.Path(root.get, os.pwd)
    try {
      SocleExporter.fromCaptured(
        os.read(os.Path(in.get, os.pwd)),
        os.Path(out.get, os.pwd),
        Some(sourceRoot),
        index = Some(new ScalaSourceIndex(sourceRoot)),
      )
      0
    } catch {
      case e: IllegalArgumentException =>
        Console.err.println(s"[socle] from-capture: ${e.getMessage}")
        1
    }
  }

  def main(args: Array[String]): Unit = sys.exit(run(args))
}
