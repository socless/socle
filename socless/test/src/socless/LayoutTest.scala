package socless

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
  }

  private implicit class EdgeOps(e: RoutedEdge) {
    // from=mem.io (sink), to=cpu.io (source) after wire swap
    def bundleIsAxi: Boolean = e.from == "mem.io" && e.to == "cpu.io"
  }
}
