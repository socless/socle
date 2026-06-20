package socless.layout

import socless.schema._
import scala.collection.mutable

/** Pure, engine-independent schematic layout logic (issue #167).
  *
  * Everything here is free of the Eclipse Layout Kernel Java API, so it
  * cross-compiles to Scala.js and is shared by both the JVM backend
  * ([[Layout]], which feeds it to ELK) and the in-browser layout
  * (`socless-js`, which feeds it to elkjs). Keeping the wire-endpoint
  * resolution, pin-side voting, node/port geometry and anti-parallel edge
  * collapse in one place means the two engines cannot drift.
  *
  * [[prepare]] is the seam: it turns a module + design into a fully resolved
  * [[Prepared]] graph (nodes with sizes and per-pin coordinates, edges oriented
  * source->sink). The only thing left for a concrete engine to do is build its
  * own node/port/edge objects from this and run orthogonal routing.
  */
object LayoutCore {

  // ── Header / port-row geometry (single source of truth, emitted via consts) ──
  val NodeW: Double    = 160.0
  // Logic-op / register nodes render as a compact centered symbol (issue #203),
  // not a header+label box, so they get a small fixed width and the symbol's
  // stubs reach the WEST/EAST pins closely instead of crossing a wide footprint.
  val LogicNodeW: Double = 80.0
  val PortStep: Double = 24.0
  val PortPad: Double  = 18.0
  /** Height of the component header band (name + type). */
  val HeaderH: Double  = 28.0
  /** The header/port-row layout constants, packaged for the schema (issue #38). */
  val consts: LayoutConsts = LayoutConsts(HeaderH, PortStep, PortPad)
  val BoundaryW: Double = 16.0
  val BoundaryH: Double = 28.0
  val LabelGap: Double  = 24.0   // horizontal gap between widest WEST and EAST label columns
  val MaxNodeW: Double  = 520.0  // cap so layout doesn't explode on very long labels

  def stripIdx(s: String): String = {
    val i = s.indexOf('[')
    if (i < 0) s else s.substring(0, i)
  }

  /** A pin sits on the EAST (right/driving) side when its port drives signals
    * rightward in the left-to-right schematic: instance outputs/masters, and
    * this module's own boundary INPUTS (which drive signals into the design).
    * Receiving pins (instance inputs/slaves, boundary outputs) sit on WEST. */
  def sideFor(kind: String, dir: String): String = (kind, dir) match {
    // An FSM box is laid out exactly like an instance: an output pin drives
    // rightward (EAST), everything else receives (WEST) (issue #210). `dir` is the
    // canonical schema PortDir vocabulary (in/out/master/slave/inout).
    case (NodeKind.Inst, PortDir.Out) | (NodeKind.Inst, PortDir.Master) => PinSide.East
    case (NodeKind.Fsm, PortDir.Out)  | (NodeKind.Fsm, PortDir.Master)  => PinSide.East
    // A boundary slave comes from `input io` (AXI-aware portRole), which maps to
    // PortDir.In, so by this file's own convention it drives rightward like any
    // boundary input. Without this case AXI slave boundary stubs fell through to
    // WEST: their anti-parallel traffic always ties the side vote and the
    // tie-break uses this declared side (issue #102).
    case (NodeKind.Boundary, PortDir.In) | (NodeKind.Boundary, PortDir.Slave) => PinSide.East
    case _                                                                     => PinSide.West
  }

  private val VecKey = """^Vec\[(.+),\s*\d+\]$""".r

  def isInterfaceBundle(bundle: String, design: Design): Boolean =
    design.bundles.get(bundle).exists(b => b.kind == BundleKind.Bundle || b.kind == BundleKind.Decoupled) ||
      // Vec-of-bundle keys ("Vec[AXI4, 2]") are not entries in design.bundles
      // but their pins are interfaces all the same (issue #89).
      (bundle match {
        case VecKey(inner) => isInterfaceBundle(inner.trim, design)
        case _             => false
      })

  def nodeHeight(westN: Int, eastN: Int): Double = {
    val maxRows = math.max(1, math.max(westN, eastN))
    math.max(HeaderH + 2 * PortPad + PortStep, HeaderH + 2 * PortPad + maxRows * PortStep)
  }

  /** Estimate rendered pixel width of `text` in the UI's small monospace font. */
  def labelPx(text: String): Double = text.length * 6.5 + 14.0

  private val ClockResetBundles = Set("Clock", "Reset", "AsyncReset")
  def isClockReset(w: WireDef): Boolean = ClockResetBundles.contains(w.bundle)

