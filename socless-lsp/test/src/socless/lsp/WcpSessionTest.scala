package socless.lsp

import socless.capture.{CapturedModel, ModelToDesign}
import socless.schema.Design
import socless.wave.{WaveGroup, WaveItem, WaveLayout, WavePlan, WaveSettings}
import scala.jdk.CollectionConverters._
import utest._

/** The external Surfer's WCP session, against [[FakeSurfer]]: the greeting and
  * load wait, the flat exchange of a plan, focus by returned id, a closed peer,
  * and the serial queue where only the latest pending plan runs. */
object WcpSessionTest extends TestSuite {
  private val Dut = "TOP.svsimTestbench.dut"

  private[lsp] lazy val gcd: Design = {
    val f = os.Path(sys.props("socless.root")) / "socless" / "test" / "resources" / "captured" / "gcd.json"
    ModelToDesign.design(CapturedModel.fromJson(os.read(f)))
  }

  private def eventually[T](what: String, timeoutMs: Long = 10000)(f: => Option[T]): T = {
    val end = System.currentTimeMillis() + timeoutMs
    var r   = Option.empty[T]
    while (r.isEmpty && System.currentTimeMillis() < end) {
      r = try f catch { case _: Exception => None }
      if (r.isEmpty) Thread.sleep(20)
    }
    r.getOrElse(sys.error(s"timed out waiting for $what"))
  }

  private def session(): WcpSession = WcpSession.listen(log = _ => ())

  private def colourIds(fake: FakeSurfer) =
    fake.frames.filter(_.value.get("command").exists(_.str == "set_item_color")).map(_("id").num.toInt)

  private def flat(n: String*) = WavePlan(Seq(WaveGroup("g", n.map(t => WaveItem(t, t, "Blue")), Nil, folded = false)), 0)

