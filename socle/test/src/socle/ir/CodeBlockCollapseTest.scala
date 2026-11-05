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
