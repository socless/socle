package socle.ui.model

import socle.schema._
import socle.js.BrowserLayout.{LaidNodeR, LaidOut, LaidPortR}
import utest._

/** The rows a laid-out box renders: its laid pins per side in y order, with the
  * declared-port metadata from PortModel (depth, label, direction, open state). */
object RowModelTest extends TestSuite {

  private val src = SourceRef.unknown

  private val design = Design(
    top = "Top",
    bundles = Map(
      // Chisel's `io`: fields both ways, so it dissolves into its channels.
      "LeafIo" -> BundleDef("LeafIo", BundleKind.Bundle, channels = Seq(
        ChannelDef("in", "UInt<8>", flip = true), ChannelDef("out", "UInt<8>", flip = false))),
      // Two bits fields: one `bits` row until opened.
      "Dec" -> BundleDef("Dec", BundleKind.Decoupled, fields = Seq(
        FieldDef("a", 4, PortDir.Out), FieldDef("b", 4, PortDir.Out))),
    ),
    modules = Seq(
      ModuleDef("Leaf", "Leaf", "", src, Seq(
        PortDef("clock", PortDir.In, "Clock", "", src),
        PortDef("io", PortDir.Out, "LeafIo", "", src),
        PortDef("d", PortDir.In, "Dec", "", src),
        PortDef("v", PortDir.Out, "Vec[UInt<4>, 2]", "", src),
      ), Nil, Nil, Metrics(0, 0, 0)),
    ),
  )

  private val top = ModuleDef("Top", "Top", "", src,
    Seq(PortDef("x", PortDir.In, "UInt<8>", "", src)),
    Seq(InstanceDef("u", "Leaf", "", src), InstanceDef("z", "Ghost", "", src)),
    Nil, Metrics(1, 0, 0),
    logic = Seq(LogicNodeDef("r", NodeKind.Reg, "r", "LeafIo", src)))

  private def port(path: String, x: Int, y: Int, side: String, label: String = "", bundle: String = "") =
    LaidPortR(path, x, y, side, interface = false, label, bundle, expandable = false)

  private val layout = LaidOut(
    nodes = Seq(
      LaidNodeR("u", NodeKind.Inst, 100, 50, 80, 60, Seq(
        // Deliberately out of y order, with one pin on a side the box does not draw.
        port("u.d.bits", 100, 90, PinSide.West, "bits-from-layout"),
        port("u.io.in", 100, 60, PinSide.West, "in"),
        port("u.extra", 100, 100, PinSide.West),
        port("u.d", 100, 70, PinSide.West, "d: Dec"),
        port("u.d.valid", 100, 80, PinSide.West, "valid-from-layout"),
        port("u.v", 180, 70, PinSide.East),
        port("u.io.out", 180, 60, PinSide.East, "out: UInt<8>"),
        port("u.north", 140, 50, "NORTH", "n"),
      )),
      LaidNodeR("z", NodeKind.Inst, 0, 200, 40, 30, Seq(port("z.p", 0, 210, PinSide.West, "p", "UInt<1>"))),
      LaidNodeR("x", NodeKind.Boundary, 0, 0, 20, 20, Seq(port("x", 20, 10, PinSide.East, "x"))),
      LaidNodeR("r", NodeKind.Reg, 300, 0, 30, 40, Seq(
        port("r", 300, 10, PinSide.West), port("r~q", 330, 10, PinSide.East))),
    ),
    edges = Nil, w = 400, h = 300)

  private def build(expanded: Set[String], mod: ModuleDef = top) =
    RowModel.build(layout, mod, design, expanded, 22, 6, 18)