  val tests = Tests {
    test("greeting first, then a GCD plan: clear, one add_items per group, a colour per id, no groups") {
      val s = session()
      val fake = new FakeSurfer(s.port, loadingProbes = 2)
      try {
        // the greeting leads and asks for waveforms_loaded; the session waits for the trace to load
        val greeting = eventually("greeting")(fake.frames.headOption)
        assert(greeting("type").str == "greeting", greeting("version").str == "0")
        assert(greeting("commands").arr.map(_.str).contains("waveforms_loaded"))
        eventually("loaded")(Some(fake.commands).filter(_ == Seq("greeting", "get_item_list", "get_item_list", "get_item_list")))

        s.apply(WaveLayout.plan(gcd, List("GCD"), Dut, Set.empty, WaveSettings()))
        val all = eventually("plan applied")(Some(fake.frames.drop(4)).filter(_.count(_("command").str == "set_item_color") == 10))
        Thread.sleep(100)
        assert(fake.frames.size == 4 + 1 + 5 + 10) // nothing else: WCP has no grouping command
        assert(all.head("command").str == "clear")
        val adds = all.slice(1, 6)
        assert(adds.forall(_("command").str == "add_items"), adds.forall(!_("recursive").bool))
        assert(adds.map(_("items").arr.map(_.str.stripPrefix(Dut + ".")).toSeq) == Seq(
          Seq("io_in_valid", "io_in_ready"), Seq("io_in_bits_a", "io_in_bits_b"),
          Seq("io_out_valid", "io_out_ready"), Seq("io_out_bits_result"), Seq("x", "y", "busy")))
        val colours = all.drop(6).map(f => f("id").num.toInt -> f("color").str)
        assert(colours == Seq(1 -> "Blue", 2 -> "Green", 3 -> "Blue", 4 -> "Blue", 5 -> "Green", 6 -> "Blue",
          7 -> "Green", 8 -> "Orange", 9 -> "Orange", 10 -> "Orange"))
        assert(s.alive)
      } finally { fake.close(); s.close() }
    }
    test("focus sends focus_item with the id returned for that path") {
      val s = session()
      val fake = new FakeSurfer(s.port)
      try {
        s.apply(flat("T.a", "T.b", "T.c"))
        eventually("applied")(Some(fake.commands).filter(_.count(_ == "set_item_color") == 3))
        s.focus("T.unknown") // not shown: nothing sent
        Thread.sleep(200)
        assert(!fake.commands.contains("focus_item"))
        s.focus("T.b")
        val f = eventually("focus")(fake.frames.find(_.value.get("command").exists(_.str == "focus_item")))
        assert(f("id").num.toInt == 2)
        Thread.sleep(200)
        assert(fake.commands.count(_ == "focus_item") == 1)
        // a new plan replaces the ids: an old path is gone
        s.apply(flat("T.c"))
        eventually("second plan")(Some(fake.commands).filter(_.count(_ == "set_item_color") == 4))
        s.focus("T.a")
        s.focus("T.c")
        val last = eventually("second focus")(Some(fake.frames.filter(_.value.get("command").exists(_.str == "focus_item"))).filter(_.size == 2))
        assert(last(1)("id").num.toInt == 4)
      } finally { fake.close(); s.close() }
    }
    test("a closed peer: later calls are ignored, nothing throws") {
      val s = session()
      val fake = new FakeSurfer(s.port)
      try {
        s.apply(flat("T.a"))
        eventually("applied")(Some(fake.commands).filter(_.contains("set_item_color")))
        fake.close()
        s.apply(flat("T.b"))
        eventually("session dead")(Some(s.alive).filter(!_))
        s.apply(flat("T.c"))
        s.focus("T.a")
        assert(!s.alive)
      } finally s.close()
    }
    test("a newer plan aborts the running one between phases, and only the latest pending plan runs") {
      val s = session()
      val fake = new FakeSurfer(s.port, delayMs = 60)
      try {
        eventually("loaded")(Some(fake.commands).filter(_.contains("get_item_list")))
        s.apply(flat("T.1a", "T.1b"))
        eventually("first exchange started")(Some(fake.commands).filter(_.contains("clear")))
        s.apply(flat("T.2"))
        s.apply(flat("T.3"))
        s.focus("T.3")
        eventually("focus")(Some(fake.commands).filter(_.contains("focus_item")))
        val cmds = fake.frames.filter(_("type").str == "command").map(_("command").str).dropWhile(_ == "get_item_list")
        // the first plan's adds finish (their replies are read), its colours are never sent
        assert(cmds == Seq("clear", "add_items", "clear", "add_items", "set_item_color", "focus_item"))
        val items = fake.frames.filter(_.value.get("command").exists(_.str == "add_items")).map(_("items").arr.map(_.str).toSeq)
        assert(items == Seq(Seq("T.1a", "T.1b"), Seq("T.3"))) // T.2 was replaced before it ran
        assert(colourIds(fake) == Seq(3), s.alive)
      } finally { fake.close(); s.close() }
    }
    test("a plan of 300 commands is pipelined: the window in flight, every reply matched in order") {
      val s = session()
      // every reply held until the session stops sending, then all at once; one colour fails midway
      val fake = new FakeSurfer(s.port, burst = true, colorError = Some(100))
      val traces = (1 to 250).map(i => s"T.s$i")
      val plan = WavePlan(traces.grouped(5).toSeq.zipWithIndex.map { case (g, i) =>
        WaveGroup(s"g$i", g.map(t => WaveItem(t, t, "Blue")), Nil, folded = false)
      }, 0)
      try {
        s.apply(plan)
        eventually("applied", 30000)(Some(colourIds(fake)).filter(_.size == 250))
        assert(fake.commands.count(_ == "add_items") == 50)
        assert(colourIds(fake) == (1 to 250))
        assert(fake.maxOutstanding == WcpSession.Window) // pipelined, never past the window
        s.focus("T.s200")
        val f = eventually("focus")(fake.frames.find(_.value.get("command").exists(_.str == "focus_item")))
        assert(f("id").num.toInt == 200)
        s.apply(flat("T.x")) // still in step: the next plan runs
        eventually("next plan")(Some(colourIds(fake)).filter(_.size == 251))
        assert(colourIds(fake).last == 251, s.alive)
      } finally { fake.close(); s.close() }
    }
    test("a greeting slower than one reply timeout still connects (a window shown late)") {
      val s = WcpSession.listen(_ => (), replyTimeoutMs = 200, loadTimeoutMs = 5000)
      val fake = new FakeSurfer(s.port, greetingDelayMs = 700)
      try {
        s.apply(flat("T.a"))
        eventually("applied")(Some(fake.commands).filter(_.contains("set_item_color")))
        assert(s.alive)
      } finally { fake.close(); s.close() }
    }
    test("a reply slower than the reply timeout keeps the session; the next plan runs") {
      val s = WcpSession.listen(_ => (), replyTimeoutMs = 200)
      val fake = new FakeSurfer(s.port, slow = Some("clear" -> 700))
      try {
        s.apply(flat("T.a"))
        eventually("first plan")(Some(fake.commands).filter(_.contains("set_item_color")))
        assert(s.alive)
        s.apply(flat("T.b", "T.c"))
        eventually("second plan")(Some(fake.commands).filter(_.count(_ == "set_item_color") == 3))
        assert(s.alive)
      } finally { fake.close(); s.close() }
    }
    test("close unblocks a session waiting on a stalled reply") {
      val s = WcpSession.listen(_ => (), replyTimeoutMs = 200)
      val fake = new FakeSurfer(s.port, slow = Some("clear" -> 30000))
      try {
        s.apply(flat("T.a"))
        eventually("stalled")(Some(fake.commands).filter(_.contains("clear")))
        val t0 = System.currentTimeMillis()
        s.close()
        assert(!s.alive, System.currentTimeMillis() - t0 < 2000)
      } finally fake.close()
    }
    test("error paths: a group with a dropped path keeps its other signals (matched by name), a colour error is skipped") {
      val logs = new java.util.concurrent.ConcurrentLinkedQueue[String]
      val s = WcpSession.listen(m => { logs.add(m); () })
      // ids: T.a 1 (T.b dropped), then T.c 2, T.d 3 (its colour fails)
      val fake = new FakeSurfer(s.port, drop = Set("T.b"), colorError = Some(3))
      val plan = WavePlan(Seq(
        WaveGroup("g1", Seq(WaveItem("T.a", "a", "Blue"), WaveItem("T.b", "b", "Blue")), Nil, folded = false),
        WaveGroup("g2", Seq(WaveItem("T.c", "c", "Green"), WaveItem("T.d", "d", "Green")), Nil, folded = false)), 0)
      try {
        s.apply(plan)
        eventually("colours")(Some(colourIds(fake)).filter(_ == Seq(1, 2, 3)))
        // the short group's ids were named by get_item_info ("a [7:0]" is T.a)
        val info = fake.frames.filter(_.value.get("command").exists(_.str == "get_item_info"))
        assert(info.map(_("ids").arr.map(_.num.toInt).toSeq) == Seq(Seq(1)))
        eventually("dropped count logged")(logs.asScala.find(m => m.contains("1 signal") && m.contains("not in the trace")))
        s.focus("T.a")
        eventually("focus a")(fake.frames.find(_.value.get("command").exists(_.str == "focus_item")))
        s.focus("T.b") // dropped: nothing sent, a log line
        eventually("focus skipped")(logs.asScala.find(m => m.contains("no focus on T.b")))
        s.focus("T.d")
        eventually("focus d")(Some(fake.frames.filter(_.value.get("command").exists(_.str == "focus_item"))).filter(_.size == 2))
        assert(fake.frames.filter(_.value.get("command").exists(_.str == "focus_item")).map(_("id").num.toInt) == Seq(1, 3))
        assert(logs.asScala.exists(_.contains("set_item_color")), s.alive)
        s.apply(flat("T.x")) // the next plan still runs
        eventually("next plan")(Some(colourIds(fake)).filter(_.size == 4))
        assert(fake.commands.count(_ == "clear") == 2)
      } finally { fake.close(); s.close() }
    }
    test("an empty frame is skipped") {
      val s = session()
      val sock = new java.net.Socket("127.0.0.1", s.port)
      try {
        val in = sock.getInputStream
        val out = sock.getOutputStream
        def readFrame(): String = {
          val b = new java.io.ByteArrayOutputStream
          var c = in.read()
          while (c > 0) { b.write(c); c = in.read() }
          b.toString("UTF-8")
        }
        assert(readFrame().contains("greeting"))
        out.write(0) // an empty frame, then the real greeting
        out.write("""{"type":"greeting","version":"0","commands":[]}""".getBytes("UTF-8")); out.write(0); out.flush()
        assert(readFrame().contains("get_item_list"))
        assert(s.alive)
      } finally { sock.close(); s.close() }
    }
    test("start runs <command> --wcp-initiate <port> <trace>; a Surfer that exits early is reported once") {
      val bin  = os.temp.dir(prefix = "socless-wcp-bin-")
      val argv = bin / "argv"
      val surfer = bin / "surfer.sh"
      os.write(surfer, s"""#!/bin/sh\nprintf '%s\\n' "$$@" > $argv\n""", perms = os.PermSet.fromString("rwx------"))
      val trace = bin / "trace.vcd"
      os.write(trace, "")
      val log = bin / "surfer.log"
      val early = new java.util.concurrent.atomic.AtomicInteger
      val s = WcpSession.start(surfer.toString, trace, Some(log), _ => (), () => { early.incrementAndGet(); () })
      try {
        val args = eventually("argv")(Some(argv).filter(os.exists).map(os.read.lines(_)).filter(_.size == 3))
        assert(args(0) == "--wcp-initiate", args(1) == s.port.toString, args(2) == trace.toString)
        eventually("gave up")(Some(s.alive).filter(!_))
        eventually("reported")(Some(early.get).filter(_ == 1))
        s.apply(flat("T.a")) // ignored
        assert(os.exists(log))
      } finally { s.close(); os.remove.all(bin) }
    }
    test("a Surfer closed before it connects is not reported as an early exit") {
      val bin = os.temp.dir(prefix = "socless-wcp-bin-")
      val surfer = bin / "surfer.sh"
      os.write(surfer, "#!/bin/sh\nexec sleep 600\n", perms = os.PermSet.fromString("rwx------"))
      val trace = bin / "trace.vcd"
      os.write(trace, "")
      val early = new java.util.concurrent.atomic.AtomicInteger
      val s = WcpSession.start(surfer.toString, trace, None, _ => (), () => { early.incrementAndGet(); () })
      try {
        Thread.sleep(300)
        s.close()
        Thread.sleep(500)
        assert(early.get == 0, !s.alive)
      } finally { s.close(); os.remove.all(bin) }
    }
    test("start with a missing command throws, for the caller to report") {
      val e = intercept[java.io.IOException](WcpSession.start("/no/such/surfer", os.root / "t.vcd", None, _ => ()))
      assert(e.getMessage.contains("/no/such/surfer"))
    }
  }
}
