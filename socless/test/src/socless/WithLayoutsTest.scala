package socless

import utest._

object WithLayoutsTest extends TestSuite {
  private val cpuMod = ModuleDef("Cpu", "Cpu", "", SourceRef.unknown,
    Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown, Nil,
    Seq(InstanceDef("cpu", "Cpu", "", SourceRef.unknown)),
    Seq(WireDef("cpu.io", "cpu.io", "AXI4", "", SourceRef.unknown)), Metrics(0,0,0))
  private val design = Design("Top", Map("AXI4" -> BundleDef("AXI4","Bundle")), Seq(topMod, cpuMod))

  val tests = Tests {
    test("withLayouts adds a layout entry per module") {
      val out = Layout.withLayouts(design)
      assert(out.layouts.contains("Top"))
      assert(out.layouts.contains("Cpu"))
    }
    test("a failing module does not abort the whole export") {
      // a wire referencing a child whose module is absent must not throw
      val broken = topMod.copy(wires = Seq(WireDef("ghost.x", "ghost.y", "Wire", "", SourceRef.unknown)))
      val d2 = design.copy(modules = Seq(broken, cpuMod))
      val out = Layout.withLayouts(d2)         // must not throw
      assert(out.layouts.contains("Cpu"))
    }
  }
}
