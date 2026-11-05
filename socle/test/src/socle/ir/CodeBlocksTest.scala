package socle.ir

import socle.schema._
import socle.parse._

import utest._
import ChirrtlParser.parse

object CodeBlocksTest extends TestSuite {

  // Er1Octet (a SPI byte transceiver): a `switch(state)` under `when(io.enable)`.
  // Lines 36..56 hold the code; the markers below point at the `{` of each call.
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

  private def erMod(fir: String) = parse(fir).modules.find(_.name == "Er1Octet").get

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

  val tests = Tests {

    test("a when around a switch gives two nested blocks") {
      val blocks = CodeBlocks.of(erMod(erFir), erLines)
      assert(blocks.map(_.label) == Seq("when(io.enable)", "switch(state)", "when(io.go)", "when(cpt === 0.U)"))
      val Seq(enable, sw, go, cpt) = blocks
      assert(enable.parent.isEmpty, enable.kind == NodeKind.Block)
      assert(sw.parent.contains(enable.id))
      assert(sw.kind == NodeKind.Fsm, sw.state == "state")
      assert(sw.whenIds.size == 3)
      assert(go.parent.contains(sw.id), cpt.parent.contains(sw.id))
      assert(go.kind == NodeKind.Block, cpt.kind == NodeKind.Block)
      // The first arm's compare sits beside the switch, in the when(io.enable) block.
      assert(sw.condNodes == Set("_T", "_T_1", "_T_3"))
      assert(cpt.condNodes == Set("_T_2"))
      assert(enable.condNodes.isEmpty) // io.enable is a port, not a node
    }

    test("blockOf is the innermost block") {
      val m      = erMod(erFir)
      val blocks = CodeBlocks.of(m, erLines)
      val byLabel = blocks.map(b => b.label -> b.id).toMap
      def sinkBlock(lhs: String, rhs: String) =
        CodeBlocks.blockOf(blocks, m.conns.find(c => c.lhs == lhs && c.rhs == rhs).get.whenIds)
      assert(sinkBlock("registre", "io.din") == byLabel.get("when(io.go)"))
      assert(sinkBlock("registre", "decale") == byLabel.get("switch(state)"))
      // The `.otherwise` arm keeps its when's id.
      assert(sinkBlock("cpt", "_cpt_T_1") == byLabel.get("when(cpt === 0.U)"))
      assert(sinkBlock("io.sclk", "sclk").isEmpty)
    }

    test("otherwise and elsewhen arms join their chain") {
      val m      = parse(readFixture("regheavy.fir")).modules.find(_.name == "RegStage").get
      val blocks = CodeBlocks.of(m, SourceLines.fromRoot(repoRoot))
      assert(blocks.map(_.label) == Seq("when(io.flush)"))
      def whenId(cond: String) = m.whens.find(_.cond == cond).get.id
      val b = blocks.head
      assert(b.whenIds == Set(whenId("io.flush"), whenId("io.stall"), whenId("_T")))
      assert(b.condNodes == Set("_T"))
      assert(b.kind == NodeKind.Block, b.parent.isEmpty)
    }

    test("a source tree gives the real switch and its nested whens") {
      val circ  = parse(readFixture("regheavy.fir"))
      val lines = SourceLines.fromRoot(repoRoot)
      val ctrl  = CodeBlocks.of(circ.modules.find(_.name == "Controller").get, lines)
      assert(ctrl.map(_.label) == Seq("switch(state)", "when(timer === 15.U || io.hint)", "when(timer === 3.U)"))
      assert(ctrl.head.kind == NodeKind.Fsm, ctrl.head.whenIds.size == 3)
      assert(ctrl.tail.forall(_.parent.contains(ctrl.head.id)))
      // Queue's whens are the library's (its file is not under the root): no block.
      assert(CodeBlocks.of(circ.modules.find(_.name == "Queue2_PipePayload").get, lines).isEmpty)
    }

    test("a when on a line without when( is not a block") {
      val fir =
        """circuit R :
          |  module R : @[R.scala 1:7]
          |    input clock : Clock
          |    input en : UInt<1>
          |    input d : UInt<8>
          |    reg r : UInt<8>, clock @[R.scala 3:20]
          |    when en : @[R.scala 3:20]
          |      connect r, d @[R.scala 3:20]
          |""".stripMargin
      val lines = SourceLines.of(Map("R.scala" -> "class R {\n\n  val r = RegEnable(d, en)\n}\n"))
      assert(CodeBlocks.of(parse(fir).modules.head, lines).isEmpty)
    }

    test("a switch that is not an FSM is one block of its arms") {
      val fir =
        """circuit S :
          |  module S : @[S.scala 1:7]
          |    input op : UInt<2>
          |    output o : UInt<1>
          |    node _T = eq(op, UInt<2>(0h0)) @[S.scala 3:17]
          |    when _T : @[S.scala 3:17]
          |      connect o, UInt<1>(0h1) @[S.scala 4:25]
          |    else :
          |      node _T_1 = eq(op, UInt<2>(0h1)) @[S.scala 3:17]
          |      when _T_1 : @[S.scala 3:17]
          |        connect o, UInt<1>(0h0) @[S.scala 5:25]
          |""".stripMargin
      val lines = SourceLines.of(Map("S.scala" -> "class S {\n\n  switch(op)    {\n"))
      val blocks = CodeBlocks.of(parse(fir).modules.head, lines)
      assert(blocks.size == 1)
      val b = blocks.head
      assert(b.kind == NodeKind.Block, b.id == "switch$S$3_17", b.label == "switch(op)")
      assert(b.whenIds.size == 2, b.condNodes == Set("_T", "_T_1"))
    }

    // The Er source with line 37 (the switch) written another way.
    def erWithSwitch(line37: String): SourceLines =
      SourceLines.of(Map("Er.scala" -> erScala.replace("    switch(state) {", line37)))

    test("a spaced or qualified switch call is still the FSM") {
      for ((text, label) <- Seq(
          "    switch (state) {"             -> "switch (state)",
          "    chisel3.util.switch(state) {" -> "switch(state)")) {
        val blocks = CodeBlocks.of(erMod(erFir), erWithSwitch(text))
        val sw     = blocks.find(_.kind == NodeKind.Fsm)
        assert(sw.map(_.label).contains(label), sw.exists(_.whenIds.size == 3), blocks.size == 4)
      }
    }

    test("a recognised FSM keeps its box when its line has no call") {
      val blocks = CodeBlocks.of(erMod(erFir), erWithSwitch("    mySwitchHelper(state) {"))
      assert(blocks.map(_.label) == Seq("when(io.enable)", "switch(state)", "when(io.go)", "when(cpt === 0.U)"))
      val sw = blocks(1)
      assert(sw.kind == NodeKind.Fsm, sw.whenIds.size == 3, sw.parent.contains(blocks.head.id))
    }

    test("a spaced when call is a block") {
      val fir =
        """circuit W :
          |  module W : @[W.scala 1:7]
          |    input x : UInt<1>
          |    output o : UInt<1>
          |    when x : @[W.scala 2:12]
          |      connect o, x @[W.scala 2:16]
          |""".stripMargin
      val lines  = SourceLines.of(Map("W.scala" -> "class W {\n  when (x) { o := x }\n}\n"))
      val blocks = CodeBlocks.of(parse(fir).modules.head, lines)
      assert(blocks.map(b => (b.kind, b.label)) == Seq((NodeKind.Block, "when (x)")))
    }

    test("no source lines keeps only FSM switches") {
      val blocks = CodeBlocks.of(erMod(erFir), SourceLines.none)
      assert(blocks.size == 1)
      val b = blocks.head
      assert(b.kind == NodeKind.Fsm, b.state == "state", b.label == "switch(state)")
      assert(b.whenIds.size == 3, b.parent.isEmpty)
    }

    test("ids come from source markers") {
      val ids = CodeBlocks.of(erMod(erFir), erLines).map(_.id)
      assert(ids == Seq("when$Er1Octet$36_19", "fsm$Er1Octet$37_19", "when$Er1Octet$39_21", "when$Er1Octet$50_27"))
      val shifted = erFir.replace("circuit Er1Octet :\n", "circuit Er1Octet :\n\n")
      assert(erMod(shifted).whens.head.id != erMod(erFir).whens.head.id)
      assert(CodeBlocks.of(erMod(shifted), erLines).map(_.id) == ids)
    }

    test("a second block on the same marker gets a copy suffix") {
      val fir =
        """circuit L :
          |  module L : @[L.scala 1:7]
          |    input a : UInt<1>
          |    output o : UInt<1>[2]
          |    when a : @[L.scala 3:11]
          |      connect o[0], a @[L.scala 3:20]
          |    when a : @[L.scala 3:11]
          |      connect o[1], a @[L.scala 3:20]
          |""".stripMargin
      val lines = SourceLines.of(Map("L.scala" -> "class L {\n  for (i <- 0 until 2)\n  when(a) { o(i) := a }\n}\n"))
      val ids   = CodeBlocks.of(parse(fir).modules.head, lines).map(_.id)
      assert(ids == Seq("when$L$3_11", "when$L$3_11$1"))
    }

    test("blockId falls back to the CHIRRTL line without a marker") {
      assert(Synthetic.blockId("switch", "M", None, 7, 0) == "switch$M$L7")
    }
  }
}
