package socle.ir

import socle.schema._
import socle.parse._

import utest._
import ChirrtlParser.parse

object CodeBlockCollapseTest extends TestSuite {

  // The Er1Octet fixture of CodeBlocksTest (a `switch(state)` under
  // `when(io.enable)`), extended: `busy` is also assigned at module level before
  // the `when`, `io.out.valid` is assigned inside the `when` and `io.out.bits`
  // outside it. `registre` is read back inside the block (`decale`, `mosi`).
  private val erScala: String = {
    val code = Seq(
      "  when(io.enable) {",                                                // 36
      "    switch(state) {",                                                // 37
      "      is(attente) {",                                                // 38
      "        when(io.go) {",                                              // 39
      "          registre := io.din",                                       // 40
      "          cpt := 7.U",                                               // 41
      "          busy := true.B",                                           // 42
      "          state := reception",                                       // 43
      "        }",                                                          // 44
      "      }",                                                            // 45
      "      is(reception) {",                                              // 46
      "        val decale = registre(6, 0) ## io.miso",                     // 47
      "        sclk := true.B",                                             // 48
      "        registre := decale",                                         // 49
      "        when(cpt === 0.U) {",                                        // 50
      "          dout := decale; busy := false.B; state := attente",        // 51
      "        }.otherwise { cpt := cpt - 1.U; state := envoi }",           // 52
      "      }",                                                            // 53
      "      is(envoi) { sclk := false.B; mosi := registre(7); state := reception }", // 54
      "    }",                                                              // 55
      "  }",                                                                // 56
    )
    val pad = Seq.tabulate(35)(i => s"// line ${i + 1}")
    (pad ++ code).mkString("\n") + "\n"
  }

  private val erFir: String =
    """FIRRTL version 7.0.0
      |circuit Er1Octet :
      |  public module Er1Octet : @[Er.scala 10:7]
      |    input clock : Clock @[Er.scala 10:7]
      |    input reset : UInt<1> @[Er.scala 10:7]
      |    output io : { flip enable : UInt<1>, flip go : UInt<1>, flip din : UInt<8>, flip miso : UInt<1>, sclk : UInt<1>, mosi : UInt<1>, busy : UInt<1>, dout : UInt<8>, out : { valid : UInt<1>, bits : UInt<8>}} @[Er.scala 11:14]
      |
      |    regreset state : UInt<2>, clock, reset, UInt<2>(0h0) @[Er.scala 26:22]
      |    regreset registre : UInt<8>, clock, reset, UInt<8>(0h0) @[Er.scala 27:25]
      |    regreset cpt : UInt<3>, clock, reset, UInt<3>(0h0) @[Er.scala 28:20]
      |    regreset sclk : UInt<1>, clock, reset, UInt<1>(0h0) @[Er.scala 29:21]
      |    regreset mosi : UInt<1>, clock, reset, UInt<1>(0h0) @[Er.scala 30:21]
      |    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[Er.scala 31:21]
      |    regreset dout : UInt<8>, clock, reset, UInt<8>(0h0) @[Er.scala 32:21]
      |    connect io.sclk, sclk @[Er.scala 34:11]
      |    connect io.mosi, mosi @[Er.scala 34:28]
      |    connect io.busy, busy @[Er.scala 34:45]
      |    connect io.dout, dout @[Er.scala 34:62]
      |    connect io.out.bits, registre @[Er.scala 34:70]
      |    connect busy, UInt<1>(0h0) @[Er.scala 35:8]
      |    when io.enable : @[Er.scala 36:19]
      |      connect io.out.valid, UInt<1>(0h1) @[Er.scala 36:30]
      |      node _T = eq(UInt<2>(0h0), state) @[Er.scala 37:19]
      |      when _T : @[Er.scala 37:19]
      |        when io.go : @[Er.scala 39:21]
      |          connect registre, io.din @[Er.scala 40:20]
      |          connect cpt, UInt<3>(0h7) @[Er.scala 41:15]
      |          connect busy, UInt<1>(0h1) @[Er.scala 42:16]
      |          connect state, UInt<2>(0h1) @[Er.scala 43:17]
      |      else :
      |        node _T_1 = eq(UInt<2>(0h1), state) @[Er.scala 37:19]
      |        when _T_1 : @[Er.scala 37:19]
      |          node _decale_T = bits(registre, 6, 0) @[Er.scala 47:34]
      |          node decale = cat(_decale_T, io.miso) @[Er.scala 47:40]
      |          connect sclk, UInt<1>(0h1) @[Er.scala 48:14]
      |          connect registre, decale @[Er.scala 49:18]
      |          node _T_2 = eq(cpt, UInt<1>(0h0)) @[Er.scala 50:18]
      |          when _T_2 : @[Er.scala 50:27]
      |            connect dout, decale @[Er.scala 51:16]
      |            connect busy, UInt<1>(0h0) @[Er.scala 51:34]
      |            connect state, UInt<2>(0h0) @[Er.scala 51:52]
      |          else :
      |            node _cpt_T = sub(cpt, UInt<1>(0h1)) @[Er.scala 52:32]
      |            node _cpt_T_1 = tail(_cpt_T, 1) @[Er.scala 52:32]
      |            connect cpt, _cpt_T_1 @[Er.scala 52:25]
      |            connect state, UInt<2>(0h2) @[Er.scala 52:46]
      |        else :
      |          node _T_3 = eq(UInt<2>(0h2), state) @[Er.scala 37:19]
      |          when _T_3 : @[Er.scala 37:19]
      |            connect sclk, UInt<1>(0h0) @[Er.scala 54:22]
      |            node _mosi_T = bits(registre, 7, 7) @[Er.scala 54:54]
      |            connect mosi, _mosi_T @[Er.scala 54:43]
      |            connect state, UInt<2>(0h1) @[Er.scala 54:64]
      |""".stripMargin

