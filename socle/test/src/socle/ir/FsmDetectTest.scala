package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._
import ChirrtlParser.parse

object FsmDetectTest extends TestSuite {
  private def mod(s: String) = parse(s).modules.head

  private val fsm =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input go : UInt<1>
      |    output out : UInt<1>
      |    regreset state : UInt<1>, clock, reset, UInt<1>(0h0)
      |    node _T = asUInt(state)
      |    node _T_1 = eq(UInt<1>(0h0), _T)
      |    when _T_1 :
      |      connect state, go
      |    else :
      |      node _T_2 = asUInt(state)
      |      node _T_3 = eq(UInt<1>(0h1), _T_2)
      |      when _T_3 :
      |        connect state, UInt<1>(0h0)
      |    connect out, _T_1
      |""".stripMargin

  private val notFsm =
    """circuit M :
      |  module M :
      |    input a : UInt<8>
      |    input b : UInt<8>
      |    output o : UInt<8>
      |    node _T = eq(a, b)
      |    when _T :
      |      connect o, a
      |""".stripMargin

  val tests = Tests {
    test("recognizes a 2-state switch on a register") {
      val fs = FsmDetect.detect(mod(fsm))
      assert(fs.size == 1)
      val f = fs.head
      assert(f.stateReg == "state")
      assert(f.id == "fsm$M$state")
      assert(f.outerCond == "_T_1")
      assert(f.memberSinks.contains("state"))
      assert(f.caseLits.size == 2)
    }
    test("a non-register single-when comparison is not an FSM") {
      assert(FsmDetect.detect(mod(notFsm)).isEmpty)
    }
    test("a chain comparing two different registers is not one FSM") {
      // outer compares regA, inner compares regB -> neither has >=2 case lits on one reg
      val s =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    regreset a : UInt<1>, clock, reset, UInt<1>(0h0)
          |    regreset b : UInt<1>, clock, reset, UInt<1>(0h0)
          |    output o : UInt<1>
          |    node _T = eq(UInt<1>(0h0), a)
          |    when _T :
          |      connect o, a
          |    else :
          |      node _T_1 = eq(UInt<1>(0h1), b)
          |      when _T_1 :
          |        connect o, b
          |""".stripMargin
      assert(FsmDetect.detect(mod(s)).isEmpty)
    }
    test("two independent FSMs in one module are both detected, sorted by id") {
      val s =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    regreset a : UInt<2>, clock, reset, UInt<2>(0h0)
          |    regreset b : UInt<2>, clock, reset, UInt<2>(0h0)
          |    node _A0 = eq(UInt<2>(0h0), a)
          |    when _A0 :
          |      connect a, UInt<2>(0h1)
          |    else :
          |      node _A1 = eq(UInt<2>(0h1), a)
          |      when _A1 :
          |        connect a, UInt<2>(0h0)
          |    node _B0 = eq(UInt<2>(0h0), b)
          |    when _B0 :
          |      connect b, UInt<2>(0h1)
          |    else :
          |      node _B1 = eq(UInt<2>(0h1), b)
          |      when _B1 :
          |        connect b, UInt<2>(0h0)
          |""".stripMargin
      val fs = FsmDetect.detect(mod(s))
      assert(fs.map(_.id) == Seq("fsm$M$a", "fsm$M$b")) // sorted, both found
    }
    test("a switch nested in an enclosing when is recognized") {
      // when(en) { switch(state) { ... } }: every node of the chain has `en`
      // as its outermost guard, the case conditions come after it
      val s =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    input en : UInt<1>
          |    input go : UInt<1>
          |    output busy : UInt<1>
          |    regreset state : UInt<1>, clock, reset, UInt<1>(0h0)
          |    regreset other : UInt<1>, clock, reset, UInt<1>(0h0)
          |    when en :
          |      connect other, go
          |      node _T = asUInt(state)
          |      node _T_1 = eq(UInt<1>(0h0), _T)
          |      when _T_1 :
          |        connect state, go
          |      else :
          |        node _T_2 = asUInt(state)
          |        node _T_3 = eq(UInt<1>(0h1), _T_2)
          |        when _T_3 :
          |          connect state, UInt<1>(0h0)
          |    connect busy, state
          |""".stripMargin
      val fs = FsmDetect.detect(mod(s))
      assert(fs.size == 1)
      val f = fs.head
      assert(f.stateReg == "state")
      assert(f.outerCond == "_T_1")
      assert(f.caseLits.size == 2)
      // a sink written under `en` but outside the switch is not the FSM's
      assert(f.memberSinks == Set("state"))
      // the conditions it reads besides its case compares: the enclosing `en`
      assert(f.condRefs == Seq("en"))
    }
    test("the conditions inside the cases are condition references") {
      val f = FsmDetect.detect(mod(fsm.replace("connect state, go", "when go :\n        connect state, UInt<1>(0h1)"))).head
      assert(f.condRefs == Seq("go"))
    }
    test("regheavy Controller is recognized with state register 'state'") {
      val text = scala.io.Source.fromInputStream(
        getClass.getResourceAsStream("/chirrtl/regheavy.fir")).mkString
      val controller = parse(text).modules.find(_.name == "Controller").get
      val fs = FsmDetect.detect(controller)
      assert(fs.exists(_.stateReg == "state"))
      val f = fs.find(_.stateReg == "state").get
      assert(f.memberSinks.contains("state") && f.memberSinks.contains("timer"))
    }
  }
}
