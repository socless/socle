package socle.lsp

import java.util.concurrent.{Callable, Executors}
import utest._

object SimBaseIndexTest extends TestSuite {
  private def tree(files: (String, String)*): os.Path = {
    val dir = os.temp.dir(prefix = "socle-simbase-")
    files.foreach { case (rel, text) => os.write(dir / os.RelPath(rel), text, createFolders = true) }
    dir
  }

  /** Every lens the server would show under `root`, as (suite, name). */
  private def lenses(root: os.Path, bases: Set[String]): Seq[(String, String)] =
    os.walk(root).filter(_.ext == "scala").sortBy(_.toString)
      .flatMap(f => TestDiscovery.find(os.read(f), bases)).map(t => (t.suite, t.name))

  // A Mill project with a ChiselSim base trait in its main sources (reached
  // through a CLI-option trait), suites in its test sources: several suites
  // and a same-named file per directory, a helper trait, a generic class with
  // a non-literal subject, a fully qualified base and a plain AnyFlatSpec.
  private val projectLike = Seq(
    "build.mill" -> "",
    "fabric/src/fabric/testkit/SimSpec.scala" ->
      """package fabric.testkit
        |
        |import chisel3._
        |import chisel3.simulator.scalatest.HasCliOptions.CliOption
        |import chisel3.simulator.scalatest.{ChiselSim, HasCliOptions}
        |import org.scalatest.flatspec.AnyFlatSpec
        |
        |trait VerboseFlag {
        |  this: HasCliOptions =>
        |  addOption(CliOption.flag("verbose", "prints every cycle"))
        |}
        |
        |trait ClockedSpec
        |    extends AnyFlatSpec
        |    with ChiselSim
        |    with VerboseFlag {
        |  override def simulate[T <: Module](module: => T)(stimulus: T => Unit): Unit =
        |    super.simulate(module)(stimulus)
        |}
        |class SmokeSim[D <: Module](label: String, build: () => D) extends ClockedSpec {
        |  behavior of label
        |  it should "elaborate and run" in {
        |    simulate(build())(_ => ())
        |  }
        |}
        |""".stripMargin,
    "fabric/test/src/router/RouterSpec.scala" ->
      """package router
        |
        |import fabric.net._
        |import fabric.tags
        |import fabric.testkit.ClockedSpec
        |
        |@tags.Fast
        |class RouterSpec extends ClockedSpec {
        |  behavior of "Router"
        |  it should "forward a packet" in
        |    simulate(new Router)(new RouterDriver(_))
        |
        |  it should "drop a bad header" in simulate(new Router)(
        |    new BadHeaderDriver(_)
        |  )
        |
        |  behavior of "RouterArbiter"
        |
        |  it should "start idle" in simulate(new RouterArbiter)(
        |    new IdleChecker(_)
        |  )
        |}
        |""".stripMargin,
    "fabric/test/src/replay/trace/RouterSpec.scala" ->
      """package replay.trace
        |
        |import fabric.testkit.ClockedSpec
        |
        |class Replayer(dut: Router, path: String, verbose: Boolean) {
        |  val x = 1
        |}
        |
        |class TraceReplaySpec extends ClockedSpec {
        |  behavior of "Router"
        |
        |  val verbose = false
        |  def replay(path: String) = {
        |    simulate(new Router) { d =>
        |      new Replayer(d, path, verbose)
        |    }
        |  }
        |  it should "replay a short trace" in replay(
        |    "/traces/short.txt"
        |  )
        |
        |  it should "replay a long trace" in
        |    replay("/traces/long.txt")
        |}
        |
        |class Boot_check extends ClockedSpec {
        |  "Boot sequence" should "finish without errors" in
        |    simulate(new Router)(_ => ())
        |}
        |""".stripMargin,
    "fabric/test/src/dsp/FilterSpec.scala" ->
      """package dsp
        |
        |import fabric.testkit.ClockedSpec
        |
        |trait FilterHarness extends ClockedSpec {
        |  def runFilter(cfg: String): Unit = simulate(new Filter)(_ => ())
        |}
        |
        |class FilterSpec extends FilterHarness {
        |  behavior of "Filter"
        |
        |  it should "smooth a step" in runFilter(
        |    "step.cfg"
        |  )
        |}
        |""".stripMargin,
    "fabric/test/src/router/Smoke.scala" ->
      """package router
        |
        |import fabric.tags.{Fast, Slow}
        |
        |class SmokeTest[T <: Module, P <: Config](
        |  label: String,
        |  slow: Boolean = false
        |) extends fabric.testkit.ClockedSpec {
        |
        |  behavior of label
        |  if (slow) {
        |    it should "pass" taggedAs (Slow) in { }
        |  } else {
        |    it should "pass" taggedAs (Fast) in { }
        |  }
        |}
        |class FilterSmoke extends SmokeTest("FilterSmoke")
        |""".stripMargin,
    "fabric/test/src/bus/BusSpec.scala" ->
      """package fabric.bus
        |
        |import org.scalatest.matchers.should.Matchers
        |import fabric.testkit.ClockedSpec
        |
        |class BusSpec extends ClockedSpec with Matchers {
        |  behavior of "Bus"
        |  it should "complete one write" taggedAs (Fast) in { }
        |  ignore should "split a burst at a page boundary" in { }
        |}
        |""".stripMargin,
    "fabric/test/src/testkit/ConfigLoaderTest.scala" ->
      """package fabric.testkit
        |class ConfigLoaderTest extends AnyFlatSpec with Matchers {
        |  "ConfigLoader" should "parse the defaults" in { }
        |}
        |""".stripMargin,
  )

