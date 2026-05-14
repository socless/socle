package rvpipeline

import chisel3._
import chisel3.util._

// ── Minimal 4-stage in-order pipeline: Fetch → Decode → Exec → WB ────────────
// Not a real RISC-V — the goal is to give the SoCLess exporter a multi-module
// hierarchy with mixed Decoupled handshakes, sub-bundles, and a memory port.

// Memory port: a request channel (master) and a response channel (slave).
class MemReq extends Bundle {
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
  val wen   = Bool()
}
class MemResp extends Bundle {
  val rdata = UInt(32.W)
}
class MemPort extends Bundle {
  val req  = Decoupled(new MemReq)
  val resp = Flipped(Decoupled(new MemResp))
}

// Stage-to-stage payload bundles.
class FetchOut extends Bundle {
  val pc   = UInt(32.W)
  val inst = UInt(32.W)
}
class DecodeOut extends Bundle {
  val pc  = UInt(32.W)
  val op  = UInt(4.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val imm = UInt(32.W)
  val rd  = UInt(5.W)
}
class ExecOut extends Bundle {
  val result = UInt(32.W)
  val rd     = UInt(5.W)
  val wen    = Bool()
}

// Register-file ports. Splitting reads and writes into separate Bundles lets
// Decode and WB connect to different sub-bundles of the same RegFile without
// fighting over a shared aggregate.
class RFRead extends Bundle {
  val raddr1 = Output(UInt(5.W))
  val raddr2 = Output(UInt(5.W))
  val rdata1 = Input(UInt(32.W))
  val rdata2 = Input(UInt(32.W))
}
class RFWrite extends Bundle {
  val waddr = Output(UInt(5.W))
  val wdata = Output(UInt(32.W))
  val wen   = Output(Bool())
}

// ── Leaf modules ─────────────────────────────────────────────────────────────

class Fetch extends Module {
  val io = IO(new Bundle {
    val imem = new MemPort
    val out  = Decoupled(new FetchOut)
  })
  val pc = RegInit(0.U(32.W))
  io.imem.req.valid     := io.out.ready
  io.imem.req.bits.addr := pc
  io.imem.req.bits.wdata:= 0.U
  io.imem.req.bits.wen  := false.B
  io.imem.resp.ready    := true.B
  io.out.valid          := io.imem.resp.valid
  io.out.bits.pc        := pc
  io.out.bits.inst      := io.imem.resp.bits.rdata
  when(io.out.fire) { pc := pc + 4.U }
}

class Decode extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new FetchOut))
    val out = Decoupled(new DecodeOut)
    val rf  = new RFRead
  })
  io.rf.raddr1 := io.in.bits.inst(19, 15)
  io.rf.raddr2 := io.in.bits.inst(24, 20)

  io.in.ready        := io.out.ready
  io.out.valid       := io.in.valid
  io.out.bits.pc     := io.in.bits.pc
  io.out.bits.op     := io.in.bits.inst(6, 3)
  io.out.bits.rs1    := io.rf.rdata1
  io.out.bits.rs2    := io.rf.rdata2
  io.out.bits.imm    := io.in.bits.inst(31, 0)   // simplified
  io.out.bits.rd     := io.in.bits.inst(11, 7)
}

class Exec extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new DecodeOut))
    val out = Decoupled(new ExecOut)
  })
  io.in.ready        := io.out.ready
  io.out.valid       := io.in.valid
  // Trivial ALU: rs1 + rs2 (real one would mux on op).
  io.out.bits.result := io.in.bits.rs1 + io.in.bits.rs2
  io.out.bits.rd     := io.in.bits.rd
  io.out.bits.wen    := io.in.bits.rd =/= 0.U
}

class WB extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new ExecOut))
    val dmem = new MemPort
    val rf   = new RFWrite
  })
  io.in.ready := true.B
  // We don't actually load/store here — just stub the dmem port.
  io.dmem.req.valid     := false.B
  io.dmem.req.bits      := DontCare
  io.dmem.resp.ready    := true.B
  io.rf.waddr := io.in.bits.rd
  io.rf.wdata := io.in.bits.result
  io.rf.wen   := io.in.valid && io.in.bits.wen
}

class RegFile extends Module {
  val io = IO(new Bundle {
    val rd = Flipped(new RFRead)
    val wr = Flipped(new RFWrite)
  })
  val regs = Mem(32, UInt(32.W))
  io.rd.rdata1 := regs(io.rd.raddr1)
  io.rd.rdata2 := regs(io.rd.raddr2)
  when(io.wr.wen && (io.wr.waddr =/= 0.U)) {
    regs(io.wr.waddr) := io.wr.wdata
  }
}

// ── Top ──────────────────────────────────────────────────────────────────────

class RVPipeline extends Module {
  val io = IO(new Bundle {
    val imem = new MemPort
    val dmem = new MemPort
  })

  val fetch  = Module(new Fetch)
  val decode = Module(new Decode)
  val exec   = Module(new Exec)
  val wb     = Module(new WB)
  val rf     = Module(new RegFile)

  io.imem        <> fetch.io.imem
  io.dmem        <> wb.io.dmem
  decode.io.in   <> fetch.io.out
  exec.io.in     <> decode.io.out
  wb.io.in       <> exec.io.out
  rf.io.rd       <> decode.io.rf
  rf.io.wr       <> wb.io.rf
}
