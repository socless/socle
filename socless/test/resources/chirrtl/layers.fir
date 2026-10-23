FIRRTL version 7.0.0
circuit LayeredCounter :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|LayeredCounter",
    "group":"LayeredCounter"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  public module LayeredCounter : @[example/src/layers/LayeredCounter.scala 18:7]
    input clock : Clock @[example/src/layers/LayeredCounter.scala 18:7]
    input reset : UInt<1> @[example/src/layers/LayeredCounter.scala 18:7]
    output io : { flip en : UInt<1>, flip limit : UInt<8>, count : UInt<8>, wrap : UInt<1>} @[example/src/layers/LayeredCounter.scala 19:14]

    regreset count : UInt<8>, clock, reset, UInt<8>(0h0) @[example/src/layers/LayeredCounter.scala 26:22]
    node wrap = eq(count, io.limit) @[example/src/layers/LayeredCounter.scala 27:21]
    when io.en : @[example/src/layers/LayeredCounter.scala 29:15]
      node _count_T = add(count, UInt<1>(0h1)) @[example/src/layers/LayeredCounter.scala 30:35]
      node _count_T_1 = tail(_count_T, 1) @[example/src/layers/LayeredCounter.scala 30:35]
      node _count_T_2 = mux(wrap, UInt<1>(0h0), _count_T_1) @[example/src/layers/LayeredCounter.scala 30:17]
      connect count, _count_T_2 @[example/src/layers/LayeredCounter.scala 30:11]
    connect io.count, count @[example/src/layers/LayeredCounter.scala 33:12]
    connect io.wrap, wrap @[example/src/layers/LayeredCounter.scala 34:12]
    node _T = leq(count, io.limit) @[example/src/layers/LayeredCounter.scala 38:16]
    node _T_1 = eq(reset, UInt<1>(0h0)) @[example/src/layers/LayeredCounter.scala 38:9]
    skip
    layerblock Verification : @[example/src/layers/LayeredCounter.scala 38:9]
      layerblock Assert : @[example/src/layers/LayeredCounter.scala 38:9]
        intrinsic(circt_chisel_ifelsefatal<format = "Assertion failed: count must never exceed limit\n", label = "chisel3_builtin">, clock, _T, _T_1) @[example/src/layers/LayeredCounter.scala 38:9]
    node _T_2 = neq(io.limit, UInt<1>(0h0)) @[example/src/layers/LayeredCounter.scala 39:19]
    skip
    layerblock Verification : @[example/src/layers/LayeredCounter.scala 39:9]
      layerblock Assume : @[example/src/layers/LayeredCounter.scala 39:9]
        node _T_3 = eq(reset, UInt<1>(0h0)) @[example/src/layers/LayeredCounter.scala 39:9]
        when _T_3 : @[example/src/layers/LayeredCounter.scala 39:9]
          assume(clock, _T_2, UInt<1>(0h1), "Assumption failed: limit is configured non-zero\n") : assume @[example/src/layers/LayeredCounter.scala 39:9]
    skip
    layerblock Verification : @[example/src/layers/LayeredCounter.scala 40:8]
      layerblock Cover : @[example/src/layers/LayeredCounter.scala 40:8]
        node _T_4 = eq(reset, UInt<1>(0h0)) @[example/src/layers/LayeredCounter.scala 40:8]
        when _T_4 : @[example/src/layers/LayeredCounter.scala 40:8]
          cover(clock, wrap, UInt<1>(0h1), "") : cover @[example/src/layers/LayeredCounter.scala 40:8]

