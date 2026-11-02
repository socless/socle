package socle.layout

import socle.schema._
import socle.parse.ChirrtlParser
import socle.ir.IrWalk

import utest._

/** Verification-layer content is laid out apart from the design: the base
  * schematic has no layer node (no hole when layers are hidden), and the layer
  * nodes sit in a band below it, their wires to the design routed into it. */
object DebugBandTest extends TestSuite {

  private def design(name: String): Design = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing /chirrtl/$name")
    try IrWalk.design(ChirrtlParser.parse(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
    finally s.close()
  }

  val tests = Tests {
    test("layer nodes sit in a band below the design") {
      val d = design("layers.fir")
      val m = d.modules.find(_.name == "LayeredCounter").get
      val layer = m.logic.filter(_.layer).map(_.id).toSet
      assert(layer.nonEmpty)
      val l = Layout.layout(m, d)
      assert(l.debugY > 0)
      val (dbg, main) = l.nodes.partition(n => layer(n.id))
      assert(dbg.nonEmpty && main.nonEmpty)
      main.foreach(n => assert(n.y + n.h <= l.debugY))
      dbg.foreach(n => assert(n.y > l.debugY))
      assert(l.mainW > 0 && l.mainW <= l.w)
      main.foreach(n => assert(n.x + n.w <= l.mainW))
    }

    test("the band starts right under the design: no hole") {
      val d = design("layers.fir")
      val m = d.modules.find(_.name == "LayeredCounter").get
      val layer = m.logic.filter(_.layer).map(_.id).toSet
      val l = Layout.layout(m, d)
      val mainBottom = l.nodes.filterNot(n => layer(n.id)).map(n => n.y + n.h).max
      assert(l.debugY >= mainBottom && l.debugY - mainBottom <= LayoutCore.DebugGap)
    }

    test("every wire into the band ends on its pins") {
      val d = design("layers.fir")
      val m = d.modules.find(_.name == "LayeredCounter").get
      val l = Layout.layout(m, d)
      val pin = l.nodes.flatMap(_.ports.map(p => p.path -> ((p.x, p.y)))).toMap
      val layer = m.logic.filter(_.layer).map(_.id).toSet
      val cross = l.edges.filter { e =>
        val a = LayoutCore.isLayerHead(e.from.takeWhile(_ != '.'), layer)
        val b = LayoutCore.isLayerHead(e.to.takeWhile(_ != '.'), layer)
        a != b
      }
      assert(cross.nonEmpty)
      cross.foreach { e =>
        // points run source (to) -> sink (from)
        assert(pin.get(e.to).forall(p => math.abs(p._2 - e.points.head._2) <= 1))
        assert(pin.get(e.from).forall(p => math.abs(p._2 - e.points.last._2) <= 1))
      }
    }

    test("a design with no layer content has no band") {
      val d = design("gcd.fir")
      assert(Layout.layout(d.modules.find(_.name == "GCD").get, d).debugY == 0)
    }
  }
}
