package socle.lsp

import socle.capture.FsmSource
import socle.schema.SourceRef
import utest._

object FsmSourceTest extends TestSuite {
  private val repo = os.Path(sys.props("socle.root"))

  private def tree(files: (String, String)*): os.Path = {
    val dir = os.temp.dir(prefix = "socle-fsmsource-")
    files.foreach { case (rel, text) => os.write(dir / os.RelPath(rel), text, createFolders = true) }
    dir
  }

  /** The 1-based (line, col) of the only occurrence of `token` in `text`. */
  private def at(text: String, token: String): (Int, Int) = {
    val i = text.indexOf(token)
    assert(i >= 0, text.indexOf(token, i + 1) < 0)
    val before = text.substring(0, i)
    (before.count(_ == '\n') + 1, i - before.lastIndexOf('\n'))
  }

  /** The 1-based lines holding `token`, in order. */
  private def linesOf(text: String, token: String): Seq[Int] =
    text.linesIterator.zipWithIndex.collect { case (l, i) if l.contains(token) => i + 1 }.toSeq

  private def states(f: FsmSource): Seq[(String, Boolean)] = f.diagram.states.map(s => (s.name, s.initial))
  private def edges(f: FsmSource): Seq[(String, String, String)] = f.diagram.transitions.map(t => (t.from, t.to, t.label))
  private def edgeLines(f: FsmSource): Seq[Int] = f.diagram.transitions.map(_.source.line)

  /** A one-class module around `body`, its switch on `state` over states `a` to `d`. */
  private def module(body: String): String =
    s"""package pkg
       |
       |import chisel3._
       |import chisel3.util._
       |
       |class M extends Module {
       |  val a :: b :: c :: d :: Nil = Enum(4)
       |  val state = RegInit(a)
       |$body
       |}
       |""".stripMargin

  private val MFile = "src/main/scala/pkg/M.scala"
  private def fsmOf(body: String): Option[FsmSource] =
    new ScalaSourceIndex(tree(MFile -> module(body))).fsm("pkg.M", "state")

  /** A module with states `sIdle`, `sRun`, `sDone` around `body`, which
    * defines `state` itself. */
  private def nextFsm(body: String): Option[FsmSource] = {
    val text =
      s"""package pkg
         |
         |import chisel3._
         |import chisel3.util._
         |
         |class N extends Module {
         |  val sIdle :: sRun :: sDone :: Nil = Enum(3)
         |$body
         |}
         |""".stripMargin
    new ScalaSourceIndex(tree("src/main/scala/pkg/N.scala" -> text)).fsm("pkg.N", "state")
  }

  private val GemmFile = "src/main/scala/core/Gemm.scala"
  /** TensorGemmSimple's `switch(state)`, copied verbatim, with the later
    * `state === sIdle` chains and `io.state := state` that must not count. */
  private val gemm =
    """package core
      |
      |import chisel3._
      |import chisel3.util._
      |
      |class TensorGemmSimple(debug: Boolean = false) extends Module {
      |  val sIdle :: sReadUop :: sComputeIdx :: sReadTensor :: sExe :: sWait :: Nil =
      |    Enum(6)
      |  val state = RegInit(sIdle)
      |  io.state := state
      |  val inflight = Reg(UInt(inflightBits.W))
      |  val cond = cnt_o === dec.lp0 - 1.U &
      |    cnt_i === dec.lp1 - 1.U &
      |    uop_idx === uopEnd - 1.U
      |
      |  switch(state) {
      |    is(sIdle) {
      |      when(io.start) {
      |        state := sReadUop
      |      }
      |    }
      |    is(sReadUop) {
      |      state := sComputeIdx
      |    }
      |    is(sComputeIdx) {
      |      state := sReadTensor
      |    }
      |    is(sReadTensor) {
      |      state := sExe
      |    }
      |    is(sExe) {
      |      when(cond) {
      |        when(inflight =/= 0.U) {
      |          state := sWait
      |        }.otherwise {
      |          state := sIdle
      |        }
      |      }.otherwise {
      |        state := sReadUop
      |      }
      |    }
      |    is(sWait) {
      |      when(inflight === 0.U) {
      |        state := sIdle
      |      }
      |    }
      |  }
      |
      |  when(state === sIdle) {
      |    inflight := 0.U
      |  }.elsewhen(!dec.reset) {
      |    inflight := inflight + 1.U
      |  }
      |}
      |""".stripMargin

