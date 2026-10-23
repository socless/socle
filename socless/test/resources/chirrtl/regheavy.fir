FIRRTL version 7.0.0
circuit Datapath :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Controller",
    "group":"Controller"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegStage",
    "group":"RegStage"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|RegStage_1",
    "group":"RegStage"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Queue2_PipePayload",
    "group":"Queue2_PipePayload"
  },
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Datapath",
    "group":"Datapath"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  module Controller : @[example/src/regheavy/Datapath.scala 83:7]
    input clock : Clock @[example/src/regheavy/Datapath.scala 83:7]
    input reset : Reset @[example/src/regheavy/Datapath.scala 83:7]
    output io : { enable : UInt<1>, flush : UInt<1>, stall : UInt<1>, flip hint : UInt<1>} @[example/src/regheavy/Datapath.scala 84:14]

    regreset state : UInt<2>, clock, reset, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 95:23]
    regreset timer : UInt<8>, clock, reset, UInt<8>(0h0) @[example/src/regheavy/Datapath.scala 96:23]
    node _T = asUInt(state) @[example/src/regheavy/Datapath.scala 98:17]
    node _T_1 = eq(UInt<2>(0h0), _T) @[example/src/regheavy/Datapath.scala 98:17]
    when _T_1 : @[example/src/regheavy/Datapath.scala 98:17]
      node _timer_T = add(timer, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 100:22]
      node _timer_T_1 = tail(_timer_T, 1) @[example/src/regheavy/Datapath.scala 100:22]
      connect timer, _timer_T_1 @[example/src/regheavy/Datapath.scala 100:13]
      node _T_2 = eq(timer, UInt<4>(0hf)) @[example/src/regheavy/Datapath.scala 101:18]
      node _T_3 = or(_T_2, io.hint) @[example/src/regheavy/Datapath.scala 101:27]
      when _T_3 : @[example/src/regheavy/Datapath.scala 101:39]
        connect state, UInt<1>(0h1) @[example/src/regheavy/Datapath.scala 102:15]
        connect timer, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 103:15]
    else :
      node _T_4 = asUInt(state) @[example/src/regheavy/Datapath.scala 98:17]
      node _T_5 = eq(UInt<2>(0h1), _T_4) @[example/src/regheavy/Datapath.scala 98:17]
      when _T_5 : @[example/src/regheavy/Datapath.scala 98:17]
        node _timer_T_2 = add(timer, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 107:22]
        node _timer_T_3 = tail(_timer_T_2, 1) @[example/src/regheavy/Datapath.scala 107:22]
        connect timer, _timer_T_3 @[example/src/regheavy/Datapath.scala 107:13]
        node _T_6 = eq(timer, UInt<2>(0h3)) @[example/src/regheavy/Datapath.scala 108:18]
        when _T_6 : @[example/src/regheavy/Datapath.scala 108:27]
          connect state, UInt<2>(0h2) @[example/src/regheavy/Datapath.scala 109:15]
          connect timer, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 110:15]
      else :
        node _T_7 = asUInt(state) @[example/src/regheavy/Datapath.scala 98:17]
        node _T_8 = eq(UInt<2>(0h2), _T_7) @[example/src/regheavy/Datapath.scala 98:17]
        when _T_8 : @[example/src/regheavy/Datapath.scala 98:17]
          connect state, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 114:13]
          connect timer, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 115:13]
    node _io_enable_T = eq(state, UInt<1>(0h0)) @[example/src/regheavy/Datapath.scala 119:22]
    connect io.enable, _io_enable_T @[example/src/regheavy/Datapath.scala 119:13]
    node _io_flush_T = eq(state, UInt<2>(0h2)) @[example/src/regheavy/Datapath.scala 120:22]
    connect io.flush, _io_flush_T @[example/src/regheavy/Datapath.scala 120:13]
    node _io_stall_T = eq(state, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 121:22]
    connect io.stall, _io_stall_T @[example/src/regheavy/Datapath.scala 121:13]


  module RegStage : @[example/src/regheavy/Datapath.scala 35:7]
    input clock : Clock @[example/src/regheavy/Datapath.scala 35:7]
    input reset : Reset @[example/src/regheavy/Datapath.scala 35:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, flip enable : UInt<1>, flip flush : UInt<1>, flip stall : UInt<1>} @[example/src/regheavy/Datapath.scala 36:14]

    regreset rData : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/regheavy/Datapath.scala 44:24]
    regreset rTag : UInt<8>, clock, reset, UInt<8>(0h0) @[example/src/regheavy/Datapath.scala 45:24]
    regreset rFlags : UInt<4>, clock, reset, UInt<4>(0h0) @[example/src/regheavy/Datapath.scala 46:24]
    regreset rValid : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 47:24]
    regreset rCount : UInt<16>, clock, reset, UInt<16>(0h0) @[example/src/regheavy/Datapath.scala 48:24]
    node _io_in_ready_T = eq(io.stall, UInt<1>(0h0)) @[example/src/regheavy/Datapath.scala 50:31]
    node _io_in_ready_T_1 = and(io.enable, _io_in_ready_T) @[example/src/regheavy/Datapath.scala 50:28]
    node _io_in_ready_T_2 = eq(io.flush, UInt<1>(0h0)) @[example/src/regheavy/Datapath.scala 50:44]
    node _io_in_ready_T_3 = and(_io_in_ready_T_1, _io_in_ready_T_2) @[example/src/regheavy/Datapath.scala 50:41]
    connect io.in.ready, _io_in_ready_T_3 @[example/src/regheavy/Datapath.scala 50:15]
    when io.flush : @[example/src/regheavy/Datapath.scala 52:18]
      connect rData, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 53:12]
      connect rTag, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 54:12]
      connect rFlags, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 55:12]
      connect rValid, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 56:12]
      connect rCount, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 57:12]
    else :
      when io.stall : @[example/src/regheavy/Datapath.scala 58:24]
        node _rCount_T = add(rCount, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 60:22]
        node _rCount_T_1 = tail(_rCount_T, 1) @[example/src/regheavy/Datapath.scala 60:22]
        connect rCount, _rCount_T_1 @[example/src/regheavy/Datapath.scala 60:12]
      else :
        node _T = and(io.enable, io.in.valid) @[example/src/regheavy/Datapath.scala 61:24]
        when _T : @[example/src/regheavy/Datapath.scala 61:40]
          connect rData, io.in.bits.data @[example/src/regheavy/Datapath.scala 62:12]
          connect rTag, io.in.bits.tag @[example/src/regheavy/Datapath.scala 63:12]
          connect rFlags, io.in.bits.flags @[example/src/regheavy/Datapath.scala 64:12]
          connect rValid, io.in.bits.valid @[example/src/regheavy/Datapath.scala 65:12]
          node _rCount_T_2 = add(rCount, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 66:22]
          node _rCount_T_3 = tail(_rCount_T_2, 1) @[example/src/regheavy/Datapath.scala 66:22]
          connect rCount, _rCount_T_3 @[example/src/regheavy/Datapath.scala 66:12]
        else :
          connect rValid, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 68:12]
    connect io.out.valid, rValid @[example/src/regheavy/Datapath.scala 71:22]
    connect io.out.bits.data, rData @[example/src/regheavy/Datapath.scala 72:22]
    connect io.out.bits.tag, rTag @[example/src/regheavy/Datapath.scala 73:22]
    connect io.out.bits.flags, rFlags @[example/src/regheavy/Datapath.scala 74:22]
    connect io.out.bits.valid, rValid @[example/src/regheavy/Datapath.scala 75:22]


  module RegStage_1 : @[example/src/regheavy/Datapath.scala 35:7]
    input clock : Clock @[example/src/regheavy/Datapath.scala 35:7]
    input reset : Reset @[example/src/regheavy/Datapath.scala 35:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, flip enable : UInt<1>, flip flush : UInt<1>, flip stall : UInt<1>} @[example/src/regheavy/Datapath.scala 36:14]

    regreset rData : UInt<32>, clock, reset, UInt<32>(0h0) @[example/src/regheavy/Datapath.scala 44:24]
    regreset rTag : UInt<8>, clock, reset, UInt<8>(0h0) @[example/src/regheavy/Datapath.scala 45:24]
    regreset rFlags : UInt<4>, clock, reset, UInt<4>(0h0) @[example/src/regheavy/Datapath.scala 46:24]
    regreset rValid : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 47:24]
    regreset rCount : UInt<16>, clock, reset, UInt<16>(0h0) @[example/src/regheavy/Datapath.scala 48:24]
    node _io_in_ready_T = eq(io.stall, UInt<1>(0h0)) @[example/src/regheavy/Datapath.scala 50:31]
    node _io_in_ready_T_1 = and(io.enable, _io_in_ready_T) @[example/src/regheavy/Datapath.scala 50:28]
    node _io_in_ready_T_2 = eq(io.flush, UInt<1>(0h0)) @[example/src/regheavy/Datapath.scala 50:44]
    node _io_in_ready_T_3 = and(_io_in_ready_T_1, _io_in_ready_T_2) @[example/src/regheavy/Datapath.scala 50:41]
    connect io.in.ready, _io_in_ready_T_3 @[example/src/regheavy/Datapath.scala 50:15]
    when io.flush : @[example/src/regheavy/Datapath.scala 52:18]
      connect rData, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 53:12]
      connect rTag, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 54:12]
      connect rFlags, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 55:12]
      connect rValid, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 56:12]
      connect rCount, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 57:12]
    else :
      when io.stall : @[example/src/regheavy/Datapath.scala 58:24]
        node _rCount_T = add(rCount, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 60:22]
        node _rCount_T_1 = tail(_rCount_T, 1) @[example/src/regheavy/Datapath.scala 60:22]
        connect rCount, _rCount_T_1 @[example/src/regheavy/Datapath.scala 60:12]
      else :
        node _T = and(io.enable, io.in.valid) @[example/src/regheavy/Datapath.scala 61:24]
        when _T : @[example/src/regheavy/Datapath.scala 61:40]
          connect rData, io.in.bits.data @[example/src/regheavy/Datapath.scala 62:12]
          connect rTag, io.in.bits.tag @[example/src/regheavy/Datapath.scala 63:12]
          connect rFlags, io.in.bits.flags @[example/src/regheavy/Datapath.scala 64:12]
          connect rValid, io.in.bits.valid @[example/src/regheavy/Datapath.scala 65:12]
          node _rCount_T_2 = add(rCount, UInt<1>(0h1)) @[example/src/regheavy/Datapath.scala 66:22]
          node _rCount_T_3 = tail(_rCount_T_2, 1) @[example/src/regheavy/Datapath.scala 66:22]
          connect rCount, _rCount_T_3 @[example/src/regheavy/Datapath.scala 66:12]
        else :
          connect rValid, UInt<1>(0h0) @[example/src/regheavy/Datapath.scala 68:12]
    connect io.out.valid, rValid @[example/src/regheavy/Datapath.scala 71:22]
    connect io.out.bits.data, rData @[example/src/regheavy/Datapath.scala 72:22]
    connect io.out.bits.tag, rTag @[example/src/regheavy/Datapath.scala 73:22]
    connect io.out.bits.flags, rFlags @[example/src/regheavy/Datapath.scala 74:22]
    connect io.out.bits.valid, rValid @[example/src/regheavy/Datapath.scala 75:22]


  module Queue2_PipePayload : @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input clock : Clock @[src/main/scala/chisel3/util/Queue.scala 60:7]
    input reset : Reset @[src/main/scala/chisel3/util/Queue.scala 60:7]
    output io : { flip enq : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, deq : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, count : UInt<2>} @[src/main/scala/chisel3/util/Queue.scala 72:14]

    cmem ram : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}[2] @[src/main/scala/chisel3/util/Queue.scala 73:91]
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


  public module Datapath : @[example/src/regheavy/Datapath.scala 129:7]
    input clock : Clock @[example/src/regheavy/Datapath.scala 129:7]
    input reset : UInt<1> @[example/src/regheavy/Datapath.scala 129:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { data : UInt<32>, tag : UInt<8>, flags : UInt<4>, valid : UInt<1>}}, flip hint : UInt<1>} @[example/src/regheavy/Datapath.scala 130:14]

    inst ctrl of Controller @[example/src/regheavy/Datapath.scala 136:22]
    connect ctrl.clock, clock
    connect ctrl.reset, reset
    inst stage0 of RegStage @[example/src/regheavy/Datapath.scala 137:22]
    connect stage0.clock, clock
    connect stage0.reset, reset
    inst stage1 of RegStage_1 @[example/src/regheavy/Datapath.scala 138:22]
    connect stage1.clock, clock
    connect stage1.reset, reset
    inst mid of Queue2_PipePayload @[example/src/regheavy/Datapath.scala 139:22]
    connect mid.clock, clock
    connect mid.reset, reset
    connect ctrl.io.hint, io.hint @[example/src/regheavy/Datapath.scala 141:16]
    connect stage0.io.in, io.in @[example/src/regheavy/Datapath.scala 144:20]
    connect stage0.io.enable, ctrl.io.enable @[example/src/regheavy/Datapath.scala 145:20]
    connect stage0.io.flush, ctrl.io.flush @[example/src/regheavy/Datapath.scala 146:20]
    connect stage0.io.stall, ctrl.io.stall @[example/src/regheavy/Datapath.scala 147:20]
    connect mid.io.enq, stage0.io.out @[example/src/regheavy/Datapath.scala 150:14]
    connect stage1.io.in, mid.io.deq @[example/src/regheavy/Datapath.scala 153:20]
    connect stage1.io.enable, ctrl.io.enable @[example/src/regheavy/Datapath.scala 154:20]
    connect stage1.io.flush, ctrl.io.flush @[example/src/regheavy/Datapath.scala 155:20]
    connect stage1.io.stall, ctrl.io.stall @[example/src/regheavy/Datapath.scala 156:20]
    connect io.out.bits, stage1.io.out.bits @[example/src/regheavy/Datapath.scala 158:10]
    connect io.out.valid, stage1.io.out.valid @[example/src/regheavy/Datapath.scala 158:10]
    connect stage1.io.out.ready, io.out.ready @[example/src/regheavy/Datapath.scala 158:10]

