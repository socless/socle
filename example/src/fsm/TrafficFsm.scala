package fsm

import chisel3._
import chisel3.util._

/** Minimal 3-state FSM (Red -> Green -> Yellow -> Red) advanced by a tick.
  * No-arg constructor so socless.Launcher can elaborate it directly. */
class TrafficFsm extends Module {
  val io = IO(new Bundle {
    val tick  = Input(Bool())
    val green = Output(Bool())
  })
  val sRed :: sGreen :: sYellow :: Nil = Enum(3)
  val state = RegInit(sRed)
  switch(state) {
    is(sRed)    { when(io.tick) { state := sGreen } }
    is(sGreen)  { when(io.tick) { state := sYellow } }
    is(sYellow) { when(io.tick) { state := sRed } }
  }
  io.green := state === sGreen
}