  val tests = Tests {
    test("base trait in another file") {
      val root = tree(
        "src/SimBase.scala" -> "package p\ntrait SimBase extends AnyFlatSpec with ChiselSim",
        "test/S.scala"      -> "package q\nimport p.SimBase\nclass S extends SimBase { \"A\" should \"x\" in { } }")
      try {
        val bases = new SimBaseIndex(root).bases()
        assert(bases.contains("SimBase"), bases.contains("p.SimBase"))
        val s = os.read(root / "test" / "S.scala")
        assert(TestDiscovery.find(s).isEmpty)
        assert(TestDiscovery.find(s, bases).map(t => (t.suite, t.name)) == Seq(("q.S", "A should x")))
      } finally os.remove.all(root)
    }
    test("two-level chain, qualified and nested") {
      val root = tree(
        "a/B2.scala" -> "package a.b\ntrait B2 extends AnyFlatSpec with chisel3.simulator.scalatest.ChiselSim",
        "c/B1.scala" -> "package c\nobject Bases { trait B1 extends a.b.B2 }\nabstract class Mid extends Bases.B1",
        "d/S.scala"  -> "package d\nclass S extends c.Mid { \"A\" should \"x\" in { } }\nclass N extends Unrelated",
        "e/X.scala"  -> "package e\ntrait Unrelated extends AnyFlatSpec")
      try {
        val bases = new SimBaseIndex(root).bases()
        assert(Set("B2", "a.b.B2", "B1", "c.Bases.B1", "Mid", "c.Mid", "S", "d.S").subsetOf(bases))
        assert(!bases.contains("N"), !bases.contains("Unrelated"))
        assert(TestDiscovery.find(os.read(root / "d" / "S.scala"), bases).map(_.name) == Seq("A should x"))
      } finally os.remove.all(root)
    }
    test("multi-module project tree") {
      val root = tree(projectLike: _*)
      try {
        val bases = new SimBaseIndex(root).bases()
        assert(Set("ClockedSpec", "fabric.testkit.ClockedSpec", "FilterHarness").subsetOf(bases))
        val got = lenses(root, bases)
        assert(got == Seq(
          ("fabric.bus.BusSpec", "Bus should complete one write"),
          ("dsp.FilterSpec", "Filter should smooth a step"),
          ("replay.trace.TraceReplaySpec", "Router should replay a short trace"),
          ("replay.trace.TraceReplaySpec", "Router should replay a long trace"),
          ("replay.trace.Boot_check", "Boot sequence should finish without errors"),
          ("router.RouterSpec", "Router should forward a packet"),
          ("router.RouterSpec", "Router should drop a bad header"),
          ("router.RouterSpec", "RouterArbiter should start idle"),
        ))
        // without the index only the simulate( fallback finds suites: not
        // those that call it through an inherited helper or not at all
        assert(lenses(root, Set.empty) ==
          got.filterNot(t => Set("dsp.FilterSpec", "fabric.bus.BusSpec")(t._1)))
      } finally os.remove.all(root)
    }
    test("skips out, node_modules, target, dot-dirs and the root's test output; rescans changes") {
      // the root itself may sit under a dot-dir (as git worktrees under .claude/ do)
      val outer = os.temp.dir(prefix = "socle-simbase-")
      val root  = outer / ".hidden" / "ws"
      def put(rel: String, text: String) = os.write.over(root / os.RelPath(rel), text, createFolders = true)
      try {
        put("src/A.scala", "trait A extends ChiselSim")
        put("src/Keep.scala", "trait Keep extends A")
        put("out/B.scala", "trait B extends ChiselSim")
        put(".metals/C.scala", "trait C extends ChiselSim")
        put("node_modules/x/D.scala", "trait D extends ChiselSim")
        put("mod/target/E.scala", "trait E extends ChiselSim")
        // test output in the root (ChiselSim's build/, chiseltest's test_run_dir/); a deeper build/ is source
        put("build/chiselsim/G.scala", "trait G extends ChiselSim")
        put("test_run_dir/H.scala", "trait H extends ChiselSim")
        put("src/build/Deep.scala", "trait Deep extends ChiselSim")
        val idx = new SimBaseIndex(root)
        assert(idx.bases() == Set("ChiselSim", "chisel3.simulator.scalatest.ChiselSim", "A", "Keep", "Deep"))
        assert(idx.parseCount == 3)

        // cached: nothing changed, nothing reparsed
        idx.invalidate()
        assert(idx.bases().contains("Keep"), idx.parseCount == 3)

        // a changed file is reparsed (mtime moved), a new one parsed, a deleted one dropped
        val a = root / "src" / "A.scala"
        put("src/A.scala", "trait A extends Other")
        os.mtime.set(a, os.mtime(a) + 5000)
        put("src/F.scala", "trait F extends ChiselSim")
        os.remove(root / "src" / "Keep.scala")
        assert(idx.bases().contains("A")) // not invalidated yet: the cached set
        idx.invalidate()
        assert(idx.bases() == Set("ChiselSim", "chisel3.simulator.scalatest.ChiselSim", "F", "Deep"))
        assert(idx.parseCount == 5)
      } finally os.remove.all(outer)
    }
    test("safe from several threads") {
      val root = tree(projectLike: _*)
      val pool = Executors.newFixedThreadPool(4)
      try {
        val idx = new SimBaseIndex(root)
        val fs  = (1 to 8).map(_ => pool.submit(new Callable[Set[String]] { def call() = { idx.invalidate(); idx.bases() } }))
        val all = fs.map(_.get())
        assert(all.distinct.size == 1, all.head.contains("ClockedSpec"))
      } finally { pool.shutdown(); os.remove.all(root) }
    }
    test("bases declaring a subject") {
      val root = tree(
        "B.scala" -> "package p\ntrait B extends AnyFlatSpec with ChiselSim { behavior of \"X\" }",
        "C.scala" -> "package p\ntrait C extends B",
        "D.scala" -> "package p\ntrait D extends AnyFlatSpec with ChiselSim { def x = 1 }",
        "L.scala" -> "package p\nabstract class L extends ChiselSim { \"Lit\" should \"q\" in { } }",
        "S.scala" -> "package q\nclass S extends p.C {\n  it should \"y\" in { }\n  behavior of \"Z\"\n  it should \"w\" in { }\n}")
      try {
        val idx = new SimBaseIndex(root)
        val subj = idx.subjectBases()
        assert(Set("B", "p.B", "C", "p.C", "L", "p.L", "S", "q.S").subsetOf(subj), !subj.contains("D"))
        assert(TestDiscovery.find(os.read(root / "S.scala"), idx.bases(), subj).map(_.name) == Seq("Z should w"))
      } finally os.remove.all(root)
    }
    test("invalidate never blocks on a running rebuild") {
      val root    = tree("A.scala" -> "trait A extends ChiselSim")
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val rebuilds = new java.util.concurrent.atomic.AtomicInteger
      val idx = new SimBaseIndex(root, onRebuild = () => {
        rebuilds.incrementAndGet(); entered.countDown(); release.await()
      })
      val pool = Executors.newFixedThreadPool(2)
      try {
        val first = pool.submit(new Callable[Set[String]] { def call() = idx.bases() })
        assert(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) // the rebuild holds the lock
        val inv = pool.submit(new Callable[Unit] { def call() = idx.invalidate() })
        inv.get(2, java.util.concurrent.TimeUnit.SECONDS) // would time out if it waited for the lock
        release.countDown()
        assert(first.get().contains("A"), rebuilds.get == 1)
        idx.bases() // invalidated during the rebuild: rebuilds again
        assert(rebuilds.get == 2)
        idx.bases()
        assert(rebuilds.get == 2)
      } finally { release.countDown(); pool.shutdown(); os.remove.all(root) }
    }
    test("unreadable or unparsable files are ignored") {
      val root = tree("A.scala" -> "trait A extends ChiselSim", "Bad.scala" -> "class {{{")
      try assert(new SimBaseIndex(root).bases().contains("A"))
      finally os.remove.all(root)
    }
  }
}
