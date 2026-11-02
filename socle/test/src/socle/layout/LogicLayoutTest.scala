package socle.layout

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

object LogicLayoutTest extends TestSuite {
  private val regPass =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input io_in : UInt<8>
      |    output io_out : UInt<8>
      |    reg r : UInt<8>, clock
      |    connect r, io_in
      |    connect io_out, r
      |""".stripMargin

  private val fsmLayoutMod =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input go : UInt<1>
      |    output out : UInt<1>
      |    regreset state : UInt<1>, clock, reset, UInt<1>(0h0)
      |    node _T = asUInt(state)
      |    node _T_1 = eq(UInt<1>(0h0), _T)
      |    when _T_1 :
      |      connect state, go
      |    else :
      |      node _T_2 = asUInt(state)
      |      node _T_3 = eq(UInt<1>(0h1), _T_2)
      |      when _T_3 :
      |        connect state, UInt<1>(0h0)
      |    connect out, _T_1
      |""".stripMargin

  val tests = Tests {
    test("register lays out as a reg node with west input and east output pins") {
      val d = IrWalk.design(ChirrtlParser.parse(regPass))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val reg = L.nodes.find(_.kind == "reg").get
      assert(reg.id == "r")
      assert(reg.ports.exists(p => p.path == "r"   && p.side == "WEST")) // D input
      assert(reg.ports.exists(p => p.path == "r~q" && p.side == "EAST")) // Q output
    }

    test("op node pins classify with WEST inputs and EAST output") {
      val src =
        """circuit M :
          |  module M :
          |    input a : UInt<8>
          |    input b : UInt<8>
          |    output o : UInt<8>
          |    node sum = add(a, b)
          |    connect o, sum
          |""".stripMargin
      val d = IrWalk.design(ChirrtlParser.parse(src))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val op = L.nodes.find(_.kind == "op").get
      assert(op.id == "sum")
      assert(op.ports.exists(p => p.path == "sum~in0" && p.side == "WEST"))
      assert(op.ports.exists(p => p.path == "sum~in1" && p.side == "WEST"))
      assert(op.ports.exists(p => p.path == "sum" && p.side == "EAST"))
    }

    test("op and reg nodes lay out compact (no wasted instance header band)") {
      val src =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    input a : UInt<8>
          |    input b : UInt<8>
          |    output o : UInt<8>
          |    reg r : UInt<8>, clock
          |    node sum = add(a, b)
          |    connect r, sum
          |    connect o, r
          |""".stripMargin
      val d  = IrWalk.design(ChirrtlParser.parse(src))
      val L  = Layout.layout(d.modules.find(_.name == "M").get, d)
      val op = L.nodes.find(_.kind == "op").get   // 2 inputs + 1 output
      val rg = L.nodes.find(_.kind == "reg").get  // D + Q
      // A 2-input op was 112px tall and a reg 88px (HeaderH 28 + 2*PortPad +
      // rows*PortStep); compact logic geometry drops the unused header band.
      assert(op.h < 80)
      assert(rg.h < 60)
      // Every pin still sits inside the shorter box.
      (op.ports ++ rg.ports).foreach { p =>
        val node = L.nodes.find(_.ports.contains(p)).get
        assert(p.y - node.y >= 0 && p.y - node.y <= node.h)
      }
    }

    test("const node is sized to its literal value, not the synthetic id") {
      val src =
        """circuit M :
          |  module M :
          |    output o : UInt<8>
          |    connect o, UInt<8>("h0")
          |""".stripMargin
      val d = IrWalk.design(ChirrtlParser.parse(src))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val c = L.nodes.find(_.kind == "const").get
      val lbl = d.modules.find(_.name == "M").get.logic.find(_.id == c.id).get.label
      assert(!lbl.startsWith("const$"))
      assert(lbl.nonEmpty)
      assert(c.w <= 48.0)
    }

    test("fsm node lays out as a box with pins") {
      val d = Layout.withLayouts(IrWalk.design(ChirrtlParser.parse(fsmLayoutMod)))
      val lay = d.layouts(d.layoutOf.getOrElse("M", "M"))
      val box = lay.nodes.find(_.id == "fsm$M$state").get
      assert(box.kind == "fsm")
      assert(box.ports.nonEmpty)
    }

    test("block box pins resolve like fsm pins") {
      val id = "blk$M$logic"
      def port(n: String, d: String) = PortDef(n, d, "UInt<8>", "", SourceRef.unknown)
      val body = ModuleDef(id, "logic", "", SourceRef.unknown,
        Seq(port("io_a", PortDir.In), port("io_b", PortDir.Out)), Nil, Nil, Metrics(0, 0, 0))
      val m = ModuleDef("M", "M", "", SourceRef.unknown,
        Seq(port("io_a", PortDir.In), port("io_b", PortDir.Out)), Nil,
        // WireDef(from = sink, to = source), like CHIRRTL `lhs <= rhs`.
        Seq(WireDef(s"$id.io_a", "io_a", "UInt<8>", "", SourceRef.unknown),
            WireDef("io_b", s"$id.io_b", "UInt<8>", "", SourceRef.unknown)),
        Metrics(0, 0, 0),
        logic = Seq(LogicNodeDef(id, NodeKind.Block, "logic", "", SourceRef.unknown)))
      val d = Design("M", Map.empty, Seq(m), regions = Map(id -> body))
      val L = Layout.layout(m, d)
      val blocks = L.nodes.filter(_.id == id)
      assert(blocks.size == 1)
      val box = blocks.head
      assert(box.kind == "block")
      assert(box.ports.exists(p => p.path == s"$id.io_a" && p.side == "WEST"))
      assert(box.ports.exists(p => p.path == s"$id.io_b" && p.side == "EAST"))
    }

    test("mux select in0 is laid as the topmost west pin") {
      val src =
        """circuit M :
          |  module M :
          |    input s : UInt<1>
          |    input a : UInt<8>
          |    input b : UInt<8>
          |    output o : UInt<8>
          |    node m = mux(s, a, b)
          |    connect o, m
          |""".stripMargin
      val d = IrWalk.design(ChirrtlParser.parse(src))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val op = L.nodes.find(_.kind == "op").get
      val west = op.ports.filter(_.side == "WEST")
      val in0 = west.find(_.path == "m~in0").get
      // in0 (the select) must be the topmost west pin: no other west pin sits above it.
      assert(west.forall(p => p.path == "m~in0" || p.y >= in0.y))
      assert(west.exists(_.path == "m~in1"))
      assert(west.exists(_.path == "m~in2"))
    }
  }
}
