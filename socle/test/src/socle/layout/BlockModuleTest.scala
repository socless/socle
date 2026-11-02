package socle.layout

import socle.capture.{Group, LogicBlocks}
import socle.schema._

import utest._

/** A logic block opened as a module ([[LayoutCore.blockAsModule]]): its pins
  * are the ports, its body's wires end on them, and a bake carries its layout. */
object BlockModuleTest extends TestSuite {
  private val B = "blk$M$logic"
  private def node(id: String, kind: String) = LogicNodeDef(id, kind, id, "", SourceRef("M.scala", 9, 3))
  private def w(from: String, to: String) = WireDef(from, to, "", "", SourceRef.unknown)
  private def port(n: String, d: String) = PortDef(n, d, "UInt<1>", "", SourceRef.unknown)

  // An out pin named after the register that drives it (`r`): the clash a
  // block body writes `<block>.r` to avoid.
  private val (parent, bodies) = LogicBlocks.collapse(
    ModuleDef("M", "M", "", SourceRef.unknown, Seq(port("io.a", PortDir.In), port("io.o", PortDir.Out)), Nil,
      Seq(w("_T~in0", "io.a"), w("r", "_T"), w("io.o", "r~q")), Metrics(0, 0, 0),
      logic = Seq(node("_T", NodeKind.Op), node("r", NodeKind.Reg))),
    Seq(Group(B, NodeKind.Block, "logic", SourceRef("M.scala", 3, 1), layer = false, Set("_T", "r"))))
  private val design = Design("M", Map.empty, Seq(parent), regions = bodies)

  val tests = Tests {
    test("pins become ports and the body's wires end on them") {
      val m = LayoutCore.blockAsModule(B, bodies(B))
      assert(m.name == B)
      assert(LayoutCore.blockAsModule(B, bodies(B), "logic").`type` == "logic")
      assert(m.ports.map(_.name) == Seq("io_a", "r_pin"))
      assert(m.logic.map(_.id).toSet == Set("_T", "r"))
      val p    = LayoutCore.prepare(m, design)
      val pins = p.nodes.flatMap(_.pins.map(_.path)).toSet
      assert(p.edges.length == bodies(B).wires.length)
      p.edges.foreach(e => assert(pins(e.fromPath) && pins(e.toPath)))
      // The renamed pin keeps its name on screen.
      assert(m.ports.find(_.name == "r_pin").exists(_.label.nonEmpty))
    }

    test("a bake lays every block out, under its id") {
      val baked = Layout.withLayouts(design)
      assert(baked.layoutOf.get(B).exists(baked.layouts.contains))
      // The parent keeps its own layout.
      assert(baked.layoutOf.get("M").exists(baked.layouts.contains))
    }

    test("an fsm region is not a block") {
      val fsm = design.copy(regions = Map("fsm$M$s" -> bodies(B).copy(name = "fsm$M$s")),
        modules = Seq(parent.copy(logic = Seq(node("fsm$M$s", NodeKind.Fsm)))))
      assert(!Layout.withLayouts(fsm).layoutOf.contains("fsm$M$s"))
    }
  }
}
