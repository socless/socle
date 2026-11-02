package socless.layout

import socless.schema._

import utest._

/** The layout's instance and edge-stub pins come from the declared ports
  * ([[PortModel]], #332): one pin per shown row, each wire end on the deepest
  * shown row, and no wire dropped when a port cannot be resolved. */
object PortPinsTest extends TestSuite {

  private def golden(name: String): Design = {
    val s = getClass.getResourceAsStream(s"/golden/$name.json")
    require(s != null, s"missing classpath resource /golden/$name.json")
    try upickle.default.read[Design](scala.io.Source.fromInputStream(s, "UTF-8").mkString)
    finally s.close()
  }

  private lazy val rv  = golden("rvpipeline")
  private lazy val gcd = golden("gcd")
  private lazy val axi = golden("axisystem")

  private def top(d: Design) = d.modules.find(_.name == d.top).get
  private def node(p: LayoutCore.Prepared, id: String) = p.nodes.find(_.id == id).get
  private def pinPaths(p: LayoutCore.Prepared) = p.nodes.flatMap(_.pins.map(_.path)).toSet

  val tests = Tests {
    test("decode's pins are its declared rows") {
      val p = LayoutCore.prepare(top(rv), rv)
      assert(node(p, "decode").pins.map(q => q.path -> q.side) == Seq(
        "decode.io.in"  -> PinSide.West,
        "decode.io.out" -> PinSide.East,
        "decode.io.rf"  -> PinSide.East,
      ))
      val all = p.nodes.flatMap(_.pins.map(_.path))
      assert(all.distinct.length == all.length)
    }

    test("wires attach to the deepest shown row") {
      for (d <- Seq(rv, axi, gcd); open <- Seq(Set.empty[String], Set("xbar.io.in", "decode.io.in", "io.in"))) {
        val p     = LayoutCore.prepare(top(d), d, open)
        val pins  = pinPaths(p)
        p.edges.foreach(e => assert(pins(e.fromPath) && pins(e.toPath)))
      }
      val p = LayoutCore.prepare(top(axi), axi, Set("xbar.io.in"))
      assert(p.edges.exists(e => e.fromPath == "xbar.io.in[0]" || e.toPath == "xbar.io.in[0]"))
    }

    test("an instance with no module keeps its wire pins") {
      val withMod = LayoutCore.prepare(top(rv), rv)
      val noMod   = rv.copy(modules = rv.modules.filterNot(_.name == "Decode"))
      val p       = LayoutCore.prepare(top(noMod), noMod)
      assert(node(p, "decode").pins.nonEmpty)
      assert(p.edges.length == withMod.edges.length)
    }

    test("an endpoint outside the port tree keeps a pin") {
      val m = top(rv)
      val extra = m.copy(wires = m.wires :+
        WireDef("decode.io.zz", "fetch.io.out.valid", "UInt<1>", "", SourceRef.unknown))
      val p = LayoutCore.prepare(extra, rv)
      assert(node(p, "decode").pins.exists(_.path == "decode.io.zz"))
      assert(p.edges.exists(_.fromPath == "decode.io.zz"))
    }

    test("GCD stubs") {
      val m = top(gcd)
      val closed = LayoutCore.prepare(m, gcd).nodes.filter(_.kind == NodeKind.Boundary)
      assert(closed.map(_.id) == Seq("io.in", "io.out"))
      assert(closed.forall(_.pins.length == 1))
      val open = node(LayoutCore.prepare(m, gcd, Set("io.in")), "io.in")
      assert(open.pins.map(_.path) == Seq("io.in", "io.in.valid", "io.in.ready", "io.in.bits"))
      val ys = open.pins.map(_.y)
      assert(ys == ys.sorted && ys.distinct.length == ys.length)
      assert(ys.forall(y => y > 0 && y < open.height))
    }

    test("unconnected ports are shown") {
      val m  = top(rv)
      val d  = rv.copy(modules = rv.modules.map(x =>
        if (x.name == "Decode") x.copy(ports = x.ports.map(p => p.copy(connected = false))) else x))
      val noWires = m.copy(wires = m.wires.filterNot(w => w.from.startsWith("decode.") || w.to.startsWith("decode.")))
      val p = LayoutCore.prepare(noWires, d)
      assert(node(p, "decode").pins.map(_.path) == Seq("decode.io.in", "decode.io.out", "decode.io.rf"))
      assert(!p.edges.exists(e => e.fromPath.startsWith("decode.") || e.toPath.startsWith("decode.")))
    }

    test("a bake carries the layout model its pins follow") {
      // An older design.json (no field) reads model 0: its baked pins predate
      // the declared rows, so the UI lays it out again (#332).
      assert(rv.layoutModel == 0)
      assert(Layout.withLayouts(rv).layoutModel == LayoutCore.LayoutModel)
      assert(LayoutCore.LayoutModel >= 2) // #334 changed what a bake holds
    }

    test("pin labels name types at Chisel's level (#333)") {
      val labels = node(LayoutCore.prepare(top(rv), rv), "decode").pins.map(_.label)
      assert(labels == Seq("in: Decoupled", "out: Decoupled", "rf"))
      val stubs = LayoutCore.prepare(top(gcd), gcd).nodes.filter(_.kind == NodeKind.Boundary).flatMap(_.pins.map(_.label))
      assert(stubs == Seq("io.in: Decoupled", "io.out: Decoupled"))
      assert(node(LayoutCore.prepare(top(axi), axi), "cpu").pins.map(_.label) == Seq("io: AXI4"))
    }

    test("a bake is the optimize pass the UI would run (#335)") {
      // The UI's default (auto-optimize) re-laid every first view out in the
      // browser; a bake already in that mode needs no run.
      assert(!rv.layoutOptimized)
      assert(Layout.withLayouts(rv).layoutOptimized)
    }

    test("display wires use the same rows") {
      val m  = top(axi)
      val open = Set("xbar.io.in")
      val pins = pinPaths(LayoutCore.prepare(m, axi, open))
      val dw   = LayoutCore.displayWires(m, axi, open)
      assert(dw.nonEmpty)
      dw.foreach(w => assert(pins(w.from) && pins(w.to)))
    }
  }
}
