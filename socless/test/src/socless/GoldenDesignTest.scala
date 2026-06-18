package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._
import upickle.default._

/** Golden / snapshot tests for the design topology pipeline.
  *
  * For each committed CHIRRTL fixture in `socless/test/resources/chirrtl/`,
  * this test:
  *   1. Runs ChirrtlParser.parse -> IrWalk.design
  *   2. Serialises the result (without ELK layout) with upickle (indent=2)
  *   3. Asserts the output equals the committed golden in
  *      `socless/test/resources/golden/<name>.json`
  *
  * This catches unintended schema/parser/IR-walk regressions (changed field
  * names, dropped wires, wrong bundle types, etc.).  ELK layout coordinates
  * are NOT snapshotted here because ELK can produce slightly different
  * coordinates when run alongside other tests (ELK's LayoutMetaDataService is
  * a JVM-wide singleton susceptible to concurrent initialisation order).
  * Layout is already tested structurally in PipelineRobustnessTest.
  *
  * == Regenerating goldens ==
  *
  * When you intentionally change the pipeline output, update the goldens with:
  *
  *   ./mill socless.regenerateGoldens          # recommended: Mill task
  *   SOCLESS_REGENERATE=1 ./mill socless.test  # alternative: env-var flag
  *
  * After regenerating, review the diff with `git diff` before committing.
  *
  * == Determinism ==
  *
  * The IrWalk pipeline (parse -> design) is deterministic given the same
  * CHIRRTL input:
  *   - No timestamps or machine-specific paths enter the Design.  The sources
  *     map is EMPTY here (no sourceRoot supplied) so SourceRef.file values
  *     come verbatim from the committed CHIRRTL fixture text and are stable.
  *   - upickle serialises Maps in insertion order (LinkedHashMap).
  *
  * If a golden ever differs between runs on the same commit, that is a
  * nondeterminism bug in the pipeline, not an ELK issue.
  */
object GoldenDesignTest extends TestSuite {

  // ── Fixtures: (chirrtl filename, golden basename) ─────────────────────────

  private val fixtures: Seq[(String, String)] = Fixtures.all

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def readGolden(name: String): Option[String] = {
    val s = getClass.getResourceAsStream(s"/golden/$name.json")
    if (s == null) None
    else
      try Some(scala.io.Source.fromInputStream(s, "UTF-8").mkString)
      finally s.close()
  }

  /** Run parse -> IrWalk and return the pretty-printed JSON string.
    *
    * Layout.withLayouts is intentionally NOT called here: ELK coordinates
    * are nondeterministic when multiple test classes call the ELK singleton
    * concurrently (Layout coordinates are already covered by PipelineRobustnessTest).
    */
  private def generateJson(chirrtlFile: String): String = {
    val text   = readFixture(chirrtlFile)
    val circ   = ChirrtlParser.parse(text)
    val design = IrWalk.design(circ)
    upickle.default.write(design, indent = 2)
  }

  /** Locate the on-disk golden path from the classpath resource root.
    *
    * Mill places `socless/test/resources` on the test classpath.  The class
    * loader's resource URL for `/chirrtl/gcd.fir` resolves to
    * `<workspace>/socless/test/resources/chirrtl/gcd.fir`.  We walk up one
    * level to reach `resources/` and then down to `golden/<name>.json`.
    *
    * This avoids requiring the /golden directory itself to be on the
    * classpath before any files exist in it.
    */
  private def goldenPath(name: String): java.io.File = {
    // Anchor to the /chirrtl directory which is always present
    val chirrtlUrl = getClass.getResource("/chirrtl")
    require(chirrtlUrl != null, "classpath resource /chirrtl not found")
    val resourcesDir = new java.io.File(chirrtlUrl.toURI).getParentFile
    new java.io.File(resourcesDir, s"golden/$name.json")
  }

  // Set SOCLESS_REGENERATE=1 (or pass -Dsocless.regenerate=1) to write goldens.
  // The Mill task `./mill socless.regenerateGoldens` sets this automatically.
  private val regenerate: Boolean =
    System.getenv("SOCLESS_REGENERATE") == "1" ||
    System.getProperty("socless.regenerate", "0").trim == "1"

  // ── Tests ─────────────────────────────────────────────────────────────────

  val tests = Tests {

    test("golden JSON matches committed snapshot for every fixture") {
      var regenerated = 0
      fixtures.foreach { case (fname, gname) =>
        val actual = generateJson(fname)

        if (regenerate) {
          // Write (or overwrite) the golden on disk.
          val f = goldenPath(gname)
          f.getParentFile.mkdirs()
          java.nio.file.Files.write(f.toPath, actual.getBytes("UTF-8"))
          println(s"[golden] regenerated $gname.json")
          regenerated += 1
        } else {
          // Assert against committed golden.
          val golden = readGolden(gname).getOrElse(
            throw new java.lang.RuntimeException(
              s"Golden file /golden/$gname.json not found. " +
              "Run './mill socless.regenerateGoldens' to create it."
            )
          )
          if (actual != golden) {
            // Print a context-line diff to make the failure readable.
            val actualLines  = actual.linesIterator.toVector
            val goldenLines  = golden.linesIterator.toVector
            val diffs = actualLines.zipAll(goldenLines, "<missing>", "<missing>")
              .zipWithIndex
              .collect { case ((a, g), i) if a != g => (i + 1, g, a) }
              .take(20) // cap output
            val diffStr = diffs.map { case (ln, g, a) =>
              s"  line $ln:\n    golden: $g\n    actual: $a"
            }.mkString("\n")
            throw new java.lang.AssertionError(
              s"""Golden mismatch for $gname.
                 |First diffs (up to 20):
                 |$diffStr
                 |
                 |If this change is intentional, run:
                 |  ./mill socless.regenerateGoldens
                 |then review 'git diff socless/test/resources/golden/' before committing."""
                .stripMargin
            )
          }
        }
      }
      if (regenerate)
        println(s"[golden] regenerated $regenerated golden(s) - review diff and commit.")
    }

  }
}
