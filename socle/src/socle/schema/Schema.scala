package socle.schema

import upickle.default._

/** Closed string enumerations carried in the JSON schema, centralized so code
  * compares against named constants instead of scattered string literals that a
  * typo would silently break (issue #218). The string VALUES are the wire
  * contract with the UI and must not change - only the call sites route through
  * these names. Shared across the backend pipeline, the layout engine, and (via
  * the cross-compiled schema sources) the Scala.js UI. */
object NodeKind {
  /** A child-module instance box (`LaidNode.kind`). */
  val Inst     = "inst"
  /** A top-level IO boundary stub (`LaidNode.kind`). */
  val Boundary = "boundary"
  /** Register node (`LogicNodeDef.kind` / `LaidNode.kind`, issue #188). */
  val Reg      = "reg"
  /** Lifted literal-constant node. */
  val Const    = "const"
  /** Combinational logic-op node (mux / add / and / ...). */
  val Op       = "op"
  /** Collapsed FSM region box (issue #210). */
  val Fsm      = "fsm"
  /** Generator or logic block: a collapsed group of logic, laid out like an FSM box. */
  val Block    = "block"
  /** Memory node: a cmem/smem with read/write access ports (issue #205). */
  val Mem      = "mem"

  /** A box with named pins and a body in `Design.regions`: an FSM or a block. */
  def isBox(kind: String): Boolean = kind == Fsm || kind == Block
}

/** Schematic pin side (`LaidPort.side` / the layout engine's pin placement). */
object PinSide {
  val West = "WEST"
  val East = "EAST"
}

/** Canonical port/pin direction in the schema (`PortDef.dir`, `FieldDef.dir`,
  * `BundleTree.PortNode.dir`). The CHIRRTL `input`/`output` keyword is converted
  * to `In`/`Out` at exactly one boundary (`IrWalk.portRole`); everything
  * downstream sees only these values. `Master`/`Slave` are the AXI directional
  * roles surfaced for whole-bundle ports; `Inout` is a reused bidirectional pin. */
object PortDir {
  val In     = "in"
  val Out    = "out"
  val Inout  = "inout"
  val Master = "master"
  val Slave  = "slave"
}

/** Module kind (`ModuleDef.kind`): a normal module with internals, an
  * extmodule/blackbox with none, or an intmodule/intrinsic (issue #20). */
object ModuleKind {
  val Internal  = "internal"
  val External  = "external"
  val Intrinsic = "intrinsic"
}

/** Bundle kind (`BundleDef.kind`): a Decoupled handshake (valid/ready + bits),
  * or a generic Bundle of named channels. */
object BundleKind {
  val Bundle    = "Bundle"
  val Decoupled = "Decoupled"
}

