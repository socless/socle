package socless.schema

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

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
    test("Design.regions round-trips and defaults empty") {
      import upickle.default._
      val body = ModuleDef("fsm$M$state", "fsm$M$state", "", SourceRef.unknown,
        Nil, Nil, Nil, Metrics(0, 0, 0))
      val d = Design(top = "M", bundles = Map.empty, modules = Nil,
        regions = Map("fsm$M$state" -> body))
      val rt = read[Design](write(d))
      assert(rt.regions.contains("fsm$M$state"))
      // Old JSON without `regions` still parses (additive).
      val old = """{"top":"M","bundles":{},"modules":[]}"""
      assert(read[Design](old).regions.isEmpty)
    }
    test("an fsm LogicNodeDef round-trips") {
      import upickle.default._
      val n = LogicNodeDef("fsm$M$state", "fsm", "FSM: state", "", SourceRef.unknown)
      assert(read[LogicNodeDef](write(n)).kind == "fsm")
    }
  }
}
