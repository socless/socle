FIRRTL version 7.0.0
circuit Interconnect :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Endpoint",
    "group":"Endpoint"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Endpoint_1",
    "group":"Endpoint"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Endpoint_2",
    "group":"Endpoint"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Aggregator",
    "group":"Aggregator"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue2_SocPayload",
    "group":"Queue2_SocPayload"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue2_SocPayload_1",
    "group":"Queue2_SocPayload"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue2_SocPayload_2",
    "group":"Queue2_SocPayload"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Interconnect",
    "group":"Interconnect"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  module Endpoint : @[example/src/iobundles/Interconnect.scala 44:7]
    input clock : Clock @[example/src/iobundles/Interconnect.scala 44:7]
    input reset : Reset @[example/src/iobundles/Interconnect.scala 44:7]
    output io : { flip req : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, ack : UInt<1>, busy : UInt<1>} @[example/src/iobundles/Interconnect.scala 45:14]

    regreset reg : UInt<64>, clock, reset, UInt<64>(0h0) @[example/src/iobundles/Interconnect.scala 51:22]
    regreset valid : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 52:22]
    node _io_req_ready_T = eq(valid, UInt<1>(0h0)) @[example/src/iobundles/Interconnect.scala 54:19]
    connect io.req.ready, _io_req_ready_T @[example/src/iobundles/Interconnect.scala 54:16]
    node _T = and(io.req.ready, io.req.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/iobundles/Interconnect.scala 56:21]
      connect reg, io.req.bits.data.data @[example/src/iobundles/Interconnect.scala 57:11]
      connect valid, UInt<1>(0h1) @[example/src/iobundles/Interconnect.scala 58:11]
    when valid : @[example/src/iobundles/Interconnect.scala 60:15]
      connect valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 60:23]
    connect io.ack, valid @[example/src/iobundles/Interconnect.scala 62:11]
    connect io.busy, valid @[example/src/iobundles/Interconnect.scala 63:11]


  module Endpoint_1 : @[example/src/iobundles/Interconnect.scala 44:7]
    input clock : Clock @[example/src/iobundles/Interconnect.scala 44:7]
    input reset : Reset @[example/src/iobundles/Interconnect.scala 44:7]
    output io : { flip req : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, ack : UInt<1>, busy : UInt<1>} @[example/src/iobundles/Interconnect.scala 45:14]

    regreset reg : UInt<64>, clock, reset, UInt<64>(0h0) @[example/src/iobundles/Interconnect.scala 51:22]
    regreset valid : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 52:22]
    node _io_req_ready_T = eq(valid, UInt<1>(0h0)) @[example/src/iobundles/Interconnect.scala 54:19]
    connect io.req.ready, _io_req_ready_T @[example/src/iobundles/Interconnect.scala 54:16]
    node _T = and(io.req.ready, io.req.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/iobundles/Interconnect.scala 56:21]
      connect reg, io.req.bits.data.data @[example/src/iobundles/Interconnect.scala 57:11]
      connect valid, UInt<1>(0h1) @[example/src/iobundles/Interconnect.scala 58:11]
    when valid : @[example/src/iobundles/Interconnect.scala 60:15]
      connect valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 60:23]
    connect io.ack, valid @[example/src/iobundles/Interconnect.scala 62:11]
    connect io.busy, valid @[example/src/iobundles/Interconnect.scala 63:11]


  module Endpoint_2 : @[example/src/iobundles/Interconnect.scala 44:7]
    input clock : Clock @[example/src/iobundles/Interconnect.scala 44:7]
    input reset : Reset @[example/src/iobundles/Interconnect.scala 44:7]
    output io : { flip req : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, ack : UInt<1>, busy : UInt<1>} @[example/src/iobundles/Interconnect.scala 45:14]

    regreset reg : UInt<64>, clock, reset, UInt<64>(0h0) @[example/src/iobundles/Interconnect.scala 51:22]
    regreset valid : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 52:22]
    node _io_req_ready_T = eq(valid, UInt<1>(0h0)) @[example/src/iobundles/Interconnect.scala 54:19]
    connect io.req.ready, _io_req_ready_T @[example/src/iobundles/Interconnect.scala 54:16]
    node _T = and(io.req.ready, io.req.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/iobundles/Interconnect.scala 56:21]
      connect reg, io.req.bits.data.data @[example/src/iobundles/Interconnect.scala 57:11]
      connect valid, UInt<1>(0h1) @[example/src/iobundles/Interconnect.scala 58:11]
    when valid : @[example/src/iobundles/Interconnect.scala 60:15]
      connect valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 60:23]
    connect io.ack, valid @[example/src/iobundles/Interconnect.scala 62:11]
    connect io.busy, valid @[example/src/iobundles/Interconnect.scala 63:11]


  module Aggregator : @[example/src/iobundles/Interconnect.scala 71:7]
    input clock : Clock @[example/src/iobundles/Interconnect.scala 71:7]
    input reset : Reset @[example/src/iobundles/Interconnect.scala 71:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}[3], out : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}} @[example/src/iobundles/Interconnect.scala 72:14]

    wire valids : UInt<1>[3] @[example/src/iobundles/Interconnect.scala 77:25]
    connect valids[0], io.in[0].valid @[example/src/iobundles/Interconnect.scala 77:25]
    connect valids[1], io.in[1].valid @[example/src/iobundles/Interconnect.scala 77:25]
    connect valids[2], io.in[2].valid @[example/src/iobundles/Interconnect.scala 77:25]
    node _anyValid_T = cat(valids[2], valids[1], valids[0]) @[example/src/iobundles/Interconnect.scala 78:25]
    node anyValid = orr(_anyValid_T) @[example/src/iobundles/Interconnect.scala 78:32]
    node _selIdx_T = mux(valids[1], UInt<1>(0h1), UInt<2>(0h2)) @[src/main/scala/chisel3/util/Mux.scala 58:84]
    node selIdx = mux(valids[0], UInt<1>(0h0), _selIdx_T) @[src/main/scala/chisel3/util/Mux.scala 58:84]
    connect io.out.valid, anyValid @[example/src/iobundles/Interconnect.scala 81:16]
    node _io_out_bits_T = eq(selIdx, UInt<1>(0h0)) @[example/src/iobundles/Interconnect.scala 83:34]
    node _io_out_bits_T_1 = eq(selIdx, UInt<1>(0h1)) @[example/src/iobundles/Interconnect.scala 83:34]
    node _io_out_bits_T_2 = eq(selIdx, UInt<2>(0h2)) @[example/src/iobundles/Interconnect.scala 83:34]
    node _io_out_bits_T_3 = mux(_io_out_bits_T_2, io.in[2].bits, io.in[0].bits) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_bits_T_4 = mux(_io_out_bits_T_1, io.in[1].bits, _io_out_bits_T_3) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    node _io_out_bits_T_5 = mux(_io_out_bits_T, io.in[0].bits, _io_out_bits_T_4) @[src/main/scala/chisel3/util/Mux.scala 130:16]
    connect io.out.bits, _io_out_bits_T_5 @[example/src/iobundles/Interconnect.scala 82:16]
    node _io_in_0_ready_T = and(io.out.ready, anyValid) @[example/src/iobundles/Interconnect.scala 86:36]
    node _io_in_0_ready_T_1 = eq(selIdx, UInt<1>(0h0)) @[example/src/iobundles/Interconnect.scala 86:59]
    node _io_in_0_ready_T_2 = and(_io_in_0_ready_T, _io_in_0_ready_T_1) @[example/src/iobundles/Interconnect.scala 86:48]
    connect io.in[0].ready, _io_in_0_ready_T_2 @[example/src/iobundles/Interconnect.scala 86:20]
    node _io_in_1_ready_T = and(io.out.ready, anyValid) @[example/src/iobundles/Interconnect.scala 86:36]
    node _io_in_1_ready_T_1 = eq(selIdx, UInt<1>(0h1)) @[example/src/iobundles/Interconnect.scala 86:59]
    node _io_in_1_ready_T_2 = and(_io_in_1_ready_T, _io_in_1_ready_T_1) @[example/src/iobundles/Interconnect.scala 86:48]
    connect io.in[1].ready, _io_in_1_ready_T_2 @[example/src/iobundles/Interconnect.scala 86:20]
    node _io_in_2_ready_T = and(io.out.ready, anyValid) @[example/src/iobundles/Interconnect.scala 86:36]
    node _io_in_2_ready_T_1 = eq(selIdx, UInt<2>(0h2)) @[example/src/iobundles/Interconnect.scala 86:59]
    node _io_in_2_ready_T_2 = and(_io_in_2_ready_T, _io_in_2_ready_T_1) @[example/src/iobundles/Interconnect.scala 86:48]
    connect io.in[2].ready, _io_in_2_ready_T_2 @[example/src/iobundles/Interconnect.scala 86:20]


  module Queue2_SocPayload : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, count : UInt<2>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}[2] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
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
      node wrap = eq(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
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
    node _io_count_T_1 = mux(_io_count_T, UInt<2>(0h2), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  module Queue2_SocPayload_1 : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, count : UInt<2>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}[2] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
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
      node wrap = eq(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
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
    node _io_count_T_1 = mux(_io_count_T, UInt<2>(0h2), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  module Queue2_SocPayload_2 : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}, count : UInt<2>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}[2] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<1>, clock, reset, UInt<1>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
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
      node wrap = eq(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
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
    node _io_count_T_1 = mux(_io_count_T, UInt<2>(0h2), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  public module Interconnect : @[example/src/iobundles/Interconnect.scala 150:7]
    input clock : Clock @[example/src/iobundles/Interconnect.scala 150:7]
    input reset : UInt<1> @[example/src/iobundles/Interconnect.scala 150:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}[3], out : { flip ready : UInt<1>, valid : UInt<1>, bits : { ctrl : { addr : UInt<32>, opcode : UInt<4>, last : UInt<1>}, data : { data : UInt<64>, mask : UInt<8>}}}[3], ack : UInt<1>[3], busy : UInt<1>[3]} @[example/src/iobundles/Interconnect.scala 114:14]

    inst endpoints_0 of Endpoint @[example/src/iobundles/Interconnect.scala 121:46]
    connect endpoints_0.clock, clock
    connect endpoints_0.reset, reset
    inst endpoints_1 of Endpoint_1 @[example/src/iobundles/Interconnect.scala 121:46]
    connect endpoints_1.clock, clock
    connect endpoints_1.reset, reset
    inst endpoints_2 of Endpoint_2 @[example/src/iobundles/Interconnect.scala 121:46]
    connect endpoints_2.clock, clock
    connect endpoints_2.reset, reset
    inst agg of Aggregator @[example/src/iobundles/Interconnect.scala 122:25]
    connect agg.clock, clock
    connect agg.reset, reset
    inst q of Queue2_SocPayload @[example/src/iobundles/Interconnect.scala 126:19]
    connect q.clock, clock
    connect q.reset, reset
    connect q.io.enq, io.in[0] @[example/src/iobundles/Interconnect.scala 127:27]
    connect endpoints_0.io.req, q.io.deq @[example/src/iobundles/Interconnect.scala 128:27]
    connect agg.io.in[0].valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 132:27]
    invalidate agg.io.in[0].bits.data.mask @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[0].bits.data.data @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[0].bits.ctrl.last @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[0].bits.ctrl.opcode @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[0].bits.ctrl.addr @[example/src/iobundles/Interconnect.scala 133:27]
    connect io.ack[0], endpoints_0.io.ack @[example/src/iobundles/Interconnect.scala 135:16]
    connect io.busy[0], endpoints_0.io.busy @[example/src/iobundles/Interconnect.scala 136:16]
    inst q_1 of Queue2_SocPayload_1 @[example/src/iobundles/Interconnect.scala 126:19]
    connect q_1.clock, clock
    connect q_1.reset, reset
    connect q_1.io.enq, io.in[1] @[example/src/iobundles/Interconnect.scala 127:27]
    connect endpoints_1.io.req, q_1.io.deq @[example/src/iobundles/Interconnect.scala 128:27]
    connect agg.io.in[1].valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 132:27]
    invalidate agg.io.in[1].bits.data.mask @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[1].bits.data.data @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[1].bits.ctrl.last @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[1].bits.ctrl.opcode @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[1].bits.ctrl.addr @[example/src/iobundles/Interconnect.scala 133:27]
    connect io.ack[1], endpoints_1.io.ack @[example/src/iobundles/Interconnect.scala 135:16]
    connect io.busy[1], endpoints_1.io.busy @[example/src/iobundles/Interconnect.scala 136:16]
    inst q_2 of Queue2_SocPayload_2 @[example/src/iobundles/Interconnect.scala 126:19]
    connect q_2.clock, clock
    connect q_2.reset, reset
    connect q_2.io.enq, io.in[2] @[example/src/iobundles/Interconnect.scala 127:27]
    connect endpoints_2.io.req, q_2.io.deq @[example/src/iobundles/Interconnect.scala 128:27]
    connect agg.io.in[2].valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 132:27]
    invalidate agg.io.in[2].bits.data.mask @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[2].bits.data.data @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[2].bits.ctrl.last @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[2].bits.ctrl.opcode @[example/src/iobundles/Interconnect.scala 133:27]
    invalidate agg.io.in[2].bits.ctrl.addr @[example/src/iobundles/Interconnect.scala 133:27]
    connect io.ack[2], endpoints_2.io.ack @[example/src/iobundles/Interconnect.scala 135:16]
    connect io.busy[2], endpoints_2.io.busy @[example/src/iobundles/Interconnect.scala 136:16]
    connect io.out[0].bits, agg.io.out.bits @[example/src/iobundles/Interconnect.scala 140:13]
    connect io.out[0].valid, agg.io.out.valid @[example/src/iobundles/Interconnect.scala 140:13]
    connect agg.io.out.ready, io.out[0].ready @[example/src/iobundles/Interconnect.scala 140:13]
    connect io.out[1].valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 144:21]
    invalidate io.out[1].bits.data.mask @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[1].bits.data.data @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[1].bits.ctrl.last @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[1].bits.ctrl.opcode @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[1].bits.ctrl.addr @[example/src/iobundles/Interconnect.scala 145:21]
    connect io.out[2].valid, UInt<1>(0h0) @[example/src/iobundles/Interconnect.scala 144:21]
    invalidate io.out[2].bits.data.mask @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[2].bits.data.data @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[2].bits.ctrl.last @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[2].bits.ctrl.opcode @[example/src/iobundles/Interconnect.scala 145:21]
    invalidate io.out[2].bits.ctrl.addr @[example/src/iobundles/Interconnect.scala 145:21]

