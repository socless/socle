package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

/** Export warnings channel (issue #118): the parser/IR-walk collect non-fatal
  * issues into Design.warnings instead of only printing to a forked JVM's
  * stderr, so the UI can surface them and --strict can gate on them.
  */
object WarningsTest extends TestSuite {

  // A design that exercises both skipped constructs: a partial connect and an
  // attach. The rest is a normal connect so the module is not empty.
  private val withSkips =
    """|circuit T :
       |  module T :
       |    input a : UInt<8>
       |    output b : UInt<8>
       |    output c : UInt<8>
       |    connect b, a
       |    c <- a
       |    attach(a, b)
       |""".stripMargin

  private val clean =
    """|circuit T :
       |  module T :
       |    input a : UInt<8>
       |    output b : UInt<8>
       |    connect b, a
       |""".stripMargin

  // `dangling` is never referenced in the body, so it stays unconnected; `a`/`b`
  // are wired so the module is not flagged empty.
  private val withDangling =
    """|circuit T :
       |  module T :
       |    input a : UInt<8>
       |    output b : UInt<8>
       |    input dangling : UInt<8>
       |    connect b, a
       |""".stripMargin

  // `Stub` is a real module with no connects and no instances -> empty-module.
  // `T` wires its ports so it is the clean, non-empty baseline.
  private val withEmptyModule =
    """|circuit T :
       |  module Stub :
       |    input a : UInt<8>
       |    output b : UInt<8>
       |  module T :
       |    input a : UInt<8>
       |    output b : UInt<8>
       |    connect b, a
       |""".stripMargin

  private def module(body: String): String =
    "circuit T :\n  module T :\n    input a : UInt<8>\n    output b : UInt<8>\n    connect b, a\n" +
      body.stripMargin + "\n"

  private def parserWarnings(chirrtl: String): Map[String, Warning] =
    ChirrtlParser.parse(chirrtl).warnings.map(w => w.kind -> w).toMap

