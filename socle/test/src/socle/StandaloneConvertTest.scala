package socle

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

/** The in-process CHIRRTL -> design.json conversion behind the standalone
  * server's POST /convert (issue #115). The HTTP layer is integration-tested
  * by the consume smoke; this pins the pure conversion.
  */
object StandaloneConvertTest extends TestSuite {

  private val gcd =
    """|circuit GCD :
       |  module GCD :
       |    input clock : Clock
       |    input reset : UInt<1>
       |    output io : { flip a : UInt<16>, b : UInt<16>}
       |""".stripMargin

  val tests = Tests {

    test("designJson produces valid, laid-out design JSON with no file I/O") {
      val js = SocleExporter.designJson(gcd)
      val design = upickle.default.read[Design](js)
      assert(design.top == "GCD")
      assert(design.modules.nonEmpty)
      // designJson runs the layout pass, so the GCD module has a layout.
      assert(design.layouts.nonEmpty)
    }

    test("designFromChirrtl and designJson agree") {
      val d  = SocleExporter.designFromChirrtl(gcd)
      val js = SocleExporter.designJson(gcd)
      assert(upickle.default.write(d, indent = 2) == js)
    }

    test("a sourceRoot embeds source text from the .fir's markers (#154)") {
      val dir     = os.temp.dir()
      val rel     = "pkg/Tiny.scala"
      val srcText = "class Tiny extends Module { val io = IO(new Bundle {}) }\n"
      os.write(dir / os.RelPath(rel), srcText, createFolders = true)
      val chirrtl =
        s"""|circuit Tiny :
            |  module Tiny : @[$rel 1:7]
            |    input clock : Clock @[$rel 1:7]
            |    input reset : UInt<1> @[$rel 1:7]
            |    output io : { flip a : UInt<8>, b : UInt<8>} @[$rel 2:14]
            |""".stripMargin

      // With a root: the marker resolves and the text is embedded.
      val withRoot = SocleExporter.designFromChirrtl(chirrtl, Some(dir))
      assert(withRoot.sources.get(rel).contains(srcText))
      assert(withRoot.sourceRoot == dir.toString)

      // Without a root: behaviour is unchanged (no embedding, terse JSON).
      val without = SocleExporter.designFromChirrtl(chirrtl)
      assert(without.sources.isEmpty)
      assert(without.sourceRoot == "")
    }
  }
}
