package socless.layout

import socless.schema._
import socless.parse._
import socless.ir.IrWalk
import utest._

/** Verification-layer wires must hide together with their layer nodes (issue
  * #212 follow-up). The UI hides a layer node's BOX by its node id, but a wire
  * into that node keeps a synthetic pin head (`L~in<i>`, `L~q`) that never
  * matches the bare id: the box vanishes while its wire renders as a dangling
  * stub. [[LayoutCore.isLayerHead]] resolves a wire-endpoint head back to its
  * owning node id so the two stay in lockstep. */
object LayerWireTest extends TestSuite {

  private def fixtureSource: String = {
    val url = getClass.getResource("/chirrtl/layers.fir")
    scala.io.Source.fromURL(url, "UTF-8").mkString
  }

  // The head a wire endpoint reduces to in the UI: `path.split('.').head`, the
  // same value stored as Wire.fromInst / Wire.toInst.
  private def head(path: String): String = path.split('.').headOption.getOrElse("")

  val tests = Tests {
    test("isLayerHead resolves synthetic pin heads to their owning node id") {
      val layerIds = Set("_T", "_T_1", "const$_T_1~in1")
      // Op input pins and reg Q pins of a layer node count as layer.
      assert(LayoutCore.isLayerHead("_T~in0", layerIds))
      assert(LayoutCore.isLayerHead("_T~in1", layerIds))
      assert(LayoutCore.isLayerHead("_T~q", layerIds))
      // The bare node id and an exact const id match directly.
      assert(LayoutCore.isLayerHead("_T", layerIds))
      assert(LayoutCore.isLayerHead("const$_T_1~in1", layerIds))
      // Design signals (not in the set) never match, even with a pin suffix.
      assert(!LayoutCore.isLayerHead("count~q", layerIds))
      assert(!LayoutCore.isLayerHead("io", layerIds))
      // A const id legitimately contains `~in`; it must match by exact id only,
      // never reduce via baseOf to a different (non-const) layer id.
      assert(!LayoutCore.isLayerHead("const$_T_1~in1", Set("const$_T_1")))
    }

    test("no layer wire survives the hide filter (no dangling stubs)") {
      val design = IrWalk.design(ChirrtlParser.parse(fixtureSource))
      val mod    = design.modules.find(_.name == "LayeredCounter").get
      val layerIds = mod.logic.iterator.filter(_.layer).map(_.id).toSet
      assert(layerIds.nonEmpty) // fixture really has layer content

      val dws = LayoutCore.displayWires(mod, design)
      // The UI hide rule, with the resolver under test.
      def hidden(w: LayoutCore.DisplayWire) =
        LayoutCore.isLayerHead(head(w.from), layerIds) || LayoutCore.isLayerHead(head(w.to), layerIds)
      val visible = dws.filterNot(hidden)

      // Every visible wire must have BOTH endpoints resolve to a non-layer node:
      // a surviving endpoint whose base is a hidden layer node is a dangling stub.
      def baseLayer(p: String) = layerIds.contains(LayoutCore.nodeBaseId(head(p)))
      val dangling = visible.filter(w => baseLayer(w.from) || baseLayer(w.to))
      assert(dangling.isEmpty)

      // And the filter actually removed the layer wires (it is not a no-op): the
      // assert/assume/cover condition ops each feed off design signals.
      assert(dws.size > visible.size)

      // Regression: the pre-fix UI rule matched the bare node id against the raw
      // pin head, so a layer op's input wires (`_T~in0 -> count~q`) stayed
      // visible while the op box was hidden - the dangling stubs this fixes.
      val naiveVisible = dws.filterNot(w =>
        layerIds.contains(head(w.from)) || layerIds.contains(head(w.to)))
      val naiveDangling = naiveVisible.filter(w => baseLayer(w.from) || baseLayer(w.to))
      assert(naiveDangling.nonEmpty)
    }
  }
}