  /** Compute display label for a pin.
    * shortPath = the endpoint path with the leading "<nodeId>." stripped.
    * If the pin has a non-generic bundle, label = "shortPath: bundle";
    * otherwise label = shortPath. */
  def pinLabel(nodeId: String, path: String, bundle: Option[String]): String = {
    val shortPath = if (path.startsWith(nodeId + ".")) path.drop(nodeId.length + 1) else path
    bundle match {
      case Some(b) if b.nonEmpty && b != "Wire" => s"$shortPath: $b"
      case _                                     => shortPath
    }
  }

  /** A resolved wire endpoint. `nodeId` is the box that owns the pin (instance
    * name, or the FULL endpoint path for a boundary stub: since issue #68 each
    * external pin is its own per-path boundary node), `kind` is "inst" or
    * "boundary", `path` is the full endpoint path used as the pin id, `side`
    * is "WEST"/"EAST", and `interface` flags aggregate bundles. `bundle` is the
    * pin's own-port bundle key (the instance/boundary port's declared type, or a
    * register's registered type), or empty for bundleless logic nodes
    * (const/op/fsm). */
  case class Endpoint(nodeId: String, kind: String, path: String, side: String,
                      interface: Boolean, bundle: String)

  /** Resolve `path` against `module`. Returns None for internal nets / unknown
    * heads (those endpoints are dropped, as in IrWalk). */
  def classify(path: String, module: ModuleDef, design: Design): Option[Endpoint] =
    classifyWith(path, module, design, design.modules.iterator.map(m => m.name -> m).toMap)

