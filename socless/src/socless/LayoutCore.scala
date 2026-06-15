package socless

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
    case ("inst", "output") | ("inst", "master")        => "EAST"
    // A boundary "slave" comes from `input io` (AXI-aware portRole), so by
    // this file's own convention it drives rightward like any boundary input.
    // Without this case AXI slave boundary stubs fell through to WEST: their
    // anti-parallel traffic always ties the side vote and the tie-break uses
    // this declared side (issue #102).
    case ("boundary", "input") | ("boundary", "slave")  => "EAST"
    case _                                               => "WEST"
  }

  private val VecKey = """^Vec\[(.+),\s*\d+\]$""".r

  def isInterfaceBundle(bundle: String, design: Design): Boolean =
    design.bundles.get(bundle).exists(b => b.kind == "Bundle" || b.kind == "Decoupled") ||
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
    * is "WEST"/"EAST", and `interface` flags aggregate bundles. */
  case class Endpoint(nodeId: String, kind: String, path: String, side: String, interface: Boolean)

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
      val dir      = portDef.map(_.dir).getOrElse("input")
      val bundle   = portDef.map(_.bundle).getOrElse("Wire")
      Some(Endpoint(head, "inst", path, sideFor("inst", dir), isInterfaceBundle(bundle, design)))
    } else {
      module.ports.find(_.name == head).map { p =>
        // Each external pin is its OWN boundary node (keyed by full path), not
        // grouped under the port head. A single shared node would force all of
        // a module's I/O into one layer, so inputs could not sit on the left
        // and outputs on the right; wires then route backward or span the whole
        // canvas (issue #68). Per-pin stubs let ELK rank input-sources left and
        // output-sinks right with forward-flowing wires.
        Endpoint(path, "boundary", path, sideFor("boundary", p.dir), isInterfaceBundle(p.bundle, design))
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

  /** One box: an instance component (`kind == "inst"`, ports at fixed rows) or a
    * boundary stub (`kind == "boundary"`, ports placed on their side).
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

    def vote(ep: Endpoint, isSource: Boolean, wireBundleName: String): Unit = {
      val k = (ep.nodeId, ep.kind, ep.path)
      if (isSource) eastVotes(k) += 1 else westVotes(k) += 1
      ifaceOf(k) = ifaceOf(k) || ep.interface
      declaredOf(k) = ep.side
      if (!bundleOf.contains(k) && wireBundleName.nonEmpty && wireBundleName != "Wire")
        bundleOf(k) = wireBundleName
      pinOrder.getOrElseUpdate((ep.nodeId, ep.kind), mutable.LinkedHashSet.empty) += ep.path
    }
    // e.to is SOURCE; e.from is SINK.
    resolved.foreach { e =>
      vote(e.to,   isSource = true,  e.w.bundle)
      vote(e.from, isSource = false, e.w.bundle)
    }
    // Ensure instances with no resolved wires still appear as empty boxes.
    module.children.foreach { c =>
      pinOrder.getOrElseUpdate((c.name, "inst"), mutable.LinkedHashSet.empty)
    }

    // Build nodePins from the vote tallies.
    val nodePins = mutable.LinkedHashMap.empty[(String, String), PinMap]
    pinOrder.foreach { case ((nodeId, kind), paths) =>
      val pins: PinMap = mutable.LinkedHashMap.empty
      paths.foreach { path =>
        val k = (nodeId, kind, path)
        val side =
          if (eastVotes(k) > westVotes(k)) "EAST"
          else if (westVotes(k) > eastVotes(k)) "WEST"
          else if (kind == "boundary") declaredOf.getOrElse(k, "EAST")
          else "EAST"
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
      if (kind == "boundary") {
        // Boundary: compact, but at least wide enough for the label (if any).
        val maxLabelPx = pins.values.map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        val bw = math.max(BoundaryW, maxLabelPx)
        val laidPins = pins.values.toSeq.map { p =>
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), 0.0, 0.0,
            BundleTree.isAggregateType(p.bundle, design))
        }
        // An EAST boundary pin drives into the design (a source): pin it to the
        // first column. A WEST one receives (a sink): pin it to the last column.
        // Skip the constraint for any pin that also carries a back-edge.
        val side        = laidPins.headOption.map(_.side).getOrElse("EAST")
        val paths       = laidPins.map(_.path)
        val anyIncoming = paths.exists(edgeSinks.contains)
        val anyOutgoing = paths.exists(edgeSources.contains)
        val lc =
          if (side == "EAST" && !anyIncoming)      Some("FIRST_SEPARATE")
          else if (side == "WEST" && !anyOutgoing) Some("LAST_SEPARATE")
          else                                     None
        PreparedNode(id, kind, bw, BoundaryH, laidPins, lc)
      } else {
        // Instance component: reserve HeaderH at top, ports in rows below.
        val west = pins.values.count(_.side == "WEST")
        val east = pins.values.count(_.side == "EAST")
        val westMaxPx = pins.values.filter(_.side == "WEST").map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        val eastMaxPx = pins.values.filter(_.side == "EAST").map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        // Also ensure header text (instance id) fits: id.length * 6.5 + 24 (glyph + padding)
        val headerTextPx = id.length * 6.5 + 24.0
        val w = math.min(MaxNodeW, math.max(NodeW, math.max(headerTextPx, westMaxPx + LabelGap + eastMaxPx)))
        val h = nodeHeight(west, east)
        // Independent row counters per side so WEST and EAST ports start at row 0.
        var westRow = 0; var eastRow = 0
        val laidPins = pins.values.toSeq.map { p =>
          val rowIdx = if (p.side == "WEST") { val r = westRow; westRow += 1; r }
                       else                   { val r = eastRow; eastRow += 1; r }
          val portY = HeaderH + PortPad + rowIdx * PortStep + PortStep / 2
          val portX = if (p.side == "EAST") w else 0.0
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), portX, portY,
            BundleTree.isAggregateType(p.bundle, design))
        }
        PreparedNode(id, kind, w, h, laidPins)
      }
    }.toSeq

    Prepared(nodes, edges)
  }
}
