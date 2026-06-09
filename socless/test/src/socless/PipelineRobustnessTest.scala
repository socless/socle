package socless

import utest._

/** End-to-end pipeline robustness tests using committed CHIRRTL fixtures.
  *
  * Fixtures are in socless/test/resources/chirrtl/ and are loaded via the
  * classpath (Mill puts test/resources on the test classpath). For each
  * fixture the test runs the full pipeline:
  *
  *   ChirrtlParser.parse -> IrWalk.design -> Layout.withLayouts
  *
  * and verifies:
  *   1. parse returns a Circ with >= 1 module (no exception)
  *   2. IrWalk.design succeeds and returns a Design with modules
  *   3. Layout.withLayouts produces no skips (layouts.size == modules.size)
  *   4. Per-module invariants: no node overlap, all edges >= 2 pts, canvas > 0
  *   5. Mixed EAST/WEST sides appear across the whole fixture set (no all-EAST bug)
  *   6. Top-level hub modules (AXISystem, DeepSoc) individually show both sides
  */
object PipelineRobustnessTest extends TestSuite {

  // ── Fixtures ─────────────────────────────────────────────────────────────────

  private val fixtures: Seq[(String, String)] = Seq(
    "axisystem.fir"    -> "axisystem",
    "gcd.fir"          -> "gcd",
    "rvpipeline.fir"   -> "rvpipeline",
    "deepsoc.fir"      -> "deepsoc",
    "interconnect.fir" -> "interconnect",
    "regheavy.fir"     -> "regheavy",
  )

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  // utest's assert() macro does not accept a message argument. Use this helper
  // to attach context to a failed assertion.
  private def check(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new java.lang.RuntimeException(msg)

  // Helper: run parse + design + withLayouts for a fixture file.
  private def pipeline(fname: String): (ChirrtlParser.Circ, Design, Design) = {
    val text   = readFixture(fname)
    val circ   = ChirrtlParser.parse(text)
    val design = IrWalk.design(circ)
    val laid   = Layout.withLayouts(design)
    (circ, design, laid)
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def nodesOverlap(nodes: Seq[LaidNode]): Option[String] = {
    for (i <- nodes.indices; j <- (i + 1) until nodes.length) {
      val a = nodes(i); val b = nodes(j)
      if (a.x < b.x + b.w && b.x < a.x + a.w &&
          a.y < b.y + b.h && b.y < a.y + a.h) {
        return Some(s"${a.id} overlaps ${b.id}")
      }
    }
    None
  }

  // ── Tests ─────────────────────────────────────────────────────────────────────

  val tests = Tests {

    // 1 + 2: parse + IrWalk succeed for all fixtures
    test("all fixtures parse and IrWalk without exception") {
      fixtures.foreach { case (fname, label) =>
        val text   = readFixture(fname)
        val circ   = ChirrtlParser.parse(text)
        check(circ.modules.nonEmpty,    s"[$label] ChirrtlParser.parse returned empty modules")
        val design = IrWalk.design(circ)
        check(design.modules.nonEmpty,  s"[$label] IrWalk.design returned empty modules")
        check(
          design.modules.size == circ.modules.size,
          s"[$label] design.modules.size ${design.modules.size} != circ.modules.size ${circ.modules.size}"
        )
      }
    }

    // 3: no modules skipped by Layout.withLayouts
    test("Layout.withLayouts produces no skips for any fixture") {
      fixtures.foreach { case (fname, label) =>
        val (_, design, laid) = pipeline(fname)
        check(
          laid.layouts.size == design.modules.size,
          s"[$label] layouts.size ${laid.layouts.size} != modules.size ${design.modules.size} — some modules were skipped"
        )
      }
    }

    // 4a: no node overlaps per module
    test("no node overlaps in any module layout") {
      fixtures.foreach { case (fname, label) =>
        val (_, design, laid) = pipeline(fname)
        for (m <- design.modules) {
          val layout = laid.layouts(m.name)
          nodesOverlap(layout.nodes) match {
            case Some(msg) => throw new java.lang.RuntimeException(s"[$label/${m.name}] node overlap: $msg")
            case None      => ()
          }
        }
      }
    }

    // 4b: all routed edges have >= 2 points
    test("all routed edges have at least 2 points") {
      fixtures.foreach { case (fname, label) =>
        val (_, design, laid) = pipeline(fname)
        for (m <- design.modules) {
          val layout = laid.layouts(m.name)
          for (e <- layout.edges) {
            check(
              e.points.length >= 2,
              s"[$label/${m.name}] edge from=${e.from} to=${e.to} has ${e.points.length} points (need >= 2)"
            )
          }
        }
      }
    }

    // 4c: canvas dimensions positive for modules with nodes
    test("canvas dimensions are positive for non-empty module layouts") {
      fixtures.foreach { case (fname, label) =>
        val (_, design, laid) = pipeline(fname)
        for (m <- design.modules) {
          val layout = laid.layouts(m.name)
          if (layout.nodes.nonEmpty) {
            check(
              layout.w > 0 && layout.h > 0,
              s"[$label/${m.name}] canvas size ${layout.w}x${layout.h} is not positive"
            )
          }
        }
      }
    }

    // 5: regression guard — both EAST and WEST sides exist across all layouts
    test("both EAST and WEST pin sides occur across all fixture layouts") {
      val allSides = fixtures.flatMap { case (fname, _) =>
        val (_, design, laid) = pipeline(fname)
        for {
          layout <- laid.layouts.values.toSeq
          node   <- layout.nodes
          pin    <- node.ports
        } yield pin.side
      }.toSet
      check(allSides.contains("EAST"), "no EAST pin sides found across all fixture layouts")
      check(allSides.contains("WEST"), "no WEST pin sides found across all fixture layouts")
    }

    // 6: hub modules (multi-instance tops) individually show both EAST and WEST
    test("AXISystem top module has both EAST and WEST pin sides") {
      val (circ, design, laid) = pipeline("axisystem.fir")
      val topLayout = laid.layouts.getOrElse(
        circ.name,
        throw new java.lang.RuntimeException(s"[axisystem] no layout for ${circ.name}")
      )
      val sides   = topLayout.nodes.flatMap(_.ports).map(_.side).toSet
      val hasPins = topLayout.nodes.exists(_.ports.nonEmpty)
      if (hasPins) {
        check(
          sides.contains("EAST") && sides.contains("WEST"),
          s"[axisystem] ${circ.name} layout has only sides: $sides (expected both EAST and WEST)"
        )
      }
    }

    test("DeepSoc top module has both EAST and WEST pin sides") {
      val (circ, design, laid) = pipeline("deepsoc.fir")
      val topLayout = laid.layouts.getOrElse(
        circ.name,
        throw new java.lang.RuntimeException(s"[deepsoc] no layout for ${circ.name}")
      )
      val sides   = topLayout.nodes.flatMap(_.ports).map(_.side).toSet
      val hasPins = topLayout.nodes.exists(_.ports.nonEmpty)
      if (hasPins) {
        check(
          sides.contains("EAST") && sides.contains("WEST"),
          s"[deepsoc] ${circ.name} layout has only sides: $sides (expected both EAST and WEST)"
        )
      }
    }
  }
}
