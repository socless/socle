FIRRTL version 6.0.0
circuit RVPipeline :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Fetch",
    "group":"Fetch"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Decode",
    "group":"Decode"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Exec",
    "group":"Exec"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|WB",
    "group":"WB"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegFile",
    "group":"RegFile"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RVPipeline",
    "group":"RVPipeline"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  module Fetch : @[example/src/rvpipeline/RVPipeline.scala 60:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 60:7]
    input reset : Reset @[example/src/rvpipeline/RVPipeline.scala 60:7]
    output io : { imem : { req : { flip ready : UInt<1>, valid : UInt<1>, bits : { addr : UInt<32>, wdata : UInt<32>, wen : UInt<1>}}, flip resp : { flip ready : UInt<1>, valid : UInt<1>, bits : { rdata : UInt<32>}}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { pc : UInt<32>, inst : UInt<32>}}} @[example/src/rvpipeline/RVPipeline.scala 61:14]

    regreset pc : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/rvpipeline/RVPipeline.scala 65:19]
    connect io.imem.req.valid, io.out.ready @[example/src/rvpipeline/RVPipeline.scala 66:25]
    connect io.imem.req.bits.addr, pc @[example/src/rvpipeline/RVPipeline.scala 67:25]
    connect io.imem.req.bits.wdata, UInt<1>(0h0) @[example/src/rvpipeline/RVPipeline.scala 68:25]
    connect io.imem.req.bits.wen, UInt<1>(0h0) @[example/src/rvpipeline/RVPipeline.scala 69:25]
    connect io.imem.resp.ready, UInt<1>(0h1) @[example/src/rvpipeline/RVPipeline.scala 70:25]
    connect io.out.valid, io.imem.resp.valid @[example/src/rvpipeline/RVPipeline.scala 71:25]
    connect io.out.bits.pc, pc @[example/src/rvpipeline/RVPipeline.scala 72:25]
    connect io.out.bits.inst, io.imem.resp.bits.rdata @[example/src/rvpipeline/RVPipeline.scala 73:25]
    node _T = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/rvpipeline/RVPipeline.scala 74:21]
      node _pc_T = add(pc, UInt<3>(0h4)) @[example/src/rvpipeline/RVPipeline.scala 74:32]
      node _pc_T_1 = tail(_pc_T, 1) @[example/src/rvpipeline/RVPipeline.scala 74:32]
      connect pc, _pc_T_1 @[example/src/rvpipeline/RVPipeline.scala 74:26]


  module Decode : @[example/src/rvpipeline/RVPipeline.scala 77:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 77:7]
    input reset : Reset @[example/src/rvpipeline/RVPipeline.scala 77:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { pc : UInt<32>, inst : UInt<32>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { pc : UInt<32>, op : UInt<4>, rs1 : UInt<32>, rs2 : UInt<32>, imm : UInt<32>, rd : UInt<5>}}, rf : { raddr1 : UInt<5>, raddr2 : UInt<5>, flip rdata1 : UInt<32>, flip rdata2 : UInt<32>}} @[example/src/rvpipeline/RVPipeline.scala 78:14]

    node _io_rf_raddr1_T = bits(io.in.bits.inst, 19, 15) @[example/src/rvpipeline/RVPipeline.scala 83:34]
    connect io.rf.raddr1, _io_rf_raddr1_T @[example/src/rvpipeline/RVPipeline.scala 83:16]
    node _io_rf_raddr2_T = bits(io.in.bits.inst, 24, 20) @[example/src/rvpipeline/RVPipeline.scala 84:34]
    connect io.rf.raddr2, _io_rf_raddr2_T @[example/src/rvpipeline/RVPipeline.scala 84:16]
    connect io.in.ready, io.out.ready @[example/src/rvpipeline/RVPipeline.scala 86:22]
    connect io.out.valid, io.in.valid @[example/src/rvpipeline/RVPipeline.scala 87:22]
    connect io.out.bits.pc, io.in.bits.pc @[example/src/rvpipeline/RVPipeline.scala 88:22]
    node _io_out_bits_op_T = bits(io.in.bits.inst, 6, 3) @[example/src/rvpipeline/RVPipeline.scala 89:40]
    connect io.out.bits.op, _io_out_bits_op_T @[example/src/rvpipeline/RVPipeline.scala 89:22]
    connect io.out.bits.rs1, io.rf.rdata1 @[example/src/rvpipeline/RVPipeline.scala 90:22]
    connect io.out.bits.rs2, io.rf.rdata2 @[example/src/rvpipeline/RVPipeline.scala 91:22]
    node _io_out_bits_imm_T = bits(io.in.bits.inst, 31, 0) @[example/src/rvpipeline/RVPipeline.scala 92:40]
    connect io.out.bits.imm, _io_out_bits_imm_T @[example/src/rvpipeline/RVPipeline.scala 92:22]
    node _io_out_bits_rd_T = bits(io.in.bits.inst, 11, 7) @[example/src/rvpipeline/RVPipeline.scala 93:40]
    connect io.out.bits.rd, _io_out_bits_rd_T @[example/src/rvpipeline/RVPipeline.scala 93:22]


  module Exec : @[example/src/rvpipeline/RVPipeline.scala 96:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 96:7]
    input reset : Reset @[example/src/rvpipeline/RVPipeline.scala 96:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { pc : UInt<32>, op : UInt<4>, rs1 : UInt<32>, rs2 : UInt<32>, imm : UInt<32>, rd : UInt<5>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { result : UInt<32>, rd : UInt<5>, wen : UInt<1>}}} @[example/src/rvpipeline/RVPipeline.scala 97:14]

    connect io.in.ready, io.out.ready @[example/src/rvpipeline/RVPipeline.scala 101:22]
    connect io.out.valid, io.in.valid @[example/src/rvpipeline/RVPipeline.scala 102:22]
    node _io_out_bits_result_T = add(io.in.bits.rs1, io.in.bits.rs2) @[example/src/rvpipeline/RVPipeline.scala 104:40]
    node _io_out_bits_result_T_1 = tail(_io_out_bits_result_T, 1) @[example/src/rvpipeline/RVPipeline.scala 104:40]
    connect io.out.bits.result, _io_out_bits_result_T_1 @[example/src/rvpipeline/RVPipeline.scala 104:22]
    connect io.out.bits.rd, io.in.bits.rd @[example/src/rvpipeline/RVPipeline.scala 105:22]
    node _io_out_bits_wen_T = neq(io.in.bits.rd, UInt<1>(0h0)) @[example/src/rvpipeline/RVPipeline.scala 106:39]
    connect io.out.bits.wen, _io_out_bits_wen_T @[example/src/rvpipeline/RVPipeline.scala 106:22]


  module WB : @[example/src/rvpipeline/RVPipeline.scala 109:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 109:7]
    input reset : Reset @[example/src/rvpipeline/RVPipeline.scala 109:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { result : UInt<32>, rd : UInt<5>, wen : UInt<1>}}, dmem : { req : { flip ready : UInt<1>, valid : UInt<1>, bits : { addr : UInt<32>, wdata : UInt<32>, wen : UInt<1>}}, flip resp : { flip ready : UInt<1>, valid : UInt<1>, bits : { rdata : UInt<32>}}}, rf : { waddr : UInt<5>, wdata : UInt<32>, wen : UInt<1>}} @[example/src/rvpipeline/RVPipeline.scala 110:14]

    connect io.in.ready, UInt<1>(0h1) @[example/src/rvpipeline/RVPipeline.scala 115:15]
    connect io.dmem.req.valid, UInt<1>(0h0) @[example/src/rvpipeline/RVPipeline.scala 117:25]
    invalidate io.dmem.req.bits.wen @[example/src/rvpipeline/RVPipeline.scala 118:25]
    invalidate io.dmem.req.bits.wdata @[example/src/rvpipeline/RVPipeline.scala 118:25]
    invalidate io.dmem.req.bits.addr @[example/src/rvpipeline/RVPipeline.scala 118:25]
    connect io.dmem.resp.ready, UInt<1>(0h1) @[example/src/rvpipeline/RVPipeline.scala 119:25]
    connect io.rf.waddr, io.in.bits.rd @[example/src/rvpipeline/RVPipeline.scala 120:15]
    connect io.rf.wdata, io.in.bits.result @[example/src/rvpipeline/RVPipeline.scala 121:15]
    node _io_rf_wen_T = and(io.in.valid, io.in.bits.wen) @[example/src/rvpipeline/RVPipeline.scala 122:30]
    connect io.rf.wen, _io_rf_wen_T @[example/src/rvpipeline/RVPipeline.scala 122:15]


  module RegFile : @[example/src/rvpipeline/RVPipeline.scala 125:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 125:7]
    input reset : Reset @[example/src/rvpipeline/RVPipeline.scala 125:7]
    output io : { flip rd : { raddr1 : UInt<5>, raddr2 : UInt<5>, flip rdata1 : UInt<32>, flip rdata2 : UInt<32>}, flip wr : { waddr : UInt<5>, wdata : UInt<32>, wen : UInt<1>}} @[example/src/rvpipeline/RVPipeline.scala 126:14]

    cmem regs : UInt<32>[32] @[example/src/rvpipeline/RVPipeline.scala 130:17]
    infer mport io_rd_rdata1_MPORT = regs[io.rd.raddr1], clock @[example/src/rvpipeline/RVPipeline.scala 131:23]
    connect io.rd.rdata1, io_rd_rdata1_MPORT @[example/src/rvpipeline/RVPipeline.scala 131:16]
    infer mport io_rd_rdata2_MPORT = regs[io.rd.raddr2], clock @[example/src/rvpipeline/RVPipeline.scala 132:23]
    connect io.rd.rdata2, io_rd_rdata2_MPORT @[example/src/rvpipeline/RVPipeline.scala 132:16]
    node _T = neq(io.wr.waddr, UInt<1>(0h0)) @[example/src/rvpipeline/RVPipeline.scala 133:34]
    node _T_1 = and(io.wr.wen, _T) @[example/src/rvpipeline/RVPipeline.scala 133:18]
    when _T_1 : @[example/src/rvpipeline/RVPipeline.scala 133:44]
      infer mport MPORT = regs[io.wr.waddr], clock @[example/src/rvpipeline/RVPipeline.scala 134:9]
      connect MPORT, io.wr.wdata @[example/src/rvpipeline/RVPipeline.scala 134:23]


  public module RVPipeline : @[example/src/rvpipeline/RVPipeline.scala 140:7]
    input clock : Clock @[example/src/rvpipeline/RVPipeline.scala 140:7]
    input reset : UInt<1> @[example/src/rvpipeline/RVPipeline.scala 140:7]
    output io : { imem : { req : { flip ready : UInt<1>, valid : UInt<1>, bits : { addr : UInt<32>, wdata : UInt<32>, wen : UInt<1>}}, flip resp : { flip ready : UInt<1>, valid : UInt<1>, bits : { rdata : UInt<32>}}}, dmem : { req : { flip ready : UInt<1>, valid : UInt<1>, bits : { addr : UInt<32>, wdata : UInt<32>, wen : UInt<1>}}, flip resp : { flip ready : UInt<1>, valid : UInt<1>, bits : { rdata : UInt<32>}}}} @[example/src/rvpipeline/RVPipeline.scala 141:14]

    inst fetch of Fetch @[example/src/rvpipeline/RVPipeline.scala 146:22]
    connect fetch.clock, clock
    connect fetch.reset, reset
    inst decode of Decode @[example/src/rvpipeline/RVPipeline.scala 147:22]
    connect decode.clock, clock
    connect decode.reset, reset
    inst exec of Exec @[example/src/rvpipeline/RVPipeline.scala 148:22]
    connect exec.clock, clock
    connect exec.reset, reset
    inst wb of WB @[example/src/rvpipeline/RVPipeline.scala 149:22]
    connect wb.clock, clock
    connect wb.reset, reset
    inst rf of RegFile @[example/src/rvpipeline/RVPipeline.scala 150:22]
    connect rf.clock, clock
    connect rf.reset, reset
    connect fetch.io.imem.resp, io.imem.resp @[example/src/rvpipeline/RVPipeline.scala 152:18]
    connect io.imem.req.bits, fetch.io.imem.req.bits @[example/src/rvpipeline/RVPipeline.scala 152:18]
    connect io.imem.req.valid, fetch.io.imem.req.valid @[example/src/rvpipeline/RVPipeline.scala 152:18]
    connect fetch.io.imem.req.ready, io.imem.req.ready @[example/src/rvpipeline/RVPipeline.scala 152:18]
    connect wb.io.dmem.resp, io.dmem.resp @[example/src/rvpipeline/RVPipeline.scala 153:18]
    connect io.dmem.req.bits, wb.io.dmem.req.bits @[example/src/rvpipeline/RVPipeline.scala 153:18]
    connect io.dmem.req.valid, wb.io.dmem.req.valid @[example/src/rvpipeline/RVPipeline.scala 153:18]
    connect wb.io.dmem.req.ready, io.dmem.req.ready @[example/src/rvpipeline/RVPipeline.scala 153:18]
    connect decode.io.in, fetch.io.out @[example/src/rvpipeline/RVPipeline.scala 154:18]
    connect exec.io.in, decode.io.out @[example/src/rvpipeline/RVPipeline.scala 155:18]
    connect wb.io.in, exec.io.out @[example/src/rvpipeline/RVPipeline.scala 156:18]
    connect rf.io.rd, decode.io.rf @[example/src/rvpipeline/RVPipeline.scala 157:18]
    connect rf.io.wr, wb.io.rf @[example/src/rvpipeline/RVPipeline.scala 158:18]

