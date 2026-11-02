package socle.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js

/** Pointer, wheel and keyboard navigation of the schematic viewport.
  *
  *  - A press anywhere (background, box, wire, pin, title) that moves more
  *    than [[DragThreshold]] px pans the view: pointer capture, the grabbing
  *    cursor, one pan per animation frame. A press that moves less is left to
  *    the element under the pointer as a click (select, drill, bundle toggle).
  *    The middle button pans at once.
  *  - Elements with their own drag, scroll or typing are left alone: anything
  *    under `[data-no-pan]` (the minimap, the FSM diagram), inputs and
  *    editable content.
  *  - The wheel is classified by [[classify]] and applied once per frame.
  *  - The arrow, `+`, `-`, `0`, `f` and `z` keys, and Alt+Left / Alt+Right,
  *    act only with the focus on the body or the schematic ([[keysAllowed]]).
  *  - The mouse's back and forward buttons over the schematic walk the view
  *    history ([[ViewHistory]]) instead of the browser's.
  */
object Gestures {

  /** Distance (px, straight line) a left press moves before it pans. */
  val DragThreshold = 4.0

  /** What a wheel event asks for. */
  sealed trait WheelIntent
  object WheelIntent {
    /** Zoom at the cursor by `exp(log)`. */
    final case class Zoom(log: Double) extends WheelIntent
    /** Move the content by (dx, dy) screen px. */
    final case class Pan(dx: Double, dy: Double) extends WheelIntent
  }

  /** The parts of a wheel event the classification reads. */
  final case class WheelIn(deltaX: Double, deltaY: Double, deltaMode: Int, ctrl: Boolean, shift: Boolean)

  // Zoom per px of deltaY: a mouse notch (100 px) is about x0.86; a pinch
  // reports a few px per event, so it gets a larger rate.
  private val MouseZoomRate = 0.0015
  private val PinchZoomRate = 0.01

  private def fractional(d: Double): Boolean = d != math.rint(d)
  // deltaMode 1 (lines, Firefox) and 2 (pages) in px.
  private def inPx(d: Double, mode: Int): Double = mode match {
    case 1 => d * 16
    case 2 => d * 400
    case _ => d
  }

  def classify(e: dom.WheelEvent): WheelIntent =
    classify(WheelIn(e.deltaX, e.deltaY, e.deltaMode, e.ctrlKey, e.shiftKey))

  /** The wheel heuristic. Browsers report a mouse wheel, a trackpad swipe and
    * a trackpad pinch all as `wheel` events, so they are told apart by their
    * modifiers and deltas, checked in this order:
    *
    *  1. Ctrl: a pinch arrives as Ctrl + wheel with a small fractional deltaY
    *     (|deltaY| < 10, not an integer, in px mode): zoom. Any other Ctrl +
    *     wheel (a real Ctrl and a mouse notch of 100, or Firefox lines) scrolls
    *     vertically.
    *  2. Shift: scrolls horizontally. Chrome turns Shift + wheel into deltaX
    *     on Linux and Windows, so deltaY is used when set, else deltaX.
    *  3. A trackpad swipe, in px mode: any deltaX, or a small (|deltaY| < 50)
    *     fractional deltaY, pans. The size bound keeps a mouse notch at a
    *     fractional page zoom or device pixel ratio (66.67, 90.91) a zoom.
    *  4. Otherwise (a mouse wheel, Firefox lines): zoom at the cursor.
    *
    * Known misses: a trackpad that reports whole-number vertical deltas with no
    * deltaX zooms instead of panning, and a high-resolution mouse wheel that
    * reports small fractional deltas pans instead of zooming.
    */
  def classify(w: WheelIn): WheelIntent = {
    import WheelIntent._
    val dx = inPx(w.deltaX, w.deltaMode)
    val dy = inPx(w.deltaY, w.deltaMode)
    val pxMode = w.deltaMode == 0
    if (w.ctrl) {
      if (pxMode && fractional(w.deltaY) && math.abs(w.deltaY) < 10) Zoom(-w.deltaY * PinchZoomRate)
      else Pan(0, -dy)
    }
    else if (w.shift) Pan(-(if (dy != 0) dy else dx), 0)
    else if (pxMode && (w.deltaX != 0 || (fractional(w.deltaY) && math.abs(w.deltaY) < 50))) Pan(-dx, -dy)
    else Zoom(-dy * MouseZoomRate)
  }

