package socle.capture

import socle.SocleExporter
import socle.ir.IrWalk
import socle.layout.{Layout, LayoutCore}
import socle.schema._
import utest._

/** A captured model (committed fixtures, produced by the probe's run contract
  * on the example ChiselSim tests) adapted to the design.json schema, checked
  * against the CHIRRTL pipeline of the same elaboration as the oracle.
  *
  * Regenerating the fixtures (after a change to the probe or to CapturedModel):
  * from the repo root, build the agent with `./mill socle-probe.assembly`,
  * write the test classpath with
  * `./mill show example.test.runClasspath | python3 -c "import json,sys; print(':'.join(p.split(':',3)[-1] if p.startswith('ref:') else p for p in json.load(sys.stdin)))" > /tmp/example.cp`,
  * then run, once per fixture,
  * `java -javaagent:out/socle-probe/assembly.dest/out.jar -Dsocle.probe.mode=view -Dsocle.probe.out=socle/test/resources/captured/<name>.json -cp "$(cat /tmp/example.cp)" org.scalatest.tools.Runner -oW -s <Suite> -t "<test>"`
  * with (gcd, gcd.GcdSpec, "GCD computes gcd(48, 18)"),
  * (rvpipeline, rvpipeline.RVPipelineSpec, "RVPipeline should run a few cycles after reset"),
  * (deepsoc, deephier.DeepSocSpec, "DeepSoc elaborates and steps") and
  * (fsm, fsm.TrafficFsmSpec, "TrafficFsm cycles red, green, yellow"); scalatest
  * reports the test FAILED (ViewOnly), which is expected. Finally make the
  * source paths repo-relative: `sed -i "s|$PWD/||g" socle/test/resources/captured/[a-z]*.json`.
  */
object ModelToDesignTest extends TestSuite {

  private def resource(path: String): String = {
    val s = getClass.getResourceAsStream(path)
    require(s != null, s"missing classpath resource $path")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def captured(name: String): CapturedModel =
    CapturedModel.fromJson(resource(s"/captured/$name.json"))

  private lazy val gcd        = captured("gcd")
  private lazy val rvpipeline = captured("rvpipeline")
  private lazy val deepsoc    = captured("deepsoc")
  private lazy val fsm        = captured("fsm")

  private def check(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new java.lang.RuntimeException(msg)

  /** Every ground leaf of every port as (module, path, dir, width), expanded
    * through the design's own bundle definitions the way the UI does. */
  private def leaves(d: Design): Set[(String, String, Option[String], Int)] = {
    def walk(n: BundleTree.PortNode): Seq[BundleTree.PortNode] =
      if (n.children.isEmpty) Seq(n) else n.children.flatMap(walk)
    d.modules.flatMap { m =>
      m.ports.flatMap { p =>
        val base = if (p.dir == PortDir.Out || p.dir == PortDir.Master) PortDir.Out else PortDir.In
        walk(BundleTree.portTree(p.bundle, Some(base), p.name, d)).map(l => (m.name, l.path, l.dir, l.width))
      }
    }.toSet
  }

  private val Ground = """(UInt|SInt)(<\d+>)?|Clock|Reset|AsyncReset|Analog""".r
  private val Vec    = """Vec\[(.+), (\d+)\]""".r

  /** A type key the UI can resolve: a bundle entry, a ground type, or a Vec of one. */
  private def known(k: String, d: Design): Boolean = k match {
    case Vec(inner, _)                 => known(inner, d)
    case _ if Ground.matches(k)        => true
    case _                             => d.bundles.contains(k)
  }

  /** Module X instantiating `insts` (name -> module, all of class x.P) from
    * one source line, as a helper or Seq.fill does. */
  private def oneLine(insts: Seq[(String, String)]): ModuleDef = {
    val mods = insts.map(_._2)
    val chirrtl = (Seq("FIRRTL version 3.3.0", "circuit X :") ++
      mods.flatMap(n => Seq(s"  module $n :", "    input clock : Clock")) ++
      Seq("  module X :", "    input clock : Clock") ++
      insts.map { case (i, n) => s"    inst $i of $n @[X.scala 3:10]" }).mkString("\n") + "\n"
    def cm(n: String) = CModule(n, "x.P", Nil, None, Nil, Nil)
    val model = CapturedModel("ok", "X",
      cm("X").copy(scalaClass = "x.X", instances = insts.map { case (i, n) => CInstance(i, n) }) +: mods.map(cm),
      chirrtl = chirrtl)
    ModelToDesign.design(model).modules.find(_.name == "X").get
  }

  // The Er1Octet fixture of socle.ir.CodeBlockCollapseTest: a `switch(state)`
  // under `when(io.enable)`, seven registers, `busy` also assigned at module level.
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

  /** Er1Octet captured, with an index that reads its lines and its state machine. */
  private lazy val erCaptured: (CapturedModel, SourceIndex, FsmSource) = {
    val u1 = CType("chisel3.Bool", "Bool", "unspecified", 1)
    val u8 = CType("chisel3.UInt", "UInt", "unspecified", 8)
    def in(t: CType) = t.copy(dir = "in")
    val io = CType("", "io", "out", -1, Seq(
      "enable" -> in(u1), "go" -> in(u1), "din" -> in(u8), "miso" -> in(u1),
      "sclk" -> u1, "mosi" -> u1, "busy" -> u1, "dout" -> u8,
      "out" -> CType("", "out", "unspecified", -1, Seq("valid" -> u1, "bits" -> u8))))
    val ports = Seq(CPort("clock", CType("chisel3.Clock", "Clock", "in", -1)), CPort("reset", in(u1)), CPort("io", io))
    val model = CapturedModel("ok", "Er1Octet",
      Seq(CModule("Er1Octet", "er.Er1Octet", Nil, Some(CSource("Er.scala", 10, 7)), ports, Nil)), chirrtl = erFir)
    val src = FsmSource(FsmDiagram(Seq(FsmState("attente", initial = true), FsmState("reception"), FsmState("envoi")), Nil),
      SourceRef("Er.scala", 37, 5))
    val index = new SourceIndex {
      def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] = None
      override def fsm(scalaClass: String, register: String, at: Option[SourceRef]): Option[FsmSource] =
        if (scalaClass == "er.Er1Octet" && register == "state") Some(src) else None
      override def lines: socle.ir.SourceLines = socle.ir.SourceLines.of(Map("Er.scala" -> erScala))
    }
    (model, index, src)
  }

  // `for (i <- 0 until 2) { val r = RegInit(0.U); when(io.sel(i)) { r := io.d; s := io.d } }`
  // and, outside the loop, `when(io.sel === 3.U) { s := 0.U }` (ChiselStage's
  // CHIRRTL, markers shortened to L.scala). `s` is written inside and outside it.
  private val loopScala: String = Seq(
    "package loop", "", "import chisel3._", "", "",
    "class LoopWhen extends Module {",                       // 6
    "  val io = IO(new Bundle {",                            // 7
    "    val sel = Input(UInt(2.W))",
    "    val d   = Input(UInt(8.W))",
    "    val o   = Output(Vec(2, UInt(8.W)))",
    "    val s   = Output(UInt(8.W))",
    "  })",
    "  val s = RegInit(0.U(8.W))",                           // 13
    "  for (i <- 0 until 2) {",                              // 14
    "    val r = RegInit(0.U(8.W))",                         // 15
    "    when(io.sel(i)) { r := io.d; s := io.d }",          // 16
    "    io.o(i) := r",                                      // 17
    "  }",                                                   // 18
    "  when(io.sel === 3.U) { s := 0.U }",                   // 19
    "  io.s := s",                                           // 20
    "}").mkString("\n") + "\n"
  private val loopFir: String =
    """FIRRTL version 7.0.0
      |circuit LoopWhen :
      |  public module LoopWhen : @[L.scala 6:7]
      |    input clock : Clock @[L.scala 6:7]
      |    input reset : UInt<1> @[L.scala 6:7]
      |    output io : { flip sel : UInt<2>, flip d : UInt<8>, o : UInt<8>[2], s : UInt<8>} @[L.scala 7:14]
      |
      |    regreset s : UInt<8>, clock, reset, UInt<8>(0h0) @[L.scala 13:18]
      |    regreset r : UInt<8>, clock, reset, UInt<8>(0h0) @[L.scala 15:20]
      |    node _T = bits(io.sel, 0, 0) @[L.scala 16:16]
      |    when _T : @[L.scala 16:21]
      |      connect r, io.d @[L.scala 16:25]
      |      connect s, io.d @[L.scala 16:36]
      |    connect io.o[0], r @[L.scala 17:13]
      |    regreset r_1 : UInt<8>, clock, reset, UInt<8>(0h0) @[L.scala 15:20]
      |    node _T_1 = bits(io.sel, 1, 1) @[L.scala 16:16]
      |    when _T_1 : @[L.scala 16:21]
      |      connect r_1, io.d @[L.scala 16:25]
      |      connect s, io.d @[L.scala 16:36]
      |    connect io.o[1], r_1 @[L.scala 17:13]
      |    node _T_2 = eq(io.sel, UInt<2>(0h3)) @[L.scala 19:15]
      |    when _T_2 : @[L.scala 19:24]
      |      connect s, UInt<1>(0h0) @[L.scala 19:28]
      |    connect io.s, s @[L.scala 20:8]
      |""".stripMargin
  private lazy val loopCaptured: (CapturedModel, SourceIndex) = {
    val u2 = CType("chisel3.UInt", "UInt", "in", 2)
    val u8 = CType("chisel3.UInt", "UInt", "unspecified", 8)
    val io = CType("", "io", "out", -1, Seq("sel" -> u2, "d" -> u8.copy(dir = "in"),
      "o" -> CType("chisel3.Vec", "Vec", "unspecified", -1, Seq("0" -> u8), vecLen = 2), "s" -> u8))
    val ports = Seq(CPort("clock", CType("chisel3.Clock", "Clock", "in", -1)),
      CPort("reset", CType("chisel3.Bool", "Bool", "in", 1)), CPort("io", io))
    val model = CapturedModel("ok", "LoopWhen",
      Seq(CModule("LoopWhen", "loop.LoopWhen", Nil, Some(CSource("L.scala", 6, 7)), ports, Nil)), chirrtl = loopFir)
    val loop = Construct("for14", "for (i <- 0 until 2)", SourceRef("L.scala", 14, 3))
    val index = new SourceIndex {
      def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] =
        if (file == "L.scala" && line >= 14 && line <= 18) Some(loop) else None
      override def lines: socle.ir.SourceLines = socle.ir.SourceLines.of(Map("L.scala" -> loopScala))
    }
    (model, index)
  }

