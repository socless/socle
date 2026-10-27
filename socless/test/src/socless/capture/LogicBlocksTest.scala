package socless.capture

import socless.schema._
import utest._

object LogicBlocksTest extends TestSuite {
  private val src = SourceRef("M.scala", 3, 1)
  private def node(id: String, kind: String) = LogicNodeDef(id, kind, id, "", SourceRef.unknown)
  private def w(from: String, to: String) = WireDef(from, to, "", "", SourceRef.unknown)
  private def module(logic: Seq[LogicNodeDef], wires: Seq[WireDef]) =
    ModuleDef("M", "M", "", SourceRef.unknown, Nil, Nil, wires, Metrics(0, 0, 0), logic = logic)
  private def group(id: String, members: String*) =
    Group(id, NodeKind.Block, "logic", src, layer = false, members.toSet)
  private def pairs(ws: Seq[WireDef]) = ws.map(x => (x.from, x.to))

  private val B = "blk$M$logic"

  val tests = Tests {
    test("a group becomes one box with in and out pins") {
      val m = module(
        Seq(node("_T", NodeKind.Op), node("_T_1", NodeKind.Op), node("r", NodeKind.Reg)),
        Seq(w("_T~in0", "io.a"), w("r", "_T"), w("_T_1~in0", "r~q"), w("io.b", "_T_1")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "_T_1", "r")))
      assert(out.logic.map(_.id) == Seq(B), out.logic.map(_.kind) == Seq(NodeKind.Block))
      // Pins are named after the wire's driver: the port for an in pin, the
      // member for an out pin.
      assert(pairs(out.wires) == Seq((s"$B.io_a", "io.a"), ("io.b", s"$B._T_1")))
      val body = bodies(B)
      assert(body.ports.map(p => (p.name, p.dir)) == Seq(("io_a", PortDir.In), ("_T_1", PortDir.Out)))
      assert(body.logic.size == 3)
      // The internal wires, then each crossing wire from its member end to the pin.
      assert(pairs(body.wires) == Seq(("_T~in0", s"$B.io_a"), ("r", "_T"), ("_T_1~in0", "r~q"), (s"$B._T_1", "_T_1")))
    }
    test("a crossing wire's pin is written as the parent writes it, never by its bare name") {
      // The out pin is named after the register that drives it: `r`, also the
      // register's own id (its input) inside the body.
      val m = module(Seq(node("_T", NodeKind.Op), node("r", NodeKind.Reg)),
        Seq(w("r", "_T"), w("_T~in0", "r~q"), w("io.o", "r~q")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "r")))
      assert(bodies(B).ports.map(_.name) == Seq("r"))
      assert(pairs(out.wires) == Seq(("io.o", s"$B.r")))
      assert(pairs(bodies(B).wires) == Seq(("r", "_T"), ("_T~in0", "r~q"), (s"$B.r", "r~q")))
    }
    test("wires between two groups go pin to pin") {
      val m = module(Seq(node("_T", NodeKind.Op), node("_T_1", NodeKind.Op)),
        Seq(w("_T_1~in0", "_T")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group("A", "_T"), group("B", "_T_1")))
      // Both pins carry the driver's name; B's in pin is fed by A's pin `_T`.
      assert(pairs(out.wires) == Seq(("B._T", "A._T")))
      assert(bodies("A").ports.map(p => (p.name, p.dir)) == Seq(("_T", PortDir.Out)))
      assert(bodies("B").ports.map(p => (p.name, p.dir)) == Seq(("_T", PortDir.In)))
    }
    test("duplicate crossings collapse to one pin and one wire") {
      val m = module(Seq(node("_T", NodeKind.Op), node("_T_1", NodeKind.Op)),
        Seq(w("_T~in0", "io.a"), w("_T_1~in0", "io.a")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "_T_1")))
      assert(bodies(B).ports.size == 1, out.wires.size == 1)
    }
    test("a port read and written gives two pins under driver naming") {
      val m = module(Seq(node("_T", NodeKind.Op)), Seq(w("_T~in0", "io.a"), w("io.a", "_T")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T")))
      assert(bodies(B).ports.map(p => (p.name, p.dir)) == Seq(("io_a", PortDir.In), ("_T", PortDir.Out)))
      assert(pairs(out.wires) == Seq((s"$B.io_a", "io.a"), ("io.a", s"$B._T")))
    }
    test("a port read and written is one inout pin when pins key on the port") {
      // As ModelToDesign names pins: a port external end names the pin.
      val ports = Set("io")
      val namer: LogicBlocks.PinNamer = (driver, external, boxes) =>
        if (ports(external.takeWhile(_ != '.'))) LogicBlocks.driverPin(external.replaceAll("""\[[^\]]*\]""", ""), boxes)
        else LogicBlocks.driverPin(driver, boxes)
      val m = module(Seq(node("_T", NodeKind.Op)), Seq(w("_T~in0", "io.a"), w("io.a", "_T")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T")), namer)
      assert(bodies(B).ports.map(p => (p.name, p.dir)) == Seq(("io_a", PortDir.Inout)))
      assert(pairs(out.wires) == Seq((s"$B.io_a", "io.a"), ("io.a", s"$B.io_a")))
    }
    test("pin names come from the driver, cleaned") {
      // A register read names the register; inner indices and `__` collapse,
      // a trailing `_` goes; a trailing index is dropped.
      val m = module(Seq(node("_T", NodeKind.Op), node("_T_1", NodeKind.Op), node("_T_2", NodeKind.Op)),
        Seq(w("_T~in0", "r[0]~q"), w("_T_1~in0", "io.rd[0].idx.valid"), w("_T_2~in0", "io.v[3]")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "_T_1", "_T_2")))
      assert(bodies(B).ports.map(_.name) == Seq("r_0", "io_rd_0_idx_valid", "io_v"))
      assert(pairs(out.wires) == Seq((s"$B.r_0", "r[0]~q"), (s"$B.io_rd_0_idx_valid", "io.rd[0].idx.valid"), (s"$B.io_v", "io.v[3]")))
    }
    test("a box pin driver names the pin after the box's pin") {
      val m = module(Seq(node("fsm$M$state", NodeKind.Fsm), node("_T", NodeKind.Op)),
        Seq(w("_T~in0", "fsm$M$state.state_q"), w("io.s", "fsm$M$state.io_s")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T")))
      assert(bodies(B).ports.map(_.name) == Seq("state_q"))
      assert(pairs(out.wires).contains((s"$B.state_q", "fsm$M$state.state_q")))
    }
    test("two drivers with one name get distinct pins") {
      // `A.p` and `C.p` are different signals: `p` and `p_2`, never merged.
      val m = module(Seq(node("A", NodeKind.Block), node("C", NodeKind.Block), node("_T", NodeKind.Op), node("_T_1", NodeKind.Op)),
        Seq(w("_T~in0", "A.p"), w("_T_1~in0", "C.p"), w("_T_1~in1", "A.p")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "_T_1")))
      assert(bodies(B).ports.map(_.name) == Seq("p", "p_2"))
      assert(pairs(out.wires) == Seq((s"$B.p", "A.p"), (s"$B.p_2", "C.p")))
    }
    test("a naming function renames driver heads: copies share one pin") {
      val fold = Map("acc_0" -> "acc", "acc_1" -> "acc")
      val m = module(Seq(node("_T", NodeKind.Op), node("_T_1", NodeKind.Op)),
        Seq(w("_T~in0", "acc_0.io.deq.bits"), w("_T_1~in0", "acc_1.io.deq.bits")))
      val (out, bodies) = LogicBlocks.collapse(m, Seq(group(B, "_T", "_T_1")),
        (d, _, boxes) => LogicBlocks.driverPin(d, boxes, h => fold.getOrElse(h, h)))
      assert(bodies(B).ports.map(_.name) == Seq("acc_io_deq_bits"))
      // The wires keep each copy's real path (folding merges them later).
      assert(pairs(out.wires) == Seq((s"$B.acc_io_deq_bits", "acc_0.io.deq.bits"), (s"$B.acc_io_deq_bits", "acc_1.io.deq.bits")))
    }
    test("nodes in no group are untouched") {
      val m = module(Seq(node("_T", NodeKind.Op), node("const$k", NodeKind.Const)),
        Seq(w("_T~in0", "io.a"), w("io.c", "const$k")))
      val (out, _) = LogicBlocks.collapse(m, Seq(group(B, "_T")))
      assert(out.logic.map(_.id) == Seq("const$k", B))
      assert(pairs(out.wires).contains(("io.c", "const$k")))
    }
    test("no groups, module unchanged") {
      val m = module(Seq(node("_T", NodeKind.Op)), Seq(w("_T~in0", "io.a")))
      assert(LogicBlocks.collapse(m, Nil) == ((m, Map.empty[String, ModuleDef])))
    }
    test("an empty group produces nothing") {
      val m = module(Seq(node("_T", NodeKind.Op)), Seq(w("_T~in0", "io.a")))
      assert(LogicBlocks.collapse(m, Seq(group(B))) == ((m, Map.empty[String, ModuleDef])))
    }
  }
}
