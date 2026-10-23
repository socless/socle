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
