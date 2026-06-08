package socless

import utest._

object HarnessTest extends TestSuite {
  val tests = Tests {
    test("harness runs") {
      assert(1 + 1 == 2)
    }
  }
}
