package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

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

  val tests = Tests {

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