  private val erLines = SourceLines.of(Map("Er.scala" -> erScala))

  private lazy val erDesign: Design = IrWalk.design(parse(erFir), erLines)
  private def erModule: ModuleDef   = erDesign.modules.find(_.name == "Er1Octet").get

  private val enable = "when$Er1Octet$36_19"
  private val sw     = "fsm$Er1Octet$37_19"
  private val go     = "when$Er1Octet$39_21"

  private val regs = Seq("state", "registre", "cpt", "sclk", "mosi", "busy", "dout")

  private def pairs(ws: Seq[WireDef]): Set[(String, String)] = ws.map(w => (w.from, w.to)).toSet

  // One block, two sinks that sanitize to the same token.
  private val collideFir: String =
    """FIRRTL version 7.0.0
      |circuit C :
      |  public module C : @[C.scala 1:7]
      |    input en : UInt<1> @[C.scala 1:7]
      |    output io : { a : { b : UInt<1>}, a_b : UInt<1>} @[C.scala 2:14]
      |    when en : @[C.scala 3:11]
      |      connect io.a.b, en @[C.scala 4:14]
      |      connect io.a_b, en @[C.scala 5:14]
      |""".stripMargin
  private val collideLines = SourceLines.of(Map("C.scala" -> "class C {\n// 2\n  when(en) {\n"))

  // A bulk (bundle-level) connect inside a block.
  private val bulkFir: String =
    """FIRRTL version 7.0.0
      |circuit B :
      |  public module B : @[B.scala 1:7]
      |    input en : UInt<1> @[B.scala 1:7]
      |    input x : { a : UInt<1>, b : UInt<2>} @[B.scala 2:14]
      |    output io : { out : { a : UInt<1>, b : UInt<2>}} @[B.scala 2:30]
      |    when en : @[B.scala 3:11]
      |      connect io.out, x @[B.scala 4:14]
      |""".stripMargin
  private val bulkLines = SourceLines.of(Map("B.scala" -> "class B {\n// 2\n  when(en) {\n"))

