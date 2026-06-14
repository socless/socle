package axisystem

import chisel3._
import chisel3.util._

// ── AXI4 channel definitions ────────────────────────────────────────────────
// Each channel is the `bits` payload of a Decoupled handshake. Widths follow
// the standard AXI4 channel shapes (see socless/test/resources/reference/axi.json).

class AR extends Bundle {
  val id    = UInt(4.W)
  val addr  = UInt(32.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)
  val cache = UInt(4.W)
  val prot  = UInt(3.W)
}

class AW extends Bundle {
  val id    = UInt(4.W)
  val addr  = UInt(32.W)
  val len   = UInt(8.W)
  val size  = UInt(3.W)
  val burst = UInt(2.W)
  val cache = UInt(4.W)
  val prot  = UInt(3.W)
}

class W extends Bundle {
  val data = UInt(64.W)
  val strb = UInt(8.W)
  val last = Bool()
}

class R extends Bundle {
  val id   = UInt(4.W)
  val data = UInt(64.W)
  val resp = UInt(2.W)
  val last = Bool()
}

class B extends Bundle {
  val id   = UInt(4.W)
  val resp = UInt(2.W)
}

// ── AXI4 aggregate ──────────────────────────────────────────────────────────
// Five channels - AR/AW/W flow master→slave, R/B flow slave→master (Flipped).
// Used directly as a master port; Flipped(new AXI4) on the slave side.

class AXI4 extends Bundle {
  val ar = Decoupled(new AR)
  val aw = Decoupled(new AW)
  val w  = Decoupled(new W)
  val r  = Flipped(Decoupled(new R))
  val b  = Flipped(Decoupled(new B))
}
