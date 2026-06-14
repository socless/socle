package socless

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.{CoreOptions, Direction, EdgeRouting, PortConstraints, PortSide}
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.alg.layered.options.{LayeredMetaDataProvider, LayeredOptions}
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
    val prep = LayoutCore.prepare(module, design)
    val root = newGraph()

    // Build ELK nodes + ports from the prepared geometry. Track handles by path.
    //   Boundary nodes are compact stubs (FIXED_SIDE, ports placed by ELK).
    //   Instance nodes use FIXED_POS: each port sits at its precomputed (x, y)
    //   so the header band never overlaps a pin (issue #28).
    val elkPort = scala.collection.mutable.HashMap.empty[String, ElkPort]
    val elkNode = scala.collection.mutable.LinkedHashMap.empty[(String, String), ElkNode]
    prep.nodes.foreach { n =>
      if (n.kind == "boundary") {
        val node = addNode(root, n.width, n.height)
        elkNode((n.id, n.kind)) = node
        n.pins.foreach { p =>
          val side = if (p.side == "EAST") PortSide.EAST else PortSide.WEST
          elkPort(p.path) = addPort(node, side)
        }
      } else {
        val node = ElkGraphUtil.createNode(root)
        node.setWidth(n.width); node.setHeight(n.height)
        node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS)
        elkNode((n.id, n.kind)) = node
        n.pins.foreach { p =>
          val port = ElkGraphUtil.createPort(node)
          port.setWidth(6); port.setHeight(6)
          port.setX(p.x); port.setY(p.y)
          port.setProperty(CoreOptions.PORT_SIDE, if (p.side == "EAST") PortSide.EAST else PortSide.WEST)
          elkPort(p.path) = port
        }
      }
    }

    // Create edges, oriented source->sink for correct left-to-right layering.
    // PreparedEdge.toPath is the SOURCE, fromPath is the SINK.
    prep.edges.foreach { e =>
      for { src <- elkPort.get(e.toPath); dst <- elkPort.get(e.fromPath) }
        ElkGraphUtil.createSimpleEdge(src, dst)
    }

    // Run layout.
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor())

    // Extract node geometry.
    val laidNodes = prep.nodes.map { n =>
      val node = elkNode((n.id, n.kind))
      val nx = node.getX; val ny = node.getY
      val laidPorts = n.pins.map { p =>
        val ep = elkPort(p.path)
        LaidPort(p.path, (nx + ep.getX).toInt, (ny + ep.getY).toInt, p.side, p.interface, p.label, p.bundle, p.expandable)
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
      // Edge was created source->sink, i.e. from the toPath port to the fromPath port.
      val src = elkPort.get(e.toPath)
      val dst = elkPort.get(e.fromPath)
      (src, dst) match {
        case (Some(s), Some(d)) =>
          edgeByPortPair.get((s, d)).flatMap { ed =>
            val pts = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
            val sections = ed.getSections
            if (!sections.isEmpty) {
              val sec = sections.get(0)
              pts += ((sec.getStartX.toInt, sec.getStartY.toInt))
              sec.getBendPoints.asScala.foreach(bp => pts += ((bp.getX.toInt, bp.getY.toInt)))
              pts += ((sec.getEndX.toInt, sec.getEndY.toInt))
            }
            // An ELK edge without sections has no usable route: drop it rather
            // than emit a polyline that violates the >=2-points invariant (issue #102).
            if (pts.size < 2) None
            // points run source->sink (toPath -> fromPath); from/to keep WireDef
            // semantics so the UI looks up the polyline by (w.from, w.to).
            else Some(RoutedEdge(e.fromPath, e.toPath, pts.toSeq, e.label))
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
      val children = m.children.map(c => (c.name, portsByModule.getOrElse(c.`type`, Nil)))
      val ports    = m.ports.map(p => (p.name, p.dir, p.bundle))
      val wires    = m.wires.map(w => (w.from, w.to, w.bundle))
      (ports, children, wires).toString
    }

    var failed = 0
    val repByFp  = scala.collection.mutable.HashMap.empty[String, String]      // fingerprint -> representative module name
    val layouts  = scala.collection.mutable.LinkedHashMap.empty[String, ModuleLayout]
    val layoutOf = scala.collection.mutable.LinkedHashMap.empty[String, String]
    for (m <- design.modules) {
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
              Console.err.println(s"[socless] layout failed for ${m.name}: ${t.getMessage}")
          }
      }
    }
    if (failed > 0) Console.err.println(s"[socless] $failed module(s) had no layout")
    design.copy(layouts = layouts.toMap, layoutOf = layoutOf.toMap, layoutConsts = LayoutCore.consts)
  }
}
