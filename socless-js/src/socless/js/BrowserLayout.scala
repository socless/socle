package socless.js

import socless.{Design, ModuleDef, LayoutCore}
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** Browser schematic layout: the elkjs counterpart of the JVM [[socless.Layout]]
  * ELK shell (issue #167). Shares [[LayoutCore.prepare]] with the backend, then
  * builds an elkjs JSON graph, runs it on `window.ELK`, and extracts geometry in
  * the exact shape the UI already consumes for the baked layout:
  *
  * {{{ { nodes:[{id,x,y,w,h,kind,ports:[{path,x,y,side,interface,label,bundle}]}],
  *       edges:[{from,to,points:[[x,y]],junctionPoints:[[x,y]]}], w, h } }}}
  */
object BrowserLayout {

  /** elkjs node id: combine id + kind so a boundary stub whose path equals an
    * instance name cannot collide (the JVM keys its node map by (id, kind)). */
  private def synthId(n: LayoutCore.PreparedNode): String = s"${n.kind}${n.id}"

  /** Build the elkjs graph (a plain JS object) from the prepared graph.
    *
    * `overrides` (may be null) carries the UI's expansion-aware geometry per
    * node id: `{ height, pins:{ path -> yWithinBox } }`. Applying it means a box
    * whose bundle was expanded is laid out at its grown height with its top-level
    * pins at their shifted rows, so elkjs re-routes cleanly to the moved pins
    * (this is what removes the up-then-down kink, issue #164). */
  private def buildGraph(prep: LayoutCore.Prepared, mode: String, overrides: js.Dynamic): js.Dynamic = {
    val ovDict =
      if (overrides == null || js.isUndefined(overrides)) js.Dictionary.empty[js.Dynamic]
      else overrides.asInstanceOf[js.Dictionary[js.Dynamic]]

    val children = prep.nodes.map { n =>
      val nodeOv  = ovDict.get(n.id)
      val height  = nodeOv.flatMap(o => o.height.asInstanceOf[js.UndefOr[Double]].toOption).getOrElse(n.height)
      val pinYOv  = nodeOv.flatMap(o => o.pins.asInstanceOf[js.UndefOr[js.Dictionary[Double]]].toOption)
                          .getOrElse(js.Dictionary.empty[Double])
      val ports = n.pins.map { p =>
        val py = pinYOv.getOrElse(p.path, p.y)
        js.Dynamic.literal(
          id = p.path,
          x = p.x, y = py, width = 6, height = 6,
          layoutOptions = js.Dynamic.literal("elk.port.side" -> (if (p.side == "EAST") "EAST" else "WEST")),
        )
      }.toJSArray
      // Boundary stubs let elkjs place ports on their side; instance nodes pin
      // each port at its precomputed (x, y) so the header never overlaps a pin.
      val portConstraints = if (n.kind == "boundary") "FIXED_SIDE" else "FIXED_POS"
      val nodeOpts = js.Dynamic.literal("elk.portConstraints" -> portConstraints)
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

  /** Pull the laid-out geometry back into the UI's layout shape. */
  private def extract(prep: LayoutCore.Prepared, res: js.Dynamic): js.Dynamic = {
    val resChildren = res.children.asInstanceOf[js.Array[js.Dynamic]]
    val childById = js.Dictionary.empty[js.Dynamic]
    resChildren.foreach(c => childById(c.id.asInstanceOf[String]) = c)

    val nodes = prep.nodes.map { n =>
      val c  = childById(synthId(n))
      val nx = c.x.asInstanceOf[Double]
      val ny = c.y.asInstanceOf[Double]
      // Index the laid ports by id (= pin path) to read each one's box-relative
      // position; absolute = node origin + port offset (handles FIXED_SIDE too).
      val portById = js.Dictionary.empty[js.Dynamic]
      c.ports.asInstanceOf[js.Array[js.Dynamic]].foreach(p => portById(p.id.asInstanceOf[String]) = p)
      val ports = n.pins.map { p =>
        val lp = portById(p.path)
        js.Dynamic.literal(
          path = p.path,
          x = (nx + lp.x.asInstanceOf[Double]).toInt,
          y = (ny + lp.y.asInstanceOf[Double]).toInt,
          side = p.side, interface = p.interface, label = p.label, bundle = p.bundle,
        )
      }.toJSArray
      js.Dynamic.literal(
        id = n.id, kind = n.kind,
        x = nx.toInt, y = ny.toInt,
        w = c.width.asInstanceOf[Double].toInt, h = c.height.asInstanceOf[Double].toInt,
        ports = ports,
      )
    }.toJSArray

    val resEdges = res.edges.asInstanceOf[js.Array[js.Dynamic]]
    val edges = resEdges.flatMap { e =>
      val sources = e.sources.asInstanceOf[js.Array[String]]
      val targets = e.targets.asInstanceOf[js.Array[String]]
      val sections = e.sections.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
      val pts = js.Array[js.Array[Int]]()
      sections.toOption.flatMap(_.headOption).foreach { sec =>
        val sp = sec.startPoint.asInstanceOf[js.Dynamic]
        pts.push(js.Array(sp.x.asInstanceOf[Double].toInt, sp.y.asInstanceOf[Double].toInt))
        sec.bendPoints.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toOption.foreach(_.foreach { bp =>
          pts.push(js.Array(bp.x.asInstanceOf[Double].toInt, bp.y.asInstanceOf[Double].toInt))
        })
        val ep = sec.endPoint.asInstanceOf[js.Dynamic]
        pts.push(js.Array(ep.x.asInstanceOf[Double].toInt, ep.y.asInstanceOf[Double].toInt))
      }
      // junctionPoints mark where this edge shares a segment with others (the
      // fan-out branch dots, issue #166); pass them through when present.
      val jps = js.Array[js.Array[Int]]()
      e.junctionPoints.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toOption.foreach(_.foreach { jp =>
        jps.push(js.Array(jp.x.asInstanceOf[Double].toInt, jp.y.asInstanceOf[Double].toInt))
      })
      if (pts.length < 2) js.Array[js.Dynamic]()
      else js.Array(js.Dynamic.literal(
        from = targets(0), to = sources(0), points = pts, junctionPoints = jps,
      ))
    }

    js.Dynamic.literal(
      nodes = nodes, edges = edges,
      w = res.width.asInstanceOf[Double].toInt.max(1),
      h = res.height.asInstanceOf[Double].toInt.max(1),
    )
  }

  /** Lay out one module with elkjs, returning a Promise of the UI layout shape. */
  def run(module: ModuleDef, design: Design, mode: String, overrides: js.Dynamic): js.Promise[js.Dynamic] = {
    val prep  = LayoutCore.prepare(module, design)
    val graph = buildGraph(prep, mode, overrides)
    val elk   = js.Dynamic.newInstance(js.Dynamic.global.ELK)()
    val laid: Future[js.Dynamic] = elk.layout(graph).asInstanceOf[js.Promise[js.Dynamic]]
    laid.map(res => extract(prep, res)).toJSPromise
  }
}
