package socless.probe

import chisel3.RawModule
import socless.capture._
import utest._

/** Instance wiring, checked against the CHIRRTL pipeline as the oracle. The
  * effective wiring of a module is the probe's `wires` when non-empty, else the
  * consumer-side derivation from the captured CHIRRTL (the agreed fallback). */
object WiringTest extends TestSuite {

  private def effectiveWires(m: CapturedModel): Map[String, Set[CWire]] = {
    lazy val fromChirrtl = WiresFromChirrtl.wires(m.chirrtl)
    m.modules.map { mod =>
      val ws = if (mod.wires.nonEmpty) mod.wires else fromChirrtl.getOrElse(mod.name, Nil)
      mod.name -> ws.toSet
    }.toMap
  }

  private def check(gen: => RawModule): Unit = {
    val m      = TestElab.captureOf(gen)
    val oracle = socless.SoclessExporter.designFromChirrtl(m.chirrtl)
    // Elaborated module names line up between the live walk and the CHIRRTL.
    assert(oracle.modules.map(_.name).toSet == m.modules.map(_.name).toSet)
    assert(WiresFromChirrtl.wires(m.chirrtl).keySet == m.modules.map(_.name).toSet)
    // Contract: empty `wires` with non-empty `chirrtl` means the consumer
    // derives wiring; a clean capture still has no problems.
    assert(m.modules.forall(_.wires.isEmpty), m.chirrtl.nonEmpty)
    assert(m.problems.isEmpty)
    val eff = effectiveWires(m)
    for (om <- oracle.modules) {
      val module   = om.name
      val expected = om.wires.map(w => CWire(w.from, w.to)).toSet
      val actual   = eff(module)
      Predef.assert(actual == expected, s"wiring of $module: actual $actual != expected $expected")
    }
    // Sanity: the comparison is not vacuous.
    assert(eff.values.exists(_.nonEmpty))
  }

  val tests = Tests {
    test("wiring matches the CHIRRTL pipeline") {
      test("rvpipeline") { check(new rvpipeline.RVPipeline) }
      test("deepsoc") { check(new deephier.DeepSoc) }
      test("axisystem") { check(new axisystem.AXISystem) }
    }
  }
}
