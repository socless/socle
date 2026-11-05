FIRRTL version 7.0.0
circuit SpiSlave :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|SpiSlave",
    "group":"SpiSlave"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  public module SpiSlave : @[example/src/domains/SpiSlave.scala 11:7]
    input clock : Clock @[example/src/domains/SpiSlave.scala 11:7]
    input reset : UInt<1> @[example/src/domains/SpiSlave.scala 11:7]
    output io : { flip sclk : UInt<1>, flip ss : UInt<1>, flip mosi : UInt<1>, miso : UInt<1>} @[example/src/domains/SpiSlave.scala 12:14]

    node rise = asClock(io.sclk) @[example/src/domains/SpiSlave.scala 19:32]
    node _fall_T = eq(io.sclk, UInt<1>(0h0)) @[example/src/domains/SpiSlave.scala 20:25]
    node fall = asClock(_fall_T) @[example/src/domains/SpiSlave.scala 20:35]
    node ssReset = asAsyncReset(io.ss) @[example/src/domains/SpiSlave.scala 21:35]
    regreset shiftIn : UInt<8>, rise, ssReset, UInt<8>(0h0) @[example/src/domains/SpiSlave.scala 24:65]
    node _shiftIn_T = bits(shiftIn, 6, 0) @[example/src/domains/SpiSlave.scala 25:21]
    node _shiftIn_T_1 = cat(_shiftIn_T, io.mosi) @[example/src/domains/SpiSlave.scala 25:28]
    connect shiftIn, _shiftIn_T_1 @[example/src/domains/SpiSlave.scala 25:11]
    regreset shiftOut : UInt<8>, fall, ssReset, UInt<8>(0h0) @[example/src/domains/SpiSlave.scala 28:66]
    connect shiftOut, shiftIn @[example/src/domains/SpiSlave.scala 29:12]
    reg miso : UInt<1>, fall @[example/src/domains/SpiSlave.scala 30:41]
    node _miso_T = bits(shiftOut, 7, 7) @[example/src/domains/SpiSlave.scala 31:19]
    connect miso, _miso_T @[example/src/domains/SpiSlave.scala 31:8]
    connect io.miso, miso @[example/src/domains/SpiSlave.scala 32:11]