  private val ClockReset = Set("Clock", "Reset", "AsyncReset")
  /** Endpoint kinds a captured module's wires may end on. */
  private val Kept = Set(NodeKind.Inst, NodeKind.Boundary, NodeKind.Const, NodeKind.Block, NodeKind.Fsm)

  val tests = Tests {
    test("gcd structure") {
      val d      = ModelToDesign.design(gcd)
      val oracle = SocleExporter.designFromChirrtl(gcd.chirrtl)
      assert(d.top == "GCD")
      val m = d.modules.find(_.name == "GCD").get
      assert(m.ports.map(_.name) == oracle.modules.find(_.name == "GCD").get.ports.map(_.name))
      assert(m.scalaClass == "gcd.GCD")
      val io = d.bundles(m.ports.find(_.name == "io").get.bundle)
      val in = d.bundles(io.channels.find(_.name == "in").get.`type`)
      assert(in.scalaClass == "chisel3.util.DecoupledIO")
      assert(in.kind == "Decoupled")
    }

    test("deepsoc hierarchy") {
      val d      = ModelToDesign.design(deepsoc)
      val oracle = SocleExporter.designFromChirrtl(deepsoc.chirrtl)
      assert(d.top == "DeepSoc")
      assert(d.modules.map(_.name).toSet == oracle.modules.map(_.name).toSet)
      for (om <- oracle.modules) {
        val m = d.modules.find(_.name == om.name).get
        check(m.children.map(c => c.name -> c.`type`).toSet == om.children.map(c => c.name -> c.`type`).toSet,
          s"instances of ${om.name}: ${m.children} != ${om.children}")
      }
      assert(d.modules.find(_.name == "Cluster").get.parameters("nCores") == "<unreadable>")
      // Declaration order and declared types survive in parameterDecls.
      val q = d.modules.find(_.name == "Queue4_Token").get
      assert(q.parameterDecls.map(_.name) == Seq("gen", "entries", "pipe", "flow", "useSyncReadMem", "hasFlush"))
      assert(q.parameterDecls(1) == ParamDef("entries", "Int", "4"))
    }

    test("seq.fill copies share a fold group") {
      val d = ModelToDesign.design(deepsoc)
      val cluster = d.modules.find(_.name == "Cluster").get
      val cores = cluster.children.filter(_.name.startsWith("cores_"))
      assert(cores.size >= 2)
      cores.foreach(c => check(c.foldGroup == "cores", s"${c.name} foldGroup '${c.foldGroup}'"))
      val top = d.modules.find(_.name == "DeepSoc").get
      val clusters = top.children.filter(_.name.startsWith("cluster"))
      assert(clusters.size >= 2)
      clusters.foreach(c => check(c.foldGroup.isEmpty, s"${c.name} foldGroup '${c.foldGroup}'"))
    }

    test("block pins fed by generator copies take the fold group's name") {
      val d       = ModelToDesign.design(deepsoc)
      val cluster = d.modules.find(_.name == "Cluster").get
      val B       = "blk$Cluster$logic"
      val pins    = d.regions(B).ports.map(_.name)
      check(!pins.exists(_.matches("_?cores_\\d.*")), s"per-copy pins on $B: $pins")
      // In pins driven by a copy: one pin per group signal, the wires keep
      // each copy's real path.
      val fed = cluster.wires.filter(w => w.from.startsWith(s"$B.") && w.to.startsWith("cores_"))
      check(fed.map(_.to.takeWhile(_ != '.')).toSet == Set("cores_0", "cores_1"), s"copy-fed block wires $fed")
      for ((pin, ws) <- fed.groupBy(_.from)) {
        val tail = ws.map(_.to.dropWhile(_ != '.')).distinct
        check(tail.size == 1 && pin == s"$B.cores" + tail.head.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_"),
          s"$pin <- ${ws.map(_.to)}")
        check(ws.size == 2, s"$pin is fed by ${ws.map(_.to)}, not by both copies")
      }
      // Out pins feeding a copy: named after the copy's signal on the group,
      // whatever op drives each lane, so both copies hang off one pin.
      val feeding = cluster.wires.filter(w => w.from.startsWith("cores_") && w.to.startsWith(s"$B."))
      check(feeding.map(_.from.takeWhile(_ != '.')).toSet == Set("cores_0", "cores_1"), s"block wires to copies $feeding")
      for ((pin, ws) <- feeding.groupBy(_.to)) {
        val tail = ws.map(_.from.dropWhile(_ != '.')).distinct
        check(tail.size == 1 && pin == s"$B.cores" + tail.head.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_"),
          s"${ws.map(_.from)} <- $pin")
        check(ws.size == 2, s"$pin feeds ${ws.map(_.from)}, not both copies")
      }
      // Folding retargets them to the group and merges the copies' wires.
      val folded = socle.layout.InstanceFold.fold(cluster, Set.empty)
      val after  = folded.wires.filter(w => (w.from.startsWith(s"$B.") && w.to.startsWith("cores")) ||
        (w.to.startsWith(s"$B.") && w.from.startsWith("cores")))
      check(after.forall(w => w.from.startsWith("cores.") || w.to.startsWith("cores.")), s"unfolded copy wires: $after")
      check(after.size * 2 == fed.size + feeding.size, s"folding did not merge ${fed.size + feeding.size} copy wires: $after")
    }

    test("a port the block reads back and writes is one inout pin") {
      val d = ModelToDesign.design(deepsoc)
      val ports = d.regions("blk$Cluster$logic").ports.map(p => p.name -> p.dir).toMap
      check(ports.get("io_in_ready").contains(PortDir.Inout), s"Cluster block pins $ports")
      check(ports.get("io_out_valid").contains(PortDir.Inout), s"Cluster block pins $ports")
    }

    test("the baked layout folds groups") {
      val laid = Layout.withLayouts(ModelToDesign.design(deepsoc))
      val l = laid.layouts(laid.layoutOf("Cluster"))
      val ids = l.nodes.map(_.id)
      check(ids.contains("cores"), s"no cores node in $ids")
      check(!ids.contains("cores_0") && !ids.contains("cores_1"), s"copies still laid out: $ids")
    }

    test("two fill calls give two groups") {
      val chirrtl =
        """FIRRTL version 3.3.0
          |circuit X :
          |  module P :
          |    input clock : Clock
          |  module P_1 :
          |    input clock : Clock
          |  module P_2 :
          |    input clock : Clock
          |  module P_3 :
          |    input clock : Clock
          |  module X :
          |    input clock : Clock
          |    inst a_0 of P @[X.scala 3:10]
          |    inst a_1 of P_1 @[X.scala 3:10]
          |    inst b_0 of P_2 @[X.scala 4:10]
          |    inst b_1 of P_3 @[X.scala 4:10]
          |""".stripMargin
      def cm(n: String) = CModule(n, "x.P", Nil, None, Nil, Nil)
      val model = CapturedModel("ok", "X",
        Seq(cm("X").copy(scalaClass = "x.X", instances = Seq(CInstance("a_0", "P"), CInstance("a_1", "P_1"),
          CInstance("b_0", "P_2"), CInstance("b_1", "P_3"))), cm("P"), cm("P_1"), cm("P_2"), cm("P_3")),
        chirrtl = chirrtl)
      val x = ModelToDesign.design(model).modules.find(_.name == "X").get
      val groups = x.children.groupBy(_.foldGroup).filter(_._1.nonEmpty).map { case (g, cs) => g -> cs.map(_.name) }
      check(groups == Map("a" -> Seq("a_0", "a_1"), "b" -> Seq("b_0", "b_1")), s"groups $groups")
    }

    test("a group's own members never push its id to _g2") {
      val x = oneLine(Seq("m" -> "P", "m_1" -> "P_1", "m_2" -> "P_2"))
      val groups = x.children.groupBy(_.foldGroup).filter(_._1.nonEmpty).map { case (g, cs) => g -> cs.map(_.name) }
      check(groups == Map("m" -> Seq("m", "m_1", "m_2")), s"groups $groups")
    }

    test("distinct names made on one line never fold") {
      val x = oneLine(Seq("inQ" -> "P", "outQ" -> "P_1"))
      check(x.children.forall(_.foldGroup.isEmpty), s"folded: ${x.children.map(c => c.name -> c.foldGroup)}")
    }

    test("the CHIRRTL view has no fold groups") {
      val d = SocleExporter.designFromChirrtl(deepsoc.chirrtl)
      check(d.modules.flatMap(_.children).forall(_.foldGroup.isEmpty), "CHIRRTL view has a fold group")
    }

    test("block pins show their source path, not the lowered name (#333)") {
      val d     = ModelToDesign.design(gcd)
      val ports = d.regions("blk$GCD$logic").ports
      // The pin id stays lowered (paths address it as <block>.<pin>); its label is the source path.
      val bitsA = ports.find(_.name == "io_in_bits_a").get
      assert(bitsA.label == "io.in.bits.a")
      val top    = d.modules.find(_.name == "GCD").get
      val labels = LayoutCore.prepare(top, d).nodes.find(_.id == "blk$GCD$logic").get.pins.map(_.label)
      assert(labels.contains("io.in.bits.a") && !labels.exists(_.contains("io_in")))
    }

    test("captured modules carry their register and memory counts (#333)") {
      val d = ModelToDesign.design(gcd)
      val m = d.modules.find(_.name == "GCD").get.metrics
      assert(m.registers == 3 && m.lines > 0)
    }

    test("layouts bake") {
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val design = ModelToDesign.design(cm)
        val laid   = Layout.withLayouts(design)
        // A module each, and an open logic block each.
        val blocks = LayoutCore.openableBlocks(design).length
        check(laid.layoutOf.size == design.modules.size + blocks,
          s"[$label] layoutOf.size ${laid.layoutOf.size} != modules.size ${design.modules.size} + blocks $blocks - some were skipped")
        design.modules.foreach { m =>
          check(laid.layoutOf.get(m.name).exists(laid.layouts.contains), s"[$label] module ${m.name} has no resolvable layout")
        }
      }
    }

