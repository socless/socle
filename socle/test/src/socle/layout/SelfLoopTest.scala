package socle.layout

import socle.schema._

import utest._

/** Self-loops (a register holding its value: `r~q` wired back to `r`) are
  * routed by socle, not by ELK. ELK's self-loop router iterates identity-hashed
  * sets, so its routes, and the room it left around the box, changed with
  * whatever the JVM had hashed first: the same block laid out differently from
  * one LSP session to the next. */
object SelfLoopTest extends TestSuite {

  private val design = {
    val top = ModuleDef("Top", "Top", "", SourceRef.unknown,
      Seq(PortDef("x", PortDir.In, "UInt<8>", "", SourceRef.unknown), PortDef("y", PortDir.Out, "UInt<8>", "", SourceRef.unknown)),
      Nil,
      Seq(
        WireDef("r", "x", "UInt<8>", "", SourceRef.unknown),
        WireDef("r", "r~q", "UInt<8>", "", SourceRef.unknown), // the hold: Q back into D
        WireDef("y", "r~q", "UInt<8>", "", SourceRef.unknown),
      ),
      Metrics(1, 3, 0),
      logic = Seq(LogicNodeDef("r", NodeKind.Reg, "r", "UInt<8>", SourceRef.unknown)))
    Design("Top", Map.empty, Seq(top))
  }
  private val top = design.modules.head

  val tests = Tests {
    test("the hold is a self-loop, kept apart from what ELK lays out") {
      val (loops, rest) = LayoutCore.splitSelfLoops(LayoutCore.prepare(top, design))
      assert(loops.map(e => e.toPath -> e.fromPath) == Seq("r~q" -> "r"))
      assert(rest.nonEmpty && rest.forall(e => !(e.toPath == "r~q" && e.fromPath == "r")))
    }
    test("its route runs out of Q, under the register and back into D") {
      val l    = Layout.layout(top, design)
      val reg  = l.nodes.find(_.id == "r").get
      val loop = l.edges.find(e => e.from == "r" && e.to == "r~q").get
      val q    = reg.ports.find(_.path == "r~q").get
      val d    = reg.ports.find(_.path == "r").get
      val want = LayoutCore.selfLoopRoute((q.x.toDouble, q.y.toDouble, q.side), (d.x.toDouble, d.y.toDouble, d.side),
        (reg.x.toDouble, reg.y.toDouble, reg.w.toDouble, reg.h.toDouble), 0).map { case (x, y) => (x.toInt, y.toInt) }
      assert(loop.points == want)
      assert(loop.points.head == (q.x, q.y), loop.points.last == (d.x, d.y))
      // Under the box, and inside the canvas.
      assert(loop.points.map(_._2).max > reg.y + reg.h, loop.points.map(_._2).max < l.h)
    }
    test("loops on one box stack instead of overlapping") {
      val a = LayoutCore.selfLoopRoute((100, 50, PinSide.East), (0, 50, PinSide.West), (0, 0, 100, 80), 0)
      val b = LayoutCore.selfLoopRoute((100, 60, PinSide.East), (0, 60, PinSide.West), (0, 0, 100, 80), 1)
      assert(b.map(_._2).max > a.map(_._2).max)
    }
  }
}
