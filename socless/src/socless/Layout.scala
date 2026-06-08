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
  def smokeTwoNodes(): (Box, Box) = {
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

  private val NodeW    = 160.0
  private val PortStep = 22.0
  private val PortPad  = 18.0

  private def nodeHeight(westN: Int, eastN: Int): Double = {
    val maxPorts = math.max(1, math.max(westN, eastN))
    math.max(72.0, 2 * PortPad + (maxPorts - 1) * PortStep)
  }

  /** Lay out `module`'s schematic: child instances + boundary ports as boxes,
    * coalesced wires as orthogonally-routed edges. */
  def layout(module: ModuleDef, design: Design): ModuleLayout = {
    val root = newGraph()

    // 1. Resolve every wire endpoint; keep only wires whose BOTH ends resolve.
    case class E(w: WireDef, from: Endpoint, to: Endpoint)
    val resolved: Seq[E] = module.wires.flatMap { w =>
      for { f <- classify(w.from, module, design); t <- classify(w.to, module, design) }
        yield E(w, f, t)
    }

    // 2. Group pins per node (distinct path), preserving first-seen order.
    case class PinSpec(path: String, side: String, interface: Boolean)
    val nodePins = scala.collection.mutable.LinkedHashMap.empty[(String, String), scala.collection.mutable.LinkedHashMap[String, PinSpec]]
    def register(ep: Endpoint): Unit = {
      val pins = nodePins.getOrElseUpdate((ep.nodeId, ep.kind), scala.collection.mutable.LinkedHashMap.empty)
      pins.getOrElseUpdate(ep.path, PinSpec(ep.path, ep.side, ep.interface))
    }
    resolved.foreach { e => register(e.from); register(e.to) }
    // Ensure instances with no resolved wires still appear as empty boxes.
    module.children.foreach { c =>
      nodePins.getOrElseUpdate((c.name, "inst"), scala.collection.mutable.LinkedHashMap.empty)
    }

    // 3. Create ELK nodes + ports. Track ELK port handles by path.
    val elkPort = scala.collection.mutable.HashMap.empty[String, ElkPort]
    val elkNode = scala.collection.mutable.LinkedHashMap.empty[(String, String), ElkNode]
    nodePins.foreach { case ((id, kind), pins) =>
      val west = pins.values.count(_.side == "WEST")
      val east = pins.values.count(_.side == "EAST")
      val n = addNode(root, NodeW, nodeHeight(west, east))
      elkNode((id, kind)) = n
      pins.values.foreach { p =>
        val side = if (p.side == "EAST") PortSide.EAST else PortSide.WEST
        elkPort(p.path) = addPort(n, side)
      }
    }

    // 4. Create edges, oriented source->sink for correct left-to-right layering.
    //    WireDef.from is the SINK and WireDef.to is the SOURCE, so the ELK edge
    //    runs from e.to (source) to e.from (sink).
    resolved.foreach { e =>
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
        val ep = elkPort(p.path)
        LaidPort(p.path, (nx + ep.getX).toInt, (ny + ep.getY).toInt, p.side, p.interface)
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

    val laidEdges = resolved.flatMap { e =>
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
      catch { case t: Throwable => failed += 1; Console.err.println(s"[socless] layout failed for ${m.name}: ${t.getMessage}"); None }
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
