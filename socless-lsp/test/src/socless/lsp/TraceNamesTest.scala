package socless.lsp

import utest._

/** The VCD header reader: full dotted var names and the DUT prefix. */
object TraceNamesTest extends TestSuite {
  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private val gcd = os.Path(prop("socless.root")) / "socless-lsp" / "test" / "resources" / "wave" / "gcd-header.vcd"

  private def vcd(text: String, ext: String = "vcd"): os.Path = {
    val p = os.temp(text, suffix = s".$ext", prefix = "socless-trace-")
    p.toIO.deleteOnExit()
    p
  }

  val tests = Tests {
    test("gcd header: names and the dut prefix") {
      val t = TraceNames.read(gcd, Set("clock", "reset", "io"))
      // the testbench mirrors the ports too
      assert(t.prefix == "TOP.svsimTestbench.dut")
      assert(t.names.contains("TOP.svsimTestbench.dut.io_out_bits_result"),
        t.names.contains("TOP.svsimTestbench.dut.x"),
        t.names.contains("TOP.svsimTestbench.io_in_ready"), // a bare `$` id code
        t.names.contains("TOP.svsimTestbench.traceSupported"))
      assert(t.names.size == 24)
    }
    test("no ports: the known prefix") {
      assert(TraceNames.read(gcd).prefix == TraceNames.DefaultPrefix)
      assert(TraceNames.DefaultPrefix == "TOP.svsimTestbench.dut")
    }
    test("the prefix comes from the trace") {
      val t = TraceNames.read(vcd(
        """$date today $end
          |$comment a $scope inside a comment $end
          |$scope module TOP $end
          |$scope module tb $end
          |$var wire 1 ! clk $end
          |$scope module u0 $end
          |$var wire 1 " clock $end
          |$var wire 8 # bus_a [7:0] $end
          |$var reg 1 $ q $end
          |$scope module inner $end
          |$var wire 1 % clock $end
          |$var wire 1 ' bus_b $end
          |$upscope $end
          |$upscope $end
          |$upscope $end
          |$upscope $end
          |$enddefinitions $end
          |#0
          |$var wire 1 & late $end
          |""".stripMargin), Set("clock", "bus"))
      assert(t.prefix == "TOP.tb.u0")
      assert(t.names == Set("TOP.tb.clk", "TOP.tb.u0.clock", "TOP.tb.u0.bus_a", "TOP.tb.u0.q", "TOP.tb.u0.inner.clock",
        "TOP.tb.u0.inner.bus_b"))
    }
    test("a submodule with the same port names does not win") {
      // nearly every Chisel module has clock, reset and io
      val sub = "$scope module sub $end $var wire 1 5 clock $end $var wire 1 6 reset $end " +
        "$var wire 4 7 io_q [3:0] $end $upscope $end\n"
      val last = "$var wire 1 & io_out_valid_0 $end\n"
      val text = os.read(gcd).replace(last, last + sub)
      val t = TraceNames.read(vcd(text), Set("clock", "reset", "io"))
      assert(t.names.contains("TOP.svsimTestbench.dut.sub.io_q"))
      assert(t.prefix == "TOP.svsimTestbench.dut")
    }
    test("ports matching no scope: the known prefix") {
      assert(TraceNames.read(gcd, Set("nope")).prefix == TraceNames.DefaultPrefix)
    }
    test("a header without $enddefinitions returns what it read") {
      val t = TraceNames.read(vcd("$scope module TOP $end\n$var wire 1 ! a $end\n$var wire 1 \" b"))
      assert(t.names == Set("TOP.a"), t.prefix == TraceNames.DefaultPrefix)
    }
    test("odd files never throw") {
      val bytes = Array[Byte](0, -1, -2, 36, 115, 99, 111, 112, 101, 10, -128)
      val bin = os.temp(bytes, suffix = ".vcd")
      bin.toIO.deleteOnExit()
      assert(TraceNames.read(bin).names.isEmpty)
      assert(TraceNames.read(vcd("")).names.isEmpty)
      assert(TraceNames.read(vcd("$upscope $end $upscope $end $var $end $scope")).names.isEmpty)
      val missing = os.temp.dir() / "none.vcd"
      assert(TraceNames.read(missing) == TraceNames(TraceNames.DefaultPrefix, Set.empty))
    }
    test("fst: the known prefix, names unknown") {
      assert(TraceNames.read(vcd("not a vcd", "fst"), Set("io")) == TraceNames(TraceNames.DefaultPrefix, Set.empty))
    }
  }
}