    test("ports show as declared boundary stubs") {
      // Every declared port is one stub, its pins facing the design (#332).
      val d = ModelToDesign.design(gcd)
      assert(d.showUnwiredPorts)
      val laid  = Layout.withLayouts(d)
      val nodes = laid.layouts(laid.layoutOf("GCD")).nodes
      val pins  = nodes.filter(_.kind == NodeKind.Boundary).flatMap(_.ports).map(p => p.path -> p.side).toMap
      assert(pins == Map("io.in" -> PinSide.East, "io.out" -> PinSide.West))
      assert(!pins.contains("clock"))
      assert(!pins.contains("reset"))
    }

    test("instance boxes show their full interface") {
      // Pins of each node, as path -> side, from the shared layout core.
      val d = ModelToDesign.design(deepsoc)
      def pinsOf(module: String): Map[String, Map[String, String]] =
        LayoutCore.prepare(d.modules.find(_.name == module).get, d).nodes
          .map(n => n.id -> n.pins.map(p => p.path -> p.side).toMap).toMap
      val core = pinsOf("Core")
      // alu leaves io.cout unwired; it still shows.
      check(Seq("a", "b", "op", "out", "cout").forall(n => core("alu").contains(s"alu.io.$n")), s"alu pins: ${core("alu").keys}")
      assert(core("alu")("alu.io.a") == PinSide.West)
      assert(core("alu")("alu.io.cout") == PinSide.East)
      check(Seq("raddr", "waddr", "wen", "wdata", "rdata").forall(n => core("rf").contains(s"rf.io.$n")), s"rf pins: ${core("rf").keys}")
      // Cluster: cores_* io.in wires only bits, yet each box shows its declared
      // Decoupled rows, collapsed, one per port (#332).
      val cluster = pinsOf("Cluster")
      for (c <- Seq("cores_0", "cores_1"))
        assert(cluster(c) == Map(s"$c.io.in" -> PinSide.West, s"$c.io.out" -> PinSide.East))
      assert(cluster.get("io.in").flatMap(_.get("io.in")) == Some(PinSide.East))
      // The flag no longer matters: a CHIRRTL design shows the same rows
      // (`alu.io.op := 0.U` is a constant tie-off: not drawn, still shown).
      val off  = d.copy(showUnwiredPorts = false)
      val alu  = LayoutCore.prepare(off.modules.find(_.name == "Core").get, off).nodes.find(_.id == "alu").get
      assert(alu.pins.map(_.path).toSet == Set("alu.io.a", "alu.io.b", "alu.io.op", "alu.io.out", "alu.io.cout"))
    }

    test("clock and reset stay hidden") {
      val d = ModelToDesign.design(deepsoc)
      for (m <- d.modules) {
        val nodes = LayoutCore.prepare(m, d).nodes
        val paths = nodes.flatMap(_.pins.map(_.path))
        check(!nodes.exists(n => n.kind == NodeKind.Boundary && (n.id == "reset" || n.id == "clock")), s"${m.name}: clock/reset boundary node")
        check(!paths.exists(p => p.endsWith(".reset") || p.endsWith(".clock")), s"${m.name}: clock/reset pin in ${paths}")
      }
      // The top's reset is a Bool, yet its wires keep IrWalk's Reset bundle.
      val top = d.modules.find(_.name == "DeepSoc").get
      check(top.wires.filter(_.to == "reset").forall(_.bundle == "Reset"), s"top reset wires: ${top.wires.filter(_.to == "reset")}")
    }

