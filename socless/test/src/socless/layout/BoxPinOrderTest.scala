package socless.layout

import socless.schema._
import socless.capture.{CapturedModel, ModelToDesign}

import utest._

/** A logic or FSM block's pins are ordered within their side by where their
  * wires go, so each pin sits across from the box it connects to (#334).
  * Inputs stay West and outputs East. */
object BoxPinOrderTest extends TestSuite {
  import LayoutCore._

  private def pin(path: String, side: String, y: Double) =
    PreparedPin(path, side, false, "", path, if (side == PinSide.East) 100 else 0, y, false)

  private def captured(name: String): Design = {
    val s = getClass.getResourceAsStream(s"/captured/$name.json")
    require(s != null, s"missing classpath resource /captured/$name.json")
    try ModelToDesign.design(CapturedModel.fromJson(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
    finally s.close()
  }

  val tests = Tests {
    test("pins follow their peers") {
      val prep = Prepared(Seq(PreparedNode("b", NodeKind.Block, 100, 100,
        Seq(pin("b.p1", PinSide.West, 40), pin("b.p2", PinSide.West, 64), pin("b.o", PinSide.East, 40)))), Nil)
      val out = reorderBoxPins(prep, Map("b.p1" -> 300.0, "b.p2" -> 100.0)).nodes.head.pins
      assert(out.find(_.path == "b.p2").get.y == 40 && out.find(_.path == "b.p1").get.y == 64)
      assert(out.find(_.path == "b.o").get.y == 40)
      assert(out.map(p => p.path -> p.side).toMap == prep.nodes.head.pins.map(p => p.path -> p.side).toMap)
    }

    test("other nodes are untouched") {
      val inst = PreparedNode("i", NodeKind.Inst, 100, 100, Seq(pin("i.a", PinSide.West, 40), pin("i.b", PinSide.West, 64)))
      val out  = reorderBoxPins(Prepared(Seq(inst), Nil), Map("i.a" -> 300.0, "i.b" -> 100.0))
      assert(out.nodes.head == inst)
    }

    test("peerYOf averages the other ends") {
      val prep = Prepared(Seq(PreparedNode("b", NodeKind.Block, 100, 100, Seq(pin("b.p", PinSide.West, 40)))),
        Seq(PreparedEdge("b.p", "x.o", ""), PreparedEdge("b.p", "y.o", "")))
      assert(peerYOf(prep, Map("x.o" -> 10.0, "y.o" -> 30.0)) == Map("b.p" -> 20.0))
    }

    test("unwired and self-wired block pins keep a slot") {
      val prep = Prepared(Seq(PreparedNode("b", NodeKind.Block, 100, 120,
        Seq(pin("b.a", PinSide.West, 40), pin("b.free", PinSide.West, 64), pin("b.self", PinSide.West, 88), pin("b.o", PinSide.East, 40)))),
        Seq(PreparedEdge("b.a", "z.o", ""), PreparedEdge("b.self", "b.o", "")))
      val portY = Map("z.o" -> 500.0, "b.o" -> 40.0)
      val out   = reorderBoxPins(prep, peerYOf(prep, portY)).nodes.head.pins
      assert(out.length == 4 && out.map(_.path).toSet == prep.nodes.head.pins.map(_.path).toSet)
      assert(out.filter(_.side == PinSide.West).map(_.y).sorted == Seq(40.0, 64.0, 88.0))
    }

    test("a pin with no peer keeps its slot, wherever the block sits") {
      // Peers are absolute (the block is at y=300 on the canvas); pin ys are box-relative.
      val prep = Prepared(Seq(PreparedNode("b", NodeKind.Block, 100, 120,
        Seq(pin("b.a", PinSide.West, 40), pin("b.free", PinSide.West, 64), pin("b.c", PinSide.West, 88)))), Nil)
      val out = reorderBoxPins(prep, Map("b.a" -> 420.0, "b.c" -> 310.0)).nodes.head.pins
      def y(p: String) = out.find(_.path == p).get.y
      assert(y("b.free") == 64)            // unchanged slot
      assert(y("b.c") == 40 && y("b.a") == 88) // the peered ones swap
    }

    test("no input on the East, after layout") {
      for (name <- Seq("deepsoc", "gcd", "rvpipeline", "fsm")) {
        val d = captured(name)
        for (m <- d.modules; l = Layout.layout(m, d); n <- l.nodes if NodeKind.isBox(n.kind); p <- n.ports) {
          val pinName = p.path.stripPrefix(n.id + ".")
          val dir = d.regions.get(n.id).flatMap(_.ports.find(_.name == pinName)).map(_.dir)
          if (dir.contains(PortDir.In) && p.side != PinSide.West)
            throw new java.lang.AssertionError(s"$name/${m.name}: input ${p.path} on ${p.side}")
        }
      }
    }

    test("fsm pins keep labels") {
      val d = captured("fsm")
      for (m <- d.modules) {
        val prep   = LayoutCore.prepare(InstanceFold.fold(m, Set.empty), d)
        val before = prep.nodes.filter(n => NodeKind.isBox(n.kind)).flatMap(_.pins.map(_.label)).toSet
        val after  = Layout.layout(m, d).nodes.filter(n => NodeKind.isBox(n.kind)).flatMap(_.ports.map(_.label)).toSet
        assert(before == after)
      }
    }

    test("a block's pins follow their peers in a captured Cluster") {
      val d  = captured("deepsoc")
      val m  = d.modules.find(_.name == "Cluster").get
      val l  = Layout.layout(m, d)
      val y  = l.nodes.flatMap(_.ports.map(p => p.path -> p.y.toDouble)).toMap
      val blk = l.nodes.find(n => NodeKind.isBox(n.kind)).get
      // West pins of the block, top to bottom, are sorted by the mean y of their peers.
      val prep  = LayoutCore.prepare(InstanceFold.fold(m, Set.empty), d)
      val peers = peerYOf(prep, y)
      val west  = blk.ports.filter(_.side == PinSide.West).sortBy(_.y).map(p => peers.getOrElse(p.path, Double.NaN))
      val known = west.filterNot(_.isNaN)
      assert(known == known.sorted)
    }
  }
}
