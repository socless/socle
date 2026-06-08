package socless

import utest._
import upickle.default._

object SchemaLayoutTest extends TestSuite {
  val tests = Tests {
    test("ModuleLayout round-trips through JSON") {
      val layout = ModuleLayout(
        nodes = Seq(
          LaidNode("cpu", "inst", 0, 0, 160, 80,
            Seq(LaidPort("cpu.io", 160, 40, "EAST", interface = true))),
        ),
        edges = Seq(RoutedEdge("cpu.io", "xbar.in", Seq((160, 40), (220, 40), (220, 90)), "31x ground connects")),
        w = 400, h = 200,
      )
      val d = Design("Top", Map.empty, Nil, Map.empty, Map("Top" -> layout))
      val json = write(d)
      val back = read[Design](json)
      assert(back.layouts("Top").nodes.head.id == "cpu")
      assert(back.layouts("Top").edges.head.points.length == 3)
      assert(back.layouts("Top").nodes.head.ports.head.interface)
    }

    test("Design without layouts still parses (default empty)") {
      val back = read[Design]("""{"top":"T","bundles":{},"modules":[]}""")
      assert(back.layouts.isEmpty)
    }
  }
}
