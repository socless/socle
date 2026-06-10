package socless

import upickle.default._

/** Standalone JVM entry point used by `./mill socless.regenerateGoldens`.
  *
  * Writes (or overwrites) the committed golden JSON files in
  * `socless/test/resources/golden/` from the current pipeline output.
  * Not intended to be called directly - use the Mill task instead:
  *
  *   ./mill socless.regenerateGoldens
  *
  * or the equivalent env-var form:
  *
  *   SOCLESS_REGENERATE=1 ./mill socless.test socless.GoldenDesignTest
  */
object GoldenWriter {

  private val fixtures: Seq[(String, String)] = Seq(
    "axisystem.fir"    -> "axisystem",
    "gcd.fir"          -> "gcd",
    "rvpipeline.fir"   -> "rvpipeline",
    "deepsoc.fir"      -> "deepsoc",
    "interconnect.fir" -> "interconnect",
    "regheavy.fir"     -> "regheavy",
  )

  def main(args: Array[String]): Unit = {
    // Expect exactly one argument: the path to socless/test/resources/golden/
    if (args.length != 1) {
      System.err.println("Usage: GoldenWriter <path-to-golden-dir>")
      System.exit(1)
    }
    val goldenDir = new java.io.File(args(0))
    goldenDir.mkdirs()

    fixtures.foreach { case (fname, gname) =>
      val s = getClass.getResourceAsStream(s"/chirrtl/$fname")
      require(s != null, s"missing classpath resource /chirrtl/$fname")
      val text = try scala.io.Source.fromInputStream(s, "UTF-8").mkString finally s.close()

      val circ   = ChirrtlParser.parse(text)
      val design = IrWalk.design(circ)
      val json   = upickle.default.write(design, indent = 2)

      val out = new java.io.File(goldenDir, s"$gname.json")
      java.nio.file.Files.write(out.toPath, json.getBytes("UTF-8"))
      println(s"[golden] wrote ${out.getAbsolutePath}")
    }
    println(s"[golden] regenerated ${fixtures.size} goldens in ${goldenDir.getAbsolutePath}")
  }
}
