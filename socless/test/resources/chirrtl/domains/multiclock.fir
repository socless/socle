FIRRTL version 6.0.0
circuit MultiClock :
  module Leaf :
    input clock : Clock
    input reset : UInt<1>
    output io : { flip a : UInt<8>, b : UInt<8>}

    regreset r : UInt<8>, clock, reset, UInt<8>(0h0)
    connect r, io.a
    connect io.b, r

  public module MultiClock :
    input clock : Clock
    input reset : UInt<1>
    input altClock : Clock
    output io : { flip a : UInt<8>, b : UInt<8>}

    inst subA of Leaf
    inst subB of Leaf

    connect subA.clock, clock
    connect subA.reset, reset
    connect subB.clock, altClock
    connect subB.reset, reset

    connect subA.io.a, io.a
    connect subB.io.a, subA.io.b
    connect io.b, subB.io.b
