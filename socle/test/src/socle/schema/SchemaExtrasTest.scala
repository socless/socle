package socle.schema

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

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

  // Port `connected` flag (issue #74): a port is connected iff its name is
  // referenced somewhere in the body other than its own declaration. Clock used
  // only by a register counts as connected; a port in no statement does not.
  private val connChirrtl =
    """|circuit M :
       |  module M :
       |    input clk : Clock
       |    input used : UInt<8>
       |    output out : UInt<8>
       |    input dangling : UInt<8>
       |    reg r : UInt<8>, clk
       |    connect out, used
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

    test("connected flag: connect/reg-clock used = true, dangling = false") {
      val design = IrWalk.design(ChirrtlParser.parse(connChirrtl))
      val m = design.modules.find(_.name == "M").get
      def conn(n: String) = m.ports.find(_.name == n).get.connected
      assert(conn("used") == true)       // appears in `connect out, used`
      assert(conn("out") == true)        // LHS of the connect
      assert(conn("clk") == true)        // used as the register clock
      assert(conn("dangling") == false)  // referenced nowhere but its declaration
    }

    // Issue #100: references are path HEADS, not tokens. A dangling port that
    // shares its name with a referenced FIELD (io.valid), a module name
    // (inst u of Sub), or a statement keyword must stay flagged.
    test("connected flag: field names, module names, and keywords are not references") {
      val text =
        """|circuit T :
           |  module Sub :
           |    input io : { valid : UInt<1>}
           |  module T :
           |    input io : { valid : UInt<1>}
           |    output o : UInt<1>
           |    input valid : UInt<1>
           |    input Sub : UInt<1>
           |    input of : UInt<1>
           |    inst u of Sub
           |    connect u.io.valid, io.valid
           |    connect o, io.valid
           |""".stripMargin
      val design = IrWalk.design(ChirrtlParser.parse(text))
      val m = design.modules.find(_.name == "T").get
      def conn(n: String) = m.ports.find(_.name == n).get.connected
      assert(conn("io") == true)      // head of io.valid
      assert(conn("o") == true)       // connect LHS
      assert(conn("valid") == false)  // only `.valid` fields are referenced
      assert(conn("Sub") == false)    // only the module name appears
      assert(conn("of") == false)     // only the keyword appears
    }

    test("connected flag: blackbox ports default to connected") {
      val text =
        """|circuit T :
           |  extmodule Blk :
           |    input clk : Clock
           |    output q : UInt<8>
           |    defname = Blk
           |  module T :
           |    input clk : Clock
           |    inst b of Blk
           |    connect b.clk, clk
           |""".stripMargin
      val design = IrWalk.design(ChirrtlParser.parse(text))
      val blk = design.modules.find(_.name == "Blk").get
      assert(blk.ports.forall(_.connected))
    }

    test("PortDef.connected defaults to true and round-trips") {
      val p = PortDef("p", "in", "UInt<8>", "", SourceRef.unknown)
      assert(p.connected == true)
      val un   = p.copy(connected = false)
      val back = upickle.default.read[PortDef](upickle.default.write(un))
      assert(back.connected == false)
    }
  }
}