/** Socle design-export schema.
  *
  * These case classes are the canonical JSON contract between the exporter and
  * the `socle-ui` frontend, which reads `design.json` into this typed `Design`
  * via the shared upickle ReadWriters. Any field added here must be
  * backward-compatible (a trailing default so older `design.json` still parses).
  *
  * Field naming follows the JSON conventions: camelCase, `type` is keyed as
  * `"type"`, `children`/`wires`/`ports` are arrays.
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
  // Baked schematic geometry. Keyed by a layout key (the representative module
  // of a group of structurally-identical modules), not necessarily every module
  // name - see `layoutOf`. Empty when layout was skipped or failed; the UI has
  // NO built-in layout fallback: it shows an explicit "no layout data" notice
  // for affected modules, so re-export with a current socle to get geometry
  // (issue #96).
  layouts: Map[String, ModuleLayout] = Map.empty,
  // Maps every module name to the key in `layouts` that holds its geometry.
  // Chisel emits many structurally-identical module copies (X, X_1, X_2, ...);
  // their layouts are byte-identical, so each unique layout is stored once and
  // every copy points at it, keeping design.json small on large SoCs (issue #31).
  // Empty for designs emitted before dedup; the UI then falls back to the module
  // name as the key.
  layoutOf: Map[String, String] = Map.empty,
  // Header/port-row layout constants, emitted so the UI reads them from the
  // design instead of hardcoding a second copy (issue #38). The trailing
  // default keeps older design.json (without this block) loadable; the
  // serializeDefaults annotation forces it to always be written even though it
  // usually equals the default, so the UI can rely on the field being present.
  @upickle.implicits.serializeDefaults(true)
  layoutConsts: LayoutConsts = LayoutConsts(28, 24, 18),
  // Absolute workspace root the relative SourceRef paths resolve against, as
  // seen on the exporting machine. Lets the UI build open-in-editor links
  // (vscode://file/..., issue #123). Empty when no source root was
  // configured; consumers on other machines simply get dead links
  // (best-effort by design). Additive: older design.json omits it. Kept LAST
  // so positional Design(...) constructions stay source-compatible.
  sourceRoot: String = "",
  // Non-fatal issues raised while parsing CHIRRTL / walking the IR: skipped
  // constructs (partial connects, attach, ...), unparsed lines, unresolved
  // wire endpoints. Empty for a clean export. The UI surfaces them in a
  // dismissible banner so "missing wire vs parser drop" is self-diagnosing on
  // large designs (issue #118). Additive, last: older design.json omits it.
  warnings: Seq[Warning] = Seq.empty,
  // Stored bodies of collapsed control-flow regions (issue #210): the internal
  // logic of a recognized FSM, keyed by the fsm node's id. Kept OUT of
  // `modules`, so regions never appear in the hierarchy tree, module picker, or
  // metrics. Not rendered yet (the FSM box is collapsed/non-drillable in this
  // slice); stored so a future drill-in view is pure UI. Additive, last.
  regions: Map[String, ModuleDef] = Map.empty,
  // Draw every port of the viewed module as a boundary pin, even when no wire
  // ends on it. Set only for probe-captured designs (ModelToDesign), whose
  // wiring keeps port-to-port wires only, so a port fed through internal logic
  // would otherwise vanish from the schematic. Additive, last.
  showUnwiredPorts: Boolean = false,
  // The pin model the baked `layouts` follow (`LayoutCore.LayoutModel`): 0 for
  // an older design.json, whose pins were wire ends rather than declared port
  // rows (#332); the UI lays such a bake out again. Additive, last.
  layoutModel: Int = 0,
  // The baked `layouts` were made with the optimize pass (the UI's default,
  // auto-optimize), so a first view needs no browser re-layout (#335).
  // Additive, last.
  layoutOptimized: Boolean = false,
)
object Design { implicit val rw: ReadWriter[Design] = macroRW }

/** A non-fatal export warning (issue #118). `kind` is a stable machine tag
  * (e.g. "skipped-partial-connect", "unparsed-line", "unresolved-endpoint"),
  * `count` how many times it occurred, `message` a human-readable summary,
  * and `module` the module it was localized to when known (else "").
  */
case class Warning(kind: String, count: Int, message: String, module: String = "")
object Warning { implicit val rw: ReadWriter[Warning] = macroRW }

/** Header/port-row geometry shared between the Scala backend ([[Layout]]) and
  * the UI. The numeric source of truth lives in [[Layout]]; this struct just
  * carries the values across into design.json (issue #38).
  */
