package socless

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.{CoreOptions, Direction, EdgeRouting, PortConstraints, PortSide}
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.alg.layered.options.{LayeredMetaDataProvider, LayeredOptions}
import org.eclipse.elk.graph.{ElkEdge, ElkNode, ElkPort}
import org.eclipse.elk.graph.util.ElkGraphUtil

import scala.jdk.CollectionConverters._

/** Backend schematic layout via the Eclipse Layout Kernel (ELK).
  *
  * Builds a layered, left-to-right graph from a module's instances + coalesced
  * wires, runs orthogonal edge routing, and returns absolute-coordinate
  * geometry (see [[ModuleLayout]]). Pure and deterministic given fixed options,
  * so it is unit-testable without a UI.
  */
object Layout {

  // ELK looks algorithms up by id from a global registry. In a plain JVM
  // (no Eclipse runtime) we must register the layered provider once.
  private lazy val registered: Boolean = {
    LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(new LayeredMetaDataProvider())
    true
  }

  // Serialize all ELK calls: LayoutMetaDataService is a JVM-wide singleton and
  // RecursiveGraphLayoutEngine mutates shared algorithm state during a run.
  // Without this lock, concurrent calls from parallel test classes produce
  // slightly different coordinates for identical inputs (issue #55).
  private val elkLock = new Object()

