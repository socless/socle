package socle

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

object HarnessTest extends TestSuite {
  val tests = Tests {
    test("harness runs") {
      assert(1 + 1 == 2)
    }
  }
}
