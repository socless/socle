package socless

import utest._

object LayoutTest extends TestSuite {
  // Top: producer `cpu` (Cpu.io master) -> consumer `mem` (Mem.io slave),
  // plus a boundary output `done`.
  private val cpuMod = ModuleDef("Cpu", "Cpu", "", SourceRef.unknown,
    Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val memMod = ModuleDef("Mem", "Mem", "", SourceRef.unknown,
    Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports = Seq(PortDef("done", "output", "UInt<1>", "", SourceRef.unknown)),
    children = Seq(InstanceDef("cpu", "Cpu", "", SourceRef.unknown),
                   InstanceDef("mem", "Mem", "", SourceRef.unknown)),
    wires = Seq(
      WireDef("cpu.io", "mem.io", "AXI4", "31x ground connects", SourceRef.unknown),
      WireDef("mem.io", "done", "UInt<1>", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))
  private val design = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, cpuMod, memMod))

  val tests = Tests {
    val L = Layout.layout(topMod, design)

    test("every instance and boundary port becomes a node") {
      val ids = L.nodes.map(_.id).toSet
      assert(ids == Set("cpu", "mem", "done"))
      assert(L.nodes.find(_.id == "done").get.kind == "boundary")
    }
    test("boxes do not overlap pairwise") {
      val bs = L.nodes
      for (i <- bs.indices; j <- (i + 1) until bs.length) {
        val a = bs(i); val b = bs(j)
        val overlap = a.x < b.x + b.w && b.x < a.x + a.w &&
                      a.y < b.y + b.h && b.y < a.y + a.h
        assert(!overlap)
      }
    }
    test("each wire becomes a routed edge with >= 2 points") {
      assert(L.edges.length == 2)
      assert(L.edges.forall(_.points.length >= 2))
      val axi = L.edges.find(_.bundleIsAxi).get
      assert(axi.label.contains("ground"))
    }
    test("the cpu<->mem pin is marked as an interface pin") {
      val cpuPort = L.nodes.find(_.id == "cpu").get.ports.find(_.path == "cpu.io").get
      assert(cpuPort.interface && cpuPort.side == "EAST")
    }
    test("canvas size is positive") {
      assert(L.w > 0 && L.h > 0)
    }
  }

  private implicit class EdgeOps(e: RoutedEdge) {
    def bundleIsAxi: Boolean = e.from == "cpu.io" && e.to == "mem.io"
  }
}