case class LayoutConsts(headerH: Double, portStep: Double, portPad: Double)
object LayoutConsts { implicit val rw: ReadWriter[LayoutConsts] = macroRW }

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
  // Module kind: "internal" (normal module with internals), "external"
  // (extmodule / blackbox, no internals), or "intrinsic" (intmodule).
  // The UI renders external/intrinsic distinctly and skips drilldown (issue
  // #20). Trailing default keeps older design.json loadable.
  kind:     String = ModuleKind.Internal,
  // Elaboration parameters that define this module's behavior (issue #19).
  // CHIRRTL does NOT carry Chisel constructor parameters (they're erased by
  // emission), so this is empty from the CHIRRTL path today; the field is the
  // UI contract for a future elaboration-time source to populate. Default-empty
  // map is omitted, so goldens are unchanged.
  parameters: Map[String, String] = Map.empty,
  // Intra-module logic nodes (registers, constants) made visible in the
  // schematic (issue #188 B1). Trailing default keeps older design.json loadable.
  logic: Seq[LogicNodeDef] = Seq.empty,
  // Generator class ("gcd.GCD") when the design was captured from elaborated
  // Chisel objects by the probe; empty from the CHIRRTL path. Additive, last.
  scalaClass: String = "",
  // The generator's constructor parameters in declaration order, with their
  // declared Scala type (`parameters` is an unordered name -> value map and
  // keeps no type). From a probe capture; empty from the CHIRRTL path.
  // Additive, last.
  parameterDecls: Seq[ParamDef] = Seq.empty,
  // Constant tie-offs dropped from `logic` and `wires` (probe capture only:
  // a const whose every wire ends on a port or an instance pin). The UI marks
  // each tied pin with a value stub. Empty from the CHIRRTL path. Additive, last.
  tieOffs: Seq[TieOff] = Seq.empty,
  // Named internal signals (registers, wires, named nodes) in declaration
  // order, aggregates expanded to ground leaves (`name.field`, `name[i]`),
  // as they appear in the CHIRRTL (probe capture only; empty from the CHIRRTL
  // path). Lets a waveform follow the schematic. Additive, last.
  signals: Seq[SignalDef] = Seq.empty,
)
object ModuleDef { implicit val rw: ReadWriter[ModuleDef] = macroRW }

/** A named internal signal of a module. `kind` is "reg" or "wire" (a wire or a
  * named node); `width` is the declared ground width, 0 when not declared (a node). */
case class SignalDef(name: String, kind: String, width: Int, source: SourceRef)
object SignalDef { implicit val rw: ReadWriter[SignalDef] = macroRW }

/** A port or instance pin tied to a constant: `sink` is the tied endpoint path
  * (a wire's `from`, e.g. "alu.io.op"), `value` the constant as displayed ("0"). */
case class TieOff(sink: String, value: String)
object TieOff { implicit val rw: ReadWriter[TieOff] = macroRW }

/** One constructor parameter of a captured generator: its name, its declared
  * Scala type ("Int", "T") and its value as read at elaboration ("4",
  * "<unreadable>", or a long value cut with "...(truncated)"). */
case class ParamDef(name: String, tpe: String, value: String)
object ParamDef { implicit val rw: ReadWriter[ParamDef] = macroRW }

case class PortDef(
  name:   String,
  dir:    String,      // PortDir: In | Out | Inout | Master | Slave
  bundle: String,      // key into Design.bundles, or a primitive like "UInt<8>"
  doc:    String,
  source: SourceRef,
  // True when the port appears in at least one connect in the source (to another
  // port, internal logic, or a constant). False only when the port is genuinely
  // unconnected - the UI flags those so a port is never shown as unconnected
  // unless it truly is (issue #74). Default true keeps existing JSON terse.
  connected: Boolean = true,
  // How the port is shown when its name is not its source name: a logic
  // block's pin is named `io_in_bits_a` (paths address it as `<block>.<pin>`)
  // and labelled `io.in.bits.a` (#333). Empty: shown by `name`. Additive, last.
  label: String = "",
)
object PortDef { implicit val rw: ReadWriter[PortDef] = macroRW }

case class InstanceDef(
  name:   String,      // instance name as written in Scala (e.g. "cpu")
  `type`: String,      // module class name
  doc:    String,
  source: SourceRef,
  // The signal in the PARENT that drives this instance's clock / reset port,
  // inferred from connect connectivity (issue #19). Two sibling instances with
  // the same `clock` string share a clock domain; the UI highlights them. Empty
  // when the instance has no clock/reset port, the driver couldn't be resolved,
  // or for blackboxes. Additive + default-omitted, so only clocked instances
  // change the goldens. Kept LAST so positional constructions stay compatible.
  clock:  String = "",
  reset:  String = "",
  // The instantiated module's constructor parameters (name -> value, or
  // "<unreadable>"), from a probe capture; empty from the CHIRRTL path.
  // Additive, last.
  params: Map[String, String] = Map.empty,
  // Generator-copy fold group id (Seq.fill(n)(Module(...))), set only by
  // ModelToDesign on each member of a group; empty otherwise. Additive, last.
  foldGroup: String = "",
  // > 0 only on the synthetic instance standing for a folded group: the number
  // of copies it replaces. Additive, last.
  foldCount: Int = 0,
)
object InstanceDef { implicit val rw: ReadWriter[InstanceDef] = macroRW }