  def classifyWith(path: String, module: ModuleDef, design: Design, modulesByName: Map[String, ModuleDef]): Option[Endpoint] = {
    val parts = path.split('.').toIndexedSeq
    if (parts.isEmpty) return None
    val head = stripIdx(parts.head)
    val instOf = module.children.find(_.name == head)
    if (instOf.isDefined && parts.length >= 2) {
      val childMod = modulesByName.get(instOf.get.`type`)
      val portName = stripIdx(parts(1))
      val portDef  = childMod.flatMap(_.ports.find(_.name == portName))
      val dir      = portDef.map(_.dir).getOrElse(PortDir.In)
      val bundle   = portDef.map(_.bundle).getOrElse("Wire")
      Some(Endpoint(head, NodeKind.Inst, path, sideFor(NodeKind.Inst, dir), isInterfaceBundle(bundle, design), bundle))
    } else {
      // Intra-module logic nodes: registers (a D input pin `r`/`r.field` on WEST
      // and a Q output pin `r~q`/`r~q.field` on EAST) and constants (single EAST
      // driver pin keyed by the whole `const$...` atom). These resolve before the
      // boundary-port fallthrough so a logic node is never mistaken for a port and
      // is never dropped (issue #188 B1).
      val regIds   = module.logic.iterator.filter(_.kind == NodeKind.Reg).map(_.id).toSet
      val constIds = module.logic.iterator.filter(_.kind == NodeKind.Const).map(_.id).toSet
      val opIds    = module.logic.iterator.filter(_.kind == NodeKind.Op).map(_.id).toSet
      // An FSM box has named boundary pins arriving as `<fsmId>.<pin>`, exactly
      // like an instance: the head segment is the box id. Resolve it before the
      // boundary-port fallthrough so the pins are never mistaken for ports or
      // dropped (issue #210).
      val fsmIds   = module.logic.iterator.filter(_.kind == NodeKind.Fsm).map(_.id).toSet
      def regIface(id: String): Boolean =
        module.logic.find(n => n.id == id && n.kind == NodeKind.Reg).exists(n => isInterfaceBundle(n.bundle, design))
      def regBundle(id: String): String =
        module.logic.find(n => n.id == id && n.kind == NodeKind.Reg).map(_.bundle).getOrElse("")
      val rawHead = stripIdx(parts.head)
      if (rawHead.endsWith("~q") && regIds.contains(rawHead.dropRight(2)))
        return Some(Endpoint(rawHead.dropRight(2), NodeKind.Reg, path, PinSide.East, regIface(rawHead.dropRight(2)), regBundle(rawHead.dropRight(2))))
      if (regIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Reg, path, PinSide.West, regIface(rawHead), regBundle(rawHead)))
      if (constIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Const, path, PinSide.East, false, ""))
      val inIdx = rawHead.indexOf("~in")
      if (inIdx > 0 && opIds.contains(rawHead.substring(0, inIdx)))
        return Some(Endpoint(rawHead.substring(0, inIdx), NodeKind.Op, path, PinSide.West, false, ""))
      if (opIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Op, path, PinSide.East, false, ""))
      if (fsmIds.contains(rawHead) && parts.length >= 2) {
        // Box pin direction lives in the stored region body's port list. An out
        // pin drives rightward (EAST), an in/inout pin receives (WEST); the
        // dataflow vote in `prepare` refines this, this is just the declared side.
        val pinName = stripIdx(parts(1))
        val dir     = design.regions.get(rawHead).flatMap(_.ports.find(_.name == pinName)).map(_.dir).getOrElse(PortDir.In)
        // FSM box pins carry no bundle: the synthesized region ports store no
        // bundle key, so a pin reflects its own (bundleless) port rather than
        // the wire's aggregate type (#237).
        return Some(Endpoint(rawHead, NodeKind.Fsm, path, sideFor(NodeKind.Fsm, dir), false, ""))
      }
      module.ports.find(_.name == head).map { p =>
        // Each external pin is its OWN boundary node (keyed by full path), not
        // grouped under the port head. A single shared node would force all of
        // a module's I/O into one layer, so inputs could not sit on the left
        // and outputs on the right; wires then route backward or span the whole
        // canvas (issue #68). Per-pin stubs let ELK rank input-sources left and
        // output-sinks right with forward-flowing wires.
        Endpoint(path, NodeKind.Boundary, path, sideFor(NodeKind.Boundary, p.dir), isInterfaceBundle(p.bundle, design), p.bundle)
      }
    }
  }

  // ── Prepared graph: the engine-agnostic layout input ────────────────────────

  /** One pin on a [[PreparedNode]]. For instance nodes `x`/`y` are the explicit
    * FIXED_POS coordinates (relative to the node box). For boundary stubs the
    * engine places the pin from `side`, so `x`/`y` are unused (0). `label` and
    * `bundle` carry through to the laid-out port. */
  case class PreparedPin(path: String, side: String, interface: Boolean, bundle: String,
                         label: String, x: Double, y: Double, expandable: Boolean)

  /** One box: an instance component (`kind == NodeKind.Inst`, ports at fixed rows)
    * or a boundary stub (`kind == NodeKind.Boundary`, ports placed on their side).
    *
    * `layerConstraint` pins boundary stubs into a dedicated first/last ELK layer
    * so all external inputs align into one leftmost column and all outputs into
    * one rightmost column, neither sharing a column with an instance (issue #163).
    * The value is an ELK `LayerConstraint` name ("FIRST_SEPARATE" /
    * "LAST_SEPARATE"), applied by each engine. The SEPARATE variants (not plain
    * FIRST/LAST) are required: a bidirectional boundary pin (e.g. a Decoupled
    * output carrying `ready` back) has a "wrong-direction" edge that plain
    * FIRST/LAST reject; SEPARATE tolerates it. None = unconstrained (instances
    * rank by dataflow). */
  case class PreparedNode(id: String, kind: String, width: Double, height: Double,
                          pins: Seq[PreparedPin], layerConstraint: Option[String] = None)

  /** One edge oriented source->sink. `toPath` is the SOURCE pin (drives), and
    * `fromPath` is the SINK pin, matching [[WireDef]] semantics (from = sink,
    * to = source). `label` is the merged label of its anti-parallel group. */
  case class PreparedEdge(fromPath: String, toPath: String, label: String)

  case class Prepared(nodes: Seq[PreparedNode], edges: Seq[PreparedEdge])

  /** Resolve a module into an engine-agnostic graph: nodes with sizes + per-pin
    * coordinates, and anti-parallel-collapsed edges. This is the entire pure
    * pipeline that used to live inline in [[Layout.layout]] before the ELK
    * graph was built; both the JVM (ELK) and browser (elkjs) engines consume
    * it identically. */
  def prepare(module: ModuleDef, design: Design): Prepared = {
    val modulesByName = design.modules.iterator.map(m => m.name -> m).toMap

    // 1. Resolve every wire endpoint; keep only wires whose BOTH ends resolve.
    //    Drop clock/reset wires before resolution so their boundary stubs are
    //    also excluded from the layout.
    case class E(w: WireDef, from: Endpoint, to: Endpoint)
    val resolved: Seq[E] = module.wires.filterNot(isClockReset).flatMap { w =>
      for { f <- classifyWith(w.from, module, design, modulesByName); t <- classifyWith(w.to, module, design, modulesByName) }
        yield E(w, f, t)
    }

    // 2. Group pins per node (distinct path), preserving first-seen order. Pin
    //    side is determined by dataflow role: SOURCE (e.to) votes EAST, SINK
    //    (e.from) votes WEST; a pin voted both ways resolves EAST on >= tie.
    case class PinSpec(path: String, side: String, interface: Boolean, bundle: String)
    type PinMap = mutable.LinkedHashMap[String, PinSpec]

    val eastVotes  = mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val westVotes  = mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val ifaceOf    = mutable.Map.empty[(String, String, String), Boolean].withDefaultValue(false)
    val bundleOf   = mutable.Map.empty[(String, String, String), String]
    val declaredOf = mutable.Map.empty[(String, String, String), String]
    val pinOrder   = mutable.LinkedHashMap.empty[(String, String), mutable.LinkedHashSet[String]]

    def vote(ep: Endpoint, isSource: Boolean): Unit = {
      val k = (ep.nodeId, ep.kind, ep.path)
      if (isSource) eastVotes(k) += 1 else westVotes(k) += 1
      ifaceOf(k) = ifaceOf(k) || ep.interface
      declaredOf(k) = ep.side
      if (!bundleOf.contains(k) && ep.bundle.nonEmpty && ep.bundle != "Wire")
        bundleOf(k) = ep.bundle
      pinOrder.getOrElseUpdate((ep.nodeId, ep.kind), mutable.LinkedHashSet.empty) += ep.path
    }
    // e.to is SOURCE; e.from is SINK.
    resolved.foreach { e =>
      vote(e.to,   isSource = true)
      vote(e.from, isSource = false)
    }
    // Ensure instances with no resolved wires still appear as empty boxes.
    module.children.foreach { c =>
      pinOrder.getOrElseUpdate((c.name, NodeKind.Inst), mutable.LinkedHashSet.empty)
    }

    // Build nodePins from the vote tallies.
    val nodePins = mutable.LinkedHashMap.empty[(String, String), PinMap]
    pinOrder.foreach { case ((nodeId, kind), paths) =>
      val pins: PinMap = mutable.LinkedHashMap.empty
      paths.foreach { path =>
        val k = (nodeId, kind, path)
        val side =
          if (eastVotes(k) > westVotes(k)) PinSide.East
          else if (westVotes(k) > eastVotes(k)) PinSide.West
          else if (kind == NodeKind.Boundary) declaredOf.getOrElse(k, PinSide.East)
          else PinSide.East
        val bundle = bundleOf.get(k)
        pins(path) = PinSpec(path, side, ifaceOf(k), bundle.getOrElse(""))
      }
      nodePins((nodeId, kind)) = pins
    }

    // 3. Collapse anti-parallel wire pairs (same unordered port pair, opposite
    //    direction). Keep the edge whose source drives EAST by vote; merge the
    //    group's labels so a dropped partner's count does not vanish (issue #102).
    //    Done before node geometry because the per-boundary layer constraint
    //    below depends on the FINAL (collapsed) edge directions.
    def antiParallelKey(e: E): (String, String) = {
      val a = e.to.path   // source
      val b = e.from.path // sink
      if (a <= b) (a, b) else (b, a)
    }
    val edges: Seq[PreparedEdge] = resolved
      .groupBy(antiParallelKey)
      .values
      .map { grp =>
        val kept =
          if (grp.size == 1) grp.head
          else {
            def isEastByVote(ep: Endpoint): Boolean = {
              val k = (ep.nodeId, ep.kind, ep.path)
              eastVotes(k) >= westVotes(k)
            }
            grp.find(e => isEastByVote(e.to))
              .orElse(grp.sortBy(_.to.path).headOption)
              .getOrElse(grp.head)
          }
        val label = grp.map(_.w.label).filter(_.nonEmpty).distinct.mkString(" / ")
        PreparedEdge(kept.from.path, kept.to.path, label)
      }
      .toSeq

    // Pins that drive / receive a collapsed edge. A boundary stub is column-pinned
    // only when it is purely one-directional: ELK rejects a first-layer node with
    // an incoming edge or a last-layer node with an outgoing edge, so a pin that
    // both sends and receives (a Decoupled boundary still carrying `ready`, a
    // fan-in/out) is left unconstrained and ranks naturally (issue #163).
    val edgeSources = edges.iterator.map(_.toPath).toSet
    val edgeSinks   = edges.iterator.map(_.fromPath).toSet

    // 4. Compute node geometry + per-pin coordinates (pure; no engine objects).
    val nodes: Seq[PreparedNode] = nodePins.map { case ((id, kind), pins) =>
      def lblOf(p: PinSpec): String = pinLabel(id, p.path, if (p.bundle.nonEmpty) Some(p.bundle) else None)
      // Const nodes share the boundary stub's compact geometry (a single small
      // driver), but unlike boundaries they are NOT layer-pinned: a const ranks
      // naturally by dataflow next to its sink (issue #188 B1). Reg nodes fall
      // into the instance branch below (header + D/Q port rows).
      val compact = kind == NodeKind.Boundary || kind == NodeKind.Const
      if (compact) {
        // Boundary/const: compact, but at least wide enough for the label (if any).
        // A const shows ONLY its literal value (the logic node's `label`, e.g.
        // `0x0`), never the synthetic `const$...` pin id or its bundle suffix,
        // so size it to that value rather than the pin label (issue #188 follow-up).
        val constLabel =
          if (kind == NodeKind.Const) module.logic.find(_.id == id).map(_.label).filter(_.nonEmpty)
          else None
        val maxLabelPx = constLabel match {
          case Some(v) => labelPx(v)
          case None    => pins.values.map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        }
        val bw = math.max(BoundaryW, maxLabelPx)
        val laidPins = pins.values.toSeq.map { p =>
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), 0.0, 0.0,
            BundleTree.isAggregateType(p.bundle, design))
        }
        // An EAST boundary pin drives into the design (a source): pin it to the
        // first column. A WEST one receives (a sink): pin it to the last column.
        // Skip the constraint for any pin that also carries a back-edge.
        val side        = laidPins.headOption.map(_.side).getOrElse(PinSide.East)
        val paths       = laidPins.map(_.path)
        val anyIncoming = paths.exists(edgeSinks.contains)
        val anyOutgoing = paths.exists(edgeSources.contains)
        // Only true boundary stubs get column-pinned (issue #163); const nodes
        // share the compact box but rank by dataflow, so they stay unconstrained.
        val lc =
          if (kind != NodeKind.Boundary)                  None
          else if (side == PinSide.East && !anyIncoming) Some("FIRST_SEPARATE")
          else if (side == PinSide.West && !anyOutgoing) Some("LAST_SEPARATE")
          else                                     None
        PreparedNode(id, kind, bw, BoundaryH, laidPins, lc)
      } else {
        // Instance component: reserve HeaderH at top, ports in rows below.
        val west = pins.values.count(_.side == PinSide.West)
        val east = pins.values.count(_.side == PinSide.East)
        val westMaxPx = pins.values.filter(_.side == PinSide.West).map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        val eastMaxPx = pins.values.filter(_.side == PinSide.East).map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        // Also ensure header text (instance id) fits: id.length * 6.5 + 24 (glyph + padding)
        val headerTextPx = id.length * 6.5 + 24.0
        // Op/reg logic nodes render as a compact symbol (no header, short or no
        // pin labels), so they use a small fixed width; real instances size to
        // their header + label columns (issue #203).
        val isLogic = kind == NodeKind.Op || kind == NodeKind.Reg
        val w = if (isLogic) LogicNodeW
                else math.min(MaxNodeW, math.max(NodeW, math.max(headerTextPx, westMaxPx + LabelGap + eastMaxPx)))
        val h = nodeHeight(west, east)
        // Independent row counters per side so WEST and EAST ports start at row 0.
        def rowY(r: Double): Double = HeaderH + PortPad + r * PortStep + PortStep / 2
        // An op renders as a symbol whose single output leaves from the vertical
        // center of its DATA inputs; place its EAST pin there so the output is a
        // straight horizontal line and the inputs sit equidistant about it
        // (#230). A mux select (in0) renders on top, so it is excluded from the
        // centering.
        val opName  = if (kind == NodeKind.Op) module.logic.find(_.id == id).map(_.op).getOrElse("") else ""
        val westSeq = pins.values.filter(_.side == PinSide.West).toSeq
        val dataRows = westSeq.zipWithIndex.collect {
          case (p, i) if !(opName == "mux" && p.path.endsWith("~in0")) => i.toDouble
        }
        val opOutY  = if (dataRows.nonEmpty) rowY(dataRows.sum / dataRows.size) else rowY(0)
        var westRow = 0; var eastRow = 0
        val laidPins = pins.values.toSeq.map { p =>
          val portY =
            if (kind == NodeKind.Op && p.side == PinSide.East) opOutY
            else {
              val rowIdx = if (p.side == PinSide.West) { val r = westRow; westRow += 1; r }
                           else                   { val r = eastRow; eastRow += 1; r }
              rowY(rowIdx.toDouble)
            }
          val portX = if (p.side == PinSide.East) w else 0.0
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), portX, portY,
            BundleTree.isAggregateType(p.bundle, design))
        }
        PreparedNode(id, kind, w, h, laidPins)
      }
    }.toSeq

    Prepared(nodes, edges)
  }
}
