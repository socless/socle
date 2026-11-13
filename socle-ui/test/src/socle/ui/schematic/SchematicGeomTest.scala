package socle.ui.schematic

import socle.schema._
import socle.js.BrowserLayout.{LaidEdgeR, LaidNodeR, LaidOut, LaidPortR}
import socle.layout.LayoutCore.DisplayWire
import socle.ui.model.RowModel
import socle.ui.theme.Tokens
import utest._

import SchematicGeom._

/** The pure half of the schematic: wire geometry, box shifts, pin anchors and
  * the per-geometry lookups. */
object SchematicGeomTest extends TestSuite {

  private val src = SourceRef.unknown
  private val noShift = BoxShift(Map.empty, _ => 0.0, 0.0)

  private def port(path: String, x: Int, y: Int, side: String) =
    LaidPortR(path, x, y, side, interface = false, "", "", expandable = false)
  private def box(id: String, x: Int, y: Int, w: Int, h: Int, ports: LaidPortR*) =
    LaidNodeR(id, NodeKind.Inst, x, y, w, h, ports)

  // Source `a.o` (EAST, y 10) drives sink `b.i` (WEST, y 30).
  private val a = box("a", 0, 0, 40, 40, port("a.o", 40, 10, PinSide.East))
  private val b = box("b", 100, 0, 40, 40, port("b.i", 100, 30, PinSide.West))
  private val boxes = Map("a" -> a, "b" -> b)
  private val pinYs = Map("a" -> Map("a.o" -> 10.0), "b" -> Map("b.i" -> 30.0))
  // A display wire is (sink, source); a routed edge is keyed the same way, its points run source to sink.
  private val wire = DisplayWire("b.i", "a.o", "UInt<8>", "", bidir = false, count = 1)
  private val route = Seq((40, 10), (70, 10), (70, 30), (100, 30))
  private def laid(edges: LaidEdgeR*) = LaidOut(Seq(a, b), edges, 200, 100)

