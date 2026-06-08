package socless

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.{CoreOptions, Direction, EdgeRouting, PortConstraints, PortSide}
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.alg.layered.options.{LayeredMetaDataProvider, LayeredOptions}
import org.eclipse.elk.graph.{ElkNode, ElkPort}
import org.eclipse.elk.graph.util.ElkGraphUtil

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
