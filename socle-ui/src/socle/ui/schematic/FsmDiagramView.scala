package socle.ui.schematic

import com.raquo.laminar.api.L._
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}
import socle.schema.{FsmDiagram, FsmTransition, SourceRef}
import socle.js.BrowserLayout
import socle.js.BrowserLayout.{DiagramEdge, DiagramLayout, DiagramNode}
import socle.ui.theme.Tokens

/** An FSM block opened into its state diagram (slice 3), shown in the
  * schematic pane in place of the schematic. States are pill-shaped boxes, the
  * initial one ringed like the FSM box and entered from a start dot; each
  * transition is an arrow with its condition; transitions from any state leave
  * a small `*` marker. elkjs lays it out top-down ([[BrowserLayout.layoutDiagram]]).
  * The diagram fits the pane by default. As on the schematic, the wheel zooms
  * at the pointer, a drag pans, `+` / `-` zoom and `f` (or `0`) fits; `100%`
  * shows it at natural size. The pane scrolls when the drawing is larger.
  *
  * Clicking a state or a transition calls `openSource` with its source. Test
  * hooks: `data-fsm-diagram` (and `data-fsm-ready` once laid out),
  * `data-fsm-scroll` (the scrolling pane), `data-fsm-zoom` (`fit` / `1:1`, the 100% button),
  * `data-fsm-scale` (the scale shown),
  * `data-fsm-state` / `data-initial`, `data-fsm-transition` / `data-from` /
  * `data-to` (with `data-fsm-path` and `data-fsm-label` inside),
  * `data-fsm-any`, `data-fsm-start` (the start dot), `data-fsm-note`. */
