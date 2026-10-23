package socless.probe

import socless.capture.{CapturedModel, WiresFromChirrtl}
import utest._

/** Runs unmodified ChiselSim scalatest tests in a forked JVM with the probe
  * agent attached in view mode, as the tool will. Each run gets a fresh
  * working directory, so `build/chiselsim` checks are isolated. */
object AgentRunTest extends TestSuite {

  case class Run(model: CapturedModel, output: String, cwd: os.Path)

  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))

  private def viewRun(cpProp: String, suite: String, test: Option[String]): Run = {
    val cwd  = os.temp.dir(prefix = "probe-run-")
    val out  = cwd / "captured.json"
    val java = os.Path(sys.props("java.home")) / "bin" / "java"
    val cmd = Seq(
      java.toString,
      s"-javaagent:${prop("probe.jar")}",
      "-Dsocless.probe.mode=view",
      s"-Dsocless.probe.out=$out",
      "-cp", prop(cpProp),
      "org.scalatest.tools.Runner", "-oW", "-s", suite,
    ) ++ test.toSeq.flatMap(t => Seq("-t", t))
    val res = os.proc(cmd).call(cwd = cwd, timeout = 120000, check = false, mergeErrIntoOut = true)
    val output = res.out.text()
    Predef.assert(os.exists(out), s"no $out after the run; JVM output:\n$output")
    Run(CapturedModel.fromJson(os.read(out)), output, cwd)
  }

  // The Task 4 contract: no problems, and the captured CHIRRTL is the one of
  // this elaboration (the consumer derives the wiring from it, per module).
  private def assertConsumable(m: CapturedModel): Unit = {
    assert(m.problems.isEmpty)
    assert(m.chirrtl.nonEmpty)
    assert(WiresFromChirrtl.wires(m.chirrtl).keySet == m.modules.map(_.name).toSet)
  }

  val tests = Tests {
    test("gcd view") {
      val r = viewRun("example.cp", "gcd.GcdSpec", Some("GCD computes gcd(48, 18)"))
      assert(r.model.status == "ok")
      assert(r.model.top == "GCD")
      assert(r.model.chiselVersion == "7.16.0")
      assertConsumable(r.model)
      assert(r.output.contains("socless.probe.ViewOnly"))
      // Neither firtool nor Verilator ran. Without the agent this run creates
      // build/chiselsim/GcdSpec/GCD/computes-gcd-48--18-/workdir-verilator.
      val chiselsim = r.cwd / "build" / "chiselsim" / "GcdSpec"
      val produced  = if (os.exists(chiselsim)) os.walk(chiselsim) else Nil
      assert(!produced.exists(_.last == "workdir-verilator"))
      assert(!produced.exists(_.ext == "sv"))
    }
    test("deepsoc parameterized") {
      val r = viewRun("example.cp", "deephier.DeepSocSpec", Some("Cluster with 3 cores steps"))
      assert(r.model.status == "ok")
      assert(r.model.top == "Cluster")
      assertConsumable(r.model)
      val cluster = r.model.modules.find(_.name == "Cluster").get
      val coreModules = r.model.modules.filter(_.scalaClass == "deephier.Core").map(_.name).toSet
      assert(cluster.instances.count(i => coreModules(i.module)) == 3)
    }
    test("no simulate") {
      val r = viewRun("fixtures.cp", "probefix.NoSimulateSpec", None)
      assert(r.model.status == "no-design")
      assert(r.model.modules.isEmpty)
      assert(r.output.contains("Tests: succeeded 1, failed 0"))
    }
    test("elaboration error") {
      val r = viewRun("fixtures.cp", "probefix.BrokenSpec", None)
      assert(r.model.status == "elaboration-error")
      assert(r.model.error.contains("broken on purpose"))
    }
  }
}
