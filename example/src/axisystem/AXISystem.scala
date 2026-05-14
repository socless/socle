package axisystem

import chisel3._
import chisel3.util._

// ── Leaf modules ─────────────────────────────────────────────────────────────
// Minimal stand-ins — enough to elaborate cleanly and exercise the exporter.
// Real implementations live elsewhere; the exporter doesn't care about logic.

/** AXI4 master. Issues a single AR per cycle when downstream is ready. */
class AXIMaster(role: String) extends Module {
  val io = IO(new AXI4)

  // Queue is a "real" Chisel module so the exporter sees it as a submodule.
  val txQ   = Module(new Queue(UInt(32.W), entries = 8))
  val idCnt = RegInit(0.U(4.W))

  // Drive AR from the queue head
  io.ar.valid := txQ.io.deq.valid
  io.ar.bits.id    := idCnt
  io.ar.bits.addr  := txQ.io.deq.bits
  io.ar.bits.len   := 3.U
  io.ar.bits.size  := 3.U
  io.ar.bits.burst := 1.U
  io.ar.bits.cache := 0.U
  io.ar.bits.prot  := 0.U
  txQ.io.deq.ready := io.ar.ready

  // Provide some dummy queue input
  txQ.io.enq.valid := false.B
  txQ.io.enq.bits  := 0.U

  // Sink responses
  io.r.ready := true.B
  io.b.ready := true.B

  // Stub the write path
  io.aw.valid := false.B
  io.aw.bits  := DontCare
  io.w.valid  := false.B
  io.w.bits   := DontCare

  when (io.ar.fire) { idCnt := idCnt + 1.U }
}

/** AXI4 slave. Echoes a constant R response after each AR. */
class AXISlave(base: BigInt, size: BigInt) extends Module {
  val io = IO(Flipped(new AXI4))

  val respQ = Module(new Queue(new R, entries = 4))

  // Forward AR → response queue
  respQ.io.enq.valid     := io.ar.valid
  respQ.io.enq.bits.id   := io.ar.bits.id
  respQ.io.enq.bits.data := 0xdead_beefL.U
  respQ.io.enq.bits.resp := 0.U
  respQ.io.enq.bits.last := true.B
  io.ar.ready := respQ.io.enq.ready

  // Pop responses onto R
  io.r <> respQ.io.deq

  // Stub the write path
  io.aw.ready := true.B
  io.w.ready  := true.B
  io.b.valid  := false.B
  io.b.bits   := DontCare
}

/** 2×2 round-robin crossbar. Routes AR/AW by address-decode, R/B by id. */
class AXICrossbar(nMasters: Int, nSlaves: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(nMasters, Flipped(new AXI4))
    val out = Vec(nSlaves,  new AXI4)
  })

  // Simplest possible crossbar: master 0 → slave 0, master 1 → slave 1.
  // (Real crossbars use arbiters + address decoders; this is just enough
  // to give the exporter realistic Connect statements to walk.)
  for (i <- 0 until math.min(nMasters, nSlaves)) {
    io.out(i) <> io.in(i)
  }

  // Tie off unmatched ports if M ≠ S
  for (i <- math.min(nMasters, nSlaves) until nMasters) {
    io.in(i).ar.ready := false.B; io.in(i).aw.ready := false.B; io.in(i).w.ready := false.B
    io.in(i).r.valid  := false.B; io.in(i).r.bits  := DontCare
    io.in(i).b.valid  := false.B; io.in(i).b.bits  := DontCare
  }
  for (i <- math.min(nMasters, nSlaves) until nSlaves) {
    io.out(i).ar.valid := false.B; io.out(i).ar.bits := DontCare
    io.out(i).aw.valid := false.B; io.out(i).aw.bits := DontCare
    io.out(i).w.valid  := false.B; io.out(i).w.bits  := DontCare
    io.out(i).r.ready  := true.B
    io.out(i).b.ready  := true.B
  }
}

// ── Top-level system ────────────────────────────────────────────────────────

/** Top-level interconnect — 2 masters, 1 crossbar, 2 slaves. */
class AXISystem extends Module {
  val io = IO(new Bundle {})

  val cpu    = Module(new AXIMaster(role = "cpu"))
  val dma    = Module(new AXIMaster(role = "dma"))
  val xbar   = Module(new AXICrossbar(nMasters = 2, nSlaves = 2))
  val mem    = Module(new AXISlave(base = 0x80000000L, size = 0x10000000L))
  val periph = Module(new AXISlave(base = 0x40000000L, size = 0x00100000L))

  xbar.io.in(0)  <> cpu.io
  xbar.io.in(1)  <> dma.io
  mem.io         <> xbar.io.out(0)
  periph.io      <> xbar.io.out(1)
}
