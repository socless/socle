package socle.ui

import com.raquo.laminar.api.L.Var
import utest._

/** The side panes close when the window narrows past their width, and reopen
  * on widening only when the narrowing closed them. */
object PaneFitTest extends TestSuite {
  private def fit(width: Double = 1440) = {
    val l = Var(PaneFit.leftFits(width)); val r = Var(PaneFit.rightFits(width))
    (l, r, new PaneFit(l, r, width))
  }

  val tests = Tests {
    test("start: a narrow window has them closed") {
      assert(PaneFit.leftFits(1440), PaneFit.rightFits(1440))
      assert(PaneFit.leftFits(1024), !PaneFit.rightFits(1024))
      assert(!PaneFit.leftFits(800), !PaneFit.rightFits(800))
    }
    test("narrowing closes, widening back reopens") {
      val (l, r, f) = fit()
      f.resized(1100)
      assert(l.now(), !r.now())
      f.resized(900)
      assert(!l.now(), !r.now())
      f.resized(1440)
      assert(l.now(), r.now())
    }
    test("a pane the user closed stays closed on widening") {
      val (l, r, f) = fit()
      r.set(false) // the user's toggle
      f.resized(900)
      f.resized(1440)
      assert(l.now(), !r.now())
    }
    test("a pane the user reopened while narrow is theirs") {
      val (l, r, f) = fit()
      f.resized(1100)
      r.set(true) // reopened by hand
      f.resized(1150) // a resize within the band does not close it again
      assert(r.now())
      r.set(false) // then closed by hand
      f.resized(1440)
      assert(!r.now())
    }
    test("a window that starts narrow reopens them on widening") {
      val (l, r, f) = fit(900)
      assert(!l.now(), !r.now())
      f.resized(1440)
      assert(l.now(), r.now()) // closed for the width at start, not by the user
    }
  }
}