  /** Whether the schematic keys may act with this element focused:
    *  - an element of the schematic pane that is not a field;
    *  - the body (nothing focused), unless the last press was outside the
    *    schematic: the hierarchy rows and most panel content are not
    *    focusable, so a click there leaves the focus on the body, and the
    *    arrows must keep scrolling what was clicked (the Source tab).
    * Never from a field, the palette, the inspector, a panel or the Surfer
    * canvas. */
  def keysAllowed(active: dom.Element, lastPressInSchematic: Boolean): Boolean =
    if (active == null || active == dom.document.body || active == dom.document.documentElement) lastPressInSchematic
    else active.closest("[data-pane=schematic]") != null && !editable(active)

  private def editable(el: dom.Element): Boolean =
    el.closest("input, textarea, select, [contenteditable]:not([contenteditable=false])") != null

  /** Where a press is never a pan: elements with their own drag or scroll,
    * and fields. */
  private val NoPan = "[data-no-pan], input, textarea, select, [contenteditable]:not([contenteditable=false])"

  /** What the controller does to the view. */
  final case class View(
    panVar: Var[(Double, Double)],
    zoomVar: Var[Double],
    viewSize: () => (Double, Double),
    clamp: Double => Double,
    userView: () => Unit,        // the view becomes the user's (cancels a pending fit)
    zoomBy: Double => Unit,      // zoom about the viewport centre
    fit: () => Unit,
    clearSelection: () => Unit,  // a click on the background
    clearFollow: () => Unit,     // Escape: stop following a net (NetFollow)
    keysBlocked: () => Boolean,  // e.g. a state diagram covers the schematic
    back: () => Unit,            // the view history (ViewHistory)
    forward: () => Unit,
    zoomToSelection: () => Boolean, // false: nothing selected, nothing done
  )

  /** The gesture controller on the schematic viewport. [[install]] adds its
    * listeners and returns their removal. */
  final class Controller(viewport: dom.html.Element, v: View, hl: Highlight) {
    // The press in progress (-1: none).
    private var pointerId = -1.0
    private var button = 0
    private var sx = 0.0
    private var sy = 0.0
    private var downTarget: dom.Element = null
    private var panning = false
    private var startPan = (0.0, 0.0)
    // The press panned: its click (and a double-click it completes) is not a click.
    private var dragged = false
    private var panEndAt = -1e9
    // The latest pan of the drag, applied once per animation frame.
    private var nextPan: Option[(Double, Double)] = None
    private var panFrame = 0
    // Wheel input since the last frame.
    private var wheelLog = 0.0
    private var wheelDx = 0.0
    private var wheelDy = 0.0
    private var wheelX = 0.0
    private var wheelY = 0.0
    private var wheelFrame = 0

    private def now(): Double = dom.window.performance.now()

    private def flushPan(): Unit = {
      if (panFrame != 0) dom.window.cancelAnimationFrame(panFrame)
      panFrame = 0
      nextPan.foreach(v.panVar.set)
      nextPan = None
    }

    // The viewport's `[data-pan-overlay]`: shown over the content while
    // panning, it gives the grabbing cursor and no text selection wherever the
    // pointer goes, and holds the pointer capture (so the cursor is right
    // whether the browser draws the capture target's or the hit element's).
    // One element shown, so nothing in the content restyles. A direct child:
    // found among the few children, not by a subtree search.
    private def overlay: Option[dom.html.Element] =
      viewport.children.find(_.hasAttribute("data-pan-overlay")).collect { case el: dom.html.Element => el }
    // The element holding the capture during a pan.
    private var captor: dom.html.Element = null

