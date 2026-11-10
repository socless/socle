package socle.lsp

import socle.schema.Design
import socle.wave.{WaveGroup, WaveLayout, WaveSettings}
import utest._

/** Every port of every instance of every bundled example has its trace (#7):
  * each example's ChiselSim test is viewed (the design the panel shows) and
  * simulated with a VCD (Simulate), and the wave plan of each instance, ports
  * only, finds all of its signals in the trace's header. Registers and wires
  * are left out: Verilator may still rename or remove them.
  *
  * The simulation keeps names (`-DfirtoolOpts=-preserve-values=named`): by
  * default firtool removes the ports a parent never reads and the instances
  * nothing reads, and those are absent from any trace.
  *
  * Needs Verilator; skipped when it is not on PATH (as in CI). */
object TraceCoverageTest extends TestSuite {
  private val PreserveNames = "-DfirtoolOpts=-preserve-values=named"

  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private def cp(k: String)   = prop(k).split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(os.Path(_))
  private val javaBin = (os.Path(sys.props("java.home")) / "bin" / "java").toString

  private def onPath(exe: String): Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).exists(d => d.nonEmpty && os.isFile(os.Path(d, os.pwd) / exe))

  /** The ChiselSim test of each bundled example. */
  private val examples = Seq(
    "gcd.GcdSpec"               -> "GCD computes gcd(48, 18)",
    "rvpipeline.RVPipelineSpec" -> "RVPipeline should run a few cycles after reset",
    "deephier.DeepSocSpec"      -> "DeepSoc elaborates and steps",
    "deephier.DeepSocSpec"      -> "Cluster with 3 cores steps",
    "fsm.TrafficFsmSpec"        -> "TrafficFsm cycles red, green, yellow",
  ) ++ Seq("AXISystem", "Interconnect", "Datapath", "LayeredCounter", "ScatterDemo", "SpiSlave", "BigArray")
    .map(top => "examples.StepSpec" -> s"$top steps")

  /** Every instance path of the design, top first (`List(top)`, `List(top, a)`, ...). */
  private def instancePaths(d: Design): Seq[List[String]] = {
    val byName = d.modules.map(m => m.name -> m).toMap
    def walk(path: List[String], module: String): Seq[List[String]] =
      path +: byName.get(module).toSeq.flatMap(_.children).flatMap(c => walk(path :+ c.name, c.`type`))
    walk(List(d.top), d.top)
  }

  /** (instance paths, port signals predicted, the predicted trace paths the trace lacks). */
  private def coverage(design: Design, vcd: os.Path): (Int, Int, Seq[String]) = {
    val names    = TraceNames.read(vcd, WaveLayout.tracedPorts(design)) // as the server reads it
    val portOnly = design.copy(modules = design.modules.map(_.copy(signals = Nil)))
    // clock and reset left out, as the panel does by default: firtool removes
    // them from a module that does not use them, names kept or not
    val settings = WaveSettings(showClockReset = false, showWires = false)
    def traces(g: WaveGroup): Seq[String] = g.items.map(_.trace) ++ g.children.flatMap(traces)
    val paths = instancePaths(design)
    // with no names the plan keeps every signal: the full prediction
    val predicted = paths.flatMap(p => WaveLayout.plan(portOnly, p, names.prefix, Set.empty, settings).groups.flatMap(traces))
    (paths.size, predicted.size, predicted.filterNot(names.names))
  }

  val tests = Tests {
    test("every port of every bundled example resolves to a trace signal") {
      if (!onPath("verilator")) {
        println("[TraceCoverageTest] SKIPPED: verilator is not on PATH")
      } else {
        val root   = os.temp.dir(prefix = "socle-trace-coverage-")
        val viewer = new ViewRunner(ViewRunner.extractProbeJar(), os.Path(prop("socle.root")), javaBin)
        val sim    = new SimRunner(root, javaBin, log = _ => ())
        val fork   = TestFork(cp("example.cp"))
        val waves  = SimSettings("emitVcd", "surfer", waveViewer = "surfer-panel")
        try {
          val problems = examples.flatMap { case (suite, test) =>
            val design = viewer.run(fork, suite, test) match {
              case Viewed(d) => d
              case other     => sys.error(s"$suite $test: expected Viewed, got $other")
            }
            val out = sim.run(SimRequest(os.root / "nowhere", suite, test), waves, Right(fork), Seq(PreserveNames))
            assert(out.passed)
            val vcd = out.waves.getOrElse(sys.error(s"$suite $test: no waves: ${out.message}"))
            val (instances, found, missing) = coverage(design, vcd)
            println(s"[TraceCoverageTest] ${design.top} ($test): $instances instances, $found port signals, ${missing.size} missing")
            missing.take(12).foreach(m => println(s"[TraceCoverageTest]   missing $m"))
            assert(found > 0)
            missing.map(m => s"${design.top} ($test) $m")
          }
          assert(problems.isEmpty)
        } finally { sim.close(); os.remove.all(root) }
      }
    }
  }
}
