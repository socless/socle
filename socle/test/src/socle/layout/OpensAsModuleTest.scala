package socle.layout

import socle.schema._

import utest._

/** Which instances open as a module on a double-click ([[LayoutCore.opensAsModule]]):
  * a leaf module with registers, logic or wires opens like one with children. */
object OpensAsModuleTest extends TestSuite {
  private val port = PortDef("io.a", PortDir.In, "UInt<1>", "", SourceRef.unknown)
  private def mod(children: Seq[InstanceDef] = Nil, wires: Seq[WireDef] = Nil,
                  logic: Seq[LogicNodeDef] = Nil, kind: String = ModuleKind.Internal) =
    ModuleDef("M", "M", "", SourceRef.unknown, Seq(port), children, wires, Metrics(0, 0, 0), kind = kind, logic = logic)
  private val inst = InstanceDef("u", "Sub", "", SourceRef.unknown)
  private val wire = WireDef("io.o", "io.a", "", "", SourceRef.unknown)
  private val reg  = LogicNodeDef("r", NodeKind.Reg, "r", "", SourceRef.unknown)

  val tests = Tests {
    test("a module with instances opens") { assert(LayoutCore.opensAsModule(mod(children = Seq(inst)))) }
    test("a leaf module with a register opens") { assert(LayoutCore.opensAsModule(mod(logic = Seq(reg)))) }
    test("a leaf module with only wires opens") { assert(LayoutCore.opensAsModule(mod(wires = Seq(wire)))) }
    test("ports alone do not open") { assert(!LayoutCore.opensAsModule(mod())) }
    test("a blackbox or an intrinsic never opens") {
      assert(!LayoutCore.opensAsModule(mod(logic = Seq(reg), wires = Seq(wire), kind = ModuleKind.External)))
      assert(!LayoutCore.opensAsModule(mod(children = Seq(inst), kind = ModuleKind.Intrinsic)))
    }
  }
}
