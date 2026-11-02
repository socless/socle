package socle.probe

import chisel3.RawModule
import chisel3.stage.DesignAnnotation
import socle.capture.CapturedModel
import utest._

/** Drives Capture's hooks in-process in the order the agent calls them, with a
  * walk that fails the way a Chisel version mismatch does (a LinkageError,
  * which NonFatal does not catch). The probe's own failure must never be
  * recorded as the user's elaboration error, nor escape a non-view run. */
object CaptureTest extends TestSuite {

  private lazy val gcdDut: RawModule = TestElab.elaborate(new _root_.gcd.GCD)._1

  /** Run the agent's sequence: design annotation, Elaborate exit with whatever
    * escaped, JVM shutdown. Returns what escaped and the written model. */
  private def run(view: Boolean): (Option[Throwable], CapturedModel) = {
    val out = os.temp.dir(prefix = "capture-test-") / "captured.json"
    Capture.resetForTest()
    Capture.configure(out.toNIO, view)
    Capture.walk = (_, _) => throw new NoClassDefFoundError("chisel3/SomethingMissing")
    try {
      val escaped =
        try { Capture.onAnnotation(DesignAnnotation(gcdDut)); None }
        catch { case t: Throwable => Some(t) }
      Capture.onElaborateExit(escaped.orNull)
      Capture.onShutdown()
      (escaped, CapturedModel.fromJson(os.read(out)))
    } finally Capture.resetForTest()
  }

  val tests = Tests {
    test("throwing walk in view mode: ok with a problem, only ViewOnly escapes") {
      val (escaped, m) = run(view = true)
      assert(escaped.exists(_.isInstanceOf[ViewOnly]))
      assert(m.status == "ok")
      assert(m.top == "GCD")
      assert(m.problems.exists(_.contains("SomethingMissing")))
    }
    test("throwing walk in non-view mode: nothing escapes") {
      val (escaped, m) = run(view = false)
      assert(escaped.isEmpty)
      assert(m.status == "ok")
      assert(m.problems.exists(_.contains("SomethingMissing")))
    }
    test("chisel version is recorded") {
      val out = os.temp.dir(prefix = "capture-test-") / "captured.json"
      Capture.resetForTest()
      Capture.configure(out.toNIO, view = false)
      try {
        Capture.onAnnotation(DesignAnnotation(gcdDut))
        val m = CapturedModel.fromJson(os.read(out))
        assert(m.status == "ok")
        assert(m.chiselVersion == "7.16.0")
      } finally Capture.resetForTest()
    }
  }
}
