package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._
import ChirrtlParser.{parse, NodeDecl}

object NodeDeclTest extends TestSuite {
  private def nodesOf(s: String): Seq[NodeDecl] =
    parse(s).modules.find(_.name == "M").get.nodes

  private val src =
    """circuit M :
      |  module M :
      |    input a : UInt<8>
      |    input b : UInt<8>
      |    input c : UInt<1>
      |    output o : UInt<8>
      |    node sum = add(a, b)
      |    node sel = mux(c, a, sum)
      |    node sliced = bits(a, 3, 0)
      |    connect o, sel
      |""".stripMargin

  val tests = Tests {
    test("flat node parses into op + operands") {
      val ns = nodesOf(src)
      val sum = ns.find(_.name == "sum").get
      assert(sum.op == "add")
      assert(sum.operands == Seq("a", "b"))
    }
    test("mux operands keep order") {
      val sel = nodesOf(src).find(_.name == "sel").get
      assert(sel.op == "mux")
      assert(sel.operands == Seq("c", "a", "sum"))
    }
    test("bit-select node is parsed (classification happens later)") {
      val sl = nodesOf(src).find(_.name == "sliced").get
      assert(sl.op == "bits")
      assert(sl.operands == Seq("a", "3", "0"))
    }

    test("bare-reference node yields empty op and single operand") {
      val chirrtl =
        """circuit M :
          |  module M :
          |    input io_in : UInt<8>
          |    output o : UInt<8>
          |    node alias = io_in
          |    connect o, alias
          |""".stripMargin
      val alias = nodesOf(chirrtl).find(_.name == "alias").get
      assert(alias.op == "")
      assert(alias.operands == Seq("io_in"))
    }

    test("nested-call node: inner comma does not split outer operands") {
      val chirrtl =
        """circuit M :
          |  module M :
          |    input a : UInt<8>
          |    input b : UInt<8>
          |    input c : UInt<1>
          |    output o : UInt<8>
          |    node nested = add(mux(c, a, b), d)
          |    connect o, nested
          |""".stripMargin
      val nested = nodesOf(chirrtl).find(_.name == "nested").get
      assert(nested.op == "add")
      assert(nested.operands == Seq("mux(c, a, b)", "d"))
    }

    test("source marker is captured and not absorbed into operands") {
      val chirrtl =
        """circuit M :
          |  module M :
          |    input a : UInt<8>
          |    input b : UInt<8>
          |    output o : UInt<8>
          |    node sum = add(a, b) @[Foo.scala 3:2]
          |    connect o, sum
          |""".stripMargin
      val s = nodesOf(chirrtl).find(_.name == "sum").get
      assert(s.op == "add")
      assert(s.operands == Seq("a", "b"))
      assert(s.src.isDefined)
    }
  }
}
