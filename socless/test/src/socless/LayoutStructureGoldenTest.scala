package socless

/** Cross-commit structural guard for the ELK layout output (issue #103).
  *
  * GoldenDesignTest deliberately excludes layouts because ELK coordinates can
  * jitter; this test snapshots the layout STRUCTURE only, which is stable:
  * which nodes exist (id, kind), their pins (path, side, interface flag,
  * sorted), which edges are routed (from -> to, sorted), and the layoutOf
  * dedup mapping. A layout-algorithm change that silently drops nodes, flips
  * pin sides, or changes edge dedup now fails CI instead of shipping
  * unnoticed (coordinates remain free to move).
  *
  * Note: single-module / leaf fixtures (gcd, and regheavy's Controller) lay
  * out to one node with no edges, so their layout golden is near-vacuous; the
  * real layout-structure coverage comes from the multi-module fixtures
  * (AXISystem, DeepSoc, Interconnect) which carry nodes, pin sides and edges.
  *
  * Regenerate together with the JSON goldens:
  *
  *   ./mill socless.regenerateGoldens
  */
import utest._

/** Shared by the test and GoldenWriter so the regenerate task and the
  * assertion can never drift. */
object LayoutStructure {
  def summarize(design: Design): String = {
    val sb = new StringBuilder
    for ((mod, l) <- design.layouts.toSeq.sortBy(_._1)) {
      sb.append(s"layout $mod\n")
      for (n <- l.nodes.sortBy(_.id)) {
        val pins = n.ports.sortBy(_.path)
          .map(p => s"${p.path}:${p.side}${if (p.interface) ":iface" else ""}")
          .mkString(",")
        sb.append(s"  node ${n.id} kind=${n.kind} pins=$pins\n")
      }
      for (e <- l.edges.sortBy(e => (e.from, e.to))) {
        // RoutedEdge.from is the producer, .to the consumer (Schema.scala), so
        // print producer -> consumer, matching the field it is sorted by
        // (issue #137: this used to print sink -> source, reading backwards).
        sb.append(s"  edge ${e.from} -> ${e.to}\n")
      }
    }
    for ((m, rep) <- design.layoutOf.toSeq.sortBy(_._1) if m != rep) {
      sb.append(s"layoutOf $m -> $rep\n")
    }
    sb.toString
  }
}

object LayoutStructureGoldenTest extends TestSuite {

  private val fixtures: Seq[(String, String)] = Fixtures.all

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  /** Render the structural summary of every layout in the design. */
  private def summarize(chirrtlFile: String): String =
    LayoutStructure.summarize(
      Layout.withLayouts(IrWalk.design(ChirrtlParser.parse(readFixture(chirrtlFile))))
    )

  private def goldenPath(name: String): java.io.File = {
    val chirrtlUrl = getClass.getResource("/chirrtl")
    require(chirrtlUrl != null, "classpath resource /chirrtl not found")
    val resourcesDir = new java.io.File(chirrtlUrl.toURI).getParentFile
    new java.io.File(resourcesDir, s"golden/layout/$name.txt")
  }

  private def readGolden(name: String): Option[String] = {
    val f = goldenPath(name)
    if (!f.exists) None
    else Some(new String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8"))
  }

  private val regenerate: Boolean =
    System.getenv("SOCLESS_REGENERATE") == "1" ||
    System.getProperty("socless.regenerate", "0").trim == "1"

  val tests = Tests {

    test("layout structure matches committed snapshot for every fixture") {
      fixtures.foreach { case (fname, gname) =>
        val actual = summarize(fname)
        if (regenerate) {
          val f = goldenPath(gname)
          f.getParentFile.mkdirs()
          java.nio.file.Files.write(f.toPath, actual.getBytes("UTF-8"))
          println(s"[golden] regenerated layout/$gname.txt")
        } else {
          val golden = readGolden(gname).getOrElse(
            throw new RuntimeException(
              s"Golden file /golden/layout/$gname.txt not found. " +
              "Run './mill socless.regenerateGoldens' to create it."
            )
          )
          if (actual != golden) {
            val diffs = actual.linesIterator.toVector
              .zipAll(golden.linesIterator.toVector, "<missing>", "<missing>")
              .zipWithIndex
              .collect { case ((a, g), i) if a != g => s"  line ${i + 1}:\n    golden: $g\n    actual: $a" }
              .take(20)
            throw new java.lang.AssertionError(
              s"""Layout structure mismatch for $gname.
                 |${diffs.mkString("\n")}
                 |
                 |If intentional, run ./mill socless.regenerateGoldens and review the diff.""".stripMargin
            )
          }
        }
      }
    }
  }
}
