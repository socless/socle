package fsm

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class TrafficFsmSpec extends AnyFunSpec with ChiselSim {
  describe("TrafficFsm") {
    it("cycles red, green, yellow") {
      simulate(new TrafficFsm) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.tick.poke(false.B)
        dut.io.green.expect(false.B)
        dut.io.tick.poke(true.B)
        dut.clock.step(1)
        dut.io.green.expect(true.B)
        dut.clock.step(1)
        dut.io.green.expect(false.B)
        dut.clock.step(1)
        dut.io.green.expect(false.B)
        dut.clock.step(1)
        dut.io.green.expect(true.B)
      }
    }
  }
}
