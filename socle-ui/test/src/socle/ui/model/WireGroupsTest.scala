package socle.ui.model

import socle.schema._
import utest._

/** A drawn wire's field wires, grouped by the bundles they connect. */
object WireGroupsTest extends TestSuite {
  private def w(from: String, to: String) = WireDef(from, to, "UInt<1>", "", SourceRef.unknown)
  // `io.mem.rd(1) <> dma.io.rd(0)` and `io.mem.rd(0) <> cpu.io.rd`, field by field.
  private val ws = Seq(
    w("io.mem.rd[1].cmd.bits", "dma.io.rd[0].cmd.bits"),
    w("io.mem.rd[1].cmd.valid", "dma.io.rd[0].cmd.valid"),
    w("dma.io.rd[0].cmd.ready", "io.mem.rd[1].cmd.ready"),
    w("dma.io.rd[0].data", "io.mem.rd[1].data"),
    w("io.mem.rd[0].cmd.valid", "cpu.io.rd.cmd.valid"),
    w("cpu.io.rd.data", "io.mem.rd[0].data"))

  val tests = Tests {
    test("the fields both ends share are cut off") {
      assert(WireGroups.roots("io.mem.rd[1].cmd.bits", "dma.io.rd[0].cmd.bits", "io.mem") == ("io.mem.rd[1]", "dma.io.rd[0]"))
      assert(WireGroups.roots("io.mem.rd[0].data", "cpu.io.rd.data", "io.mem") == ("io.mem.rd[0]", "cpu.io.rd"))
    }
    test("the end stays at or below the selection") {
      assert(WireGroups.roots("io.a", "inst.io.a", "io") == ("io", "inst.io"))
      assert(WireGroups.roots("port.io.wr.cmd.bits", "io.mem.wr[0].cmd.bits", "port.io.wr") == ("port.io.wr", "io.mem.wr[0]"))
      assert(WireGroups.roots("io.ack[0]", "endpoints.io.ack", "io.ack") == ("io.ack[0]", "endpoints.io.ack"))
    }
    test("one group per connection, both directions in it") {
      val gs = WireGroups.of(ws, "io.mem")
      assert(gs.map(g => (g.end, g.far, g.wires.size)) ==
        Seq(("io.mem.rd[1]", "dma.io.rd[0]", 4), ("io.mem.rd[0]", "cpu.io.rd", 2)))
    }
    test("only the clicked wire's far end") {
      assert(WireGroups.of(ws, "io.mem", Some("cpu.io.rd")).map(_.far) == Seq("cpu.io.rd"))
    }
    test("the type at a path, under a port or an instance's port") {
      val vec = BundleDef("Rd", BundleKind.Bundle, Nil, Seq(ChannelDef("cmd", "UInt<4>", flip = false)))
      val io  = BundleDef("Io", BundleKind.Bundle, Nil, Seq(ChannelDef("rd", "Vec[Rd, 2]", flip = false)))
      val sub = ModuleDef("Sub", "Sub", "", SourceRef.unknown, Seq(PortDef("io", PortDir.Out, "Io", "", SourceRef.unknown)), Nil, Nil, Metrics(0, 0, 0))
      val top = sub.copy(name = "Top", children = Seq(InstanceDef("s", "Sub", "", SourceRef.unknown)))
      val d   = Design("Top", Map("Rd" -> vec, "Io" -> io), Seq(top, sub))
      assert(WireGroups.typeAt(top, d, "io.rd[1]") == "Rd")
      assert(WireGroups.typeAt(top, d, "s.io.rd[0]") == "Rd")
      assert(WireGroups.typeAt(top, d, "nope.x") == "")
    }
  }
}
