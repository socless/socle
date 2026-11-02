package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._

import utest._

/** Chisel verification-layer handling (issue #212). A module-level condition
  * node feeding only a `layerblock` assertion is verification logic, not design
  * dataflow, and must be flagged so the UI can hide it. */
object LayerTest extends TestSuite {
  private def circ(s: String)     = ChirrtlParser.parse(s)
  private def designOf(s: String) = IrWalk.design(ChirrtlParser.parse(s))

  // `sum` is real design (drives io.o) AND is read by the assertion - it must
  // NOT be flagged (the wrap/cover regression). `cond` is computed at module
  // level but read only by the assert chain. `inner` is declared inside the
  // layerblock.
  private val src =
    """circuit M :
      |  layer Verification, bind, "verification" :
      |    layer Assert, bind, "verification/assert" :
      |  module M :
      |    input clock : Clock
      |    output io : { flip a : UInt<8>, flip b : UInt<8>, o : UInt<8>}
      |    node sum = add(io.a, io.b)
      |    connect io.o, sum
      |    node cond = leq(io.a, io.b)
      |    skip
      |    layerblock Verification :
      |      layerblock Assert :
      |        node inner = and(cond, cond)
      |        intrinsic(circt_chisel_ifelsefatal<format = "x">, clock, inner, sum)
      |""".stripMargin

  val tests = Tests {
    test("layerblock-interior node is tagged inLayer; module-level nodes are not") {
      val m = circ(src).modules.find(_.name == "M").get
      assert(m.nodes.find(_.name == "inner").get.inLayer)
      assert(!m.nodes.find(_.name == "sum").get.inLayer)
      assert(!m.nodes.find(_.name == "cond").get.inLayer)
    }
    test("layerConsumes records the signals the layer reads") {
      val m = circ(src).modules.find(_.name == "M").get
      assert(m.layerConsumes.contains("cond"))
      assert(m.layerConsumes.contains("sum")) // read by the intrinsic too
    }
    test("layer condition nodes are flagged layer=true; design nodes are not") {
      val mod = designOf(src).modules.find(_.name == "M").get
      def flag(id: String) = mod.logic.find(_.id == id).map(_.layer)
      assert(flag("inner") == Some(true)) // interior
      assert(flag("cond")  == Some(true)) // feeds only the layer (via the fixpoint)
      assert(flag("sum")   == Some(false)) // drives io.o, so design despite being layer-read
    }
    test("a module with no layer content flags nothing") {
      val plain =
        """circuit P :
          |  module P :
          |    input a : UInt<8>
          |    output o : UInt<8>
          |    node n = not(a)
          |    connect o, n
          |""".stripMargin
      val mod = designOf(plain).modules.find(_.name == "P").get
      assert(mod.logic.forall(!_.layer))
    }
  }
}
