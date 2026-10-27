package socless.wave

import socless.capture.{CapturedModel, ModelToDesign}
import socless.schema._
import utest._

object WaveLinksTest extends TestSuite {
  private val Dut = "TOP.svsimTestbench.dut"

  private def resource(p: String) =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(p), "UTF-8").mkString

  // logic folded into a block whose region holds the registers and ops
  private lazy val gcd: Design = ModelToDesign.design(CapturedModel.fromJson(resource("/captured/gcd.json")))
  // a hierarchy with ops, registers and memories at the module's level
  private lazy val deep: Design = upickle.default.read[Design](resource("/golden/deepsoc.json"))

  private val Core = s"$Dut.cluster0.cores_0"

  val tests = Tests {
    test("locate a port leaf, a register, and what is not in the design") {
      val m = gcd.modules.head
      assert(WaveLinks.locate(gcd, Dut, s"$Dut.io_in_bits_a") == Some(WaveLinks.Located(List("GCD"), "io.in.bits.a", m.source)))
      val x = WaveLinks.locate(gcd, Dut, s"$Dut.x").get
      assert(x.leaf == "x", x.source.line == 30)
      assert(WaveLinks.locate(gcd, Dut, "TOP.svsimTestbench.clock").isEmpty)
      assert(WaveLinks.locate(gcd, Dut, s"$Dut.nope").isEmpty)
      assert(WaveLinks.locate(gcd, Dut, "Other.x").isEmpty)
    }

    test("locate walks the instance scopes") {
      val l = WaveLinks.locate(deep, Dut, s"$Core.alu.io_a").get
      assert(l.instPath == List("DeepSoc", "cluster0", "cores_0", "alu"), l.leaf == "io.a")
      assert(WaveLinks.locate(deep, Dut, s"$Core.acc").map(_.leaf) == Some("acc"))
    }

    test("a register drives an output through a block's pin") {
      assert(WaveLinks.drivers(gcd, Dut, Set.empty, s"$Dut.io_out_bits_result") == Seq(s"$Dut.x"))
    }

    test("an input feeds the registers through the block's muxes and ops") {
      val l = WaveLinks.loads(gcd, Dut, Set.empty, s"$Dut.io_in_bits_a")
      assert(l.contains(s"$Dut.x"))
      assert(!l.contains(s"$Dut.io_in_bits_a"))
    }

    test("a register's drivers go through its input logic, its loads from its output") {
      val d = WaveLinks.drivers(gcd, Dut, Set.empty, s"$Dut.busy")
      assert(d.contains(s"$Dut.io_in_valid"))
      val l = WaveLinks.loads(gcd, Dut, Set.empty, s"$Dut.busy")
      assert(l.contains(s"$Dut.io_out_valid"))
    }

    test("a child's input is driven in the parent") {
      assert(WaveLinks.drivers(deep, Dut, Set.empty, s"$Core.alu.io_a") == Seq(s"$Core.acc"))
    }

    test("a child's output feeds the parent's register (through a mux) and other instance") {
      // acc := alu.io.out (DeepSoc.scala:92), rf.io.wdata := alu.io.out
      assert(WaveLinks.loads(deep, Dut, Set.empty, s"$Core.alu.io_out").toSet == Set(s"$Core.acc", s"$Core.rf.io_wdata"))
    }

    test("a memory's read data is driven by its access ports") {
      val d = WaveLinks.drivers(deep, Dut, Set.empty, s"$Core.rf.io_rdata").toSet
      assert(d == Set(s"$Core.rf.io_raddr", s"$Core.rf.io_waddr", s"$Core.rf.io_wdata"))
    }

    test("a bundle wire carries a field") {
      assert(WaveLinks.loads(deep, Dut, Set.empty, s"$Dut.io_in_bits_data") == Seq(s"$Dut.cluster0.io_in_bits_data"))
    }

    test("the trace's names filter the result; a constant drives nothing traced") {
      assert(WaveLinks.drivers(gcd, Dut, Set(s"$Dut.y"), s"$Dut.io_out_bits_result").isEmpty)
      assert(WaveLinks.drivers(deep, Dut, Set.empty, s"$Core.rf.io_wen").isEmpty)
    }

    test("a top input has no drivers, an unknown path no links") {
      assert(WaveLinks.drivers(gcd, Dut, Set.empty, s"$Dut.io_in_bits_a").isEmpty)
      assert(WaveLinks.loads(gcd, Dut, Set.empty, "TOP.svsimTestbench.clock").isEmpty)
    }
  }
}
