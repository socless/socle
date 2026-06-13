package demo

import chisel3._

/** A second design so the consume smoke exercises the multi-top path
  * (issue #117): structurally distinct from Blinky. */
class Counter extends Module {
  val io = IO(new Bundle {
    val en  = Input(Bool())
    val out = Output(UInt(16.W))
  })
  val count = RegInit(0.U(16.W))
  when(io.en) { count := count + 1.U }
  io.out := count
}
