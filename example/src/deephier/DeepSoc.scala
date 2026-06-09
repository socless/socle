package deephier

import chisel3._
import chisel3.util._

// ── Deep hierarchy: DeepSoc > Cluster > Vec[Core] > (ALU, RegFile) ──────────
//
// Level 0 (top)  : DeepSoc        — 2 × Cluster, Decoupled in/out
// Level 1        : Cluster        — Vec(2, Core), routes Decoupled slices
// Level 2        : Core           — ALU + RegFile leaf; one Decoupled in/out
// Level 3 (leaf) : ALU            — combinational op on 32-bit operands
// Level 3 (leaf) : RegFile        — 16-entry 32-bit register file
//
// Inter-instance wires appear at every level so the exporter can exercise
// connection-walking at >= 3 nesting depths.

// ── Shared payload ────────────────────────────────────────────────────────────

/** Generic 32-bit data token flowing through the pipeline. */
class Token extends Bundle {
  val data  = UInt(32.W)
  val valid = Bool()
}

// ── Leaf: ALU ─────────────────────────────────────────────────────────────────

/** Combinational ALU: adds two 32-bit operands; carry-out flag on the side. */
class ALU extends Module {
  val io = IO(new Bundle {
    val a    = Input(UInt(32.W))
    val b    = Input(UInt(32.W))
    val op   = Input(UInt(2.W))          // 00=add, 01=sub, 10=and, 11=or
    val out  = Output(UInt(32.W))
    val cout = Output(Bool())
  })

  val sum = Wire(UInt(33.W))
  sum := Mux(io.op(0),
    io.a +& (~io.b + 1.U),              // sub via adder
    io.a +& io.b)

  io.out  := Mux1H(Seq(
    (io.op === 0.U) -> sum(31, 0),
    (io.op === 1.U) -> sum(31, 0),
    (io.op === 2.U) -> (io.a & io.b),
    (io.op === 3.U) -> (io.a | io.b),
  ))
  io.cout := sum(32)
}

// ── Leaf: RegFile ─────────────────────────────────────────────────────────────

/** 16-entry × 32-bit register file with one read port and one write port. */
class RegFile extends Module {
  val io = IO(new Bundle {
    val raddr = Input(UInt(4.W))
    val rdata = Output(UInt(32.W))
    val waddr = Input(UInt(4.W))
    val wdata = Input(UInt(32.W))
    val wen   = Input(Bool())
  })

  val mem = Mem(16, UInt(32.W))
  io.rdata := mem(io.raddr)
  when(io.wen) { mem(io.waddr) := io.wdata }
}

// ── Level 2: Core ─────────────────────────────────────────────────────────────

/** A tiny core: receives a Token, ALU-processes it, writes result to RegFile,
  * emits a Token downstream.  Contains an ALU and a RegFile as sub-instances.
  */
class Core extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Token))
    val out = Decoupled(new Token)
  })

  val alu = Module(new ALU)
  val rf  = Module(new RegFile)

  // Accumulator register: adds each incoming data word to a running total.
  val acc  = RegInit(0.U(32.W))
  val busy = RegInit(false.B)

  io.in.ready := !busy

  when(io.in.fire) {
    alu.io.a  := acc
    alu.io.b  := io.in.bits.data
    alu.io.op := 0.U                     // add
    acc       := alu.io.out
    busy      := true.B
    rf.io.waddr := 0.U
    rf.io.wdata := alu.io.out
    rf.io.wen   := true.B
  }.otherwise {
    alu.io.a    := acc
    alu.io.b    := 0.U
    alu.io.op   := 0.U
    rf.io.waddr := 0.U
    rf.io.wdata := 0.U
    rf.io.wen   := false.B
  }

  rf.io.raddr      := 0.U
  io.out.valid     := busy
  io.out.bits.data := rf.io.rdata
  io.out.bits.valid := true.B

  when(io.out.fire) { busy := false.B }
}

// ── Level 1: Cluster ─────────────────────────────────────────────────────────

/** A cluster contains a Vec of 2 Cores; it splits the incoming stream across
  * them in round-robin and merges their outputs onto a single output channel.
  */
class Cluster(nCores: Int = 2) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Token))
    val out = Decoupled(new Token)
  })

  val cores = Seq.fill(nCores)(Module(new Core))

  // Round-robin selector register.
  val sel = RegInit(0.U(log2Ceil(nCores).W))

  // Wire each core's input — only the selected core gets valid.
  for (i <- 0 until nCores) {
    cores(i).io.in.valid := io.in.valid && (sel === i.U)
    cores(i).io.in.bits  := io.in.bits
  }
  // Mux over Seq-indexed Scala values to pick the selected core's ready.
  io.in.ready := MuxCase(cores(0).io.in.ready,
    (0 until nCores).map(i => (sel === i.U) -> cores(i).io.in.ready))

  when(io.in.fire) {
    sel := Mux(sel === (nCores - 1).U, 0.U, sel + 1.U)
  }

  // Output: round-robin among whichever cores are valid.
  val outSel = RegInit(0.U(log2Ceil(nCores).W))
  io.out.valid := MuxCase(cores(0).io.out.valid,
    (0 until nCores).map(i => (outSel === i.U) -> cores(i).io.out.valid))
  io.out.bits  := MuxCase(cores(0).io.out.bits,
    (0 until nCores).map(i => (outSel === i.U) -> cores(i).io.out.bits))
  for (i <- 0 until nCores) {
    cores(i).io.out.ready := io.out.ready && (outSel === i.U)
  }
  when(io.out.fire) {
    outSel := Mux(outSel === (nCores - 1).U, 0.U, outSel + 1.U)
  }
}

// ── Level 0: DeepSoc (top) ────────────────────────────────────────────────────

/** Top-level SoC: 2 Clusters in series.  Data flows in → cluster0 → cluster1
  * → out, giving a 4-level deep module hierarchy exercising the full pipeline.
  */
class DeepSoc extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Token))
    val out = Decoupled(new Token)
  })

  val cluster0 = Module(new Cluster(nCores = 2))
  val cluster1 = Module(new Cluster(nCores = 2))

  // Pipe: top-in → cluster0 → inter-cluster FIFO → cluster1 → top-out
  val pipe = Module(new Queue(new Token, entries = 4))

  cluster0.io.in  <> io.in
  pipe.io.enq     <> cluster0.io.out
  cluster1.io.in  <> pipe.io.deq
  io.out          <> cluster1.io.out
}
