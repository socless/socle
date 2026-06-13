circuit Mini :
  module Mini : @[src/main/scala/mini/Mini.scala 5:7]
    input clock : Clock @[src/main/scala/mini/Mini.scala 5:7]
    input reset : UInt<1> @[src/main/scala/mini/Mini.scala 5:7]
    output io : { flip a : UInt<8>, flip b : UInt<8>, c : UInt<8>} @[src/main/scala/mini/Mini.scala 6:14]

    reg r : UInt<8>, clock with : (reset => (UInt<1>("h0"), r)) @[src/main/scala/mini/Mini.scala 8:20]
    node _sum_T = add(io.a, io.b) @[src/main/scala/mini/Mini.scala 9:17]
    node _sum = tail(_sum_T, 1) @[src/main/scala/mini/Mini.scala 9:17]
    r <= _sum @[src/main/scala/mini/Mini.scala 9:9]
    io.c <= r @[src/main/scala/mini/Mini.scala 10:10]
