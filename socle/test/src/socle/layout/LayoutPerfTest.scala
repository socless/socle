package socle.layout

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

/** Measurement harness for issue #31 (scale + performance). Builds a synthetic
  * design that mimics a large SoC - many structurally-identical "cluster"
  * modules (as Chisel emits X, X_1, X_2, ... for repeated parameterized
  * modules) - and reports layout time and design.json size.
  *
  * Not a hard pass/fail perf gate (timings are machine-dependent); it asserts
  * only that the layout completes and prints numbers to stdout. Run with:
  *   ./mill socle.test socle.LayoutPerfTest
  */
object LayoutPerfTest extends TestSuite {

  // A leaf module with one AXI4 port (no children): cheap to lay out.
  private def leaf(name: String) =
    ModuleDef(name, name, "", SourceRef.unknown,
      ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
      children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  /** Build a design with `clusters` structurally-identical cluster modules,
    * each instantiating `width` leaves chained by AXI wires, under one top. */
  private def bigDesign(clusters: Int, width: Int): Design = {
    val mods = scala.collection.mutable.ArrayBuffer.empty[ModuleDef]
    mods += leaf("Leaf")
    // Each cluster: width instances l0..l(width-1) of Leaf, chained l0->l1->...
    val clusterNames = (0 until clusters).map(i => if (i == 0) "Cluster" else s"Cluster_$i")
    for (cn <- clusterNames) {
      val insts = (0 until width).map(j => InstanceDef(s"l$j", "Leaf", "", SourceRef.unknown))
      val wires = (0 until width - 1).map(j =>
        WireDef(s"l${j + 1}.io", s"l$j.io", "AXI4", "", SourceRef.unknown))
      mods += ModuleDef(cn, cn, "", SourceRef.unknown,
        ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
        children = insts, wires = wires, metrics = Metrics(0, 0, 0))
    }
    // Top: instantiate every cluster, chain them.
    val topInsts = clusterNames.zipWithIndex.map { case (cn, i) => InstanceDef(s"c$i", cn, "", SourceRef.unknown) }
    val topWires = (0 until clusters - 1).map(i =>
      WireDef(s"c${i + 1}.io", s"c$i.io", "AXI4", "", SourceRef.unknown))
    mods += ModuleDef("Top", "Top", "", SourceRef.unknown,
      ports = Nil, children = topInsts, wires = topWires, metrics = Metrics(0, 0, 0))
    Design("Top", Map("AXI4" -> BundleDef("AXI4", "Bundle")), mods.toSeq)
  }

  private def timeMs[T](body: => T): (T, Long) = {
    val t0 = System.nanoTime()
    val r = body
    (r, (System.nanoTime() - t0) / 1000000L)
  }

  val tests = Tests {
    test("measure layout time and JSON size at scale") {
      Layout.withLayouts(bigDesign(20, 8)) // warm up JIT + ELK registration
      for ((clusters, width) <- Seq((100, 8), (300, 8), (600, 12))) {
        val d = bigDesign(clusters, width)
        val nModules = d.modules.size
        val (laid, ms) = timeMs(Layout.withLayouts(d))
        val json = upickle.default.write(laid)
        println(f"[perf] modules=$nModules%-5d layout=${ms}%5dms json=${json.length / 1024}%5dKB " +
          f"stored-layouts=${laid.layouts.size}%-4d (dedup from $nModules%d)")
        // Every module must resolve to a stored layout.
        assert(laid.layoutOf.size == nModules)
        assert(d.modules.forall(m => laid.layouts.contains(laid.layoutOf(m.name))))
        // Dedup must collapse the identical clusters: far fewer stored than total.
        assert(laid.layouts.size < nModules)
      }
    }

    test("dedup is sound: every module's resolved layout equals a direct layout") {
      val d = bigDesign(40, 6)
      val laid = Layout.withLayouts(d)
      // For a sample of modules, the deduped (shared) layout must be byte-identical
      // to laying that module out on its own.
      for (m <- Seq("Leaf", "Cluster", "Cluster_17", "Cluster_39", "Top")) {
        val mod = d.modules.find(_.name == m).get
        val shared = laid.layouts(laid.layoutOf(m))
        val direct = Layout.layout(mod, d)
        assert(upickle.default.write(shared) == upickle.default.write(direct))
      }
    }
  }
}
