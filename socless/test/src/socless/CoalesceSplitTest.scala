package socless

import utest._

/** Issue #182 Part A: coalesceWires now resolves real driver->sink edges via
  * NetTrace and applies a binary collapse-or-split rule. A bundle whose ground
  * fields all reach exactly one partner bundle stays one coalesced wire; a
  * bundle whose fields scatter to multiple partners is split into one
  * field-level WireDef per ground field.
  */
object CoalesceSplitTest extends TestSuite {

  private def topWires(chirrtl: String): Seq[WireDef] =
    IrWalk.design(ChirrtlParser.parse(chirrtl)).modules.find(_.name == "Top").get.wires

  val tests = Tests {
    // u.io and v.io pair on every field: io.x flows u -> v, io.y flows v -> u.
    // The whole bundle has a single partner on each side, so it stays collapsed.
    test("wholeBundle") {
      val chirrtl =
        """circuit Top :
          |  module Leaf :
          |    output io : { x : UInt<8>, flip y : UInt<8>}
          |  module Top :
          |    inst u of Leaf
          |    inst v of Leaf
          |    connect v.io.x, u.io.x
          |    connect u.io.y, v.io.y
          |""".stripMargin
      val wires = topWires(chirrtl)
      val between = wires.filter { w =>
        Set(w.from, w.to) == Set("u.io", "v.io")
      }
      // Whole-bundle pairing collapses: at least one coalesced wire spans the
      // two ports and none of the u.io<->v.io wires is field-level.
      assert(between.nonEmpty)
      assert(between.forall(!_.fieldLevel))
      // No ground field of u.io / v.io leaked out as a split wire.
      assert(!wires.exists(w => w.fieldLevel && (w.from.startsWith("u.io.") || w.to.startsWith("u.io."))))
    }

    // u.io.x drives v, u.io.y drives w: u.io scatters to two partners, so it is
    // split into one field-level wire per ground field, and there is NO coalesced
    // u.io <-> v.io whole-bundle wire.
    test("scattered") {
      val chirrtl =
        """circuit Top :
          |  module Leaf :
          |    output io : { x : UInt<8>, y : UInt<8>}
          |  module Top :
          |    inst u of Leaf
          |    inst v of Leaf
          |    inst w of Leaf
          |    connect v.io.x, u.io.x
          |    connect w.io.x, u.io.y
          |""".stripMargin
      val wires = topWires(chirrtl)

      val xWire = wires.find(w => w.to == "u.io.x" && w.from == "v.io.x")
      val yWire = wires.find(w => w.to == "u.io.y" && w.from == "w.io.x")
      assert(xWire.isDefined && xWire.get.fieldLevel)
      assert(yWire.isDefined && yWire.get.fieldLevel)

      val coalesced = wires.filter(w => Set(w.from, w.to) == Set("u.io", "v.io"))
      assert(coalesced.isEmpty)
    }
  }
}