  val tests = Tests {
    test("a state machine built in a helper (an implicit class's method) is found by its register's position") {
      // AXI-style handlers in a real design: the module calls io.writeHandler / io.readHandler, whose bodies
      // (outside the module's class) declare and switch their own state registers. Chisel names
      // each register after the val holding the call: `w_state`, `r_state`.
      val helper =
        """package pkg
          |
          |import chisel3._
          |import chisel3.util._
          |
          |object Handlers {
          |  implicit class Port(io: Bundle) {
          |    def writeHandler(en: Bool): UInt = {
          |      object WriteState extends ChiselEnum {
          |        val idle, waddr, wdata = Value
          |      }
          |      val state = RegInit(WriteState.idle)
          |      switch(state) {
          |        is(WriteState.idle) { when(en) { state := WriteState.waddr } }
          |        is(WriteState.waddr) { state := WriteState.wdata }
          |        is(WriteState.wdata) { state := WriteState.idle }
          |      }
          |      0.U
          |    }
          |    def readHandler(en: Bool): UInt = {
          |      val idle :: raddr :: Nil = Enum(2)
          |      val state = RegInit(idle)
          |      switch(state) {
          |        is(idle) { when(en) { state := raddr } }
          |        is(raddr) { state := idle }
          |      }
          |      0.U
          |    }
          |  }
          |}
          |""".stripMargin
      val user =
        """package pkg
          |
          |import chisel3._
          |import Handlers._
          |
          |class Mock extends Module {
          |  val io = IO(new Bundle {})
          |  val r = io.readHandler(true.B)
          |  val w = io.writeHandler(true.B)
          |}
          |""".stripMargin
      val hFile = "src/main/scala/pkg/Handlers.scala"
      val idx   = new ScalaSourceIndex(tree(hFile -> helper, "src/test/scala/pkg/Mock.scala" -> user))
      val regs  = linesOf(helper, "val state = RegInit")
      def stateAt(line: Int) = Some(SourceRef(hFile, line, 19))
      // Without a position the module's own body has no switch: no diagram (the old behaviour).
      assert(idx.fsm("pkg.Mock", "w_state").isEmpty)
      val w = idx.fsm("pkg.Mock", "w_state", stateAt(regs(0))).get
      assert(states(w) == Seq("idle" -> true, "waddr" -> false, "wdata" -> false))
      assert(edges(w).map(e => (e._1, e._2)) == Seq("idle" -> "waddr", "waddr" -> "wdata", "wdata" -> "idle"))
      val r = idx.fsm("pkg.Mock", "r_state", stateAt(regs(1))).get
      assert(states(r) == Seq("idle" -> true, "raddr" -> false))
      assert(r.switch.file == hFile && r.switch.line == linesOf(helper, "switch(state)")(1))
    }
    test("trafficfsm") {
      val text = os.read(repo / "example/src/fsm/TrafficFsm.scala")
      val file = "src/fsm/TrafficFsm.scala"
      val f    = new ScalaSourceIndex(tree(file -> text)).fsm("fsm.TrafficFsm", "state").get
      assert(states(f) == Seq("sRed" -> true, "sGreen" -> false, "sYellow" -> false))
      assert(edges(f) == Seq(("sRed", "sGreen", "io.tick"), ("sGreen", "sYellow", "io.tick"), ("sYellow", "sRed", "io.tick")))
      val (gl, gc) = at(text, "state := sGreen")
      val (yl, yc) = at(text, "state := sYellow")
      val (rl, rc) = at(text, "state := sRed")
      assert(f.diagram.transitions.map(_.source) ==
        Seq(SourceRef(file, gl, gc), SourceRef(file, yl, yc), SourceRef(file, rl, rc)))
      val (sl, sc) = at(text, "switch(state)")
      assert(f.switch == SourceRef(file, sl, sc))
      val (il, ic) = at(text, "is(sRed)")
      assert(f.diagram.states.head.source == SourceRef(file, il, ic), f.diagram.note == "")
    }

    test("tensorgemm-like") {
      val f = new ScalaSourceIndex(tree(GemmFile -> gemm)).fsm("core.TensorGemmSimple", "state").get
      assert(states(f) == Seq("sIdle" -> true, "sReadUop" -> false, "sComputeIdx" -> false,
        "sReadTensor" -> false, "sExe" -> false, "sWait" -> false))
      assert(edges(f) == Seq(
        ("sIdle", "sReadUop", "io.start"),
        ("sReadUop", "sComputeIdx", ""),
        ("sComputeIdx", "sReadTensor", ""),
        ("sReadTensor", "sExe", ""),
        ("sExe", "sWait", "cond && inflight =/= 0.U"),
        ("sExe", "sIdle", "cond && !(inflight =/= 0.U)"),
        ("sExe", "sReadUop", "!cond"),
        ("sWait", "sIdle", "inflight === 0.U"),
      ))
      val toReadUop = linesOf(gemm, "state := sReadUop")
      val toIdle    = linesOf(gemm, "state := sIdle")
      assert(edgeLines(f) == Seq(toReadUop(0), linesOf(gemm, "state := sComputeIdx").head,
        linesOf(gemm, "state := sReadTensor").head, linesOf(gemm, "state := sExe").head,
        linesOf(gemm, "state := sWait").head, toIdle(0), toReadUop(1), toIdle(1)))
      assert(f.switch.line == linesOf(gemm, "switch(state)").head, f.diagram.note == "")
      assert(f.diagram.states.forall(_.source.file == GemmFile))
    }

    test("elsewhen and otherwise chain") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      when(x) { state := b }
          |        .elsewhen(y) { state := c }
          |        .otherwise { state := d }
          |    }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", "x"), ("a", "c", "!x && y"), ("a", "d", "!x && !y")))

