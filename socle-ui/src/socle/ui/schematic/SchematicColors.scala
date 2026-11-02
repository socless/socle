package socle.ui.schematic

import socle.ui.theme.Tokens

/** Optional schematic styling colors (issue #266), applied only when the
  * "colorize components" / "colorize wires" toggles are on. Both are pure
  * String producers (theme-aware CSS color expressions) so the renderers stay
  * declarative and the off state is byte-for-byte unchanged.
  *
  * Members are `private[schematic]`. */
private[schematic] object SchematicColors {

  /** The pale per-kind box fill for a node `kind` (a `NodeKind` string), or the
    * neutral panel for an unknown kind. Mirrors the debug-layout palette hues. */
  def boxFill(kind: String): String = Tokens.nodeFill.getOrElse(kind, Tokens.panel)

  /** A stable stroke color for a wire, used when "colorize wires" is on.
    *
    * Honors both requested dimensions (issue #266). A typed wire takes a hue
    * FAMILY from its bundle name, so every wire of a given interface (AXI4,
    * Decoupled, ...) reads as the same broad color, plus a small per-net jitter
    * so individual nets of that interface stay distinguishable rather than
    * collapsing to one indistinct color. A plain untyped net (`bundle` empty or
    * `"Wire"`) spreads fully across the wheel by `netKey` (its driver path) so
    * adjacent / crossing wires separate cleanly. Lightness and chroma come from
    * theme vars (`--wire-l` / `--wire-c`) so the hue reads on both the light and
    * dark canvas; only the hue is computed, deterministically (no `Math.random`,
    * which would reshuffle colors every render). */
  def wireStroke(bundle: String, netKey: String): String = {
    val typed = bundle.nonEmpty && bundle != "Wire"
    val hue =
      if (typed) {
        val family = hueOf(bundle)
        val jitter = hueOf(netKey) % 40 - 20 // +/-20 deg around the family hue
        ((family + jitter) % 360 + 360) % 360
      } else hueOf(netKey)
    s"oklch(var(--wire-l) var(--wire-c) $hue)"
  }

  /** A deterministic hue in `[0, 360)` derived from `key` (FNV-1a/32). Distinct
    * keys spread across the wheel; the same key always maps to the same hue. */
  def hueOf(key: String): Int = {
    var h = 0x811c9dc5
    var i = 0
    while (i < key.length) { h = (h ^ key.charAt(i).toInt) * 0x01000193; i += 1 }
    val m = h % 360
    if (m < 0) m + 360 else m
  }

  /** A colour for each clock domain of `m` (its instances' `clock`), in order of
    * first use; empty when `m` has fewer than two domains. */
  def clockDomains(m: socle.schema.ModuleDef): Map[String, String] = {
    val ds = m.children.map(_.clock).filter(_.nonEmpty).distinct
    if (ds.length < 2) Map.empty
    else ds.zipWithIndex.map { case (d, i) => d -> Tokens.domainColors(i % Tokens.domainColors.length) }.toMap
  }
}
