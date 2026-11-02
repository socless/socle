package socle.probe

import socle.capture.ModelToDesign
import socle.schema._
import utest._

/** Vec ports, captured live from iobundles.Interconnect and adapted to the
  * design.json schema, follow the CHIRRTL pipeline's Vec convention
  * ("Vec[<element key>, n]", the element's flip carried by the Vec field). */
object AdaptVecTest extends TestSuite {

  private def leaves(d: Design): Set[(String, String, Option[String], Int)] = {
    def walk(n: BundleTree.PortNode): Seq[BundleTree.PortNode] =
      if (n.children.isEmpty) Seq(n) else n.children.flatMap(walk)
    d.modules.flatMap { m =>
      m.ports.flatMap { p =>
        val base = if (p.dir == PortDir.Out || p.dir == PortDir.Master) PortDir.Out else PortDir.In
        walk(BundleTree.portTree(p.bundle, Some(base), p.name, d)).map(l => (m.name, l.path, l.dir, l.width))
      }
    }.toSet
  }

  val tests = Tests {
    test("interconnect vec ports") {
      val m      = TestElab.captureOf(new iobundles.Interconnect)
      val d      = ModelToDesign.design(m)
      val oracle = socle.SocleExporter.designFromChirrtl(m.chirrtl)
      val io     = d.bundles(d.modules.find(_.name == "Interconnect").get.ports.find(_.name == "io").get.bundle)
      val in     = io.channels.find(_.name == "in").get
      assert(in.flip)
      val vecOf = """Vec\[(.+), 3\]""".r
      val elem = in.`type` match { case vecOf(e) => d.bundles(e); case other => throw new RuntimeException(other) }
      assert(elem.scalaClass == "chisel3.util.DecoupledIO")
      assert(io.channels.find(_.name == "ack").get.`type` == "Vec[UInt<1>, 3]")
      val (mine, theirs) = (leaves(d), leaves(oracle))
      Predef.assert(mine == theirs, s"only adapted ${mine -- theirs}, only oracle ${theirs -- mine}")
    }
  }
}
