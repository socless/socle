package socless.ir

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

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

  private val muxDefault =
    """circuit M :
      |  module M :
      |    input c : UInt<1>
      |    input x : UInt<8>
      |    input d : UInt<8>
      |    output o : UInt<8>
      |    connect o, d
      |    when c :
      |      connect o, x
      |""".stripMargin

  private val muxRegHold =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input c : UInt<1>
      |    input x : UInt<8>
      |    reg r : UInt<8>, clock
      |    when c :
      |      connect r, x
      |""".stripMargin

  private val nestedIneligible =
    """circuit M :
      |  module M :
      |    input a : UInt<1>
      |    input b : UInt<1>
      |    input x : UInt<8>
      |    output o : UInt<8>
      |    when a :
      |      when b :
      |        connect o, x
      |""".stripMargin

  private val fsmMod =
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

  private val fsmIoMod =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input cmd : UInt<1>
      |    output busy : UInt<1>
      |    regreset state : UInt<1>, clock, reset, UInt<1>(0h0)
      |    node _T = asUInt(state)
      |    node _T_1 = eq(UInt<1>(0h0), _T)
      |    when _T_1 :
      |      connect state, cmd
      |      connect busy, UInt<1>(0h0)
      |    else :
      |      node _T_2 = asUInt(state)
      |      node _T_3 = eq(UInt<1>(0h1), _T_2)
      |      when _T_3 :
      |        connect state, UInt<1>(0h0)
      |        connect busy, UInt<1>(0h1)
      |""".stripMargin

  private val fsmBundleSink =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input cmd : UInt<1>
      |    output io : { a : UInt<1>, b : UInt<1>}
      |    regreset state : UInt<1>, clock, reset, UInt<1>(0h0)
      |    node _T = asUInt(state)
      |    node _T_1 = eq(UInt<1>(0h0), _T)
      |    when _T_1 :
      |      connect state, cmd
      |      connect io.a, cmd
      |    else :
      |      node _T_2 = asUInt(state)
      |      node _T_3 = eq(UInt<1>(0h1), _T_2)
      |      when _T_3 :
      |        connect state, UInt<1>(0h0)
      |        connect io.a, UInt<1>(0h0)
      |    connect io.b, cmd
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
    test("default-then-when sink becomes a mux node routed to the sink") {
      val m = mod(designOf(muxDefault), "M")
      val mx = m.logic.find(n => n.kind == "op" && n.op == "mux").get
      // sel/true/false inputs and the mux->sink output (WireDef.from = sink).
      assert(m.wires.exists(w => w.from == s"${mx.id}~in0" && w.to == "c"))
      assert(m.wires.exists(w => w.from == s"${mx.id}~in1" && w.to == "x"))
      assert(m.wires.exists(w => w.from == s"${mx.id}~in2" && w.to == "d"))
      assert(m.wires.exists(w => w.from == "o" && w.to == mx.id))
    }
    test("conditional register feeds its own Q into the mux (self-loop hold)") {
      val m = mod(designOf(muxRegHold), "M")
      val mx = m.logic.find(n => n.kind == "op" && n.op == "mux").get
      assert(m.wires.exists(w => w.from == s"${mx.id}~in1" && w.to == "x"))
      assert(m.wires.exists(w => w.from == s"${mx.id}~in2" && w.to == "r~q")) // held value
      assert(m.wires.exists(w => w.from == "r" && w.to == mx.id))             // mux drives reg D
    }
    test("nested (depth-2) conditional is left as fan-in, no mux") {
      val m = mod(designOf(nestedIneligible), "M")
      assert(!m.logic.exists(n => n.kind == "op" && n.op == "mux"))
      assert(m.wires.exists(w => w.from == "o" && w.to == "x")) // direct fan-in kept
    }
    test("an FSM collapses to one fsm box with a stored body") {
      val d = designOf(fsmMod)
      val m = mod(d, "M")
      val fsm = m.logic.find(_.kind == "fsm").get
      assert(fsm.id == "fsm$M$state" && fsm.label == "FSM: state")
      // the eq chain nodes are gone from the parent (collapsed)
      assert(!m.logic.exists(n => n.kind == "op" && n.id == "_T_1"))
      // the body is stored in Design.regions and holds the chain nodes
      val body = d.regions("fsm$M$state")
      assert(body.logic.exists(_.id == "_T_1"))
      // the state register stays parent-level
      assert(m.logic.exists(n => n.kind == "reg" && n.id == "state"))
      // the eq operand const is absorbed into the body, not stranded in the parent
      assert(body.logic.exists(_.kind == "const"))
      assert(!m.logic.exists(n => n.kind == "const" && n.id.contains("~in")))
      // const->op wires live inside the body, not as parent wires/pins
      assert(body.wires.nonEmpty)
      // no parent wire references a node that only exists in the body
      val bodyOnlyIds = body.logic.map(_.id).toSet -- m.logic.map(_.id).toSet
      assert(!m.wires.exists(w => bodyOnlyIds.contains(w.from) || bodyOnlyIds.contains(w.to)))
    }
    test("FSM boundary pins are distinct per signal with correct direction") {
      val d = designOf(fsmIoMod)
      val body = d.regions("fsm$M$state")
      // distinct pins for distinct external signals (no collapse onto one "io"/head pin)
      val pinNames = body.ports.map(_.name)
      assert(pinNames.distinct.size == pinNames.size)
      // an input pin (cmd, read by the FSM) and an output pin (busy, written) both exist
      assert(body.ports.exists(p => p.name.contains("cmd")))
      assert(body.ports.exists(p => p.name.contains("busy")))
      // busy is driven by the FSM -> out; cmd feeds the FSM -> in
      assert(body.ports.find(_.name.contains("busy")).get.dir == "out")
      assert(body.ports.find(_.name.contains("cmd")).get.dir == "in")
    }
    test("FSM absorbs only its own bundle-field sink, not sibling fields") {
      val d = designOf(fsmBundleSink)
      val m = mod(d, "M")
      val body = d.regions("fsm$M$state")
      // io.a (written inside the switch) is the FSM's output; io.b (written
      // outside) must stay in the parent and NOT appear in the body.
      val bodyPaths = (body.ports.map(_.name) ++ body.wires.flatMap(w => Seq(w.from, w.to))).mkString(" ")
      assert(bodyPaths.contains("io_a"))       // io.a is the FSM output
      assert(!bodyPaths.contains("io_b"))      // io.b sibling not absorbed
      // io.b stays a parent wire, driven from outside the FSM
      assert(m.wires.exists(w => w.from.contains("io") && w.from.contains("b")))
    }
    test("a non-FSM module is unchanged (no fsm node, empty regions)") {
      val d = designOf(opAdd) // existing fixture in this file: node add + connect
      assert(!mod(d, "M").logic.exists(_.kind == "fsm"))
      assert(d.regions.isEmpty)
    }
  }
}
