package socless.layout

import utest._
import socless.schema._

object PinBundleTest extends TestSuite {

  // A child `c` (type CType) whose port `io` is a Bundle {a:out, b:out, c:in};
  // its fields scatter to three different scalar peers x, y, z. This is the
  // #237 case: each scattered field becomes its own fieldLevel WireDef.
  private def design: Design = {
    val ioBundle = BundleDef(
      name = "IoB",
      kind = BundleKind.Bundle,
      channels = Seq(
        ChannelDef("a", "UInt<8>", flip = false),
        ChannelDef("b", "UInt<8>", flip = false),
        ChannelDef("c", "UInt<8>", flip = true), // flipped -> input on the producer
      ),
    )
    val cType = ModuleDef(
      name = "CType",
      `type` = "CType",
      doc = "",
      source = SourceRef.unknown,
      ports = Seq(PortDef("io", PortDir.Out, "IoB", "", SourceRef.unknown)),
      children = Nil,
      wires = Nil,
      metrics = Metrics(0, 0, 0),
    )
    val peer = ModuleDef(
      name = "Peer",
      `type` = "Peer",
      doc = "",
      source = SourceRef.unknown,
      ports = Seq(PortDef("p", PortDir.In, "UInt<8>", "", SourceRef.unknown)),
      children = Nil,
      wires = Nil,
      metrics = Metrics(0, 0, 0),
    )
    val top = ModuleDef(
      name = "Top",
      `type` = "Top",
      doc = "",
      source = SourceRef.unknown,
      ports = Nil,
      children = Seq(
        InstanceDef("c", "CType", "", SourceRef.unknown),
        InstanceDef("x", "Peer", "", SourceRef.unknown),
        InstanceDef("y", "Peer", "", SourceRef.unknown),
        InstanceDef("z", "Peer", "", SourceRef.unknown),
      ),
      wires = Seq(
        // from = sink, to = source; fieldLevel marks the scatter (issue #182).
        WireDef(from = "x.p", to = "c.io.a", bundle = "IoB", label = "", source = SourceRef.unknown, fieldLevel = true),
        WireDef(from = "y.p", to = "c.io.b", bundle = "IoB", label = "", source = SourceRef.unknown, fieldLevel = true),
        WireDef(from = "c.io.c", to = "z.p", bundle = "IoB", label = "", source = SourceRef.unknown, fieldLevel = true),
      ),
      metrics = Metrics(0, 0, 0),
    )
    Design(top = "Top", bundles = Map("IoB" -> ioBundle), modules = Seq(top, cType, peer))
  }

  val tests = Tests {
    test("peer pin carries its own port type, not the scattered source bundle") {
      val d    = design
      val top  = d.modules.find(_.name == "Top").get
      val prep = LayoutCore.prepare(top, d)
      def pin(node: String, path: String) =
        prep.nodes.find(_.id == node).flatMap(_.pins.find(_.path == path))
          .getOrElse(sys.error(s"no pin $path on $node"))
      // Peer x's pin is its own scalar port: NOT "IoB".
      assert(pin("x", "x.p").bundle != "IoB")
      assert(pin("x", "x.p").bundle == "UInt<8>")
      // The owning box keeps the bundle on its field pins.
      assert(pin("c", "c.io.a").bundle == "IoB")
    }
  }
}
