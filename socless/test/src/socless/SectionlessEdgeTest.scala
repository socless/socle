package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

/** No-silent-edge-loss invariant for the ELK read-back (issue 2c follow-up).
  *
  * ELK occasionally returns a laid-out edge WITHOUT route sections (it placed
  * the endpoints but emitted no polyline). The old read-back dropped such edges
  * to keep the >=2-points contract, so the wire vanished from the render. The
  * fix synthesizes a straight 2-point route from the source port anchor to the
  * target port anchor instead.
  *
  * This test asserts the post-condition on the real example fixtures: for every
  * module, every prepared edge whose BOTH endpoints are laid out yields a routed
  * edge with `points.length >= 2`.
  *
  * NOTE: in the committed fixtures ELK's layered router never actually returns a
  * sectionless edge, so the fixture invariants below already held before the fix
  * and serve as a no-silent-loss GUARD. The synthesis branch is exercised
  * directly by the smoke-fixture test at the bottom (Layout.smokeSectionlessRoute
  * strips an edge's sections), which is the only way to reach the fallback.
  */
object SectionlessEdgeTest extends TestSuite {

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  /** For one module, return (resolvable prepared edges, routed edges). An edge
    * is "resolvable" when both its endpoint pins were laid out as ports. */
  private def coverage(module: ModuleDef, design: Design): (Int, Int, Seq[String]) = {
    val prep   = LayoutCore.prepare(module, design)
    val laid   = Layout.layout(module, design)
    val laidPorts = laid.nodes.flatMap(_.ports.map(_.path)).toSet
    // Prepared edges are already deduped (anti-parallel collapse); each one with
    // both endpoints laid out must surface as a routed edge.
    val resolvable = prep.edges.filter(e => laidPorts.contains(e.toPath) && laidPorts.contains(e.fromPath))
    // Routed edges keyed by the same (producer=from, consumer=to) identity that
    // RoutedEdge carries; PreparedEdge.fromPath is the sink (RoutedEdge.from),
    // toPath the source (RoutedEdge.to).
    val routedKeys = laid.edges.filter(_.points.length >= 2).map(e => (e.from, e.to)).toSet
    val missing = resolvable.collect {
      case e if !routedKeys.contains((e.fromPath, e.toPath)) => s"${e.toPath} -> ${e.fromPath}"
    }
    (resolvable.length, laid.edges.count(_.points.length >= 2), missing)
  }

  val tests = Tests {

    test("no resolvable edge is silently dropped in any fixture module") {
      val report = new StringBuilder
      var totalMissing = 0
      Fixtures.all.foreach { case (fname, gname) =>
        val design = IrWalk.design(ChirrtlParser.parse(readFixture(fname)))
        design.modules.foreach { m =>
          val (resolvable, routed, missing) = coverage(m, design)
          if (missing.nonEmpty) {
            totalMissing += missing.length
            report.append(s"[$gname] ${m.name}: ${missing.length} dropped of $resolvable resolvable (routed=$routed)\n")
            missing.foreach(x => report.append(s"    $x\n"))
          }
        }
      }
      assert(totalMissing == 0)
      if (report.nonEmpty) println(report.toString)
    }

    test("every routed edge has at least two points in every fixture") {
      Fixtures.all.foreach { case (fname, _) =>
        val design = IrWalk.design(ChirrtlParser.parse(readFixture(fname)))
        design.modules.foreach { m =>
          val laid = Layout.layout(m, design)
          assert(laid.edges.forall(_.points.length >= 2))
        }
      }
    }

    // The real fixtures never produce a sectionless ELK edge (layered routing
    // always emits a section, even for self-loops), so the fallback branch in
    // Layout.layout cannot be reached through them. This drives the synthesis
    // directly via a smoke fixture that strips an edge's sections, proving the
    // fallback yields a straight 2-point route in the routed-point coordinate
    // space (the two port anchors).
    test("sectionless edge synthesizes a straight 2-point route between port anchors") {
      val (pts, srcAnchor, sinkAnchor) = Layout.smokeSectionlessRoute()
      assert(pts.length == 2)
      assert(pts.head == srcAnchor)
      assert(pts.last == sinkAnchor)
      // anchors are absolute (root) coordinates, so non-negative after layout
      assert(srcAnchor._1 >= 0 && srcAnchor._2 >= 0)
      assert(sinkAnchor._1 >= 0 && sinkAnchor._2 >= 0)
      // direction RIGHT: the source (EAST pin of the left node) sits left of the
      // sink (WEST pin of the right node)
      assert(srcAnchor._1 < sinkAnchor._1)
    }
  }
}
