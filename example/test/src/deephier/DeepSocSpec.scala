package deephier

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class DeepSocSpec extends AnyFunSuite with ChiselSim {
  test("DeepSoc elaborates and steps") {
    simulate(new DeepSoc) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.data.poke(7.U)
      dut.io.in.bits.valid.poke(true.B)
      dut.clock.step(5)
    }
  }

  test("Cluster with 3 cores steps") {
    simulate(new Cluster(3)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.data.poke(3.U)
      dut.io.in.bits.valid.poke(true.B)
      dut.clock.step(5)
    }
  }
}