/** One access port of a memory node (issue #205). `tag` is the UI role label
  * ("R0", "W0", "RW0"); `dir` is "read" | "write" | "rdwr"; `name` is the real
  * CHIRRTL mport name; `source` is the access (mport) line. */
case class MemPortDef(tag: String, dir: String, name: String, source: SourceRef)
object MemPortDef { implicit val rw: ReadWriter[MemPortDef] = macroRW }

/** Geometry + ports of a memory node, for the inspector and pin labels
  * (issue #205). `depth` is the element count, `elem` the rendered element
  * type ("UInt<32>"), `sync` true for SyncReadMem (smem). */
case class MemInfo(depth: Int, elem: String, sync: Boolean, ports: Seq[MemPortDef])
object MemInfo { implicit val rw: ReadWriter[MemInfo] = macroRW }

/** A non-module schematic node: intra-module glue made visible (issue #188).
  * `kind` is "reg" (a register), "const" (an explicit literal driver), "op"
  * (a combinational operation), or "fsm" (a recognized state machine collapsed
  * to one box; its body lives in `Design.regions`, issue #210). `id` is unique within the module (the register
  * name, a synthetic const id, or a synthetic op id). `label` is what the UI
  * shows. `bundle` is the registered type's key for a "reg" (for pin
  * expansion); "" for "const"/"op". */
case class LogicNodeDef(
  id:     String,
  kind:   String,      // NodeKind: Reg | Const | Op | Fsm | Mem
  label:  String,
  bundle: String,
  source: SourceRef,
  // For kind "op": the CHIRRTL primop ("mux", "add", "lt", ...). Empty for
  // reg/const. Trailing default keeps older design.json loadable (issue #188 B2).
  op: String = "",
  // True when this node is Chisel verification-layer logic (assert/assume/cover
  // condition, issue #212): the UI hides it unless "show layers" is on. Default
  // false is omitted by upickle, so only layer nodes carry it in design.json.
  layer: Boolean = false,
  // For kind "mem" (issue #205): the memory's geometry and access ports, used
  // for pin labels + the inspector. None for every other kind. Trailing default
  // keeps older design.json loadable.
  memInfo: Option[MemInfo] = None,
  // For kind "fsm" in the IDE view: the state diagram the UI opens. None for
  // every other node and in the CHIRRTL view. Trailing default keeps older
  // design.json loadable.
  fsm: Option[FsmDiagram] = None,
  // For kind "fsm": its state register. Empty for every other node. Trailing
  // default keeps older design.json loadable.
  state: String = "",
)
object LogicNodeDef { implicit val rw: ReadWriter[LogicNodeDef] = macroRW }

/** A state machine's diagram: its states and the transitions between them.
  * `note` explains what the diagram leaves out ("" when nothing). */
case class FsmDiagram(states: Seq[FsmState], transitions: Seq[FsmTransition], note: String = "")
object FsmDiagram { implicit val rw: ReadWriter[FsmDiagram] = macroRW }

/** One state; `source` is its first `is(...)` case, unknown when none.
  * `value` is its encoding in the state register, in decimal ("" when it is
  * not known): the waveform viewer shows the name for it. */
case class FsmState(name: String, initial: Boolean = false, source: SourceRef = SourceRef.unknown, value: String = "")
object FsmState { implicit val rw: ReadWriter[FsmState] = macroRW }

/** One transition. `from == "*"` means from any state (an assignment outside
  * the `switch`); `label` is its condition, "" when unconditional. */
case class FsmTransition(from: String, to: String, label: String, source: SourceRef)
object FsmTransition { implicit val rw: ReadWriter[FsmTransition] = macroRW }

