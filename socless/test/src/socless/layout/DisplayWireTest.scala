package socless.layout

import socless.schema._
import utest._

/** The schematic's display wires must describe the SAME connections the layout
  * routes (issue #258). The old `displayWires` folded anti-parallel pairs by
  * NODE HEAD (`path.split('.')(0)`), but each external pin is its own per-path
  * boundary node (issue #68), so every `io.*` pin collapsed under one `io`
  * node: all but the first connection per direction was silently absorbed into
  * a bidirectional wire on another pin, leaving real inputs unconnected in the
  * UI while the debug-layout SVG (which reflects `prepare`) drew them. Folding by
  * exact pin pair, like [[LayoutCore.prepare]], keeps them aligned. */
object DisplayWireTest extends TestSuite {

  // A component with two inputs and one output.
  private val compMod = ModuleDef("Comp", "Comp", "", SourceRef.unknown,
    Seq(PortDef("in1", "in", "Wire", "", SourceRef.unknown),
        PortDef("in2", "in", "Wire", "", SourceRef.unknown),
        PortDef("out1", "out", "Wire", "", SourceRef.unknown)),
    Nil, Nil, Metrics(0, 0, 0))

  // Top exposes a bundle port `io`; its leaf pins (io.in1/io.in2/io.out1) each
  // become their own per-path boundary node (issue #68). Two boundary->comp
  // wires and one comp->boundary wire all sit between the heads "comp" and "io",
  // so the old node-head fold lumped them: the comp->io output connection got
  // absorbed and vanished from the render.
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "io", "TopIo", "", SourceRef.unknown)),
    children = Seq(InstanceDef("comp", "Comp", "", SourceRef.unknown)),
    wires = Seq(
      // from = sink, to = source.
      WireDef("comp.in1", "io.in1", "Wire", "", SourceRef.unknown),   // io drives comp.in1
      WireDef("comp.in2", "io.in2", "Wire", "", SourceRef.unknown),   // io drives comp.in2
      WireDef("io.out1", "comp.out1", "Wire", "", SourceRef.unknown), // comp drives io.out1
    ),
    metrics = Metrics(0, 0, 0))

  private val design = Design("Top", Map("TopIo" -> BundleDef("TopIo", "Bundle")),
    Seq(topMod, compMod))

  // Unordered {endpoint, endpoint} pairs: the connection a wire/edge represents
  // regardless of orientation.
  private def pairsOf(ds: Seq[LayoutCore.DisplayWire]) =
    ds.map(d => Set(d.from, d.to)).toSet
  private def edgePairs(p: LayoutCore.Prepared) =
    p.edges.map(e => Set(e.fromPath, e.toPath)).toSet

  val tests = Tests {
    test("display wires match the routed edge set pin-for-pin") {
      val dws   = LayoutCore.displayWires(topMod, design)
      val edges = LayoutCore.prepare(topMod, design)
      // Every routed connection has exactly one display wire and vice versa.
      assert(pairsOf(dws) == edgePairs(edges))
      // Concretely: all three pin pairs are present (the bug dropped comp.out1).
      assert(pairsOf(dws).contains(Set("io.out1", "comp.out1")))
      assert(pairsOf(dws).contains(Set("comp.in1", "io.in1")))
      assert(pairsOf(dws).contains(Set("comp.in2", "io.in2")))
      assert(dws.size == 3)
    }

    test("genuine anti-parallel pin pair folds to one bidirectional wire") {
      // Same two pins connected both ways collapse into a single bidir wire,
      // matching prepare's anti-parallel collapse.
      val biTop = ModuleDef("Bi", "Bi", "", SourceRef.unknown,
        ports = Nil,
        children = Seq(InstanceDef("a", "Comp", "", SourceRef.unknown),
                       InstanceDef("b", "Comp", "", SourceRef.unknown)),
        wires = Seq(
          WireDef("a.in1", "b.out1", "Wire", "", SourceRef.unknown),
          WireDef("b.out1", "a.in1", "Wire", "", SourceRef.unknown),
        ),
        metrics = Metrics(0, 0, 0))
      val dws = LayoutCore.displayWires(biTop, design)
      assert(dws.size == 1)
      assert(dws.head.bidir)
    }
  }
}
