package socless

import utest._

object LogicNodeTest extends TestSuite {
  private def designOf(s: String): Design = IrWalk.design(ChirrtlParser.parse(s))
  private def mod(d: Design, n: String): ModuleDef = d.modules.find(_.name == n).get

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

  private val constTie =
    """circuit M :
      |  module M :
      |    output io_cfg : UInt<8>
      |    connect io_cfg, UInt<8>("h1f")
      |""".stripMargin

  val tests = Tests {
    test("register becomes a reg logic node") {
      val m = mod(designOf(regPass), "M")
      assert(m.logic.exists(n => n.id == "r" && n.kind == "reg"))
    }
    test("path goes through the register, not around it") {
      val m = mod(designOf(regPass), "M")
      // input wire: io_in (source) drives the register input pin `r` (sink)
      assert(m.wires.exists(w => w.to == "io_in" && w.from == "r"))
      // output wire: register output pin `r~q` (source) drives io_out (sink)
      assert(m.wires.exists(w => w.to == "r~q" && w.from == "io_out"))
      // NOT a single direct io_in <-> io_out wire (Part A caveat resolved)
      assert(!m.wires.exists(w => Set(w.from, w.to) == Set("io_in", "io_out")))
    }
    test("literal driver becomes a const node feeding its sink") {
      val m = mod(designOf(constTie), "M")
      val c = m.logic.find(_.kind == "const").get
      assert(c.label.contains("1f") || c.label.contains("31")) // value shown (hex or dec)
      assert(m.wires.exists(w => w.to == c.id && w.from == "io_cfg")) // const(source) -> io_cfg(sink)
    }
  }
}
