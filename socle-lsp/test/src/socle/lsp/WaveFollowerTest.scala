package socle.lsp

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._
import socle.wave.{WaveLayout, WavePlan, WaveSettings}
import utest._

/** `POST /wave/select` to the viewer: debounce (only the last selection of a
  * burst), the trace's test, the follow setting, the module plan and focus. */
object WaveFollowerTest extends TestSuite {
  private val Dut  = "TOP.svsimTestbench.dut"
  private val Test = "gcd.GcdSpec#GCD computes gcd(48, 18)"
  private def gcd  = WcpSessionTest.gcd

  /** Records what reaches the viewer, in order. */
  private final class Recorder extends WaveTarget {
    val calls = new ConcurrentLinkedQueue[Any]
    def apply(plan: WavePlan): Unit = calls.add(plan)
    def focus(trace: String): Unit  = calls.add(trace)
    def seen: Seq[Any] = calls.asScala.toSeq
  }

  private def gcdPlan(s: WaveSettings = WaveSettings()) = WaveLayout.plan(gcd, List("GCD"), Dut, Set.empty, s)

  private final class Env(settings: WaveSettings = WaveSettings(followDelayMs = 100)) {
    val rec = new Recorder
    @volatile var ctx: Option[WaveFollower.Context] =
      Some(WaveFollower.Context(rec, Test, gcd, Dut, Set.empty, settings))
    val follower = new WaveFollower(() => ctx, _ => ())
    def select(instPath: List[String], selection: Option[String], test: String = Test): Unit =
      follower.select(SelectRequest(test, instPath, selection))
  }

  val tests = Tests {
    test("three selections within the debounce give one apply, the last") {
      val e = new Env
      try {
        e.select(List("GCD"), None)
        e.select(List("GCD"), Some("x"))
        e.select(List("GCD"), Some("io.out"))
        Thread.sleep(500)
        // the module was not shown yet: its plan, then the focus on io.out's first field
        assert(e.rec.seen == Seq(gcdPlan(), Dut + ".io_out_valid"))
      } finally e.follower.close()
    }
    test("a request the panel already debounced runs without the delay") {
      val e = new Env(WaveSettings(followDelayMs = 5000))
      try {
        e.follower.select(SelectRequest(Test, List("GCD"), Some("x")), debounce = false)
        Thread.sleep(400)
        assert(e.rec.seen == Seq(gcdPlan(WaveSettings(followDelayMs = 5000)), Dut + ".x"))
      } finally e.follower.close()
    }
    test("a selection of another test's trace is ignored") {
      val e = new Env
      try {
        e.select(List("GCD"), None, test = "other.Spec#t")
        Thread.sleep(300)
        assert(e.rec.seen.isEmpty)
      } finally e.follower.close()
    }
    test("following off, or no trace or design: selections do nothing and never throw") {
      val e = new Env(WaveSettings(followSelection = false, followDelayMs = 50))
      try {
        e.select(List("GCD"), None)
        Thread.sleep(200)
        assert(e.rec.seen.isEmpty)
        e.ctx = None
        e.select(List("GCD"), Some("x"))
        Thread.sleep(200)
        assert(e.rec.seen.isEmpty)
      } finally e.follower.close()
    }
    test("showTop applies the top module's plan at once, whatever the follow setting") {
      val e = new Env(WaveSettings(followSelection = false))
      try {
        e.follower.showTop()
        assert(e.rec.seen == Seq(gcdPlan()))
      } finally e.follower.close()
    }
    test("inside the shown module a selection only focuses; null keeps it; unknown paths do nothing") {
      val e = new Env
      try {
        e.follower.showTop()
        e.select(List("GCD"), Some("y"))
        Thread.sleep(300)
        assert(e.rec.seen == Seq(gcdPlan(), Dut + ".y"))
        e.select(List("GCD"), Some("io.in.bits")) // a bundle pin: its first field
        Thread.sleep(300)
        assert(e.rec.seen.last == Dut + ".io_in_bits_a")
        e.select(List("GCD"), Some("nothing_here")) // not a signal (a wire, a block): nothing
        e.select(List("GCD", "nochild"), None)      // a stale module path: nothing, the view stays
        Thread.sleep(300)
        assert(e.rec.seen.size == 3)
        e.select(List("GCD"), None) // the module shown: no re-plan (a panel reload posts it again)
        Thread.sleep(300)
        assert(e.rec.seen.size == 3)
      } finally e.follower.close()
    }
    test("a null selection re-plans the shown module only when the settings or the design changed") {
      val e = new Env
      try {
        e.follower.showTop()
        val clockReset = WaveSettings(showClockReset = true, followDelayMs = 50)
        e.ctx = e.ctx.map(_.copy(settings = clockReset))
        e.select(List("GCD"), None)
        Thread.sleep(300)
        assert(e.rec.seen == Seq(gcdPlan(), gcdPlan(clockReset)))
        e.select(List("GCD"), None)
        Thread.sleep(300)
        assert(e.rec.seen.size == 2)
        e.ctx = e.ctx.map(_.copy(design = gcd.copy())) // a new capture of the same test
        e.select(List("GCD"), None)
        Thread.sleep(300)
        assert(e.rec.seen == Seq(gcdPlan(), gcdPlan(clockReset), gcdPlan(clockReset)))
      } finally e.follower.close()
    }
    test("a focus re-plans the shown module when the settings or the design changed") {
      val e = new Env
      try {
        e.follower.showTop()
        val clockReset = WaveSettings(showClockReset = true, followDelayMs = 50)
        e.ctx = e.ctx.map(_.copy(settings = clockReset))
        e.select(List("GCD"), Some("y"))
        Thread.sleep(300)
        assert(e.rec.seen == Seq(gcdPlan(), gcdPlan(clockReset), Dut + ".y"))
        e.select(List("GCD"), Some("x")) // nothing changed: focus only
        Thread.sleep(300)
        assert(e.rec.seen.size == 4, e.rec.seen.last == Dut + ".x")
        e.ctx = e.ctx.map(_.copy(design = gcd.copy())) // a new capture of the same test
        e.select(List("GCD"), Some("busy"))
        Thread.sleep(300)
        assert(e.rec.seen.drop(4) == Seq(gcdPlan(clockReset), Dut + ".busy"))
      } finally e.follower.close()
    }
    test("the plan uses the settings of the moment") {
      val e = new Env
      try {
        e.ctx = e.ctx.map(_.copy(settings = WaveSettings(showClockReset = true, followDelayMs = 50)))
        e.select(List("GCD"), None)
        Thread.sleep(300)
        assert(e.rec.seen == Seq(gcdPlan(WaveSettings(showClockReset = true))))
      } finally e.follower.close()
    }
  }
}
