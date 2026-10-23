package socless.lsp

import utest._

object TestDiscoveryTest extends TestSuite {
  private val root = os.Path(sys.props("socless.root"))
  private def read(rel: String) = os.read(root / os.RelPath(rel))
  private def lineOf(src: String, needle: String) =
    src.linesIterator.indexWhere(_.contains(needle))

  val tests = Tests {
    test("funspec") {
      val src = read("example/test/src/gcd/GcdSpec.scala")
      assert(TestDiscovery.find(src) ==
        Seq(TestCase("gcd.GcdSpec", "GCD computes gcd(48, 18)", lineOf(src, "it(\"computes"))))
    }
    test("flatspec") {
      val src = read("example/test/src/rvpipeline/RVPipelineSpec.scala")
      assert(TestDiscovery.find(src) == Seq(
        TestCase("rvpipeline.RVPipelineSpec", "RVPipeline should run a few cycles after reset",
          lineOf(src, "\"RVPipeline\" should"))))
      val inline =
        """package p
          |class S extends AnyFlatSpec with ChiselSim {
          |  "A" should "x" in { }
          |  it should "y" in { }
          |  "B" must "z" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(inline).map(t => (t.name, t.line)) ==
        Seq(("A should x", 2), ("A should y", 3), ("B must z", 4)))
    }
    test("funsuite") {
      val src = read("example/test/src/deephier/DeepSocSpec.scala")
      assert(TestDiscovery.find(src).map(_.name) ==
        Seq("DeepSoc elaborates and steps", "Cluster with 3 cores steps"))
    }
    test("not chiselsim") {
      val src = "class S extends AnyFunSuite { test(\"a\") { } }"
      assert(TestDiscovery.find(src).isEmpty)
    }
    test("escapes") {
      val src =
        "class S extends AnyFunSpec with ChiselSim {\n  describe(\"X\") {\n    it(\"say \\\"hi\\\" (\\u00fc)\") { }\n  }\n}"
      assert(TestDiscovery.find(src).map(_.name) == Seq("X say \"hi\" (ü)"))
    }
    test("computed names skipped") {
      val src =
        """class S extends AnyFunSuite with ChiselSim {
          |  for (i <- 0 until 2) { test("loop") { } }
          |  test(s"case $i") { }
          |  test("ok") { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("ok"))
    }
    test("non-literal describe skipped") {
      val src =
        """class S extends AnyFunSpec with ChiselSim {
          |  describe(name) { it("a") { } }
          |  describe("X") { it("b") { } }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("X b"))
    }
    test("only runnable top-level classes") {
      val src =
        """package p
          |object O { class In extends AnyFunSuite with ChiselSim { test("n") { } } }
          |abstract class A extends AnyFunSuite with ChiselSim { test("a") { } }
          |trait T extends AnyFunSuite with ChiselSim { test("t") { } }
          |object Ob extends AnyFunSuite with ChiselSim { test("o") { } }
          |class Ok extends AnyFunSuite with ChiselSim { test("ok") { } }""".stripMargin
      assert(TestDiscovery.find(src).map(t => (t.suite, t.name)) == Seq(("p.Ok", "ok")))
    }
    test("behavior of sets the subject") {
      val src =
        """package unittest
          |class TensorGemmTestSuite extends AnyFlatSpecSim {
          |  behavior of "TensorGemmSimple"
          |  it should "compute a simple operation" in
          |    simulate(new TensorGemmSimple)(new TensorGemmTester(_))
          |
          |  it should "correctly generate indices" in simulate(new TensorGemmSimple)(
          |    new TensorGemmIdxTester(_)
          |  )
          |  behavior of "TensorGemmIndexGenerator"
          |  it should "perform correctly" in simulate(new TensorGemmIndexGenerator)(x)
          |  behavior.of("Dotted")
          |  they must "work" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src, Set("AnyFlatSpecSim")).map(t => (t.suite, t.name, t.line)) == Seq(
        ("unittest.TensorGemmTestSuite", "TensorGemmSimple should compute a simple operation", 3),
        ("unittest.TensorGemmTestSuite", "TensorGemmSimple should correctly generate indices", 6),
        ("unittest.TensorGemmTestSuite", "TensorGemmIndexGenerator should perform correctly", 10),
        ("unittest.TensorGemmTestSuite", "Dotted must work", 12)))
    }
    test("it before any subject") {
      // scalatest names it "should x" (std-vta-dev ComputeTestLeNet5)
      val src =
        """class S extends AnyFlatSpec with ChiselSim {
          |  it should "x" in { }
          |  behavior of "A"
          |  it must "y" in { }
          |}
          |class T extends AnyFlatSpec with ChiselSim {
          |  it can "z" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(t => (t.suite, t.name)) ==
        Seq(("S", "should x"), ("S", "A must y"), ("T", "can z")))
    }
    test("a base declaring a subject hides it before the suite's own") {
      // scalatest names the first test "X should y" (X set in Base's body): unknown here
      val src =
        """class S extends Base {
          |  it should "y" in { }
          |  "A" should "x" in { }
          |  behavior of "Z"
          |  it should "w" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src, Set("Base"), Set("Base")).map(_.name) == Seq("A should x", "Z should w"))
      assert(TestDiscovery.find(src, Set("Base"), Set("Other")).map(_.name) ==
        Seq("should y", "A should x", "Z should w"))
      assert(TestDiscovery.find(src.replace("extends Base", "extends p.Base"), Set("Base"), Set("Base"))
        .map(_.name) == Seq("A should x", "Z should w"))
    }
    test("fallback: simulate with type arguments") {
      val src =
        """class T extends AnyFlatSpec with X { "A" should "x" in { simulate[Foo](new Foo)(_ => ()) } }
          |class R extends AnyFlatSpec with X { "B" should "y" in { sim.simulateRaw[Foo](new Foo) { _ => () } } }""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("A should x", "B should y"))
    }
    test("non-literal behavior of clears the subject") {
      val src =
        """class S extends AnyFlatSpec with ChiselSim {
          |  behavior of "A"
          |  it should "a" in { }
          |  behavior of tag
          |  it should "b" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("A should a"))
    }
    test("ignore gets no lens and keeps the subject") {
      val src =
        """class S extends AnyFlatSpec with ChiselSim {
          |  behavior of "A"
          |  ignore should "x" in { }
          |  ignore must "x2" taggedAs (T) in { }
          |  it should "y" in { }
          |  "B" should "z" ignore { }
          |  it can "w" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("A should y", "B can w"))
    }
    test("taggedAs keeps the name") {
      val src =
        """class S extends AnyFlatSpec with ChiselSim {
          |  behavior of "A"
          |  it should "y" taggedAs(UnitTests) in { }
          |  it should "z" taggedAs (T1, T2) in { }
          |  "B" must "w" taggedAs (T1) taggedAs (T2) in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(t => (t.name, t.line)) ==
        Seq(("A should y", 2), ("A should z", 3), ("B must w", 4)))
    }
    test("fallback: a body calling simulate") {
      val src =
        """package p
          |class T extends AnyFlatSpec with CustomThing {
          |  "A" should "x" in { simulate(new Foo)(_ => ()) }
          |}
          |class R extends AnyFlatSpec with Other {
          |  "B" should "y" in { chisel3.simulator.EphemeralSimulator.simulateRaw(new Foo) { _ => () } }
          |}
          |class N extends AnyFlatSpec with Other {
          |  "C" should "z" in { simulateLater(new Foo) }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(t => (t.suite, t.name)) ==
        Seq(("p.T", "A should x"), ("p.R", "B should y")))
    }
    test("simBases match simple, qualified and generic parents") {
      def names(ext: String) =
        TestDiscovery.find(s"class S extends $ext { test(\"a\") { } }", Set("Base", "p.q.Base")).map(_.name)
      assert(names("Base") == Seq("a"))
      assert(names("p.q.Base") == Seq("a"))
      assert(names("other.Base") == Seq("a")) // same simple name: prefer the lens
      assert(names("Base[Int](1)") == Seq("a"))
      assert(names("AnyFunSuite with Other").isEmpty)
    }
    test("tests registered inside a def are skipped") {
      // the subject at the def site is not the one at its call sites
      val src =
        """class S extends AnyFlatSpec with ChiselSim {
          |  behavior of "A"
          |  def shared(): Unit = {
          |    behavior of "Inner"
          |    it should "in def" in { }
          |  }
          |  it should "x" in { }
          |}""".stripMargin
      assert(TestDiscovery.find(src).map(_.name) == Seq("A should x"))
    }
    test("parse failure") {
      assert(TestDiscovery.find("class {{{").isEmpty)
      assert(TestDiscovery.enclosingClass("class {{{", 0).isEmpty)
    }
    test("enclosingClass") {
      val src = read("example/src/deephier/DeepSoc.scala")
      val l = lineOf(src, "val cores = Seq.fill")
      assert(TestDiscovery.enclosingClass(src, l) == Some("deephier.Cluster"))
      assert(TestDiscovery.enclosingClass(src, lineOf(src, "Level 0: DeepSoc")) == None)
    }
    test("enclosingClass is the binary name") {
      // nesting joins with '$' (as getClass.getName), the package with '.'
      val src =
        """package a.b
          |object Units {
          |  class Adder extends Module {
          |    val io = 1
          |    class Inner {
          |      val x = 2
          |    }
          |  }
          |}""".stripMargin
      assert(TestDiscovery.enclosingClass(src, 3) == Some("a.b.Units$Adder"))
      assert(TestDiscovery.enclosingClass(src, 5) == Some("a.b.Units$Adder$Inner"))
      assert(TestDiscovery.enclosingClass(src, 1) == Some("a.b.Units$"))
      assert(TestDiscovery.enclosingClass("class Top {\n  val y = 1\n}", 1) == Some("Top"))
    }
  }
}
