package socless.layout

import utest._

object VecGroupTest extends TestSuite {
  import LayoutCore.{groupPath, vecRangeLabel}

  val tests = Tests {
    test("groupPath truncates at shallowest unexpanded Vec level") {
      // Nothing expanded: collapse at the first Vec index.
      assert(groupPath("a.io.data[0][3]", Set.empty) == "a.io.data")
      // Outer Vec expanded: descend one level, collapse at the inner index.
      assert(groupPath("a.io.data[0][3]", Set("a.io.data")) == "a.io.data[0]")
      // Both levels expanded: full leaf path, no grouping.
      assert(groupPath("a.io.data[0][3]", Set("a.io.data", "a.io.data[0]")) == "a.io.data[0][3]")
      // No Vec index at all: unchanged.
      assert(groupPath("a.io.flag", Set.empty) == "a.io.flag")
    }

    test("vecRangeLabel renders contiguous range half-open, else count") {
      assert(vecRangeLabel("data", Seq(0, 1, 2, 3)) == "data[0:4]")
      assert(vecRangeLabel("data", Seq(2, 0, 1)) == "data[0:3]")     // sorted, contiguous
      assert(vecRangeLabel("data", Seq(0, 2, 5)) == "data[3×]")       // non-contiguous -> count
      assert(vecRangeLabel("data", Seq(7)) == "data[7]")              // singleton -> exact index
    }

    test("collapsed Vec siblings to one node become one labelled edge + pin") {
      import socless.schema._
      val producer = ModuleDef("P", "P", "", SourceRef.unknown,
        Seq(PortDef("v", "out", "Vec[UInt<8>, 4]", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      val sink = ModuleDef("S", "S", "", SourceRef.unknown,
        Seq(PortDef("v", "in", "Vec[UInt<8>, 4]", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      val top = ModuleDef("Top", "Top", "", SourceRef.unknown, Nil,
        Seq(InstanceDef("p", "P", "", SourceRef.unknown), InstanceDef("s", "S", "", SourceRef.unknown)),
        (0 until 4).map(i => WireDef(s"s.v[$i]", s"p.v[$i]", "UInt<8>", "", SourceRef.unknown)),
        Metrics(0,0,0))
      val design = Design("Top", Map.empty, Seq(top, producer, sink))

      val collapsed = LayoutCore.prepare(top, design, Set.empty)
      val pEdges = collapsed.edges.filter(e => e.fromPath.startsWith("s.") && e.toPath.startsWith("p."))
      assert(pEdges.size == 1)
      assert(pEdges.head.label.contains("v[0:4]"))
      val sNode = collapsed.nodes.find(_.id == "s").get
      assert(sNode.pins.count(_.path == "s.v") == 1)
      assert(sNode.pins.forall(p => !p.path.matches(""".*\[\d+\]$""")))

      val expanded = LayoutCore.prepare(top, design, Set("s.v", "p.v"))
      val leafEdges = expanded.edges.filter(e => e.fromPath.startsWith("s.v["))
      assert(leafEdges.size == 4)
    }

    test("disjoint sinks split into two ranged groups") {
      import socless.schema._
      val p = ModuleDef("P","P","",SourceRef.unknown, Seq(PortDef("v","out","Vec[UInt<8>, 4]","",SourceRef.unknown)),Nil,Nil,Metrics(0,0,0))
      val a = ModuleDef("A","A","",SourceRef.unknown, Seq(PortDef("v","in","Vec[UInt<8>, 2]","",SourceRef.unknown)),Nil,Nil,Metrics(0,0,0))
      val b = ModuleDef("B","B","",SourceRef.unknown, Seq(PortDef("v","in","Vec[UInt<8>, 2]","",SourceRef.unknown)),Nil,Nil,Metrics(0,0,0))
      val top = ModuleDef("Top","Top","",SourceRef.unknown, Nil,
        Seq(InstanceDef("p","P","",SourceRef.unknown), InstanceDef("a","A","",SourceRef.unknown), InstanceDef("b","B","",SourceRef.unknown)),
        Seq(
          WireDef("a.v[0]","p.v[0]","UInt<8>","",SourceRef.unknown),
          WireDef("a.v[1]","p.v[1]","UInt<8>","",SourceRef.unknown),
          WireDef("b.v[0]","p.v[2]","UInt<8>","",SourceRef.unknown),
          WireDef("b.v[1]","p.v[3]","UInt<8>","",SourceRef.unknown),
        ), Metrics(0,0,0))
      val design = Design("Top", Map.empty, Seq(top,p,a,b))
      val g = LayoutCore.prepare(top, design, Set.empty)
      val pa = g.edges.filter(e => e.fromPath.startsWith("a.") && e.toPath.startsWith("p."))
      val pb = g.edges.filter(e => e.fromPath.startsWith("b.") && e.toPath.startsWith("p."))
      assert(pa.size == 1 && pa.head.label.contains("v[0:2]"))
      assert(pb.size == 1 && pb.head.label.contains("v[2:4]"))
    }

    test("displayWires collapses Vec sibling wires in lockstep with prepare") {
      import socless.schema._
      val producer = ModuleDef("P", "P", "", SourceRef.unknown,
        Seq(PortDef("v", "out", "Vec[UInt<8>, 4]", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      val sink = ModuleDef("S", "S", "", SourceRef.unknown,
        Seq(PortDef("v", "in", "Vec[UInt<8>, 4]", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      val top = ModuleDef("Top", "Top", "", SourceRef.unknown, Nil,
        Seq(InstanceDef("p", "P", "", SourceRef.unknown), InstanceDef("s", "S", "", SourceRef.unknown)),
        (0 until 4).map(i => WireDef(s"s.v[$i]", s"p.v[$i]", "UInt<8>", "", SourceRef.unknown)),
        Metrics(0,0,0))
      // Collapsed: the 4 leaf wires fold to ONE display wire s.v <- p.v.
      val collapsed = LayoutCore.displayWires(top, Set.empty)
      val vecWires = collapsed.filter(d => d.from.startsWith("s.v") && d.to.startsWith("p.v"))
      assert(vecWires.size == 1)
      assert(vecWires.head.from == "s.v" && vecWires.head.to == "p.v")
      // Expanded: the 4 leaves reappear as 4 display wires.
      val expanded = LayoutCore.displayWires(top, Set("s.v", "p.v"))
      assert(expanded.count(d => d.from.startsWith("s.v[") && d.to.startsWith("p.v[")) == 4)
    }

    test("collapsed Vec boundary port becomes a single boundary node") {
      import socless.schema._
      val sink = ModuleDef("S", "S", "", SourceRef.unknown,
        Seq(PortDef("v", "in", "Vec[UInt<8>, 4]", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
      // Top has a Vec boundary input `io` whose 4 elements each drive s.v[i].
      val top = ModuleDef("Top", "Top", "", SourceRef.unknown,
        Seq(PortDef("io", "in", "Vec[UInt<8>, 4]", "", SourceRef.unknown)),
        Seq(InstanceDef("s", "S", "", SourceRef.unknown)),
        (0 until 4).map(i => WireDef(s"s.v[$i]", s"io[$i]", "UInt<8>", "", SourceRef.unknown)),
        Metrics(0,0,0))
      val design = Design("Top", Map.empty, Seq(top, sink))

      val collapsed = LayoutCore.prepare(top, design, Set.empty)
      // Exactly ONE boundary node for the whole io Vec (was 4 before the fix).
      val ioBoundaries = collapsed.nodes.filter(n => n.kind == NodeKind.Boundary && n.id.startsWith("io"))
      assert(ioBoundaries.size == 1)
      assert(ioBoundaries.head.id == "io")

      // Expanded: the 4 element boundary nodes reappear.
      val expanded = LayoutCore.prepare(top, design, Set("io"))
      val ioExpanded = expanded.nodes.filter(n => n.kind == NodeKind.Boundary && n.id.startsWith("io"))
      assert(ioExpanded.size == 4)
    }
  }
}
