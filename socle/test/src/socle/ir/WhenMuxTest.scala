package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._
import ChirrtlParser.{Conn, Guard}

object WhenMuxTest extends TestSuite {
  private def c(lhs: String, rhs: String, g: Guard*): Conn =
    Conn(lhs, rhs, None, g.toSeq)
  // No registers in most cases; override per-test.
  private def run(conns: Seq[Conn], regs: Set[String] = Set.empty) =
    WhenMux.synthesize(conns, regs.contains)

  val tests = Tests {
    test("default then single when becomes one mux(sel, true, default)") {
      // connect o, d ; when c: connect o, x   =>  o = mux(c, x, d)
      val cs = Seq(c("o", "d"), c("o", "x", Guard("c", false)))
      val r = run(cs)
      assert(r.nodes.size == 1)
      val id = r.nodes.head.id
      assert(r.nodes.head.kind == "op" && r.nodes.head.op == "mux")
      assert(r.edges.contains((s"$id~in0", "c")))   // sel
      assert(r.edges.contains((s"$id~in1", "x")))   // true / "1"
      assert(r.edges.contains((s"$id~in2", "d")))   // false / "0"
      assert(r.edges.contains(("o", id)))            // mux drives the sink
      assert(r.consumed == Set(0, 1))
    }
    test("when/else becomes mux(sel, true, elseVal); default is overridden") {
      // connect o, d ; when c: connect o,x else: connect o,y  => mux(c, x, y)
      val cs = Seq(c("o", "d"), c("o", "x", Guard("c", false)), c("o", "y", Guard("c", true)))
      val r = run(cs)
      val id = r.nodes.head.id
      assert(r.edges.contains((s"$id~in1", "x")))
      assert(r.edges.contains((s"$id~in2", "y")))   // else wins over default d
      assert(!r.edges.exists { case (sink, src) => sink == s"$id~in2" && src == "d" })
    }
    test("register with no default folds the held value into the false input") {
      // reg r ; when c: connect r, x   =>  r = mux(c, x, r)
      val cs = Seq(c("r", "x", Guard("c", false)))
      val res = run(cs, regs = Set("r"))
      val id = res.nodes.head.id
      assert(res.edges.contains((s"$id~in2", "r"))) // hold = the reg's own path
    }
    test("two independent depth-1 whens build a 2-mux priority chain") {
      // reg busy ; when c1: busy:=1 ; when c2: busy:=0
      val cs = Seq(c("busy", "1", Guard("c1", false)), c("busy", "0", Guard("c2", false)))
      val res = run(cs, regs = Set("busy"))
      assert(res.nodes.size == 2)
      val outer = res.nodes.head.id            // outermost = last event (c2), highest priority
      val inner = res.nodes(1).id
      assert(res.edges.contains((s"$outer~in0", "c2")))
      assert(res.edges.contains((s"$outer~in1", "0")))
      assert(res.edges.contains((s"$outer~in2", inner)))   // false side nests the c1 mux
      assert(res.edges.contains((s"$inner~in0", "c1")))
      assert(res.edges.contains((s"$inner~in1", "1")))
      assert(res.edges.contains((s"$inner~in2", "busy")))  // innermost else = hold
      assert(res.edges.contains(("busy", outer)))
    }
    test("a depth-2 guard makes the sink ineligible (left as fan-in)") {
      // when a: when b: connect o, x   (no default, not reg) => no mux, nothing consumed
      val cs = Seq(c("o", "x", Guard("a", false), Guard("b", false)))
      val r = run(cs)
      assert(r.nodes.isEmpty && r.edges.isEmpty && r.consumed.isEmpty)
    }
    test("non-register sink with only conditional assigns and no default is ineligible") {
      val cs = Seq(c("o", "x", Guard("c", false)))
      val r = run(cs) // o is not a register and has no default
      assert(r.nodes.isEmpty && r.consumed.isEmpty)
    }
    test("last-connect-wins within identical guards keeps the later rhs") {
      // connect o, d1 ; connect o, d2 ; when c: connect o, x  => default is d2
      val cs = Seq(c("o", "d1"), c("o", "d2"), c("o", "x", Guard("c", false)))
      val r = run(cs)
      val id = r.nodes.head.id
      assert(r.edges.contains((s"$id~in2", "d2")))
      assert(!r.edges.exists { case (sink, src) => sink == s"$id~in2" && src == "d1" })
      assert(r.consumed == Set(0, 1, 2))
    }
    test("distinct sinks that flatten to the same base get unique ids") {
      // "a.b" and "a_b" both flatten to mux$a_b
      val cs = Seq(
        c("a.b", "d1"), c("a.b", "x1", Guard("c1", false)),
        c("a_b", "d2"), c("a_b", "x2", Guard("c2", false)),
      )
      val r = run(cs)
      assert(r.nodes.size == 2)
      assert(r.nodes.map(_.id).distinct.size == 2) // no id collision
    }
    test("multiple true-branch connects under the same cond keep the last") {
      // connect o, d ; when c: connect o, x1 ; when c: connect o, x2  (same cond)
      val cs = Seq(c("o", "d"), c("o", "x1", Guard("c", false)), c("o", "x2", Guard("c", false)))
      val r = run(cs)
      val id = r.nodes.head.id
      assert(r.edges.contains((s"$id~in1", "x2")))
      assert(!r.edges.exists { case (sink, src) => sink == s"$id~in1" && src == "x1" })
    }
    test("interleaved conditions: priority follows last-event source order, not first appearance") {
      // o:=d ; when c1: o:=x1 ; when c2: o:=x2 ; when c1: o:=x3
      // c1's last event (index 3) is last in source order -> c1 is highest priority (outermost).
      val cs = Seq(
        c("o", "d"),
        c("o", "x1", Guard("c1", false)),
        c("o", "x2", Guard("c2", false)),
        c("o", "x3", Guard("c1", false)),
      )
      val r = run(cs)
      assert(r.nodes.size == 2)
      val outer = r.nodes.head.id
      val inner = r.nodes(1).id
      assert(r.edges.contains((s"$outer~in0", "c1")))   // outermost selects on c1
      assert(r.edges.contains((s"$outer~in1", "x3")))   // with its last rhs
      assert(r.edges.contains((s"$outer~in2", inner)))
      assert(r.edges.contains((s"$inner~in0", "c2")))
      assert(r.edges.contains((s"$inner~in1", "x2")))
      assert(r.edges.contains((s"$inner~in2", "d")))    // innermost else = default
    }
  }
}