  private def newGraph(): ElkNode = {
    require(registered)
    val root = ElkGraphUtil.createGraph()
    root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
    root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
    root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
    root.setProperty(CoreOptions.SPACING_NODE_NODE, java.lang.Double.valueOf(40.0))
    root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, java.lang.Double.valueOf(64.0))
    root
  }

  private def addNode(root: ElkNode, w: Double, h: Double): ElkNode = {
    val n = ElkGraphUtil.createNode(root)
    n.setWidth(w); n.setHeight(h)
    n.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
    n
  }

  private def addPort(n: ElkNode, side: PortSide): ElkPort = {
    val p = ElkGraphUtil.createPort(n)
    p.setWidth(6); p.setHeight(6)
    p.setProperty(CoreOptions.PORT_SIDE, side)
    p
  }

  /** Smoke fixture used by ElkSmokeTest: two nodes, one edge A->B. */
  def smokeTwoNodes(): (Box, Box) = elkLock.synchronized {
    val root = newGraph()
    val a = addNode(root, 160, 80)
    val ap = addPort(a, PortSide.EAST)
    val b = addNode(root, 160, 80)
    val bp = addPort(b, PortSide.WEST)
    ElkGraphUtil.createSimpleEdge(ap, bp)
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor())
    (Box(a.getX.toInt, a.getY.toInt, a.getWidth.toInt, a.getHeight.toInt),
     Box(b.getX.toInt, b.getY.toInt, b.getWidth.toInt, b.getHeight.toInt))
  }

  case class Box(x: Int, y: Int, w: Int, h: Int)

  /** A resolved wire endpoint. `nodeId` is the box that owns the pin (instance
    * name, or bare port name for a boundary), `kind` is "inst" or "boundary",
    * `path` is the full endpoint path used as the pin id, `side` is
    * "WEST"/"EAST", and `interface` flags aggregate bundles. */
  case class Endpoint(nodeId: String, kind: String, path: String, side: String, interface: Boolean)

  private def stripIdx(s: String): String = {
    val i = s.indexOf('[')
    if (i < 0) s else s.substring(0, i)
  }

  /** A pin sits on the EAST (right/driving) side when its port drives signals
    * rightward in the left-to-right schematic: instance outputs/masters, and
    * this module's own boundary INPUTS (which drive signals into the design).
    * Receiving pins (instance inputs/slaves, boundary outputs) sit on WEST. */
  private def sideFor(kind: String, dir: String): String = (kind, dir) match {
    case ("inst", "output") | ("inst", "master") => "EAST"
    case ("boundary", "input")                    => "EAST"
    case _                                         => "WEST"
  }

  private def isInterfaceBundle(bundle: String, design: Design): Boolean =
    design.bundles.get(bundle).exists(b => b.kind == "Bundle" || b.kind == "Decoupled")

  private val NodeW      = 160.0
  private val PortStep   = 24.0
  private val PortPad    = 18.0
  /** Height of the component header band (name + type). Must match HEADER_H in SoCLess.html (issue #38). */
  val HeaderH            = 28.0
  private val BoundaryW  = 16.0
  private val BoundaryH  = 28.0
  private val LabelGap   = 24.0   // horizontal gap between widest WEST and EAST label columns
  private val MaxNodeW   = 520.0  // cap so layout doesn't explode on very long labels

  private def nodeHeight(westN: Int, eastN: Int): Double = {
    val maxRows = math.max(1, math.max(westN, eastN))
    math.max(HeaderH + 2 * PortPad + PortStep, HeaderH + 2 * PortPad + maxRows * PortStep)
  }

  /** Estimate rendered pixel width of `text` in the UI's small monospace font. */
  private def labelPx(text: String): Double = text.length * 6.5 + 14.0

  private val ClockResetBundles = Set("Clock", "Reset", "AsyncReset")
  private def isClockReset(w: WireDef): Boolean = ClockResetBundles.contains(w.bundle)

  /** Lay out `module`'s schematic: child instances + boundary ports as boxes,
    * coalesced wires as orthogonally-routed edges. */
  def layout(module: ModuleDef, design: Design): ModuleLayout = elkLock.synchronized {
    val root = newGraph()

    // 1. Resolve every wire endpoint; keep only wires whose BOTH ends resolve.
    //    Drop clock/reset wires before resolution so their boundary stubs are
    //    also excluded from the layout.
    case class E(w: WireDef, from: Endpoint, to: Endpoint)
    val resolved: Seq[E] = module.wires.filterNot(isClockReset).flatMap { w =>
      for { f <- classify(w.from, module, design); t <- classify(w.to, module, design) }
        yield E(w, f, t)
    }

    // 2. Group pins per node (distinct path), preserving first-seen order.
    //    Pin side is determined by dataflow role, not declared direction:
    //    - SOURCE endpoint (e.to) votes EAST  (it drives rightward)
    //    - SINK   endpoint (e.from) votes WEST (it receives)
    //    A pin that appears in multiple wires may get votes for both sides;
    //    final side = EAST when eastVotes >= westVotes (tie -> EAST).
    case class PinSpec(path: String, side: String, interface: Boolean, bundle: String)
    type PinMap = scala.collection.mutable.LinkedHashMap[String, PinSpec]

    // Tally votes: (nodeId, kind, path) -> (eastVotes, westVotes, interface)
    val eastVotes  = scala.collection.mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val westVotes  = scala.collection.mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val ifaceOf    = scala.collection.mutable.Map.empty[(String, String, String), Boolean].withDefaultValue(false)
    val bundleOf   = scala.collection.mutable.Map.empty[(String, String, String), String]
    // Ordered paths per (nodeId, kind) to preserve first-seen insertion order.
    val pinOrder   = scala.collection.mutable.LinkedHashMap.empty[(String, String), scala.collection.mutable.LinkedHashSet[String]]

    def vote(ep: Endpoint, isSource: Boolean, wireBundleName: String): Unit = {
      val k = (ep.nodeId, ep.kind, ep.path)
      if (isSource) eastVotes(k) += 1 else westVotes(k) += 1
      ifaceOf(k) = ifaceOf(k) || ep.interface
      // First-seen bundle name wins (skip generic "Wire")
      if (!bundleOf.contains(k) && wireBundleName.nonEmpty && wireBundleName != "Wire")
        bundleOf(k) = wireBundleName
      pinOrder.getOrElseUpdate((ep.nodeId, ep.kind), scala.collection.mutable.LinkedHashSet.empty) += ep.path
    }
    // e.to is SOURCE; e.from is SINK.
    resolved.foreach { e =>
      vote(e.to,   isSource = true,  e.w.bundle)
      vote(e.from, isSource = false, e.w.bundle)
    }
    // Ensure instances with no resolved wires still appear as empty boxes.
    module.children.foreach { c =>
      pinOrder.getOrElseUpdate((c.name, "inst"), scala.collection.mutable.LinkedHashSet.empty)
    }

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

    // Build nodePins from the vote tallies.
    val nodePins = scala.collection.mutable.LinkedHashMap.empty[(String, String), PinMap]
    pinOrder.foreach { case ((nodeId, kind), paths) =>
      val pins: PinMap = scala.collection.mutable.LinkedHashMap.empty
      paths.foreach { path =>
        val k      = (nodeId, kind, path)
        val side   = if (eastVotes(k) >= westVotes(k)) "EAST" else "WEST"
        val bundle = bundleOf.get(k)
        pins(path) = PinSpec(path, side, ifaceOf(k), bundle.getOrElse(""))
      }
      nodePins((nodeId, kind)) = pins
    }

    // 3. Create ELK nodes + ports. Track ELK port handles by path.
    //    Boundary nodes are compact stubs (FIXED_SIDE, no header).
    //    Instance (component) nodes use FIXED_POS with a header band: ports are
    //    placed in rows below the header at explicit (x, y) coordinates so the
    //    name label never overlaps any pin (issue #28).
    val elkPort = scala.collection.mutable.HashMap.empty[String, ElkPort]
    val elkNode = scala.collection.mutable.LinkedHashMap.empty[(String, String), ElkNode]
    nodePins.foreach { case ((id, kind), pins) =>
      if (kind == "boundary") {
        // Boundary: keep compact but at least wide enough for the label (if any).
        val maxLabelPx = pins.values.map { p =>
          val lbl = pinLabel(id, p.path, if (p.bundle.nonEmpty) Some(p.bundle) else None)
          labelPx(lbl)
        }.maxOption.getOrElse(0.0)
        val bw = math.max(BoundaryW, maxLabelPx)
        val n = addNode(root, bw, BoundaryH)
        elkNode((id, kind)) = n
        pins.values.foreach { p =>
          val side = if (p.side == "EAST") PortSide.EAST else PortSide.WEST
          elkPort(p.path) = addPort(n, side)
        }
      } else {
        // Instance (component) node: reserve HeaderH at top, place ports in rows below.
        val west = pins.values.count(_.side == "WEST")
        val east = pins.values.count(_.side == "EAST")
        val westMaxPx = pins.values.filter(_.side == "WEST").map { p =>
          val lbl = pinLabel(id, p.path, if (p.bundle.nonEmpty) Some(p.bundle) else None)
          labelPx(lbl)
        }.maxOption.getOrElse(0.0)
        val eastMaxPx = pins.values.filter(_.side == "EAST").map { p =>
          val lbl = pinLabel(id, p.path, if (p.bundle.nonEmpty) Some(p.bundle) else None)
          labelPx(lbl)
        }.maxOption.getOrElse(0.0)
        // Also ensure header text (instance id) fits: id.length * 6.5 + 24 (glyph + padding)
        val headerTextPx = id.length * 6.5 + 24.0
        val w = math.min(MaxNodeW, math.max(NodeW, math.max(headerTextPx, westMaxPx + LabelGap + eastMaxPx)))
        val node = ElkGraphUtil.createNode(root)
        node.setWidth(w); node.setHeight(nodeHeight(west, east))
        // FIXED_POS: ELK will honour our explicit per-port (x, y) values.
        node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS)
        elkNode((id, kind)) = node
        // Independent row counters per side so WEST and EAST ports start at row 0.
        var westRow = 0; var eastRow = 0
        pins.values.foreach { p =>
          val rowIdx = if (p.side == "WEST") { val r = westRow; westRow += 1; r }
                       else                   { val r = eastRow; eastRow += 1; r }
          val portY = HeaderH + PortPad + rowIdx * PortStep + PortStep / 2
          val port = ElkGraphUtil.createPort(node)
          port.setWidth(6); port.setHeight(6)
          port.setX(if (p.side == "EAST") w else 0.0)
          port.setY(portY)
          // FIXED_POS still requires PORT_SIDE for ELK's edge-routing decisions.
          port.setProperty(CoreOptions.PORT_SIDE, if (p.side == "EAST") PortSide.EAST else PortSide.WEST)
          elkPort(p.path) = port
        }
      }
    }

    // 4. Collapse anti-parallel wire pairs before creating ELK edges.
    //    Two resolved wires form an anti-parallel pair when one drives port P->Q
    //    and the other drives Q->P (same unordered port-path pair, opposite
    //    direction). This happens for AXI-style bundles where master drives the
    //    request channel and slave drives the response channel back. Feeding both
    //    to ELK creates a 2-cycle that defeats the layered ranker, bunching nodes
    //    in the same layer instead of flowing left-to-right. Collapse each pair
    //    to the single edge whose SOURCE is the DRIVER (side == "EAST").
    //
    //    NOTE: node/pin registration above still uses ALL resolved endpoints, so
    //    no pin is dropped by this dedup -- only the ELK edges and RoutedEdges are
    //    deduplicated.
    def antiParallelKey(e: E): (String, String) = {
      val a = e.to.path   // source
      val b = e.from.path // sink
      if (a <= b) (a, b) else (b, a)
    }
    val edgesToDraw: Seq[E] = resolved
      .groupBy(antiParallelKey)
      .values
      .map { grp =>
        if (grp.size == 1) grp.head
        else {
          // Anti-parallel (or duplicate) pair: prefer the edge whose source
          // (e.to) is the driver by VOTE -- eastVotes >= westVotes for that pin.
          // Do NOT use e.to.side from classify/sideFor; that uses declared
          // direction which is unreliable for shared AXI4 bundles (C2 regression).
          // Fall back to lexicographic source-path order for a stable deterministic choice.
          def isEastByVote(ep: Endpoint): Boolean = {
            val k = (ep.nodeId, ep.kind, ep.path)
            eastVotes(k) >= westVotes(k)
          }
          grp.find(e => isEastByVote(e.to))
            .orElse(grp.sortBy(_.to.path).headOption)
            .getOrElse(grp.head)
        }
      }
      .toSeq

    // Create edges, oriented source->sink for correct left-to-right layering.
    // WireDef.from is the SINK and WireDef.to is the SOURCE, so the ELK edge
    // runs from e.to (source) to e.from (sink).
    edgesToDraw.foreach { e =>
      for { src <- elkPort.get(e.to.path); dst <- elkPort.get(e.from.path) }
        ElkGraphUtil.createSimpleEdge(src, dst)
    }

    // 5. Run layout.
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor())

    // 6. Extract geometry.
    val laidNodes = nodePins.map { case ((id, kind), pins) =>
      val n = elkNode((id, kind))
      val nx = n.getX; val ny = n.getY
      val laidPorts = pins.values.toSeq.map { p =>
        val ep    = elkPort(p.path)
        val lbl   = pinLabel(id, p.path, if (p.bundle.nonEmpty) Some(p.bundle) else None)
        LaidPort(p.path, (nx + ep.getX).toInt, (ny + ep.getY).toInt, p.side, p.interface, lbl, p.bundle)
      }
      LaidNode(id, kind, nx.toInt, ny.toInt, n.getWidth.toInt, n.getHeight.toInt, laidPorts)
    }.toSeq

    // Build a lookup from (srcPort, dstPort) to the created ElkEdge.
    // allOutgoingEdges takes ElkNode, not ElkPort; instead iterate root.getContainedEdges().
    // Parallel edges between the same port pair share a route; one representative
    // polyline is sufficient, so collapse duplicates deterministically.
    val edgeByPortPair = root.getContainedEdges.asScala.flatMap { ed =>
      // All edges are simple (one source, one target) because we only call
      // createSimpleEdge above; assert here to catch any future regression.
      assert(ed.getSources.size == 1 && ed.getTargets.size == 1,
        "Layout expects simple edges only (createSimpleEdge); hyperedges unsupported")
      val src = ed.getSources.asScala.headOption.flatMap {
        case p: ElkPort => Some(p)
        case _          => None
      }
      val dst = ed.getTargets.asScala.headOption.flatMap {
        case p: ElkPort => Some(p)
        case _          => None
      }
      (src, dst) match {
        case (Some(s), Some(d)) => Some((s, d) -> ed)
        case _                  => None
      }
    }.groupBy(_._1).view.mapValues(_.head._2).toMap

    val laidEdges = edgesToDraw.flatMap { e =>
      // Edge was created source->sink, i.e. from the e.to port to the e.from port.
      val src = elkPort.get(e.to.path)
      val dst = elkPort.get(e.from.path)
      (src, dst) match {
        case (Some(s), Some(d)) =>
          edgeByPortPair.get((s, d)).map { ed =>
            val pts = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
            val sections = ed.getSections
            if (!sections.isEmpty) {
              val sec = sections.get(0)
              pts += ((sec.getStartX.toInt, sec.getStartY.toInt))
              sec.getBendPoints.asScala.foreach(bp => pts += ((bp.getX.toInt, bp.getY.toInt)))
              pts += ((sec.getEndX.toInt, sec.getEndY.toInt))
            }
            // points run source->sink (e.to -> e.from); from/to keep WireDef
            // semantics so the UI looks up the polyline by (w.from, w.to).
            RoutedEdge(e.from.path, e.to.path, pts.toSeq, e.w.label)
          }
        case _ => None
      }
    }

    ModuleLayout(laidNodes, laidEdges, root.getWidth.toInt.max(1), root.getHeight.toInt.max(1))
  }

  /** Compute a layout for every module and attach them to the design. A module
    * that fails to lay out is skipped with a stderr note (mirrors
    * SoclessExporter.collectSources' skip-and-warn policy) so one bad module
    * never aborts the export. */
  def withLayouts(design: Design): Design = {
    var failed = 0
    val layouts = design.modules.flatMap { m =>
      try Some(m.name -> layout(m, design))
      catch {
        case t: Throwable =>
          failed += 1
          Console.err.println(s"[socless] layout failed for ${m.name}: ${t.getMessage}")
          None
      }
    }.toMap
    if (failed > 0) Console.err.println(s"[socless] $failed module(s) had no layout")
    design.copy(layouts = layouts)
  }

  /** Resolve `path` against `module`. Returns None for internal nets / unknown
    * heads (those endpoints are dropped, as in IrWalk). */
  def classify(path: String, module: ModuleDef, design: Design): Option[Endpoint] = {
    val parts = path.split('.').toIndexedSeq
    if (parts.isEmpty) return None
    val head = stripIdx(parts.head)
    val instOf = module.children.find(_.name == head)
    if (instOf.isDefined && parts.length >= 2) {
      val childMod = design.modules.find(_.name == instOf.get.`type`)
      val portName = stripIdx(parts(1))
      val portDef  = childMod.flatMap(_.ports.find(_.name == portName))
      val dir      = portDef.map(_.dir).getOrElse("input")
      val bundle   = portDef.map(_.bundle).getOrElse("Wire")
      Some(Endpoint(head, "inst", path, sideFor("inst", dir), isInterfaceBundle(bundle, design)))
    } else {
      module.ports.find(_.name == head).map { p =>
        Endpoint(head, "boundary", path, sideFor("boundary", p.dir), isInterfaceBundle(p.bundle, design))
      }
    }
  }
}