  val tests = Tests {

    test("a statement of an unknown kind becomes an unrecognised-statement warning") {
      val w = parserWarnings(module(
        """|    frobnicate a, b @[T.scala 3:5]
           |    frobnicate b, a
           |    tangle(a)""")).get("unrecognised-statement")
      assert(w.isDefined)
      assert(w.get.count == 3)
      assert(w.get.message.contains("frobnicate"))
      assert(w.get.message.contains("tangle"))
    }

    test("a known statement in a shape the parser does not read becomes an unparsed-statement warning") {
      val w = parserWarnings(module(
        """|    node = add(a, a) @[T.scala 4:5]
           |    inst u1""")).get("unparsed-statement")
      assert(w.isDefined)
      assert(w.get.count == 2)
      assert(w.get.message.contains("node = add(a, a)"))
      assert(!w.get.message.contains("@["))
    }

    test("statements skipped on purpose raise no parser warning") {
      val ws = parserWarnings(
        """|circuit T :
           |  extmodule BB :
           |    input a : UInt<8>
           |    defname = BlackBoxImpl
           |    parameter W = 8
           |  module T :
           |    input clock : Clock
           |    input a : UInt<8>
           |    output b : UInt<8>
           |
           |    invalidate b
           |    b is invalid
           |    connect b, a
           |    when a :
           |      skip
           |    else when a :
           |      printf(clock, UInt<1>(1), "a <= %d\n", a) : p
           |    stop(clock, UInt<1>(1), 1) : s
           |    assert(clock, a, UInt<1>(1), "") : x
           |    assume(clock, a, UInt<1>(1), "") : y
           |    cover(clock, a, UInt<1>(1), "") : z
           |    intrinsic(circt_chisel_ifelsefatal<format = "f">, clock, a, a)
           |    define p = probe(a)
           |    layerblock Verification :
           |      skip
           |""".stripMargin)
      assert(ws.isEmpty)
    }

    test("block bodies are skipped with their header: formal, simulation, class, instchoice") {
      val c = ChirrtlParser.parse(
        """|circuit T :
           |  module T :
           |    input a : UInt<8>
           |    output b : UInt<8>
           |    domain d of ClockDomain(a)
           |    domain_define b = d
           |    instchoice u of Dflt, Platform : @[T.scala 5:5]
           |      FPGA => FpgaImpl
           |      ASIC => AsicImpl
           |    connect b, a
           |  class Props :
           |    output n : Integer
           |
           |    object o of Other
           |    propassign n, Integer(1)
           |    propassert n, "n"
           |  formal check of T :
           |    bound = 20
           |  simulation sim of T :
           |    seed = 1
           |""".stripMargin)
      assert(c.warnings.map(w => (w.kind, w.count)) == Seq(("unrecognised-statement", 1)))
      assert(c.warnings.head.message.contains("'instchoice'"))
      // the class's port is not T's, and T's connect after the instchoice body is read
      assert(c.modules.head.ports.map(_.name) == Seq("a", "b"))
      assert(c.modules.head.conns.map(_.lhs) == Seq("b"))
    }

    test("no committed CHIRRTL fixture or capture raises a parser warning") {
      val root = socle.Fixtures.repoRoot / "socle" / "test" / "resources"
      val firs = os.walk(root / "chirrtl").filter(_.ext == "fir").map(f => f.last -> os.read(f))
      val caps = os.list(root / "captured").map(f =>
        f.last -> socle.capture.CapturedModel.fromJson(os.read(f)).chirrtl)
      assert(firs.size >= 9, caps.size >= 4)
      val noisy = (firs ++ caps).flatMap { case (n, c) => ChirrtlParser.parse(c).warnings.map(w => s"$n: ${w.message}") }
      assert(noisy.isEmpty)
    }

    test("skipped partial connects and attach become structured warnings") {
      val d = IrWalk.design(ChirrtlParser.parse(withSkips))
      val byKind = d.warnings.map(w => w.kind -> w).toMap
      assert(byKind.contains("skipped-partial-connect"))
      assert(byKind.contains("skipped-attach"))
      assert(byKind("skipped-partial-connect").count == 1)
      assert(byKind("skipped-attach").count == 1)
      assert(byKind("skipped-partial-connect").message.contains("partial connect"))
    }

    test("a clean design has no warnings") {
      val d = IrWalk.design(ChirrtlParser.parse(clean))
      assert(d.warnings.isEmpty)
    }

    test("a dangling port becomes an unconnected-port warning") {
      val d = IrWalk.design(ChirrtlParser.parse(withDangling))
      val byKind = d.warnings.map(w => w.kind -> w).toMap
      assert(byKind.contains("unconnected-port"))
      assert(byKind("unconnected-port").count == 1)
      assert(byKind("unconnected-port").message.contains("T.dangling"))
    }

    test("a module with no connects or instances becomes an empty-module warning") {
      val d = IrWalk.design(ChirrtlParser.parse(withEmptyModule))
      val byKind = d.warnings.map(w => w.kind -> w).toMap
      assert(byKind.contains("empty-module"))
      assert(byKind("empty-module").count == 1)
      assert(byKind("empty-module").message.contains("Stub"))
    }

    test("Design.warnings defaults to empty and round-trips through JSON") {
      val d = Design("T", Map.empty, Nil)
      assert(d.warnings.isEmpty)
      val js = upickle.default.write(d)
      // serializeDefaults is NOT set on warnings, so a clean design omits it,
      // keeping older/byte-identical JSON for warning-free exports.
      assert(!js.contains("\"warnings\""))
      val withW = d.copy(warnings = Seq(Warning("k", 2, "msg", "ModX")))
      val back  = upickle.default.read[Design](upickle.default.write(withW))
      assert(back.warnings == Seq(Warning("k", 2, "msg", "ModX")))
    }

    test("--strict is parsed as a boolean flag") {
      val Right(cfg) = Launcher.parseArgs(Array("--top", "pkg.T", "--strict"))
      assert(cfg.strict)
      val Right(cfg2) = Launcher.parseArgs(Array("--top", "pkg.T"))
      assert(!cfg2.strict)
    }
  }
}
