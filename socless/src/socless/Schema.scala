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

// ── Source info ─────────────────────────────────────────────────────────────

case class SourceRef(file: String, line: Int, col: Int)
object SourceRef {
  val unknown = SourceRef("", 0, 0)
  implicit val rw: ReadWriter[SourceRef] = macroRW
}
