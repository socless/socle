package socle.ui

import com.raquo.laminar.api.L._

/** The side panes fit the window: the hierarchy needs `LeftMin` px of window,
  * the inspector `RightMin`, so the schematic keeps a usable width (at 1024 px
  * both open left it under 500 px). A window narrowing past a pane's width
  * closes it; widening back past it reopens only a pane closed for the width (a narrow
  * start included).
  * Only crossings act, so a pane the user opens or closes stays as they left
  * it while the window resizes within a band. `width`: the window's at start. */
final class PaneFit(left: Var[Boolean], right: Var[Boolean], width: Double) {
  private var last      = width
  // closed for the window's width (at start or by a narrowing), not by the user
  private var autoLeft  = !PaneFit.leftFits(width)
  private var autoRight = !PaneFit.rightFits(width)

  def resized(w: Double): Unit = {
    autoLeft = cross(left, autoLeft, PaneFit.leftFits(last), PaneFit.leftFits(w))
    autoRight = cross(right, autoRight, PaneFit.rightFits(last), PaneFit.rightFits(w))
    last = w
  }

  /** Applies a crossing to `open`; returns whether the pane is closed by a narrowing. */
  private def cross(open: Var[Boolean], auto: Boolean, before: Boolean, now: Boolean): Boolean =
    if (before && !now && open.now()) { open.set(false); true }
    else if (!before && now && auto) { if (!open.now()) open.set(true); false }
    else auto && !open.now() // reopened by hand: no longer the narrowing's
}

object PaneFit {
  val LeftMin  = 1000.0
  val RightMin = 1280.0
  def leftFits(width: Double): Boolean  = width >= LeftMin
  def rightFits(width: Double): Boolean = width >= RightMin
}
