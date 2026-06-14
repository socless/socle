package socless.ui

import socless.Design

/** Theme color tokens. Each is a `var(--x)` CSS custom property resolved from
  * `:root` in `socless-ui/html/SoCLess.src.html`, so a single stylesheet drives
  * both the Laminar inline styles here and any CSS classes. */
object Tokens {
  val bg         = "var(--bg)"
  val panel      = "var(--panel)"
  val panelAlt   = "var(--panelAlt)"
  val border     = "var(--border)"
  val borderSoft = "var(--borderSoft)"
  val ink        = "var(--ink)"
  val ink2       = "var(--ink2)"
  val ink3       = "var(--ink3)"
  val ink4       = "var(--ink4)"
  val accent     = "var(--accent)"
  val accentBg   = "var(--accentBg)"
  val accentLine = "var(--accentLine)"
  val hi         = "var(--hi)"
  val ok         = "var(--ok)"
  val warn       = "var(--warn)"
  val mono       = "var(--mono)"

  /** Header/port-row layout constants for `design`, from `design.layoutConsts`
    * (issue #38): the backend emits them so the schematic geometry stays in
    * sync with `LayoutCore`. */
  final case class Consts(headerH: Double, portPad: Double, portStep: Double)
  def consts(design: Design): Consts = {
    val lc = design.layoutConsts
    Consts(lc.headerH, lc.portPad, lc.portStep)
  }
}