    private def beginPan(): Unit = {
      panning = true; dragged = true
      v.userView()
      startPan = v.panVar.now()
      hl.suspendHover()
      captor = overlay match {
        case Some(o) => o.style.display = "block"; o
        case None    => viewport
      }
      try captor.asInstanceOf[js.Dynamic].setPointerCapture(pointerId) catch { case _: Throwable => () }
      // A press on a label may have started a text selection.
      Option(dom.window.getSelection()).foreach(_.removeAllRanges())
    }

    private val onDown: js.Function1[dom.PointerEvent, Unit] = (e) => {
      val t = e.target match { case el: dom.Element => el; case _ => null }
      if (pointerId == -1 && (e.button == 0 || e.button == 1) && (t == null || t.closest(NoPan) == null)) {
        dragged = false
        // The schematic takes the focus (it is not otherwise focusable), so the
        // keys act after a click in it; a focusable child still takes it after.
        viewport.focus(new dom.FocusOptions { preventScroll = true })
        pointerId = e.pointerId; button = e.button
        sx = e.clientX; sy = e.clientY; downTarget = t
        dom.window.addEventListener("pointermove", onMove)
        dom.window.addEventListener("pointerup", onUp)
        dom.window.addEventListener("pointercancel", onCancel)
        if (button == 1) { e.preventDefault(); beginPan() }
      }
    }

    private val onMove: js.Function1[dom.PointerEvent, Unit] = (e) => if (e.pointerId == pointerId) {
      // No button down any more: the release was lost (outside the window, a
      // dialog): end the press here instead of a drag stuck to the pointer.
      if (e.buttons == 0) end(apply = true)
      else {
        val dx = e.clientX - sx; val dy = e.clientY - sy
        if (!panning && math.hypot(dx, dy) > DragThreshold) beginPan()
        if (panning) {
          nextPan = Some((startPan._1 + dx, startPan._2 + dy))
          if (panFrame == 0) panFrame = dom.window.requestAnimationFrame { _ => panFrame = 0; flushPan() }
        }
      }
    }

    private val onUp: js.Function1[dom.PointerEvent, Unit] = (e) => if (e.pointerId == pointerId) {
      val wasPanning = panning
      val wasLeft = button == 0
      val tgt = downTarget
      end(apply = true)
      // A left click on the background (no drag) clears the selection. Here
      // and not on `click`: after a drag the click lands on the viewport.
      if (!wasPanning && wasLeft && (tgt == null || tgt.closest("[data-pan-block]") == null)) v.clearSelection()
    }

    private val onCancel: js.Function1[dom.PointerEvent, Unit] = (e) => if (e.pointerId == pointerId) end(apply = true)

    private val onLostCapture: js.Function1[dom.PointerEvent, Unit] = (e) =>
      if (e.pointerId == pointerId && panning) end(apply = true)

    /** Finish the press: drop the listeners, apply (or drop) the last pan,
      * hand the hover back, release the capture. */
    private def end(apply: Boolean): Unit = {
      dom.window.removeEventListener("pointermove", onMove)
      dom.window.removeEventListener("pointerup", onUp)
      dom.window.removeEventListener("pointercancel", onCancel)
      if (apply) flushPan()
      else { if (panFrame != 0) dom.window.cancelAnimationFrame(panFrame); panFrame = 0; nextPan = None }
      val id = pointerId
      pointerId = -1.0; downTarget = null
      if (panning) {
        panning = false
        panEndAt = now()
        // Before the release, so the boundary events after it set the hover.
        hl.resumeHover()
        if (captor != null) {
          try captor.asInstanceOf[js.Dynamic].releasePointerCapture(id) catch { case _: Throwable => () }
          if (captor ne viewport) captor.style.display = "none"
          captor = null
        }
      }
    }

