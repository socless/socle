package socle.ir

import socle.schema._
import socle.parse._

import utest._
import ChirrtlParser.parse

object BlockTerminalsTest extends TestSuite {

  // The Er1Octet fixture of CodeBlocksTest: a `switch(state)` under `when(io.enable)`.
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
      |    output io : { flip enable : UInt<1>, flip go : UInt<1>, flip din : UInt<8>, flip miso : UInt<1>, sclk : UInt<1>, mosi : UInt<1>, busy : UInt<1>, dout : UInt<8>} @[Er.scala 11:14]
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
      |    when io.enable : @[Er.scala 36:19]
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

  private def erModule: ModuleDef =
    IrWalk.design(parse(erFir), erLines, collapse = false).modules.find(_.name == "Er1Octet").get

  private val go     = "when$Er1Octet$when_io_go$0"
  private val sw     = "fsm$Er1Octet$switch_state$0"
  private val enable = "when$Er1Octet$when_io_enable$0"

  val tests = Tests {

    test("assignments inside a block go through its terminal") {
      val m     = erModule
      val pairs = m.wires.map(w => (w.from, w.to)).toSet
      val asg   = Synthetic.assignId(go, "registre")
      assert(asg == s"asg$$$go$$registre")
      assert(pairs.contains(("registre", asg)))
      assert(pairs.contains((Synthetic.opInPin(asg, 0), "io.din")))
      assert(!pairs.contains(("registre", "io.din")))
      // The switch's own assignment of `registre` has its own terminal.
      val swAsg = Synthetic.assignId(sw, "registre")
      assert(pairs.contains(("registre", swAsg)))
    }

    test("arm conditions go to the block's condition terminal") {
      val pairs = erModule.wires.map(w => (w.from, w.to)).toSet
      assert(pairs.contains((Synthetic.opInPin(Synthetic.condId(go), 0), "io.go")))
      assert(pairs.contains((Synthetic.opInPin(Synthetic.condId(enable), 0), "io.enable")))
      // The switch's arms: one condition pin per case compare.
      val swCnd = Synthetic.condId(sw)
      assert(Set("_T", "_T_1", "_T_3").forall(c =>
        pairs.exists { case (from, to) => to == c && Synthetic.baseOf(from) == swCnd }))
    }

    test("without source lines the FSM reads the whens around and inside it") {
      val m = IrWalk.design(parse(erFir), SourceLines.none, collapse = false).modules.head
      val cnd = Synthetic.condId(sw)
      val reads: Seq[String] = m.wires.filter(w => Synthetic.baseOf(w.from) == cnd).map(_.to)
      // Around the switch (io.enable), its arms, then the plain whens inside it; once each.
      assert(reads.toSet == Set("io.enable", "_T", "_T_1", "_T_3", "io.go", "_T_2"))
      assert(m.wires.exists(_.from == Synthetic.opInPin(cnd, 0)) &&
        m.wires.find(_.from == Synthetic.opInPin(cnd, 0)).get.to == "io.enable")
      // Every connect under the switch goes through the FSM's terminals.
      val pairs = m.wires.map(w => (w.from, w.to)).toSet
      assert(pairs.contains(("registre", Synthetic.assignId(sw, "registre"))))
      assert(!pairs.contains(("registre", "io.din")))
    }

    test("terminals are op nodes of op terminal") {
      val logic = erModule.logic
      val terms = logic.filter(n => Synthetic.isTerminal(n.id))
      assert(terms.nonEmpty)
      assert(terms.forall(n => n.kind == NodeKind.Op && n.op == "terminal" && n.label.isEmpty))
      assert(terms.exists(_.id == Synthetic.assignId(go, "registre")))
      assert(terms.exists(_.id == Synthetic.condId(sw)))
    }

    test("a field sink and a memory pin give identifier-safe terminal ids") {
      assert(Synthetic.assignId("b", "io.out[1].valid") == "asg$b$io_out_1__valid")
      assert(Synthetic.assignId("b", "mem~p~w") == "asg$b$mem_p_w")
      assert(Synthetic.condId("b") == "cnd$b")
      assert(Synthetic.isTerminal("asg$b$x"), Synthetic.isTerminal("cnd$b"))
      assert(!Synthetic.isTerminal("mux$x"))
    }

    test("connects outside every block are untouched") {
      val pairs = erModule.wires.map(w => (w.from, w.to)).toSet
      assert(pairs.contains(("io.sclk", "sclk~q")) || pairs.exists { case (f, t) => f.startsWith("io") && t == "sclk~q" })
    }
  }
}
