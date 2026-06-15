package socless

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
  }
}
