FIRRTL version 7.0.0
circuit TrafficFsm :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|TrafficFsm",
    "group":"TrafficFsm"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  public module TrafficFsm : @[example/src/fsm/TrafficFsm.scala 8:7]
    input clock : Clock @[example/src/fsm/TrafficFsm.scala 8:7]
    input reset : UInt<1> @[example/src/fsm/TrafficFsm.scala 8:7]
    output io : { flip tick : UInt<1>, green : UInt<1>} @[example/src/fsm/TrafficFsm.scala 9:14]

    regreset state : UInt<2>, clock, reset, UInt<2>(0h0) @[example/src/fsm/TrafficFsm.scala 14:22]
    node _T = eq(UInt<2>(0h0), state) @[example/src/fsm/TrafficFsm.scala 15:17]
    when _T : @[example/src/fsm/TrafficFsm.scala 15:17]
      when io.tick : @[example/src/fsm/TrafficFsm.scala 16:33]
        connect state, UInt<2>(0h1) @[example/src/fsm/TrafficFsm.scala 16:41]
    else :
      node _T_1 = eq(UInt<2>(0h1), state) @[example/src/fsm/TrafficFsm.scala 15:17]
      when _T_1 : @[example/src/fsm/TrafficFsm.scala 15:17]
        when io.tick : @[example/src/fsm/TrafficFsm.scala 17:33]
          connect state, UInt<2>(0h2) @[example/src/fsm/TrafficFsm.scala 17:41]
      else :
        node _T_2 = eq(UInt<2>(0h2), state) @[example/src/fsm/TrafficFsm.scala 15:17]
        when _T_2 : @[example/src/fsm/TrafficFsm.scala 15:17]
          when io.tick : @[example/src/fsm/TrafficFsm.scala 18:33]
            connect state, UInt<2>(0h0) @[example/src/fsm/TrafficFsm.scala 18:41]
    node _io_green_T = eq(state, UInt<2>(0h1)) @[example/src/fsm/TrafficFsm.scala 20:21]
    connect io.green, _io_green_T @[example/src/fsm/TrafficFsm.scala 20:12]

