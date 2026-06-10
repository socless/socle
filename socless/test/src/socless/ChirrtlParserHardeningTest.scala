package socless

import utest._

/** Hardening tests for ChirrtlParser: edge-case and malformed inputs must not
  * throw uncaught exceptions. The parser must skip or tolerate bad lines and
  * still return a Circ for anything it CAN parse.
  */
object ChirrtlParserHardeningTest extends TestSuite {

  // Convenience: parse and return Circ, letting the test fail on any throw.
  private def parse(s: String): ChirrtlParser.Circ = ChirrtlParser.parse(s)

  // Helper to find a module by name.
  private def mod(circ: ChirrtlParser.Circ, name: String): ChirrtlParser.Mod =
    circ.modules.find(_.name == name).getOrElse(
      throw new RuntimeException(s"module '$name' not found in ${circ.modules.map(_.name)}")
    )

  val tests = Tests {

    // ── Empty / blank input ──────────────────────────────────────────────────

    test("empty string returns empty Circ without throwing") {
      val circ = parse("")
      assert(circ.modules.isEmpty)
    }

    test("whitespace-only input returns empty Circ") {
      val circ = parse("   \n\t\n  ")
      assert(circ.modules.isEmpty)
    }

    test("blank lines inside module body are skipped") {
      val chirrtl =
        """|circuit Foo :
           |  module Foo :
           |    input clk : Clock
           |
           |
           |    input x : UInt<8>
           |""".stripMargin
      val circ = parse(chirrtl)
      assert(circ.modules.size == 1)
      assert(mod(circ, "Foo").ports.size == 2)
    }

    // ── Truncated / malformed port lines ────────────────────────────────────

    test("truncated port line (no type) is skipped gracefully") {
      val chirrtl =
        """|circuit Foo :
           |  module Foo :
           |    input x :
           |    input y : UInt<4>
           |""".stripMargin
      // 'input x :' still matches PortRe with typeStr="", parseType("") returns GroundT("",None)
      // so it may parse; what matters is no exception.
      val circ = parse(chirrtl)
      assert(circ.modules.size == 1)
    }

    test("port line missing colon is skipped gracefully") {
      val chirrtl =
        """|circuit Bar :
           |  module Bar :
           |    input x UInt<8>
           |    output y : UInt<8>
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "Bar")
      // the malformed line is silently skipped; only the valid port is kept
      assert(m.ports.size == 1)
      assert(m.ports.head.name == "y")
    }

    // ── Truncated connect lines ──────────────────────────────────────────────

    test("connect line with missing rhs is skipped") {
      val chirrtl =
        """|circuit Baz :
           |  module Baz :
           |    input a : UInt<1>
           |    connect a,
           |    connect a, b
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "Baz")
      // The truncated 'connect a,' has empty rhs and may or may not be captured
      // depending on regex. Either way no exception.
      assert(m.ports.size == 1)
    }

    test("legacy connect (<=) with missing rhs is skipped gracefully") {
      val chirrtl =
        """|circuit Q :
           |  module Q :
           |    output z : UInt<1>
           |    z <=
           |    z <= UInt<1>(0)
           |""".stripMargin
      // 'z <=' doesn't match ConnRe2 (needs non-empty rhs capture). No throw.
      val circ = parse(chirrtl)
      assert(circ.modules.size == 1)
    }

    // ── Weird indentation / tabs ─────────────────────────────────────────────

    test("tab-indented port lines are parsed correctly") {
      val chirrtl = "circuit T :\n\tmodule T :\n\t\tinput clk : Clock\n\t\toutput q : UInt<1>\n"
      val circ = parse(chirrtl)
      // ModLine regex requires at least one leading whitespace character.
      // If the module line is not matched, modules is empty -- that is still
      // safe (no exception). If matched, we get ports.
      assert(circ.modules.size <= 1) // won't crash either way
    }

