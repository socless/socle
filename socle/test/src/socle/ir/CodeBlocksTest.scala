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
      // Each when arm keeps its condition as written; a switch arm has none.
      def condOf(b: CodeBlock): Seq[String] = b.whenIds.toSeq.sorted.flatMap(b.armConds.get)
      assert(condOf(enable) == Seq("io.enable"), condOf(go) == Seq("io.go"), condOf(cpt) == Seq("cpt === 0.U"))
      assert(sw.armConds.isEmpty)
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
      assert(b.armConds == Map(whenId("io.flush") -> "io.flush", whenId("io.stall") -> "io.stall", whenId("_T") -> "io.enable && io.in.valid"))
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

    // RegEnable's own `when v`, nested in the user's `when en`: its marker is the
    // RegEnable call (column 19 on the one-liner, 10 on line 4 of the block).
    def regEnableFir(libMarker: String): String =
      s"""circuit R :
         |  module R : @[R.scala 1:7]
         |    input clock : Clock
         |    input en : UInt<1>
         |    input v : UInt<1>
         |    input d : UInt<8>
         |    output o : UInt<8>
         |    reg r : UInt<8>, clock @[R.scala 2:16]
         |    when en : @[R.scala 3:12]
         |      reg r_r : UInt<8>, clock @[$libMarker]
         |      when v : @[$libMarker]
         |        connect r_r, d @[$libMarker]
         |      connect r, r_r @[R.scala 3:16]
         |    connect o, r @[R.scala 6:5]
         |""".stripMargin

    test("a library when on a one-liner user block is not a second block") {
      val lines  = SourceLines.of(Map("R.scala" -> "class R {\n  val r = Reg(UInt(8.W))\n  when(en) { r := RegEnable(d, v) }\n}\n"))
      val m      = parse(regEnableFir("R.scala 3:19")).modules.head
      val blocks = CodeBlocks.of(m, lines)
      assert(blocks.map(_.label) == Seq("when(en)"))
      val lib = m.conns.find(_.lhs == "r_r").get
      assert(CodeBlocks.blockOf(blocks, lib.whenIds).contains(blocks.head.id))
    }

    test("a library when inside a multi-line user block joins it") {
      val src    = "class R {\n  val r = Reg(UInt(8.W))\n  when(en) {\n    r := RegEnable(d, v)\n  }\n}\n"
      val m      = parse(regEnableFir("R.scala 4:10")).modules.head
      val blocks = CodeBlocks.of(m, SourceLines.of(Map("R.scala" -> src)))
      assert(blocks.map(_.label) == Seq("when(en)"))
      val lib = m.conns.find(_.lhs == "r_r").get
      assert(CodeBlocks.blockOf(blocks, lib.whenIds).contains(blocks.head.id))
      // Its connect goes through the user block's box, not a box of its own.
      val d = IrWalk.design(parse(regEnableFir("R.scala 4:10")), SourceLines.of(Map("R.scala" -> src)))
      val boxes = d.modules.head.logic.filter(n => NodeKind.isBox(n.kind))
      assert(boxes.map(_.label) == Seq("when(en)"), d.regions.size == 1)
    }

    test("callAt takes the call whose brace the marker is on") {
      assert(CodeBlocks.callAt("  when(en) { r := RegEnable(d, v) }", 19).isEmpty)
      assert(CodeBlocks.callAt("  when(en) { r := RegEnable(d, v) }", 12).contains(("when", "when(en)")))
      assert(CodeBlocks.callAt("  when(en) { cnt.inc() }", 14).isEmpty)
      assert(CodeBlocks.callAt("  when(a) { x := 1.U }.elsewhen(b) { x := 2.U }", 36).contains(("elsewhen", "elsewhen(b)")))
      // No call left of the marker: none (not a call further right).
      assert(CodeBlocks.callAt("  val r = RegEnable(d, en); when(x) { o := r }", 11).isEmpty)
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
      assert(b.kind == NodeKind.Block, b.id == "switch$S$switch_op$0", b.label == "switch(op)")
      assert(b.whenIds.size == 2, b.armConds.isEmpty)
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

    test("ids come from labels and order") {
      val ids = CodeBlocks.of(erMod(erFir), erLines).map(_.id)
      assert(ids == Seq("when$Er1Octet$when_io_enable$0", "fsm$Er1Octet$switch_state$0",
        "when$Er1Octet$when_io_go$0", "when$Er1Octet$when_cpt_0_U$0"))
      val shifted = erFir.replace("circuit Er1Octet :\n", "circuit Er1Octet :\n\n")
      assert(erMod(shifted).whens.head.id != erMod(erFir).whens.head.id)
      assert(CodeBlocks.of(erMod(shifted), erLines).map(_.id) == ids)
    }

    // Er1Octet with one line inserted above its blocks (line 36 on): markers
    // and source lines both move down.
    def erInserted(line: String): (String, SourceLines) = {
      val fir = """@\[Er\.scala (\d+):""".r.replaceAllIn(erFir, mt => {
        val n: Int = mt.group(1).toInt
        scala.util.matching.Regex.quoteReplacement(s"@[Er.scala ${if (n >= 36) n + 1 else n}:")
      })
      val (pad, code) = erScala.linesIterator.toSeq.splitAt(35)
      (fir, SourceLines.of(Map("Er.scala" -> (pad ++ Seq(line) ++ code).mkString("\n"))))
    }

    test("an edit above a block does not rename it") {
      val ids = CodeBlocks.of(erMod(erFir), erLines).map(_.id)
      Seq("", "  val unrelated = RegInit(0.U(4.W))").foreach { line =>
        val (fir, lines) = erInserted(line)
        val moved = CodeBlocks.of(erMod(fir), lines)
        assert(moved.map(_.src.get.line) != CodeBlocks.of(erMod(erFir), erLines).map(_.src.get.line))
        assert(moved.map(_.id) == ids)
      }
    }

    // Blocks of module G, one per (label, sink), at lines 2, 3...
    def gBlocks(blocks: Seq[(String, String)]): Seq[String] = {
      val body = blocks.zipWithIndex.map { case ((c, sink), i) =>
        s"    when $c : @[G.scala ${i + 2}:${c.length + 10}]\n      connect $sink, UInt<1>(0h1) @[G.scala ${i + 2}:20]\n"
      }.mkString
      val fir =
        "circuit G :\n  module G : @[G.scala 1:7]\n" +
          "    input go : UInt<1>\n    input en : UInt<1>\n    input a : { b : UInt<1>}\n    input a_b : UInt<1>\n" +
          "    output p : UInt<1>\n    output q : UInt<1>\n    output r : UInt<1>\n" + body
      val scala = ("class G {" +: blocks.map { case (c, sink) => s"  when($c) { $sink := 1.U }" }).mkString("\n") + "\n"
      CodeBlocks.of(parse(fir).modules.head, SourceLines.of(Map("G.scala" -> scala))).map(_.id)
    }

    test("a block with the same label before another shifts only that label's ordinals") {
      assert(gBlocks(Seq("go" -> "q", "en" -> "r")) == Seq("when$G$when_go$0", "when$G$when_en$0"))
      assert(gBlocks(Seq("go" -> "p", "go" -> "q", "en" -> "r")) ==
        Seq("when$G$when_go$0", "when$G$when_go$1", "when$G$when_en$0"))
    }

    test("two labels that sanitize alike get distinct ids") {
      assert(gBlocks(Seq("a.b" -> "p", "a_b" -> "q", "a.b" -> "r")) ==
        Seq("when$G$when_a_b$0", "when$G$when_a_b_2$0", "when$G$when_a_b$1"))
    }

    test("generator-loop copies on one marker get consecutive ordinals") {
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
      assert(ids == Seq("when$L$when_a$0", "when$L$when_a$1"))
    }

    test("labelToken keeps only identifier chars") {
      assert(Synthetic.labelToken("when(io.in.fire)") == "when_io_in_fire")
      assert(Synthetic.labelToken("when(busy && x =/= y)") == "when_busy_x_y")
      assert(Synthetic.labelToken("switch(state)") == "switch_state")
      assert(Synthetic.blockId("fsm", "M", "switch_state", 0) == "fsm$M$switch_state$0")
    }
  }
}
