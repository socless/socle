package socless

import utest._

/** Tests that ChirrtlParser correctly captures register and memory declarations,
  * and that IrWalk propagates the counts into Metrics.
  *
  * Expected counts in the regheavy fixture:
  *   Controller           - 2 regresets (state, timer)
  *   RegStage             - 5 regresets (rData, rTag, rFlags, rValid, rCount)
  *   RegStage_1           - 5 regresets (same structure, different module instance)
  *   Queue2_PipePayload   - 3 regresets + 1 cmem (ram)
  *   Datapath             - 0 registers (top-level interconnect module)
  *
  * Expected counts in a synthetic snippet with plain `reg` (no reset):
  *   one plain reg, two regresets, one smem
  */
object RegisterCountTest extends TestSuite {

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private def mod(circ: ChirrtlParser.Circ, name: String): ChirrtlParser.Mod =
    circ.modules.find(_.name == name).getOrElse(
      throw new RuntimeException(s"module '$name' not found in ${circ.modules.map(_.name)}")
    )

  val tests = Tests {

    // ── regheavy fixture ──────────────────────────────────────────────────────

    test("regheavy: total register count across all modules is > 0") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val total = circ.modules.map(_.regs.size).sum
      assert(total > 0)
    }

    test("regheavy: Controller has exactly 2 registers") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val m = mod(circ, "Controller")
      assert(m.regs.size == 2)
      assert(m.regs.map(_.name).toSet == Set("state", "timer"))
    }

    test("regheavy: RegStage has exactly 5 registers") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val m = mod(circ, "RegStage")
      assert(m.regs.size == 5)
      assert(m.regs.map(_.name).toSet == Set("rData", "rTag", "rFlags", "rValid", "rCount"))
    }

    test("regheavy: RegStage_1 has exactly 5 registers (deduped module copy)") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val m = mod(circ, "RegStage_1")
      assert(m.regs.size == 5)
    }

    test("regheavy: Queue2_PipePayload has 3 registers and 1 memory") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val m = mod(circ, "Queue2_PipePayload")
      assert(m.regs.size == 3)
      assert(m.mems.size == 1)
      assert(m.mems.head.name == "ram")
    }

    test("regheavy: Datapath (top-level) has 0 registers") {
      val circ = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val m = mod(circ, "Datapath")
      assert(m.regs.isEmpty)
    }

    // ── rvpipeline fixture ────────────────────────────────────────────────────

    test("rvpipeline: Fetch has exactly 1 register (pc)") {
      val circ = ChirrtlParser.parse(readFixture("rvpipeline.fir"))
      val m = mod(circ, "Fetch")
      assert(m.regs.size == 1)
      assert(m.regs.head.name == "pc")
    }

    test("rvpipeline: RegFile has 0 registers and 1 memory (cmem regs)") {
      val circ = ChirrtlParser.parse(readFixture("rvpipeline.fir"))
      val m = mod(circ, "RegFile")
      assert(m.regs.isEmpty)
      assert(m.mems.size == 1)
      assert(m.mems.head.name == "regs")
    }

    // ── IrWalk integration: Metrics.registers is populated ───────────────────

    test("IrWalk: regheavy Metrics.registers > 0 for register-bearing modules") {
      val circ   = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val design = IrWalk.design(circ)
      val regStage = design.modules.find(_.name == "RegStage").getOrElse(
        throw new RuntimeException("RegStage not found in design")
      )
      assert(regStage.metrics.registers == 5)
    }

    test("IrWalk: regheavy Metrics.memories > 0 for Queue2_PipePayload") {
      val circ   = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val design = IrWalk.design(circ)
      val queue = design.modules.find(_.name == "Queue2_PipePayload").getOrElse(
        throw new RuntimeException("Queue2_PipePayload not found in design")
      )
      assert(queue.metrics.memories == 1)
    }

    test("IrWalk: Datapath (top-level) has registers=0 and memories=0") {
      val circ   = ChirrtlParser.parse(readFixture("regheavy.fir"))
      val design = IrWalk.design(circ)
      val top = design.modules.find(_.name == "Datapath").getOrElse(
        throw new RuntimeException("Datapath not found in design")
      )
      assert(top.metrics.registers == 0)
      assert(top.metrics.memories == 0)
    }

    // ── Synthetic snippet: plain reg (no reset) ───────────────────────────────

    test("synthetic: plain reg (no regreset) is captured") {
      val chirrtl =
        """|circuit SyntheticTest :
           |  module SyntheticTest :
           |    input clock : Clock
           |    input reset : UInt<1>
           |    output out : UInt<8>
           |    reg counter : UInt<8>, clock
           |    regreset status : UInt<2>, clock, reset, UInt<2>(0h0)
           |    smem storage : UInt<32>[16]
           |""".stripMargin
      val circ = ChirrtlParser.parse(chirrtl)
      val m = mod(circ, "SyntheticTest")
      assert(m.regs.size == 2)
      assert(m.regs.map(_.name).toSet == Set("counter", "status"))
      assert(m.mems.size == 1)
      assert(m.mems.head.name == "storage")
    }

    test("synthetic: mem (structured-memory block header) is captured") {
      val chirrtl =
        """|circuit MemTest :
           |  module MemTest :
           |    input clock : Clock
           |    input reset : UInt<1>
           |    mem data_mem :
           |      data-type => UInt<32>
           |      depth => 256
           |      read-latency => 1
           |      write-latency => 1
           |      reader => rd
           |      writer => wr
           |      read-under-write => undefined
           |""".stripMargin
      val circ = ChirrtlParser.parse(chirrtl)
      val m = mod(circ, "MemTest")
      // mem header line should be captured as a MemDecl
      assert(m.mems.size == 1)
      assert(m.mems.head.name == "data_mem")
    }
  }
}
