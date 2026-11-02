package socle.ir

import socle.schema._
import socle.parse._
import socle.ir._
import utest._
import upickle.default._

object MemoryNodeTest extends TestSuite {
  val tests = Tests {
    test("LogicNodeDef carries optional memInfo and round-trips") {
      val mi = MemInfo(depth = 16, elem = "UInt<32>", sync = false,
        ports = Seq(MemPortDef("R0", "read", "io_rdata_MPORT", SourceRef.unknown)))
      val n = LogicNodeDef("mem", NodeKind.Mem, "mem", "", SourceRef.unknown, memInfo = Some(mi))
      val back = read[LogicNodeDef](write(n))
      assert(back.kind == "mem")
      assert(back.memInfo.get.depth == 16)
      assert(back.memInfo.get.ports.head.tag == "R0")
    }
    test("old design.json without memInfo still parses (back-compat)") {
      val json = """{"id":"r","kind":"reg","label":"r","bundle":"UInt<8>","source":{"file":"","line":0,"col":0}}"""
      val n = read[LogicNodeDef](json)
      assert(n.memInfo.isEmpty)
    }
    test("memPin round-trips through baseOf to the mem id; reg/op unaffected") {
      val p = Synthetic.memPin("mem", "io_rdata_MPORT", "r")
      assert(p == "mem~io_rdata_MPORT~r")
      assert(Synthetic.memPinParts(p).contains(("mem", "io_rdata_MPORT", "r")))
      assert(Synthetic.baseOf(p) == "mem")
      assert(Synthetic.baseOf("r~q") == "r")     // register unchanged
      assert(Synthetic.baseOf("_T~in0") == "_T") // op input unchanged
      assert(Synthetic.baseOf(Synthetic.memPin("mem", "in_data", "w")) == "mem")
    }
    test("read+write memory yields a mem node wired through addr/data pins") {
      val src =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    input io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>}
          |    cmem mem : UInt<32>[16]
          |    infer mport io_rdata_MPORT = mem[io.raddr], clock
          |    connect io.rdata, io_rdata_MPORT
          |    when io.wen :
          |      infer mport MPORT = mem[io.waddr], clock
          |      connect MPORT, io.wdata
          |""".stripMargin
      val d = IrWalk.design(ChirrtlParser.parse(src))
      val m = d.modules.find(_.name == "M").get
      val mem = m.logic.find(_.kind == NodeKind.Mem).get
      assert(mem.id == "mem")
      val info = mem.memInfo.get
      assert(info.depth == 16 && info.elem == "UInt<32>")
      assert(info.ports.exists(p => p.dir == "read"  && p.name == "io_rdata_MPORT"))
      assert(info.ports.exists(p => p.dir == "write" && p.name == "MPORT"))
      // WireDef(from = sink, to = source).
      def has(from: String, to: String) = m.wires.exists(w => w.from == from && w.to == to)
      assert(has("io.rdata", Synthetic.memPin("mem", "io_rdata_MPORT", "r")))
      assert(has(Synthetic.memPin("mem", "io_rdata_MPORT", "a"), "io.raddr"))
      assert(has(Synthetic.memPin("mem", "MPORT", "w"), "io.wdata"))
      assert(has(Synthetic.memPin("mem", "MPORT", "a"), "io.waddr"))
    }
    test("a cmem with no mports still yields a mem node with empty ports") {
      val src =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    cmem mem : UInt<8>[4]
          |""".stripMargin
      val d = IrWalk.design(ChirrtlParser.parse(src))
      val m = d.modules.find(_.name == "M").get
      val mem = m.logic.find(_.kind == NodeKind.Mem).get
      assert(mem.id == "mem")
      assert(mem.memInfo.get.ports.isEmpty)
    }
  }
}
