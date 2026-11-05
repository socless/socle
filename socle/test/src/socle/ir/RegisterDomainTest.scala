package socle.ir

import socle.schema._
import socle.parse._
import socle.layout._
import socle.capture.{CapturedModel, ModelToDesign}

import utest._

/** Registers carry their clock and reset domain, and the logic that only
  * derives a clock or reset gets a domain sink (a short labelled stub).
  *
  * The fixture is SlaveOpL from miniProjet as Chisel 7.16 emits it: registers
  * on `rise = io.sclk.asClock` and `fall = (!io.sclk).asClock`, resets
  * `ssReset = io.ss.asAsyncReset` and `reset.asAsyncReset` (a temp `_T`),
  * plus one implicit-clock register `plain` added for the `clock` domain. */
object RegisterDomainTest extends TestSuite {

  private def read(path: String): String = {
    val s = getClass.getResourceAsStream(path)
    require(s != null, s"missing classpath resource $path")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  private lazy val circ   = ChirrtlParser.parse(read("/chirrtl/domains/slaveopl.fir"))
  private lazy val design = IrWalk.design(circ)
  private lazy val slave  = design.modules.find(_.name == "SlaveOpL").get

  private def reg(id: String): LogicNodeDef =
    slave.logic.find(n => n.id == id && n.kind == NodeKind.Reg).getOrElse(sys.error(s"no register $id"))

  val tests = Tests {
    test("the parser keeps a register's clock and a regreset's reset operand") {
      val regs = circ.modules.find(_.name == "SlaveOpL").get.regs.map(r => r.name -> r).toMap
      assert(regs("v1").clock == "rise", regs("v1").reset == "")
      assert(regs("miso").clock == "fall")
      assert(regs("countMosi").clock == "rise", regs("countMosi").reset == "ssReset")
      assert(regs("valXor").clock == "rise", regs("valXor").reset == "_T")
      assert(regs("valXor").init.contains("UInt<8>(0h33)"))
      assert(regs("plain").clock == "clock", regs("plain").reset == "")
    }

    test("a register's domain is the signal as the user named it") {
      assert(reg("v1").clock == "rise", reg("v1").reset == "")
      assert(reg("miso").clock == "fall")
      assert(reg("countMosi").clock == "rise", reg("countMosi").reset == "ssReset")
      assert(reg("countMiso").clock == "fall", reg("countMiso").reset == "ssReset")
      // `_T = asAsyncReset(reset)` is a temp: the domain is the implicit `reset`.
      assert(reg("valXor").reset == "reset")
      assert(reg("plain").clock == "clock", reg("plain").reset == "")
    }

    test("a derived clock or reset gets a domain sink wired from its source") {
      val sinks = slave.logic.filter(_.kind == NodeKind.Domain).map(n => n.id -> n).toMap
      // fall: from the `!io.sclk` op; rise: from the port leaf io.sclk; ssReset: from io.ss.
      assert(sinks.keySet == Set(Synthetic.clockDomainId("fall"), Synthetic.clockDomainId("rise"),
        Synthetic.resetDomainId("ssReset")))
      val fall = sinks(Synthetic.clockDomainId("fall"))
      assert(fall.label == "fall", fall.clock == "fall", fall.reset == "")
      val ss = sinks(Synthetic.resetDomainId("ssReset"))
      assert(ss.label == "ssReset", ss.reset == "ssReset")
      // Resets registers on both clocks: no single clock to colour it with.
      assert(ss.clock == "")
      def driver(sink: String): Seq[String] = slave.wires.filter(_.from == sink).map(_.to)
      assert(driver(Synthetic.clockDomainId("fall")) == Seq("_fall_T"))
      assert(driver(Synthetic.clockDomainId("rise")) == Seq("io.sclk"))
      assert(driver(Synthetic.resetDomainId("ssReset")) == Seq("io.ss"))
      // The implicit clock and reset ports have their clk/rst rows: no sink.
      assert(!sinks.values.exists(n => n.label == "clock" || n.label == "reset"))
      // None of these wires is hidden as a clock/reset wire.
      assert(slave.wires.filter(w => sinks.contains(w.from)).forall(w => !LayoutCore.isClockReset(w)))
    }

    test("the `!io.sclk` op and io.ss are no longer dangling") {
      assert(slave.wires.exists(w => w.to == "_fall_T"))
      assert(slave.wires.exists(w => w.to == "io.ss"))
    }

    test("the layout places each domain sink as a stub with one WEST pin") {
      val prep = LayoutCore.prepare(slave, design)
      val fall = prep.nodes.find(_.id == Synthetic.clockDomainId("fall")).get
      assert(fall.kind == NodeKind.Domain, fall.pins.map(_.side) == Seq(PinSide.West), fall.layerConstraint.isEmpty)
      assert(prep.edges.exists(e => e.fromPath == Synthetic.clockDomainId("fall") && e.toPath == "_fall_T"))
      val laid = Layout.layout(slave, design)
      val box  = laid.nodes.find(_.id == Synthetic.clockDomainId("fall")).get
      assert(box.w > 0, box.h > 0)
      assert(laid.edges.exists(e => e.from == Synthetic.clockDomainId("fall")))
    }

    test("a module's domains: instance clocks, then register clocks") {
      assert(LayoutCore.clockDomains(slave) == Seq("rise", "fall", "clock"))
    }

    test("a module with one domain shows no colours") {
      val gcd = IrWalk.design(ChirrtlParser.parse(read("/chirrtl/gcd.fir")))
      gcd.modules.foreach(m => assert(LayoutCore.clockDomains(m).isEmpty))
      // Instances only, all on `clock`: no colours either.
      val deep = IrWalk.design(ChirrtlParser.parse(read("/chirrtl/deepsoc.fir")))
      deep.modules.foreach(m => assert(LayoutCore.clockDomains(m).isEmpty))
    }

    test("a captured design's instances carry their clock and reset from the CHIRRTL") {
      val d = ModelToDesign.design(CapturedModel.fromJson(read("/captured/deepsoc.json")))
      val clocked = d.modules.flatMap(_.children).filter(_.foldCount == 0)
      assert(clocked.nonEmpty)
      clocked.foreach(c => assert(c.clock == "clock", c.reset == "reset"))
    }
  }
}
