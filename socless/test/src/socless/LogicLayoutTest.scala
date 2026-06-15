package socless

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
  }
}
