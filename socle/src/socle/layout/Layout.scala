package socle.layout

import socle.schema._

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.{CoreOptions, Direction, EdgeRouting, PortConstraints, PortSide}
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.alg.layered.options.{LayerConstraint, LayeredMetaDataProvider, LayeredOptions, NodePlacementStrategy}
import org.eclipse.elk.graph.{ElkNode, ElkPort}
import org.eclipse.elk.graph.util.ElkGraphUtil

import scala.jdk.CollectionConverters._

/** Backend schematic layout via the Eclipse Layout Kernel (ELK).
  *
  * The engine-independent graph prep (endpoint resolution, pin-side voting,
  * node/port geometry, anti-parallel collapse) lives in [[LayoutCore]] and is
  * shared with the in-browser elkjs engine (issue #167). This object is the
  * thin ELK-specific shell: it turns a [[LayoutCore.Prepared]] graph into ELK
  * node/port/edge objects, runs orthogonal routing, and extracts absolute
  * geometry (see [[ModuleLayout]]). Pure and deterministic given fixed options.
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
    // Keep all components in one global layering so the boundary layer
    // constraints (issue #163) produce a single leftmost/rightmost column for the
    // whole module, not one per disconnected component.
    root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, java.lang.Boolean.FALSE)
    // The optimize pass (BrowserLayout's "optimize" mode, the UI's default
    // auto-optimize): baked with it, a first view needs no browser re-layout (#335).
    root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
    root.setProperty(LayeredOptions.NODE_PLACEMENT_FAVOR_STRAIGHT_EDGES, java.lang.Boolean.TRUE)
    root.setProperty(LayeredOptions.THOROUGHNESS, Integer.valueOf(12))
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

  /** Absolute (root-relative) CENTER of an ELK port: its owning node origin +
    * the port offset + half the port box. This is the same node-origin/offset
    * transform [[layout]] applies when emitting LaidPort coordinates, so an
    * anchor computed here lands in the coordinate space of routed edge points. */
  private def portAnchor(p: ElkPort): (Int, Int) = {
    val n = p.getParent
    ((n.getX + p.getX + p.getWidth / 2.0).toInt,
     (n.getY + p.getY + p.getHeight / 2.0).toInt)
  }

  /** Smoke fixture for the sectionless-edge fallback: lay out two connected
    * nodes, strip the routed edge's sections to simulate ELK returning an edge
    * without a usable route, then synthesize the straight 2-point fallback the
    * way [[layout]] does. Returns (synthesized points, source anchor, sink
    * anchor) so the test can assert the route is exactly the two port anchors. */
  def smokeSectionlessRoute(): (Seq[(Int, Int)], (Int, Int), (Int, Int)) = elkLock.synchronized {
    val root = newGraph()
    val a  = addNode(root, 160, 80)
    val ap = addPort(a, PortSide.EAST)
    val b  = addNode(root, 160, 80)
    val bp = addPort(b, PortSide.WEST)
    val ed = ElkGraphUtil.createSimpleEdge(ap, bp)
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor())
    ed.getSections.clear() // simulate the sectionless case
    val pts = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    if (ed.getSections.isEmpty) { pts += portAnchor(ap); pts += portAnchor(bp) }
    (pts.toSeq, portAnchor(ap), portAnchor(bp))
  }

  // The header/port-row geometry constants moved to LayoutCore (shared with the
  // browser engine, issue #167). Forwarded here so existing callers and tests
  // keep reaching them through Layout.
  def HeaderH: Double  = LayoutCore.HeaderH
  def PortStep: Double = LayoutCore.PortStep
  def PortPad: Double  = LayoutCore.PortPad

  /** Resolve `path` against `module` (delegates to [[LayoutCore.classify]];
    * kept here for the existing call sites). */
  def classify(path: String, module: ModuleDef, design: Design): Option[LayoutCore.Endpoint] =
    LayoutCore.classify(path, module, design)

  /** Lay out `module`'s schematic: child instances + boundary ports as boxes,
    * coalesced wires as orthogonally-routed edges. */
  def layout(module: ModuleDef, design: Design): ModuleLayout = elkLock.synchronized {
    // All engine-independent prep (resolve, vote, geometry, anti-parallel
    // collapse) is shared with the browser engine (issue #167).
    val prep = LayoutCore.prepare(InstanceFold.fold(module, Set.empty), design, Set.empty)
    // Verification-layer content apart: the design laid out alone (no hole when
    // layers are hidden), the layer nodes in a band below it.
    val layerIds = module.logic.iterator.filter(_.layer).map(_.id).toSet
    val (mainP, dbgP, cross) = LayoutCore.splitLayers(prep, layerIds)
    val main = ordered(mainP)
    if (dbgP.nodes.isEmpty) main
    else {
      val dbg  = ordered(dbgP)
      val dy   = (main.h + LayoutCore.DebugGap).toInt
      val band = dbg.copy(
        nodes = dbg.nodes.map(n => n.copy(y = n.y + dy, ports = n.ports.map(p => p.copy(y = p.y + dy)))),
        edges = dbg.edges.map(e => e.copy(points = e.points.map { case (x, y) => (x, y + dy) })))
      val pin = (main.nodes ++ band.nodes).iterator.flatMap(_.ports.map(p => p.path -> ((p.x.toDouble, p.y.toDouble, p.side)))).toMap
      val routes = LayoutCore.debugRoutes(cross, pin, main.h + LayoutCore.DebugGap / 2).map { case (e, pts) =>
        RoutedEdge(e.fromPath, e.toPath, pts.map { case (x, y) => (x.toInt, y.toInt) }, e.label)
      }
      ModuleLayout(main.nodes ++ band.nodes, main.edges ++ band.edges ++ routes,
        math.max(main.w, dbg.w), dy + dbg.h, debugY = (main.h + LayoutCore.DebugGap / 3).toInt, mainW = main.w)
    }
  }

  // A logic or FSM block's pins follow their peers (#334): a second pass with
  // them reordered within their side by where pass one put the other ends.
  private def ordered(prep: LayoutCore.Prepared): ModuleLayout = {
    val first = layoutPrepared(prep)
    if (!prep.nodes.exists(n => NodeKind.isBox(n.kind))) first
    else {
      val portY = first.nodes.iterator.flatMap(_.ports.map(p => p.path -> p.y.toDouble)).toMap
      layoutPrepared(LayoutCore.reorderBoxPins(prep, LayoutCore.peerYOf(prep, portY)))
    }
  }

  private def layoutPrepared(prep: LayoutCore.Prepared): ModuleLayout = {
    val root = newGraph()

    // Build ELK nodes + ports from the prepared geometry. Track handles by path.
    //   Boundary nodes are compact stubs (FIXED_SIDE, ports placed by ELK); an
    //   open stub stacks its rows at fixed positions like an instance (#332).
    //   Instance nodes use FIXED_POS: each port sits at its precomputed (x, y)
    //   so the header band never overlaps a pin (issue #28).
    val elkPort = scala.collection.mutable.HashMap.empty[String, ElkPort]
    val elkNode = scala.collection.mutable.LinkedHashMap.empty[(String, String), ElkNode]
    // Pin boundary stubs into their own first/last layer so external I/O
    // forms two aligned columns flanking the instances (issue #163).
    def constrain(n: LayoutCore.PreparedNode, node: ElkNode): Unit =
      n.layerConstraint.foreach {
        case "FIRST_SEPARATE" => node.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.FIRST_SEPARATE)
        case "LAST_SEPARATE"  => node.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.LAST_SEPARATE)
        case _                => ()
      }
    prep.nodes.foreach { n =>
      if (n.kind == NodeKind.Boundary && n.pins.length <= 1) {
        val node = addNode(root, n.width, n.height)
        constrain(n, node)
        elkNode((n.id, n.kind)) = node
        n.pins.foreach { p =>
          val side = if (p.side == PinSide.East) PortSide.EAST else PortSide.WEST
          elkPort(p.path) = addPort(node, side)
        }
      } else {
        val node = ElkGraphUtil.createNode(root)
        node.setWidth(n.width); node.setHeight(n.height)
        node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS)
        constrain(n, node)
        elkNode((n.id, n.kind)) = node
        n.pins.foreach { p =>
          val port = ElkGraphUtil.createPort(node)
          port.setWidth(6); port.setHeight(6)
          // The port's centre is the pin (#334): the router ends a wire on the
          // pin's row, so the UI never bends its last segment onto the pin.
          port.setX(p.x); port.setY(p.y - 3)
          port.setProperty(CoreOptions.PORT_SIDE, if (p.side == PinSide.East) PortSide.EAST else PortSide.WEST)
          elkPort(p.path) = port
        }
      }
    }

    // Create edges, oriented source->sink for correct left-to-right layering.
    // PreparedEdge.toPath is the SOURCE, fromPath is the SINK; a `reversed` edge
    // goes in sink->source (it runs against its pins' sides, see PreparedEdge).
    def elkEnds(e: LayoutCore.PreparedEdge): Option[(ElkPort, ElkPort)] =
      for { src <- elkPort.get(e.toPath); dst <- elkPort.get(e.fromPath) }
        yield if (e.reversed) (dst, src) else (src, dst)
    prep.edges.foreach(e => elkEnds(e).foreach { case (a, b) => ElkGraphUtil.createSimpleEdge(a, b) })

    // Run layout.
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor())

    // Extract node geometry.
    val laidNodes = prep.nodes.map { n =>
      val node = elkNode((n.id, n.kind))
      val nx = node.getX; val ny = node.getY
      val laidPorts = n.pins.map { p =>
        val ep = elkPort(p.path)
        LaidPort(p.path, (nx + ep.getX).toInt, (ny + ep.getY + ep.getHeight / 2).toInt, p.side, p.interface, p.label, p.bundle, p.expandable)
      }
      LaidNode(n.id, n.kind, nx.toInt, ny.toInt, node.getWidth.toInt, node.getHeight.toInt, laidPorts)
    }

    // Build a lookup from (srcPort, dstPort) to the created ElkEdge.
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

    val laidEdges = prep.edges.flatMap { e =>
      // Edge was created source->sink, i.e. from the toPath port to the fromPath
      // port (sink->source when `reversed`: its route is flipped back below).
      val src = elkPort.get(e.toPath)
      val dst = elkPort.get(e.fromPath)
      (src, dst) match {
        case (Some(s), Some(d)) =>
          edgeByPortPair.get(if (e.reversed) (d, s) else (s, d)).flatMap { ed =>
            val pts = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
            val sections = ed.getSections
            if (!sections.isEmpty) {
              val sec = sections.get(0)
              pts += ((sec.getStartX.toInt, sec.getStartY.toInt))
              sec.getBendPoints.asScala.foreach(bp => pts += ((bp.getX.toInt, bp.getY.toInt)))
              pts += ((sec.getEndX.toInt, sec.getEndY.toInt))
              if (e.reversed) { val back = pts.reverse; pts.clear(); pts ++= back }
            }
            // ELK occasionally lays out an edge's endpoints but emits no route
            // section. Rather than drop the wire (it then vanishes from the
            // render, issue 2c), synthesize a straight 2-point route between the
            // two port anchors (portAnchor uses the same node-origin/offset
            // transform laidNodes applies, so these points share the coordinate
            // space of sectioned routes).
            if (pts.size < 2) {
              pts.clear()
              pts += portAnchor(s) // source (toPath) port anchor
              pts += portAnchor(d) // sink   (fromPath) port anchor
            }
            // points run source->sink (toPath -> fromPath); from/to keep WireDef
            // semantics so the UI looks up the polyline by (w.from, w.to).
            if (pts.size < 2) None
            else Some(RoutedEdge(e.fromPath, e.toPath, pts.toSeq, e.label))
          }
        case _ => None
      }
    }

    ModuleLayout(laidNodes, laidEdges, root.getWidth.toInt.max(1), root.getHeight.toInt.max(1))
  }

  /** Compute a layout for every module and attach them to the design. A module
    * that fails to lay out is skipped with a stderr note (mirrors
    * SocleExporter.collectSources' skip-and-warn policy) so one bad module
    * never aborts the export. */
  def withLayouts(design: Design): Design = {
    // Structural fingerprint of everything the layout depends on: the module's
    // own ports, its child instances by instance NAME plus each child module's
    // ports (classify reads only the child's ports - dir/bundle - for pin sides
    // and interface detection; the child's type NAME and internals are irrelevant
    // to this module's geometry), and its wires - all order-preserving. So two
    // modules that instantiate differently-named but structurally-identical
    // children (e.g. Core uses ALU, Core_1 uses ALU_1) still share a layout.
    // Modules with equal fingerprints lay out byte-identically, so each unique
    // layout is computed once and every copy points at it (issue #31).
    val portsByModule = design.modules.iterator.map(m => m.name -> m.ports.map(p => (p.name, p.dir, p.bundle))).toMap
    def fingerprint(m: ModuleDef): String = {
      val children = m.children.map(c => (c.name, c.foldGroup, portsByModule.getOrElse(c.`type`, Nil)))
      val ports    = m.ports.map(p => (p.name, p.dir, p.bundle))
      val wires    = m.wires.map(w => (w.from, w.to, w.bundle))
      (ports, children, wires).toString
    }

    var failed = 0
    val repByFp  = scala.collection.mutable.HashMap.empty[String, String]      // fingerprint -> representative module name
    val layouts  = scala.collection.mutable.LinkedHashMap.empty[String, ModuleLayout]
    val layoutOf = scala.collection.mutable.LinkedHashMap.empty[String, String]
    // An open logic block is a module too, laid out under its id.
    for (m <- design.modules ++ LayoutCore.openableBlocks(design).map(_._2)) {
      repByFp.get(fingerprint(m)) match {
        case Some(rep) =>
          layoutOf(m.name) = rep                      // reuse an identical module's layout
        case None =>
          try {
            val l = layout(m, design)
            layouts(m.name)  = l
            layoutOf(m.name) = m.name
            repByFp(fingerprint(m)) = m.name
          } catch {
            case t: Throwable =>
              failed += 1
              Console.err.println(s"[socle] layout failed for ${m.name}: ${t.getMessage}")
          }
      }
    }
    if (failed > 0) Console.err.println(s"[socle] $failed module(s) had no layout")
    design.copy(layouts = layouts.toMap, layoutOf = layoutOf.toMap, layoutConsts = LayoutCore.consts,
      layoutModel = LayoutCore.LayoutModel, layoutOptimized = true)
  }
}
