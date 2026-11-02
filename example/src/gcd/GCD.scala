package gcd

import chisel3._
import chisel3.util._

// ── A classic Chisel demo: iterative Euclidean GCD ───────────────────────────
// One module, one Decoupled in, one Decoupled out, three registers.
//
// Functionally: pull (a, b) when idle, run x/y until equal, then emit on out.
// The exporter sees:
//   • Two ports (in: Flipped(Decoupled(...)), out: Decoupled(...))
//   • No submodules
//   • Connect statements for io.in.ready / io.out.valid / io.out.bits.result

class GCDInput extends Bundle {
  val a = UInt(16.W)
  val b = UInt(16.W)
}

class GCDOutput extends Bundle {
  val result = UInt(16.W)
}

class GCD extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new GCDInput))
    val out = Decoupled(new GCDOutput)
  })

  val x    = RegInit(0.U(16.W))
  val y    = RegInit(0.U(16.W))
  val busy = RegInit(false.B)

  // Accept a new pair when idle.
  io.in.ready := !busy
  when(io.in.fire) {
    x    := io.in.bits.a
    y    := io.in.bits.b
    busy := true.B
  }

  // Subtractive step.
  when(busy && x =/= y) {
    when(x > y) { x := x - y }.otherwise { y := y - x }
  }

  // Emit when converged.
  io.out.valid       := busy && (x === y)
  io.out.bits.result := x
  when(io.out.fire) { busy := false.B }
}

/** Generator-object form accepted by `--top` (issue #116): the natural
  * wrapper for parameterized designs, since the object can close over any
  * config or implicits it needs.
  *
  *   mill: socleTop = "gcd.GcdGen"   (or --top gcd.GcdGen)
  */
object GcdGen {
  def apply(): GCD = new GCD
}
