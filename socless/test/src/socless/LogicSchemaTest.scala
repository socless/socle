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
    test("LogicNodeDef.op round-trips and defaults empty") {
      val n = LogicNodeDef("_T", "op", "sum", "", SourceRef.unknown, op = "add")
      assert(read[LogicNodeDef](write(n)).op == "add")
      assert(LogicNodeDef("r", "reg", "r", "", SourceRef.unknown).op.isEmpty)
      // Older design.json without the "op" key must still parse with op defaulting to "".
      val oldJson = """{"id":"_T","kind":"reg","label":"r","bundle":"","source":{"file":"","line":0,"col":0}}"""
      assert(read[LogicNodeDef](oldJson).op == "")
    }
  }
}
