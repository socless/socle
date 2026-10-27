package socless.wave

import socless.capture.{CapturedModel, ModelToDesign}
import socless.schema._
import utest._

object WaveEnumsTest extends TestSuite {
  private val Dut = "TOP.svsimTestbench.dut"

  // TrafficFsm's capture; without the source index its diagram has no names,
  // so they are set here as the index would.
  private lazy val fsm: Design = {
    val s = getClass.getResourceAsStream("/captured/fsm.json")
    val d = ModelToDesign.design(CapturedModel.fromJson(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
    val named = FsmDiagram(Seq(FsmState("sRed", initial = true, value = "0"), FsmState("sGreen", value = "1"), FsmState("sYellow", value = "2")), Nil)
    d.copy(modules = d.modules.map(m => m.copy(logic = m.logic.map(n => if (n.fsm.isDefined) n.copy(fsm = Some(named)) else n))))
  }

  val tests = Tests {
    test("an FSM's state register and its named values") {
      assert(WaveEnums.tables(fsm, Dut) ==
        Seq(WaveEnums.Table(s"$Dut.state", "state", Seq(BigInt(0) -> "sRed", BigInt(1) -> "sGreen", BigInt(2) -> "sYellow"))))
    }

    test("every instance of the module gets its table") {
      val m   = fsm.modules.head
      val top = ModuleDef("Top", "Top", "", SourceRef.unknown, Nil,
        Seq(InstanceDef("a", m.name, "", SourceRef.unknown), InstanceDef("b", m.name, "", SourceRef.unknown)), Nil, Metrics(0, 0, 0))
      val d = fsm.copy(top = "Top", modules = fsm.modules :+ top)
      assert(WaveEnums.tables(d, Dut).map(_.trace) == Seq(s"$Dut.a.state", s"$Dut.b.state"))
    }

    test("states without values give no table") {
      val bare = fsm.copy(modules = fsm.modules.map(m => m.copy(logic = m.logic.map(n =>
        n.copy(fsm = n.fsm.map(d => d.copy(states = d.states.map(_.copy(value = "")))))))))
      assert(WaveEnums.tables(bare, Dut).isEmpty)
    }
  }
}
