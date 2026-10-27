package socless.ui.model

import socless.schema.{Design, ModuleDef, ModuleKind}

/** One-character summary of a module's kind, shared by the hierarchy tree
  * ([[socless.ui.App]]) and the schematic box headers (`Schematic`) so both
  * label a module identically (mirrors the JS `ModuleGlyph`).
  */
object Glyphs {

  /** Glyph for the module named `tpe` in design `d`: hub (>=3 children),
    * register-bearing, blackbox (external/intrinsic), or plain. */
  def of(d: Design, tpe: String): String = of(d.modules.find(_.name == tpe))

  /** Glyph for an already resolved module (None: unknown type). */
  def of(mod: Option[ModuleDef]): String =
    mod match {
      case None => "·"
      case Some(m) =>
        if (m.children.length >= 3) "⬢"               // hub
        else if (m.metrics.registers > 0) "◆"          // register-bearing
        else if (m.kind == ModuleKind.External || m.kind == ModuleKind.Intrinsic) "■" // blackbox
        else "·"
    }
}