  val tests = Tests {
    test("rows per side in y order, other sides dropped, box height kept") {
      val u = build(Set("u.d"))("u")
      assert(u.westRows.map(_.path) == Seq("u.io.in", "u.d", "u.d.valid", "u.d.bits", "u.extra"))
      assert(u.eastRows.map(_.path) == Seq("u.io.out", "u.v"))
      assert(u.boxH == 60.0)
      assert(u.westRows.forall(_.side == PinSide.West), u.eastRows.forall(_.side == PinSide.East))
    }
    test("pins are box-relative") {
      val u = build(Set("u.d"))("u")
      val in = u.westRows.head
      assert(in.pinX == 0.0, in.pinYBaked == 10.0)
      val out = u.eastRows.head
      assert(out.pinX == 80.0, out.pinYBaked == 10.0)
    }
    test("a top row keeps the layout's label, a field row its own name") {
      val u = build(Set("u.d"))("u")
      val byPath = (u.westRows ++ u.eastRows).map(r => r.path -> r).toMap
      assert(byPath("u.d").label == "d: Dec")
      assert(byPath("u.d.valid").label == "valid")
      assert(byPath("u.d.bits").label == "bits")
      // A top row the layout left unlabelled falls back to the declared name.
      assert(byPath("u.v").label == "v")
    }
    test("declared metadata: depth, direction, width, open state") {
      val u = build(Set("u.d"))("u")
      val byPath = (u.westRows ++ u.eastRows).map(r => r.path -> r).toMap
      val d = byPath("u.d")
      assert(d.depth == 0, d.isTopLevel, d.isExpandable, d.open, d.dir.isEmpty, d.bundleKey == "Dec")
      val valid = byPath("u.d.valid")
      assert(valid.depth == 1, !valid.isTopLevel, !valid.isExpandable, valid.dir == Some(PortDir.In), valid.width == 1)
      val bits = byPath("u.d.bits")
      assert(bits.depth == 1, bits.isExpandable, !bits.open, bits.bundleKey == "Dec#bits")
      val in = byPath("u.io.in")
      assert(in.depth == 0, in.dir == Some(PortDir.In), in.width == 8, !in.isExpandable)
      assert(byPath("u.io.out").dir == Some(PortDir.Out))
      val v = byPath("u.v")
      assert(v.isExpandable, !v.open, v.bundleKey == "Vec[UInt<4>, 2]")
    }
    test("a closed row's children are not declared") {
      // `u.d` closed: its field pins (if laid) are plain top-level rows.
      val u = build(Set.empty)("u")
      val valid = u.westRows.find(_.path == "u.d.valid").get
      assert(valid.depth == 0, valid.isTopLevel, valid.label == "valid-from-layout", valid.dir.isEmpty)
      assert(!u.westRows.find(_.path == "u.d").get.open)
    }
    test("an undeclared pin is a plain top row labelled by its path") {
      val extra = build(Set("u.d"))("u").westRows.last
      assert(extra == RowModel.Row(0, "u.extra", None, 0, "u.extra", "", isExpandable = false, isTopLevel = true,
        PinSide.West, 0.0, 50.0))
    }
    test("an instance of an unknown module keeps its laid pins") {
      val z = build(Set.empty)("z")
      assert(z.westRows.map(r => (r.path, r.label, r.bundleKey, r.depth, r.isExpandable)) ==
        Seq(("z.p", "p", "UInt<1>", 0, false)))
      assert(z.eastRows.isEmpty)
    }
    test("boundary stubs and bundle registers are declared") {
      val rows = build(Set.empty)
      val x = rows("x").eastRows.head
      assert(x.path == "x", x.width == 8, x.dir == Some(PortDir.In))
      val r = rows("r")
      assert(r.westRows.map(_.path) == Seq("r"), r.eastRows.map(_.path) == Seq("r~q"))
      assert(r.westRows.head.isExpandable, r.eastRows.head.isExpandable)
    }
    test("a port wired as a whole keeps its row instead of dissolving") {
      val wired = top.copy(wires = Seq(WireDef("u.io", "x", "LeafIo", "", src)))
      val layout2 = layout.copy(nodes = Seq(LaidNodeR("u", NodeKind.Inst, 0, 0, 10, 10, Seq(
        port("u.io", 0, 2, PinSide.West, "io"), port("u.io.in", 0, 4, PinSide.West, "in")))))
      val u = RowModel.build(layout2, wired, design, Set.empty, 22, 6, 18)("u")
      val io = u.westRows.head
      assert(io.path == "u.io", io.isExpandable, io.bundleKey == "LeafIo")
      // Its channel is no longer a declared row.
      val in = u.westRows(1)
      assert(in.dir.isEmpty, in.width == 0)
    }
    test("pinY is where the layout placed each pin, whatever the constants") {
      val u = build(Set("u.d"))("u")
      val ys = u.pinY(0, 0, 0)
      assert(ys == u.pinY(22, 6, 18))
      assert(ys("u.io.in") == 10.0, ys("u.v") == 20.0, ys("u.extra") == 50.0)
      assert(ys.size == u.westRows.size + u.eastRows.size)
    }
    test("an empty layout has no boxes") {
      assert(RowModel.build(LaidOut(Nil, Nil, 0, 0), top, design, Set.empty, 22, 6, 18).isEmpty)
    }
  }
}