  // A Chisel `Wire` (contracted by NetTrace) assigned in one block and read in
  // another and at module level: `val next = WireDefault(state)`.
  private val wireFir: String =
    """FIRRTL version 7.0.0
      |circuit W :
      |  public module W : @[W.scala 1:7]
      |    input clock : Clock @[W.scala 1:7]
      |    input reset : UInt<1> @[W.scala 1:7]
      |    input a : UInt<1> @[W.scala 1:7]
      |    input b : UInt<1> @[W.scala 1:7]
      |    input x : UInt<2> @[W.scala 1:7]
      |    output o : UInt<2> @[W.scala 1:7]
      |    regreset state : UInt<2>, clock, reset, UInt<2>(0h0) @[W.scala 2:22]
      |    wire next : UInt<2> @[W.scala 2:40]
      |    connect next, state @[W.scala 2:40]
      |    when a : @[W.scala 3:11]
      |      connect next, x @[W.scala 3:20]
      |    when b : @[W.scala 4:11]
      |      connect state, next @[W.scala 4:20]
      |    connect o, next @[W.scala 5:5]
      |""".stripMargin
  private val wireLines = SourceLines.of(Map("W.scala" -> "class W {\n// 2\n  when(a) {\n  when(b) {\n"))

  // A `when` on a wire another block assigns: `val fire = WireDefault(false.B)`.
  private val fireFir: String =
    """FIRRTL version 7.0.0
      |circuit F :
      |  public module F : @[F.scala 1:7]
      |    input a : UInt<1> @[F.scala 1:7]
      |    input x : UInt<1> @[F.scala 1:7]
      |    output o : UInt<1> @[F.scala 1:7]
      |    wire fire : UInt<1> @[F.scala 2:20]
      |    connect fire, UInt<1>(0h0) @[F.scala 2:20]
      |    connect o, UInt<1>(0h0) @[F.scala 2:30]
      |    when a : @[F.scala 3:11]
      |      connect fire, x @[F.scala 3:20]
      |    when fire : @[F.scala 4:11]
      |      connect o, x @[F.scala 4:20]
      |""".stripMargin
  private val fireLines = SourceLines.of(Map("F.scala" -> "class F {\n  val fire = WireDefault(false.B)\n  when(a) {\n  when(fire) {\n"))

  // Under `when(p)`: `when(b)` reads `next` (assigned by its sibling `when(a)`)
  // and assigns it too, so its in pin takes the name `next` first.
  private val siblingFir: String =
    """FIRRTL version 7.0.0
      |circuit G :
      |  public module G : @[G.scala 1:7]
      |    input p : UInt<1> @[G.scala 1:7]
      |    input a : UInt<1> @[G.scala 1:7]
      |    input b : UInt<1> @[G.scala 1:7]
      |    input x : UInt<2> @[G.scala 1:7]
      |    output y : UInt<2> @[G.scala 1:7]
      |    output o : UInt<2> @[G.scala 1:7]
      |    wire next : UInt<2> @[G.scala 2:5]
      |    connect next, UInt<2>(0h0) @[G.scala 2:5]
      |    connect y, UInt<2>(0h0) @[G.scala 2:9]
      |    when p : @[G.scala 3:11]
      |      when a : @[G.scala 4:11]
      |        connect next, x @[G.scala 4:20]
      |      when b : @[G.scala 5:11]
      |        connect y, next @[G.scala 5:20]
      |        connect next, UInt<2>(0h3) @[G.scala 5:30]
      |    connect o, next @[G.scala 6:5]
      |""".stripMargin
  private val siblingLines = SourceLines.of(Map("G.scala" -> "class G {\n//2\n  when(p) {\n  when(a) {\n  when(b) {\n"))

