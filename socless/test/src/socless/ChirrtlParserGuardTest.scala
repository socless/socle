package socless

import utest._
import ChirrtlParser.{Guard, Conn}

object ChirrtlParserGuardTest extends TestSuite {
  private def conns(s: String): Seq[Conn] =
    ChirrtlParser.parse(s).modules.head.conns

  // 2-space indentation per nesting level, matching emitCHIRRTL output.
  private val nested =
    """circuit M :
      |  module M :
      |    input a : UInt<1>
      |    input b : UInt<1>
      |    output o : UInt<8>
      |    input x : UInt<8>
      |    input y : UInt<8>
      |    connect o, x
      |    when a :
      |      connect o, y
      |    else :
      |      when b :
      |        connect o, x
      |""".stripMargin

  val tests = Tests {
    test("unconditional connect has empty guard") {
      val c = conns(nested).find(c => c.lhs == "o" && c.rhs == "x" && c.guard.isEmpty)
      assert(c.isDefined)
    }
    test("single when tags a depth-1 non-negated guard") {
      val c = conns(nested).find(c => c.lhs == "o" && c.rhs == "y").get
      assert(c.guard == Seq(Guard("a", negated = false)))
    }
    test("else then nested when tags a depth-2 guard (outer negated)") {
      val c = conns(nested).filter(c => c.lhs == "o" && c.rhs == "x").maxBy(_.guard.length)
      assert(c.guard == Seq(Guard("a", negated = true), Guard("b", negated = false)))
    }
    test("scope is cleared across module boundaries") {
      val two =
        """circuit M :
          |  module A :
          |    output o : UInt<1>
          |    when sel :
          |      connect o, sel
          |  module B :
          |    output p : UInt<1>
          |    connect p, p
          |""".stripMargin
      val pConn = ChirrtlParser.parse(two).modules.find(_.name == "B").get.conns.head
      assert(pConn.guard.isEmpty)
    }
  }
}
