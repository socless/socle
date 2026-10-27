package socless.wave

import utest._
import socless.wave.SurferWire._

object SurferWireTest extends TestSuite {
  private val plan = WavePlan(
    Seq(
      WaveGroup("io", Nil, Seq(
        WaveGroup("in", Seq(WaveItem("TOP.dut.io_in_valid", "valid", "Blue", 1)), Nil, folded = true)), folded = false),
      WaveGroup("Registers", Seq(WaveItem("TOP.dut.x", "x", "Orange", 16), WaveItem("TOP.dut.n", "n", "Gray")), Nil, folded = false),
    ),
    missing = 2,
  )

  val tests = Tests {
    test("every Cmd case round-trips") {
      val cmds: Seq[Cmd] = Seq(
        Load("http://127.0.0.1:1/wave/t1/trace"),
        Show(3, plan, List("GCD", "sub"), "http://127.0.0.1:1/wave/t1/trace"),
        Show(4, WavePlan(Nil, 0), Nil, ""),
        Focus("TOP.dut.x"),
        Add(Seq("TOP.dut.x", "TOP.dut.io_a"), Some("TOP.dut.y")),
        Add(Nil),
        Hello,
      )
      for (c <- cmds) assert(parseCmd(writeCmd(c)) == Some(c))
    }
    test("the type tags are the short case names") {
      assert(writeCmd(Load("u")).contains("\"$type\":\"Load\""))
      assert(parseCmd("""{"$type":"Focus","trace":"t"}""") == Some(Focus("t")))
      assert(parseCmd("""{"$type":"Hello"}""") == Some(Hello))
    }
    test("Hello is written as a tagged object, like the other commands") {
      // upickle writes a bare case object as a string ("Hello"), which the page drops
      assert(writeCmd(Hello) == """{"$type":"Hello"}""")
    }
    test("a Status round-trips") {
      for (s <- Seq(
        Status("ready", "http://h/wave/t1/trace", Some(7), Some(1)),
        Status("error", "", Some(2), None, shownSeqFailed = true),
        Status("idle", "", None, None),
        Status("ready", "u", None, None, event = Some(Event("add_drivers", "TOP.dut.x"))),
      )) assert(parseStatus(writeStatus(s)) == Some(s))
    }
    test("a Status has plain JSON fields for the page") {
      val j = ujson.read(writeStatus(Status("ready", "u", Some(7), None)))
      assert(j("shownSeq").num == 7, j("state").str == "ready", j("loadedTrace").str == "u")
    }
    test("an older Status without an event still parses") {
      assert(parseStatus("""{"state":"ready","loadedTrace":"u","shownSeq":null,"dropped":null}""").map(_.event) == Some(None))
    }
    test("malformed input is None") {
      assert(parseCmd("{}").isEmpty)
      assert(parseCmd("not json").isEmpty)
      assert(parseCmd("""{"$type":"Nope"}""").isEmpty)
      assert(parseCmd("""{"$type":"Show","seq":"x"}""").isEmpty)
      assert(parseCmd("null").isEmpty)
      assert(parseStatus("{}").isEmpty)
      assert(parseStatus("not json").isEmpty)
      assert(parseStatus("""{"state":"ready"}""").isEmpty)
      assert(parseStatus("""{"state":"ready","loadedTrace":"","shownSeq":"x","dropped":null}""").isEmpty)
    }
  }
}
