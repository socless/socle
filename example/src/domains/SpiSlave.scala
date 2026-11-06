package domains

import chisel3._

/** Two clock domains derived from an input, as a SPI slave does: registers on
  * the rising edge of `sclk` (`rise`) and on its falling edge (`fall`, from the
  * `!sclk` logic), plus an asynchronous reset from the select line
  * (`ssReset`). The schematic colours each register by its domain, lists the
  * domains in a legend, and ends the logic deriving each clock or reset on a
  * labelled stub (`→ fall`). */
class SpiSlave extends Module {
  val io = IO(new Bundle {
    val sclk = Input(Bool())
    val ss   = Input(Bool())
    val mosi = Input(Bool())
    val miso = Output(Bool())
  })

  val rise: Clock    = io.sclk.asClock
  val fall: Clock    = (!io.sclk).asClock
  val ssReset: AsyncReset = io.ss.asAsyncReset

  // Shift in on the rising edge.
  val shiftIn: UInt = withClockAndReset(rise, ssReset) { RegInit(0.U(8.W)) }
  shiftIn := shiftIn(6, 0) ## io.mosi

  // Shift out on the falling edge.
  val shiftOut: UInt = withClockAndReset(fall, ssReset) { RegInit(0.U(8.W)) }
  shiftOut := shiftIn
  val miso: Bool = withClock(fall) { Reg(Bool()) }
  miso := shiftOut(7)
  io.miso := miso
}
