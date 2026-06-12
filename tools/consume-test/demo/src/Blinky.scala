package demo

import chisel3._

/** Minimal design for the plugin consumption smoke: one register, one
  * output, enough for the exporter to emit a non-trivial design.json.
  */
class Blinky extends Module {
  val io = IO(new Bundle { val led = Output(Bool()) })
  val cnt = RegInit(0.U(8.W))
  cnt := cnt + 1.U
  io.led := cnt(7)
}
