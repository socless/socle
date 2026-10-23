package gcd

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class GcdSpec extends AnyFunSpec with ChiselSim {
  describe("GCD") {
    it("computes gcd(48, 18)") {
      simulate(new GCD) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        dut.io.out.ready.poke(true.B)
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.a.poke(48.U)
        dut.io.in.bits.b.poke(18.U)
        dut.clock.step(1)
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (dut.io.out.valid.peek().litValue == 0 && cycles < 50) {
          dut.clock.step(1)
          cycles += 1
        }
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.result.expect(6.U)
      }
    }
  }
}
