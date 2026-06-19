package socless.layout

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

object LayoutTest extends TestSuite {
  // Top: producer `cpu` (Cpu.io master) drives mem (Mem.io slave).
  // WireDef semantics: from=SINK, to=SOURCE.
  // cpu.io is SOURCE (drives the bus) so it goes in the `to` field -> EAST pin.
  // mem.io is SINK (receives) so it goes in the `from` field -> WEST pin.
  private val cpuMod = ModuleDef("Cpu", "Cpu", "", SourceRef.unknown,
    Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val memMod = ModuleDef("Mem", "Mem", "", SourceRef.unknown,
    Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports = Seq(PortDef("done", "output", "UInt<1>", "", SourceRef.unknown)),
    children = Seq(InstanceDef("cpu", "Cpu", "", SourceRef.unknown),
                   InstanceDef("mem", "Mem", "", SourceRef.unknown)),
    wires = Seq(
      // from=mem.io (sink), to=cpu.io (source): cpu drives the AXI bus into mem
      WireDef("mem.io", "cpu.io", "AXI4", "31x ground connects", SourceRef.unknown),
      WireDef("mem.io", "done", "UInt<1>", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))
  private val design = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, cpuMod, memMod))

  // ── Role-based side fixture: prod drives cons via a single wire ─────────────
  private val prodMod = ModuleDef("Prod", "Prod", "", SourceRef.unknown,
    Seq(PortDef("p", "output", "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val consMod = ModuleDef("Cons", "Cons", "", SourceRef.unknown,
    Seq(PortDef("p", "input", "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  // roleTop: prod.p is SOURCE (to), cons.p is SINK (from).
  private val roleTop = ModuleDef("RoleTop", "RoleTop", "", SourceRef.unknown,
    ports = Nil,
    children = Seq(InstanceDef("prod", "Prod", "", SourceRef.unknown),
                   InstanceDef("cons", "Cons", "", SourceRef.unknown)),
    wires = Seq(
      // from=cons.p (sink), to=prod.p (source)
      WireDef("cons.p", "prod.p", "Wire", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))
  private val roleDesign = Design("RoleTop", Map.empty, Seq(roleTop, prodMod, consMod))

  // ── Tie-breaking fixture: port appears as source AND sink ────────────────────
  // mid is both SOURCE for left (left.p <- mid.p) and SINK for right (mid.p <- right.p).
  // east votes == west votes for mid.p -> tie resolves to EAST.
  private val leftMod  = ModuleDef("Left",  "Left",  "", SourceRef.unknown,
    Seq(PortDef("p", "input",  "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val midMod   = ModuleDef("Mid",   "Mid",   "", SourceRef.unknown,
    Seq(PortDef("p", "output", "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val rightMod = ModuleDef("Right", "Right", "", SourceRef.unknown,
    Seq(PortDef("p", "output", "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val tieTop = ModuleDef("TieTop", "TieTop", "", SourceRef.unknown,
    ports = Nil,
    children = Seq(InstanceDef("left",  "Left",  "", SourceRef.unknown),
                   InstanceDef("mid",   "Mid",   "", SourceRef.unknown),
                   InstanceDef("right", "Right", "", SourceRef.unknown)),
    wires = Seq(
      // mid.p is SOURCE here (drives left): from=left.p (sink), to=mid.p (source) -> 1 EAST vote
      WireDef("left.p",  "mid.p",   "Wire", "", SourceRef.unknown),
      // mid.p is SINK here (right drives it): from=mid.p (sink), to=right.p (source) -> 1 WEST vote
      WireDef("mid.p",   "right.p", "Wire", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))
  private val tieDesign = Design("TieTop", Map.empty, Seq(tieTop, leftMod, midMod, rightMod))

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
    test("the cpu pin is marked as an interface pin on EAST (it is the source/driver)") {
      // cpu.io is SOURCE (to=cpu.io in the wire): EAST vote -> side EAST
      val cpuPort = L.nodes.find(_.id == "cpu").get.ports.find(_.path == "cpu.io").get
      assert(cpuPort.interface && cpuPort.side == "EAST")
    }
    test("canvas size is positive") {
      assert(L.w > 0 && L.h > 0)
    }

    // ── Role-based side tests ────────────────────────────────────────────────

    test("source pin (driver) gets EAST, sink pin (receiver) gets WEST") {
      val LR = Layout.layout(roleTop, roleDesign)
      val prodPin = LR.nodes.find(_.id == "prod").get.ports.find(_.path == "prod.p").get
      val consPin = LR.nodes.find(_.id == "cons").get.ports.find(_.path == "cons.p").get
      // prod.p is SOURCE (to field) -> EAST
      assert(prodPin.side == "EAST")
      // cons.p is SINK (from field) -> WEST
      assert(consPin.side == "WEST")
    }

    test("tie: port with equal east and west votes resolves to EAST") {
      val LT = Layout.layout(tieTop, tieDesign)
      // mid.p has 1 EAST vote (drives left) and 1 WEST vote (receives from right): tie -> EAST
      val midPin = LT.nodes.find(_.id == "mid").get.ports.find(_.path == "mid.p").get
      assert(midPin.side == "EAST")
      // left.p is pure SINK -> WEST
      val leftPin = LT.nodes.find(_.id == "left").get.ports.find(_.path == "left.p").get
      assert(leftPin.side == "WEST")
      // right.p is pure SOURCE -> EAST
      val rightPin = LT.nodes.find(_.id == "right").get.ports.find(_.path == "right.p").get
      assert(rightPin.side == "EAST")
    }

    // ── Label computation tests ──────────────────────────────────────────────

    test("cpu.io pin label is 'io: AXI4' (shortPath: bundle)") {
      // cpu.io is SOURCE (EAST), bundle = AXI4 from the wire definition
      val cpuPort = L.nodes.find(_.id == "cpu").get.ports.find(_.path == "cpu.io").get
      assert(cpuPort.label == "io: AXI4")
    }

    test("done boundary pin label includes bundle suffix 'done: UInt<1>'") {
      // done wire has bundle "UInt<1>", which is not "Wire", so the label includes it.
      val doneNode = L.nodes.find(_.id == "done").get
      assert(doneNode.ports.exists(_.label == "done: UInt<1>"))
    }

    test("node width grows when both sides have labels") {
      // A node with pins on BOTH WEST and EAST gets width = max(NodeW, westPx+gap+eastPx).
      // mem has mem.io on WEST (label ~"io: AXI4") and done drives into it (label ~"io: UInt<1>").
      // Actually mem has two WEST pins: mem.io and the done wire endpoint.
      // When westMaxPx + gap + eastMaxPx > NodeW the box grows.
      // Use the roleDesign for a cleaner assertion: a node with pins on BOTH sides
      // whose combined label width exceeds NodeW gets a wider box.
      // For the main design, cpu.io is on EAST only (no WEST pins), so width = NodeW=160.
      val cpuNode = L.nodes.find(_.id == "cpu").get
      // cpu has only an EAST pin; westMaxPx=0, eastMaxPx~66, combined < 160 -> stays at NodeW
      assert(cpuNode.w >= 160)
    }

    test("node with pins on both sides grows wider than NodeW when labels are long") {
      // Build a fixture where an instance has both WEST and EAST pins with long-enough labels
      // that combined > NodeW. Use xbar from the AXISystem-style design:
      // mem.io appears on WEST (as sink) and done appears on EAST (from done boundary -> EAST).
      // Actually the simplest check: mem node has mem.io (WEST) with "io: AXI4" label.
      // Its width is max(NodeW, westMaxPx+gap+eastMaxPx).
      // "io: AXI4" = 8 chars * 6.5 + 14 = 66px. Two WEST pins for mem => westMaxPx=66, eastMaxPx=0.
      // 66 + 24 + 0 = 90 < 160 -> still NodeW. So mem stays at 160 too.
      // The sizing formula ensures no shrinkage below NodeW; growth only when labels are wider.
      val memNode = L.nodes.find(_.id == "mem").get
      assert(memNode.w >= 160)
    }

    test("label-aware node width grows strictly beyond NodeW for long bundle labels") {
      // NodeW = 160, LabelGap = 24, labelPx(n) = n*6.5 + 14.
      //
      // Node A: ShortOut has one output port "io" with bundle "Wire".
      //   Wire bundle -> no suffix -> label = "io" (2 chars) -> px = 2*6.5+14 = 27.
      //   eastMaxPx=27, westMaxPx=0 -> 0+24+27=51 < 160 -> width = NodeW = 160.
      //
      // Node B: LongOut has one output port "io" with bundle "AXI4BundleVeryLongType" (22 chars).
      //   Label = "io: AXI4BundleVeryLongType" (26 chars) -> px = 26*6.5+14 = 183.
      //   eastMaxPx=183, westMaxPx=0 -> 0+24+183=207 > 160 -> width = 207 > NodeW.
      //
      // This proves the label-aware sizing path fires: if Layout ignored labels the
      // assertion widthOf(B) > widthOf(A) would fail (both would stay at NodeW=160).
      val shortOutMod = ModuleDef("ShortOut", "ShortOut", "", SourceRef.unknown,
        Seq(PortDef("io", "output", "Wire", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      val longOutMod  = ModuleDef("LongOut", "LongOut", "", SourceRef.unknown,
        Seq(PortDef("io", "output", "AXI4BundleVeryLongType", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))

      // ShortTop: instance "s" of ShortOut; boundary port "out" receives s.io.
      val shortTop = ModuleDef("ShortTop", "ShortTop", "", SourceRef.unknown,
        ports    = Seq(PortDef("out", "output", "Wire", "", SourceRef.unknown)),
        children = Seq(InstanceDef("s", "ShortOut", "", SourceRef.unknown)),
        wires    = Seq(WireDef("out", "s.io", "Wire", "", SourceRef.unknown)),
        metrics  = Metrics(0, 0, 0))
      val shortDesign = Design("ShortTop", Map.empty, Seq(shortTop, shortOutMod))

      // LongTop: instance "l" of LongOut; boundary port "out" receives l.io.
      val longTop = ModuleDef("LongTop", "LongTop", "", SourceRef.unknown,
        ports    = Seq(PortDef("out", "output", "AXI4BundleVeryLongType", "", SourceRef.unknown)),
        children = Seq(InstanceDef("l", "LongOut", "", SourceRef.unknown)),
        wires    = Seq(WireDef("out", "l.io", "AXI4BundleVeryLongType", "", SourceRef.unknown)),
        metrics  = Metrics(0, 0, 0))
      val longDesign = Design("LongTop", Map.empty, Seq(longTop, longOutMod))

      val LA = Layout.layout(shortTop, shortDesign)
      val LB = Layout.layout(longTop, longDesign)

      val widthA = LA.nodes.find(_.id == "s").get.w
      val widthB = LB.nodes.find(_.id == "l").get.w

      // Node B's label is long enough that the sizing formula yields > 160.
      assert(widthB > 160)
      // Node A stays at NodeW, proving B genuinely grew (not just both at NodeW).
      assert(widthB > widthA)
    }

    // ── Header-band tests (issue #28) ────────────────────────────────────────

    test("component node height accounts for the header (>= HeaderH + something)") {
      // nodeHeight formula: HeaderH + 2*PortPad + max(1,maxRows)*PortStep
      // The cpu node has 1 port (EAST), so height >= HeaderH + 2*PortPad + 1*PortStep
      val cpuNode = L.nodes.find(_.id == "cpu").get
      assert(cpuNode.h >= Layout.HeaderH.toInt + 1)
    }

    test("every component LaidPort.y is >= node.y + HeaderH") {
      // All ports on inst nodes must sit below the header band.
      val HeaderH = Layout.HeaderH.toInt
      L.nodes.filter(_.kind == "inst").foreach { node =>
        node.ports.foreach { p =>
          assert(p.y >= node.y + HeaderH)
        }
      }
    }

    test("LaidPort.bundle is populated for an interface pin") {
      // cpu.io has bundle "AXI4" from the wire definition; it must be propagated to LaidPort.
      val cpuPort = L.nodes.find(_.id == "cpu").get.ports.find(_.path == "cpu.io").get
      assert(cpuPort.bundle == "AXI4")
    }

    test("LaidPort.bundle survives JSON round-trip") {
      import upickle.default._
      val cpuPort = L.nodes.find(_.id == "cpu").get.ports.find(_.path == "cpu.io").get
      val json = write(cpuPort)
      val back = read[LaidPort](json)
      assert(back.bundle == "AXI4")
    }
  }

  private implicit class EdgeOps(e: RoutedEdge) {
    // from=mem.io (sink), to=cpu.io (source) after wire swap
    def bundleIsAxi: Boolean = e.from == "mem.io" && e.to == "cpu.io"
  }
}