    /** End a drag in progress without applying it, and drop pending wheel
      * input: on a navigation, so the new module's fit is not undone by a
      * stale drag. */
    def cancel(): Unit = {
      if (pointerId != -1) end(apply = false)
      if (wheelFrame != 0) dom.window.cancelAnimationFrame(wheelFrame)
      wheelFrame = 0; wheelLog = 0; wheelDx = 0; wheelDy = 0
    }

    // The click (and double-click) that ends a drag is not a click.
    private val onClickCapture: js.Function1[dom.MouseEvent, Unit] = (e) =>
      if (dragged) { e.stopPropagation(); e.preventDefault() }
    private val onDblClickCapture: js.Function1[dom.MouseEvent, Unit] = (e) =>
      if (dragged || now() - panEndAt < 500) { e.stopPropagation(); e.preventDefault() }

    // The middle button's autoscroll starts on mousedown.
    private val onMouseDown: js.Function1[dom.MouseEvent, Unit] = (e) =>
      if (e.button == 1 || e.button == 3 || e.button == 4) e.preventDefault()

    // The mouse's back (3) and forward (4) buttons walk the view history over
    // the schematic. The browser navigates the page on their release unless
    // it is prevented.
    private val onMouseUp: js.Function1[dom.MouseEvent, Unit] = (e) =>
      if (e.button == 3 || e.button == 4) {
        e.preventDefault()
        if (!v.keysBlocked()) { if (e.button == 3) v.back() else v.forward() }
      }
    private val onAuxClick: js.Function1[dom.MouseEvent, Unit] = (e) =>
      if (e.button == 3 || e.button == 4) e.preventDefault()

    /** A press is in progress (a drag may still move the view). */
    def busy: Boolean = pointerId != -1

    private def flushWheel(): Unit = {
      wheelFrame = 0
      // One rect read per frame, not per event.
      val rect = viewport.getBoundingClientRect()
      val mx = wheelX - rect.left; val my = wheelY - rect.top
      val z = v.zoomVar.now(); val newZ = v.clamp(z * math.exp(wheelLog))
      val (px, py) = ViewHistory.zoomAt(v.panVar.now(), z, newZ, mx, my)
      Var.set(v.panVar -> ((px + wheelDx, py + wheelDy)), v.zoomVar -> newZ)
      wheelLog = 0; wheelDx = 0; wheelDy = 0
    }

    private val onWheel: js.Function1[dom.WheelEvent, Unit] = (e) => {
      e.preventDefault()
      v.userView()
      classify(e) match {
        case WheelIntent.Zoom(l)       => wheelLog += l
        case WheelIntent.Pan(dx, dy)   => wheelDx += dx; wheelDy += dy
      }
      wheelX = e.clientX; wheelY = e.clientY
      if (wheelFrame == 0) wheelFrame = dom.window.requestAnimationFrame(_ => flushWheel())
    }

    // Where the last press anywhere on the page landed (true before any).
    private var lastPressInSchematic = true
    private val onAnyDown: js.Function1[dom.PointerEvent, Unit] = (e) =>
      lastPressInSchematic = e.target match {
        case el: dom.Element => el.closest("[data-pane=schematic]") != null
        case _               => false
      }

    private val mac = js.typeOf(dom.window.navigator.platform) == "string" &&
      dom.window.navigator.platform.matches("(?s).*(Mac|iP(hone|ad|od)).*")

