package socle.capture

import socle.schema._
import utest._

object LogicGroupsTest extends TestSuite {
  private val modSrc = SourceRef("M.scala", 1, 1)
  private def at(line: Int) = SourceRef("M.scala", line, 1)
  private def node(id: String, kind: String, source: SourceRef = SourceRef.unknown, layer: Boolean = false) =
    LogicNodeDef(id, kind, id, "", source, layer = layer)
  private def w(from: String, to: String) = WireDef(from, to, "", "", SourceRef.unknown)
  private def module(logic: Seq[LogicNodeDef], wires: Seq[WireDef] = Nil) =
    ModuleDef("M", "M", "", modSrc, Nil, Nil, wires, Metrics(0, 0, 0), logic = logic,
      scalaClass = "pkg.M")

  /** Constructs keyed by (file, line); the one at line 20 has a long label. */
  private val index: Option[SourceIndex] = {
    val table = Map(
      ("M.scala", 10) -> Construct("c10", "when(a)", at(10)),
      ("M.scala", 20) -> Construct("c20", "for (i <- 0 until aVeryLongLoopBoundNameForTheTest)", at(20)),
    )
    Some(new SourceIndex {
      def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] =
        if (scalaClass == "pkg.M") table.get((file, line)) else None
    })
  }

  private def ids(g: Group) = g.members

  val tests = Tests {
    test("an fsm box and its state register stay out of every group") {
      val m  = module(
        Seq(node("fsm$M$switch_state$0", NodeKind.Fsm, at(5)).copy(state = "state"), node("state", NodeKind.Reg, at(4))),
        Seq(w("state", "fsm$M$switch_state$0.state")))
      assert(LogicGroups.assign(m, None).isEmpty)
    }
    test("a register a box writes stays plain") {
      // `r` is written by the box and by a module-level op; `r2` only by the op.
      val m  = module(
        Seq(node("when$M$when_en$0", NodeKind.Block, at(7)), node("a", NodeKind.Op, at(99)),
          node("r", NodeKind.Reg), node("r2", NodeKind.Reg)),
        Seq(w("r", "when$M$when_en$0.r"), w("r", "a"), w("r2", "a")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$logic"), ids(gs.head) == Set("a", "r2"))
    }
    test("ops inside one construct share a generator group") {
      val m  = module(Seq(node("a", NodeKind.Op, at(10)), node("b", NodeKind.Op, at(10))))
      val gs = LogicGroups.assign(m, index)
      assert(gs.size == 1)
      assert(gs.head.id == "blk$M$gen$1", gs.head.kind == NodeKind.Block, gs.head.label == "when(a)",
        gs.head.source == at(10), ids(gs.head) == Set("a", "b"))
    }
    test("two constructs give two groups numbered in source order") {
      val m  = module(Seq(node("late", NodeKind.Op, at(20)), node("early", NodeKind.Op, at(10))))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$gen$1", "blk$M$gen$2"))
      assert(ids(gs(0)) == Set("early"), ids(gs(1)) == Set("late"))
      assert(gs(1).label.length == 40, gs(1).label.endsWith("…"))
    }
    test("no index: logic only, the fsm box left out") {
      val m = module(Seq(
        node("fsm$M$switch_state$0", NodeKind.Fsm, at(5)), node("state", NodeKind.Reg),
        node("a", NodeKind.Op, at(10)), node("r", NodeKind.Reg)),
        Seq(w("state", "fsm$M$switch_state$0.state")))
      val gs = LogicGroups.assign(m, None)
      assert(gs.map(_.id) == Seq("blk$M$logic"))
      assert(ids(gs(0)) == Set("a", "r"), gs(0).label == "logic", gs(0).source == modSrc)
    }
    test("unknown-position node follows the group it drives") {
      val m = module(
        Seq(node("a", NodeKind.Op, at(10)), node("mux", NodeKind.Op)),
        Seq(w("a~in0", "mux")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$gen$1"), ids(gs.head) == Set("a", "mux"))
    }
    test("register follows its writers") {
      val m = module(
        Seq(node("a", NodeKind.Op, at(10)), node("b", NodeKind.Op, at(20)),
          node("r", NodeKind.Reg), node("r2", NodeKind.Reg)),
        Seq(w("r", "a"), w("r2", "a"), w("r2", "b")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$gen$1", "blk$M$gen$2", "blk$M$logic"))
      assert(ids(gs(0)) == Set("a", "r"), ids(gs(1)) == Set("b"), ids(gs(2)) == Set("r2"))
    }
    test("const follows a single consumer group, else stays") {
      val m = module(
        Seq(node("a", NodeKind.Op, at(10)), node("const$k", NodeKind.Const), node("const$j", NodeKind.Const)),
        Seq(w("a~in1", "const$k"), w("io.x", "const$j")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.size == 1, ids(gs.head) == Set("a", "const$k"))
    }
    test("layer nodes never mix") {
      val m = module(Seq(
        node("a", NodeKind.Op, at(10)), node("chk", NodeKind.Op, at(10), layer = true),
        node("lr", NodeKind.Reg, layer = true)))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$gen$1", "blk$M$layer$1", "blk$M$layer$2"))
      assert(!gs(0).layer, ids(gs(0)) == Set("a"))
      assert(gs(1).layer, ids(gs(1)) == Set("chk"))
      assert(gs(2).layer, ids(gs(2)) == Set("lr"), gs(2).label == "debug logic")
    }
    test("a vec register write r[0] counts as a D driver") {
      val m = module(
        Seq(node("a", NodeKind.Op, at(10)), node("r", NodeKind.Reg)),
        Seq(w("r[0]", "a")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$gen$1"), ids(gs.head) == Set("a", "r"))
    }
    test("const feeding only a logic op joins logic") {
      val m = module(
        Seq(node("a", NodeKind.Op, at(99)), node("const$k", NodeKind.Const)),
        Seq(w("a~in1", "const$k")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$logic"), ids(gs.head) == Set("a", "const$k"))
    }
    test("const feeding only a box stays plain") {
      val m = module(
        Seq(node("fsm$M$switch_state$0", NodeKind.Fsm), node("const$k", NodeKind.Const)),
        Seq(w("fsm$M$switch_state$0.k", "const$k")))
      assert(LogicGroups.assign(m, index).isEmpty)
    }
    test("unknown-position op driving only a box goes to logic") {
      val m = module(
        Seq(node("fsm$M$switch_state$0", NodeKind.Fsm), node("u", NodeKind.Op)),
        Seq(w("fsm$M$switch_state$0.u", "u")))
      val gs = LogicGroups.assign(m, index)
      assert(gs.map(_.id) == Seq("blk$M$logic"), ids(gs.head) == Set("u"))
    }
    test("index gets the scala class, not the type") {
      val m = module(Seq(node("a", NodeKind.Op, at(10)))).copy(`type` = "M_1")
      assert(LogicGroups.assign(m, index).map(_.id) == Seq("blk$M$gen$1"))
      assert(LogicGroups.assign(m.copy(scalaClass = ""), index).map(_.id) == Seq("blk$M$logic"))
    }
    test("no logic, no groups") {
      assert(LogicGroups.assign(module(Nil), index).isEmpty)
    }
  }
}
