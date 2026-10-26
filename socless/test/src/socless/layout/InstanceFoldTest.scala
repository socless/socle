package socless.layout

import socless.schema._
import utest._

object InstanceFoldTest extends TestSuite {
  private def inst(name: String, tpe: String, group: String = "") =
    InstanceDef(name, tpe, "", SourceRef.unknown, foldGroup = group)
  private def wire(from: String, to: String, label: String = "") =
    WireDef(from, to, "UInt<8>", label, SourceRef.unknown)
  private def module(children: Seq[InstanceDef], wires: Seq[WireDef]) =
    ModuleDef("Top", "Top", "", SourceRef.unknown, Nil, children, wires, Metrics(0, 0, 0))

  private val kids = Seq(
    inst("cores_0", "Core", "cores"),
    inst("cores_1", "Core_1", "cores"),
    inst("q", "Q"),
  )

  val tests = Tests {
    test("folds a group into one instance") {
      val f = InstanceFold.fold(module(kids, Nil), Set.empty)
      assert(f.children.map(_.name) == Seq("cores", "q"))
      val c = f.children.head
      assert(c.`type` == "Core" && c.foldCount == 2 && c.foldGroup == "")
    }

    test("retargets and merges wires") {
      val m = module(kids, Seq(wire("cores_0.io.in", "io.a"), wire("cores_1.io.in", "io.a")))
      val f = InstanceFold.fold(m, Set.empty)
      assert(f.wires.map(w => (w.from, w.to, w.label)) == Seq(("cores.io.in", "io.a", "2×")))
      val m2 = module(kids, Seq(wire("cores_0.io.in", "io.a", "3× ground"), wire("cores_1.io.in", "io.a", "1× ground")))
      assert(InstanceFold.fold(m2, Set.empty).wires.map(_.label) == Seq("4× ground"))
    }

    test("a single wire is untouched") {
      val m = module(kids, Seq(wire("cores_0.io.in", "io.a", "3× ground")))
      assert(InstanceFold.fold(m, Set.empty).wires.map(w => (w.from, w.label)) == Seq(("cores.io.in", "3× ground")))
    }

    test("drops wires inside a folded group") {
      val m = module(kids, Seq(wire("cores_1.io.in", "cores_0.io.out")))
      assert(InstanceFold.fold(m, Set.empty).wires.isEmpty)
      assert(InstanceFold.fold(m, Set("cores")).wires.size == 1)
    }

    test("vec-indexed member ports retarget") {
      val m = module(kids, Seq(wire("cores_1.io.in[2]", "io.b")))
      assert(InstanceFold.fold(m, Set.empty).wires.map(_.from) == Seq("cores.io.in[2]"))
    }

    test("unfolded group and unknown ids") {
      val m = module(kids, Seq(wire("cores_0.io.in", "io.a")))
      assert(InstanceFold.fold(m, Set("cores", "nope")) == m)
    }

    test("only the groups not unfolded fold") {
      val two = kids :+ inst("dot_0", "Dot", "dot") :+ inst("dot_1", "Dot_1", "dot")
      val f = InstanceFold.fold(module(two, Nil), Set("cores"))
      assert(f.children.map(_.name) == Seq("cores_0", "cores_1", "q", "dot"))
      assert(f.children.map(_.foldCount) == Seq(0, 0, 0, 2))
    }

    test("idempotent and no-op without groups") {
      val m = module(kids, Seq(wire("cores_0.io.in", "io.a"), wire("cores_1.io.in", "io.a")))
      val once = InstanceFold.fold(m, Set.empty)
      assert(InstanceFold.fold(once, Set.empty) == once)
      val plain = module(Seq(inst("a", "A"), inst("b", "B")), Seq(wire("a.x", "b.y")))
      assert(InstanceFold.fold(plain, Set.empty) == plain)
    }

    test("tie-offs on a folded copy land on the group") {
      val ties = Seq(TieOff("cores_0.io.op", "0"), TieOff("cores_1.io.op", "0"), TieOff("cores_1.io.en[1]", "1"), TieOff("q.x", "2"))
      val m = module(kids, Nil).copy(tieOffs = ties)
      // The copies' identical ties merge; another value or pin stays its own.
      assert(InstanceFold.fold(m, Set.empty).tieOffs ==
        Seq(TieOff("cores.io.op", "0"), TieOff("cores.io.en[1]", "1"), TieOff("q.x", "2")))
      // Unfolded members keep their own pins.
      assert(InstanceFold.fold(m, Set("cores")).tieOffs == ties)
    }

    test("groups lists members in child order") {
      assert(InstanceFold.groups(module(kids, Nil)) == Seq("cores" -> Seq("cores_0", "cores_1")))
    }

    test("old json parses") {
      val json = """{"name":"a","type":"A","doc":"","source":{"file":"","line":0,"col":0}}"""
      val i = upickle.default.read[InstanceDef](json)
      assert(i.foldGroup == "" && i.foldCount == 0)
    }
  }
}
