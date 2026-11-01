package scatter

import chisel3._

/** Minimal design exercising the scattered-bundle direction split (#237).
  *
  * `Producer.io` is one aggregate bundle whose fields connect to three
  * DIFFERENT peers: outputs `a`/`b` drive two separate consumers and input `c`
  * is driven by a third. The wire coalescer therefore splits `p.io` into
  * field-level wires (no single clean partner), and the schematic shows the
  * `p` box's declared fields on their own sides (c WEST; a, b EAST, #332), with
  * each field wired to its own peer.
  * The consumers use plain scalar ports, so they show a single pin each (no
  * replicated bundle copy). */
class Producer extends Module {
  val io = IO(new Bundle {
    val a = Output(UInt(8.W))
    val b = Output(UInt(8.W))
    val c = Input(UInt(8.W))
  })
  io.a := io.c
  io.b := io.c + 1.U
}

/** A trivial pass-through with plain scalar ports (not an aggregate), so it is
  * never itself a scattered bundle. */
class Consumer extends Module {
  val in  = IO(Input(UInt(8.W)))
  val out = IO(Output(UInt(8.W)))
  out := in
}

class ScatterDemo extends Module {
  val z = IO(Input(UInt(8.W)))
  val x = IO(Output(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))

  val p  = Module(new Producer)
  val cx = Module(new Consumer)
  val cy = Module(new Consumer)
  val cz = Module(new Consumer)

  cz.in  := z
  p.io.c := cz.out   // p.io.c  <- cz  (input field, WEST group)
  cx.in  := p.io.a   // p.io.a  -> cx  (output field, EAST group)
  cy.in  := p.io.b   // p.io.b  -> cy  (output field, EAST group)
  x := cx.out
  y := cy.out
}
