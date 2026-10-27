package socless.capture

import socless.ir.FsmDetect
import socless.parse.ChirrtlParser
import socless.schema._
import utest._

object FsmEncodingTest extends TestSuite {
  private val F = "example/src/fsm/TrafficFsm.scala"

  private lazy val traffic = {
    val s = getClass.getResourceAsStream("/captured/fsm.json")
    val chirrtl = ujson.read(scala.io.Source.fromInputStream(s, "UTF-8").mkString)("chirrtl").str
    ChirrtlParser.parse(chirrtl).modules.find(_.name == "TrafficFsm").get
  }

  private def state(n: String, line: Int, initial: Boolean = false, file: String = F) =
    FsmState(n, initial, SourceRef(file, line, 5))
  private def tr(from: String, to: String, line: Int, col: Int = 35, file: String = F) =
    FsmTransition(from, to, "io.tick", SourceRef(file, line, col))

  // TrafficFsm.scala as FsmFacts reads it: `is(sRed) { when(io.tick) { state := sGreen } }` on 16..18.
  private val trafficDiagram = FsmDiagram(
    Seq(state("sRed", 16, initial = true), state("sGreen", 17), state("sYellow", 18)),
    Seq(tr("sRed", "sGreen", 16), tr("sGreen", "sYellow", 17), tr("sYellow", "sRed", 18)))

  private def lits(m: ChirrtlParser.Mod) = FsmDetect.detect(m).find(_.stateReg == "state").get.caseLits

  // A ChiselEnum with sparse values (Idle 0, Load 5, Run 9, Done 12), as Chisel 7 emits it.
  private val Sparse = "S.scala"
  private lazy val sparse = ChirrtlParser.parse(
    s"""FIRRTL version 7.0.0
       |circuit SparseFsm :
       |  public module SparseFsm : @[$Sparse 16:7]
       |    input clock : Clock @[$Sparse 16:7]
       |    input reset : UInt<1> @[$Sparse 16:7]
       |    output io : { flip go : UInt<1>, busy : UInt<1>} @[$Sparse 17:14]
       |
       |    regreset state : UInt<4>, clock, reset, UInt<1>(0h0) @[$Sparse 21:22]
       |    node _T = asUInt(state) @[$Sparse 22:17]
       |    node _T_1 = eq(UInt<4>(0h0), _T) @[$Sparse 22:17]
       |    when _T_1 : @[$Sparse 22:17]
       |      when io.go : @[$Sparse 23:34]
       |        connect state, UInt<3>(0h5) @[$Sparse 23:42]
       |    else :
       |      node _T_2 = asUInt(state) @[$Sparse 22:17]
       |      node _T_3 = eq(UInt<4>(0h5), _T_2) @[$Sparse 22:17]
       |      when _T_3 : @[$Sparse 22:17]
       |        connect state, UInt<4>(0h9) @[$Sparse 24:28]
       |      else :
       |        node _T_4 = asUInt(state) @[$Sparse 22:17]
       |        node _T_5 = eq(UInt<4>(0h9), _T_4) @[$Sparse 22:17]
       |        when _T_5 : @[$Sparse 22:17]
       |          node _T_6 = eq(io.go, UInt<1>(0h0)) @[$Sparse 25:27]
       |          when _T_6 : @[$Sparse 25:35]
       |            connect state, UInt<4>(0hc) @[$Sparse 25:43]
       |        else :
       |          node _T_7 = asUInt(state) @[$Sparse 22:17]
       |          node _T_8 = eq(UInt<4>(0hc), _T_7) @[$Sparse 22:17]
       |          when _T_8 : @[$Sparse 22:17]
       |            connect state, UInt<1>(0h0) @[$Sparse 26:28]
       |    node _io_busy_T = neq(state, UInt<1>(0h0)) @[$Sparse 28:20]
       |    connect io.busy, _io_busy_T @[$Sparse 28:11]
       |""".stripMargin).modules.head

  private val sparseDiagram = FsmDiagram(
    Seq(state("Idle", 23, initial = true, file = Sparse), state("Load", 24, file = Sparse),
      state("Run", 25, file = Sparse), state("Done", 26, file = Sparse)),
    Seq(tr("Idle", "Load", 23, 36, Sparse), tr("Load", "Run", 24, 20, Sparse),
      tr("Run", "Done", 25, 37, Sparse), tr("Done", "Idle", 26, 20, Sparse)))

  val tests = Tests {
    test("the parser keeps a regreset's init") {
      assert(traffic.regs.find(_.name == "state").flatMap(_.init) == Some("UInt<2>(0h0)"))
    }

    test("TrafficFsm: Enum(3) in order") {
      val v = FsmEncoding.values(traffic, "state", trafficDiagram, lits(traffic))
      assert(v == Map[String, BigInt]("sRed" -> 0, "sGreen" -> 1, "sYellow" -> 2))
    }

    test("a ChiselEnum keeps its own values") {
      val v = FsmEncoding.values(sparse, "state", sparseDiagram, lits(sparse))
      assert(v == Map[String, BigInt]("Idle" -> 0, "Load" -> 5, "Run" -> 9, "Done" -> 12))
    }

    test("each pairing alone gives the states it reaches") {
      // no transitions, no reset: the chain alone
      val chainOnly = FsmEncoding.values(sparse.copy(regs = Nil), "state", sparseDiagram.copy(transitions = Nil), lits(sparse))
      assert(chainOnly == Map[String, BigInt]("Idle" -> 0, "Load" -> 5, "Run" -> 9, "Done" -> 12))
      // no chain (an `is(a, b)` gives two states one case): assignments and the reset
      val shared = sparseDiagram.copy(states = sparseDiagram.states.map(_.copy(source = SourceRef(Sparse, 23, 5))))
      assert(FsmEncoding.values(sparse, "state", shared, lits(sparse)) ==
        Map[String, BigInt]("Idle" -> 0, "Load" -> 5, "Run" -> 9, "Done" -> 12))
      // nothing but the reset
      assert(FsmEncoding.values(sparse, "state", shared.copy(transitions = Nil), Nil) == Map[String, BigInt]("Idle" -> 0))
    }

    test("pairings that disagree give no value at all") {
      // the states read in another order than the cases: the chain says sGreen 0
      val swapped = trafficDiagram.copy(states = Seq(state("sGreen", 16), state("sRed", 17, initial = true), state("sYellow", 18)))
      assert(FsmEncoding.values(traffic, "state", swapped, lits(traffic)).isEmpty)
    }

    test("a line assigning another literal too pairs nothing there") {
      val extra = traffic.copy(conns = traffic.conns :+ ChirrtlParser.Conn("io.green", "UInt<1>(0h1)", Some(ChirrtlParser.SrcInfo(F, 16, 60))))
      val v = FsmEncoding.values(extra, "state", trafficDiagram.copy(states = trafficDiagram.states.map(_.copy(source = SourceRef.unknown))), Nil)
      // line 16 has two literal connects for one transition: skipped; 17, 18 and the reset still pair
      assert(v == Map[String, BigInt]("sYellow" -> 2, "sRed" -> 0))
    }
  }
}
