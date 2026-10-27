package socless.schema

import upickle.default.{ReadWriter, macroRW}

/** The panel's schematic defaults, from the editor's `socless.schematic*`
  * settings (and `socless.followCursor`): the language server hands them to
  * the panel in `/ide.json`. The panel starts from them and applies a change
  * of a setting at once; its own settings popover changes the view until the
  * next change of the setting. Shared by the language server and the UI.
  *
  *  - `colorizeComponents`, `colorizeWires`: colour boxes and wires by kind;
  *  - `density`: compact, comfortable or comfy chrome; `labelSize`: normal,
  *    large or xl schematic labels;
  *  - `autoOptimize`: lay each view out with the optimize pass;
  *  - `hintBar`: the hint bar under the schematic;
  *  - `showLayers`: draw verification layers;
  *  - `hide`: node kinds left out of the schematic ([[SchematicSettings.Kinds]]);
  *  - `followCursor`: the class under the editor's cursor lights its boxes
  *    and dims the rest. */
case class SchematicSettings(
  colorizeComponents: Boolean = false,
  colorizeWires: Boolean = false,
  density: String = "comfortable",
  labelSize: String = "normal",
  autoOptimize: Boolean = true,
  hintBar: Boolean = true,
  showLayers: Boolean = false,
  hide: Seq[String] = Nil,
  followCursor: Boolean = true,
)

object SchematicSettings {
  implicit val rw: ReadWriter[SchematicSettings] = macroRW

  val Densities: Seq[String]  = Seq("compact", "comfortable", "comfy")
  val LabelSizes: Seq[String] = Seq("normal", "large", "xl")
  /** The node kinds `hide` may name, as the settings popover's Show section lists them. */
  val Kinds: Seq[String] = Seq("op", "reg", "const", "mem", "fsm", "block")
}
