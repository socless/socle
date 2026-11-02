package socless.layout

import socless.schema._

import utest._

/** A register of bundle type opens in place again (lost in #332): its D side
  * and its Q side are rows of its fields, and wires land on the deepest one shown. */
object RegRowsTest extends TestSuite {

  private val design = {
    val b = BundleDef("B", BundleKind.Bundle, channels = Seq(ChannelDef("a", "UInt<8>", flip = false), ChannelDef("b", "UInt<4>", flip = false)))
    val top = ModuleDef("Top", "Top", "", SourceRef.unknown,
      Seq(PortDef("x", PortDir.In, "UInt<8>", "", SourceRef.unknown), PortDef("y", PortDir.Out, "UInt<4>", "", SourceRef.unknown)),
      Nil,
      Seq(WireDef("r.a", "x", "UInt<8>", "", SourceRef.unknown), WireDef("y", "r~q.b", "UInt<4>", "", SourceRef.unknown)),
      Metrics(1, 2, 0),
      logic = Seq(LogicNodeDef("r", NodeKind.Reg, "r", "B", SourceRef.unknown)))
    Design("Top", Map("B" -> b), Seq(top))
  }
  private val top = design.modules.head
  private val reg = top.logic.head

  val tests = Tests {
    test("a closed register is one row on each side") {
      val rs = PortModel.regRows(reg, design, Set.empty)
      assert(rs.map(r => r.path -> r.side) == Seq("r" -> PinSide.West, "r~q" -> PinSide.East))
      assert(rs.forall(_.expandable))
    }
    test("opening its D side shows the fields under it") {
      val rs = PortModel.regRows(reg, design, Set("r"))
      assert(rs.map(_.path) == Seq("r", "r.a", "r.b", "r~q"))
      assert(rs.filter(_.depth == 1).forall(_.side == PinSide.West))
    }
    test("a ground register has no rows") {
      assert(PortModel.regRows(reg.copy(bundle = "UInt<8>"), design, Set.empty).isEmpty)
    }
    test("the layout's register pins are its rows, wires on the deepest one shown") {
      def pins(open: Set[String]) = LayoutCore.prepare(top, design, open).nodes.find(_.id == "r").get.pins.map(_.path)
      assert(pins(Set.empty) == Seq("r", "r~q"))
      assert(pins(Set("r", "r~q")) == Seq("r", "r.a", "r.b", "r~q", "r~q.a", "r~q.b"))
      val closed = LayoutCore.prepare(top, design).edges.map(e => e.fromPath -> e.toPath).toSet
      assert(closed == Set("r" -> "x", "y" -> "r~q"))
      val open = LayoutCore.prepare(top, design, Set("r", "r~q")).edges.map(e => e.fromPath -> e.toPath).toSet
      assert(open == Set("r.a" -> "x", "y" -> "r~q.b"))
      val dw = LayoutCore.displayWires(top, design, Set("r")).map(w => w.from -> w.to).toSet
      assert(dw == Set("r.a" -> "x", "y" -> "r~q"))
    }
  }
}