  // A shared condition: `val c = a & b` is read by `when(c)` and at module level.
  private val sharedFir: String =
    """FIRRTL version 7.0.0
      |circuit S :
      |  public module S : @[S.scala 1:7]
      |    input a : UInt<1> @[S.scala 1:7]
      |    input b : UInt<1> @[S.scala 1:7]
      |    input x : UInt<1> @[S.scala 1:7]
      |    output o : UInt<1> @[S.scala 1:7]
      |    output p : UInt<1> @[S.scala 1:7]
      |    node c = and(a, b) @[S.scala 2:13]
      |    connect p, UInt<1>(0h0) @[S.scala 2:20]
      |    when c : @[S.scala 3:11]
      |      connect p, x @[S.scala 3:20]
      |    connect o, c @[S.scala 4:5]
      |""".stripMargin
  private val sharedLines = SourceLines.of(Map("S.scala" -> "class S {\n  val c = a & b\n  when(c) {\n"))

  // Chisel 7's CHIRRTL for `when(io.valid) { assert(io.a =/= io.b) }` (an assert
  // only) and `when(io.a > 3.U) { r := io.b; assert(io.b =/= 0.U) }`, as
  // emitted by ChiselStage.emitCHIRRTL (markers shortened to A.scala).
  private val assertFir: String =
    """FIRRTL version 7.0.0
      |circuit A :
      |  public module A : @[A.scala 6:7]
      |    input clock : Clock @[A.scala 6:7]
      |    input reset : UInt<1> @[A.scala 6:7]
      |    output io : { flip valid : UInt<1>, flip a : UInt<8>, flip b : UInt<8>, o : UInt<8>} @[A.scala 7:14]
      |
      |    regreset r : UInt<8>, clock, reset, UInt<8>(0h0) @[A.scala 13:18]
      |    connect io.o, r @[A.scala 14:8]
      |    when io.valid : @[A.scala 15:18]
      |      node _T = neq(io.a, io.b) @[A.scala 16:25]
      |      node _T_1 = eq(reset, UInt<1>(0h0)) @[A.scala 16:19]
      |      skip
      |      layerblock Verification : @[A.scala 16:19]
      |        layerblock Assert : @[A.scala 16:19]
      |          intrinsic(circt_chisel_ifelsefatal<format = "Assertion failed: a and b differ\n", label = "chisel3_builtin">, clock, _T, _T_1) @[A.scala 16:19]
      |    node _T_2 = gt(io.a, UInt<2>(0h3)) @[A.scala 18:13]
      |    when _T_2 : @[A.scala 18:20]
      |      connect r, io.b @[A.scala 19:7]
      |      node _T_3 = neq(io.b, UInt<1>(0h0)) @[A.scala 20:25]
      |      node _T_4 = eq(reset, UInt<1>(0h0)) @[A.scala 20:19]
      |      skip
      |      layerblock Verification : @[A.scala 20:19]
      |        layerblock Assert : @[A.scala 20:19]
      |          intrinsic(circt_chisel_ifelsefatal<format = "Assertion failed at A.scala:20\n", label = "chisel3_builtin">, clock, _T_3, _T_4) @[A.scala 20:19]
      |    node _T_5 = gt(io.b, UInt<3>(0h5)) @[A.scala 22:13]
      |    when _T_5 : @[A.scala 22:20]
      |      node _T_6 = neq(io.a, UInt<1>(0h0)) @[A.scala 23:25]
      |      node _T_7 = eq(reset, UInt<1>(0h0)) @[A.scala 23:19]
      |      skip
      |      layerblock Verification : @[A.scala 23:19]
      |        layerblock Assert : @[A.scala 23:19]
      |          intrinsic(circt_chisel_ifelsefatal<format = "Assertion failed at A.scala:23\n", label = "chisel3_builtin">, clock, _T_6, _T_7) @[A.scala 23:19]
      |""".stripMargin
  private val assertLines: SourceLines = SourceLines.of(Map("A.scala" -> (Seq.fill(14)("//").mkString("\n") +
    "\n  when(io.valid) {\n    assert(io.a =/= io.b, \"a and b differ\")\n  }\n  when(io.a > 3.U) {\n    r := io.b\n    assert(io.b =/= 0.U)\n  }\n  when(io.b > 5.U) {\n    assert(io.a =/= 0.U)\n  }\n")))

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  // The worktree root: `socle/test/resources` (holding /chirrtl) is three levels below.
  private def repoRoot: os.Path = {
    val url = getClass.getResource("/chirrtl")
    require(url != null, "classpath resource /chirrtl not found")
    os.Path(new java.io.File(url.toURI)) / os.up / os.up / os.up / os.up
  }

