package probefix

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** A ChiselSim test that never calls `simulate`: the probe finds no design. */
class NoSimulateSpec extends AnyFunSuite with ChiselSim {
  test("plain arithmetic") {
    assert(1 + 1 == 2)
  }
}