private[schematic] object FsmDiagramView {

  private def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

  private val NameSize  = 12.0
  private val LabelSize = 10.5
  private val StateH    = 32.0
  private val LabelH    = 16.0
  private val Margin    = 28.0
  private val Pad       = 24.0  // the scrolling pane's padding around the drawing
  private val LabelMax  = 60
  private val InitId    = "init"
  private val AnyId     = "any"

  /** A label longer than [[LabelMax]] characters, cut with a trailing ellipsis. */
  private def shorten(s: String): String = if (s.length > LabelMax) s.take(LabelMax - 1) + "…" else s

  private lazy val measureCtx: dom.CanvasRenderingContext2D =
    dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas].getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]

  private def stateId(i: Int): String = s"s:$i"

  private def fmt(p: (Double, Double)): String = f"${p._1}%.1f ${p._2}%.1f"
  private def num(v: Double): String = f"$v%.1f"

  /** The note shown above the diagram: the diagram's own, then how many
    * transitions point at a state the diagram does not list. */
  private def noteText(fsm: FsmDiagram, unknown: Int): String = {
    val dropped =
      if (unknown == 0) ""
      else if (unknown == 1) "1 transition to an unknown state is not shown."
      else s"$unknown transitions to unknown states are not shown."
    Seq(fsm.note, dropped).filter(_.nonEmpty).mkString(" ")
  }

  def render(blockId: String, fsm: FsmDiagram, openSource: SourceRef => Unit, nav: Option[Element] = None): HtmlElement = {
    // The theme's `--mono` stack, resolved once: a canvas cannot read CSS variables.
    val mono = dom.window.getComputedStyle(dom.document.documentElement).getPropertyValue("--mono").trim match {
      case "" => "monospace"
      case m  => m
    }
    def textW(text: String, size: Double, weight: Int = 400): Double = {
      measureCtx.font = s"$weight ${size}px $mono"
      measureCtx.measureText(text).width
    }

    val idOf: Map[String, String] = fsm.states.iterator.zipWithIndex.map { case (s, i) => s.name -> stateId(i) }.toMap
    val initial = fsm.states.indexWhere(_.initial)
    // Transitions whose ends are drawn: one whose end is not a listed state has
    // no box to attach to, and is counted in the note instead.
    val shown = fsm.transitions.zipWithIndex.filter { case (t, _) =>
      idOf.contains(t.to) && (t.from == "*" || idOf.contains(t.from))
    }
    val unknown = fsm.transitions.length - shown.length
    if (unknown > 0)
      dom.console.warn(s"[socle] $blockId: $unknown transition(s) to a state the diagram does not list are not shown")
    val note    = noteText(fsm, unknown)
    val hasAny  = shown.exists(_._1.from == "*")
    val labelW: Map[Int, Double] = shown.map { case (t, i) =>
      i -> (if (t.label.isEmpty) 0.0 else textW(shorten(t.label), LabelSize) + 10)
    }.toMap
    val sizes: Map[String, (Double, Double)] =
      fsm.states.iterator.zipWithIndex.map { case (s, i) =>
        stateId(i) -> ((math.max(72.0, textW(s.name, NameSize, 600) + 32), StateH))
      }.toMap ++
      Option.when(initial >= 0)(InitId -> ((12.0, 12.0))) ++
      Option.when(hasAny)(AnyId -> ((22.0, 22.0)))
    val nodes = sizes.toSeq.sortBy(_._1).map { case (id, (w, h)) => DiagramNode(id, w, h, first = id == InitId) }
    val edges = shown.map { case (t, i) =>
      val lw = labelW(i)
      DiagramEdge(s"e:$i", if (t.from == "*") AnyId else idOf(t.from), idOf(t.to), lw, if (lw > 0) LabelH else 0)
    } ++ Option.when(initial >= 0)(DiagramEdge("e:init", InitId, stateId(initial), 0, 0))

    // None while laying out, then the layout or the failure's message.
    val layoutVar = Var[Option[Either[String, DiagramLayout]]](None)
    val zoomVar   = Var(Option.empty[Double]) // None: fit the pane (default); else the scale
    val paneVar   = Var((0.0, 0.0))    // the scrolling pane's client size
    var paneNode: dom.html.Element = null
    var observer: dom.ResizeObserver = null
    var curScale  = 1.0                // the scale drawn now (fit or chosen)
    def measure(): Unit = if (paneNode != null) paneVar.set((paneNode.clientWidth.toDouble, paneNode.clientHeight.toDouble))

    // Zoom by `f` keeping the drawing point under (mx, my) (pane coordinates) in place.
    def zoomAt(f: Double, mx: Double, my: Double): Unit = if (paneNode != null) {
      val svgEl = paneNode.querySelector("svg").asInstanceOf[dom.html.Element]
      if (svgEl != null) {
        val s0 = curScale
        val s1 = math.max(0.1, math.min(4.0, s0 * f))
        val cx = (paneNode.scrollLeft + mx - svgEl.offsetLeft) / s0
        val cy = (paneNode.scrollTop + my - svgEl.offsetTop) / s0
        zoomVar.set(Some(s1))
        paneNode.scrollLeft = cx * s1 + svgEl.offsetLeft - mx
        paneNode.scrollTop = cy * s1 + svgEl.offsetTop - my
      }
    }
    def zoomCentre(f: Double): Unit = if (paneNode != null) zoomAt(f, paneNode.clientWidth / 2.0, paneNode.clientHeight / 2.0)

    // A drag pans the pane; a press that moves less than 4 px is still a click.
    var drag: Option[(Double, Double, Double, Double)] = None // start x, y, scrollLeft, scrollTop
    var dragged = false
    val keys: scala.scalajs.js.Function1[dom.KeyboardEvent, Unit] = (e: dom.KeyboardEvent) => {
      val t = e.target match { case el: dom.Element => el; case _ => null }
      val typing = t != null && t.closest("input, textarea, select, [contenteditable]:not([contenteditable=false])") != null
      if (!typing && !e.ctrlKey && !e.metaKey && !e.altKey) e.key match {
        case "+" | "=" => e.preventDefault(); zoomCentre(1.25)
        case "-" | "_" => e.preventDefault(); zoomCentre(0.8)
        case "f" | "0" => e.preventDefault(); zoomVar.set(None)
        case _         =>
      }
    }

    val zbtn = s"border:none;background:transparent;cursor:pointer;padding:3px 6px;font-family:${Tokens.mono};font-size:11px;border-radius:3px"
    def zoomBtn(mode: String, label: String, at: Option[Double], tip: String) =
      button(dataAttr("fsm-zoom") := mode, title := tip,
        styleAttr <-- zoomVar.signal.map(z => zbtn + s";color:${if (z == at) Tokens.accent else Tokens.ink3}"),
        onClick --> { (_: dom.MouseEvent) => zoomVar.set(at) }, label)

    div(
      dataAttr("fsm-diagram") := blockId,
      dataAttr("fsm-ready") <-- layoutVar.signal.map(l => if (l.exists(_.isRight)) "1" else "0"),
      dataAttr("pan-block") := "1",
      dataAttr("no-pan") := "1", // keeps its own scroll and pan
      styleAttr := s"position:absolute;inset:0;z-index:5;display:flex;flex-direction:column;background:${Tokens.bg};cursor:default",
      onMountUnmountCallback(
        mount = { _ => dom.window.addEventListener("keydown", keys) },
        unmount = { _ => dom.window.removeEventListener("keydown", keys) }),
      onMountCallback { _ =>
        BrowserLayout.layoutDiagram(nodes, edges).onComplete {
          case Success(l) => layoutVar.set(Some(Right(l)))
          case Failure(e) =>
            dom.console.warn("[socle] state diagram layout failed: " + e.getMessage)
            layoutVar.set(Some(Left(e.getMessage)))
        }
      },
      // The schematic's Back / Forward chip, 12 px in like its chrome, and the
      // note beside it (never over it).
      Option.when(nav.nonEmpty || note.nonEmpty)(div(
        styleAttr := "flex:none;display:flex;align-items:flex-start;gap:8px;margin:12px 12px 0",
        nav.toSeq,
      Option.when(note.nonEmpty)(div(
        dataAttr("fsm-note") := "1",
        styleAttr := s"flex:1 1 auto;min-width:0;max-width:460px;box-sizing:border-box;padding:6px 10px;" +
          s"border:1px solid ${Tokens.border};border-left:3px solid ${Tokens.warn};border-radius:4px;background:${Tokens.panel};" +
          s"font-family:${Tokens.mono};font-size:11px;line-height:1.45;color:${Tokens.ink2}",
        note)))),
      div(
        dataAttr("fsm-scroll") := "1",
        styleAttr := "flex:1 1 auto;min-height:0;overflow:auto;position:relative;cursor:grab;user-select:none",
        // The wheel (and a trackpad pinch) zooms at the pointer; Shift + wheel scrolls sideways.
        onWheel --> { (e: dom.WheelEvent) =>
          if (!e.shiftKey && paneNode != null) {
            e.preventDefault()
            val r = paneNode.getBoundingClientRect()
            zoomAt(math.exp(-e.deltaY * (if (e.ctrlKey) 0.01 else 0.0015)), e.clientX - r.left, e.clientY - r.top)
          }
        },
        onPointerDown --> { (e: dom.PointerEvent) =>
          if (e.button == 0 && paneNode != null) {
            drag = Some((e.clientX, e.clientY, paneNode.scrollLeft, paneNode.scrollTop)); dragged = false
          }
        },
        onPointerMove --> { (e: dom.PointerEvent) =>
          drag.foreach { case (x0, y0, sl, st) =>
            val dx = e.clientX - x0; val dy = e.clientY - y0
            if (!dragged && math.hypot(dx, dy) > 4) {
              dragged = true
              paneNode.asInstanceOf[scala.scalajs.js.Dynamic].setPointerCapture(e.pointerId)
              paneNode.style.cursor = "grabbing"
            }
            if (dragged) { paneNode.scrollLeft = sl - dx; paneNode.scrollTop = st - dy }
          }
        },
        onPointerUp --> { (_: dom.PointerEvent) => drag = None; if (paneNode != null) paneNode.style.cursor = "grab" },
        onPointerCancel --> { (_: dom.PointerEvent) => drag = None; dragged = false },
        // The click that ends a drag opens nothing.
        inContext { el =>
          el.ref.addEventListener("click", (e: dom.MouseEvent) => {
            if (dragged) { e.stopPropagation(); e.preventDefault(); dragged = false }
          }, useCapture = true)
          emptyMod
        },
        // The pane's size, whatever changes it (window, side panels, bottom panel).
        onMountUnmountCallback(
          mount = { ctx =>
            paneNode = ctx.thisNode.ref
            measure()
            observer = new dom.ResizeObserver((_, _) => measure())
            observer.observe(paneNode)
          },
          unmount = { _ =>
            if (observer != null) observer.disconnect()
            observer = null
            paneNode = null
          }),
        child <-- layoutVar.signal.map {
          case None => span(styleAttr := s"position:absolute;top:16px;left:50%;transform:translateX(-50%);font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink3}", "laying out…")
          case Some(Left(msg)) => span(styleAttr := s"position:absolute;top:16px;left:16px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink3}",
            s"The state diagram could not be laid out: $msg")
          case Some(Right(l)) =>
            drawing(fsm, l, sizes, idOf, shown, labelW, initial, openSource, zoomVar.signal, paneVar.signal, curScale = _)
        },
      ),
      // fit / 100%, bottom-right like the schematic's zoom controls.
      div(styleAttr := s"position:absolute;bottom:12px;right:12px;display:flex;align-items:center;gap:2px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:2px",
        zoomBtn("fit", "fit", None, "Fit the diagram to the pane (f)"),
        zoomBtn("1:1", "100%", Some(1.0), "Show the diagram at its natural size; the wheel zooms, a drag pans")),
    )
  }

  /** The laid-out diagram as one SVG: scaled down to fit the pane when `fit`
    * (never up), else at natural size; centered, scrolling when larger. */
  private def drawing(fsm: FsmDiagram, l: DiagramLayout, sizes: Map[String, (Double, Double)],
                      idOf: Map[String, String], shown: Seq[(FsmTransition, Int)], labelW: Map[Int, Double],
                      initial: Int, openSource: SourceRef => Unit,
                      zoomSig: Signal[Option[Double]], paneSig: Signal[(Double, Double)],
                      onScale: Double => Unit): HtmlElement = {
    // Everything drawn (boxes, path and control points, labels) widens these
    // bounds, so a self-loop routed to negative x or a far label stays inside.
    var minX = Double.MaxValue; var minY = Double.MaxValue
    var maxX = Double.MinValue; var maxY = Double.MinValue
    def include(x: Double, y: Double): Unit = {
      minX = math.min(minX, x); minY = math.min(minY, y); maxX = math.max(maxX, x); maxY = math.max(maxY, y)
    }
    def includeRect(x: Double, y: Double, w: Double, h: Double): Unit = { include(x, y); include(x + w, y + h) }

    def box(id: String): Option[(Double, Double, Double, Double)] =
      for { (x, y) <- l.nodes.get(id); (w, h) <- sizes.get(id) } yield (x, y, w, h)

    // An edge's path and the two points that give its arrival direction.
    def edgePath(id: String, from: String, to: String): Option[(String, (Double, Double), (Double, Double))] = {
      val pts = l.edges.get(id).map(_.points).getOrElse(Nil)
      pts.foreach { case (x, y) => include(x, y) }
      if (pts.length >= 4 && (pts.length - 1) % 3 == 0) {
        val segs = pts.drop(1).grouped(3).map(g => s"C ${fmt(g(0))} ${fmt(g(1))} ${fmt(g(2))}").mkString(" ")
        val prev = if (pts(pts.length - 2) != pts.last) pts(pts.length - 2) else pts(pts.length - 3)
        Some((s"M ${fmt(pts.head)} $segs", prev, pts.last))
      } else if (pts.length >= 2) {
        Some(("M " + pts.map(fmt).mkString(" L "), pts(pts.length - 2), pts.last))
      } else if (from == to) {
        // A self-loop ELK routed no section for: a loop off the box's right side.
        box(from).map { case (x, y, w, h) =>
          val s = (x + w, y + h * 0.3); val e = (x + w, y + h * 0.7)
          val c1 = (x + w + 40, y + h * 0.3 - 16); val c2 = (x + w + 40, y + h * 0.7 + 16)
          Seq(s, e, c1, c2).foreach { case (px, py) => include(px, py) }
          (s"M ${fmt(s)} C ${fmt(c1)} ${fmt(c2)} ${fmt(e)}", c2, e)
        }
      } else
        for { (fx, fy, fw, fh) <- box(from); (tx, ty, tw, _) <- box(to) } yield {
          val s = (fx + fw / 2, fy + fh); val e = (tx + tw / 2, ty)
          (s"M ${fmt(s)} L ${fmt(e)}", s, e)
        }
    }

    // A filled arrowhead with its tip at `tip`, pointing away from `from`.
    def arrowHead(from: (Double, Double), tip: (Double, Double), color: String): SvgElement = {
      val dx = tip._1 - from._1; val dy = tip._2 - from._2
      val len = math.max(1e-6, math.hypot(dx, dy))
      val ux = dx / len; val uy = dy / len
      val bx = tip._1 - 8 * ux; val by = tip._2 - 8 * uy
      val d = s"M ${fmt(tip)} L ${fmt((bx - 4 * uy, by + 4 * ux))} L ${fmt((bx + 4 * uy, by - 4 * ux))} Z"
      svg.path(sa("d") := d, sa("style") := s"fill:$color;stroke:none")
    }

    val transitionEls: Seq[SvgElement] = shown.flatMap { case (t, i) =>
      val eid  = s"e:$i"
      val from = if (t.from == "*") AnyId else idOf(t.from)
      edgePath(eid, from, idOf(t.to)).map { case (d, prev, tip) =>
        val text = shorten(t.label)
        val lw   = labelW(i)
        // The label's top-left: where ELK put it, else beside the loop or mid-arrow.
        val lpos: (Double, Double) = l.edges.get(eid).flatMap(_.label).getOrElse {
          if (from == idOf(t.to)) box(from).map { case (x, y, w, h) => (x + w + 46, y + h / 2 - LabelH / 2) }.getOrElse(prev)
          else ((prev._1 + tip._1) / 2 - lw / 2, (prev._2 + tip._2) / 2 - LabelH / 2)
        }
        if (lw > 0) includeRect(lpos._1, lpos._2, lw, LabelH)
        val hover = Var(false)
        val stroke = hover.signal.map(h => if (h) Tokens.accent else Tokens.ink3)
        svg.g(
          sa("data-fsm-transition") := eid,
          sa("data-from") := t.from,
          sa("data-to") := t.to,
          sa("style") := "cursor:pointer",
          onMouseEnter --> { (_: dom.MouseEvent) => hover.set(true) },
          onMouseLeave --> { (_: dom.MouseEvent) => hover.set(false) },
          onClick --> { (_: dom.MouseEvent) => openSource(t.source) },
          svg.titleTag(s"${t.from} → ${t.to}" + (if (t.label.nonEmpty) s"\n${t.label}" else "")),
          svg.path(sa("d") := d, sa("style") := "stroke:transparent;stroke-width:12;fill:none;pointer-events:stroke"),
          svg.path(sa("d") := d, sa("data-fsm-path") := "1",
            sa("style") <-- stroke.map(c => s"stroke:$c;stroke-width:1.4;fill:none;stroke-linecap:round")),
          child <-- stroke.map(c => arrowHead(prev, tip, c)),
          Option.when(t.label.nonEmpty)(svg.g(
            sa("data-fsm-label") := "1",
            svg.rect(sa("x") := num(lpos._1), sa("y") := num(lpos._2), sa("width") := num(lw), sa("height") := num(LabelH),
              sa("rx") := "3", sa("style") := s"fill:${Tokens.bg};stroke:none"),
            svg.text(sa("x") := num(lpos._1 + lw / 2), sa("y") := num(lpos._2 + LabelH / 2),
              sa("text-anchor") := "middle", sa("dominant-baseline") := "central",
              sa("style") <-- hover.signal.map(h => s"font-family:${Tokens.mono};font-size:${LabelSize}px;fill:${if (h) Tokens.ink else Tokens.ink2}"),
              text),
          )),
        )
      }
    }

    val stateEls: Seq[SvgElement] = fsm.states.zipWithIndex.flatMap { case (s, i) =>
      box(stateId(i)).map { case (x, y, w, h) =>
        includeRect(x, y, w, h)
        val hover = Var(false)
        svg.g(
          sa("data-fsm-state") := s.name,
          Option.when(s.initial)(sa("data-initial") := "1"),
          sa("style") := "cursor:pointer",
          onMouseEnter --> { (_: dom.MouseEvent) => hover.set(true) },
          onMouseLeave --> { (_: dom.MouseEvent) => hover.set(false) },
          onClick --> { (_: dom.MouseEvent) => openSource(s.source) },
          svg.titleTag(if (s.initial) s"${s.name} (initial)" else s.name),
          svg.rect(sa("x") := num(x), sa("y") := num(y), sa("width") := num(w), sa("height") := num(h),
            sa("rx") := num(h / 2),
            sa("style") <-- hover.signal.map(hv => s"fill:${Tokens.panel};stroke:${if (hv || s.initial) Tokens.accent else Tokens.border};stroke-width:1.25")),
          // The initial state's inner ring: the FSM box's double border.
          Option.when(s.initial)(svg.rect(sa("x") := num(x + 3), sa("y") := num(y + 3), sa("width") := num(w - 6),
            sa("height") := num(h - 6), sa("rx") := num(h / 2 - 3),
            sa("style") := s"fill:${Tokens.accentBg};stroke:${Tokens.accentLine};stroke-width:1;pointer-events:none")),
          svg.text(sa("x") := num(x + w / 2), sa("y") := num(y + h / 2),
            sa("text-anchor") := "middle", sa("dominant-baseline") := "central",
            sa("style") := s"font-family:${Tokens.mono};font-size:${NameSize}px;font-weight:600;fill:${Tokens.ink};pointer-events:none",
            s.name),
        )
      }
    }

    // The start dot and its arrow into the initial state.
    val initEls: Seq[SvgElement] =
      if (initial < 0) Nil
      else box(InitId).toSeq.flatMap { case (x, y, w, h) =>
        includeRect(x, y, w, h)
        Seq[SvgElement](
          svg.circle(sa("data-fsm-start") := "1", sa("cx") := num(x + w / 2), sa("cy") := num(y + h / 2), sa("r") := "5",
            sa("style") := s"fill:${Tokens.ink2}"),
        ) ++ edgePath("e:init", InitId, stateId(initial)).toSeq.flatMap { case (d, prev, tip) =>
          Seq[SvgElement](svg.path(sa("d") := d, sa("style") := s"stroke:${Tokens.ink2};stroke-width:1.4;fill:none"),
            arrowHead(prev, tip, Tokens.ink2))
        }
      }

    // The "any state" marker the `*` transitions leave from.
    val anyEls: Seq[SvgElement] = box(AnyId).toSeq.map { case (x, y, w, h) =>
      includeRect(x, y, w, h)
      svg.g(
        sa("data-fsm-any") := "1",
        svg.titleTag("from any state"),
        svg.circle(sa("cx") := num(x + w / 2), sa("cy") := num(y + h / 2), sa("r") := num(w / 2),
          sa("style") := s"fill:${Tokens.panelAlt};stroke:${Tokens.ink3};stroke-width:1.25;stroke-dasharray:3 2"),
        svg.text(sa("x") := num(x + w / 2), sa("y") := num(y + h / 2 + 2),
          sa("text-anchor") := "middle", sa("dominant-baseline") := "central",
          sa("style") := s"font-family:${Tokens.mono};font-size:14px;font-weight:600;fill:${Tokens.ink2}",
          "*"),
      )
    }

    if (minX > maxX) { minX = 0; minY = 0; maxX = 1; maxY = 1 } // nothing drawn
    val vx = minX - Margin; val vy = minY - Margin
    val vw = maxX - minX + 2 * Margin; val vh = maxY - minY + 2 * Margin
    // Fit scales down only (a small diagram stays at natural size).
    val scaleSig = Signal.combine(zoomSig, paneSig).map {
      case (Some(z), _)                  => z
      case (None, (pw, ph)) if pw <= 0 || ph <= 0 => 1.0
      case (None, (pw, ph))              => math.max(0.05, math.min(1.0, math.min((pw - 2 * Pad) / vw, (ph - 2 * Pad) / vh)))
    }
    // max-content + auto margins: centered when smaller, scrollable from its
    // left/top edge when larger (plain flex centering would clip the left side).
    div(
      styleAttr := s"width:max-content;min-width:100%;min-height:100%;box-sizing:border-box;display:flex;padding:${Pad}px",
      svg.svg(
        sa("viewBox") := s"${num(vx)} ${num(vy)} ${num(vw)} ${num(vh)}",
        sa("width") <-- scaleSig.map(s => num(vw * s)),
        sa("height") <-- scaleSig.map(s => num(vh * s)),
        sa("style") := "flex-shrink:0;margin:auto;overflow:visible",
        sa("data-fsm-scale") <-- scaleSig.map(s => f"$s%.3f"),
        scaleSig --> { (s: Double) => onScale(s) },
        // Transitions first, then everything drawn over them.
        transitionEls ++ initEls ++ anyEls ++ stateEls,
      ),
    )
  }
}
