package socless.layout

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

/** Regression tests for issue #68: a module's external I/O ports must not be
  * collapsed into a single boundary node.
  *
  * Real Chisel modules wrap all their I/O in one `io` bundle, so every external
  * interface used to share one boundary node. A single node occupies one ELK
  * layer, so inputs could not sit on the left and outputs on the right; wires
  * between the boundary and the interior (or between two boundary pins on a leaf
  * module) routed backward or spanned the whole canvas ("broken arrows").
  *
  * The fix gives each external pin its own boundary node, so ELK ranks
  * input-sources toward the left and output-sinks toward the right with
  * forward-flowing wires.
  */
object BoundaryIoTest extends TestSuite {

  // A leaf module (no child instances) whose only connection is between two of
  // its own external interfaces: io.in drives io.rf. This is the shape that was
  // visibly broken (the WB stage in RVPipeline): the wire ran right-to-left
  // between two pins of a single shared boundary box.
  private val leafMod = ModuleDef("Leaf", "Leaf", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
    children = Nil,
    wires = Seq(
      // from = sink (io.rf), to = source (io.in): io.in drives io.rf.
      WireDef("io.rf", "io.in", "AXI4", "2x ground connects", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val leafDesign = Design("Leaf",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(leafMod))

  // A hierarchical module: boundary input io.src drives an instance, the
  // instance drives boundary output io.dst. Both external pins must be distinct
  // boundary nodes, with the input toward the left and the output toward the right.
  private val coreMod = ModuleDef("Core", "Core", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
    children = Nil, wires = Nil, metrics = Metrics(0, 0, 0))

  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
    children = Seq(InstanceDef("core", "Core", "", SourceRef.unknown)),
    wires = Seq(
      // io.src drives core.io: from = sink (core.io), to = source (io.src).
      WireDef("core.io", "io.src", "AXI4", "", SourceRef.unknown),
      // core.io drives io.dst: from = sink (io.dst), to = source (core.io).
      WireDef("io.dst", "core.io", "AXI4", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val topDesign = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, coreMod))

  // A Decoupled/AXI output port carries data forward and `ready` back, so its
  // boundary pin gets one source vote and one sink vote (a tie). The tie must
  // break toward the port's declared direction (output -> WEST/right), not the
  // default EAST, or the external output is misread as an input and lands on
  // the left with a backward wire (the DeepSoc io.out regression in #68).
  private val tieMod = ModuleDef("Tie", "Tie", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "output", "Decoupled_out", "", SourceRef.unknown)),
    children = Seq(InstanceDef("u", "Core", "", SourceRef.unknown)),
    wires = Seq(
      // forward data: u drives io.out (from = sink io.out, to = source u.io)
      WireDef("io.out", "u.io", "Decoupled_out", "", SourceRef.unknown),
      // backward ready: io.out drives u (from = sink u.io, to = source io.out)
      WireDef("u.io", "io.out", "Decoupled_out", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val tieDesign = Design("Tie",
    Map("Decoupled_out" -> BundleDef("Decoupled_out", "Decoupled")),
    Seq(tieMod, coreMod))

  // Boundary-column alignment (issue #163). One connected component with a
  // depth-3 instance chain (io.in -> c1 -> c2 -> c3). Some external pins attach
  // to early nodes and some to late nodes: input io.in2 also feeds the deep c3,
  // and output io.early is fed by the shallow c1 while io.late is fed by c3. By
  // dataflow ranking alone io.in2 and io.early would strand in middle columns
  // (the #163 symptom). All external inputs must instead share one leftmost
  // column and all external outputs one rightmost column, neither holding an
  // instance.
  private val colMod = ModuleDef("Cols", "Cols", "", SourceRef.unknown,
    ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
    children = Seq(
      InstanceDef("c1", "Core", "", SourceRef.unknown),
      InstanceDef("c2", "Core", "", SourceRef.unknown),
      InstanceDef("c3", "Core", "", SourceRef.unknown),
    ),
    wires = Seq(
      // chain: io.in -> c1 -> c2 -> c3 (from = sink, to = source)
      WireDef("c1.io", "io.in", "AXI4", "", SourceRef.unknown),
      WireDef("c2.io", "c1.io", "AXI4", "", SourceRef.unknown),
      WireDef("c3.io", "c2.io", "AXI4", "", SourceRef.unknown),
      // io.in2 is an input that feeds the deep node c3 (would rank mid-graph).
      WireDef("c3.io", "io.in2", "AXI4", "", SourceRef.unknown),
      // io.early is an output fed by the shallow c1 (would rank mid-graph).
      WireDef("io.early", "c1.io", "AXI4", "", SourceRef.unknown),
      // io.late is an output fed by the deep c3.
      WireDef("io.late", "c3.io", "AXI4", "", SourceRef.unknown),
    ),
    metrics = Metrics(0, 0, 0))

  private val colDesign = Design("Cols",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(colMod, coreMod))

  val tests = Tests {

    test("each external pin is its own boundary node, not a shared 'io' node") {
      val L = Layout.layout(leafMod, leafDesign)
      val boundaryIds = L.nodes.filter(_.kind == "boundary").map(_.id).toSet
      assert(boundaryIds == Set("io.in", "io.rf"))
      // The collapsed-into-one-node bug would have produced a single "io" node.
      assert(!L.nodes.exists(_.id == "io"))
    }

    test("leaf boundary-to-boundary wire flows forward (no backward arrow)") {
      val L = Layout.layout(leafMod, leafDesign)
      // Source is io.in (drives), sink is io.rf (receives).
      val src = L.nodes.find(_.id == "io.in").get.ports.head
      val dst = L.nodes.find(_.id == "io.rf").get.ports.head
      // The source pin must be left of the sink pin: a left-to-right wire.
      assert(src.x < dst.x)
      // And the routed polyline itself must run forward.
      val e = L.edges.find(ed => ed.to == "io.in" && ed.from == "io.rf").get
      assert(e.points.nonEmpty)
      assert(e.points.head._1 <= e.points.last._1)
    }

    test("hierarchical boundary input sits left of boundary output") {
      val L = Layout.layout(topMod, topDesign)
      val boundaryIds = L.nodes.filter(_.kind == "boundary").map(_.id).toSet
      assert(boundaryIds == Set("io.src", "io.dst"))
      val inNode  = L.nodes.find(_.id == "io.src").get
      val outNode = L.nodes.find(_.id == "io.dst").get
      // External input (drives into the design) should rank left of the output.
      assert(inNode.x < outNode.x)
    }

    test("every boundary node has exactly one pin after the split") {
      val L = Layout.layout(topMod, topDesign)
      L.nodes.filter(_.kind == "boundary").foreach { n =>
        assert(n.ports.size == 1)
      }
    }

    test("Decoupled boundary output breaks the vote tie toward WEST (output side)") {
      val L = Layout.layout(tieMod, tieDesign)
      val out = L.nodes.find(_.id == "io.out").get
      assert(out.ports.head.side == "WEST")
    }

    // An AXI slave port at the module boundary comes from `input io` (dir
    // "slave" via portRole). Its anti-parallel traffic always ties the side
    // vote, and the tie-break uses the declared side: that must be EAST (it
    // drives requests into the design like any boundary input). It used to
    // fall through to WEST and land on the output side (issue #102).
    test("AXI slave boundary port breaks the vote tie toward EAST (input side)") {
      val slaveMod = ModuleDef("SlaveTop", "SlaveTop", "", SourceRef.unknown,
        ports = Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)),
        children = Seq(InstanceDef("u", "Core", "", SourceRef.unknown)),
        wires = Seq(
          // forward request: io drives u.io (from = sink u.io, to = source io)
          WireDef("u.io", "io", "AXI4", "", SourceRef.unknown),
          // backward response: u.io drives io (from = sink io, to = source u.io)
          WireDef("io", "u.io", "AXI4", "", SourceRef.unknown),
        ),
        metrics = Metrics(0, 0, 0))
      val slaveDesign = Design("SlaveTop",
        Map("AXI4" -> BundleDef("AXI4", "Bundle")),
        Seq(slaveMod, coreMod))
      val L = Layout.layout(slaveMod, slaveDesign)
      val io = L.nodes.find(_.id == "io").get
      assert(io.ports.head.side == "EAST")
    }

    // Issue #163: external inputs align into one leftmost column and external
    // outputs into one rightmost column, with no instance in either column.
    test("external inputs share one leftmost column, outputs one rightmost column") {
      val L = Layout.layout(colMod, colDesign)
      val boundary = L.nodes.filter(_.kind == "boundary")
      val inputs  = boundary.filter(_.ports.head.side == "EAST")  // drive into design
      val outputs = boundary.filter(_.ports.head.side == "WEST")  // receive from design
      val insts   = L.nodes.filter(_.kind == "inst")
      assert(inputs.map(_.id).toSet  == Set("io.in", "io.in2"))
      assert(outputs.map(_.id).toSet == Set("io.early", "io.late"))

      // The pins (not the box left edges, which vary with label width) align
      // into one column per side: all input pins at one x, all output pins at one.
      val inPinX  = inputs.map(_.ports.head.x)
      val outPinX = outputs.map(_.ports.head.x)
      assert(inPinX.distinct.size == 1)
      assert(outPinX.distinct.size == 1)

      // The columns flank the instances: the input column is left of every
      // instance and the output column right of every instance (no submodule in
      // either I/O column).
      val inputX  = inPinX.head
      val outputX = outPinX.head
      assert(insts.forall(n => inputX < n.x))
      assert(insts.forall(n => n.x + n.w < outputX))
    }
  }
}
