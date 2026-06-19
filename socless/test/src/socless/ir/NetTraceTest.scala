package socless.ir

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

object NetTraceTest extends TestSuite {
  // edges are (sink, source) = CHIRRTL (lhs, rhs); resolve returns (source, sink).
  private val ext = Set("a", "b", "p", "q", "x", "y", "d")
  private def isExt(s: String): Boolean = ext.contains(s.split('.').head)

  val tests = Tests {
    test("direct external-to-external connect") {
      assert(NetTrace.resolve(Seq(("b", "a")), isExt) == Seq(("a", "b")))
    }
    test("trace through an internal net") { // a -> _t -> b
      assert(NetTrace.resolve(Seq(("_t", "a"), ("b", "_t")), isExt) == Seq(("a", "b")))
    }
    test("trace through a register (pass-through)") { // d -> r -> q
      assert(NetTrace.resolve(Seq(("r", "d"), ("q", "r")), isExt) == Seq(("d", "q")))
    }
    test("fan-out: one source reaches several external sinks") {
      assert(NetTrace.resolve(Seq(("_t", "a"), ("x", "_t"), ("y", "_t")), isExt) == Seq(("a", "x"), ("a", "y")))
    }
    test("combinational cycle terminates") {
      assert(NetTrace.resolve(Seq(("_t", "a"), ("_u", "_t"), ("_t", "_u"), ("b", "_t")), isExt) == Seq(("a", "b")))
    }
    test("scatter: two sources to two sinks") {
      assert(NetTrace.resolve(Seq(("p", "a"), ("q", "b")), isExt) == Seq(("a", "p"), ("b", "q")))
    }
    test("internal-only path reaching no external sink yields nothing") {
      assert(NetTrace.resolve(Seq(("_t", "a"), ("_u", "_t")), isExt) == Seq.empty)
    }
    test("plumbing node is contracted, logic input pin is a terminal") {
      // a -> _T~in0 (logic input), and x -> _p -> _T~in1 where _p is plumbing.
      val ext = Set("a", "x", "_T~in0", "_T~in1", "_T")
      def isT(s: String) = ext.contains(s)
      val r = NetTrace.resolve(Seq(("_T~in0", "a"), ("_p", "x"), ("_T~in1", "_p")), isT)
      assert(r.contains(("a", "_T~in0")))
      assert(r.contains(("x", "_T~in1")))   // traced through _p
    }
    test("two-hop plumbing chain contracts to direct terminal-to-terminal edge") {
      // a -> _p1 -> _p2 -> _T~in0; _p1 and _p2 are non-terminal (plumbing), a and _T~in0 are terminal.
      val terms = Set("a", "_T~in0")
      def isT(s: String) = terms.contains(s)
      val r = NetTrace.resolve(Seq(("_p1", "a"), ("_p2", "_p1"), ("_T~in0", "_p2")), isT)
      assert(r == Seq(("a", "_T~in0")))
    }
  }
}
