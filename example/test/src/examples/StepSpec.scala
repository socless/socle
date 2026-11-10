package examples

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** One test per bundled example without a spec of its own: reset, then a few
  * cycles. It gives every example a ChiselSim test, so the language server's
  * trace coverage check (socle-lsp `TraceCoverageTest`) can simulate it. */
class StepSpec extends AnyFunSuite with ChiselSim {
  /** `inputs`: pokes the inputs an example's assumptions need, before reset. */
  private def steps[T <: Module](gen: => T)(inputs: T => Unit = (_: T) => ()): Unit =
    simulate(gen) { dut =>
      inputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(3)
    }

  test("AXISystem steps")      { steps(new axisystem.AXISystem)() }
  test("Interconnect steps")   { steps(new iobundles.Interconnect)() }
  test("Datapath steps")       { steps(new regheavy.Datapath)() }
  test("LayeredCounter steps") { steps(new _root_.layers.LayeredCounter)(_.io.limit.poke(5.U)) }
  test("ScatterDemo steps")    { steps(new scatter.ScatterDemo)() }
  test("SpiSlave steps")       { steps(new _root_.domains.SpiSlave)() }
  test("BigArray steps")       { steps(new bigarray.BigArray)() }
}
