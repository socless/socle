package socless.capture

import socless.SoclessExporter
import socless.layout.{Layout, LayoutCore}
import socless.schema._
import utest._

/** A captured model (committed fixtures, produced by the probe's run contract
  * on the example ChiselSim tests) adapted to the design.json schema, checked
  * against the CHIRRTL pipeline of the same elaboration as the oracle.
  *
  * Regenerating the fixtures (after a change to the probe or to CapturedModel):
  * from the repo root, build the agent with `./mill socless-probe.assembly`,
  * write the test classpath with
  * `./mill show example.test.runClasspath | python3 -c "import json,sys; print(':'.join(p.split(':',3)[-1] if p.startswith('ref:') else p for p in json.load(sys.stdin)))" > /tmp/example.cp`,
  * then run, once per fixture,
  * `java -javaagent:out/socless-probe/assembly.dest/out.jar -Dsocless.probe.mode=view -Dsocless.probe.out=socless/test/resources/captured/<name>.json -cp "$(cat /tmp/example.cp)" org.scalatest.tools.Runner -oW -s <Suite> -t "<test>"`
  * with (gcd, gcd.GcdSpec, "GCD computes gcd(48, 18)"),
  * (rvpipeline, rvpipeline.RVPipelineSpec, "RVPipeline should run a few cycles after reset") and
  * (deepsoc, deephier.DeepSocSpec, "DeepSoc elaborates and steps"); scalatest
  * reports the test FAILED (ViewOnly), which is expected. Finally make the
  * source paths repo-relative: `sed -i "s|$PWD/||g" socless/test/resources/captured/[a-z]*.json`.
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

  val tests = Tests {
    test("gcd structure") {
      val d      = ModelToDesign.design(gcd)
      val oracle = SoclessExporter.designFromChirrtl(gcd.chirrtl)
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
      val oracle = SoclessExporter.designFromChirrtl(deepsoc.chirrtl)
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
      val d = SoclessExporter.designFromChirrtl(deepsoc.chirrtl)
      check(d.modules.flatMap(_.children).forall(_.foldGroup.isEmpty), "CHIRRTL view has a fold group")
    }

    test("layouts bake") {
      for ((label, cm) <- Seq("gcd" -> gcd, "rvpipeline" -> rvpipeline, "deepsoc" -> deepsoc)) {
        val design = ModelToDesign.design(cm)
        val laid   = Layout.withLayouts(design)
        check(laid.layoutOf.size == design.modules.size,
          s"[$label] layoutOf.size ${laid.layoutOf.size} != modules.size ${design.modules.size} - some modules were skipped")
        design.modules.foreach { m =>
          check(laid.layoutOf.get(m.name).exists(laid.layouts.contains), s"[$label] module ${m.name} has no resolvable layout")
        }
      }
    }

    test("unwired ports show as boundary pins") {
      // Every GCD wire touches internal logic, so none is kept; the ports
      // must still be drawn (captured designs only, the flag is off elsewhere).
      val d = ModelToDesign.design(gcd)
      assert(d.showUnwiredPorts)
      assert(d.modules.head.wires.isEmpty)
      val laid  = Layout.withLayouts(d)
      val nodes = laid.layouts(laid.layoutOf("GCD")).nodes
      val pins  = nodes.filter(_.kind == NodeKind.Boundary).flatMap(_.ports).map(p => p.path -> p.side).toMap
      assert(pins.get("io.in") == Some(PinSide.East))
      assert(pins.get("io.out") == Some(PinSide.West))
      assert(!pins.contains("clock"))
      assert(!pins.contains("reset"))
      // The CHIRRTL path never sets the flag: no boundary stub without a wire.
      assert(!SoclessExporter.designFromChirrtl(gcd.chirrtl).showUnwiredPorts)
    }

    test("instance boxes show their full interface") {
      // Pins of each node, as path -> side, from the shared layout core.
      val d = ModelToDesign.design(deepsoc)
      def pinsOf(module: String): Map[String, Map[String, String]] =
        LayoutCore.prepare(d.modules.find(_.name == module).get, d).nodes
          .map(n => n.id -> n.pins.map(p => p.path -> p.side).toMap).toMap
      val core = pinsOf("Core")
      // alu wires only io.b and io.out; every other port still shows.
      check(Seq("a", "b", "op", "out", "cout").forall(n => core("alu").contains(s"alu.io.$n")), s"alu pins: ${core("alu").keys}")
      assert(core("alu")("alu.io.a") == PinSide.West)
      assert(core("alu")("alu.io.cout") == PinSide.East)
      check(Seq("raddr", "waddr", "wen", "wdata", "rdata").forall(n => core("rf").contains(s"rf.io.$n")), s"rf pins: ${core("rf").keys}")
      // Cluster: cores_* wire io.in.bits only, so the uncovered Decoupled
      // leaves (valid, ready) and the unwired io.out channel show.
      val cluster = pinsOf("Cluster")
      for (c <- Seq("cores_0", "cores_1")) {
        val pins = cluster(c)
        check(Seq("io.in.valid", "io.in.ready", "io.out").forall(p => pins.contains(s"$c.$p")), s"$c pins: ${pins.keys}")
        assert(pins(s"$c.io.in.valid") == PinSide.West)
        assert(pins(s"$c.io.in.ready") == PinSide.East)
        assert(!pins.contains(s"$c.io.in"))
      }
      // The same refinement on the boundary: Cluster's io.in is partly wired.
      assert(cluster.get("io.in.valid").flatMap(_.get("io.in.valid")) == Some(PinSide.East))
      assert(cluster.get("io.in.ready").flatMap(_.get("io.in.ready")) == Some(PinSide.West))
      // With the flag off, instance boxes show only their wired pins.
      val off  = d.copy(showUnwiredPorts = false)
      val alu  = LayoutCore.prepare(off.modules.find(_.name == "Core").get, off).nodes.find(_.id == "alu").get
      assert(alu.pins.map(_.path).toSet == Set("alu.io.b", "alu.io.out"))
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
        val oracle = SoclessExporter.designFromChirrtl(cm.chirrtl)
        // Port types: every leaf has the same path, direction and width.
        val (mine, theirs) = (leaves(d), leaves(oracle))
        check(mine == theirs, s"[$label] leaves differ: only adapted ${mine -- theirs}, only oracle ${theirs -- mine}")
        // Wiring: a subset of the oracle's, between ports and instance ports only.
        for (m <- d.modules) {
          val om = oracle.modules.find(_.name == m.name).get
          check(m.wires.map(w => (w.from, w.to)).toSet.subsetOf(om.wires.map(w => (w.from, w.to)).toSet),
            s"[$label] ${m.name} has wires the CHIRRTL lacks")
          for (w <- m.wires; e <- Seq(w.from, w.to)) {
            val kind = LayoutCore.classify(e, m, d).map(_.kind)
            check(kind.contains(NodeKind.Inst) || kind.contains(NodeKind.Boundary), s"[$label] ${m.name}: endpoint $e is $kind")
          }
          for (k <- m.wires.map(_.bundle) ++ m.ports.map(_.bundle))
            check(known(k, d), s"[$label] ${m.name}: type key $k is not in bundles")
        }
      }
      // Not vacuous: DeepSoc wires its clusters port to port.
      assert(ModelToDesign.design(deepsoc).modules.find(_.name == "DeepSoc").get.wires.nonEmpty)
    }
  }
}