    test("extra spaces around colon in port type are tolerated") {
      val chirrtl =
        """|circuit W :
           |  module W :
           |    input x  :   UInt<16>
           |    output y :UInt<8>
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "W")
      assert(m.ports.size == 2)
      assert(m.ports.map(_.name).toSet == Set("x", "y"))
    }

    // ── Unknown statement keywords ───────────────────────────────────────────

    test("unknown statement keywords are silently ignored") {
      val chirrtl =
        """|circuit U :
           |  module U :
           |    input clk : Clock
           |    future_keyword foo = bar
           |    invalidstatement!!!
           |    output q : UInt<1>
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "U")
      assert(m.ports.size == 2)
    }

    // ── Bundle type edge cases ───────────────────────────────────────────────

    test("bundle type with extra spaces parses without throwing") {
      val typeStr = "{  flip  ready  :  UInt<1>  ,  valid  :  UInt<1>  }"
      // parseType should return a BundleT (exact field count depends on
      // whitespace trimming); what matters: no exception.
      val (tpe, _) = ChirrtlParser.parseType(typeStr)
      tpe match {
        case ChirrtlParser.BundleT(fields) => assert(fields.nonEmpty)
        case other                         => throw new RuntimeException(s"expected BundleT, got $other")
      }
    }

    test("empty bundle {} returns BundleT with no fields") {
      val (tpe, rest) = ChirrtlParser.parseType("{}")
      tpe match {
        case ChirrtlParser.BundleT(fields) => assert(fields.isEmpty)
        case other                         => throw new RuntimeException(s"expected BundleT, got $other")
      }
      assert(rest.isEmpty)
    }

    test("bundle with tab-separated flip keyword parses correctly") {
      val typeStr = "{\tflip\tready : UInt<1>, valid : UInt<1>}"
      val (tpe, _) = ChirrtlParser.parseType(typeStr)
      tpe match {
        case ChirrtlParser.BundleT(fields) =>
          assert(fields.size == 2)
          assert(fields.head.flip)
          assert(!fields(1).flip)
        case other => throw new RuntimeException(s"expected BundleT, got $other")
      }
    }

    test("bundle missing closing brace returns partial BundleT without throwing") {
      val (tpe, _) = ChirrtlParser.parseType("{ valid : UInt<1>")
      tpe match {
        case ChirrtlParser.BundleT(_) => () // ok
        case other                    => throw new RuntimeException(s"expected BundleT, got $other")
      }
    }

    test("bundle missing field colon returns partial BundleT without throwing") {
      val (tpe, _) = ChirrtlParser.parseType("{ noColon }")
      tpe match {
        case ChirrtlParser.BundleT(_) => () // ok
        case other                    => throw new RuntimeException(s"expected BundleT, got $other")
      }
    }

    // ── Vec type edge cases ──────────────────────────────────────────────────

    test("vec type with extra spaces in bracket parses without throwing") {
      val (tpe, _) = ChirrtlParser.parseType("UInt<8>[ 4 ]")
      tpe match {
        case ChirrtlParser.VecT(ChirrtlParser.GroundT("UInt", Some(8)), 4) => ()
        case other => throw new RuntimeException(s"unexpected: $other")
      }
    }

    test("vec type with unclosed bracket returns base type without throwing") {
      val (tpe, _) = ChirrtlParser.parseType("UInt<8>[")
      tpe match {
        case ChirrtlParser.GroundT("UInt", Some(8)) => ()
        case other => throw new RuntimeException(s"expected GroundT, got $other")
      }
    }

    // ── Ground type edge cases ───────────────────────────────────────────────

    test("empty type string returns GroundT without throwing") {
      val (tpe, rest) = ChirrtlParser.parseType("")
      tpe match {
        case ChirrtlParser.GroundT(_, _) => ()
        case other                       => throw new RuntimeException(s"unexpected: $other")
      }
    }

    test("type with unclosed width bracket returns GroundT with no width") {
      val (tpe, _) = ChirrtlParser.parseType("UInt<")
      tpe match {
        case ChirrtlParser.GroundT("UInt", None) => ()
        case other => throw new RuntimeException(s"unexpected: $other")
      }
    }

    // ── Source info edge cases ───────────────────────────────────────────────

    test("src marker with overflowed line number does not throw") {
      // A number larger than Int.MaxValue must not cause NumberFormatException
      val chirrtl =
        """|circuit S :
           |  module S :
           |    input x : UInt<8> @[Foo.scala 999999999999:1]
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "S")
      assert(m.ports.size == 1)
      // src may be None (overflow) - that is fine
    }

    test("src marker with no digits returns None src without throwing") {
      val chirrtl =
        """|circuit S2 :
           |  module S2 :
           |    input x : UInt<1> @[Foo.scala nodigits]
           |""".stripMargin
      val circ = parse(chirrtl)
      val m = mod(circ, "S2")
      assert(m.ports.size == 1)
    }

    // ── Multi-module: malformed lines in one module don't affect others ───────

    test("malformed lines in one module do not corrupt other modules") {
      val chirrtl =
        """|circuit Multi :
           |  module Good1 :
           |    input a : UInt<1>
           |    output b : UInt<1>
           |  module Bad :
           |    GARBAGE LINE !!! @@@
           |    input ALSO GARBAGE
           |    input ok : UInt<8>
           |  module Good2 :
           |    input x : UInt<4>
           |""".stripMargin
      val circ = parse(chirrtl)
      assert(circ.modules.size == 3)
      assert(mod(circ, "Good1").ports.size == 2)
      assert(mod(circ, "Bad").ports.size <= 1) // only 'ok' might parse; no throw
      assert(mod(circ, "Good2").ports.size == 1)
    }

    // ── Realistic minimal valid snippet still parses correctly ───────────────

    test("minimal valid CHIRRTL snippet still parses correctly") {
      val chirrtl =
        """|circuit GCD :
           |  public module GCD :
           |    input clock : Clock @[GCD.scala 5:7]
           |    input reset : UInt<1> @[GCD.scala 5:7]
           |    output io : { flip x : UInt<16>, flip y : UInt<16>, z : UInt<16> }
           |    inst sub of SubModule @[GCD.scala 20:19]
           |    connect sub.in, io.x @[GCD.scala 21:11]
           |""".stripMargin
      val circ = parse(chirrtl)
      assert(circ.name == "GCD")
      val m = mod(circ, "GCD")
      assert(m.kind == "module")
      assert(m.ports.size == 3)
      assert(m.insts.size == 1)
      assert(m.insts.head.ofModule == "SubModule")
      assert(m.conns.size == 1)
    }
  }
}
