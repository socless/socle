FIRRTL version 6.0.0
circuit Mini :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|Mini",
    "group":"Mini"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :

  public module Mini : @[src/main/scala/mini/Mini.scala 5:7]
    input clock : Clock @[src/main/scala/mini/Mini.scala 5:7]
    input reset : UInt<1> @[src/main/scala/mini/Mini.scala 5:7]
    output io : { flip a : UInt<8>, flip b : UInt<8>, c : UInt<8>} @[src/main/scala/mini/Mini.scala 6:14]

    regreset r : UInt<8>, clock, reset, UInt<8>(0h0) @[src/main/scala/mini/Mini.scala 8:20]
    node _sum_T = add(io.a, io.b) @[src/main/scala/mini/Mini.scala 9:17]
    node _sum = tail(_sum_T, 1) @[src/main/scala/mini/Mini.scala 9:17]
    connect r, _sum @[src/main/scala/mini/Mini.scala 9:9]
    connect io.c, r @[src/main/scala/mini/Mini.scala 10:10]
