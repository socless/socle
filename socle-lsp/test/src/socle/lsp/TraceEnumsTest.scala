package socle.lsp

import socle.wave.WaveEnums.Table
import utest._

object TraceEnumsTest extends TestSuite {
  private val Header =
    """$version Verilator $end
      |$timescale 1ps $end
      | $scope module TOP $end
      |  $scope module svsimTestbench $end
      |   $scope module dut $end
      |    $var wire 1 ! clock $end
      |    $var wire 2 " state [1:0] $end
      |    $var wire 4 # phase [3:0] $end
      |   $upscope $end
      |  $upscope $end
      | $upscope $end
      |$enddefinitions $end
      |""".stripMargin
  private val Body = "#0\n0!\nb10 \"\nb1010 #\n#1\n1!\nb0 \"\n"
  private val Dut = "TOP.svsimTestbench.dut"
  private val traffic = Table(s"$Dut.state", "state", Seq(BigInt(0) -> "sRed", BigInt(1) -> "sGreen", BigInt(2) -> "sYellow"))
  private val sparse = Table(s"$Dut.phase", "state", Seq(BigInt(0) -> "Idle", BigInt(5) -> "Load", BigInt(9) -> "Run", BigInt(12) -> "Done"))

  private def run(tables: Seq[Table], ext: String = "vcd"): (Int, Option[String]) = {
    val dir = os.temp.dir(prefix = "socle-enums-")
    val in  = dir / s"trace.$ext"
    os.write(in, Header + Body)
    val out = dir / s"trace-states.$ext"
    val n   = TraceEnums.write(in, out, tables)
    (n, Option.when(os.exists(out))(os.read(out)))
  }

  val tests = Tests {
    test("a table and its reference go right before the register's $var; the values are untouched") {
      val (n, out) = run(Seq(traffic))
      assert(n == 1)
      val text = out.get
      val lines = text.linesIterator.toSeq
      val i = lines.indexWhere(_.contains("state [1:0]"))
      // the three states, then the fourth value (3) by its number: a 2-bit register is filled
      assert(lines(i - 2) == "$attrbegin misc 07 state_t 4 sRed sGreen sYellow 3 00 01 10 11 1 $end")
      assert(lines(i - 1) == "$attrbegin misc 07 \"\" 1 $end")
      assert(text.endsWith("$enddefinitions $end\n" + Body))
      assert(text.replaceAll("\\$attrbegin[^\n]*\n", "") == Header + Body)
    }

    test("two registers get one table each, at their own width") {
      val (n, out) = run(Seq(traffic, sparse))
      assert(n == 2)
      val phase = out.get.linesIterator.find(l => l.startsWith("$attrbegin misc 07 state_t 16 "))
      // 4 bits: the 4 states, then the 12 other values; ids count up
      assert(phase.exists(_.contains("Idle Load Run Done 1 2 3 4 6 7 8 10 11 13 14 15 0000 0101 1001 1100 0001")))
      assert(phase.exists(_.endsWith(" 2 $end")))
    }

    test("nothing written for no match, a value too wide for the variable, or an FST") {
      assert(run(Seq(traffic.copy(trace = s"$Dut.other"))) == ((0, None)))
      assert(run(Seq(traffic.copy(states = traffic.states :+ (BigInt(4) -> "sBig")))) == ((0, None)))
      assert(run(Seq(traffic), ext = "fst") == ((0, None)))
      assert(run(Nil) == ((0, None)))
    }

    test("FST: Verilator's LZ4 hierarchy gets the table before the register, as gzip; the value blocks are untouched") {
      // TrafficFsm's own trace, from Verilator through ChiselSim (an emitFst option of the spec)
      val dir = os.temp.dir(prefix = "socle-enums-")
      val in  = dir / "trace.fst"
      os.write(in, os.read.bytes(os.Path(sys.props("socle.root")) / "socle-lsp" / "test" / "resources" / "wave" / "trafficfsm.fst"))
      val out = dir / "trace-states.fst"
      assert(TraceEnums.write(in, out, Seq(traffic)) == 1)
      val before = TraceEnums.fstEntries(in).get
      val after  = TraceEnums.fstEntries(out).get
      // the attributes: 252, misc 0, enum table 7, the text, 0, the id (1); then the reference
      val table = Array[Byte](252.toByte, 0, 7) ++ "state_t 4 sRed sGreen sYellow 3 00 01 10 11".getBytes ++ Array[Byte](0, 1)
      val ref   = Array[Byte](252.toByte, 0, 7, 0, 1)
      val at    = after.indexOfSlice(table)
      assert(at > 0, after.indexOfSlice(ref, at) == at + table.length)
      // right before the var entry of `state`, the entries otherwise as they were
      assert(new String(after.slice(at + table.length + ref.length + 2, at + table.length + ref.length + 7)) == "state")
      assert(after.patch(at, Nil, table.length + ref.length).sameElements(before))
      // the blocks before and after the hierarchy are the same bytes
      val (a, b) = (os.read.bytes(in), os.read.bytes(out))
      val hierAt = 526 // HDR, VCDATA_DYN_ALIAS2, GEOM, then HIER_LZ4 (the fixture's layout)
      assert(a(hierAt) == 6, b(hierAt) == 4, a.take(hierAt).sameElements(b.take(hierAt)))
    }

    test("FST: no matching register, or not an FST, writes nothing") {
      val dir = os.temp.dir(prefix = "socle-enums-")
      val in  = dir / "trace.fst"
      os.write(in, os.read.bytes(os.Path(sys.props("socle.root")) / "socle-lsp" / "test" / "resources" / "wave" / "trafficfsm.fst"))
      assert(TraceEnums.write(in, dir / "o.fst", Seq(traffic.copy(trace = s"$Dut.other"))) == 0, !os.exists(dir / "o.fst"))
      os.write(dir / "junk.fst", "not an fst")
      assert(TraceEnums.write(dir / "junk.fst", dir / "o2.fst", Seq(traffic)) == 0)
    }

    test("a header with no end is left alone") {
      val dir = os.temp.dir(prefix = "socle-enums-")
      os.write(dir / "t.vcd", Header.replace("$enddefinitions $end\n", ""))
      assert(TraceEnums.write(dir / "t.vcd", dir / "o.vcd", Seq(traffic)) == 0, !os.exists(dir / "o.vcd"))
    }
  }
}
