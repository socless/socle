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
      // An open stub stacks its rows at fixed positions like a box (#332).
      val portConstraints = if (n.kind == NodeKind.Boundary && n.pins.length <= 1) "FIXED_SIDE" else "FIXED_POS"
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

  /** A single ELK engine, created once and reused across every layout call.
    *
    * When the elkjs bundle source is inlined into the page (the shipped
    * single-file UI tags that script `id="socless-elk"`, see `socless-ui.dist`),
    * run ELK in a **Blob-URL Web Worker** so the layout algorithm no longer
    * blocks the main thread (Phase 2). A self-contained file opened from
    * `file://` cannot load a separate worker URL, so the worker is built from a
    * Blob of that same inlined source: the vendored bundle self-detects worker
    * context and installs its own message handler.
    *
    * Falls back to the in-process (main-thread) engine when no inlined source is
    * present (the dev loop loads elkjs from a CDN `<script>`) or if worker
    * construction throws. Either way layout stays a `Future`, so the caller and
    * the "use baked layout on failure" path are unchanged. */
  private lazy val engine: js.Dynamic = {
    val ELK = js.Dynamic.global.ELK
    // The worker build of elkjs, inlined by `socless-ui.dist` as a non-executing
    // <script id="socless-elk-worker">. Absent in the dev loop (elkjs loaded from
    // a CDN <script>), where we fall back to the in-process engine.
    val el  = js.Dynamic.global.document.getElementById("socless-elk-worker")
    val src = if (js.isUndefined(el) || el == null) "" else el.textContent.asInstanceOf[String]
    if (src.nonEmpty) {
      try {
        val blob = js.Dynamic.newInstance(js.Dynamic.global.Blob)(
          js.Array[js.Any](src), js.Dynamic.literal(`type` = "text/javascript"))
        val url  = js.Dynamic.global.URL.createObjectURL(blob)
        // Pass only workerFactory (no workerUrl, which makes elkjs try its Node
        // web-worker package and warn). elkjs calls factory(workerUrl); we ignore
        // the url and serve the Blob worker built from the inlined worker source.
        val factory: js.Function1[js.Any, js.Any] = (_: js.Any) => js.Dynamic.newInstance(js.Dynamic.global.Worker)(url)
        js.Dynamic.newInstance(ELK)(js.Dynamic.literal(workerFactory = factory))
      } catch { case _: Throwable => js.Dynamic.newInstance(ELK)() }
    } else js.Dynamic.newInstance(ELK)()
  }

  /** Lay out one module with elkjs, returning a [[LaidOut]] (the in-process
    * seam for the Scala.js + Laminar UI, epic #172). The engine runs off the
    * main thread when available (see [[engine]]). Callers pass the module
    * already folded (App's `currentModuleSig`), while the JVM `Layout.layout`
    * folds internally, so both engines get the same input. */
  def runTyped(module: ModuleDef, design: Design, mode: String,
               overrides: Map[String, BoxOverride], expanded: Set[String],
               hidden: Set[String] = Set.empty): Future[LaidOut] = {
    val prep0 = LayoutCore.prepare(module, design, expanded)
    // Declutter (#223): drop nodes of hidden kinds and any edge that touches one,
    // then re-lay-out the visible subset. Wires that targeted a now-absent node
    // surface as dangling stubs (SchematicGeom.buildWires) since their endpoint no
    // longer resolves to a box.
    val prep = if (hidden.isEmpty) prep0 else {
      val hiddenPaths = prep0.nodes.iterator.filter(n => hidden.contains(n.kind))
        .flatMap(n => Iterator(n.id) ++ n.pins.iterator.map(_.path)).toSet
      LayoutCore.Prepared(
        prep0.nodes.filterNot(n => hidden.contains(n.kind)),
        prep0.edges.filterNot(e => hiddenPaths.contains(e.fromPath) || hiddenPaths.contains(e.toPath)))
    }
    val graph = buildGraph(prep, mode, overrides)
    val laid: Future[js.Dynamic] = engine.layout(graph).asInstanceOf[js.Promise[js.Dynamic]]
    laid.map(res => extractTyped(prep, res))
  }

  // ── State diagrams (FSM) ───────────────────────────────────────────────────

  /** `first` pins the node to the first layer (the top): the start dot. */
  case class DiagramNode(id: String, width: Double, height: Double, first: Boolean = false)
  case class DiagramEdge(id: String, from: String, to: String, labelWidth: Double, labelHeight: Double)
  /** `points` is the routed spline (SPLINES routing): the start point, then
    * cubic Bezier segments as (control 1, control 2, end) triples. Empty when
    * ELK routed no section (a self-loop may get none). `label` is the label's
    * top-left. */
  case class DiagramEdgePath(points: Seq[(Double, Double)], label: Option[(Double, Double)])
  case class DiagramLayout(nodes: Map[String, (Double, Double)], edges: Map[String, DiagramEdgePath],
                           width: Double, height: Double)

  @js.native private trait ElkLabeledEdge extends ElkEdge {
    def id: String = js.native
    def labels: js.UndefOr[js.Array[ElkPoint]] = js.native
  }

  /** Lay out a state diagram (nodes + labelled directed edges, self-loops
    * allowed) top-down with elkjs. Coordinates are in the diagram's own space
    * (root-relative; the root is the edges' container, so label x/y need no
    * offset). Self-loops use ELK's default loop handling. */
  def layoutDiagram(nodes: Seq[DiagramNode], edges: Seq[DiagramEdge]): Future[DiagramLayout] = {
    val children = nodes.map { n =>
      val c = js.Dynamic.literal(id = n.id, width = n.width, height = n.height)
      if (n.first)
        c.updateDynamic("layoutOptions")(js.Dynamic.literal("elk.layered.layering.layerConstraint" -> "FIRST"))
      c
    }.toJSArray
    val elkEdges = edges.map { e =>
      val edge = js.Dynamic.literal(id = e.id, sources = js.Array[String](e.from), targets = js.Array[String](e.to))
      if (e.labelWidth > 0 || e.labelHeight > 0)
        edge.updateDynamic("labels")(js.Array(js.Dynamic.literal(
          text = e.id, width = e.labelWidth, height = e.labelHeight)))
      edge
    }.toJSArray
    val opts = js.Dynamic.literal(
      "elk.algorithm"   -> "layered",
      "elk.direction"   -> "DOWN",
      "elk.edgeRouting" -> "SPLINES",
      "elk.edgeLabels.placement" -> "CENTER",
      "elk.layered.considerModelOrder.strategy" -> "NONE",
      // Break cycles depth-first from the sources: the start dot (pinned to the
      // first layer) is the only one, so the initial state sits right under it
      // and the arrows that return to it point up.
      "elk.layered.cycleBreaking.strategy" -> "DEPTH_FIRST",
      "elk.spacing.nodeNode" -> "40",
      "elk.layered.spacing.nodeNodeBetweenLayers" -> "56",
      "elk.spacing.edgeLabel" -> "6",
      // One layered graph, not packed components: states with no transition
      // (the fallback diagram) line up in a row instead of a scattered pack.
      "elk.separateConnectedComponents" -> "false",
    )
    val graph = js.Dynamic.literal(id = "root", layoutOptions = opts, children = children, edges = elkEdges)
    val laid: Future[js.Dynamic] = engine.layout(graph).asInstanceOf[js.Promise[js.Dynamic]]
    laid.map { res =>
      val g = res.asInstanceOf[ElkGraph]
      val nodePos = g.children.map(c => c.id -> ((c.x, c.y))).toMap
      val paths = g.edges.toSeq.map { e0 =>
        val e   = e0.asInstanceOf[ElkLabeledEdge]
        val pts = scala.collection.mutable.ArrayBuffer.empty[(Double, Double)]
        e.sections.toOption.flatMap(_.headOption).foreach { sec =>
          pts += ((sec.startPoint.x, sec.startPoint.y))
          sec.bendPoints.toOption.foreach(_.foreach(bp => pts += ((bp.x, bp.y))))
          pts += ((sec.endPoint.x, sec.endPoint.y))
        }
        val label = e.labels.toOption.flatMap(_.headOption).map(l => (l.x, l.y))
        e.id -> DiagramEdgePath(pts.toSeq, label)
      }.toMap
      DiagramLayout(nodePos, paths, g.width, g.height)
    }
  }

}
