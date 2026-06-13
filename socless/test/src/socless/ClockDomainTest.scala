package socless

import utest._

/** Clock/reset domain inference (issue #19).
  *
  * IrWalk annotates each instance with the parent-side signal driving its
  * clock and reset ports, so the UI can group instances that share a domain.
  * Two checks:
  *
  *   1. A real multi-clock fixture: siblings on different clocks land in
  *      different clock domains, on the same reset in the same reset domain.
  *   2. The committed example fixtures (single clock domain) all resolve every
  *      instance's clock/reset to the parent's `clock`/`reset`.
  */
object ClockDomainTest extends TestSuite {

  private def read(path: String): String = {
    val s = getClass.getResourceAsStream(path)
    require(s != null, s"missing classpath resource $path")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def designOf(path: String): Design =
    IrWalk.design(ChirrtlParser.parse(read(path)))

  val tests = Tests {
    test("siblings on different clocks get different clock domains") {
      val d   = designOf("/chirrtl/domains/multiclock.fir")
      val top = d.modules.find(_.name == "MultiClock").get
      val byInst = top.children.map(c => c.name -> c).toMap

      assert(byInst("subA").clock == "clock")
      assert(byInst("subB").clock == "altClock")
      // Distinct clock domains...
      assert(byInst("subA").clock != byInst("subB").clock)
      // ...but a shared reset domain.
      assert(byInst("subA").reset == "reset")
      assert(byInst("subB").reset == "reset")
    }

    test("single-clock example designs resolve every instance to clock/reset") {
      // deepsoc is deep and entirely single-domain: a good worst case.
      val d = designOf("/chirrtl/deepsoc.fir")
      val clockedInstances = d.modules.flatMap(_.children)
      assert(clockedInstances.nonEmpty)
      for (c <- clockedInstances) {
        assert(c.clock == "clock")
        assert(c.reset == "reset")
      }
    }
  }
}
