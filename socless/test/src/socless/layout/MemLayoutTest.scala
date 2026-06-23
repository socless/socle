package socless.layout

import socless._
import socless.schema._
import socless.parse._
import socless.ir._
import utest._

object MemLayoutTest extends TestSuite {
  private val memMod =
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

  val tests = Tests {
    test("mem node lays out with WEST addr/wdata pins and EAST rdata pin") {
      val d = IrWalk.design(ChirrtlParser.parse(memMod))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val mem = L.nodes.find(_.kind == NodeKind.Mem).get
      assert(mem.id == "mem")
      val rData = Synthetic.memPin("mem", "io_rdata_MPORT", "r")
      val rAddr = Synthetic.memPin("mem", "io_rdata_MPORT", "a")
      val wData = Synthetic.memPin("mem", "MPORT", "w")
      assert(mem.ports.exists(p => p.path == rData && p.side == PinSide.East))
      assert(mem.ports.exists(p => p.path == rAddr && p.side == PinSide.West))
      assert(mem.ports.exists(p => p.path == wData && p.side == PinSide.West))
    }
    test("mem pin labels show role tag + addr/data, not the raw pin id") {
      val d = IrWalk.design(ChirrtlParser.parse(memMod))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val mem = L.nodes.find(_.kind == NodeKind.Mem).get
      val labels = mem.ports.map(_.label).toSet
      assert(labels.contains("R0 addr"))
      assert(labels.contains("R0 data"))
      assert(labels.contains("W0 addr"))
      assert(labels.contains("W0 data"))
    }
    test("debug palette and legend include mem") {
      assert(LayoutDebug.Palette.contains("mem"))
    }
    test("debug-layout text dump shows the mem node with its addr/data pins") {
      val d = IrWalk.design(ChirrtlParser.parse(memMod))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val t = LayoutDebug.textFor("M", L)
      assert(t.contains("kind=mem"))
      val rData = Synthetic.memPin("mem", "io_rdata_MPORT", "r")
      val rAddr = Synthetic.memPin("mem", "io_rdata_MPORT", "a")
      assert(t.contains(s"pin $rData"))
      assert(t.contains(s"pin $rAddr"))
    }
    test("debug-layout SVG with a mem node is well-formed XML") {
      val d = IrWalk.design(ChirrtlParser.parse(memMod))
      val L = Layout.layout(d.modules.find(_.name == "M").get, d)
      val s = LayoutDebug.svgFor("M", L)
      javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser()
        .parse(new java.io.ByteArrayInputStream(s.getBytes("UTF-8")),
               new org.xml.sax.helpers.DefaultHandler())
      assert(s.contains("<svg"))
    }
  }
}
