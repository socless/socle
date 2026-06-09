FIRRTL version 6.0.0
circuit DeepSoc :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|ALU",
    "group":"ALU"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegFile",
    "group":"RegFile"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Core",
    "group":"Core"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|ALU_1",
    "group":"ALU"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegFile_1",
    "group":"RegFile"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Core_1",
    "group":"Core"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Cluster",
    "group":"Cluster"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|ALU_2",
    "group":"ALU"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegFile_2",
    "group":"RegFile"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Core_2",
    "group":"Core"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|ALU_3",
    "group":"ALU"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegFile_3",
    "group":"RegFile"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Core_3",
    "group":"Core"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Cluster_1",
    "group":"Cluster"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue4_Token",
    "group":"Queue4_Token"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|DeepSoc",
    "group":"DeepSoc"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  module ALU : @[example/src/deephier/DeepSoc.scala 28:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 28:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 28:7]
    output io : { flip a : UInt<32>, flip b : UInt<32>, flip op : UInt<2>, out : UInt<32>, cout : UInt<1>} @[example/src/deephier/DeepSoc.scala 29:14]

    wire sum : UInt<33> @[example/src/deephier/DeepSoc.scala 37:17]
    node _sum_T = bits(io.op, 0, 0) @[example/src/deephier/DeepSoc.scala 38:19]
    node _sum_T_1 = not(io.b) @[example/src/deephier/DeepSoc.scala 39:14]
    node _sum_T_2 = add(_sum_T_1, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_3 = tail(_sum_T_2, 1) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_4 = add(io.a, _sum_T_3) @[example/src/deephier/DeepSoc.scala 39:10]
    node _sum_T_5 = add(io.a, io.b) @[example/src/deephier/DeepSoc.scala 40:10]
    node _sum_T_6 = mux(_sum_T, _sum_T_4, _sum_T_5) @[example/src/deephier/DeepSoc.scala 38:13]
    connect sum, _sum_T_6 @[example/src/deephier/DeepSoc.scala 38:7]
    node _io_out_T = eq(io.op, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 43:12]
    node _io_out_T_1 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 43:27]
    node _io_out_T_2 = eq(io.op, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 44:12]
    node _io_out_T_3 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 44:27]
    node _io_out_T_4 = eq(io.op, UInt<2>(0h2)) @[example/src/deephier/DeepSoc.scala 45:12]
    node _io_out_T_5 = and(io.a, io.b) @[example/src/deephier/DeepSoc.scala 45:30]
    node _io_out_T_6 = eq(io.op, UInt<2>(0h3)) @[example/src/deephier/DeepSoc.scala 46:12]
    node _io_out_T_7 = or(io.a, io.b) @[example/src/deephier/DeepSoc.scala 46:30]
    node _io_out_T_8 = mux(_io_out_T, _io_out_T_1, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_9 = mux(_io_out_T_2, _io_out_T_3, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_10 = mux(_io_out_T_4, _io_out_T_5, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_11 = mux(_io_out_T_6, _io_out_T_7, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_12 = or(_io_out_T_8, _io_out_T_9) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_13 = or(_io_out_T_12, _io_out_T_10) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_14 = or(_io_out_T_13, _io_out_T_11) @[example/src/deephier/DeepSoc.scala 42:19]
    connect io.out, _io_out_T_14 @[example/src/deephier/DeepSoc.scala 42:11]
    node _io_cout_T = bits(sum, 32, 32) @[example/src/deephier/DeepSoc.scala 48:17]
    connect io.cout, _io_cout_T @[example/src/deephier/DeepSoc.scala 48:11]


  module RegFile : @[example/src/deephier/DeepSoc.scala 54:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 54:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 54:7]
    output io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>} @[example/src/deephier/DeepSoc.scala 55:14]

    cmem mem : UInt<32>[16] @[example/src/deephier/DeepSoc.scala 63:16]
    infer mport io_rdata_MPORT = mem[io.raddr], clock @[example/src/deephier/DeepSoc.scala 64:18]
    connect io.rdata, io_rdata_MPORT @[example/src/deephier/DeepSoc.scala 64:12]
    when io.wen : @[example/src/deephier/DeepSoc.scala 65:16]
      infer mport MPORT = mem[io.waddr], clock @[example/src/deephier/DeepSoc.scala 65:21]
      connect MPORT, io.wdata @[example/src/deephier/DeepSoc.scala 65:32]


  module Core : @[example/src/deephier/DeepSoc.scala 73:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 73:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 73:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 74:14]

    inst alu of ALU @[example/src/deephier/DeepSoc.scala 79:19]
    connect alu.clock, clock
    connect alu.reset, reset
    inst rf of RegFile @[example/src/deephier/DeepSoc.scala 80:19]
    connect rf.clock, clock
    connect rf.reset, reset
    regreset acc : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/deephier/DeepSoc.scala 83:21]
    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 84:21]
    node _io_in_ready_T = eq(busy, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 86:18]
    connect io.in.ready, _io_in_ready_T @[example/src/deephier/DeepSoc.scala 86:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 88:20]
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 89:15]
      connect alu.io.b, io.in.bits.data @[example/src/deephier/DeepSoc.scala 90:15]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 91:15]
      connect acc, alu.io.out @[example/src/deephier/DeepSoc.scala 92:15]
      connect busy, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 93:15]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 94:17]
      connect rf.io.wdata, alu.io.out @[example/src/deephier/DeepSoc.scala 95:17]
      connect rf.io.wen, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 96:17]
    else :
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 98:17]
      connect alu.io.b, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 99:17]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 100:17]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 101:17]
      connect rf.io.wdata, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 102:17]
      connect rf.io.wen, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 103:17]
    connect rf.io.raddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 106:20]
    connect io.out.valid, busy @[example/src/deephier/DeepSoc.scala 107:20]
    connect io.out.bits.data, rf.io.rdata @[example/src/deephier/DeepSoc.scala 108:20]
    connect io.out.bits.valid, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 109:21]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 111:21]
      connect busy, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 111:28]


  module ALU_1 : @[example/src/deephier/DeepSoc.scala 28:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 28:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 28:7]
    output io : { flip a : UInt<32>, flip b : UInt<32>, flip op : UInt<2>, out : UInt<32>, cout : UInt<1>} @[example/src/deephier/DeepSoc.scala 29:14]

    wire sum : UInt<33> @[example/src/deephier/DeepSoc.scala 37:17]
    node _sum_T = bits(io.op, 0, 0) @[example/src/deephier/DeepSoc.scala 38:19]
    node _sum_T_1 = not(io.b) @[example/src/deephier/DeepSoc.scala 39:14]
    node _sum_T_2 = add(_sum_T_1, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_3 = tail(_sum_T_2, 1) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_4 = add(io.a, _sum_T_3) @[example/src/deephier/DeepSoc.scala 39:10]
    node _sum_T_5 = add(io.a, io.b) @[example/src/deephier/DeepSoc.scala 40:10]
    node _sum_T_6 = mux(_sum_T, _sum_T_4, _sum_T_5) @[example/src/deephier/DeepSoc.scala 38:13]
    connect sum, _sum_T_6 @[example/src/deephier/DeepSoc.scala 38:7]
    node _io_out_T = eq(io.op, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 43:12]
    node _io_out_T_1 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 43:27]
    node _io_out_T_2 = eq(io.op, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 44:12]
    node _io_out_T_3 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 44:27]
    node _io_out_T_4 = eq(io.op, UInt<2>(0h2)) @[example/src/deephier/DeepSoc.scala 45:12]
    node _io_out_T_5 = and(io.a, io.b) @[example/src/deephier/DeepSoc.scala 45:30]
    node _io_out_T_6 = eq(io.op, UInt<2>(0h3)) @[example/src/deephier/DeepSoc.scala 46:12]
    node _io_out_T_7 = or(io.a, io.b) @[example/src/deephier/DeepSoc.scala 46:30]
    node _io_out_T_8 = mux(_io_out_T, _io_out_T_1, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_9 = mux(_io_out_T_2, _io_out_T_3, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_10 = mux(_io_out_T_4, _io_out_T_5, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_11 = mux(_io_out_T_6, _io_out_T_7, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_12 = or(_io_out_T_8, _io_out_T_9) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_13 = or(_io_out_T_12, _io_out_T_10) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_14 = or(_io_out_T_13, _io_out_T_11) @[example/src/deephier/DeepSoc.scala 42:19]
    connect io.out, _io_out_T_14 @[example/src/deephier/DeepSoc.scala 42:11]
    node _io_cout_T = bits(sum, 32, 32) @[example/src/deephier/DeepSoc.scala 48:17]
    connect io.cout, _io_cout_T @[example/src/deephier/DeepSoc.scala 48:11]


  module RegFile_1 : @[example/src/deephier/DeepSoc.scala 54:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 54:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 54:7]
    output io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>} @[example/src/deephier/DeepSoc.scala 55:14]

    cmem mem : UInt<32>[16] @[example/src/deephier/DeepSoc.scala 63:16]
    infer mport io_rdata_MPORT = mem[io.raddr], clock @[example/src/deephier/DeepSoc.scala 64:18]
    connect io.rdata, io_rdata_MPORT @[example/src/deephier/DeepSoc.scala 64:12]
    when io.wen : @[example/src/deephier/DeepSoc.scala 65:16]
      infer mport MPORT = mem[io.waddr], clock @[example/src/deephier/DeepSoc.scala 65:21]
      connect MPORT, io.wdata @[example/src/deephier/DeepSoc.scala 65:32]


  module Core_1 : @[example/src/deephier/DeepSoc.scala 73:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 73:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 73:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 74:14]

    inst alu of ALU_1 @[example/src/deephier/DeepSoc.scala 79:19]
    connect alu.clock, clock
    connect alu.reset, reset
    inst rf of RegFile_1 @[example/src/deephier/DeepSoc.scala 80:19]
    connect rf.clock, clock
    connect rf.reset, reset
    regreset acc : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/deephier/DeepSoc.scala 83:21]
    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 84:21]
    node _io_in_ready_T = eq(busy, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 86:18]
    connect io.in.ready, _io_in_ready_T @[example/src/deephier/DeepSoc.scala 86:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 88:20]
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 89:15]
      connect alu.io.b, io.in.bits.data @[example/src/deephier/DeepSoc.scala 90:15]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 91:15]
      connect acc, alu.io.out @[example/src/deephier/DeepSoc.scala 92:15]
      connect busy, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 93:15]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 94:17]
      connect rf.io.wdata, alu.io.out @[example/src/deephier/DeepSoc.scala 95:17]
      connect rf.io.wen, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 96:17]
    else :
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 98:17]
      connect alu.io.b, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 99:17]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 100:17]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 101:17]
      connect rf.io.wdata, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 102:17]
      connect rf.io.wen, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 103:17]
    connect rf.io.raddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 106:20]
    connect io.out.valid, busy @[example/src/deephier/DeepSoc.scala 107:20]
    connect io.out.bits.data, rf.io.rdata @[example/src/deephier/DeepSoc.scala 108:20]
    connect io.out.bits.valid, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 109:21]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 111:21]
      connect busy, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 111:28]


  module Cluster : @[example/src/deephier/DeepSoc.scala 119:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 119:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 119:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 120:14]

    inst cores_0 of Core @[example/src/deephier/DeepSoc.scala 125:38]
    connect cores_0.clock, clock
    connect cores_0.reset, reset
    inst cores_1 of Core_1 @[example/src/deephier/DeepSoc.scala 125:38]
    connect cores_1.clock, clock
    connect cores_1.reset, reset
    regreset sel : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 128:20]
    node _cores_0_io_in_valid_T = eq(sel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 132:49]
    node _cores_0_io_in_valid_T_1 = and(io.in.valid, _cores_0_io_in_valid_T) @[example/src/deephier/DeepSoc.scala 132:41]
    connect cores_0.io.in.valid, _cores_0_io_in_valid_T_1 @[example/src/deephier/DeepSoc.scala 132:26]
    connect cores_0.io.in.bits.valid, io.in.bits.valid @[example/src/deephier/DeepSoc.scala 133:26]
    connect cores_0.io.in.bits.data, io.in.bits.data @[example/src/deephier/DeepSoc.scala 133:26]
    node _cores_1_io_in_valid_T = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 132:49]
    node _cores_1_io_in_valid_T_1 = and(io.in.valid, _cores_1_io_in_valid_T) @[example/src/deephier/DeepSoc.scala 132:41]
    connect cores_1.io.in.valid, _cores_1_io_in_valid_T_1 @[example/src/deephier/DeepSoc.scala 132:26]
    connect cores_1.io.in.bits.valid, io.in.bits.valid @[example/src/deephier/DeepSoc.scala 133:26]
    connect cores_1.io.in.bits.data, io.in.bits.data @[example/src/deephier/DeepSoc.scala 133:26]
    node _io_in_ready_T = eq(sel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 137:36]
    node _io_in_ready_T_1 = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 137:36]
    node _io_in_ready_T_2 = mux(_io_in_ready_T_1, cores_1.io.in.ready, cores_0.io.in.ready) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_in_ready_T_3 = mux(_io_in_ready_T, cores_0.io.in.ready, _io_in_ready_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.in.ready, _io_in_ready_T_3 @[example/src/deephier/DeepSoc.scala 136:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 139:20]
      node _sel_T = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 140:20]
      node _sel_T_1 = add(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 140:49]
      node _sel_T_2 = tail(_sel_T_1, 1) @[example/src/deephier/DeepSoc.scala 140:49]
      node _sel_T_3 = mux(_sel_T, UInt<1>(0h0), _sel_T_2) @[example/src/deephier/DeepSoc.scala 140:15]
      connect sel, _sel_T_3 @[example/src/deephier/DeepSoc.scala 140:9]
    regreset outSel : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 144:23]
    node _io_out_valid_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 146:39]
    node _io_out_valid_T_1 = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 146:39]
    node _io_out_valid_T_2 = mux(_io_out_valid_T_1, cores_1.io.out.valid, cores_0.io.out.valid) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_valid_T_3 = mux(_io_out_valid_T, cores_0.io.out.valid, _io_out_valid_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.out.valid, _io_out_valid_T_3 @[example/src/deephier/DeepSoc.scala 145:16]
    node _io_out_bits_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 148:39]
    node _io_out_bits_T_1 = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 148:39]
    node _io_out_bits_T_2 = mux(_io_out_bits_T_1, cores_1.io.out.bits, cores_0.io.out.bits) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_bits_T_3 = mux(_io_out_bits_T, cores_0.io.out.bits, _io_out_bits_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.out.bits, _io_out_bits_T_3 @[example/src/deephier/DeepSoc.scala 147:16]
    node _cores_0_io_out_ready_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 150:54]
    node _cores_0_io_out_ready_T_1 = and(io.out.ready, _cores_0_io_out_ready_T) @[example/src/deephier/DeepSoc.scala 150:43]
    connect cores_0.io.out.ready, _cores_0_io_out_ready_T_1 @[example/src/deephier/DeepSoc.scala 150:27]
    node _cores_1_io_out_ready_T = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 150:54]
    node _cores_1_io_out_ready_T_1 = and(io.out.ready, _cores_1_io_out_ready_T) @[example/src/deephier/DeepSoc.scala 150:43]
    connect cores_1.io.out.ready, _cores_1_io_out_ready_T_1 @[example/src/deephier/DeepSoc.scala 150:27]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 152:21]
      node _outSel_T = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 153:26]
      node _outSel_T_1 = add(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 153:58]
      node _outSel_T_2 = tail(_outSel_T_1, 1) @[example/src/deephier/DeepSoc.scala 153:58]
      node _outSel_T_3 = mux(_outSel_T, UInt<1>(0h0), _outSel_T_2) @[example/src/deephier/DeepSoc.scala 153:18]
      connect outSel, _outSel_T_3 @[example/src/deephier/DeepSoc.scala 153:12]


  module ALU_2 : @[example/src/deephier/DeepSoc.scala 28:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 28:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 28:7]
    output io : { flip a : UInt<32>, flip b : UInt<32>, flip op : UInt<2>, out : UInt<32>, cout : UInt<1>} @[example/src/deephier/DeepSoc.scala 29:14]

    wire sum : UInt<33> @[example/src/deephier/DeepSoc.scala 37:17]
    node _sum_T = bits(io.op, 0, 0) @[example/src/deephier/DeepSoc.scala 38:19]
    node _sum_T_1 = not(io.b) @[example/src/deephier/DeepSoc.scala 39:14]
    node _sum_T_2 = add(_sum_T_1, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_3 = tail(_sum_T_2, 1) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_4 = add(io.a, _sum_T_3) @[example/src/deephier/DeepSoc.scala 39:10]
    node _sum_T_5 = add(io.a, io.b) @[example/src/deephier/DeepSoc.scala 40:10]
    node _sum_T_6 = mux(_sum_T, _sum_T_4, _sum_T_5) @[example/src/deephier/DeepSoc.scala 38:13]
    connect sum, _sum_T_6 @[example/src/deephier/DeepSoc.scala 38:7]
    node _io_out_T = eq(io.op, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 43:12]
    node _io_out_T_1 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 43:27]
    node _io_out_T_2 = eq(io.op, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 44:12]
    node _io_out_T_3 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 44:27]
    node _io_out_T_4 = eq(io.op, UInt<2>(0h2)) @[example/src/deephier/DeepSoc.scala 45:12]
    node _io_out_T_5 = and(io.a, io.b) @[example/src/deephier/DeepSoc.scala 45:30]
    node _io_out_T_6 = eq(io.op, UInt<2>(0h3)) @[example/src/deephier/DeepSoc.scala 46:12]
    node _io_out_T_7 = or(io.a, io.b) @[example/src/deephier/DeepSoc.scala 46:30]
    node _io_out_T_8 = mux(_io_out_T, _io_out_T_1, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_9 = mux(_io_out_T_2, _io_out_T_3, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_10 = mux(_io_out_T_4, _io_out_T_5, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_11 = mux(_io_out_T_6, _io_out_T_7, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_12 = or(_io_out_T_8, _io_out_T_9) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_13 = or(_io_out_T_12, _io_out_T_10) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_14 = or(_io_out_T_13, _io_out_T_11) @[example/src/deephier/DeepSoc.scala 42:19]
    connect io.out, _io_out_T_14 @[example/src/deephier/DeepSoc.scala 42:11]
    node _io_cout_T = bits(sum, 32, 32) @[example/src/deephier/DeepSoc.scala 48:17]
    connect io.cout, _io_cout_T @[example/src/deephier/DeepSoc.scala 48:11]


  module RegFile_2 : @[example/src/deephier/DeepSoc.scala 54:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 54:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 54:7]
    output io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>} @[example/src/deephier/DeepSoc.scala 55:14]

    cmem mem : UInt<32>[16] @[example/src/deephier/DeepSoc.scala 63:16]
    infer mport io_rdata_MPORT = mem[io.raddr], clock @[example/src/deephier/DeepSoc.scala 64:18]
    connect io.rdata, io_rdata_MPORT @[example/src/deephier/DeepSoc.scala 64:12]
    when io.wen : @[example/src/deephier/DeepSoc.scala 65:16]
      infer mport MPORT = mem[io.waddr], clock @[example/src/deephier/DeepSoc.scala 65:21]
      connect MPORT, io.wdata @[example/src/deephier/DeepSoc.scala 65:32]


  module Core_2 : @[example/src/deephier/DeepSoc.scala 73:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 73:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 73:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 74:14]

    inst alu of ALU_2 @[example/src/deephier/DeepSoc.scala 79:19]
    connect alu.clock, clock
    connect alu.reset, reset
    inst rf of RegFile_2 @[example/src/deephier/DeepSoc.scala 80:19]
    connect rf.clock, clock
    connect rf.reset, reset
    regreset acc : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/deephier/DeepSoc.scala 83:21]
    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 84:21]
    node _io_in_ready_T = eq(busy, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 86:18]
    connect io.in.ready, _io_in_ready_T @[example/src/deephier/DeepSoc.scala 86:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 88:20]
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 89:15]
      connect alu.io.b, io.in.bits.data @[example/src/deephier/DeepSoc.scala 90:15]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 91:15]
      connect acc, alu.io.out @[example/src/deephier/DeepSoc.scala 92:15]
      connect busy, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 93:15]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 94:17]
      connect rf.io.wdata, alu.io.out @[example/src/deephier/DeepSoc.scala 95:17]
      connect rf.io.wen, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 96:17]
    else :
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 98:17]
      connect alu.io.b, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 99:17]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 100:17]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 101:17]
      connect rf.io.wdata, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 102:17]
      connect rf.io.wen, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 103:17]
    connect rf.io.raddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 106:20]
    connect io.out.valid, busy @[example/src/deephier/DeepSoc.scala 107:20]
    connect io.out.bits.data, rf.io.rdata @[example/src/deephier/DeepSoc.scala 108:20]
    connect io.out.bits.valid, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 109:21]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 111:21]
      connect busy, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 111:28]


  module ALU_3 : @[example/src/deephier/DeepSoc.scala 28:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 28:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 28:7]
    output io : { flip a : UInt<32>, flip b : UInt<32>, flip op : UInt<2>, out : UInt<32>, cout : UInt<1>} @[example/src/deephier/DeepSoc.scala 29:14]

    wire sum : UInt<33> @[example/src/deephier/DeepSoc.scala 37:17]
    node _sum_T = bits(io.op, 0, 0) @[example/src/deephier/DeepSoc.scala 38:19]
    node _sum_T_1 = not(io.b) @[example/src/deephier/DeepSoc.scala 39:14]
    node _sum_T_2 = add(_sum_T_1, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_3 = tail(_sum_T_2, 1) @[example/src/deephier/DeepSoc.scala 39:20]
    node _sum_T_4 = add(io.a, _sum_T_3) @[example/src/deephier/DeepSoc.scala 39:10]
    node _sum_T_5 = add(io.a, io.b) @[example/src/deephier/DeepSoc.scala 40:10]
    node _sum_T_6 = mux(_sum_T, _sum_T_4, _sum_T_5) @[example/src/deephier/DeepSoc.scala 38:13]
    connect sum, _sum_T_6 @[example/src/deephier/DeepSoc.scala 38:7]
    node _io_out_T = eq(io.op, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 43:12]
    node _io_out_T_1 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 43:27]
    node _io_out_T_2 = eq(io.op, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 44:12]
    node _io_out_T_3 = bits(sum, 31, 0) @[example/src/deephier/DeepSoc.scala 44:27]
    node _io_out_T_4 = eq(io.op, UInt<2>(0h2)) @[example/src/deephier/DeepSoc.scala 45:12]
    node _io_out_T_5 = and(io.a, io.b) @[example/src/deephier/DeepSoc.scala 45:30]
    node _io_out_T_6 = eq(io.op, UInt<2>(0h3)) @[example/src/deephier/DeepSoc.scala 46:12]
    node _io_out_T_7 = or(io.a, io.b) @[example/src/deephier/DeepSoc.scala 46:30]
    node _io_out_T_8 = mux(_io_out_T, _io_out_T_1, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_9 = mux(_io_out_T_2, _io_out_T_3, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_10 = mux(_io_out_T_4, _io_out_T_5, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_11 = mux(_io_out_T_6, _io_out_T_7, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_12 = or(_io_out_T_8, _io_out_T_9) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_13 = or(_io_out_T_12, _io_out_T_10) @[example/src/deephier/DeepSoc.scala 42:19]
    node _io_out_T_14 = or(_io_out_T_13, _io_out_T_11) @[example/src/deephier/DeepSoc.scala 42:19]
    connect io.out, _io_out_T_14 @[example/src/deephier/DeepSoc.scala 42:11]
    node _io_cout_T = bits(sum, 32, 32) @[example/src/deephier/DeepSoc.scala 48:17]
    connect io.cout, _io_cout_T @[example/src/deephier/DeepSoc.scala 48:11]


  module RegFile_3 : @[example/src/deephier/DeepSoc.scala 54:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 54:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 54:7]
    output io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>} @[example/src/deephier/DeepSoc.scala 55:14]

    cmem mem : UInt<32>[16] @[example/src/deephier/DeepSoc.scala 63:16]
    infer mport io_rdata_MPORT = mem[io.raddr], clock @[example/src/deephier/DeepSoc.scala 64:18]
    connect io.rdata, io_rdata_MPORT @[example/src/deephier/DeepSoc.scala 64:12]
    when io.wen : @[example/src/deephier/DeepSoc.scala 65:16]
      infer mport MPORT = mem[io.waddr], clock @[example/src/deephier/DeepSoc.scala 65:21]
      connect MPORT, io.wdata @[example/src/deephier/DeepSoc.scala 65:32]


  module Core_3 : @[example/src/deephier/DeepSoc.scala 73:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 73:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 73:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 74:14]

    inst alu of ALU_3 @[example/src/deephier/DeepSoc.scala 79:19]
    connect alu.clock, clock
    connect alu.reset, reset
    inst rf of RegFile_3 @[example/src/deephier/DeepSoc.scala 80:19]
    connect rf.clock, clock
    connect rf.reset, reset
    regreset acc : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/deephier/DeepSoc.scala 83:21]
    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 84:21]
    node _io_in_ready_T = eq(busy, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 86:18]
    connect io.in.ready, _io_in_ready_T @[example/src/deephier/DeepSoc.scala 86:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 88:20]
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 89:15]
      connect alu.io.b, io.in.bits.data @[example/src/deephier/DeepSoc.scala 90:15]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 91:15]
      connect acc, alu.io.out @[example/src/deephier/DeepSoc.scala 92:15]
      connect busy, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 93:15]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 94:17]
      connect rf.io.wdata, alu.io.out @[example/src/deephier/DeepSoc.scala 95:17]
      connect rf.io.wen, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 96:17]
    else :
      connect alu.io.a, acc @[example/src/deephier/DeepSoc.scala 98:17]
      connect alu.io.b, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 99:17]
      connect alu.io.op, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 100:17]
      connect rf.io.waddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 101:17]
      connect rf.io.wdata, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 102:17]
      connect rf.io.wen, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 103:17]
    connect rf.io.raddr, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 106:20]
    connect io.out.valid, busy @[example/src/deephier/DeepSoc.scala 107:20]
    connect io.out.bits.data, rf.io.rdata @[example/src/deephier/DeepSoc.scala 108:20]
    connect io.out.bits.valid, UInt<1>(0h1) @[example/src/deephier/DeepSoc.scala 109:21]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 111:21]
      connect busy, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 111:28]


  module Cluster_1 : @[example/src/deephier/DeepSoc.scala 119:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 119:7]
    input reset : Reset @[example/src/deephier/DeepSoc.scala 119:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 120:14]

    inst cores_0 of Core_2 @[example/src/deephier/DeepSoc.scala 125:38]
    connect cores_0.clock, clock
    connect cores_0.reset, reset
    inst cores_1 of Core_3 @[example/src/deephier/DeepSoc.scala 125:38]
    connect cores_1.clock, clock
    connect cores_1.reset, reset
    regreset sel : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 128:20]
    node _cores_0_io_in_valid_T = eq(sel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 132:49]
    node _cores_0_io_in_valid_T_1 = and(io.in.valid, _cores_0_io_in_valid_T) @[example/src/deephier/DeepSoc.scala 132:41]
    connect cores_0.io.in.valid, _cores_0_io_in_valid_T_1 @[example/src/deephier/DeepSoc.scala 132:26]
    connect cores_0.io.in.bits.valid, io.in.bits.valid @[example/src/deephier/DeepSoc.scala 133:26]
    connect cores_0.io.in.bits.data, io.in.bits.data @[example/src/deephier/DeepSoc.scala 133:26]
    node _cores_1_io_in_valid_T = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 132:49]
    node _cores_1_io_in_valid_T_1 = and(io.in.valid, _cores_1_io_in_valid_T) @[example/src/deephier/DeepSoc.scala 132:41]
    connect cores_1.io.in.valid, _cores_1_io_in_valid_T_1 @[example/src/deephier/DeepSoc.scala 132:26]
    connect cores_1.io.in.bits.valid, io.in.bits.valid @[example/src/deephier/DeepSoc.scala 133:26]
    connect cores_1.io.in.bits.data, io.in.bits.data @[example/src/deephier/DeepSoc.scala 133:26]
    node _io_in_ready_T = eq(sel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 137:36]
    node _io_in_ready_T_1 = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 137:36]
    node _io_in_ready_T_2 = mux(_io_in_ready_T_1, cores_1.io.in.ready, cores_0.io.in.ready) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_in_ready_T_3 = mux(_io_in_ready_T, cores_0.io.in.ready, _io_in_ready_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.in.ready, _io_in_ready_T_3 @[example/src/deephier/DeepSoc.scala 136:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/deephier/DeepSoc.scala 139:20]
      node _sel_T = eq(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 140:20]
      node _sel_T_1 = add(sel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 140:49]
      node _sel_T_2 = tail(_sel_T_1, 1) @[example/src/deephier/DeepSoc.scala 140:49]
      node _sel_T_3 = mux(_sel_T, UInt<1>(0h0), _sel_T_2) @[example/src/deephier/DeepSoc.scala 140:15]
      connect sel, _sel_T_3 @[example/src/deephier/DeepSoc.scala 140:9]
    regreset outSel : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/deephier/DeepSoc.scala 144:23]
    node _io_out_valid_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 146:39]
    node _io_out_valid_T_1 = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 146:39]
    node _io_out_valid_T_2 = mux(_io_out_valid_T_1, cores_1.io.out.valid, cores_0.io.out.valid) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_valid_T_3 = mux(_io_out_valid_T, cores_0.io.out.valid, _io_out_valid_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.out.valid, _io_out_valid_T_3 @[example/src/deephier/DeepSoc.scala 145:16]
    node _io_out_bits_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 148:39]
    node _io_out_bits_T_1 = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 148:39]
    node _io_out_bits_T_2 = mux(_io_out_bits_T_1, cores_1.io.out.bits, cores_0.io.out.bits) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_bits_T_3 = mux(_io_out_bits_T, cores_0.io.out.bits, _io_out_bits_T_2) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.out.bits, _io_out_bits_T_3 @[example/src/deephier/DeepSoc.scala 147:16]
    node _cores_0_io_out_ready_T = eq(outSel, UInt<1>(0h0)) @[example/src/deephier/DeepSoc.scala 150:54]
    node _cores_0_io_out_ready_T_1 = and(io.out.ready, _cores_0_io_out_ready_T) @[example/src/deephier/DeepSoc.scala 150:43]
    connect cores_0.io.out.ready, _cores_0_io_out_ready_T_1 @[example/src/deephier/DeepSoc.scala 150:27]
    node _cores_1_io_out_ready_T = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 150:54]
    node _cores_1_io_out_ready_T_1 = and(io.out.ready, _cores_1_io_out_ready_T) @[example/src/deephier/DeepSoc.scala 150:43]
    connect cores_1.io.out.ready, _cores_1_io_out_ready_T_1 @[example/src/deephier/DeepSoc.scala 150:27]
    node _T_1 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_1 : @[example/src/deephier/DeepSoc.scala 152:21]
      node _outSel_T = eq(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 153:26]
      node _outSel_T_1 = add(outSel, UInt<1>(0h1)) @[example/src/deephier/DeepSoc.scala 153:58]
      node _outSel_T_2 = tail(_outSel_T_1, 1) @[example/src/deephier/DeepSoc.scala 153:58]
      node _outSel_T_3 = mux(_outSel_T, UInt<1>(0h0), _outSel_T_2) @[example/src/deephier/DeepSoc.scala 153:18]
      connect outSel, _outSel_T_3 @[example/src/deephier/DeepSoc.scala 153:12]


  module Queue4_Token : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, count : UInt<3>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { data : UInt<32>, valid : UInt<1>}[4] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<2>, clock, reset, UInt<2>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<2>, clock, reset, UInt<2>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset maybe_full : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Queue.scala 76:27]
    node ptr_match = eq(enq_ptr_value, deq_ptr_value) @[src/main/scala/chisel3/util/Queue.scala 77:33]
    node _empty_T = eq(maybe_full, UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 78:28]
    node empty = and(ptr_match, _empty_T) @[src/main/scala/chisel3/util/Queue.scala 78:25]
    node full = and(ptr_match, maybe_full) @[src/main/scala/chisel3/util/Queue.scala 79:24]
    node _do_enq_T = and(io.enq.ready, io.enq.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    wire do_enq : UInt<1> @[src/main/scala/chisel3/util/Queue.scala 80:27]
    connect do_enq, _do_enq_T @[src/main/scala/chisel3/util/Queue.scala 80:27]
    node _do_deq_T = and(io.deq.ready, io.deq.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    wire do_deq : UInt<1> @[src/main/scala/chisel3/util/Queue.scala 81:27]
    connect do_deq, _do_deq_T @[src/main/scala/chisel3/util/Queue.scala 81:27]
    when do_enq : @[src/main/scala/chisel3/util/Queue.scala 86:16]
      infer mport MPORT = ram[enq_ptr_value], clock @[src/main/scala/chisel3/util/Queue.scala 87:8]
      connect MPORT, io.enq.bits @[src/main/scala/chisel3/util/Queue.scala 87:24]
      node wrap = eq(enq_ptr_value, UInt<2>(0h3)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<2>(0h3)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T_2 = add(deq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_3 = tail(_value_T_2, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect deq_ptr_value, _value_T_3 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    node _T = neq(do_enq, do_deq) @[src/main/scala/chisel3/util/Queue.scala 93:15]
    when _T : @[src/main/scala/chisel3/util/Queue.scala 93:27]
      connect maybe_full, do_enq @[src/main/scala/chisel3/util/Queue.scala 94:16]
    when UInt<1>(0h0) : @[src/main/scala/chisel3/util/Queue.scala 96:15]
      connect enq_ptr_value, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 98:11]
      connect deq_ptr_value, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 98:11]
      connect maybe_full, UInt<1>(0h0) @[src/main/scala/chisel3/util/Queue.scala 99:16]
    node _io_deq_valid_T = eq(empty, UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 102:19]
    connect io.deq.valid, _io_deq_valid_T @[src/main/scala/chisel3/util/Queue.scala 102:16]
    node _io_enq_ready_T = eq(full, UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 103:19]
    connect io.enq.ready, _io_enq_ready_T @[src/main/scala/chisel3/util/Queue.scala 103:16]
    infer mport io_deq_bits_MPORT = ram[deq_ptr_value], clock @[src/main/scala/chisel3/util/Queue.scala 110:23]
    connect io.deq.bits, io_deq_bits_MPORT @[src/main/scala/chisel3/util/Queue.scala 110:17]
    node _ptr_diff_T = sub(enq_ptr_value, deq_ptr_value) @[src/main/scala/chisel3/util/Queue.scala 126:32]
    node ptr_diff = tail(_ptr_diff_T, 1) @[src/main/scala/chisel3/util/Queue.scala 126:32]
    node _io_count_T = and(maybe_full, ptr_match) @[src/main/scala/chisel3/util/Queue.scala 129:32]
    node _io_count_T_1 = mux(_io_count_T, UInt<3>(0h4), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  public module DeepSoc : @[example/src/deephier/DeepSoc.scala 162:7]
    input clock : Clock @[example/src/deephier/DeepSoc.scala 162:7]
    input reset : UInt<1> @[example/src/deephier/DeepSoc.scala 162:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, valid : UInt<1>}}} @[example/src/deephier/DeepSoc.scala 163:14]

    inst cluster0 of Cluster @[example/src/deephier/DeepSoc.scala 168:24]
    connect cluster0.clock, clock
    connect cluster0.reset, reset
    inst cluster1 of Cluster_1 @[example/src/deephier/DeepSoc.scala 169:24]
    connect cluster1.clock, clock
    connect cluster1.reset, reset
    inst pipe of Queue4_Token @[example/src/deephier/DeepSoc.scala 172:20]
    connect pipe.clock, clock
    connect pipe.reset, reset
    connect cluster0.io.in, io.in @[example/src/deephier/DeepSoc.scala 174:19]
    connect pipe.io.enq, cluster0.io.out @[example/src/deephier/DeepSoc.scala 175:19]
    connect cluster1.io.in, pipe.io.deq @[example/src/deephier/DeepSoc.scala 176:19]
    connect io.out.bits, cluster1.io.out.bits @[example/src/deephier/DeepSoc.scala 177:19]
    connect io.out.valid, cluster1.io.out.valid @[example/src/deephier/DeepSoc.scala 177:19]
    connect cluster1.io.out.ready, io.out.ready @[example/src/deephier/DeepSoc.scala 177:19]