    private val onKey: js.Function1[dom.KeyboardEvent, Unit] = (e) => {
      val allowed = !e.ctrlKey && !e.metaKey && !e.defaultPrevented && !v.keysBlocked() &&
        keysAllowed(dom.document.activeElement, lastPressInSchematic)
      val altArrow = e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.key == "ArrowLeft" || e.key == "ArrowRight")
      // Alt+Left / Alt+Right walk the view history, like a browser's.
      if (allowed && altArrow) {
        e.preventDefault()
        if (e.key == "ArrowLeft") v.back() else v.forward()
      }
      // Elsewhere (a field, a panel, behind the "?" card) they do nothing,
      // and never the browser's Back, which would leave the page. Not on a
      // Mac: there Option+arrow moves by word in a field (Back is Cmd+[).
      else if (altArrow && !e.defaultPrevented && !mac) e.preventDefault()
      else if (allowed && !e.altKey) {
        val (vw, vh) = v.viewSize()
        def panBy(dx: Double, dy: Double): Unit = {
          v.userView()
          val (px, py) = v.panVar.now()
          v.panVar.set((px + dx, py + dy))
        }
        // An arrow looks that way: ArrowRight moves the content left.
        val handled = e.key match {
          case "ArrowLeft"  => panBy(0.1 * vw, 0); true
          case "ArrowRight" => panBy(-0.1 * vw, 0); true
          case "ArrowUp"    => panBy(0, 0.1 * vh); true
          case "ArrowDown"  => panBy(0, -0.1 * vh); true
          case "+" | "="    => v.zoomBy(1.25); true
          case "-" | "_"    => v.zoomBy(0.8); true
          case "0" | "f"    => v.fit(); true
          case "z"          => v.zoomToSelection()
          // Only an Escape nothing else took. Listener order matters here:
          // this window listener is added before App's (the schematic mounts
          // before App installs its keys), so it runs first. Then an open "?"
          // card or state diagram blocks it (`keysBlocked`), the palette's
          // input prevents its own Escape, and the settings open from a press
          // outside the schematic (`keysAllowed`). In the other order App
          // prevents an Escape that closes an overlay or the diagram, which
          // `allowed` checks.
          // Not prevented here: App's own Escape handling still runs.
          case "Escape"     => v.clearFollow(); false
          case _            => false
        }
        if (handled) e.preventDefault()
      }
    }

    def install(): () => Unit = {
      val vp = viewport.asInstanceOf[js.Dynamic]
      val nonPassive = js.Dynamic.literal(passive = false)
      viewport.addEventListener("pointerdown", onDown)
      viewport.addEventListener("lostpointercapture", onLostCapture)
      viewport.addEventListener("mousedown", onMouseDown)
      viewport.addEventListener("mouseup", onMouseUp)
      viewport.addEventListener("auxclick", onAuxClick)
      viewport.addEventListener("click", onClickCapture, true)
      viewport.addEventListener("dblclick", onDblClickCapture, true)
      // Non-passive, so preventDefault stops the page scrolling or zooming.
      vp.addEventListener("wheel", onWheel, nonPassive)
      dom.window.addEventListener("keydown", onKey)
      dom.window.addEventListener("pointerdown", onAnyDown, true)
      () => {
        cancel()
        viewport.removeEventListener("pointerdown", onDown)
        viewport.removeEventListener("lostpointercapture", onLostCapture)
        viewport.removeEventListener("mousedown", onMouseDown)
        viewport.removeEventListener("mouseup", onMouseUp)
        viewport.removeEventListener("auxclick", onAuxClick)
        viewport.removeEventListener("click", onClickCapture, true)
        viewport.removeEventListener("dblclick", onDblClickCapture, true)
        vp.removeEventListener("wheel", onWheel, nonPassive)
        dom.window.removeEventListener("keydown", onKey)
        dom.window.removeEventListener("pointerdown", onAnyDown, true)
      }
    }
  }

  /** Smoke hook: [[classify]] on raw values, as {kind, dx, dy, log}. */
  def classifyDebug(dx: Double, dy: Double, mode: Int, ctrl: Boolean, shift: Boolean): js.Dynamic =
    classify(WheelIn(dx, dy, mode, ctrl, shift)) match {
      case WheelIntent.Zoom(l)     => js.Dynamic.literal(kind = "zoom", dx = 0, dy = 0, log = l)
      case WheelIntent.Pan(x, y)   => js.Dynamic.literal(kind = "pan", dx = x, dy = y, log = 0)
    }
}
