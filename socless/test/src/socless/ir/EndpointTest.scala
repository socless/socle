package socless.ir

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

object EndpointTest extends TestSuite {
  // A tiny design: Top contains one instance `cpu` of module `Cpu`.
  // Cpu has one output port `io` of bundle "AXI4"; Top has a boundary
  // input port `clk` (ground).
  private val cpuMod = ModuleDef(
    name = "Cpu", `type` = "Cpu", doc = "", source = SourceRef.unknown,
    ports = Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0),
  )
  private val topMod = ModuleDef(
    name = "Top", `type` = "Top", doc = "", source = SourceRef.unknown,
    ports = Seq(PortDef("clk", "input", "Clock", "", SourceRef.unknown)),
    children = Seq(InstanceDef("cpu", "Cpu", "", SourceRef.unknown)),
    wires = Nil, metrics = Metrics(0, 0, 0),
  )
  private val design = Design(
    top = "Top",
    bundles = Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    modules = Seq(topMod, cpuMod),
  )

  val tests = Tests {
    test("instance master port is on its node's EAST and is an interface") {
      val e = Layout.classify("cpu.io", topMod, design).get
      assert(e.nodeId == "cpu" && e.kind == "inst")
      assert(e.side == "EAST")
      assert(e.interface)
    }
    test("module boundary input is a boundary node on EAST (drives into design)") {
      val e = Layout.classify("clk", topMod, design).get
      assert(e.nodeId == "clk" && e.kind == "boundary")
      assert(e.side == "EAST")
      assert(!e.interface)
    }
    test("unknown endpoint returns None") {
      assert(Layout.classify("ghost.sig", topMod, design).isEmpty)
    }
  }
}
