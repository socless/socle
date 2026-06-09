package iobundles

import chisel3._
import chisel3.util._

// ── Vec-of-bundle ports + nested/mixed-direction bundles ─────────────────────
//
// Exercises:
//  - Vec(n, Flipped(Decoupled(new ...))) as top-level input
//  - Vec(n, Decoupled(new ...))          as top-level output
//  - A nested Bundle (SocPayload wrapping CtrlWord + DataWord)
//  - Mixed-direction top-level port (ack output alongside the Vec inputs)
//  - Sub-modules wired through the Vec ports

// ── Payload types ─────────────────────────────────────────────────────────────

/** Low-level data word. */
class DataWord extends Bundle {
  val data = UInt(64.W)
  val mask = UInt(8.W)
}

/** Control word with address + opcode. */
class CtrlWord extends Bundle {
  val addr   = UInt(32.W)
  val opcode = UInt(4.W)
  val last   = Bool()
}

/** Nested aggregate: a full SoC payload wraps both a CtrlWord and a DataWord.
  * This exercises the exporter's recursive bundle-field walking.
  */
class SocPayload extends Bundle {
  val ctrl = new CtrlWord
  val data = new DataWord
}

// ── Leaf: Endpoint ────────────────────────────────────────────────────────────

/** A leaf endpoint that sinks one Decoupled SocPayload and registers it.
  * `ack` is driven back as a registered strobe on a separate Output port,
  * so the top IO bundle has mixed-direction fields (Vec input + ack output).
  */
class Endpoint(id: Int) extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new SocPayload))
    val ack  = Output(Bool())
    val busy = Output(Bool())
  })

  val reg   = RegInit(0.U(64.W))
  val valid = RegInit(false.B)

  io.req.ready := !valid

  when(io.req.fire) {
    reg   := io.req.bits.data.data
    valid := true.B
  }
  when(valid) { valid := false.B }

  io.ack  := valid
  io.busy := valid
}

// ── Leaf: Aggregator ─────────────────────────────────────────────────────────

/** Aggregator merges N incoming Decoupled SocPayloads into one output stream
  * using a priority selector (lowest-index wins).
  */
class Aggregator(n: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(n, Flipped(Decoupled(new SocPayload)))
    val out = Decoupled(new SocPayload)
  })

  val valids   = VecInit(io.in.map(_.valid))
  val anyValid = valids.asUInt.orR
  val selIdx   = PriorityEncoder(valids)

  io.out.valid := anyValid
  io.out.bits  := MuxCase(io.in(0).bits,
    (0 until n).map(i => (selIdx === i.U) -> io.in(i).bits))

  for (i <- 0 until n) {
    io.in(i).ready := io.out.ready && anyValid && (selIdx === i.U)
  }
}

// ── Top: Interconnect ─────────────────────────────────────────────────────────

/** Top-level interconnect exercising Vec-of-bundle ports.
  *
  * Ports:
  *   in  : Vec(N, Flipped(Decoupled(SocPayload)))  — N input streams
  *   out : Vec(N, Decoupled(SocPayload))            — N output streams
  *   ack : Vec(N, Bool)                             — endpoint acknowledge flags
  *   busy: Vec(N, Bool)                             — endpoint busy flags
  *
  * The combination of `in` (Flipped Vec) and `ack`/`busy` (Output Vec) on the
  * same IO bundle exercises mixed-direction bundle parsing in IrWalk.
  *
  * Internal topology:
  *   in(i) → Queue → Endpoint(i)     (sinks the payload, drives ack/busy)
  *   in(i) → agg.in(i)               (also feeds the aggregator)
  *   agg.out → out(0)                 (merged output on slot 0)
  *   out(1..N-1) driven to DontCare stubs
  *
  * The socless.Launcher requires a no-arg constructor, so the parameterised
  * class is kept private; `Interconnect` is the no-arg top instantiated by
  * the Launcher.
  */
class InterconnectImpl(n: Int) extends Module {
  val io = IO(new Bundle {
    val in   = Vec(n, Flipped(Decoupled(new SocPayload)))
    val out  = Vec(n, Decoupled(new SocPayload))
    val ack  = Output(Vec(n, Bool()))
    val busy = Output(Vec(n, Bool()))
  })

  val endpoints = Seq.tabulate(n)(i => Module(new Endpoint(i)))
  val agg       = Module(new Aggregator(n))

  for (i <- 0 until n) {
    // Each input fans into its endpoint via a Queue.
    val q = Module(new Queue(new SocPayload, entries = 2))
    q.io.enq              <> io.in(i)
    endpoints(i).io.req   <> q.io.deq

    // Aggregator gets a stub (independent Queue not fed from the same stream
    // to avoid multi-driver; here we give agg a dummy zero source).
    agg.io.in(i).valid    := false.B
    agg.io.in(i).bits     := DontCare

    io.ack(i)  := endpoints(i).io.ack
    io.busy(i) := endpoints(i).io.busy
  }

  // Aggregator output → out(0)
  io.out(0) <> agg.io.out

  // Stub remaining output slots
  for (i <- 1 until n) {
    io.out(i).valid := false.B
    io.out(i).bits  := DontCare
  }
}

/** No-argument wrapper required by socless.Launcher (uses Class.newInstance). */
class Interconnect extends InterconnectImpl(n = 3)
