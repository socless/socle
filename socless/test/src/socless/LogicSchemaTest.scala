package socless

import utest._
import upickle.default._

object LogicSchemaTest extends TestSuite {
  val tests = Tests {
    test("LogicNodeDef round-trips") {
      val n = LogicNodeDef("r", "reg", "r", "Decoupled_x", SourceRef.unknown)
      assert(read[LogicNodeDef](write(n)) == n)
    }
    test("ModuleDef.logic defaults empty and old JSON parses") {
      val m = ModuleDef("M", "M", "", SourceRef.unknown, Nil, Nil, Nil, Metrics(0,0,0))
      assert(m.logic.isEmpty)
      assert(read[ModuleDef](write(m)).logic.isEmpty)
    }
  }
}
