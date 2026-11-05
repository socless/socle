package socle.layout

import socle.Fixtures
import socle.schema._
import socle.parse.ChirrtlParser
import socle.ir.IrWalk

import utest._

/** A closed code-block box always shows its condition: a pin on its WEST side
  * labelled with the condition as written, whether the condition enters the box
  * (a signal) or is computed inside it (an expression, wired only in its body). */
object CondPinTest extends TestSuite {

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def laid(fixture: String): Design =
    Layout.withLayouts(IrWalk.design(ChirrtlParser.parse(readFixture(fixture)), Fixtures.lines))

  private lazy val gcd: Design = laid("gcd.fir")

  // The laid pins of `box` that stand for its region's condition ports.
  private def condPins(d: Design, box: LaidNode): Seq[(PortDef, LaidPort)] = {
    val conds: Seq[PortDef] = d.regions(box.id).ports.filter(_.cond)
    conds.flatMap(p => box.ports.find(_.path == s"${box.id}.${p.name}").map(p -> _))
  }

  val tests = Tests {
    test("a closed FSM box shows no condition pin, a when box does") {
      val fsm: Design = laid("fsm.fir")
      val box: LaidNode = fsm.layouts("TrafficFsm").nodes.find(_.id == "fsm$TrafficFsm$switch_state$0").get
      // Its arm compares are flagged in its body, but the closed box shows none of them.
      assert(fsm.regions(box.id).ports.count(_.cond) == 3)
      assert(condPins(fsm, box).isEmpty)
      val when: LaidNode = gcd.layouts("GCD").nodes.find(_.id == "when$GCD$when_io_in_fire$0").get
      assert(condPins(gcd, when).map(_._2.label) == Seq("io.in.fire"))
    }

    test("a plain-signal condition enters on a WEST pin, labelled") {
      val layers: Design = laid("layers.fir")
      val box: LaidNode = layers.layouts("LayeredCounter").nodes.find(_.id == "when$LayeredCounter$when_io_en$0").get
      val pins: Seq[(PortDef, LaidPort)] = condPins(layers, box)
      assert(pins.size == 1)
      val (port, pin) = pins.head
      assert(port.dir == PortDir.In, port.label == "io.en", pin.side == PinSide.West, pin.label == "io.en")
    }

    test("every closed box of gcd shows its condition on WEST, labelled") {
      val shown: Seq[String] = for {
        (_, l) <- gcd.layouts.toSeq
        n      <- l.nodes if NodeKind.isBox(n.kind)
        p      <- gcd.regions(n.id).ports if p.cond
      } yield {
        val laid: Option[LaidPort] = n.ports.find(_.path == s"${n.id}.${p.name}")
        assert(laid.exists(_.side == PinSide.West), laid.exists(_.label == p.label), p.label.nonEmpty)
        p.label
      }
      // Each of gcd's four whens, as written; all four are expressions computed inside.
      assert(shown.toSet == Set("io.in.fire", "busy && x =/= y", "x > y", "io.out.fire"))
      val boxes: Seq[LaidNode] = gcd.layouts.values.flatMap(_.nodes).filter(n => NodeKind.isBox(n.kind)).toSeq
      assert(boxes.size == 4)
    }
  }
}
