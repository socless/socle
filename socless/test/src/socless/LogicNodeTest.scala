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

  private val opAdd =
    """circuit M :
      |  module M :
      |    input a : UInt<8>
      |    input b : UInt<8>
      |    output o : UInt<8>
      |    node sum = add(a, b)
      |    node sliced = bits(sum, 3, 0)
      |    connect o, sliced
      |""".stripMargin

  private val constToLogic =
    """circuit M :
      |  module M :
      |    input a : UInt<8>
      |    output o : UInt<1>
      |    node eqz = eq(a, UInt<8>("h0"))
      |    connect o, eqz
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
    test("logic op becomes an op node routed through; plumbing is contracted") {
      val m = mod(designOf(opAdd), "M")
      val op = m.logic.find(_.kind == "op").get
      assert(op.id == "sum" && op.op == "add")
      // inputs a -> sum~in0, b -> sum~in1
      assert(m.wires.exists(w => w.to == "a" && w.from == "sum~in0"))
      assert(m.wires.exists(w => w.to == "b" && w.from == "sum~in1"))
      // output sum -> o (the `bits` plumbing node is contracted, so o is driven by sum directly)
      assert(m.wires.exists(w => w.to == "sum" && w.from == "o"))
      // no `sliced` (bits) op node
      assert(!m.logic.exists(_.id == "sliced"))
    }
    test("literal operand creates a const node driving a logic op input pin") {
      val m = mod(designOf(constToLogic), "M")
      // eq is a logic op - rendered as an op node
      val op = m.logic.find(n => n.kind == "op" && n.id == "eqz").get
      assert(op.op == "eq")
      // the literal UInt<8>("h0") becomes a const node
      val c = m.logic.find(_.kind == "const").get
      // the const node drives eqz's second input pin (eqz~in1)
      assert(m.wires.exists(w => w.to == c.id && w.from == "eqz~in1"))
    }
  }
}
