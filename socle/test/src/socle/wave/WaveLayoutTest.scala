package socle.wave

import socle.capture.{CapturedModel, ModelToDesign}
import socle.schema._
import utest._

object WaveLayoutTest extends TestSuite {
  private val Dut = "TOP.svsimTestbench.dut"

  private lazy val gcd: Design = {
    val s = getClass.getResourceAsStream("/captured/gcd.json")
    ModelToDesign.design(CapturedModel.fromJson(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
  }

  private def plan(s: WaveSettings = WaveSettings(), names: Set[String] = Set.empty) =
    WaveLayout.plan(gcd, List("GCD"), Dut, names, s)

  private def group(p: WavePlan, name: String) = p.groups.find(_.name == name).get
  private def child(g: WaveGroup, name: String) = g.children.find(_.name == name).get

  private def allTraces(g: WaveGroup): Seq[String] = g.items.map(_.trace) ++ g.children.flatMap(allTraces)

  val tests = Tests {
    test("tracePath") {
      assert(WaveLayout.tracePath(Dut, List("GCD"), "io.out.bits.result") == Dut + ".io_out_bits_result")
      assert(WaveLayout.tracePath(Dut, List("Top", "cpu"), "io.x") == Dut + ".cpu.io_x")
      assert(WaveLayout.tracePath(Dut, List("Top"), "io.v[3].a[0]") == Dut + ".io_v_3_a_0")
      assert(WaveLayout.tracePath(Dut, List("Top"), "r") == Dut + ".r")
    }

    test("an empty bundle port has no trace signal, and does not anchor the trace") {
      // AXISystem's `IO(new Bundle {})`: Verilog has nothing for it
      val empty = PortDef("e", PortDir.In, "Empty", "", SourceRef.unknown)
      val d = gcd.copy(
        bundles = gcd.bundles + ("Empty" -> BundleDef("Empty", BundleKind.Bundle)),
        modules = gcd.modules.map(m => if (m.name == gcd.top) m.copy(ports = m.ports :+ empty) else m))
      val p = WaveLayout.plan(d, List("GCD"), Dut, Set.empty, WaveSettings())
      assert(!p.groups.flatMap(allTraces).exists(_.endsWith(".e")))
      assert(WaveLayout.tracedPorts(d) == WaveLayout.tracedPorts(gcd))
      assert(WaveLayout.tracedPorts(gcd).contains("io"))
    }

    test("gcd default plan") {
      val p = plan()
      assert(p.missing == 0)
      assert(p.groups.map(_.name) == Seq("io", "Registers"))
      val io = group(p, "io")
      assert(io.items.isEmpty)
      assert(io.children.map(_.name) == Seq("in", "out"))
      val in = child(io, "in")
      assert(in.items.map(_.label) == Seq("valid", "ready"))
      assert(child(in, "bits").items.map(_.label) == Seq("a", "b"))
      assert(child(in, "bits").items.head.trace == Dut + ".io_in_bits_a")
      val out = child(io, "out")
      assert(out.items.map(_.label) == Seq("valid", "ready"))
      assert(child(out, "bits").items.map(_.label) == Seq("result"))
      // Directions seen from GCD: in.valid and in.bits are inputs, in.ready is an output.
      assert(in.items.map(i => i.label -> i.color) == Seq("valid" -> "Blue", "ready" -> "Green"))
      assert(child(in, "bits").items.forall(_.color == "Blue"))
      assert(out.items.map(i => i.label -> i.color) == Seq("valid" -> "Green", "ready" -> "Blue"))
      assert(child(out, "bits").items.forall(_.color == "Green"))
      val regs = group(p, "Registers")
      assert(regs.items.map(_.label) == Seq("x", "y", "busy"))
      assert(regs.items.forall(_.color == "Orange"))
      assert(regs.items.head.trace == Dut + ".x")
    }

    test("clock and reset lead when shown") {
      val p = plan(WaveSettings(showClockReset = true))
      assert(p.groups.map(_.name) == Seq("clock", "reset", "io", "Registers"))
      assert(p.groups.head.items.map(_.trace) == Seq(Dut + ".clock"))
    }

    test("showWires false has no Wires group") {
      assert(!plan(WaveSettings(showWires = false)).groups.exists(_.name == "Wires"))
    }

    test("wires group lists wires in order, in Gray") {
      val m = gcd.modules.head
      val sig = (n: String) => SignalDef(n, "wire", 0, SourceRef.unknown)
      val d = gcd.copy(modules = Seq(m.copy(signals = m.signals ++ Seq(sig("w1"), sig("w2")))))
      val w = group(WaveLayout.plan(d, List("GCD"), Dut, Set.empty, WaveSettings()), "Wires")
      assert(w.items.map(_.label) == Seq("w1", "w2"))
      assert(w.items.forall(_.color == "Gray"))
    }

    test("colorByDirection false paints every port field Blue") {
      val p = plan(WaveSettings(colorByDirection = false))
      val portColors = p.groups.filter(_.name == "io").flatMap(g => allItems(g)).map(_.color).distinct
      assert(portColors == Seq("Blue"))
      assert(group(p, "Registers").items.forall(_.color == "Orange"))
    }

    test("the legend names the plan's colours, from the same mapping") {
      assert(WaveLayout.legend(byDirection = true) ==
        Seq("Inputs" -> "Blue", "Outputs" -> "Green", "Registers" -> "Orange", "Wires" -> "Gray"))
      assert(WaveLayout.legend(byDirection = false) == Seq("Ports" -> "Blue", "Registers" -> "Orange", "Wires" -> "Gray"))
      // Each colour the plan paints is the legend's colour for that kind.
      val p = plan()
      val colors = p.groups.flatMap(allItems).map(_.color).toSet
      assert(colors == Set(WaveLayout.InputColor, WaveLayout.OutputColor, WaveLayout.RegisterColor))
      assert(group(p, WaveLayout.RegistersGroup).items.forall(_.color == WaveLayout.RegisterColor))
      // The plan says how it was coloured, for a viewer that has no settings (the VS Code Surfer tab).
      assert(p.colorByDirection && !plan(WaveSettings(colorByDirection = false)).colorByDirection)
    }

    test("a plan without colorByDirection reads as coloured by direction") {
      // A plan written before the field (an older server) still reads, as true.
      val old = upickle.default.read[WavePlan]("""{"groups":[],"missing":0}""")
      assert(old.colorByDirection)
      val off = plan(WaveSettings(colorByDirection = false))
      val back = upickle.default.read[WavePlan](upickle.default.write(off))
      assert(back == off, !back.colorByDirection)
    }

    test("foldBundles folds only child groups") {
      val p = plan(WaveSettings(foldBundles = true))
      val io = group(p, "io")
      assert(!io.folded)
      assert(io.children.forall(_.folded))
      assert(child(child(io, "in"), "bits").folded)
      assert(!group(p, "Registers").folded)
      assert(plan().groups.flatMap(g => g +: g.children).forall(!_.folded))
    }

    test("traceNames drops what the trace lacks") {
      val all = plan().groups.flatMap(allTraces).toSet
      val p = plan(names = all - (Dut + ".y"))
      assert(p.missing == 1)
      assert(group(p, "Registers").items.map(_.label) == Seq("x", "busy"))
      assert(plan(names = Set.empty).missing == 0)
    }

    test("a group with nothing left is dropped") {
      val p = plan(names = Set(Dut + ".x"))
      assert(p.groups.map(_.name) == Seq("Registers"))
      assert(p.missing > 0)
    }

    test("an unknown instance path plans nothing") {
      assert(WaveLayout.plan(gcd, List("GCD", "nope"), Dut, Set.empty, WaveSettings()) == WavePlan(Nil, 0))
    }

    test("items carry their width (0 when unknown)") {
      val items = plan().groups.flatMap(allItems).map(i => i.trace.stripPrefix(Dut + ".") -> i.width).toMap
      assert(items("io_in_valid") == 1, items("io_in_bits_a") == 16, items("io_out_bits_result") == 16)
      assert(items("x") == 16, items("busy") == 1)
      val m = gcd.modules.head
      val d = gcd.copy(modules = Seq(m.copy(signals = m.signals :+ SignalDef("n", "wire", 0, SourceRef.unknown))))
      assert(group(WaveLayout.plan(d, List("GCD"), Dut, Set.empty, WaveSettings()), "Wires").items.map(_.width) == Seq(0))
    }

    test("a Vec of Decoupled port: one group per element, valid and ready by the channel's direction") {
      val text = new String(getClass.getResourceAsStream("/chirrtl/interconnect.fir").readAllBytes(), "UTF-8")
      val d = socle.ir.IrWalk.design(socle.parse.ChirrtlParser.parse(text))
      val p = WaveLayout.plan(d, List("Interconnect"), Dut, Set.empty, WaveSettings())
      val io = group(p, "io")
      assert(io.children.map(_.name) == Seq("in", "out", "ack", "busy"))
      val in0 = child(child(io, "in"), "[0]")
      assert(child(io, "in").children.map(_.name) == Seq("[0]", "[1]", "[2]"))
      // Interconnect consumes `in` (valid in, ready out) and produces `out`
      assert(in0.items.map(i => i.label -> i.color) == Seq("valid" -> "Blue", "ready" -> "Green"))
      assert(in0.items.map(_.trace) == Seq(Dut + ".io_in_0_valid", Dut + ".io_in_0_ready"))
      assert(child(child(in0, "bits"), "ctrl").items.map(_.trace).head == Dut + ".io_in_0_bits_ctrl_addr")
      val out2 = child(child(io, "out"), "[2]")
      assert(out2.items.map(i => i.label -> i.color) == Seq("valid" -> "Green", "ready" -> "Blue"))
      assert(child(io, "ack").items.map(_.trace) == Seq(0, 1, 2).map(i => s"$Dut.io_ack_$i"))
      assert(WaveLayout.focus(d, List("Interconnect"), Dut, Set.empty, "io.out[1]") == Some(Dut + ".io_out_1_valid"))
    }

    test("a nested instance path resolves to its trace scopes") {
      val s = getClass.getResourceAsStream("/captured/deepsoc.json")
      val deep = ModelToDesign.design(CapturedModel.fromJson(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
      val path = List("DeepSoc", "cluster1", "cores_0", "alu")
      val p = WaveLayout.plan(deep, path, Dut, Set.empty, WaveSettings())
      val traces = p.groups.flatMap(allTraces)
      assert(traces.nonEmpty, traces.forall(_.startsWith(Dut + ".cluster1.cores_0.alu.")))
      assert(traces.exists(_.startsWith(Dut + ".cluster1.cores_0.alu.io_")))
      // the trace's names decide what is kept, at that scope
      val kept = WaveLayout.plan(deep, path, Dut, Set(traces.head), WaveSettings())
      assert(kept.groups.flatMap(allTraces) == Seq(traces.head), kept.missing == traces.size - 1)
      assert(WaveLayout.focus(deep, path, Dut, Set.empty, "io") == Some(traces.filter(_.contains(".alu.io_")).head))
    }

    test("matchAdded pairs the ids Surfer returned with their paths by name, in order") {
      val ts = Seq("T.dut.io_a", "T.dut.io_b", "T.dut.sub.x", "T.dut.x")
      // io_b was dropped; Surfer's names: the leaf with its index suffix, or a unique tail
      assert(WaveLayout.matchAdded(ts, Seq("io_a", "sub.x [15:0]", "x")) == Seq(Some("T.dut.io_a"), Some("T.dut.sub.x"), Some("T.dut.x")))
      assert(WaveLayout.matchAdded(ts, Seq("io_b [7:0]", "io_a")) == Seq(Some("T.dut.io_b"), None)) // order kept
      assert(WaveLayout.matchAdded(ts, Seq("zz", "x")) == Seq(None, Some("T.dut.sub.x")))
      assert(WaveLayout.matchAdded(Nil, Seq("x")) == Seq(None))
    }

    test("focus") {
      def f(sel: String, names: Set[String] = Set.empty) = WaveLayout.focus(gcd, List("GCD"), Dut, names, sel)
      assert(f("io.out") == Some(Dut + ".io_out_valid"))
      assert(f("io.in.bits") == Some(Dut + ".io_in_bits_a"))
      assert(f("io.out.bits.result") == Some(Dut + ".io_out_bits_result"))
      assert(f("x") == Some(Dut + ".x"))
      assert(f("nope").isEmpty)
      assert(f("io.o").isEmpty)
      assert(f("x", Set(Dut + ".y")).isEmpty)
    }
  }

  private def allItems(g: WaveGroup): Seq[WaveItem] = g.items ++ g.children.flatMap(allItems)
}
