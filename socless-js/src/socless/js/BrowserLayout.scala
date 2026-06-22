package socless.js

import socless.schema.{Design, ModuleDef, ModuleLayout, NodeKind, PinSide}
import socless.layout.LayoutCore
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ── elkjs result facade (#219) ──────────────────────────────────────────────
// A thin `@js.native` view of the shape `elk.layout(...)` resolves to, so the
// extraction below reads as plain field access instead of a wall of
// `asInstanceOf[js.Dynamic]` / `js.UndefOr` casts. Optional fields (a port's
// size, an edge's sections / junctionPoints, a section's bendPoints) stay
// `js.UndefOr` because elkjs omits them in the cases the extractor handles.

@js.native private trait ElkPoint extends js.Object {
  def x: Double = js.native
  def y: Double = js.native
}

@js.native private trait ElkPort extends js.Object {
  def id: String = js.native
  def x: Double = js.native
  def y: Double = js.native
  def width: js.UndefOr[Double] = js.native
  def height: js.UndefOr[Double] = js.native
}

@js.native private trait ElkNode extends js.Object {
  def id: String = js.native
  def x: Double = js.native
  def y: Double = js.native
  def width: Double = js.native
  def height: Double = js.native
  def ports: js.Array[ElkPort] = js.native
}

@js.native private trait ElkSection extends js.Object {
  def startPoint: ElkPoint = js.native
  def endPoint: ElkPoint = js.native
  def bendPoints: js.UndefOr[js.Array[ElkPoint]] = js.native
}

@js.native private trait ElkEdge extends js.Object {
  def sources: js.Array[String] = js.native
  def targets: js.Array[String] = js.native
  def sections: js.UndefOr[js.Array[ElkSection]] = js.native
  def junctionPoints: js.UndefOr[js.Array[ElkPoint]] = js.native
}

@js.native private trait ElkGraph extends js.Object {
  def children: js.Array[ElkNode] = js.native
  def edges: js.Array[ElkEdge] = js.native
  def width: Double = js.native
  def height: Double = js.native
}

/** Browser schematic layout: the elkjs counterpart of the JVM [[socless.Layout]]
  * ELK shell (issue #167). Shares [[LayoutCore.prepare]] with the backend, then
  * builds an elkjs JSON graph, runs it on `window.ELK`, and extracts geometry.
  *
  * [[runTyped]] is the in-process seam the Scala.js + Laminar UI calls directly
  * (epic #172): it returns typed [[LaidOut]] case classes, no `js.Dynamic`. */
object BrowserLayout {

  // ── Typed layout result (the in-process shape) ─────────────────────────────

  /** A laid-out pin: absolute `x`/`y` plus the carried pin metadata. */
  case class LaidPortR(path: String, x: Int, y: Int, side: String, interface: Boolean,
                       label: String, bundle: String, expandable: Boolean)

  /** A laid-out box (`kind` == NodeKind.Inst | NodeKind.Boundary). */
  case class LaidNodeR(id: String, kind: String, x: Int, y: Int, w: Int, h: Int,
                       ports: Seq[LaidPortR])

  /** A routed edge, source->sink, with optional fan-out/fan-in junction points. */
  case class LaidEdgeR(from: String, to: String, points: Seq[(Int, Int)],
                       junctionPoints: Seq[(Int, Int)])

  /** One module's full laid-out geometry, in the shape the UI renders. */
  case class LaidOut(nodes: Seq[LaidNodeR], edges: Seq[LaidEdgeR], w: Int, h: Int)

  object LaidOut {
    /** Adapt the backend's baked geometry ([[ModuleLayout]] from
      * `Design.layouts`) into the same [[LaidOut]] shape, so the renderer
      * consumes baked and engine results interchangeably. Baked edges carry no
      * junction points. */
    def fromBaked(L: ModuleLayout): LaidOut = {
      val nodes = L.nodes.map { n =>
        LaidNodeR(n.id, n.kind, n.x, n.y, n.w, n.h,
          n.ports.map(p => LaidPortR(p.path, p.x, p.y, p.side, p.interface, p.label, p.bundle, p.expandable)))
      }
      val edges = L.edges.map(e => LaidEdgeR(e.from, e.to, e.points, Nil))
      LaidOut(nodes, edges, L.w, L.h)
    }
  }

  /** Expansion-aware geometry override for one box: its grown `height` (None =
    * keep the prepared height) and per-pin box-relative `y` rows. */
  case class BoxOverride(height: Option[Double], pins: Map[String, Double])

  /** elkjs node id: combine id + kind so a boundary stub whose path equals an
    * instance name cannot collide (the JVM keys its node map by (id, kind)). */
  private def synthId(n: LayoutCore.PreparedNode): String = s"${n.kind}${n.id}"

  /** Build the elkjs graph (a plain JS object) from the prepared graph.
    *
    * `overrides` carries the UI's expansion-aware geometry per node id. Applying
    * it means a box whose bundle was expanded is laid out at its grown height
    * with its top-level pins at their shifted rows, so elkjs re-routes cleanly
    * to the moved pins (this is what removes the up-then-down kink, issue #164). */
  private def buildGraph(prep: LayoutCore.Prepared, mode: String,
                         overrides: Map[String, BoxOverride]): js.Dynamic = {
    val children = prep.nodes.map { n =>
      val ov     = overrides.get(n.id)
      val height = ov.flatMap(_.height).getOrElse(n.height)
      val pinYOv = ov.map(_.pins).getOrElse(Map.empty[String, Double])
      val ports = n.pins.map { p =>
        val py = pinYOv.getOrElse(p.path, p.y)
        js.Dynamic.literal(
          id = p.path,
          x = p.x, y = py, width = 6, height = 6,
          layoutOptions = js.Dynamic.literal("elk.port.side" -> (if (p.side == PinSide.East) "EAST" else "WEST")),
        )
      }.toJSArray
      // Boundary stubs let elkjs place ports on their side; instance nodes pin
      // each port at its precomputed (x, y) so the header never overlaps a pin.
      val portConstraints = if (n.kind == NodeKind.Boundary) "FIXED_SIDE" else "FIXED_POS"
      val nodeOpts = js.Dynamic.literal("elk.portConstraints" -> portConstraints)
      // Pin boundary stubs into their own first/last layer so external I/O forms
      // two aligned columns flanking the instances (issue #163). Same policy as
      // the JVM backend (LayoutCore sets layerConstraint), applied as the elkjs
      // option here so the two engines stay in sync.
      n.layerConstraint.foreach(lc => nodeOpts.updateDynamic("elk.layered.layering.layerConstraint")(lc))
      js.Dynamic.literal(
        id = synthId(n),
        width = n.width, height = height,
        layoutOptions = nodeOpts,
        ports = ports,
      )
    }.toJSArray

    // Edge: source = toPath (driver), target = fromPath (sink), matching the
    // JVM orientation. elkjs echoes sources/targets back so extraction needs no
    // index bookkeeping.
    val edges = prep.edges.zipWithIndex.map { case (e, i) =>
      js.Dynamic.literal(
        id = s"e$i",
        sources = js.Array[String](e.toPath),
        targets = js.Array[String](e.fromPath),
      )
    }.toJSArray

    // Base options mirror the JVM backend so a plain layout reproduces the
    // baked one byte-for-byte (verified on axisystem).
    val opts = js.Dynamic.literal(
      "elk.algorithm"   -> "layered",
      "elk.direction"   -> "RIGHT",
      "elk.edgeRouting" -> "ORTHOGONAL",
      "elk.spacing.nodeNode" -> "40",
      "elk.layered.spacing.nodeNodeBetweenLayers" -> "64",
      // One global layering for all components so the boundary layer constraints
      // (issue #163) yield a single leftmost/rightmost column for the whole
      // module, matching the JVM backend.
      "elk.separateConnectedComponents" -> "false",
    )
    if (mode == "optimize") {
      // The Vivado-style "refresh to optimize": push elkjs toward shorter,
      // straighter wires than the backend's defaults (issue #165).
      opts.updateDynamic("elk.layered.nodePlacement.strategy")("NETWORK_SIMPLEX")
      opts.updateDynamic("elk.layered.nodePlacement.favorStraightEdges")("true")
      opts.updateDynamic("elk.layered.thoroughness")("12")
    }
    js.Dynamic.literal(id = "root", layoutOptions = opts, children = children, edges = edges)
  }

  /** Pull the laid-out geometry back into the typed [[LaidOut]] shape. */
  private def extractTyped(prep: LayoutCore.Prepared, res: js.Dynamic): LaidOut = {
    val g = res.asInstanceOf[ElkGraph]
    val childById = js.Dictionary.empty[ElkNode]
    g.children.foreach(c => childById(c.id) = c)

    // Absolute (root-relative) CENTER of each laid port, keyed by pin path. This
    // is the elkjs counterpart of the JVM `Layout.portAnchor` (node origin +
    // port offset + half the port box). Used to synthesize a straight route for
    // a sectionless edge in the SAME coordinate space the routed points use, so
    // the wire is never silently dropped (mirrors the JVM fix d0e8cba).
    val portAnchor = js.Dictionary.empty[(Int, Int)]

    val nodes = prep.nodes.map { n =>
      val c  = childById(synthId(n))
      val nx = c.x
      val ny = c.y
      // Index the laid ports by id (= pin path) to read each one's box-relative
      // position; absolute = node origin + port offset (handles FIXED_SIDE too).
      val portById = js.Dictionary.empty[ElkPort]
      c.ports.foreach(p => portById(p.id) = p)
      val ports = n.pins.map { p =>
        val lp = portById(p.path)
        val px = lp.x
        val py = lp.y
        val pw = lp.width.getOrElse(0.0)
        val ph = lp.height.getOrElse(0.0)
        portAnchor(p.path) = ((nx + px + pw / 2.0).toInt, (ny + py + ph / 2.0).toInt)
        LaidPortR(
          p.path,
          (nx + px).toInt,
          (ny + py).toInt,
          p.side, p.interface, p.label, p.bundle, p.expandable,
        )
      }
      LaidNodeR(n.id, n.kind, nx.toInt, ny.toInt, c.width.toInt, c.height.toInt, ports)
    }

    val edges = g.edges.toSeq.flatMap { e =>
      val pts = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      e.sections.toOption.flatMap(_.headOption).foreach { sec =>
        pts += ((sec.startPoint.x.toInt, sec.startPoint.y.toInt))
        sec.bendPoints.toOption.foreach(_.foreach(bp => pts += ((bp.x.toInt, bp.y.toInt))))
        pts += ((sec.endPoint.x.toInt, sec.endPoint.y.toInt))
      }
      // junctionPoints mark where this edge shares a segment with others (the
      // fan-out branch dots, issue #166); pass them through when present.
      val jps = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      e.junctionPoints.toOption.foreach(_.foreach(jp => jps += ((jp.x.toInt, jp.y.toInt))))
      // elkjs occasionally lays out an edge's endpoints but emits no usable
      // route section (<2 points). Rather than drop the wire (it then vanishes
      // from the render), synthesize a straight 2-point route between the two
      // port anchors, in the same node-origin/offset coordinate space the
      // sectioned points use (mirrors the JVM fix d0e8cba). sources(0) is the
      // SOURCE pin (toPath) and targets(0) is the SINK pin (fromPath).
      if (pts.length < 2) {
        for {
          src  <- portAnchor.get(e.sources(0))
          sink <- portAnchor.get(e.targets(0))
        } pts ++= Seq(src, sink)
      }
      if (pts.length < 2) Nil
      else Seq(LaidEdgeR(e.targets(0), e.sources(0), pts.toSeq, jps.toSeq))
    }

    LaidOut(nodes, edges, g.width.toInt.max(1), g.height.toInt.max(1))
  }

  /** Lay out one module with elkjs, returning a [[LaidOut]] (the in-process
    * seam for the Scala.js + Laminar UI, epic #172). */
  def runTyped(module: ModuleDef, design: Design, mode: String,
               overrides: Map[String, BoxOverride], expanded: Set[String]): Future[LaidOut] = {
    val prep  = LayoutCore.prepare(module, design, expanded)
    val graph = buildGraph(prep, mode, overrides)
    val elk   = js.Dynamic.newInstance(js.Dynamic.global.ELK)()
    val laid: Future[js.Dynamic] = elk.layout(graph).asInstanceOf[js.Promise[js.Dynamic]]
    laid.map(res => extractTyped(prep, res))
  }

}
