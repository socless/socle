package socless

import utest._

/** Tests for the schema additions: emitted layout constants (issue #38) and
  * module-kind preservation for blackboxes (issue #20).
  */
object SchemaExtrasTest extends TestSuite {

  // A minimal CHIRRTL design with one normal module instantiating an
  // extmodule (blackbox) and an intmodule (intrinsic). Source markers are
  // omitted; the parser tolerates their absence.
  private val chirrtl =
    """|circuit Top :
       |  extmodule Blk :
       |    input clk : Clock
       |    output q : UInt<8>
       |  intmodule Intr :
       |    input a : UInt<8>
       |  module Top :
       |    input clk : Clock
       |    output q : UInt<8>
       |    inst blk of Blk
       |    inst intr of Intr
       |    connect blk.clk, clk
       |    connect q, blk.q
       |""".stripMargin

  val tests = Tests {

    test("layoutConsts mirrors Layout's source-of-truth values") {
      // Default on a bare Design (no layout pass) is the historical 28/24/18.
      val bare = Design("Top", Map.empty, Nil)
      assert(bare.layoutConsts.headerH == 28.0)
      assert(bare.layoutConsts.portStep == 24.0)
      assert(bare.layoutConsts.portPad == 18.0)

      // After the layout pass, the emitted block comes from Layout directly.
      val withL = Layout.withLayouts(bare)
      assert(withL.layoutConsts.headerH == Layout.HeaderH)
      assert(withL.layoutConsts.portStep == Layout.PortStep)
      assert(withL.layoutConsts.portPad == Layout.PortPad)
    }

    test("layoutConsts round-trips through JSON") {
      val d  = Layout.withLayouts(Design("Top", Map.empty, Nil))
      val js = upickle.default.write(d)
      assert(js.contains("layoutConsts"))
      val back = upickle.default.read[Design](js)
      assert(back.layoutConsts == d.layoutConsts)
    }

    test("module kind is preserved from CHIRRTL") {
      val design = IrWalk.design(ChirrtlParser.parse(chirrtl))
      def kindOf(name: String) = design.modules.find(_.name == name).map(_.kind)
      assert(kindOf("Blk")  == Some("external"))
      assert(kindOf("Intr") == Some("intrinsic"))
      assert(kindOf("Top")  == Some("internal"))
    }

    test("ModuleDef kind defaults to internal and round-trips") {
      val m = ModuleDef("M", "M", "", SourceRef.unknown, Nil, Nil, Nil, Metrics(0, 0, 0))
      assert(m.kind == "internal")
      val ext  = m.copy(kind = "external")
      val back = upickle.default.read[ModuleDef](upickle.default.write(ext))
      assert(back.kind == "external")
    }
  }
}
