package socless.parse

import socless.schema._
import utest._
import ChirrtlParser.{parse, MemDecl, MPortDecl}

object MemDeclParseTest extends TestSuite {
  private def memsOf(s: String): Seq[MemDecl] =
    parse(s).modules.find(_.name == "M").get.mems

  val tests = Tests {
    test("cmem parses element type and depth, sync=false") {
      val src =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    cmem mem : UInt<32>[16] @[F.scala 1:1]
          |""".stripMargin
      val m = memsOf(src).find(_.name == "mem").get
      assert(m.elem == "UInt<32>")
      assert(m.depth == 16)
      assert(!m.sync)
      assert(m.src.isDefined)
    }
    test("smem is sync=true") {
      val src =
        """circuit M :
          |  module M :
          |    smem ram : UInt<8>[64]
          |""".stripMargin
      val m = memsOf(src).find(_.name == "ram").get
      assert(m.sync)
      assert(m.depth == 64)
    }
    test("structured mem (no inline type) has empty elem and depth 0") {
      val src =
        """circuit M :
          |  module M :
          |    mem big :
          |""".stripMargin
      val m = memsOf(src).find(_.name == "big").get
      assert(m.elem == "")
      assert(m.depth == 0)
    }
    test("mport lines parse with mode, name, mem, addr; nested under when captured") {
      val src =
        """circuit M :
          |  module M :
          |    input clock : Clock
          |    input io : { flip raddr : UInt<4>, rdata : UInt<32>, flip waddr : UInt<4>, flip wdata : UInt<32>, flip wen : UInt<1>}
          |    cmem mem : UInt<32>[16]
          |    infer mport io_rdata_MPORT = mem[io.raddr], clock @[F.scala 2:2]
          |    connect io.rdata, io_rdata_MPORT
          |    when io.wen :
          |      infer mport MPORT = mem[io.waddr], clock
          |      connect MPORT, io.wdata
          |""".stripMargin
      val mports = parse(src).modules.find(_.name == "M").get.mports
      assert(mports.size == 2)
      val rd = mports.find(_.name == "io_rdata_MPORT").get
      assert(rd.mode == "infer" && rd.mem == "mem" && rd.addr == "io.raddr")
      val wr = mports.find(_.name == "MPORT").get
      assert(wr.mem == "mem" && wr.addr == "io.waddr")
    }
  }
}