  val tests = Tests {
    test("orthogonalize") {
      test("empty and single points") {
        assert(orthogonalize(Nil) == Nil)
        assert(orthogonalize(Seq((1.0, 2.0))) == Seq((1.0, 2.0)))
      }
      test("an orthogonal route is unchanged") {
        val pts = route.map { case (x, y) => (x.toDouble, y.toDouble) }
        assert(orthogonalize(pts) == pts)
      }
      test("a diagonal becomes an H-V-H jog through its midpoint") {
        assert(orthogonalize(Seq((0.0, 0.0), (10.0, 20.0))) ==
          Seq((0.0, 0.0), (5.0, 0.0), (5.0, 20.0), (10.0, 20.0)))
      }
      test("exact duplicates are dropped") {
        assert(orthogonalize(Seq((0.0, 0.0), (0.0, 0.0), (5.0, 0.0), (5.0, 0.0))) == Seq((0.0, 0.0), (5.0, 0.0)))
      }
    }

    test("ownerBoxId: a whole-path box, else the head segment") {
      val m = Map("io.in[0]" -> a, "xbar" -> b)
      assert(ownerBoxId(m, "io.in[0]") == "io.in[0]")
      assert(ownerBoxId(m, "xbar.in[0].ar") == "xbar")
      assert(ownerBoxId(m, "lone") == "lone")
      assert(ownerBoxId(m, "") == "")
    }

    test("opGlyph") {
      assert(Seq("add", "mul", "leq", "neq", "xorr", "dshl", "shr", "mux").map(opGlyph) ==
        Seq("+", "×", "≤", "≠", "^r", "<<", ">>", "mux"))
      assert(opGlyph("sub") == opGlyph("neg"))
      // Unknown ops show as written; nothing renders blank.
      assert(opGlyph("cat") == "cat", opGlyph("") == "?")
    }

    test("anchorX") {
      test("the pin's own x") { assert(anchorX(a, "a.o") == 40.0) }
      test("a field under a laid pin takes the pin's x") { assert(anchorX(b, "b.i.bits") == 100.0) }
      test("no pin: the box's horizontal center") { assert(anchorX(a, "a.zz") == 20.0) }
      test("a sibling sharing a text prefix is not an owner") {
        // `io.a` (WEST) is laid before `io.ab` (EAST); `in[1]` (WEST) before `in[10]` (EAST).
        val c = box("c", 200, 0, 40, 40,
          port("c.io.a", 200, 10, PinSide.West), port("c.io.ab", 240, 20, PinSide.East),
          port("c.in[1]", 200, 30, PinSide.West), port("c.in[10]", 240, 30, PinSide.East))
        assert(anchorX(c, "c.io.ab.bits") == 240.0)
        assert(anchorX(c, "c.in[10].x") == 240.0)
        assert(anchorX(c, "c.io.abc") == 220.0)
        // A Vec element under a laid Vec pin still takes the pin's x.
        assert(anchorX(c, "c.io.a[3]") == 200.0)
      }
    }

    test("computeBoxShift") {
      val top = box("top", 0, 0, 40, 40)
      val below = box("below", 0, 40, 40, 20)
      val beside = box("beside", 60, 38, 40, 20)
      val above = box("above", 60, 10, 40, 20)
      val L = LaidOut(Seq(top, below, beside, above), Nil, 100, 100)
      test("no box grew: no shift") {
        val s = computeBoxShift(L, Map("top" -> RowModel.BoxRows(Nil, Nil, 40.0)))
        assert(s.shift.isEmpty, s.total == 0.0, s.shiftAt(1000) == 0.0)
      }
      test("a grown box pushes what starts at or below its old bottom (1px slack)") {
        val s = computeBoxShift(L, Map("top" -> RowModel.BoxRows(Nil, Nil, 60.0)))
        assert(s.shift == Map("below" -> 20.0))
        assert(s.total == 20.0)
        assert(s.shiftAt(39.0) == 20.0, s.shiftAt(38.9) == 0.0)
      }
      test("two boxes with the same bottom insert their largest growth once") {
        val twin = box("twin", 200, 0, 40, 40)
        val L2 = L.copy(nodes = L.nodes :+ twin)
        val s = computeBoxShift(L2, Map(
          "top" -> RowModel.BoxRows(Nil, Nil, 50.0), "twin" -> RowModel.BoxRows(Nil, Nil, 70.0)))
        assert(s.total == 30.0, s.shift("below") == 30.0)
      }
      test("growth at different bottoms accumulates below both") {
        val s = computeBoxShift(L, Map(
          "top" -> RowModel.BoxRows(Nil, Nil, 50.0),     // bottom 40, +10
          "above" -> RowModel.BoxRows(Nil, Nil, 25.0)))  // bottom 30, +5
        assert(s.total == 15.0)
        assert(s.shift("below") == 15.0, s.shift("beside") == 5.0)
        assert(!s.shift.contains("top"), !s.shift.contains("above"))
      }
    }

    test("buildWires") {
      test("a routed wire keeps its route, ends on its pins") {
        val ws = buildWires(Seq(wire), laid(LaidEdgeR("b.i", "a.o", route, Nil)), boxes, pinYs, noShift)
        assert(ws.size == 1)
        val w = ws.head
        assert(w.id == "w0", w.fromInst == "b", w.toInst == "a", !w.dangling, w.bundle == "UInt<8>")
        assert(w.points == Seq((40.0, 10.0), (70.0, 10.0), (70.0, 30.0), (100.0, 30.0)))
        assert((w.x1, w.y1, w.x2, w.y2) == (40.0, 10.0, 100.0, 30.0))
      }
      test("a reversed edge is flipped back to source to sink") {
        val ws = buildWires(Seq(wire), laid(LaidEdgeR("a.o", "b.i", route.reverse, Nil)), boxes, pinYs, noShift)
        assert(ws.head.points.head == ((40.0, 10.0)), ws.head.points.last == ((100.0, 30.0)))
      }
      test("terminals snap to the pin rows, with an H-V-H jog where needed") {
        val moved = pinYs.updated("b", Map("b.i" -> 34.0))
        val w = buildWires(Seq(wire), laid(LaidEdgeR("b.i", "a.o", route, Nil)), boxes, moved, noShift).head
        assert(w.points == Seq((40.0, 10.0), (70.0, 10.0), (70.0, 30.0), (85.0, 30.0), (85.0, 34.0), (100.0, 34.0)))
      }
      test("a collapsed field wire ends on its nearest laid ancestor pin") {
        val field = DisplayWire("b.i.x.y", "a.o", "UInt<1>", "", bidir = false, count = 1)
        val moved = pinYs.updated("b", Map("b.i" -> 34.0))
        val w = buildWires(Seq(field), laid(LaidEdgeR("b.i.x.y", "a.o", route, Nil)), boxes, moved, noShift).head
        assert(w.points.last == ((100.0, 34.0)))
      }
      test("a shifted box moves its terminal and the bends below the shift") {
        val shift = BoxShift(Map("b" -> 20.0), y => if (y >= 20) 20.0 else 0.0, 20.0)
        val w = buildWires(Seq(wire), laid(LaidEdgeR("b.i", "a.o", route, Nil)), boxes, pinYs, shift).head
        assert(w.points == Seq((40.0, 10.0), (70.0, 10.0), (70.0, 50.0), (100.0, 50.0)))
      }
      test("no routed edge: a dangling straight segment between the anchors") {
        val w = buildWires(Seq(wire), laid(), boxes, pinYs, noShift).head
        assert(w.dangling)
        assert(w.points == Seq((40.0, 10.0), (70.0, 10.0), (70.0, 30.0), (100.0, 30.0)))
      }
      test("a one-point route counts as no route") {
        val w = buildWires(Seq(wire), laid(LaidEdgeR("b.i", "a.o", Seq((1, 1)), Nil)), boxes, pinYs, noShift).head
        assert(w.dangling)
      }
      test("an unknown pin anchors at its box's center") {
        val w = buildWires(Seq(wire), laid(), boxes, Map.empty, noShift).head
        assert(w.points.head == ((40.0, 20.0)), w.points.last == ((100.0, 20.0)))
      }
      test("only one end known: a 24px stub from it") {
        val noSink = DisplayWire("gone.i", "a.o", "UInt<8>", "", bidir = false, count = 1)
        assert(buildWires(Seq(noSink), laid(), boxes, pinYs, noShift).head.points == Seq((40.0, 10.0), (64.0, 10.0)))
        val noSource = DisplayWire("b.i", "gone.o", "UInt<8>", "", bidir = false, count = 1)
        assert(buildWires(Seq(noSource), laid(), boxes, pinYs, noShift).head.points == Seq((76.0, 30.0), (100.0, 30.0)))
      }
      test("no end known: dropped (logged unless a hidden node explains it)") {
        val lost = DisplayWire("gone.i", "lost.o", "UInt<8>", "", bidir = false, count = 1)
        assert(buildWires(Seq(lost), laid(), boxes, pinYs, noShift, hiddenIds = Set("gone")).isEmpty)
        assert(buildWires(Seq(lost), laid(), boxes, pinYs, noShift).isEmpty)
      }
      test("ids follow the display wire index, dropped wires included") {
        val lost = DisplayWire("gone.i", "lost.o", "UInt<8>", "", bidir = false, count = 1)
        val ws = buildWires(Seq(lost, wire), laid(), boxes, pinYs, noShift, hiddenIds = Set("gone"))
        assert(ws.map(_.id) == Seq("w1"))
      }
    }

    test("routePath") {
      assert(routePath(Nil) == "", routePath(Seq((1.0, 2.0))) == "")
      // Scala.js prints an integral Double without ".0".
      assert(routePath(Seq((0.0, 0.0), (10.0, 2.5), (10.0, 7.0))) == "M 0 0 L 10 2.5 L 10 7")
    }

    test("fuzzyHas") {
      assert(fuzzyHas("anything", ""), fuzzyHas("", ""))
      assert(fuzzyHas("RegisterFile", "RgF"), fuzzyHas("abc", "abc"))
      assert(!fuzzyHas("abc", "acb"), !fuzzyHas("a", "aa"), !fuzzyHas("", "a"))
      assert(!fuzzyHas("RegisterFile", "rgf")) // case-sensitive
    }

    test("bakedOf: through layoutOf, else by name") {
      val ml = ModuleLayout(Seq(LaidNode("n", NodeKind.Inst, 1, 2, 3, 4, Nil)), Nil, 10, 20)
      val m1 = ModuleDef("X_1", "X_1", "", src, Nil, Nil, Nil, Metrics(0, 0, 0))
      val m0 = m1.copy(name = "X")
      val d = Design("Top", Map.empty, Seq(m0, m1), layouts = Map("X" -> ml), layoutOf = Map("X_1" -> "X"))
      assert(bakedOf(d, m1).map(_.nodes.map(_.id)) == Some(Seq("n")))
      assert(bakedOf(d, m0).map(l => (l.w, l.h)) == Some((10, 20)))
      assert(bakedOf(d, m1.copy(name = "Y")).isEmpty)
    }

    test("Geom lookups") {
      val leaf  = ModuleDef("Leaf", "Leaf", "", src, Nil, Nil, Nil, Metrics(0, 0, 0))
      val inner = leaf.copy(name = "Inner", wires = Seq(WireDef("a", "b", "UInt<1>", "", src)))
      val ext   = leaf.copy(name = "Ext", kind = ModuleKind.External)
      val dupe  = leaf.copy(name = "Leaf", doc = "last")
      val mod = ModuleDef("Top", "Top", "", src, Nil, Seq(
        InstanceDef("l", "Leaf", "", src), InstanceDef("l", "Inner", "", src),
        InstanceDef("i", "Inner", "", src), InstanceDef("e", "Ext", "", src),
        InstanceDef("f", "Inner", "", src, foldCount = 3), InstanceDef("g", "Ghost", "", src)),
        Nil, Metrics(0, 0, 0))
      val d = Design("Top", Map.empty, Seq(leaf, inner, ext, dupe, mod))
      val wires = Seq(
        Wire("w0", "i.io.in", "e.io", "i", "e", "", bidir = false, Seq((0.0, 0.0), (1.0, 0.0))),
        Wire("w1", "v[0]", "l.x", "v[0]", "l", "", bidir = false, Seq((0.0, 0.0), (1.0, 0.0))))
      val twin = LaidNodeR("twin", NodeKind.Inst, 0, 0, 1, 1, Seq(port("i.io.in", 9, 9, PinSide.West)))
      val L = LaidOut(Seq(a, twin, box("i", 0, 0, 1, 1, port("i.io.in", 0, 5, PinSide.West))), Nil, 1, 1)
      val g = Geom(L, mod, d, Map.empty, wires, noShift, 0.0, Tokens.Consts(22, 6, 18), Map.empty)

      test("wiredPins: the end, rows above it, rows under it") {
        assert(g.wiredPins("i.io.in"))      // a wire end
        assert(g.wiredPins("i.io"))         // a row above a wire end
        assert(g.wiredPins("e.io.bits.a"))  // under a whole-bundle wire end
        assert(g.wiredPins("v"))            // a Vec above its element's wire
        assert(!g.wiredPins("i.io.inner"))  // a sibling sharing a prefix
        assert(!g.wiredPins("l.y"), !g.wiredPins("x"))
      }
      test("childByName keeps the first, moduleByName the last") {
        assert(g.childByName("l").`type` == "Leaf")
        assert(g.moduleByName("Leaf").doc == "last")
      }
      test("instInfo") {
        val by = mod.children.map(c => c.name -> c).toMap
        assert(g.instInfo(by("i")) == InstInfo(Some(inner), blackbox = false, folded = false, drillable = true))
        assert(g.instInfo(by("e")) == InstInfo(Some(ext), blackbox = true, folded = false, drillable = false))
        // A folded group unfolds on double-click rather than opening.
        assert(g.instInfo(by("f")) == InstInfo(Some(inner), blackbox = false, folded = true, drillable = false))
        // A leaf without registers, code or wires does not open.
        assert(!g.instInfo(mod.children.head).drillable)
        assert(g.instInfo(by("g")) == InstInfo(None, blackbox = false, folded = false, drillable = false))
      }
      test("pinOwner: the first box in layout order") {
        assert(g.pinOwner("i.io.in")._1.id == "twin")
        assert(g.pinOwner("a.o")._1.id == "a")
        assert(!g.pinOwner.contains("nowhere"))
      }
      test("logicById is empty without logic") { assert(g.logicById.isEmpty) }
    }
  }
}
