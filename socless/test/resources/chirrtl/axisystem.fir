FIRRTL version 6.0.0
circuit AXISystem :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue8_UInt32",
    "group":"Queue8_UInt<32>"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXIMaster",
    "group":"AXIMaster"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue8_UInt32_1",
    "group":"Queue8_UInt<32>"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXIMaster_1",
    "group":"AXIMaster"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXICrossbar",
    "group":"AXICrossbar"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue4_R",
    "group":"Queue4_R"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXISlave",
    "group":"AXISlave"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue4_R_1",
    "group":"Queue4_R"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXISlave_1",
    "group":"AXISlave"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|AXISystem",
    "group":"AXISystem"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  module Queue8_UInt32 : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}, count : UInt<4>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : UInt<32>[8] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<3>, clock, reset, UInt<3>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<3>, clock, reset, UInt<3>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
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
      node wrap = eq(enq_ptr_value, UInt<3>(0h7)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<3>(0h7)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
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
    node _io_count_T_1 = mux(_io_count_T, UInt<4>(0h8), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  module AXIMaster : @[example/src/axisystem/AXISystem.scala 11:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 11:7]
    input reset : Reset @[example/src/axisystem/AXISystem.scala 11:7]
    output io : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}} @[example/src/axisystem/AXISystem.scala 12:14]

    inst txQ of Queue8_UInt32 @[example/src/axisystem/AXISystem.scala 15:21]
    connect txQ.clock, clock
    connect txQ.reset, reset
    regreset idCnt : UInt<4>, clock, reset, UInt<4>(0h0) @[example/src/axisystem/AXISystem.scala 16:22]
    connect io.ar.valid, txQ.io.deq.valid @[example/src/axisystem/AXISystem.scala 19:15]
    connect io.ar.bits.id, idCnt @[example/src/axisystem/AXISystem.scala 20:20]
    connect io.ar.bits.addr, txQ.io.deq.bits @[example/src/axisystem/AXISystem.scala 21:20]
    connect io.ar.bits.len, UInt<2>(0h3) @[example/src/axisystem/AXISystem.scala 22:20]
    connect io.ar.bits.size, UInt<2>(0h3) @[example/src/axisystem/AXISystem.scala 23:20]
    connect io.ar.bits.burst, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 24:20]
    connect io.ar.bits.cache, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 25:20]
    connect io.ar.bits.prot, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 26:20]
    connect txQ.io.deq.ready, io.ar.ready @[example/src/axisystem/AXISystem.scala 27:20]
    connect txQ.io.enq.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 30:20]
    connect txQ.io.enq.bits, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 31:20]
    connect io.r.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 34:14]
    connect io.b.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 35:14]
    connect io.aw.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 38:15]
    invalidate io.aw.bits.prot @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.cache @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.burst @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.size @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.len @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.addr @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.id @[example/src/axisystem/AXISystem.scala 39:15]
    connect io.w.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 40:15]
    invalidate io.w.bits.last @[example/src/axisystem/AXISystem.scala 41:15]
    invalidate io.w.bits.strb @[example/src/axisystem/AXISystem.scala 41:15]
    invalidate io.w.bits.data @[example/src/axisystem/AXISystem.scala 41:15]
    node _T = and(io.ar.ready, io.ar.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/axisystem/AXISystem.scala 43:21]
      node _idCnt_T = add(idCnt, UInt<1>(0h1)) @[example/src/axisystem/AXISystem.scala 43:38]
      node _idCnt_T_1 = tail(_idCnt_T, 1) @[example/src/axisystem/AXISystem.scala 43:38]
      connect idCnt, _idCnt_T_1 @[example/src/axisystem/AXISystem.scala 43:29]


  module Queue8_UInt32_1 : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}, count : UInt<4>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : UInt<32>[8] @[src/main/scala/chisel3/util/Queue.scala 73:91]
    regreset enq_ptr_value : UInt<3>, clock, reset, UInt<3>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
    regreset deq_ptr_value : UInt<3>, clock, reset, UInt<3>(0h0) @[src/main/scala/chisel3/util/Counter.scala 61:40]
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
      node wrap = eq(enq_ptr_value, UInt<3>(0h7)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
      node _value_T = add(enq_ptr_value, UInt<1>(0h1)) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      node _value_T_1 = tail(_value_T, 1) @[src/main/scala/chisel3/util/Counter.scala 77:24]
      connect enq_ptr_value, _value_T_1 @[src/main/scala/chisel3/util/Counter.scala 77:15]
    when do_deq : @[src/main/scala/chisel3/util/Queue.scala 90:16]
      node wrap_1 = eq(deq_ptr_value, UInt<3>(0h7)) @[src/main/scala/chisel3/util/Counter.scala 73:24]
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
    node _io_count_T_1 = mux(_io_count_T, UInt<4>(0h8), UInt<1>(0h0)) @[src/main/scala/chisel3/util/Queue.scala 129:20]
    node _io_count_T_2 = or(_io_count_T_1, ptr_diff) @[src/main/scala/chisel3/util/Queue.scala 129:62]
    connect io.count, _io_count_T_2 @[src/main/scala/chisel3/util/Queue.scala 129:14]


  module AXIMaster_1 : @[example/src/axisystem/AXISystem.scala 11:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 11:7]
    input reset : Reset @[example/src/axisystem/AXISystem.scala 11:7]
    output io : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}} @[example/src/axisystem/AXISystem.scala 12:14]

    inst txQ of Queue8_UInt32_1 @[example/src/axisystem/AXISystem.scala 15:21]
    connect txQ.clock, clock
    connect txQ.reset, reset
    regreset idCnt : UInt<4>, clock, reset, UInt<4>(0h0) @[example/src/axisystem/AXISystem.scala 16:22]
    connect io.ar.valid, txQ.io.deq.valid @[example/src/axisystem/AXISystem.scala 19:15]
    connect io.ar.bits.id, idCnt @[example/src/axisystem/AXISystem.scala 20:20]
    connect io.ar.bits.addr, txQ.io.deq.bits @[example/src/axisystem/AXISystem.scala 21:20]
    connect io.ar.bits.len, UInt<2>(0h3) @[example/src/axisystem/AXISystem.scala 22:20]
    connect io.ar.bits.size, UInt<2>(0h3) @[example/src/axisystem/AXISystem.scala 23:20]
    connect io.ar.bits.burst, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 24:20]
    connect io.ar.bits.cache, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 25:20]
    connect io.ar.bits.prot, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 26:20]
    connect txQ.io.deq.ready, io.ar.ready @[example/src/axisystem/AXISystem.scala 27:20]
    connect txQ.io.enq.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 30:20]
    connect txQ.io.enq.bits, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 31:20]
    connect io.r.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 34:14]
    connect io.b.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 35:14]
    connect io.aw.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 38:15]
    invalidate io.aw.bits.prot @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.cache @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.burst @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.size @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.len @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.addr @[example/src/axisystem/AXISystem.scala 39:15]
    invalidate io.aw.bits.id @[example/src/axisystem/AXISystem.scala 39:15]
    connect io.w.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 40:15]
    invalidate io.w.bits.last @[example/src/axisystem/AXISystem.scala 41:15]
    invalidate io.w.bits.strb @[example/src/axisystem/AXISystem.scala 41:15]
    invalidate io.w.bits.data @[example/src/axisystem/AXISystem.scala 41:15]
    node _T = and(io.ar.ready, io.ar.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/axisystem/AXISystem.scala 43:21]
      node _idCnt_T = add(idCnt, UInt<1>(0h1)) @[example/src/axisystem/AXISystem.scala 43:38]
      node _idCnt_T_1 = tail(_idCnt_T, 1) @[example/src/axisystem/AXISystem.scala 43:38]
      connect idCnt, _idCnt_T_1 @[example/src/axisystem/AXISystem.scala 43:29]


  module AXICrossbar : @[example/src/axisystem/AXISystem.scala 71:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 71:7]
    input reset : Reset @[example/src/axisystem/AXISystem.scala 71:7]
    output io : { flip in : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}}[2], out : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}}[2]} @[example/src/axisystem/AXISystem.scala 72:14]

    connect io.out[0], io.in[0] @[example/src/axisystem/AXISystem.scala 81:15]
    connect io.out[1], io.in[1] @[example/src/axisystem/AXISystem.scala 81:15]


  module Queue4_R : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, count : UInt<3>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}[4] @[src/main/scala/chisel3/util/Queue.scala 73:91]
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


  module AXISlave : @[example/src/axisystem/AXISystem.scala 47:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 47:7]
    input reset : Reset @[example/src/axisystem/AXISystem.scala 47:7]
    input io : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}} @[example/src/axisystem/AXISystem.scala 48:14]

    inst respQ of Queue4_R @[example/src/axisystem/AXISystem.scala 50:21]
    connect respQ.clock, clock
    connect respQ.reset, reset
    connect respQ.io.enq.valid, io.ar.valid @[example/src/axisystem/AXISystem.scala 53:26]
    connect respQ.io.enq.bits.id, io.ar.bits.id @[example/src/axisystem/AXISystem.scala 54:26]
    connect respQ.io.enq.bits.data, UInt<32>(0hdeadbeef) @[example/src/axisystem/AXISystem.scala 55:26]
    connect respQ.io.enq.bits.resp, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 56:26]
    connect respQ.io.enq.bits.last, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 57:26]
    connect io.ar.ready, respQ.io.enq.ready @[example/src/axisystem/AXISystem.scala 58:15]
    connect io.r.bits, respQ.io.deq.bits @[example/src/axisystem/AXISystem.scala 61:8]
    connect io.r.valid, respQ.io.deq.valid @[example/src/axisystem/AXISystem.scala 61:8]
    connect respQ.io.deq.ready, io.r.ready @[example/src/axisystem/AXISystem.scala 61:8]
    connect io.aw.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 64:15]
    connect io.w.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 65:15]
    connect io.b.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 66:15]
    invalidate io.b.bits.resp @[example/src/axisystem/AXISystem.scala 67:15]
    invalidate io.b.bits.id @[example/src/axisystem/AXISystem.scala 67:15]


  module Queue4_R_1 : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, count : UInt<3>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}[4] @[src/main/scala/chisel3/util/Queue.scala 73:91]
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


  module AXISlave_1 : @[example/src/axisystem/AXISystem.scala 47:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 47:7]
    input reset : Reset @[example/src/axisystem/AXISystem.scala 47:7]
    input io : { ar : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, aw : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, addr : UInt<32>, len : UInt<8>, size : UInt<3>, burst : UInt<2>, cache : UInt<4>, prot : UInt<3>}}, w : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<64>, strb : UInt<8>, last : UInt<1>}}, flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, data : UInt<64>, resp : UInt<2>, last : UInt<1>}}, flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : { id : UInt<4>, resp : UInt<2>}}} @[example/src/axisystem/AXISystem.scala 48:14]

    inst respQ of Queue4_R_1 @[example/src/axisystem/AXISystem.scala 50:21]
    connect respQ.clock, clock
    connect respQ.reset, reset
    connect respQ.io.enq.valid, io.ar.valid @[example/src/axisystem/AXISystem.scala 53:26]
    connect respQ.io.enq.bits.id, io.ar.bits.id @[example/src/axisystem/AXISystem.scala 54:26]
    connect respQ.io.enq.bits.data, UInt<32>(0hdeadbeef) @[example/src/axisystem/AXISystem.scala 55:26]
    connect respQ.io.enq.bits.resp, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 56:26]
    connect respQ.io.enq.bits.last, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 57:26]
    connect io.ar.ready, respQ.io.enq.ready @[example/src/axisystem/AXISystem.scala 58:15]
    connect io.r.bits, respQ.io.deq.bits @[example/src/axisystem/AXISystem.scala 61:8]
    connect io.r.valid, respQ.io.deq.valid @[example/src/axisystem/AXISystem.scala 61:8]
    connect respQ.io.deq.ready, io.r.ready @[example/src/axisystem/AXISystem.scala 61:8]
    connect io.aw.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 64:15]
    connect io.w.ready, UInt<1>(0h1) @[example/src/axisystem/AXISystem.scala 65:15]
    connect io.b.valid, UInt<1>(0h0) @[example/src/axisystem/AXISystem.scala 66:15]
    invalidate io.b.bits.resp @[example/src/axisystem/AXISystem.scala 67:15]
    invalidate io.b.bits.id @[example/src/axisystem/AXISystem.scala 67:15]


  public module AXISystem : @[example/src/axisystem/AXISystem.scala 102:7]
    input clock : Clock @[example/src/axisystem/AXISystem.scala 102:7]
    input reset : UInt<1> @[example/src/axisystem/AXISystem.scala 102:7]
    output io : { } @[example/src/axisystem/AXISystem.scala 103:14]

    inst cpu of AXIMaster @[example/src/axisystem/AXISystem.scala 105:22]
    connect cpu.clock, clock
    connect cpu.reset, reset
    inst dma of AXIMaster_1 @[example/src/axisystem/AXISystem.scala 106:22]
    connect dma.clock, clock
    connect dma.reset, reset
    inst xbar of AXICrossbar @[example/src/axisystem/AXISystem.scala 107:22]
    connect xbar.clock, clock
    connect xbar.reset, reset
    inst mem of AXISlave @[example/src/axisystem/AXISystem.scala 108:22]
    connect mem.clock, clock
    connect mem.reset, reset
    inst periph of AXISlave_1 @[example/src/axisystem/AXISystem.scala 109:22]
    connect periph.clock, clock
    connect periph.reset, reset
    connect xbar.io.in[0], cpu.io @[example/src/axisystem/AXISystem.scala 111:18]
    connect xbar.io.in[1], dma.io @[example/src/axisystem/AXISystem.scala 112:18]
    connect mem.io, xbar.io.out[0] @[example/src/axisystem/AXISystem.scala 113:18]
    connect periph.io, xbar.io.out[1] @[example/src/axisystem/AXISystem.scala 114:18]

