package regheavy

import chisel3._
import chisel3.util._

// ── Register-heavy datapath: stresses reg/when/connect CHIRRTL parsing ────────
//
// Goal: produce CHIRRTL with dense `reg`, `when`/`elsewhen`/`otherwise`, and
// `connect` statements for the IrWalk / StmtParser to chew on.
//
// Topology:
//   Datapath (top)
//     ├─ stage0 : RegStage   — 4 pipeline registers, when/elsewhen/otherwise
//     ├─ stage1 : RegStage   — same, chained
//     └─ ctrl   : Controller — generates enable/flush control signals

// ── Payload ───────────────────────────────────────────────────────────────────

/** 4-field pipeline payload. */
class PipePayload extends Bundle {
  val data  = UInt(32.W)
  val tag   = UInt(8.W)
  val flags = UInt(4.W)
  val valid = Bool()
}

// ── Leaf: RegStage ────────────────────────────────────────────────────────────

/** A single pipeline stage with four RegInit registers and rich when/elsewhen
  * control.  On `flush` all registers reset; on `enable` they latch the input;
  * otherwise they hold.  The `stall` signal ties in an extra elsewhen branch.
  *
  * Generates: `reg`, `when`, `elsewhen`, `otherwise`, `connect` in CHIRRTL.
  */
class RegStage extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new PipePayload))
    val out    = Decoupled(new PipePayload)
    val enable = Input(Bool())
    val flush  = Input(Bool())
    val stall  = Input(Bool())
  })

  val rData   = RegInit(0.U(32.W))
  val rTag    = RegInit(0.U(8.W))
  val rFlags  = RegInit(0.U(4.W))
  val rValid  = RegInit(false.B)
  val rCount  = RegInit(0.U(16.W))          // extra reg to bulk-up the design

  io.in.ready := io.enable && !io.stall && !io.flush

  when(io.flush) {
    rData  := 0.U
    rTag   := 0.U
    rFlags := 0.U
    rValid := false.B
    rCount := 0.U
  }.elsewhen(io.stall) {
    // hold — no assignment, registers keep their value
    rCount := rCount + 1.U               // stall counter still increments
  }.elsewhen(io.enable && io.in.valid) {
    rData  := io.in.bits.data
    rTag   := io.in.bits.tag
    rFlags := io.in.bits.flags
    rValid := io.in.bits.valid
    rCount := rCount + 1.U
  }.otherwise {
    rValid := false.B
  }

  io.out.valid       := rValid
  io.out.bits.data   := rData
  io.out.bits.tag    := rTag
  io.out.bits.flags  := rFlags
  io.out.bits.valid  := rValid
}

// ── Leaf: Controller ─────────────────────────────────────────────────────────

/** Generates enable/flush/stall control signals from a counter-driven FSM.
  * Three states: RUN, STALL, FLUSH — exercising `switch`/`is` in Chisel.
  */
class Controller extends Module {
  val io = IO(new Bundle {
    val enable = Output(Bool())
    val flush  = Output(Bool())
    val stall  = Output(Bool())
    val hint   = Input(Bool())           // external hint triggers flush earlier
  })

  object State extends ChiselEnum {
    val sRun, sStall, sFlush = Value
  }

  val state  = RegInit(State.sRun)
  val timer  = RegInit(0.U(8.W))

  switch(state) {
    is(State.sRun) {
      timer := timer + 1.U
      when(timer === 15.U || io.hint) {
        state := State.sStall
        timer := 0.U
      }
    }
    is(State.sStall) {
      timer := timer + 1.U
      when(timer === 3.U) {
        state := State.sFlush
        timer := 0.U
      }
    }
    is(State.sFlush) {
      state := State.sRun
      timer := 0.U
    }
  }

  io.enable := state === State.sRun
  io.flush  := state === State.sFlush
  io.stall  := state === State.sStall
}

// ── Top: Datapath ─────────────────────────────────────────────────────────────

/** Top-level datapath: two RegStage instances in series driven by a Controller.
  * A Queue decouples the stages so the exporter sees cross-instance wires.
  */
class Datapath extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new PipePayload))
    val out  = Decoupled(new PipePayload)
    val hint = Input(Bool())
  })

  val ctrl   = Module(new Controller)
  val stage0 = Module(new RegStage)
  val stage1 = Module(new RegStage)
  val mid    = Module(new Queue(new PipePayload, entries = 2))

  ctrl.io.hint := io.hint

  // Stage 0
  stage0.io.in     <> io.in
  stage0.io.enable := ctrl.io.enable
  stage0.io.flush  := ctrl.io.flush
  stage0.io.stall  := ctrl.io.stall

  // Inter-stage buffer
  mid.io.enq <> stage0.io.out

  // Stage 1
  stage1.io.in     <> mid.io.deq
  stage1.io.enable := ctrl.io.enable
  stage1.io.flush  := ctrl.io.flush
  stage1.io.stall  := ctrl.io.stall

  io.out <> stage1.io.out
}