    test("an unknown ground class is named, never turned into UInt") {
      val enumT = CType("mypkg.Op$Type", "Type", "in", 3)
      val m = CapturedModel("ok", "Top", Seq(CModule("Top", "mypkg.Top", Nil, None,
        Seq(CPort("op", enumT), CPort("op2", enumT), CPort("b", CType("chisel3.Bool", "Bool", "out", 1))), Nil)))
      val d = ModelToDesign.design(m)
      val ports = d.modules.head.ports.map(p => p.name -> p.bundle).toMap
      assert(ports("op") == "Op$Type")
      assert(ports("b") == "UInt<1>")
      val unknown = d.warnings.filter(_.message.contains("mypkg.Op$Type"))
      assert(unknown.size == 1)
      assert(unknown.head.count == 2)
    }

    test("old json still parses") {
      val d = upickle.default.read[Design](resource("/golden/gcd.json"))
      assert(d.top == "GCD")
      assert(d.modules.forall(_.scalaClass == ""))
      assert(d.bundles.values.forall(_.scalaClass == ""))
      assert(d.modules.flatMap(_.children).forall(_.params.isEmpty))
      assert(d.modules.forall(_.parameterDecls.isEmpty))
    }

    test("field order, directions and wiring follow the CHIRRTL") {
      // Declaration order: io's channels (in, out); each Decoupled's payload
      // fields in their bundle's order. Two DecoupledIOs with different
      // payloads stay distinct bundle definitions.
      val g  = ModelToDesign.design(gcd)
      val io = g.bundles(g.modules.head.ports.find(_.name == "io").get.bundle)
      assert(io.channels.map(c => c.name -> c.flip) == Seq("in" -> true, "out" -> false))
      val Seq(in, out) = io.channels.map(c => g.bundles(c.`type`))
      assert(in.fields.map(_.name) == Seq("a", "b"))
      assert(out.fields.map(_.name) == Seq("result"))
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val d      = ModelToDesign.design(cm)
        val oracle = SocleExporter.designFromChirrtl(cm.chirrtl)
        // Port types: every leaf has the same path, direction and width.
        val (mine, theirs) = (leaves(d), leaves(oracle))
        check(mine == theirs, s"[$label] leaves differ: only adapted ${mine -- theirs}, only oracle ${theirs -- mine}")
        // Wiring between ports and instance ports: a subset of the oracle's.
        // Every wire touching a port or an instance pin has a known type key.
        for (m <- d.modules) {
          val om = oracle.modules.find(_.name == m.name).get
          def portOrInst(e: String) =
            LayoutCore.classify(e, m, d).exists(x => x.kind == NodeKind.Inst || x.kind == NodeKind.Boundary)
          val direct = m.wires.filter(w => portOrInst(w.from) && portOrInst(w.to))
          check(direct.map(w => (w.from, w.to)).toSet.subsetOf(om.wires.map(w => (w.from, w.to)).toSet),
            s"[$label] ${m.name} has wires the CHIRRTL lacks")
          for (k <- m.wires.filter(w => portOrInst(w.from) || portOrInst(w.to)).map(_.bundle) ++ m.ports.map(_.bundle))
            check(known(k, d), s"[$label] ${m.name}: type key $k is not in bundles")
        }
      }
      // Not vacuous: DeepSoc wires its clusters port to port.
      assert(ModelToDesign.design(deepsoc).modules.find(_.name == "DeepSoc").get.wires.nonEmpty)
    }

    // ── Logic blocks (slice 2) ──────────────────────────────────────────────
    test("no connection is dropped") {
      var exempt = 0
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val d      = ModelToDesign.design(cm)
        val oracle = SocleExporter.designFromChirrtl(cm.chirrtl)
        for (om <- oracle.modules) {
          val m     = d.modules.find(_.name == om.name).get
          val boxes = m.logic.filter(n => n.kind == NodeKind.Block || n.kind == NodeKind.Fsm).map(_.id)
          // The box whose body holds the logic node an IrWalk endpoint names.
          def boxOf(e: String): Option[String] = boxes.find { b =>
            d.regions.get(b).exists(_.logic.exists(n => n.id == e || n.id == IrWalk.fsmHeadBase(e)))
          }
          // The pin a box end must use: named after `src` (a register read
          // without `~q`, an IrWalk fsm pin by its pin name, a generator copy
          // by its fold group), cleaned; `_<n>` only when another driver holds
          // the plain name.
          val fsmNodes = om.logic.filter(_.kind == NodeKind.Fsm).map(_.id).toSet
          def expectedPin(src: String): String = {
            val parts = src.replaceAll("""\[[^\]]*\]$""", "").split('.').toList
            val shown =
              if (parts.size >= 2 && fsmNodes(LayoutCore.stripIdx(parts.head))) parts.tail.mkString(".")
              else {
                val h = parts.head.stripSuffix("~q")
                (m.children.find(_.name == LayoutCore.stripIdx(h)).map(_.foldGroup).filter(_.nonEmpty).getOrElse(h) :: parts.tail).mkString(".")
              }
            shown.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_").replaceAll("_+$", "")
          }
          def pinOk(b: String, now: String, src: String): Boolean = now.startsWith(s"$b.") && {
            val pin = LayoutCore.stripIdx(now.drop(b.length + 1))
            val exp = expectedPin(src)
            pin == exp || (pin.matches(java.util.regex.Pattern.quote(exp) + "_\\d+") && d.regions(b).ports.exists(_.name == exp))
          }
          def endOk(orig: String, now: String, src: String) = now == orig || boxOf(orig).exists(b => pinOk(b, now, src))
          // A const tie-off (every wire it drives ends on a port or an instance
          // pin) is dropped on purpose, with its wires, when no block holds it.
          val oracleConsts = om.logic.filter(_.kind == NodeKind.Const).map(_.id).toSet
          def portOrInst(e: String) =
            LayoutCore.classify(e, om, oracle).exists(x => x.kind == NodeKind.Inst || x.kind == NodeKind.Boundary)
          def tieOff(w: WireDef) = oracleConsts(w.to) && om.wires.filter(_.to == w.to).forall(x => portOrInst(x.from)) &&
            !m.logic.exists(_.id == w.to) && boxOf(w.to).isEmpty
          exempt += om.wires.count(tieOff)
          for (w <- om.wires if !ClockReset(w.bundle) && !tieOff(w)) {
            val internal = (boxOf(w.from), boxOf(w.to)) match {
              case (Some(a), Some(b)) if a == b => d.regions(a).wires.exists(x => x.from == w.from && x.to == w.to)
              case _                            => false
            }
            // The pin's name source: the external end without any Vec index
            // when it is a port or an instance pin, else the driver.
            val external = if (boxOf(w.to).isDefined) w.from else w.to
            val src      = if (boxOf(external).isEmpty && portOrInst(external)) external.replaceAll("""\[[^\]]*\]""", "") else w.to
            check(internal || m.wires.exists(x => endOk(w.from, x.from, src) && endOk(w.to, x.to, src)),
              s"[$label] ${om.name}: dropped ${w.from} <- ${w.to}")
          }
          // Every box end names an existing pin used along its declared
          // direction: a sink-side (`from`) pin is in/inout, a source-side
          // (`to`) pin is out/inout.
          for (w <- m.wires; (e, allowed) <- Seq(w.from -> Set(PortDir.In, PortDir.Inout), w.to -> Set(PortDir.Out, PortDir.Inout))) {
            val ep = LayoutCore.classify(e, m, d)
            check(ep.exists(x => Kept(x.kind)), s"[$label] ${m.name}: endpoint $e is ${ep.map(_.kind)}")
            ep.filter(x => x.kind == NodeKind.Block || x.kind == NodeKind.Fsm).foreach { x =>
              val pin = LayoutCore.stripIdx(e.split('.')(1))
              val dir = d.regions(x.nodeId).ports.find(_.name == pin).map(_.dir)
              check(dir.isDefined, s"[$label] ${m.name}: no pin $pin on ${x.nodeId}")
              check(dir.exists(allowed), s"[$label] ${m.name}: ${w.from} <- ${w.to} uses $pin (${dir.get}) against its direction")
            }
          }
        }
      }
      // Not vacuous: the fixtures tie ports off (rvpipeline, deepsoc's alu.io.op).
      check(exempt > 0, "no tie-off exempted")
    }

    test("logic kinds after collapse") {
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val d = ModelToDesign.design(cm)
        for (m <- d.modules; n <- m.logic) {
          check(Set(NodeKind.Fsm, NodeKind.Block, NodeKind.Const)(n.kind), s"[$label] ${m.name}: ${n.id} is ${n.kind}")
          if (n.kind != NodeKind.Const) check(d.regions.contains(n.id), s"[$label] ${m.name}: no body for ${n.id}")
        }
      }
    }

    test("gcd gets a logic block and stays readable") {
      val d = ModelToDesign.design(gcd)
      val m = d.modules.find(_.name == "GCD").get
      check(m.logic.forall(n => n.kind == NodeKind.Block || n.kind == NodeKind.Const), s"GCD logic ${m.logic.map(n => n.id -> n.kind)}")
      assert(m.logic.filter(_.kind == NodeKind.Block).map(_.id) == Seq("blk$GCD$logic"))
      // The io outputs (io.in.ready, io.out.valid, io.out.bits.result) are driven from the block.
      val outs = m.wires.filter(w => Seq("io.in.ready", "io.out.valid", "io.out.bits").exists(w.from.startsWith))
      check(outs.map(_.from).toSet == Set("io.in.ready", "io.out.valid", "io.out.bits.result"), s"io output wires $outs")
      check(outs.forall(_.to.startsWith("blk$GCD$logic.")), s"io outputs not from the block: $outs")
      // Wire bundles resolve against this design's own bundle keys.
      for (w <- m.wires) check(known(w.bundle, d), s"GCD wire bundle ${w.bundle} is not in bundles")
    }

    test("pure wiring modules get no block") {
      val top = ModelToDesign.design(deepsoc).modules.find(_.name == "DeepSoc").get
      check(top.logic.isEmpty, s"DeepSoc logic ${top.logic.map(_.id)}")
    }

    test("the CHIRRTL view is untouched") {
      val kinds = SocleExporter.designFromChirrtl(deepsoc.chirrtl).modules.flatMap(_.logic).map(_.kind).toSet
      check(kinds.contains(NodeKind.Op) && kinds.contains(NodeKind.Reg), s"classic kinds $kinds")
    }

    test("collapse keeps const, instance-pin and vec-indexed endpoints") {
      val chirrtl =
        """FIRRTL version 3.3.0
          |circuit X :
          |  module C :
          |    input clock : Clock
          |    output io : { y : UInt<8>}
          |    connect io.y, UInt<8>(5)
          |  module X :
          |    input clock : Clock
          |    input a : UInt<8>
          |    output v : UInt<8>[2]
          |    inst c of C @[X.scala 2:3]
          |    node _T = and(c.io.y, UInt<8>(3)) @[X.scala 4:5]
          |    connect v[0], _T
          |    connect v[1], a
          |""".stripMargin
      val u8    = CType("chisel3.UInt", "UInt", "unspecified", 8)
      val clock = CPort("clock", CType("chisel3.Clock", "Clock", "in", -1))
      val model = CapturedModel("ok", "X", Seq(
        CModule("X", "x.X", Nil, None, Seq(clock, CPort("a", u8.copy(dir = "in")),
          CPort("v", CType("chisel3.Vec", "Vec", "out", -1, Seq("0" -> u8), vecLen = 2))), Seq(CInstance("c", "C"))),
        CModule("C", "x.C", Nil, None, Seq(clock, CPort("io", CType("", "io", "out", -1, Seq("y" -> u8)))), Nil)),
        chirrtl = chirrtl)
      val d = ModelToDesign.design(model)
      val x = d.modules.find(_.name == "X").get
      val B = "blk$X$logic"
      assert(x.logic.map(n => n.id -> n.kind) == Seq(B -> NodeKind.Block))
      val body = d.regions(B)
      // The const's id carries the op input pin it feeds; it joins its consumer's block.
      val const = body.logic.find(_.kind == NodeKind.Const).get
      check(const.id.contains("~in"), s"const id ${const.id}")
      check(body.wires.exists(_.to == const.id), s"body wires ${body.wires}")
      val pairs = x.wires.map(w => (w.from, w.to, w.bundle))
      // IrWalk coalesces c.io.y into c.io; the pin is named after that endpoint.
      check(pairs.contains((s"$B.c_io", "c.io", "C.io")), s"instance-pin wire missing: $pairs")
      // The out pin is named after the port it drives, without the Vec index.
      check(pairs.contains(("v[0]", s"$B.v", "UInt<8>")), s"vec-indexed wire missing: $pairs")
      assert(body.ports.map(p => (p.name, p.dir)).toSet == Set(("c_io", PortDir.In), ("v", PortDir.Out)))
      check(pairs.contains(("v[1]", "a", "UInt<8>")), s"port wire missing: $pairs")
      // A const that only ties off a port is dropped with its wire: the port
      // shows as an unwired boundary stub.
      val c = d.modules.find(_.name == "C").get
      check(c.logic.isEmpty, s"C logic ${c.logic}")
      check(c.wires.isEmpty, s"C wires ${c.wires}")
      val laid = Layout.withLayouts(d)
      val stub = laid.layouts(laid.layoutOf("C")).nodes.filter(_.kind == NodeKind.Boundary).flatMap(_.ports).map(_.path)
      check(stub.exists(_.startsWith("io")), s"C boundary pins $stub")
      // The dropped tie-off is recorded so the UI can mark the port tied; its
      // sink is the wire's (IrWalk coalesces the one-leaf `io.y` into `io`).
      assert(c.tieOffs == Seq(TieOff("io", "5")))
      assert(x.tieOffs.isEmpty)
    }

    test("a captured tie-off is recorded with its sink and value") {
      val core = ModelToDesign.design(deepsoc).modules.find(_.name == "Core").get
      // Only a pin whose sole driver is one constant is tied (`alu.io.op` is 0
      // in both `when` branches). A `when` default next to another driver is
      // not: `alu.io.b` (0 or io.in.bits.data), `rf.io.wdata` (0 or alu.io.out)
      // and `rf.io.wen` (true or false).
      assert(core.tieOffs == Seq(
        TieOff("alu.io.op", "0"), TieOff("rf.io.raddr", "0"),
        TieOff("rf.io.waddr", "0"), TieOff("io.out.bits.valid", "1")))
    }

    test("a constant that another driver may override is no tie") {
      val u1 = CType("chisel3.UInt", "UInt", "in", 1)
      val u8 = CType("chisel3.UInt", "UInt", "out", 8)
      val clock = CPort("clock", CType("chisel3.Clock", "Clock", "in", -1))
      val bundle = CType("x.B", "B", "out", -1, Seq("v" -> CType("chisel3.UInt", "UInt", "unspecified", 1),
        "d" -> CType("chisel3.UInt", "UInt", "unspecified", 8)))
      def tieOffs(body: String, ports: Seq[CPort]): Seq[TieOff] = {
        val chirrtl = "FIRRTL version 3.3.0\ncircuit X :\n  module X :\n" + body.stripMargin
        ModelToDesign.design(CapturedModel("ok", "X", Seq(CModule("X", "x.X", Nil, None, clock +: ports, Nil)),
          chirrtl = chirrtl)).modules.head.tieOffs
      }
      // A dynamic index may write any lane.
      val vec = CType("chisel3.Vec", "Vec", "out", -1, Seq("0" -> CType("chisel3.UInt", "UInt", "unspecified", 8)), vecLen = 2)
      val dyn = tieOffs(
        """    input clock : Clock
          |    input i : UInt<1>
          |    input s : UInt<8>
          |    output o : UInt<8>[2]
          |    connect o[0], UInt<8>(0h0)
          |    connect o[1], UInt<8>(0h0)
          |    connect o[i], s
          |""", Seq(CPort("i", u1), CPort("s", u8.copy(dir = "in")), CPort("o", vec)))
      assert(dyn.isEmpty)
      // A bulk connect of the parent drives the field too.
      val bulk = tieOffs(
        """    input clock : Clock
          |    input c : UInt<1>
          |    input i : { v : UInt<1>, d : UInt<8>}
          |    output o : { v : UInt<1>, d : UInt<8>}
          |    connect o, i
          |    when c :
          |      connect o.v, UInt<1>(0h0)
          |""", Seq(CPort("c", u1), CPort("i", bundle.copy(dir = "in")), CPort("o", bundle)))
      assert(bulk.isEmpty)
      // Through a wire whose nested `when`s give it other values.
      val alias = tieOffs(
        """    input clock : Clock
          |    input c : UInt<1>
          |    input d : UInt<1>
          |    output a : UInt<8>
          |    wire w : UInt<8>
          |    connect w, UInt<8>(0h0)
          |    when c :
          |      connect w, UInt<8>(0h1)
          |    else :
          |      when d :
          |        connect w, UInt<8>(0h2)
          |    connect a, w
          |""", Seq(CPort("c", u1), CPort("d", u1), CPort("a", u8)))
      assert(alias.isEmpty)
      // A default a nested `when` overrides with a signal.
      val nested = tieOffs(
        """    input clock : Clock
          |    input c : UInt<1>
          |    input d : UInt<1>
          |    input s : UInt<8>
          |    output a : UInt<8>
          |    connect a, UInt<8>(0h0)
          |    when c :
          |      when d :
          |        connect a, s
          |""", Seq(CPort("c", u1), CPort("d", u1), CPort("s", u8.copy(dir = "in")), CPort("a", u8)))
      assert(nested.isEmpty)
      // A flipped field is driven through the right side of a bulk connect
      // (`io.out <> io.in; when(stall) { io.in.ready := false.B }`).
      val flipT = CType("x.D", "D", "out", -1, Seq("v" -> CType("chisel3.UInt", "UInt", "unspecified", 1),
        "r" -> CType("chisel3.UInt", "UInt", "flip", 1)))
      val flip = tieOffs(
        """    input clock : Clock
          |    input c : UInt<1>
          |    input i : { v : UInt<1>, flip r : UInt<1>}
          |    output o : { v : UInt<1>, flip r : UInt<1>}
          |    connect o, i
          |    when c :
          |      connect i.r, UInt<1>(0h0)
          |""", Seq(CPort("c", u1), CPort("i", flipT.copy(dir = "flip")), CPort("o", flipT)))
      assert(flip.isEmpty)
      // A sole constant is a tie, a lane too (Core's `alu.io.op`, 0 in both
      // `when` branches, is the captured case).
      val same = tieOffs(
        """    input clock : Clock
          |    output a : UInt<8>
          |    output o : UInt<8>[2]
          |    connect a, UInt<8>(0h3)
          |    connect o[1], UInt<8>(0h0)
          |""", Seq(CPort("a", u8), CPort("o", vec)))
      assert(same.toSet == Set(TieOff("a", "3"), TieOff("o[1]", "0")))
    }

    test("tie-off values read as numbers") {
      val chirrtl =
        """FIRRTL version 3.3.0
          |circuit X :
          |  module X :
          |    input clock : Clock
          |    output a : UInt<8>
          |    output b : UInt<8>
          |    output c : SInt<8>
          |    output d : UInt<8>
          |    connect a, UInt<8>(0h2A)
          |    connect b, UInt<8>(0h9)
          |    connect c, SInt<8>(-0h14)
          |    connect d, UInt<8>(0b1010)
          |""".stripMargin
      val u8 = CType("chisel3.UInt", "UInt", "out", 8)
      val model = CapturedModel("ok", "X", Seq(CModule("X", "x.X", Nil, None,
        Seq(CPort("clock", CType("chisel3.Clock", "Clock", "in", -1)), CPort("a", u8), CPort("b", u8),
          CPort("c", CType("chisel3.SInt", "SInt", "out", 8)), CPort("d", u8)), Nil)), chirrtl = chirrtl)
      val x = ModelToDesign.design(model).modules.head
      assert(x.tieOffs.toSet == Set(TieOff("a", "0x2a"), TieOff("b", "9"), TieOff("c", "-0x14"), TieOff("d", "0xa")))
    }

    test("old json without tie-offs parses") {
      val d = upickle.default.read[Design](resource("/golden/gcd.json"))
      assert(d.modules.forall(_.tieOffs.isEmpty))
    }

    test("the CHIRRTL view records no tie-offs") {
      for (cap <- Seq(gcd, rvpipeline, deepsoc, fsm)) {
        val d = SocleExporter.designFromChirrtl(cap.chirrtl)
        check(d.modules.forall(_.tieOffs.isEmpty), s"${d.top}: CHIRRTL module carries tie-offs")
      }
    }

    test("the lanes of a Vec port share one block pin") {
      val chirrtl =
        """FIRRTL version 3.3.0
          |circuit X :
          |  module X :
          |    input clock : Clock
          |    input w : UInt<8>[2]
          |    output v : UInt<8>[2][1]
          |    output y : UInt<8>
          |    node _T = and(w[0], w[1]) @[X.scala 4:5]
          |    node _T_1 = or(w[0], w[1]) @[X.scala 5:5]
          |    node _T_2 = xor(_T, _T_1) @[X.scala 6:5]
          |    connect v[0][0], _T
          |    connect v[0][1], _T_1
          |    connect y, _T_2
          |""".stripMargin
      val u8   = CType("chisel3.UInt", "UInt", "unspecified", 8)
      def vec(e: CType, n: Int, dir: String) = CType("chisel3.Vec", "Vec", dir, -1, Seq("0" -> e), vecLen = n)
      val model = CapturedModel("ok", "X", Seq(CModule("X", "x.X", Nil, None, Seq(
        CPort("clock", CType("chisel3.Clock", "Clock", "in", -1)), CPort("w", vec(u8, 2, "in")),
        CPort("v", vec(vec(u8, 2, "unspecified"), 1, "out")), CPort("y", u8.copy(dir = "out"))), Nil)), chirrtl = chirrtl)
      val d = ModelToDesign.design(model)
      val x = d.modules.head
      val B = "blk$X$logic"
      assert(d.regions(B).ports.map(p => (p.name, p.dir)).toSet == Set(("w", PortDir.In), ("v", PortDir.Out), ("y", PortDir.Out)))
      val pairs = x.wires.map(w => (w.from, w.to)).toSet
      check(Set((s"$B.w", "w[0]"), (s"$B.w", "w[1]"), ("v[0][0]", s"$B.v"), ("v[0][1]", s"$B.v"), ("y", s"$B.y")).subsetOf(pairs),
        s"wires $pairs")
    }

    test("a block's own source file is embedded") {
      val dir = os.temp.dir()
      os.write(dir / "Top.scala", "class Top\n")
      os.write(dir / "Gen.scala", "object Gen\n")
      val chirrtl =
        """FIRRTL version 3.3.0
          |circuit Top :
          |  module Top : @[Top.scala 1:1]
          |    input clock : Clock @[Top.scala 1:1]
          |    input a : UInt<8> @[Top.scala 1:1]
          |    input b : UInt<8> @[Top.scala 1:1]
          |    output y : UInt<8> @[Top.scala 1:1]
          |    node _T = and(a, b) @[Gen.scala 1:1]
          |    connect y, _T @[Top.scala 1:1]
          |""".stripMargin
      val u8 = CType("chisel3.UInt", "UInt", "in", 8)
      val model = CapturedModel("ok", "Top", Seq(CModule("Top", "x.Top", Nil, Some(CSource("Top.scala", 1, 1)),
        Seq(CPort("clock", CType("chisel3.Clock", "Clock", "in", -1)), CPort("a", u8), CPort("b", u8),
          CPort("y", u8.copy(dir = "out"))), Nil)), chirrtl = chirrtl)
      val index = new SourceIndex {
        def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] =
          if (file == "Gen.scala") Some(Construct("g", "for (i <- 0 until 2)", SourceRef("Gen.scala", 1, 1))) else None
      }
      val d = SocleExporter.designFromCaptured(model, Some(dir), Some(index))
      val top = d.modules.find(_.name == "Top").get
      check(top.logic.exists(n => n.source.file == "Gen.scala"), s"Top logic ${top.logic}")
      check(d.sources.contains("Gen.scala"), s"sources ${d.sources.keySet}")
    }

    test("an fsm block carries the diagram from the source index") {
      val at      = SourceRef("example/src/fsm/TrafficFsm.scala", 16, 33)
      val diagram = FsmDiagram(
        states      = Seq(FsmState("sRed", initial = true), FsmState("sGreen"), FsmState("sYellow")),
        transitions = Seq(FsmTransition("sRed", "sGreen", "io.tick", at),
          FsmTransition("sGreen", "sYellow", "io.tick", at), FsmTransition("sYellow", "sRed", "io.tick", at)))
      val switch  = SourceRef("example/src/fsm/TrafficFsm.scala", 15, 3)
      val index = new SourceIndex {
        def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] = None
        override def fsm(scalaClass: String, register: String, at: Option[SourceRef]): Option[FsmSource] =
          if (scalaClass == "fsm.TrafficFsm" && register == "state") Some(FsmSource(diagram, switch)) else None
      }
      val m     = ModelToDesign.design(fsm, Some(index)).modules.find(_.name == "TrafficFsm").get
      val block = m.logic.find(_.id == "fsm$TrafficFsm$15_17").get
      // as the index gives it, with the encodings FsmEncoding can pair: here
      // only the reset's (the three transitions share one line, which has one connect)
      assert(block.fsm == Some(diagram.copy(states = diagram.states.map(s => if (s.initial) s.copy(value = "0") else s))))
      assert(block.source == switch)
    }

    test("a when around a switch keeps both boxes and the diagram") {
      val (model, index, _) = erCaptured
      val d       = ModelToDesign.design(model, Some(index))
      val m       = d.modules.find(_.name == "Er1Octet").get
      val enable  = "when$Er1Octet$36_19"
      val sw      = "fsm$Er1Octet$37_19"
      val box     = m.logic.find(_.id == enable).get
      assert(box.kind == NodeKind.Block, box.label == "when(io.enable)")
      val regs    = Seq("state", "registre", "cpt", "sclk", "mosi", "busy", "dout")
      check(regs.forall(r => m.logic.exists(n => n.id == r && n.kind == NodeKind.Reg)), s"module logic ${m.logic.map(n => n.id -> n.kind)}")
      val pairs = m.wires.map(w => (w.from, w.to)).toSet
      // Each register is written from the box, and read out to its port.
      for (r <- regs) check(pairs.contains((r, s"$enable.$r")), s"no $r <- $enable.$r in $pairs")
      for (r <- Seq("sclk", "mosi", "busy", "dout")) check(pairs.contains((s"io.$r", s"$r~q")), s"no io.$r <- $r~q in $pairs")
      // `busy` keeps its module-level driver too; the condition reaches its pin.
      check(m.wires.count(_.from == "busy") == 2, s"busy drivers ${m.wires.filter(_.from == "busy")}")
      check(pairs.contains((s"$enable.cond", "io.enable")), s"no condition wire in $pairs")
      // The FSM box sits in the when box's body, with its diagram.
      val inner = d.regions(enable).logic.find(_.id == sw).get
      assert(inner.kind == NodeKind.Fsm, inner.state == "state")
      check(inner.fsm.isDefined, s"no diagram on $sw")
      assert(inner.fsm.get.states.map(_.name) == Seq("attente", "reception", "envoi"))
      // Nested bodies are regions too.
      assert(d.regions.contains(sw), d.regions.contains("when$Er1Octet$39_21"))
    }

    test("a when in a generator loop and its registers stay in the loop's box") {
      val (model, index) = loopCaptured
      val d    = ModelToDesign.design(model, Some(index))
      val m    = d.modules.find(_.name == "LoopWhen").get
      val gen  = m.logic.find(n => n.kind == NodeKind.Block && n.label == "for (i <- 0 until 2)")
      check(gen.isDefined, s"module logic ${m.logic.map(n => n.id -> n.label)}")
      val body = d.regions(gen.get.id)
      val held = body.logic.map(_.id).toSet
      // The loop's when boxes and the registers made in the loop are in its box.
      check(Set("when$LoopWhen$16_21", "when$LoopWhen$16_21$1", "r", "r_1").subsetOf(held), s"loop body $held")
      // Their bodies open from there.
      check(d.regions.contains("when$LoopWhen$16_21") && d.regions.contains("when$LoopWhen$16_21$1"), s"regions ${d.regions.keySet}")
      // `s`, written by the loop and by a when outside it, stays plain, as does
      // the outside when box.
      check(m.logic.exists(n => n.id == "s" && n.kind == NodeKind.Reg), s"module logic ${m.logic.map(_.id)}")
      check(m.logic.exists(_.id == "when$LoopWhen$19_24"), s"module logic ${m.logic.map(_.id)}")
      check(!held("s") && !held("when$LoopWhen$19_24"), s"loop body $held")
      // Every output port is still driven.
      for (p <- Seq("io.o[0]", "io.o[1]", "io.s")) check(m.wires.exists(_.from == p), s"no wire into $p in ${m.wires}")
    }

    test("with source lines the code blocks keep every port wire") {
      // The IDE path: the index reads the repo's sources, so gcd's and Core's
      // `when`s are boxes and the registers they write are plain nodes.
      val url  = getClass.getResource("/captured")
      val root = os.Path(new java.io.File(url.toURI)) / os.up / os.up / os.up / os.up
      val index = new SourceIndex {
        def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] = None
        override val lines: socle.ir.SourceLines = socle.ir.SourceLines.fromRoot(root)
      }
      var boxed = 0
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val d      = ModelToDesign.design(cm, Some(index))
        val oracle = IrWalk.design(socle.parse.ChirrtlParser.parse(cm.chirrtl), index.lines)
        for (om <- oracle.modules) {
          val m = d.modules.find(_.name == om.name).get
          boxed += m.logic.count(n => n.id.startsWith("when$") || n.id.startsWith("fsm$"))
          def portOrInst(e: String, in: ModuleDef, des: Design) =
            LayoutCore.classify(e, in, des).exists(x => x.kind == NodeKind.Inst || x.kind == NodeKind.Boundary)
          val tied = m.tieOffs.map(_.sink).toSet
          val consts = om.logic.filter(_.kind == NodeKind.Const).map(_.id).toSet
          for (w <- om.wires if !ClockReset(w.bundle)) {
            if (portOrInst(w.from, om, oracle) && !consts(w.to))
              check(m.wires.exists(_.from == w.from) || tied(w.from), s"[$label] ${om.name}: lost the wire into ${w.from} (<- ${w.to})")
            if (portOrInst(w.to, om, oracle))
              check(m.wires.exists(_.to == w.to), s"[$label] ${om.name}: lost the wire out of ${w.to} (-> ${w.from})")
          }
          for (w <- m.wires; (e, allowed) <- Seq(w.from -> Set(PortDir.In, PortDir.Inout), w.to -> Set(PortDir.Out, PortDir.Inout))) {
            val ep = LayoutCore.classify(e, m, d)
            check(ep.exists(x => (Kept + NodeKind.Reg)(x.kind)), s"[$label] ${m.name}: endpoint $e is ${ep.map(_.kind)}")
            ep.filter(x => NodeKind.isBox(x.kind)).foreach { x =>
              val pin = LayoutCore.stripIdx(e.split('.')(1))
              val dir = d.regions(x.nodeId).ports.find(_.name == pin).map(_.dir)
              check(dir.exists(allowed), s"[$label] ${m.name}: ${w.from} <- ${w.to} uses pin $pin ($dir)")
            }
          }
        }
      }
      // Not vacuous: gcd's and deepsoc's `when`s are boxes.
      check(boxed > 0, "no code-block box in any fixture")
    }

    test("from a capture with a source root the code blocks are boxes") {
      val url  = getClass.getResource("/captured")
      val root = os.Path(new java.io.File(url.toURI)) / os.up / os.up / os.up / os.up
      val m    = SocleExporter.designFromCaptured(gcd, Some(root)).modules.find(_.name == "GCD").get
      val whens = m.logic.filter(n => n.kind == NodeKind.Block && n.id.startsWith("when$GCD$"))
      check(whens.size == 3, s"GCD logic ${m.logic.map(_.id)}")
      check(Seq("x", "y", "busy").forall(r => m.logic.exists(n => n.id == r && n.kind == NodeKind.Reg)), s"GCD logic ${m.logic.map(_.id)}")
      // Without a source root, as before: no code-block box.
      check(!SocleExporter.designFromCaptured(gcd, None).modules.flatMap(_.logic).exists(_.id.startsWith("when$")), "a when box without lines")
    }

    test("without an index the fsm block falls back to case literals") {
      val m     = ModelToDesign.design(fsm).modules.find(_.name == "TrafficFsm").get
      val block = m.logic.find(_.id == "fsm$TrafficFsm$15_17").get
      assert(block.fsm == Some(FsmDiagram(Seq(FsmState("0"), FsmState("1"), FsmState("2")), Nil,
        "Transitions are not shown: this view was built without the Scala source index.")))
    }

    test("an index that cannot read the fsm falls back with the read note") {
      val index = new SourceIndex {
        def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] = None
      }
      val m     = ModelToDesign.design(fsm, Some(index)).modules.find(_.name == "TrafficFsm").get
      val block = m.logic.find(_.id == "fsm$TrafficFsm$15_17").get
      assert(block.fsm == Some(FsmDiagram(Seq(FsmState("0"), FsmState("1"), FsmState("2")), Nil,
        "Transitions are not available: the state machine's Scala source could not be read.")))
    }

    test("the sources embed the files of fsm states and transitions") {
      val dir = os.temp.dir()
      os.write(dir / "Switch.scala", "object Switch\n")
      os.write(dir / "State.scala", "object State\n")
      os.write(dir / "Step.scala", "object Step\n")
      val diagram = FsmDiagram(
        states      = Seq(FsmState("sRed", initial = true, source = SourceRef("State.scala", 1, 1)), FsmState("sGreen")),
        transitions = Seq(FsmTransition("sRed", "sGreen", "io.tick", SourceRef("Step.scala", 1, 1))))
      val index = new SourceIndex {
        def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] = None
        override def fsm(scalaClass: String, register: String, at: Option[SourceRef]): Option[FsmSource] =
          if (register == "state") Some(FsmSource(diagram, SourceRef("Switch.scala", 1, 1))) else None
      }
      val d   = SocleExporter.designFromCaptured(fsm, Some(dir), Some(index))
      val mod = d.modules.find(_.name == "TrafficFsm").get
      check(mod.source.file != "Step.scala", s"module source ${mod.source}")
      check(Set("Switch.scala", "State.scala", "Step.scala").subsetOf(d.sources.keySet), s"sources ${d.sources.keySet}")
    }

    test("old json parses") {
      val n = upickle.default.read[LogicNodeDef](
        """{"id":"r","kind":"reg","label":"r","bundle":"UInt<1>","source":{"file":"","line":0,"col":0}}""")
      assert(n.fsm == None)
    }

    test("gcd signals are its registers in order") {
      val m = ModelToDesign.design(gcd).modules.find(_.name == "GCD").get
      assert(m.signals.map(s => (s.name, s.kind, s.width)) == Seq(("x", "reg", 16), ("y", "reg", 16), ("busy", "reg", 1)))
      assert(m.signals.head.source.line == 30)
    }

    test("wires and named nodes are signals, compiler temporaries are not") {
      val chirrtl =
        """circuit Top :
          |  public module Top : @[a.scala 1:1]
          |    input a : UInt<4>
          |    output o : UInt<4>
          |    wire w : UInt<4> @[a.scala 5:3]
          |    wire agg : { p : UInt<2>, flip q : UInt<3>[2] } @[a.scala 6:3]
          |    connect w, a
          |    node n = add(w, a) @[a.scala 7:3]
          |    node _T = add(w, a)
          |    connect o, n
          |""".stripMargin
      val cm = CapturedModel("ok", "Top", Seq(CModule("Top", "mypkg.Top", Nil, None,
        Seq(CPort("a", CType("chisel3.UInt", "UInt", "in", 4)), CPort("o", CType("chisel3.UInt", "UInt", "out", 4))), Nil)),
        chirrtl = chirrtl)
      val sigs = ModelToDesign.design(cm).modules.head.signals
      assert(sigs.map(s => (s.name, s.kind, s.width)) == Seq(
        ("w", "wire", 4), ("agg.p", "wire", 2), ("agg.q[0]", "wire", 3), ("agg.q[1]", "wire", 3), ("n", "wire", 0)))
    }

    test("wires and nodes keep declaration order, layer content and huge aggregates are handled") {
      val chirrtl =
        """circuit Top :
          |  public module Top : @[a.scala 1:1]
          |    input clock : Clock
          |    wire a : UInt<1>
          |    node n = not(a)
          |    wire b : UInt<1>
          |    layerblock Verif :
          |      wire lw : UInt<2>
          |      reg lr : UInt<2>, clock
          |    reg r : UInt<2>, clock
          |    wire big : UInt<1>[300]
          |""".stripMargin
      val cm = CapturedModel("ok", "Top", Seq(CModule("Top", "mypkg.Top", Nil, None,
        Seq(CPort("clock", CType("chisel3.Clock", "Clock", "in", 1))), Nil)), chirrtl = chirrtl)
      val d = ModelToDesign.design(cm)
      val sigs = d.modules.head.signals.map(_.name)
      assert(sigs.take(4) == Seq("r", "a", "n", "b"))
      assert(!sigs.contains("lw") && !sigs.contains("lr"))
      assert(sigs.drop(4) == (0 until 256).map(i => s"big[$i]"))
      // the cut leaves are said, not lost silently: a warning of the module
      val cut = d.warnings.filter(_.kind == "signals-capped")
      assert(cut.map(w => (w.count, w.module)) == Seq((44, "Top")), cut.head.message.contains("256"))
    }

    test("the CHIRRTL view has no signals") {
      val d = SocleExporter.designFromChirrtl(gcd.chirrtl)
      check(d.modules.forall(_.signals.isEmpty), "a CHIRRTL module carries signals")
    }

    test("old json has no signals") {
      val d = upickle.default.read[Design](resource("/golden/gcd.json"))
      assert(d.modules.forall(_.signals.isEmpty))
    }

    test("the CHIRRTL view has no fsm diagrams") {
      val d = SocleExporter.designFromChirrtl(fsm.chirrtl)
      check(!d.modules.flatMap(_.logic).exists(_.fsm.isDefined), "a CHIRRTL logic node carries an fsm diagram")
      check(!d.regions.values.flatMap(_.logic).exists(_.fsm.isDefined), "a CHIRRTL region node carries an fsm diagram")
    }
  }
}
