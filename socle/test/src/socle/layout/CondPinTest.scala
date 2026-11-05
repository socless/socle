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

  private lazy val gcd: Design =
    Layout.withLayouts(IrWalk.design(ChirrtlParser.parse(readFixture("gcd.fir")), Fixtures.lines))

  val tests = Tests {
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
