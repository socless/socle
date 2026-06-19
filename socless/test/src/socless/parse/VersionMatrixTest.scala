package socless.parse

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

/** Chisel version compatibility matrix (issue #119).
  *
  * The same trivial design ("Mini": two 8-bit inputs summed into a registered
  * output) is committed as CHIRRTL across the syntax eras the parser claims to
  * support:
  *
  *   - `chisel3.fir` - SFC / legacy FIRRTL: bare `module`, `lhs <= rhs`
  *     connects, single-line `reg ... with : (reset => ...)`, `UInt<1>("h0")`
  *     literals, no `FIRRTL version` header.
  *   - `chisel5.fir` - early CIRCT: `module` (no `public`), `connect lhs, rhs`,
  *     `regreset`, `UInt<8>(0h0)` literals, `FIRRTL version 3.0.0`.
  *   - `chisel6.fir` - `public module` but no `layer` block yet, `FIRRTL
  *     version 4.0.0` (the shape between chisel5 and chisel7).
  *   - `chisel7.fir` - modern: `public module`, a `layer` block, annotation
  *     payload after the circuit, `FIRRTL version 6.0.0`.
  *
  * The contract: all three parse to the *same* SoCLess topology. This pins the
  * cross-version portability promise in README's compatibility notes with an
  * executable regression, so a parser change that silently drops the legacy
  * `<=` form or the `reg ... with` reset can't pass CI.
  */
object VersionMatrixTest extends TestSuite {

  private val variants = Seq("chisel3", "chisel5", "chisel6", "chisel7")

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/versions/$name.fir")
    require(s != null, s"missing classpath resource /chirrtl/versions/$name.fir")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def designOf(name: String): Design =
    IrWalk.design(ChirrtlParser.parse(readFixture(name)))

  /** A source-independent structural signature: everything that defines the
    * topology, with SourceRefs and docs deliberately excluded (they legitimately
    * differ line-for-line between emitter versions). */
  private def signature(d: Design): (String, Seq[(String, Seq[(String, String, String)], Int)]) = {
    val mods = d.modules
      .map { m =>
        val ports = m.ports.map(p => (p.name, p.dir, p.bundle)).sorted
        (m.name, ports, m.metrics.registers)
      }
      .sortBy(_._1)
    (d.top, mods)
  }

  val tests = Tests {
    test("every version variant parses to a non-empty Mini design") {
      for (v <- variants) {
        val d = designOf(v)
        assert(d.top == "Mini")
        assert(d.modules.sizeIs == 1)
        val m = d.modules.head
        assert(m.name == "Mini")
        // clock, reset, io
        assert(m.ports.map(_.name).sorted == Seq("clock", "io", "reset"))
        // the registered output `r`
        assert(m.metrics.registers == 1)
      }
    }

    test("the io bundle exposes a, b, c with consistent shape across versions") {
      for (v <- variants) {
        val d  = designOf(v)
        val io = d.modules.head.ports.find(_.name == "io").get
        val bundle = d.bundles(io.bundle)
        // Ground members of a bundle port land in `channels` (name, type, flip).
        val chans = bundle.channels.map(c => (c.name, c.`type`, c.flip)).sortBy(_._1)
        assert(chans == Seq(
          ("a", "UInt<8>", true),   // flip in
          ("b", "UInt<8>", true),   // flip in
          ("c", "UInt<8>", false),  // out
        ))
      }
    }

    test("all syntax eras yield an identical topology signature") {
      val sigs = variants.map(v => v -> signature(designOf(v)))
      val ref  = sigs.head._2
      for ((name, sig) <- sigs.tail)
        assert(sig == ref) // (name shown in the failure context if this trips)
      // Belt and suspenders: the reference itself is the expected shape.
      assert(ref._1 == "Mini")
    }
  }
}