  private lazy val gcd: Design = IrWalk.design(parse(readFixture("gcd.fir")), SourceLines.fromRoot(repoRoot))

  // No box pin is named after a CHIRRTL temp (`_T`, `_T_1`, `_GEN_0`) without a label.
  private def tempPins(d: Design): Seq[String] =
    d.regions.values.toSeq.flatMap(_.ports).filter(p => p.name.startsWith("_") && p.label.isEmpty).map(_.name)

  // Every node of a box body touches one of its wires.
  private def unwired(d: Design): Seq[String] =
    d.regions.values.toSeq.flatMap { b =>
      val ends: Set[String] = b.wires.iterator.flatMap(w => Seq(w.from, w.to)).flatMap(e => Seq(e, IrWalk.fsmHeadBase(e))).toSet
      b.logic.map(_.id).filterNot(ends)
    }

  // No synthetic id in a box's pin names or labels.
  private def leaks(d: Design): Seq[String] =
    d.regions.values.toSeq.flatMap(_.ports).flatMap(p => Seq(p.name, p.label))
      .filter(s => s.contains("asg") || s.contains("cnd") || s.contains("~"))

  val tests = Tests {

    test("a when around a switch gives a box holding the FSM box") {
      val m   = erModule
      val box = m.logic.find(_.id == enable).get
      assert(box.kind == NodeKind.Block, box.label == "when(io.enable)")
      assert(box.source == SourceRef("Er.scala", 36, 19))
      assert(regs.forall(r => m.logic.exists(n => n.id == r && n.kind == NodeKind.Reg)))
      val body = erDesign.regions(enable)
      val fsm  = body.logic.find(_.id == sw).get
      assert(fsm.kind == NodeKind.Fsm, fsm.label == "switch(state)", fsm.state == "state")
      assert(fsm.fsm.isEmpty)
      // The nested boxes' bodies are top-level regions too.
      assert(erDesign.regions.contains(sw), erDesign.regions.contains(go))
      assert(erDesign.regions(sw).logic.exists(_.id == go))
      // No register is in any region.
      assert(erDesign.regions.values.forall(_.logic.forall(_.kind != NodeKind.Reg)))
    }

    test("each assigned register gets a wire from the box") {
      val ps = pairs(erModule.wires)
      assert(ps.contains(("registre", s"$enable.registre")))
      assert(ps.contains(("state", s"$enable.state")))
      // The pin of the box is an out pin named after its sink.
      val body = erDesign.regions(enable)
      assert(body.ports.find(_.name == "registre").exists(_.dir == PortDir.Out))
    }

    test("register written at module level and in a block") {
      val m       = erModule
      val drivers = m.wires.filter(_.from == "busy").map(_.to)
      val consts  = m.logic.filter(_.kind == NodeKind.Const).map(_.id).toSet
      assert(drivers.toSet.size == 2)
      assert(drivers.exists(consts.contains), drivers.contains(s"$enable.busy"))
    }

    test("field sink does not grab its sibling") {
      val m = erModule
      assert(m.wires.exists(w => w.from == "io.out.valid" && w.to.startsWith(s"$enable.")))
      assert(!m.wires.exists(w => w.from.startsWith("io.out.bits") && w.to.startsWith(s"$enable.")))
      assert(m.wires.exists(w => w.from == "io.out.bits" && w.to == "registre~q"))
    }

    test("the condition is a pin of its box") {
      val body = erDesign.regions(enable)
      val cond = body.ports.find(_.name == "cond").get
      assert(cond.dir == PortDir.In, cond.label == "io.enable")
      assert(pairs(erModule.wires).contains((s"$enable.cond", "io.enable")))
      // Nested: when(io.go) has its own condition pin.
      val goCond = erDesign.regions(go).ports.find(_.name == "cond").get
      assert(goCond.label == "io.go")
      assert(pairs(erDesign.regions(sw).wires).contains((s"$go.cond", s"$sw.io_go")))
    }

    test("an expression condition computes into its box's cond pin") {
      val fire = "when$GCD$36_20"
      val step = "when$GCD$43_25"
      val gt   = "when$GCD$44_17"
      for ((box, op, text) <- Seq((fire, "_T", "io.in.fire"), (step, "_T_2", "busy && x =/= y"), (gt, "_T_3", "x > y"))) {
        val body = gcd.regions(box)
        val cond = body.ports.find(_.name == "cond")
        assert(cond.exists(_.dir == PortDir.Out), cond.exists(_.label == text))
        assert(body.logic.exists(_.id == op))
        assert(pairs(body.wires).contains((s"$box.cond", op)))
      }
      // The whole cone of `busy && x =/= y` is in its box: `x =/= y` too.
      assert(gcd.regions(step).logic.exists(_.id == "_T_1"))
      assert(!gcd.modules.head.logic.exists(_.id == "_T_1"))
      assert(tempPins(gcd).isEmpty, unwired(gcd).isEmpty, leaks(gcd).isEmpty)
    }

    test("a switch arm's compare drives a cond pin of the switch") {
      val conds = erDesign.regions(sw).ports.filter(_.name.startsWith("cond"))
      assert(conds.map(_.label).toSet == Set("state === 0", "state === 1", "state === 2"))
      assert(conds.forall(_.dir == PortDir.Out))
      assert(tempPins(erDesign).isEmpty)
    }

    test("a condition shared with module level stays outside its box") {
      val d   = IrWalk.design(parse(sharedFir), sharedLines)
      val box = "when$S$3_11"
      assert(d.modules.head.logic.exists(_.id == "c"))
      val cond = d.regions(box).ports.find(_.name == "cond").get
      assert(cond.dir == PortDir.In, cond.label == "c")
      assert(pairs(d.modules.head.wires).contains((s"$box.cond", "c")))
    }

    test("a box holding only an assertion is layer content") {
      val d = IrWalk.design(parse(assertFir), assertLines)
      val boxes = d.modules.head.logic.filter(n => NodeKind.isBox(n.kind)).map(n => n.label -> n).toMap
      assert(boxes.keySet == Set("when(io.valid)", "when(io.a > 3.U)", "when(io.b > 5.U)"))
      // Only the assertion: hidden with the layers.
      assert(boxes("when(io.valid)").layer)
      // Only the assertion and its own condition's cone: hidden too, cone and all.
      assert(boxes("when(io.b > 5.U)").layer)
      val exprBody = d.regions(boxes("when(io.b > 5.U)").id)
      assert(exprBody.logic.exists(_.id == "_T_5"))
      assert(exprBody.logic.filter(n => n.kind == NodeKind.Op || n.kind == NodeKind.Const).forall(_.layer))
      // It also assigns `r`: design content, with its assertion inside.
      assert(!boxes("when(io.a > 3.U)").layer)
      assert(d.regions(boxes("when(io.a > 3.U)").id).logic.exists(n => n.id == "_T_3" && n.layer))
    }

    test("inside a box an assignment wires its source to its pin") {
      val goBody = erDesign.regions(go)
      assert(pairs(goBody.wires).contains((s"$go.registre", s"$go.io_din")))
      // A literal stays a const inside the box that assigns it.
      assert(goBody.logic.exists(n => n.kind == NodeKind.Const && goBody.wires.exists(w => w.from == s"$go.cpt" && w.to == n.id)))
      // The switch's own and its child's assignments of `registre` share one out pin.
      val swBody = erDesign.regions(sw)
      assert(swBody.ports.count(p => p.name.startsWith("registre") && p.dir == PortDir.Out) == 1)
      assert(pairs(swBody.wires).contains((s"$sw.registre", s"$go.registre")))
      // `decale` (a `cat`) is plumbing: its operands drive the pin.
      assert(pairs(swBody.wires).contains((s"$sw.registre", s"$sw.io_miso")))
    }

    test("no terminal survives") {
      val all: Seq[ModuleDef] = erDesign.modules ++ erDesign.regions.values
      def bad(e: String): Boolean = { val h = IrWalk.fsmHeadBase(e); h.startsWith("asg$") || h.startsWith("cnd$") }
      assert(all.forall(_.logic.forall(n => !n.id.startsWith("asg$") && !n.id.startsWith("cnd$"))))
      assert(all.forall(_.wires.forall(w => !bad(w.from) && !bad(w.to))))
      assert(leaks(erDesign).isEmpty)
      assert(unwired(erDesign).isEmpty)
    }

    test("a condition on a wire another block assigns is labelled with the condition") {
      val d    = IrWalk.design(parse(fireFir), fireLines)
      val cond = d.regions("when$F$4_11").ports.find(_.name == "cond").get
      assert(cond.label == "fire")
      assert(leaks(d).isEmpty)
      // The default `false.B` stays outside, feeding the condition pin.
      assert(unwired(d).isEmpty)
      assert(pairs(d.modules.head.wires).contains(("when$F$4_11.cond", "when$F$3_11.fire")))
    }

    test("a sink read from a sibling and assigned keeps one pin in the parent") {
      val d = IrWalk.design(parse(siblingFir), siblingLines)
      val outs = d.regions("when$G$3_11").ports.filter(_.dir == PortDir.Out).map(_.name)
      assert(outs.sorted == Seq("next", "y"))
      assert(leaks(d).isEmpty, unwired(d).isEmpty)
    }

    test("a wire assigned in a block gets one pin named after it") {
      val d    = IrWalk.design(parse(wireFir), wireLines)
      val boxA = "when$W$3_11"
      val boxB = "when$W$4_11"
      val outs = d.regions(boxA).ports.filter(_.dir == PortDir.Out)
      assert(outs.map(_.name) == Seq("next"))
      assert(leaks(d).isEmpty)
      val ps = pairs(d.modules.head.wires)
      // Read by the other block and at module level, from the one pin.
      assert(ps.contains(("o", s"$boxA.next")))
      assert(ps.exists { case (f, t) => f.startsWith(s"$boxB.") && t == s"$boxA.next" })
      assert(ps.contains(("state", s"$boxB.state")))
    }

    test("two sinks that sanitize alike get distinct terminals") {
      val open = IrWalk.design(parse(collideFir), collideLines, collapse = false).modules.head
      val terms = open.logic.filter(n => n.id.startsWith(Synthetic.AssignPrefix)).map(_.id)
      assert(terms.size == 2, terms.distinct.size == 2)
      val d    = IrWalk.design(parse(collideFir), collideLines)
      val box  = "when$C$3_11"
      val outs = d.regions(box).ports.filter(_.dir == PortDir.Out)
      assert(outs.size == 2)
      val ps = pairs(d.modules.head.wires)
      assert(ps.exists { case (f, t) => f.startsWith("io.a") && f != "io.a_b" && t.startsWith(s"$box.") })
      assert(ps.exists { case (f, t) => f == "io.a_b" && t.startsWith(s"$box.") })
    }

    test("a bulk connect inside a block keeps its bundle type") {
      val plain = IrWalk.design(parse(bulkFir)).modules.head.wires.find(_.from == "io.out").get
      val d     = IrWalk.design(parse(bulkFir), bulkLines)
      val box   = "when$B$3_11"
      val w     = d.modules.head.wires.find(_.from == "io.out").get
      assert(w.to.startsWith(s"$box."), w.bundle == plain.bundle, w.bundle != "Wire")
    }

    test("without source lines only the FSM is a box") {
      val d = IrWalk.design(parse(erFir))
      val m = d.modules.head
      val boxes = m.logic.filter(n => NodeKind.isBox(n.kind))
      assert(boxes.map(_.id) == Seq(sw), boxes.head.label == "switch(state)", boxes.head.state == "state")
      assert(pairs(m.wires).contains(("registre", s"$sw.registre")))
    }
  }
}