case class WireDef(
  from:   String,      // dotted path: "cpu.io" or "xbar.in[0].ar"
  to:     String,
  bundle: String,      // bundle type of the wire (key into Design.bundles)
  label:  String,
  source: SourceRef,
  // True when this wire is one scattered ground field of a bundle whose fields
  // reach different destinations (issue #182 Part A), rather than a coalesced
  // whole-bundle wire. The UI styles these distinctly. Trailing default keeps
  // older design.json parseable and non-split wires terse.
  fieldLevel: Boolean = false,
)
object WireDef { implicit val rw: ReadWriter[WireDef] = macroRW }

case class Metrics(
  registers: Int,
  wires:     Int,
  lines:     Int,
  gates:     Option[Int] = None,   // filled by synthesis post-pass
  memories:  Int         = 0,      // count of cmem/smem/mem declarations in this module
)
object Metrics { implicit val rw: ReadWriter[Metrics] = macroRW }

// ── Bundles ─────────────────────────────────────────────────────────────────

/** A bundle definition. Two kinds for the UI:
  *
  *   - `"Bundle"`     - aggregate of named channels (e.g. AXI4)
  *   - `"Decoupled"`  - handshake (valid/ready + bits sub-bundle)
  *
  * Decoupled is detected by structural match (has `valid`/`ready`/`bits`) so
  * the UI can render the handshake annotation specially.
  */
case class BundleDef(
  name:     String,
  kind:     String,                       // BundleKind: Bundle | Decoupled
  fields:   Seq[FieldDef]    = Nil,       // ground-typed fields (Decoupled/leaf)
  channels: Seq[ChannelDef]  = Nil,       // sub-bundles (Bundle kind)
  source:   SourceRef        = SourceRef.unknown,
  // Chisel class of the bundle ("chisel3.util.DecoupledIO") from a probe
  // capture; empty from the CHIRRTL path. Additive, last.
  scalaClass: String         = "",
)
object BundleDef { implicit val rw: ReadWriter[BundleDef] = macroRW }

case class FieldDef(
  name:  String,
  width: Int,
  dir:   String,         // PortDir: Out (from producer) | In
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
case class ModuleLayout(nodes: Seq[LaidNode], edges: Seq[RoutedEdge], w: Int, h: Int,
  // Verification-layer content laid out apart (a band below the design):
  // `debugY` is the band's top (0: no band), `mainW` the design's own width, so
  // with layers hidden the view is the design alone. Additive, last.
  debugY: Int = 0, mainW: Int = 0)
object ModuleLayout { implicit val rw: ReadWriter[ModuleLayout] = macroRW }

/** A laid-out box. `kind` is "inst" (a child instance, drilled/expandable),
  * "boundary" (one of this module's own ports, drawn as a port stub),
  * "reg" (a register node, issue #188), or "const" (a literal constant node,
  * issue #188). `id` is the instance name for "inst"; for "boundary" it is
  * the FULL endpoint path (e.g. "io.in[0]"), since issue #68 each external
  * pin is its own stub.
  */
case class LaidNode(
  id: String, kind: String,   // NodeKind: Inst | Boundary | Reg | Const | Op | Fsm | Mem
  x: Int, y: Int, w: Int, h: Int, ports: Seq[LaidPort],
)
object LaidNode { implicit val rw: ReadWriter[LaidNode] = macroRW }

/** A pin on a box. `path` is the coalesced endpoint path it represents
  * (e.g. "cpu.io", "xbar.in[0].ar", "s_axi"); `side` is "WEST"/"EAST";
  * `interface` is true when the pin carries an aggregate bundle (drawn fat);
  * `label` is the display text rendered inside the box next to the pin; and
  * `bundle` is the bundle key (into Design.bundles) for this pin, enabling the
  * UI to walk the bundle tree for expand/collapse (issue #29). Trailing default
  * keeps existing positional constructions valid.
  */
case class LaidPort(
  path: String, x: Int, y: Int,
  side: String,               // "WEST" | "EAST"
  interface: Boolean,
  label: String = "",
  bundle: String = "",        // bundle key; "" for scalar/unknown pins
  expandable: Boolean = false, // bundle/Vec the UI can expand in-box (issue #170)
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
