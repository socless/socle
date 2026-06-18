package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._
import upickle.default._

object SchemaWireFieldTest extends TestSuite {
  val tests = Tests {
    test("fieldLevel defaults to false and is omitted from JSON") {
      val w = WireDef("a.x", "b.y", "UInt<8>", "", SourceRef.unknown)
      assert(!w.fieldLevel)
      assert(!write(w).contains("fieldLevel")) // serializeDefaults off -> default omitted
    }
    test("older JSON without fieldLevel still parses") {
      val json = """{"from":"a.x","to":"b.y","bundle":"UInt<8>","label":"","source":{"file":"","line":0,"col":0}}"""
      assert(!read[WireDef](json).fieldLevel)
    }
    test("fieldLevel=true round-trips") {
      val w = WireDef("a.x", "b.y", "UInt<8>", "x", SourceRef.unknown, fieldLevel = true)
      assert(read[WireDef](write(w)).fieldLevel)
    }
  }
}
