package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

object ElkSmokeTest extends TestSuite {
  val tests = Tests {
    test("ELK lays out two connected nodes side by side, no overlap") {
      val (a, b) = Layout.smokeTwoNodes()
      // direction RIGHT => the source node ends up left of the target node
      assert(a.x + a.w <= b.x + 1)
      // no vertical/horizontal overlap of the two boxes
      val overlap =
        a.x < b.x + b.w && b.x < a.x + a.w &&
        a.y < b.y + b.h && b.y < a.y + a.h
      assert(!overlap)
    }
  }
}
