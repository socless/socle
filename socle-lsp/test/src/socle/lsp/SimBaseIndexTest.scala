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

  // Shapes of a real accelerator project's hardware module (an AnyFlatSpecSim
  // base in src/main, suites in src/test), written from scratch.
  private val accelLike = Seq(
    "build.mill" -> "",
    "modules/hardware/src/main/scala/util/SimBase.scala" ->
      """package acme.util
        |
        |import chisel3._
        |import chisel3.simulator.scalatest.HasCliOptions.CliOption
        |import chisel3.simulator.scalatest.{ChiselSim, HasCliOptions}
        |import org.scalatest.flatspec.AnyFlatSpec
        |
        |trait DebugLayerCliOption {
        |  this: HasCliOptions =>
        |  addOption(CliOption.flag("debug", "enables debug"))
        |}
        |
        |trait AnyFlatSpecSim
        |    extends AnyFlatSpec
        |    with ChiselSim
        |    with DebugLayerCliOption {
        |  override def simulate[T <: Module](module: => T)(stimulus: T => Unit): Unit =
        |    super.simulate(module)(stimulus)
        |}
        |class GenericSim[T <: Module](tag: String, dutFactory: () => T) extends AnyFlatSpecSim {
        |  behavior of tag
        |  it should "not have expect violations" in {
        |    simulate(dutFactory())(_ => ())
        |  }
        |}
        |""".stripMargin,
    "modules/hardware/src/test/scala/unittest/TensorGemmTest.scala" ->
      """package unittest
        |
        |import acme.core._
        |import acme.tags
        |import acme.util.AnyFlatSpecSim
        |
        |@tags.UnitTests
        |class TensorGemmTestSuite extends AnyFlatSpecSim {
        |  behavior of "TensorGemmSimple"
        |  it should "compute a simple operation" in
        |    simulate(new TensorGemmSimple)(new TensorGemmTester(_))
        |
        |  it should "correctly generate indices" in simulate(new TensorGemmSimple)(
        |    new TensorGemmIdxTester(_)
        |  )
        |
        |  behavior of "TensorGemmPipelinedSplit"
        |
        |  it should "be reset correctly" in simulate(new TensorGemm)(
        |    new TensorGemmResetTester(_)
        |  )
        |}
        |""".stripMargin,
    "modules/hardware/src/test/scala/simulatorTest/gemm/TensorGemmTest.scala" ->
      """package simulatorTest.gemm
        |
        |import acme.util.AnyFlatSpecSim
        |
        |class TensorGemmTest(c: TensorGemmPipelinedSplit, fn: String, debug: Boolean) {
        |  val x = 1
        |}
        |
        |class TensorGemmJsonTestSuite extends AnyFlatSpecSim {
        |  behavior of "TensorGemmPipelinedSplit"
        |
        |  val debug = false
        |  def runSim(file: String) = {
        |    simulate(new TensorGemmPipelinedSplit) { c =>
        |      new TensorGemmTest(c, file, debug)
        |    }
        |  }
        |  it should "compute a simple matrix multiplication" in runSim(
        |    "/examples_gemm/simple.json"
        |  )
        |
        |  it should "compute a full operation" in
        |    runSim("/examples_gemm/full.json")
        |}
        |
        |class TensorGemmTester_test extends AnyFlatSpecSim {
        |  "Test instructions" should "run without assertions" in
        |    simulate(new TensorGemmPipelinedSplit)(_ => ())
        |}
        |""".stripMargin,
    "modules/hardware/src/test/scala/cli/ComputeTest.scala" ->
      """package cli
        |
        |import acme.util.AnyFlatSpecSim
        |
        |trait ComputeTest extends AnyFlatSpecSim {
        |  def computeSimulation(file: String): Unit = simulate(new Compute)(_ => ())
        |}
        |
        |class ComputeTests extends ComputeTest {
        |  behavior of "Compute"
        |
        |  it should "execute a ReLU" in computeSimulation(
        |    "relu.json"
        |  )
        |}
        |""".stripMargin,
    "modules/hardware/src/test/scala/unittest/Generic.scala" ->
      """package unittest
        |
        |import acme.tags.tagObjects.{LongTests, UnitTests}
        |
        |class GenericTest[T <: Module, C <: Parameters](
        |  tag: String,
        |  isLongTest: Boolean = false
        |) extends acme.util.AnyFlatSpecSim {
        |
        |  behavior of tag
        |  if (isLongTest) {
        |    it should "not have expect violations" taggedAs (LongTests) in { }
        |  } else {
        |    it should "not have expect violations" taggedAs (UnitTests) in { }
        |  }
        |}
        |class ComputeApp extends GenericTest("ComputeApp")
        |""".stripMargin,
    "modules/hardware/src/test/scala/shell/MemSpec.scala" ->
      """package acme.shell
        |
        |import org.scalatest.matchers.should.Matchers
        |import acme.util.AnyFlatSpecSim
        |
        |class MemSpec extends AnyFlatSpecSim with Matchers {
        |  behavior of "Mem"
        |  it should "read one beat" taggedAs (UnitTests) in { }
        |  ignore should "not issue AR bursts that cross a 4 KB boundary (#4)" in { }
        |}
        |""".stripMargin,
    "modules/hardware/src/test/scala/util/FileManagerTest.scala" ->
      """package acme.util
        |class FileManagerTest extends AnyFlatSpec with Matchers {
        |  "FileManager" should "find the config" in { }
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
    test("accelerator-like tree") {
      val root = tree(accelLike: _*)
      try {
        val bases = new SimBaseIndex(root).bases()
        assert(Set("AnyFlatSpecSim", "acme.util.AnyFlatSpecSim", "ComputeTest").subsetOf(bases))
        val got = lenses(root, bases)
        assert(got == Seq(
          ("cli.ComputeTests", "Compute should execute a ReLU"),
          ("acme.shell.MemSpec", "Mem should read one beat"),
          ("simulatorTest.gemm.TensorGemmJsonTestSuite", "TensorGemmPipelinedSplit should compute a simple matrix multiplication"),
          ("simulatorTest.gemm.TensorGemmJsonTestSuite", "TensorGemmPipelinedSplit should compute a full operation"),
          ("simulatorTest.gemm.TensorGemmTester_test", "Test instructions should run without assertions"),
          ("unittest.TensorGemmTestSuite", "TensorGemmSimple should compute a simple operation"),
          ("unittest.TensorGemmTestSuite", "TensorGemmSimple should correctly generate indices"),
          ("unittest.TensorGemmTestSuite", "TensorGemmPipelinedSplit should be reset correctly"),
        ))
        // without the index only the simulate( fallback finds suites: not
        // those that call it through an inherited helper or not at all
        assert(lenses(root, Set.empty) ==
          got.filterNot(t => Set("cli.ComputeTests", "acme.shell.MemSpec")(t._1)))
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
      val root = tree(accelLike: _*)
      val pool = Executors.newFixedThreadPool(4)
      try {
        val idx = new SimBaseIndex(root)
        val fs  = (1 to 8).map(_ => pool.submit(new Callable[Set[String]] { def call() = { idx.invalidate(); idx.bases() } }))
        val all = fs.map(_.get())
        assert(all.distinct.size == 1, all.head.contains("AnyFlatSpecSim"))
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
