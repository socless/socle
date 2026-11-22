package socle.ui.model

import socle.schema._
import utest._

/** A bus into a block's typed pin is inspected as the port it draws. */
object TypedBusTest extends TestSuite {
  private val Blk = "blk$Agg$logic"
  private def w(from: String, to: String) = WireDef(from, to, "UInt<1>", "", SourceRef.unknown)
  private def port(name: String, bundle: String) = PortDef(name, PortDir.Inout, bundle, "", SourceRef.unknown)
  private def design(wires: Seq[WireDef]) = {
    val m = ModuleDef("Agg", "Agg", "", SourceRef.unknown, Nil, Nil, wires, Metrics(0, 0, 0))
    val region = ModuleDef(Blk, Blk, "", SourceRef.unknown,
      Seq(port("io_in", "Vec[chisel3.util.DecoupledIO, 3]"), port("io_alt", "Vec[chisel3.util.DecoupledIO, 3]"), port("io_ack", "")), Nil, Nil, Metrics(0, 0, 0))
    (m, Design("Agg", Map.empty, Seq(m), regions = Map(Blk -> region)))
  }
  private val fields = Seq(
    w("io.in[0].ready", s"$Blk.io_in[0].ready"),
    w(s"$Blk.io_in[0].valid", "io.in[0].valid"),
    w(s"$Blk.io_in[1].bits", "io.in[1].bits"))

  val tests = Tests {
    test("from the module's end: the port, of the pin's type") {
      val (m, d) = design(fields)
      assert(TypedBus.of(m, d, "io.in") == Some(TypedBus.Bus("io.in", s"$Blk.io_in", "Vec[chisel3.util.DecoupledIO, 3]")))
    }
    test("from the pin's end: the same bus") {
      val (m, d) = design(fields)
      assert(TypedBus.of(m, d, s"$Blk.io_in") == Some(TypedBus.Bus("io.in", s"$Blk.io_in", "Vec[chisel3.util.DecoupledIO, 3]")))
    }
    test("a field of the port elsewhere too: drawn apart, the bus stays") {
      val (m, d) = design(fields :+ w("when$Agg$x$0.io_in_0_valid", "io.in[0].valid"))
      assert(TypedBus.of(m, d, "io.in").map(_.pin) == Some(s"$Blk.io_in"))
    }
    test("the port's elements on two pins: not one bus") {
      val (m, d) = design(fields :+ w(s"$Blk.io_alt[2].valid", "io.in[2].valid"))
      assert(TypedBus.of(m, d, "io.in").isEmpty)
    }
    test("an untyped pin, or a single field: none") {
      val (m, d) = design(fields :+ w("io.ack", s"$Blk.io_ack"))
      assert(TypedBus.of(m, d, "io.ack").isEmpty)
      assert(TypedBus.of(m, d, "io.in[0].valid").isEmpty)
    }
  }
}