      val nested = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      when(x) { state := b }.elsewhen(y) {
          |        when(z) { state := c }
          |      }
          |    }
          |  }""".stripMargin).get
      assert(edges(nested) == Seq(("a", "b", "x"), ("a", "c", "!x && y && z")))
    }

    test("a when nested inside otherwise") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      when(x) { state := b }.otherwise {
          |        when(y) { state := c }
          |      }
          |    }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", "x"), ("a", "c", "!x && y")))
    }

    test("a switch on another register nested in a case keeps the outer case") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      when(go) {
          |        switch(sub) {
          |          is(x) { when(y) { state := b } }
          |        }
          |      }
          |    }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", "go && y")))
    }

    test("an assignment in a helper call or lambda within a case belongs to that case") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      doIt { state := b }
          |      xs.foreach { i => when(i) { state := c } }
          |    }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", ""), ("a", "c", "i")))
    }

    test("conditions with || and ! and line breaks") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) {
          |      when(p || q) { state := b }
          |      when(r ||
          |           s) {
          |        when(t) { state := c }
          |      }.elsewhen(io.u) { state := d }
          |      when(!v) { state := a }.otherwise { state := b }
          |      when(m ===
          |        1.U) {}.otherwise { state := c }
          |      when(io.en) {}.otherwise { state := d }
          |    }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(
        ("a", "b", "p || q"),
        ("a", "c", "(r || s) && t"),
        ("a", "d", "!(r || s) && io.u"),
        ("a", "a", "!v"),
        ("a", "b", "!(!v)"),
        ("a", "c", "!(m === 1.U)"),
        ("a", "d", "!io.en"),
      ))
    }

    test("is with several states") {
      val f = fsmOf("  switch(state) {\n    is(a, b) { state := c }\n  }").get
      assert(edges(f) == Seq(("a", "c", ""), ("b", "c", "")))
      assert(states(f) == Seq("a" -> true, "b" -> false, "c" -> false, "d" -> false))
      assert(f.diagram.states.map(_.source.line) == Seq(10, 10, 0, 0))
    }

    test("assignment outside the switch is from any state") {
      val f = fsmOf(
        """  switch(state) { is(a) { state := b } }
          |  when(io.flush) { state := a }
          |  state := c""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", ""), ("*", "a", "io.flush"), ("*", "c", "")))
      assert(edgeLines(f) == Seq(9, 10, 11))
    }

    test("chisel enum states") {
      val text =
        """package pkg
          |
          |import chisel3._
          |import chisel3.util._
          |
          |object S extends ChiselEnum {
          |  val idle, run = Value
          |  val done = Value
          |}
          |
          |class E extends Module {
          |  val state = RegInit(S.idle)
          |  switch(state) {
          |    is(S.idle) { state := S.run }
          |    is(S.run) { state := S.done }
          |  }
          |}
          |""".stripMargin
      val f = new ScalaSourceIndex(tree("src/E.scala" -> text)).fsm("pkg.E", "state").get
      assert(states(f) == Seq("idle" -> true, "run" -> false, "done" -> false))
      assert(edges(f) == Seq(("idle", "run", ""), ("run", "done", "")))
      assert(f.diagram.states(2).source == SourceRef.unknown)
    }

    test("chisel enum: the same-named object that covers the is arguments") {
      val e =
        """package pkg
          |
          |class E extends Module {
          |  val state = RegInit(S.idle)
          |  switch(state) { is(S.idle) { state := S.run } }
          |}
          |""".stripMargin
      val root = tree(
        "src/A.scala" -> "package a\n\nobject S extends ChiselEnum { val other = Value }\n",
        "src/E.scala" -> e,
        "src/Z.scala" -> "package z\n\nobject S extends ChiselEnum { val idle, run, done = Value }\n",
      )
      val f = new ScalaSourceIndex(root).fsm("pkg.E", "state").get
      assert(states(f) == Seq("idle" -> true, "run" -> false, "done" -> false))
    }

    test("a switch on another register is ignored; two switches on state merge") {
      val text = module(
        """  val other = RegInit(a)
          |  switch(other) { is(a) { other := b } }
          |  switch(state) { is(a) { state := b } }
          |  switch(state) { is(b) { state := c } }""".stripMargin)
      val idx = new ScalaSourceIndex(tree(MFile -> text))
      val f   = idx.fsm("pkg.M", "state").get
      assert(edges(f) == Seq(("a", "b", ""), ("b", "c", "")), f.switch.line == 11)
      val o = idx.fsm("pkg.M", "other").get
      assert(edges(o) == Seq(("a", "b", "")), o.switch.line == 10)
      assert(idx.fsm("pkg.M", "nothing").isEmpty)
    }

    test("inherited body") {
      val base =
        """package pkg
          |
          |trait Base extends Module {
          |  val a :: b :: Nil = Enum(2)
          |  val state = RegInit(a)
          |  switch(state) { is(a) { state := b } }
          |}
          |""".stripMargin
      val root = tree(
        "src/main/scala/pkg/Base.scala" -> base,
        "src/main/scala/pkg/Leaf.scala" -> "package pkg\n\nclass Leaf extends Base {\n  when(go) { state := a }\n}\n",
        "src/main/scala/pkg/Other.scala" -> "package pkg\n\nclass Other extends Module\n",
      )
      val idx = new ScalaSourceIndex(root)
      val f   = idx.fsm("pkg.Leaf", "state").get
      assert(states(f) == Seq("a" -> true, "b" -> false))
      assert(edges(f) == Seq(("*", "a", "go"), ("a", "b", "")))
      assert(f.switch == SourceRef("src/main/scala/pkg/Base.scala", 6, 3))
      assert(f.diagram.transitions.map(_.source.file) ==
        Seq("src/main/scala/pkg/Leaf.scala", "src/main/scala/pkg/Base.scala"))
      assert(idx.fsm("pkg.Other", "state").isEmpty)
    }

    test("next-state wire: WireDefault(state) and state := next") {
      val f = nextFsm(
        """  val state = RegInit(sIdle)
          |  val next  = WireDefault(state)
          |  switch(state) {
          |    is(sIdle) { when(io.go) { next := sRun } }
          |    is(sRun)  { next := sDone }
          |    is(sDone) { next := sIdle }
          |  }
          |  state := next""".stripMargin).get
      assert(states(f) == Seq("sIdle" -> true, "sRun" -> false, "sDone" -> false))
      assert(edges(f) == Seq(("sIdle", "sRun", "io.go"), ("sRun", "sDone", ""), ("sDone", "sIdle", "")))
      assert(f.diagram.note == "")
    }

    test("next-state wire: Wire with next := state, RegNext(next, init)") {
      val f = nextFsm(
        """  val next = Wire(UInt(2.W))
          |  next := state
          |  switch(state) {
          |    is(sIdle) { when(go) { next := sRun } }
          |    is(sRun)  { next := sDone }
          |    is(sDone) { next := sIdle }
          |  }
          |  val state = RegNext(next, sIdle)""".stripMargin).get
      assert(states(f) == Seq("sIdle" -> true, "sRun" -> false, "sDone" -> false))
      assert(edges(f) == Seq(("sIdle", "sRun", "go"), ("sRun", "sDone", ""), ("sDone", "sIdle", "")))
      assert(f.diagram.note == "")
    }

    test("next-state wire: WireInit, a default hold, a from-any assignment and a computed one") {
      val f = nextFsm(
        """  val state = RegInit(sIdle)
          |  val next  = WireInit(sIdle)
          |  next := state
          |  switch(state) {
          |    is(sIdle) { when(io.go) { next := sRun } }
          |    is(sRun)  { next := Mux(x, sDone, sIdle) }
          |  }
          |  when(io.flush) { next := sIdle }
          |  state := next""".stripMargin).get
      assert(edges(f) == Seq(("sIdle", "sRun", "io.go"), ("*", "sIdle", "io.flush")))
      assert(f.diagram.note == "1 transition to a computed state is not shown.")
    }

    test("next-state wire: RegEnable(next, init, en)") {
      val f = nextFsm(
        """  val next = Wire(chiselTypeOf(sIdle))
          |  next := state
          |  switch(state) {
          |    is(sIdle) { next := sRun }
          |    is(sRun)  { when(done) { next := sIdle } }
          |  }
          |  val state = RegEnable(next, sIdle, en)""".stripMargin).get
      assert(states(f) == Seq("sIdle" -> true, "sRun" -> false, "sDone" -> false))
      assert(edges(f) == Seq(("sIdle", "sRun", ""), ("sRun", "sIdle", "done")))
      assert(f.diagram.note == "")
    }

    test("next-state wire: state := next under a when is no alias") {
      val f = nextFsm(
        """  val state = RegInit(sIdle)
          |  val next  = WireDefault(state)
          |  switch(state) {
          |    is(sIdle) { when(io.go) { next := sRun } }
          |    is(sRun)  { next := sDone }
          |  }
          |  when(en) { state := next }""".stripMargin).get
      assert(edges(f) == Nil)
      assert(f.diagram.note == "1 transition to a computed state is not shown.")
    }

    test("next-state wire: a never-assigned val (MuxLookup) is no alias, its transition stays computed") {
      val f = nextFsm(
        """  val state = RegInit(sIdle)
          |  val nxt   = MuxLookup(state, sIdle)(Seq(sIdle -> sRun, sRun -> sDone))
          |  switch(state) {
          |    is(sIdle) { }
          |    is(sRun)  { }
          |  }
          |  state := nxt""".stripMargin).get
      assert(edges(f) == Nil)
      assert(f.diagram.note == "1 transition to a computed state is not shown.")
    }

    test("next-state wire: state := next in a case of another register's switch is no alias") {
      val f = nextFsm(
        """  val state = RegInit(sIdle)
          |  val next  = WireDefault(state)
          |  switch(state) {
          |    is(sIdle) { next := sRun }
          |    is(sRun)  { next := sIdle }
          |  }
          |  switch(mode) {
          |    is(m0) { state := next }
          |  }""".stripMargin).get
      assert(edges(f) == Nil)
      assert(f.diagram.note == "1 transition to a computed state is not shown.")
    }

    test("next-state wire: RegNext(next) and RegEnable(next, en) give no initial state") {
      val body = (reg: String) =>
        s"""  val next = WireDefault(state)
           |  switch(state) {
           |    is(sIdle) { next := sRun }
           |    is(sRun)  { next := sIdle }
           |  }
           |  val state = $reg""".stripMargin
      for (reg <- Seq("RegNext(next)", "RegEnable(next, en)")) {
        val f = nextFsm(body(reg)).get
        assert(states(f) == Seq("sIdle" -> false, "sRun" -> false, "sDone" -> false))
        assert(edges(f) == Seq(("sIdle", "sRun", ""), ("sRun", "sIdle", "")), f.diagram.note == "")
      }
    }

    test("next-state wire: a state val assigned unguarded is a transition, not an alias") {
      val f = nextFsm(
        """  val sWait = 3.U(2.W)
          |  val state = RegInit(sIdle)
          |  switch(state) {
          |    is(sIdle) { state := sWait }
          |    is(sWait) { state := sIdle }
          |  }
          |  state := sWait""".stripMargin).get
      assert(edges(f) == Seq(("sIdle", "sWait", ""), ("sWait", "sIdle", ""), ("*", "sWait", "")), f.diagram.note == "")
    }

    test("computed target is skipped and counted in the note") {
      val f = fsmOf(
        """  switch(state) {
          |    is(a) { state := b; state := next }
          |    is(b) { state := Mux(x, a, b) }
          |  }""".stripMargin).get
      assert(edges(f) == Seq(("a", "b", "")))
      assert(f.diagram.note == "2 transitions to computed states are not shown.")
    }

    test("one computed target is counted in the singular") {
      val f = fsmOf("  switch(state) { is(a) { state := b; state := next } }").get
      assert(f.diagram.note == "1 transition to a computed state is not shown.")
    }

    test("no switch, or a computed is argument, gives None") {
      assert(fsmOf("  when(x) { state := b }").isEmpty)
      assert(fsmOf("  switch(state) { is(states(0)) { state := b } }").isEmpty)
      assert(fsmOf("  switch(state) { is(1.U) { state := b } }").isEmpty)
    }

    test("never throws") {
      val deep = "class Deep { val x = " + "(" * 100000 + "1" + ")" * 100000 + " }\n"
      val root = tree(
        "Deep.scala"   -> deep,
        "Broken.scala" -> "class Broken { switch(state) { is(a",
        "Empty.scala"  -> "class Empty\n",
        GemmFile       -> gemm,
      )
      val idx = new ScalaSourceIndex(root)
      assert(
        idx.fsm("Deep", "x").isEmpty,
        idx.fsm("Broken", "state").isEmpty,
        idx.fsm("Empty", "state").isEmpty,
        idx.fsm("Missing", "state").isEmpty,
        idx.fsm("", "state").isEmpty,
        idx.fsm("core.TensorGemmSimple", "").isEmpty,
      )
      // The deep file does not hide the others.
      assert(idx.fsm("core.TensorGemmSimple", "state").isDefined)
    }

    test("an unreadable file gives None, never throws") {
      val root = tree(MFile -> module("  switch(state) { is(a) { state := b } }"), GemmFile -> gemm)
      os.perms.set(root / os.RelPath(MFile), "-w-------")
      os.makeDir.all(root / "src/Dir.scala") // a directory where a file is expected
      val idx = new ScalaSourceIndex(root)
      assert(idx.fsm("pkg.M", "state").isEmpty, idx.fsm("Dir", "state").isEmpty)
      assert(idx.fsm("core.TensorGemmSimple", "state").isDefined)
    }

    test("queries parse nothing new") {
      val idx = new ScalaSourceIndex(tree(GemmFile -> gemm, MFile -> module("")))
      val first = idx.fsm("core.TensorGemmSimple", "state")
      val n     = idx.parseCount
      assert(first.isDefined, n == 2)
      assert(idx.fsm("core.TensorGemmSimple", "state") == first, idx.fsm("pkg.M", "state").isEmpty)
      val (l, c) = at(gemm, "state := sExe")
      idx.construct("core.TensorGemmSimple", GemmFile, l, c)
      assert(idx.parseCount == n)
    }
  }
}
