package rvpipeline

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class RVPipelineSpec extends AnyFlatSpec with ChiselSim {
  "RVPipeline" should "run a few cycles after reset" in {
    simulate(new RVPipeline) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.imem.req.ready.poke(true.B)
      dut.io.imem.resp.valid.poke(true.B)
      dut.io.imem.resp.bits.rdata.poke(0x00000013.U)
      dut.io.dmem.req.ready.poke(true.B)
      dut.io.dmem.resp.valid.poke(false.B)
      dut.clock.step(8)
    }
  }
}
