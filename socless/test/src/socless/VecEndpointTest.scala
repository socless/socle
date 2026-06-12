package socless

import utest._

/** Indexed vec endpoints must resolve to the ELEMENT type, not the whole vec
  * (issue #89: `xbar.io.in[0]` was typed `Vec[AXI4, 2]`, and the UI then
  * displayed the vec type on both endpoint pins of the wire).
  */
object VecEndpointTest extends TestSuite {

  private val chirrtl =
    """circuit Top :
      |  module Cpu :
      |    output io : { valid : UInt<1>, flip ready : UInt<1>}
      |
      |  module Xbar :
      |    input io : { in : { valid : UInt<1>, flip ready : UInt<1>}[2]}
      |
      |  module Top :
      |    inst c of Cpu
      |    inst x of Xbar
      |    connect x.io.in[0].valid, c.io.valid
      |    connect c.io.ready, x.io.in[0].ready
      |""".stripMargin

  val tests = Tests {

    test("wire endpoints at vec indices carry the element bundle, not the vec") {
      val design = IrWalk.design(ChirrtlParser.parse(chirrtl))
      val top    = design.modules.find(_.name == "Top").get
      val vecWires = top.wires.filter(w => w.from.contains("[0]") || w.to.contains("[0]"))
      assert(vecWires.nonEmpty)
      for (w <- vecWires) {
        assert(!w.bundle.startsWith("Vec["))
        // The element shape is {valid, flip ready}: a ReadyValid bundle that
        // must match Cpu's identically-shaped io and resolve to one shared key.
        assert(design.bundles.contains(w.bundle))
        assert(design.bundles(w.bundle).kind == "Bundle" || w.bundle.startsWith("ReadyValid"))
      }
    }

    test("identical element and port structures share one bundle definition") {
      val design = IrWalk.design(ChirrtlParser.parse(chirrtl))
      val rvKeys = design.bundles.keys.filter(_.startsWith("ReadyValid")).toSeq
      assert(rvKeys.length == 1)
    }
  }
}
