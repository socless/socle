package socle.parse

import utest._

object WhenDeclTest extends TestSuite {
  private def mod(path: String) =
    ChirrtlParser.parse(scala.io.Source.fromInputStream(getClass.getResourceAsStream(path), "UTF-8").mkString).modules.find(_.name == "TrafficFsm").get

  private val fsm = "/chirrtl/fsm.fir"

  private val elseText =
    """circuit M :
      |  module M :
      |    input a : UInt<1>
      |    input b : UInt<1>
      |    output o : UInt<8>
      |    when a : @[M.scala 5:3]
      |      connect o, UInt<8>(0h1)
      |    else :
      |      node n = not(b)
      |      when b : @[M.scala 8:5]
      |        connect o, UInt<8>(0h2)
      |""".stripMargin

  val tests = Tests {
    test("each when line gets a WhenDecl with its marker") {
      val whens = mod(fsm).whens
      assert(whens.size == 6)
      assert(whens(0).cond == "_T")
      assert(whens(0).src.map(_.line) == Some(15))
      assert(whens(1).cond == "io.tick")
      assert(whens(1).src.map(_.line) == Some(16))
      assert(whens(1).parents == Seq((whens(0).id, false)))
    }
    test("conns carry the ids of their guards") {
      val m = mod(fsm)
      val c = m.conns.find(c => c.lhs == "state" && c.rhs == "UInt<2>(0h2)").get
      assert(c.whenIds.size == c.guard.size)
      assert(c.whenIds.last == m.whens(3).id)
    }
    test("else keeps the id and flips inElse") {
      val m = ChirrtlParser.parse(elseText).modules.head
      val n = m.nodes.find(_.name == "n").get
      assert(n.whenIds.head == m.whens(0).id)
      assert(n.guard.head.negated)
      assert(m.whens(1).parents == Seq((m.whens(0).id, true)))
    }
  }
}
