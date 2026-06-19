package socless.layout

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

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
  }
}
