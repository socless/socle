package socless.ui.theme

import socless.schema.Design

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

  /** Pane accent palette (#220): colors that used to be inline OKLCH literals in
    * the Inspector / BottomPanel / Chrome panes. Resolved from `:root` like the
    * core tokens above, so they recolor under dark theme. */
  // AXI-ish channel accents (Inspector channel-color map).
  val chanColors: Map[String, String] = Map(
    "ar" -> "var(--chan-ar)", "aw" -> "var(--chan-aw)",
    "w"  -> "var(--chan-w)",  "r"  -> "var(--chan-r)",
    "b"  -> "var(--chan-b)",
  )
  // Entity-kind accents (command palette).
  val kindInstance = "var(--kind-instance)"
  val kindPort     = "var(--kind-port)"
  // Schematic component-coloring fills (#266), keyed by NodeKind string. Pale
  // tints (dark-tinted under the dark theme) used as box backgrounds when the
  // "colorize components" toggle is on; hues mirror the debug-layout palette.
  val nodeFill: Map[String, String] = Map(
    "inst"     -> "var(--node-inst)",
    "boundary" -> "var(--node-boundary)",
    "reg"      -> "var(--node-reg)",
    "const"    -> "var(--node-const)",
    "op"       -> "var(--node-op)",
    "fsm"      -> "var(--node-fsm)",
    "mem"      -> "var(--node-mem)",
    "block"    -> "var(--node-block)",
  )
  // Scala source syntax palette (BottomPanel).
  val syntaxKw  = "var(--syntax-kw)"
  val syntaxTy  = "var(--syntax-ty)"
  val syntaxLit = "var(--syntax-lit)"
  // Source-link + selected-instance highlights (BottomPanel tokenizer).
  val linkChildBg = "var(--link-child-bg)"
  val linkChildFg = "var(--link-child-fg)"
  val linkSelBg   = "var(--link-sel-bg)"
  // Port-direction badges (BottomPanel ports table).
  val dirMasterBg = "var(--dir-master-bg)"
  val dirMasterFg = "var(--dir-master-fg)"
  val dirSlaveBg  = "var(--dir-slave-bg)"
  val dirSlaveFg  = "var(--dir-slave-fg)"
  // Unconnected-port badge (BottomPanel ports table).
  val badgeUnconnBg = "var(--badge-unconn-bg)"
  val badgeUnconnFg = "var(--badge-unconn-fg)"

  /** Header/port-row layout constants for `design`, from `design.layoutConsts`
    * (issue #38): the backend emits them so the schematic geometry stays in
    * sync with `LayoutCore`. */
  final case class Consts(headerH: Double, portPad: Double, portStep: Double)
  def consts(design: Design): Consts = {
    val lc = design.layoutConsts
    Consts(lc.headerH, lc.portPad, lc.portStep)
  }
}
