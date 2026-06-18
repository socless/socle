package layers

import chisel3._
import chisel3.util._

/** A small design that exercises Chisel verification layers (issue #212).
  *
  * `assert` / `assume` / `cover` (and any `layer.block`) content is emitted by
  * Chisel into `layerblock Verification ...` blocks in the CHIRRTL, separate
  * from the real design. Without layer handling the exporter flattens that
  * verification logic (the comparison nodes feeding the assertions) into the
  * schematic as if it were design dataflow. This example gives the pipeline a
  * module whose body has both real logic and a non-trivial Verification layer,
  * so the layer-collapse + "show layers" toggle can be tested.
  *
  * No-arg constructor so `layers.LayeredCounter` elaborates directly.
  */
class LayeredCounter extends Module {
  val io = IO(new Bundle {
    val en    = Input(Bool())
    val limit = Input(UInt(8.W))
    val count = Output(UInt(8.W))
    val wrap  = Output(Bool())
  })

  val count = RegInit(0.U(8.W))
  val wrap  = count === io.limit

  when(io.en) {
    count := Mux(wrap, 0.U, count + 1.U)
  }

  io.count := count
  io.wrap  := wrap

  // Verification-layer content (#212): these land in `layerblock Verification`,
  // along with the comparison nodes that compute their conditions.
  assert(count <= io.limit, "count must never exceed limit")
  assume(io.limit =/= 0.U, "limit is configured non-zero")
  cover(wrap, "wrap occurred")
}
