FIRRTL version 7.0.0
circuit GCD :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|GCD",
    "group":"GCD"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  public module GCD : @[example/src/gcd/GCD.scala 24:7]
    input clock : Clock @[example/src/gcd/GCD.scala 24:7]
    input reset : UInt<1> @[example/src/gcd/GCD.scala 24:7]
    output io : { flip in : { flip ready : UInt<1>, valid : UInt<1>, bits : { a : UInt<16>, b : UInt<16>}}, out : { flip ready : UInt<1>, valid : UInt<1>, bits : { result : UInt<16>}}} @[example/src/gcd/GCD.scala 25:14]

    regreset x : UInt<16>, clock, reset, UInt<16>(0h0) @[example/src/gcd/GCD.scala 30:21]
    regreset y : UInt<16>, clock, reset, UInt<16>(0h0) @[example/src/gcd/GCD.scala 31:21]
    regreset busy : UInt<1>, clock, reset, UInt<1>(0h0) @[example/src/gcd/GCD.scala 32:21]
    node _io_in_ready_T = eq(busy, UInt<1>(0h0)) @[example/src/gcd/GCD.scala 35:18]
    connect io.in.ready, _io_in_ready_T @[example/src/gcd/GCD.scala 35:15]
    node _T = and(io.in.ready, io.in.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T : @[example/src/gcd/GCD.scala 36:20]
      connect x, io.in.bits.a @[example/src/gcd/GCD.scala 37:10]
      connect y, io.in.bits.b @[example/src/gcd/GCD.scala 38:10]
      connect busy, UInt<1>(0h1) @[example/src/gcd/GCD.scala 39:10]
    node _T_1 = neq(x, y) @[example/src/gcd/GCD.scala 43:18]
    node _T_2 = and(busy, _T_1) @[example/src/gcd/GCD.scala 43:13]
    when _T_2 : @[example/src/gcd/GCD.scala 43:25]
      node _T_3 = gt(x, y) @[example/src/gcd/GCD.scala 44:12]
      when _T_3 : @[example/src/gcd/GCD.scala 44:17]
        node _x_T = sub(x, y) @[example/src/gcd/GCD.scala 44:26]
        node _x_T_1 = tail(_x_T, 1) @[example/src/gcd/GCD.scala 44:26]
        connect x, _x_T_1 @[example/src/gcd/GCD.scala 44:21]
      else :
        node _y_T = sub(y, x) @[example/src/gcd/GCD.scala 44:51]
        node _y_T_1 = tail(_y_T, 1) @[example/src/gcd/GCD.scala 44:51]
        connect y, _y_T_1 @[example/src/gcd/GCD.scala 44:46]
    node _io_out_valid_T = eq(x, y) @[example/src/gcd/GCD.scala 48:36]
    node _io_out_valid_T_1 = and(busy, _io_out_valid_T) @[example/src/gcd/GCD.scala 48:30]
    connect io.out.valid, _io_out_valid_T_1 @[example/src/gcd/GCD.scala 48:22]
    connect io.out.bits.result, x @[example/src/gcd/GCD.scala 49:22]
    node _T_4 = and(io.out.ready, io.out.valid) @[src/main/scala/chisel3/util/ReadyValidIO.scala 48:35]
    when _T_4 : @[example/src/gcd/GCD.scala 50:21]
      connect busy, UInt<1>(0h0) @[example/src/gcd/GCD.scala 50:28]

