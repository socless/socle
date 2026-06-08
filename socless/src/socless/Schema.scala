package socless

import upickle.default._

/** SoCLess design-export schema.
  *
  * These case classes are the canonical JSON contract between the exporter
  * and the UI. The UI's `data.js` shape mirrors this — any field added here
  * must also be consumed (or tolerated as `null`) on the JS side.
  *
  * Field naming follows the JSON conventions of the existing UI: camelCase,
  * `type` is keyed as `"type"`, `children`/`wires`/`ports` are arrays.
  */
case class Design(
  top:     String,
  bundles: Map[String, BundleDef],
  modules: Seq[ModuleDef],
  // Source text keyed by file path (as written in module/port/wire SourceRefs).
  // Filled in by the exporter when a source-root is configured; the UI slices
  // out the relevant lines per module. Empty if files weren't readable or
  // source embedding was disabled.
  sources: Map[String, String] = Map.empty,
  // Baked schematic geometry, keyed by module name. Empty when layout was
  // skipped or failed; the UI falls back to its built-in layout in that case.
  layouts: Map[String, ModuleLayout] = Map.empty,
)
object Design { implicit val rw: ReadWriter[Design] = macroRW }

// ── Modules ─────────────────────────────────────────────────────────────────

case class ModuleDef(
  name:     String,
  `type`:   String,
  doc:      String,
  source:   SourceRef,
  ports:    Seq[PortDef],
  children: Seq[InstanceDef],
  wires:    Seq[WireDef],
  metrics:  Metrics,
)
object ModuleDef { implicit val rw: ReadWriter[ModuleDef] = macroRW }

case class PortDef(
  name:   String,
  dir:    String,      // "master" | "slave" | "in" | "out" | "inout"
  bundle: String,      // key into Design.bundles, or a primitive like "UInt<8>"
  doc:    String,
  source: SourceRef,
)
object PortDef { implicit val rw: ReadWriter[PortDef] = macroRW }

case class InstanceDef(
  name:   String,      // instance name as written in Scala (e.g. "cpu")
  `type`: String,      // module class name
  doc:    String,
  source: SourceRef,
)
object InstanceDef { implicit val rw: ReadWriter[InstanceDef] = macroRW }

case class WireDef(
  from:   String,      // dotted path: "cpu.io" or "xbar.in[0].ar"
  to:     String,
  bundle: String,      // bundle type of the wire (key into Design.bundles)
  label:  String,
  source: SourceRef,
)
object WireDef { implicit val rw: ReadWriter[WireDef] = macroRW }

case class Metrics(
  registers: Int,
  wires:     Int,
  lines:     Int,
  gates:     Option[Int] = None,   // filled by synthesis post-pass
)
object Metrics { implicit val rw: ReadWriter[Metrics] = macroRW }

// ── Bundles ─────────────────────────────────────────────────────────────────

/** A bundle definition. Two kinds for the UI:
  *
  *   - `"Bundle"`     — aggregate of named channels (e.g. AXI4)
  *   - `"Decoupled"`  — handshake (valid/ready + bits sub-bundle)
  *   - `"Aggregate"`  — any other Bundle (catch-all)
  *
  * Decoupled is detected by structural match (has `valid`/`ready`/`bits`) so
  * the UI can render the handshake annotation specially.
  */
case class BundleDef(
  name:     String,
  kind:     String,                       // "Bundle" | "Decoupled" | "Aggregate"
  fields:   Seq[FieldDef]    = Nil,       // ground-typed fields (Decoupled/leaf)
  channels: Seq[ChannelDef]  = Nil,       // sub-bundles (Bundle kind)
  source:   SourceRef        = SourceRef.unknown,
)
object BundleDef { implicit val rw: ReadWriter[BundleDef] = macroRW }

case class FieldDef(
  name:  String,
  width: Int,
  dir:   String,         // "out" (from producer) | "in"
)
object FieldDef {
  // Tuple-style writer to keep JSON terse: ["addr", 32, "out"]
  implicit val rw: ReadWriter[FieldDef] =
    readwriter[(String, Int, String)]
      .bimap[FieldDef](
        f => (f.name, f.width, f.dir),
        { case (n, w, d) => FieldDef(n, w, d) },
      )
}

case class ChannelDef(
  name: String,
  `type`: String,        // key into Design.bundles
  flip: Boolean,
)
object ChannelDef { implicit val rw: ReadWriter[ChannelDef] = macroRW }

// ── Baked schematic layout ───────────────────────────────────────────────────

/** One module's schematic geometry: instance/boundary boxes and routed wires.
  * Coordinates are absolute within a `w` x `h` canvas. Produced by the Scala
  * `Layout` object (ELK), consumed verbatim by the UI renderer.
  */
case class ModuleLayout(nodes: Seq[LaidNode], edges: Seq[RoutedEdge], w: Int, h: Int)
object ModuleLayout { implicit val rw: ReadWriter[ModuleLayout] = macroRW }

/** A laid-out box. `kind` is "inst" (a child instance, drilled/expandable) or
  * "boundary" (one of this module's own ports, drawn as a port stub). `id` is
  * the instance name for "inst", or the bare port name for "boundary".
  */
case class LaidNode(
  id: String, kind: String,   // "inst" | "boundary"
  x: Int, y: Int, w: Int, h: Int, ports: Seq[LaidPort],
)
object LaidNode { implicit val rw: ReadWriter[LaidNode] = macroRW }

/** A pin on a box. `path` is the coalesced endpoint path it represents
  * (e.g. "cpu.io", "xbar.in[0].ar", "s_axi"); `side` is "WEST"/"EAST"; and
  * `interface` is true when the pin carries an aggregate bundle (drawn fat).
  */
case class LaidPort(
  path: String, x: Int, y: Int,
  side: String,               // "WEST" | "EAST"
  interface: Boolean,
)
object LaidPort { implicit val rw: ReadWriter[LaidPort] = macroRW }

/** A routed wire as an absolute-coordinate polyline. `from`/`to` are the
  * coalesced endpoint paths (matching `WireDef.from`/`.to`). `points` includes
  * the start, every bend, and the end.
  */
case class RoutedEdge(from: String, to: String, points: Seq[(Int, Int)], label: String = "")
object RoutedEdge {
  implicit val rw: ReadWriter[RoutedEdge] = macroRW
}

// ── Source info ─────────────────────────────────────────────────────────────

case class SourceRef(file: String, line: Int, col: Int)
object SourceRef {
  val unknown = SourceRef("", 0, 0)
  implicit val rw: ReadWriter[SourceRef] = macroRW
}
