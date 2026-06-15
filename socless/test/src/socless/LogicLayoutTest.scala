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
  }
}
