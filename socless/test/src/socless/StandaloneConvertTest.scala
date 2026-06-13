package socless

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
      val js = SoclessExporter.designJson(gcd)
      val design = upickle.default.read[Design](js)
      assert(design.top == "GCD")
      assert(design.modules.nonEmpty)
      // designJson runs the layout pass, so the GCD module has a layout.
      assert(design.layouts.nonEmpty)
    }

    test("designFromChirrtl and designJson agree") {
      val d  = SoclessExporter.designFromChirrtl(gcd)
      val js = SoclessExporter.designJson(gcd)
      assert(upickle.default.write(d, indent = 2) == js)
    }
  }
}
