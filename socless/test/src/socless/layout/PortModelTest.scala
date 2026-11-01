package socless.layout

import socless.schema._

import utest._

/** The declared ports of a box as rows (#332): one row per shown tree node, its
  * side from Chisel's declared directions, children opened in place. */
object PortModelTest extends TestSuite {

  private def golden(name: String): Design = {
    val s = getClass.getResourceAsStream(s"/golden/$name.json")
    require(s != null, s"missing classpath resource /golden/$name.json")
    try upickle.default.read[Design](scala.io.Source.fromInputStream(s, "UTF-8").mkString)
    finally s.close()
  }

  private lazy val rv   = golden("rvpipeline")
  private lazy val axi  = golden("axisystem")
  private lazy val gcd  = golden("gcd")
  private lazy val deep = golden("deepsoc")

  private def mod(d: Design, name: String) = d.modules.find(_.name == name).get

  private def rows(d: Design, inst: String, tpe: String, open: Set[String] = Set.empty) =
    PortModel.instanceRows(inst, mod(d, tpe), d, open)

  private def sides(rs: Seq[PortModel.Row]) = rs.map(r => r.path -> r.side)

  val tests = Tests {
    test("decode rows") {
      val rs = rows(rv, "decode", "Decode")
      assert(sides(rs) == Seq(
        "decode.io.in"  -> PinSide.West,
        "decode.io.out" -> PinSide.East,
        "decode.io.rf"  -> PinSide.East,
      ))
      assert(rs.map(_.label) == Seq("in", "out", "rf"))
      assert(rs.forall(r => r.depth == 0 && !r.open && r.expandable))
    }

    test("opening a consumer Decoupled") {
      val rs = rows(rv, "decode", "Decode", Set("decode.io.in"))
      assert(rs.head.path == "decode.io.in" && rs.head.open)
      val kids = rs.slice(1, 4)
      assert(kids.map(_.label) == Seq("valid", "ready", "bits"))
      assert(kids.map(_.dir) == Seq(Some(PortDir.In), Some(PortDir.Out), None))
      assert(kids.forall(r => r.side == PinSide.West && r.depth == 1))
      assert(kids.last.expandable && !kids.head.expandable)
      assert(rs.drop(4).map(_.path) == Seq("decode.io.out", "decode.io.rf"))
      // bits opens to its fields
      val open = rows(rv, "decode", "Decode", Set("decode.io.in", "decode.io.in.bits"))
      assert(open.filter(_.depth == 2).map(_.label) == Seq("pc", "inst"))
      assert(PortModel.attach("decode.io.in.bits.pc", rs) == Some("decode.io.in.bits"))
    }

    test("MemPort is a master by its request") {
      val rs = rows(rv, "fetch", "Fetch")
      assert(rs.find(_.path == "fetch.io.imem").get.side == PinSide.East)
      // WB drives its data memory through the same shape: a master too.
      assert(rows(rv, "wb", "WB").find(_.path == "wb.io.dmem").get.side == PinSide.East)
    }

    test("AXI master and slave") {
      // AXISystem wires each AXI4 io whole: one row each, its side by its
      // Decoupled channels (ar, aw, w produced beat r, b).
      val ends = PortModel.wireEndsOf(mod(axi, "AXISystem"))
      assert(sides(PortModel.instanceRows("cpu", mod(axi, "AXIMaster"), axi, Set.empty, ends)) == Seq("cpu.io" -> PinSide.East))
      assert(sides(PortModel.instanceRows("mem", mod(axi, "AXISlave"), axi, Set.empty, ends)) == Seq("mem.io" -> PinSide.West))
      // Inside AXIMaster its own io is wired per channel: dissolved into them.
      assert(PortModel.boundaryStubs(mod(axi, "AXIMaster"), axi, Set.empty, PortModel.wireEndsOf(mod(axi, "AXIMaster")))
        .map(_._1) == Seq("io.ar", "io.aw", "io.w", "io.r", "io.b"))
      assert(sides(rows(axi, "xbar", "AXICrossbar")) == Seq(
        "xbar.io.in" -> PinSide.West, "xbar.io.out" -> PinSide.East,
      ))
      // Children of an open row stay on its side.
      val open = rows(axi, "xbar", "AXICrossbar", Set("xbar.io.out", "xbar.io.out[0]"))
      assert(open.filter(_.path.startsWith("xbar.io.out")).forall(_.side == PinSide.East))
      assert(open.exists(_.path == "xbar.io.out[0].ar"))
    }

    test("a Vec opens to its elements in order") {
      val rs   = rows(axi, "xbar", "AXICrossbar", Set("xbar.io.in"))
      val kids = rs.filter(_.depth == 1)
      assert(kids.map(_.path) == Seq("xbar.io.in[0]", "xbar.io.in[1]"))
      assert(kids.map(_.label) == Seq("[0]", "[1]"))
      assert(kids.forall(r => r.expandable && !r.open && r.side == PinSide.West))
    }

    test("children stay open under a closed parent") {
      val closed = rows(axi, "xbar", "AXICrossbar", Set("xbar.io.in[0]"))
      assert(closed.map(_.path) == Seq("xbar.io.in", "xbar.io.out"))
      val reopened = rows(axi, "xbar", "AXICrossbar", Set("xbar.io.in", "xbar.io.in[0]"))
      val e0 = reopened.find(_.path == "xbar.io.in[0]").get
      assert(e0.open)
      assert(reopened.filter(_.depth == 2).map(_.label) == Seq("ar", "aw", "w", "r", "b"))
      assert(reopened.map(_.path).distinct.length == reopened.length)
    }

    test("unknown open paths are ignored") {
      assert(rows(rv, "decode", "Decode", Set("decode.io.nope", "zzz")) == rows(rv, "decode", "Decode"))
    }

    test("clock and reset are hidden") {
      for (m <- rv.modules ++ axi.modules; r <- PortModel.instanceRows("x", m, rv, Set.empty))
        assert(!r.path.endsWith(".clock") && !r.path.endsWith(".reset"))
      val stubIds = PortModel.boundaryStubs(mod(gcd, "GCD"), gcd, Set.empty).map(_._1)
      assert(!stubIds.contains("clock") && !stubIds.contains("reset"))
    }

    test("GCD stubs") {
      val stubs = PortModel.boundaryStubs(mod(gcd, "GCD"), gcd, Set.empty)
      assert(stubs.map(_._1) == Seq("io.in", "io.out"))
      assert(stubs.map(_._2.map(_.side)) == Seq(Seq(PinSide.East), Seq(PinSide.West)))
      val open = PortModel.boundaryStubs(mod(gcd, "GCD"), gcd, Set("io.in")).toMap
      assert(open("io.in").map(_.label) == Seq("in", "valid", "ready", "bits"))
      assert(open("io.in").forall(_.side == PinSide.East))
    }

    test("attach") {
      val closed = rows(rv, "decode", "Decode")
      assert(PortModel.attach("decode.io.in.bits.pc", closed) == Some("decode.io.in"))
      assert(PortModel.attach("decode.io.in", closed) == Some("decode.io.in"))
      assert(PortModel.attach("decode.io.inx", closed).isEmpty)
      val open = rows(rv, "decode", "Decode", Set("decode.io.in", "decode.io.in.bits"))
      assert(PortModel.attach("decode.io.in.bits.pc", open) == Some("decode.io.in.bits.pc"))
      val axiOpen = rows(axi, "xbar", "AXICrossbar", Set("xbar.io.in"))
      assert(PortModel.attach("xbar.io.in[0].aw.valid", axiOpen) == Some("xbar.io.in[0]"))
      assert(PortModel.attach("xbar.io.in", axiOpen) == Some("xbar.io.in"))
    }

    test("a ground-only module gives one level") {
      val rs = rows(deep, "alu", "ALU")
      assert(sides(rs) == Seq(
        "alu.io.a" -> PinSide.West, "alu.io.b" -> PinSide.West, "alu.io.op" -> PinSide.West,
        "alu.io.out" -> PinSide.East, "alu.io.cout" -> PinSide.East,
      ))
      assert(rs.forall(r => !r.expandable && r.dir.nonEmpty))
    }

    test("a Record port wired whole keeps its row") {
      // `a.io <> b.io`: the wire ends on the port itself, so it is one row; its
      // Decoupled channels tie (in, out), so its first one, a consumer, sets West.
      val whole = PortModel.instanceRows("decode", mod(rv, "Decode"), rv, Set.empty, Set("decode.io"))
      assert(sides(whole) == Seq("decode.io" -> PinSide.West))
      assert(whole.head.expandable && whole.head.label == "io")
      val open = PortModel.instanceRows("decode", mod(rv, "Decode"), rv, Set("decode.io"), Set("decode.io"))
      assert(open.drop(1).map(_.path) == Seq("decode.io.in", "decode.io.out", "decode.io.rf"))
      assert(PortModel.boundaryStubs(mod(gcd, "GCD"), gcd, Set.empty, Set("io")).map(_._1) == Seq("io"))
    }

    test("a Decoupled of a ground type opens to valid, ready and bits") {
      // Queue[UInt]: IrWalk names the single bits field "" (Decoupled_enq).
      val open = rows(axi, "txQ", "Queue8_UInt32", Set("txQ.io.enq"))
      assert(open.filter(_.depth == 1).map(r => r.label -> r.path) == Seq(
        "valid" -> "txQ.io.enq.valid", "ready" -> "txQ.io.enq.ready", "bits" -> "txQ.io.enq.bits"))
      assert(PortModel.attach("txQ.io.enq.bits", open) == Some("txQ.io.enq.bits"))
    }

    test("a one-way io is shown as its fields") {
      // A source module: `val io = IO(new Bundle { val a = Output(...); val b = Output(...) })`.
      val d = Design("Src", Map("IoS" -> BundleDef("IoS", BundleKind.Bundle, channels = Seq(
        ChannelDef("a", "UInt<8>", flip = false), ChannelDef("b", "UInt<4>", flip = false)))),
        Seq(ModuleDef("Src", "Src", "", SourceRef.unknown, Seq(PortDef("io", PortDir.Out, "IoS", "", SourceRef.unknown)),
          Nil, Nil, Metrics(0, 0, 0))))
      assert(sides(rows(d, "s", "Src")) == Seq("s.io.a" -> PinSide.East, "s.io.b" -> PinSide.East))
      // Any other one-way bundle port is an interface: one row.
      val other = d.copy(modules = d.modules.map(_.copy(ports = Seq(PortDef("stats", PortDir.Out, "IoS", "", SourceRef.unknown)))))
      assert(sides(rows(other, "s", "Src")) == Seq("s.stats" -> PinSide.East))
    }

    test("a Vec of signals is told apart from a Vec of bundles (#334 glyphs)") {
      assert(PortModel.isVecOfSignals("Vec[UInt<8>, 4]", axi))
      assert(PortModel.isVecOfSignals("Vec[Vec[UInt<1>, 2], 3]", axi))
      assert(!PortModel.isVecOfSignals("Vec[AXI4, 2]", axi))
      assert(!PortModel.isVecOfSignals("UInt<8>", axi))
    }

    test("a module missing from the design has no rows") {
      val ghost = ModuleDef("Ghost", "Ghost", "", SourceRef.unknown, Nil, Nil, Nil, Metrics(0, 0, 0))
      assert(PortModel.instanceRows("g", ghost, rv, Set.empty).isEmpty)
    }
  }
}
