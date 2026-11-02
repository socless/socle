package socle.layout

import socle.schema._

import utest._

/** Where a bundle wire carries its type (#334): the middle of its longest
  * horizontal run, when the label fits there. */
object WireLabelTest extends TestSuite {
  val tests = Tests {
    test("the middle of the longest horizontal run") {
      val pts = Seq((0.0, 0.0), (100.0, 0.0), (100.0, 50.0), (400.0, 50.0))
      assert(LayoutCore.wireLabelAt(pts, 60) == Some((250.0, 50.0)))
    }
    test("none when the label does not fit") {
      val pts = Seq((0.0, 0.0), (100.0, 0.0), (100.0, 50.0), (400.0, 50.0))
      assert(LayoutCore.wireLabelAt(pts, 400).isEmpty)
      assert(LayoutCore.wireLabelAt(Seq((0.0, 0.0)), 10).isEmpty)
    }
    test("a Vec of bundles is labelled with its element") {
      val d = Design("T", Map("AXI4" -> BundleDef("AXI4", "Bundle")), Nil)
      assert(BundleTree.displayName("Vec[AXI4, 2]", d) == "Vec[AXI4, 2]")
    }
  }
}
