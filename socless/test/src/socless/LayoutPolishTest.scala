package socless

import utest._

/** Tests for the two layout-polish changes:
  *   1. Clock/Reset/AsyncReset wires (and their boundary stubs) are dropped.
  *   2. Boundary nodes are rendered as compact stubs (BoundaryW x BoundaryH).
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
  //   - data wire: cpu.io -> mem.io            -- kept
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
      // data wire (kept)
      WireDef("cpu.io",    "mem.io", "AXI4",    "",         SourceRef.unknown),
      // wire that triggers the `done` boundary node (kept)
      WireDef("mem.io",    "done",   "UInt<1>", "",         SourceRef.unknown),
      // clock wire (dropped)
      WireDef("cpu.clock", "clk",    "Clock",   "",         SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val design = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, cpuMod, memMod))

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
      val axiEdge = L.edges.find(e => e.from == "cpu.io" && e.to == "mem.io")
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
  }
}
