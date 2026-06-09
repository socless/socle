package socless

import utest._

/** Tests for layout-polish changes:
  *   1. Clock/Reset/AsyncReset wires (and their boundary stubs) are dropped.
  *   2. Boundary nodes are rendered as compact stubs (BoundaryW x BoundaryH).
  *   3. Anti-parallel wire pairs (AXI-style req+resp) collapse to one edge so
  *      ELK can rank nodes left-to-right without a 2-cycle.
  */
object LayoutPolishTest extends TestSuite {

  // ── Shared modules ──────────────────────────────────────────────────────────

  private val cpuMod = ModuleDef("Cpu", "Cpu", "", SourceRef.unknown,
    ports = Seq(
      PortDef("io",    "master", "AXI4",     "", SourceRef.unknown),
      PortDef("clock", "input",  "Clock",    "", SourceRef.unknown),
    ),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  private val memMod = ModuleDef("Mem", "Mem", "", SourceRef.unknown,
    ports = Seq(
      PortDef("io",    "slave",  "AXI4",     "", SourceRef.unknown),
      PortDef("clock", "input",  "Clock",    "", SourceRef.unknown),
    ),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  // Top has:
  //   - boundary port `clk`  (Clock,  input)  -- should be dropped
  //   - boundary port `done` (UInt<1>, output) -- should appear as compact stub
  //   - instance `cpu` (Cpu) and `mem` (Mem)
  //   - data wire: cpu.io -> mem.io (cpu drives)  -- kept
  //   - clock wire: cpu.clock <- clk           -- dropped
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports = Seq(
      PortDef("clk",  "input",  "Clock",    "", SourceRef.unknown),
      PortDef("done", "output", "UInt<1>",  "", SourceRef.unknown),
    ),
    children = Seq(
      InstanceDef("cpu", "Cpu", "", SourceRef.unknown),
      InstanceDef("mem", "Mem", "", SourceRef.unknown),
    ),
    wires = Seq(
      // data wire (kept): cpu drives mem (from=sink mem.io, to=source cpu.io)
      WireDef("mem.io",    "cpu.io", "AXI4",    "",         SourceRef.unknown),
      // wire that triggers the `done` boundary node (kept)
      WireDef("mem.io",    "done",   "UInt<1>", "",         SourceRef.unknown),
      // clock wire (dropped)
      WireDef("cpu.clock", "clk",    "Clock",   "",         SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val design = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, cpuMod, memMod))

  // ── Anti-parallel collapse fixture ──────────────────────────────────────────
  // Two instances: m (master) and x (crossbar/slave-side). The AXI connection
  // produces TWO WireDefs with opposite orientation:
  //   WireDef(from="x.io", to="m.io") -- m drives the request channels (EAST)
  //   WireDef(from="m.io", to="x.io") -- x drives the response channels back
  // Both resolve to the same unordered port-path pair {m.io, x.io}; Layout must
  // collapse them to ONE edge, oriented with the master (EAST side) as source.

  private val masterMod = ModuleDef("Master", "Master", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  private val slaveMod = ModuleDef("Slave", "Slave", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  // antiParallelTop: one master instance `m` (Master) + one slave instance `x` (Slave)
  // and exactly two anti-parallel AXI wires between them.
  private val antiParallelTop = ModuleDef("AntiParTop", "AntiParTop", "", SourceRef.unknown,
    ports = Nil,
    children = Seq(
      InstanceDef("m", "Master", "", SourceRef.unknown),
      InstanceDef("x", "Slave",  "", SourceRef.unknown),
    ),
    wires = Seq(
      // x.io is the sink (from), m.io is the source (to); master drives request
      WireDef("x.io", "m.io", "AXI4", "req",  SourceRef.unknown),
      // m.io is the sink (from), x.io is the source (to); slave drives response back
      WireDef("m.io", "x.io", "AXI4", "resp", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val antiParallelDesign = Design("AntiParTop",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(antiParallelTop, masterMod, slaveMod))

  // Control: a single (non-paired) wire; must remain exactly one edge.
  private val singleWireTop = ModuleDef("SingleTop", "SingleTop", "", SourceRef.unknown,
    ports = Nil,
    children = Seq(
      InstanceDef("m", "Master", "", SourceRef.unknown),
      InstanceDef("x", "Slave",  "", SourceRef.unknown),
    ),
    wires = Seq(
      WireDef("x.io", "m.io", "AXI4", "req", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  // ── Tests ────────────────────────────────────────────────────────────────────

  val tests = Tests {
    val L = Layout.layout(topMod, design)

    test("clock wire is excluded from routed edges") {
      // No edge should involve the clock boundary port "clk" or "cpu.clock"
      assert(L.edges.forall(e => e.from != "cpu.clock" && e.to != "clk"))
      assert(L.edges.forall(e => e.from != "clk"      && e.to != "cpu.clock"))
    }

    test("clock boundary stub is not created") {
      assert(!L.nodes.exists(_.id == "clk"))
    }

    test("data AXI edge is still present") {
      // from=sink (mem.io), to=source (cpu.io): cpu drives mem
      val axiEdge = L.edges.find(e => e.from == "mem.io" && e.to == "cpu.io")
      assert(axiEdge.isDefined)
    }

    test("done boundary node exists and is compact") {
      val doneNode = L.nodes.find(_.id == "done")
      assert(doneNode.isDefined)
      assert(doneNode.get.kind == "boundary")
      // BoundaryW = 16, BoundaryH = 28 -- use a loose upper bound so the test
      // is not brittle if the constants are tweaked slightly, but tight enough
      // to confirm it is NOT the full NodeW (160) x nodeHeight (72+) size.
      assert(doneNode.get.w <= 40)
      assert(doneNode.get.h <= 40)
    }

    test("instance nodes are full-size (not compact)") {
      val cpuNode = L.nodes.find(_.id == "cpu")
      assert(cpuNode.isDefined)
      assert(cpuNode.get.kind == "inst")
      // NodeW = 160, nodeHeight >= 72
      assert(cpuNode.get.w >= 100)
      assert(cpuNode.get.h >= 50)
    }

    test("layout canvas is positive") {
      assert(L.w > 0 && L.h > 0)
    }

    // ── Anti-parallel collapse tests ─────────────────────────────────────────

    test("anti-parallel pair collapses to one routed edge") {
      val L2 = Layout.layout(antiParallelTop, antiParallelDesign)
      assert(L2.edges.length == 1)
    }

    test("kept edge is oriented master-as-source (to == m.io, from == x.io)") {
      val L2 = Layout.layout(antiParallelTop, antiParallelDesign)
      // RoutedEdge.to is the SOURCE (m.io, the EAST/master driver)
      // RoutedEdge.from is the SINK  (x.io)
      val e = L2.edges.head
      assert(e.to   == "m.io")
      assert(e.from == "x.io")
    }

    test("both nodes m and x still exist after collapse") {
      val L2 = Layout.layout(antiParallelTop, antiParallelDesign)
      assert(L2.nodes.exists(_.id == "m"))
      assert(L2.nodes.exists(_.id == "x"))
    }

    test("both io pins still present after collapse") {
      val L2 = Layout.layout(antiParallelTop, antiParallelDesign)
      assert(L2.nodes.find(_.id == "m").get.ports.exists(_.path == "m.io"))
      assert(L2.nodes.find(_.id == "x").get.ports.exists(_.path == "x.io"))
    }

    test("single non-paired wire still yields exactly one edge") {
      val L3 = Layout.layout(singleWireTop, antiParallelDesign)
      assert(L3.edges.length == 1)
      // Orientation unchanged: from = x.io (sink), to = m.io (source)
      assert(L3.edges.head.from == "x.io")
      assert(L3.edges.head.to   == "m.io")
    }
  }
}
