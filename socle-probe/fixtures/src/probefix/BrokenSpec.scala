package probefix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** A module whose constructor fails: elaboration throws. */
class Broken extends Module {
  val io = IO(new Bundle { val out = Output(Bool()) })
  require(false, "broken on purpose")
  io.out := false.B
}

class BrokenSpec extends AnyFunSuite with ChiselSim {
  test("broken module") {
    simulate(new Broken) { dut => dut.clock.step() }
  }
}
