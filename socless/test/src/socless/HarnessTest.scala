package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

object HarnessTest extends TestSuite {
  val tests = Tests {
    test("harness runs") {
      assert(1 + 1 == 2)
    }
  }
}
