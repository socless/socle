FIRRTL version 7.0.0
circuit SlaveOpL :%[[
  {
    "class":"firrtl.transforms.DedupGroupAnnotation",
    "target":"~|SlaveOpL",
    "group":"SlaveOpL"
  }
]]
  layer Verification, bind, "verification" :
    layer Assert, bind, "verification/assert" :
      layer Temporal, inline :
    layer Assume, bind, "verification/assume" :
      layer Temporal, inline :
    layer Cover, bind, "verification/cover" :
      layer Temporal, inline :

  public module SlaveOpL : @[SlaveOpL.scala 5:7]
    input clock : Clock @[SlaveOpL.scala 5:7]
    input reset : UInt<1> @[SlaveOpL.scala 5:7]
    input io : { sclk : UInt<1>, flip miso : UInt<1>, mosi : UInt<1>, ss : UInt<1>} @[SlaveOpL.scala 6:14]

    node rise = asClock(io.sclk) @[SlaveOpL.scala 8:22]
    node _fall_T = eq(io.sclk, UInt<1>(0h0)) @[SlaveOpL.scala 9:15]
    node fall = asClock(_fall_T) @[SlaveOpL.scala 9:25]
    node ssReset = asAsyncReset(io.ss) @[SlaveOpL.scala 11:23]
    reg v1 : UInt<1>[8], rise @[SlaveOpL.scala 13:33]
    reg v2 : UInt<1>[8], rise @[SlaveOpL.scala 14:33]
    node _T = asAsyncReset(reset) @[SlaveOpL.scala 19:35]
    regreset valXor : UInt<8>, rise, _T, UInt<8>(0h33) @[SlaveOpL.scala 21:16]
    regreset valAnd : UInt<8>, rise, _T, UInt<8>(0ha0) @[SlaveOpL.scala 22:16]
    regreset valOr : UInt<8>, rise, _T, UInt<8>(0hcc) @[SlaveOpL.scala 23:16]
    reg miso : UInt<1>, fall @[SlaveOpL.scala 26:35]
    connect io.miso, miso @[SlaveOpL.scala 27:11]
    reg plain : UInt<1>, clock @[SlaveOpL.scala 28:20]
    connect plain, io.mosi @[SlaveOpL.scala 28:20]
    regreset countMosi : UInt<3>, rise, ssReset, UInt<3>(0h7) @[SlaveOpL.scala 31:28]
    regreset numOctetMosi : UInt<2>, rise, ssReset, UInt<2>(0h0) @[SlaveOpL.scala 32:31]
    node _T_1 = eq(numOctetMosi, UInt<1>(0h0)) @[SlaveOpL.scala 34:23]
    when _T_1 : @[SlaveOpL.scala 34:32]
      connect v1[countMosi], io.mosi @[SlaveOpL.scala 35:21]
      node _T_2 = gt(countMosi, UInt<1>(0h0)) @[SlaveOpL.scala 36:22]
      when _T_2 : @[SlaveOpL.scala 36:29]
        node _countMosi_T = sub(countMosi, UInt<1>(0h1)) @[SlaveOpL.scala 37:32]
        node _countMosi_T_1 = tail(_countMosi_T, 1) @[SlaveOpL.scala 37:32]
        connect countMosi, _countMosi_T_1 @[SlaveOpL.scala 37:19]
      else :
        connect countMosi, UInt<3>(0h7) @[SlaveOpL.scala 39:19]
        node _numOctetMosi_T = add(numOctetMosi, UInt<1>(0h1)) @[SlaveOpL.scala 40:38]
        node _numOctetMosi_T_1 = tail(_numOctetMosi_T, 1) @[SlaveOpL.scala 40:38]
        connect numOctetMosi, _numOctetMosi_T_1 @[SlaveOpL.scala 40:22]
    else :
      node _T_3 = eq(numOctetMosi, UInt<1>(0h1)) @[SlaveOpL.scala 42:29]
      when _T_3 : @[SlaveOpL.scala 42:38]
        connect v2[countMosi], io.mosi @[SlaveOpL.scala 43:21]
        node _T_4 = gt(countMosi, UInt<1>(0h0)) @[SlaveOpL.scala 44:22]
        when _T_4 : @[SlaveOpL.scala 44:29]
          node _countMosi_T_2 = sub(countMosi, UInt<1>(0h1)) @[SlaveOpL.scala 45:32]
          node _countMosi_T_3 = tail(_countMosi_T_2, 1) @[SlaveOpL.scala 45:32]
          connect countMosi, _countMosi_T_3 @[SlaveOpL.scala 45:19]
        else :
          connect countMosi, UInt<3>(0h7) @[SlaveOpL.scala 47:19]
          node _numOctetMosi_T_2 = add(numOctetMosi, UInt<1>(0h1)) @[SlaveOpL.scala 48:38]
          node _numOctetMosi_T_3 = tail(_numOctetMosi_T_2, 1) @[SlaveOpL.scala 48:38]
          connect numOctetMosi, _numOctetMosi_T_3 @[SlaveOpL.scala 48:22]
      else :
        node _T_5 = gt(countMosi, UInt<1>(0h0)) @[SlaveOpL.scala 51:22]
        when _T_5 : @[SlaveOpL.scala 51:29]
          node _countMosi_T_4 = sub(countMosi, UInt<1>(0h1)) @[SlaveOpL.scala 52:32]
          node _countMosi_T_5 = tail(_countMosi_T_4, 1) @[SlaveOpL.scala 52:32]
          connect countMosi, _countMosi_T_5 @[SlaveOpL.scala 52:19]
        else :
          connect countMosi, UInt<3>(0h7) @[SlaveOpL.scala 54:19]
          connect numOctetMosi, UInt<1>(0h0) @[SlaveOpL.scala 55:22]
          node _valXor_T = cat(v1[7], v1[6], v1[5], v1[4], v1[3], v1[2], v1[1], v1[0]) @[SlaveOpL.scala 56:22]
          node _valXor_T_1 = cat(v2[7], v2[6], v2[5], v2[4], v2[3], v2[2], v2[1], v2[0]) @[SlaveOpL.scala 56:34]
          node _valXor_T_2 = xor(_valXor_T, _valXor_T_1) @[SlaveOpL.scala 56:29]
          connect valXor, _valXor_T_2 @[SlaveOpL.scala 56:16]
          node _valAnd_T = cat(v1[7], v1[6], v1[5], v1[4], v1[3], v1[2], v1[1], v1[0]) @[SlaveOpL.scala 57:22]
          node _valAnd_T_1 = cat(v2[7], v2[6], v2[5], v2[4], v2[3], v2[2], v2[1], v2[0]) @[SlaveOpL.scala 57:34]
          node _valAnd_T_2 = and(_valAnd_T, _valAnd_T_1) @[SlaveOpL.scala 57:29]
          connect valAnd, _valAnd_T_2 @[SlaveOpL.scala 57:16]
          node _valOr_T = cat(v1[7], v1[6], v1[5], v1[4], v1[3], v1[2], v1[1], v1[0]) @[SlaveOpL.scala 58:21]
          node _valOr_T_1 = cat(v2[7], v2[6], v2[5], v2[4], v2[3], v2[2], v2[1], v2[0]) @[SlaveOpL.scala 58:33]
          node _valOr_T_2 = or(_valOr_T, _valOr_T_1) @[SlaveOpL.scala 58:28]
          connect valOr, _valOr_T_2 @[SlaveOpL.scala 58:15]
    regreset countMiso : UInt<3>, fall, ssReset, UInt<3>(0h7) @[SlaveOpL.scala 65:28]
    regreset numOctetMiso : UInt<2>, fall, ssReset, UInt<2>(0h0) @[SlaveOpL.scala 66:31]
    node _T_6 = eq(numOctetMiso, UInt<1>(0h0)) @[SlaveOpL.scala 68:23]
    when _T_6 : @[SlaveOpL.scala 68:32]
      node _miso_T = dshr(valXor, countMiso) @[SlaveOpL.scala 69:21]
      node _miso_T_1 = bits(_miso_T, 0, 0) @[SlaveOpL.scala 69:21]
      connect miso, _miso_T_1 @[SlaveOpL.scala 69:12]
      node _T_7 = gt(countMiso, UInt<1>(0h0)) @[SlaveOpL.scala 70:22]
      when _T_7 : @[SlaveOpL.scala 70:29]
        node _countMiso_T = sub(countMiso, UInt<1>(0h1)) @[SlaveOpL.scala 71:32]
        node _countMiso_T_1 = tail(_countMiso_T, 1) @[SlaveOpL.scala 71:32]
        connect countMiso, _countMiso_T_1 @[SlaveOpL.scala 71:19]
      else :
        connect countMiso, UInt<3>(0h7) @[SlaveOpL.scala 73:19]
        node _numOctetMiso_T = add(numOctetMiso, UInt<1>(0h1)) @[SlaveOpL.scala 74:38]
        node _numOctetMiso_T_1 = tail(_numOctetMiso_T, 1) @[SlaveOpL.scala 74:38]
        connect numOctetMiso, _numOctetMiso_T_1 @[SlaveOpL.scala 74:22]
    else :
      node _T_8 = eq(numOctetMiso, UInt<1>(0h1)) @[SlaveOpL.scala 76:29]
      when _T_8 : @[SlaveOpL.scala 76:38]
        node _miso_T_2 = dshr(valAnd, countMiso) @[SlaveOpL.scala 77:21]
        node _miso_T_3 = bits(_miso_T_2, 0, 0) @[SlaveOpL.scala 77:21]
        connect miso, _miso_T_3 @[SlaveOpL.scala 77:12]
        node _T_9 = gt(countMiso, UInt<1>(0h0)) @[SlaveOpL.scala 78:22]
        when _T_9 : @[SlaveOpL.scala 78:29]
          node _countMiso_T_2 = sub(countMiso, UInt<1>(0h1)) @[SlaveOpL.scala 79:32]
          node _countMiso_T_3 = tail(_countMiso_T_2, 1) @[SlaveOpL.scala 79:32]
          connect countMiso, _countMiso_T_3 @[SlaveOpL.scala 79:19]
        else :
          connect countMiso, UInt<3>(0h7) @[SlaveOpL.scala 81:19]
          node _numOctetMiso_T_2 = add(numOctetMiso, UInt<1>(0h1)) @[SlaveOpL.scala 82:38]
          node _numOctetMiso_T_3 = tail(_numOctetMiso_T_2, 1) @[SlaveOpL.scala 82:38]
          connect numOctetMiso, _numOctetMiso_T_3 @[SlaveOpL.scala 82:22]
      else :
        node _miso_T_4 = dshr(valOr, countMiso) @[SlaveOpL.scala 85:20]
        node _miso_T_5 = bits(_miso_T_4, 0, 0) @[SlaveOpL.scala 85:20]
        connect miso, _miso_T_5 @[SlaveOpL.scala 85:12]
        node _T_10 = gt(countMiso, UInt<1>(0h0)) @[SlaveOpL.scala 86:22]
        when _T_10 : @[SlaveOpL.scala 86:29]
          node _countMiso_T_4 = sub(countMiso, UInt<1>(0h1)) @[SlaveOpL.scala 87:32]
          node _countMiso_T_5 = tail(_countMiso_T_4, 1) @[SlaveOpL.scala 87:32]
          connect countMiso, _countMiso_T_5 @[SlaveOpL.scala 87:19]
        else :
          connect countMiso, UInt<3>(0h7) @[SlaveOpL.scala 89:19]
          connect numOctetMiso, UInt<1>(0h0) @[SlaveOpL.scala 90:22]

