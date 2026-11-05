package socle.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import SchematicGeom.Geom

/** Back and forward through the views of the schematic, and the fly-to
  * animation.
  *
  * An [[Entry]] is a place: the drill path plus the pan and zoom (expansions
  * and fold state are not part of it). The schematic pushes one on a drill in
  * or out, a fit, a fly-to, a zoom to the selection, and a pan or zoom once
  * the view has settled for [[SettleMs]]. The [[Stack]] keeps at most [[Cap]]
  * entries; a new place drops the forward ones, like a browser.
  *
  * The stack, the framing and the flight's path are pure; [[Flyer]] runs a
  * flight, one pan and zoom per animation frame. */
object ViewHistory {

  /** Entries kept; the oldest goes first. */
  val Cap = 50
  /** How long a pan or zoom must rest before it becomes an entry (ms). */
  val SettleMs = 600
  /** How long a flight lasts (ms). */
  val FlyMs = 250.0

  final case class Entry(path: List[String], pan: (Double, Double), zoom: Double) {
    /** The same place, give or take a rounding of the transform. */
    def same(o: Entry): Boolean =
      path == o.path && math.abs(pan._1 - o.pan._1) < 0.5 && math.abs(pan._2 - o.pan._2) < 0.5 &&
        math.abs(zoom - o.zoom) < 1e-4
  }

  /** The history: `entries` and the one shown (`index`, -1 when empty). */
  final case class Stack(entries: Vector[Entry] = Vector.empty, index: Int = -1) {
    def current: Option[Entry] = entries.lift(index)
    def canBack: Boolean = index > 0
    def canForward: Boolean = index < entries.size - 1
    /** A new place: the forward entries go, and the oldest past [[Cap]]. The
      * place already shown is not pushed again (the forward entries stay). */
    def push(e: Entry): Stack =
      if (current.exists(_.same(e))) this
      else {
        val kept = entries.take(index + 1) :+ e
        val drop = math.max(0, kept.size - Cap)
        Stack(kept.drop(drop), kept.size - 1 - drop)
      }
    /** The place shown changed without a move (a relayout refitted it). */
    def replace(e: Entry): Stack = if (index < 0) push(e) else copy(entries = entries.updated(index, e))
    def back: Stack = if (canBack) copy(index = index - 1) else this
    def forward: Stack = if (canForward) copy(index = index + 1) else this
  }

  /** A rectangle in content coordinates. */
  final case class Rect(x: Double, y: Double, w: Double, h: Double)

  /** What the selection `s` covers in the view `g`: a box (instance or logic
    * node) and the wires driven from `s` (all of a fan-out), else a pin (a
    * small area around it), else the box a path under it names. None when `s`
    * is not in this view. */
  private[schematic] def selectionRect(g: Geom, s: String): Option[Rect] = {
    def boxRect(id: String): Option[Rect] = g.boxById.get(id).map { n =>
      val top = n.y + g.boxShift.shift.getOrElse(n.id, 0.0)
      Rect(n.x.toDouble, top, n.w.toDouble, g.rows.get(n.id).map(_.boxH).getOrElse(n.h.toDouble))
    }
    def wiresRect: Option[Rect] = {
      val pts = g.wires.iterator.filter(_.fromPath == s).flatMap(_.points).toSeq
      if (pts.isEmpty) None
      else {
        val xs = pts.map(_._1); val ys = pts.map(_._2)
        Some(Rect(xs.min, ys.min, xs.max - xs.min, ys.max - ys.min))
      }
    }
    def pinRect: Option[Rect] = g.pinOwner.get(s).map { case (b, p) =>
      val y = p.y + g.boxShift.shift.getOrElse(b.id, 0.0)
      Rect(p.x - 60.0, y - 20.0, 120.0, 40.0)
    }
    // A boundary stub's id is also the path of the wires it drives: both.
    (boxRect(s) ++ wiresRect).reduceOption(union)
      .orElse(pinRect).orElse(boxRect(s.takeWhile(_ != '.')))
  }

  private def union(a: Rect, b: Rect): Rect = {
    val l = math.min(a.x, b.x); val t = math.min(a.y, b.y)
    Rect(l, t, math.max(a.x + a.w, b.x + b.w) - l, math.max(a.y + a.h, b.y + b.h) - t)
  }

  /** The view (pan, zoom) that centres `r` in a (vw, vh) viewport with
    * `margin` px around it, at most `maxZoom` and within `clamp`. */
  def frame(r: Rect, vw: Double, vh: Double, margin: Double, maxZoom: Double,
            clamp: Double => Double): ((Double, Double), Double) = {
    val fitZ = math.min((vw - 2 * margin) / math.max(r.w, 1.0), (vh - 2 * margin) / math.max(r.h, 1.0))
    val z = clamp(math.min(maxZoom, fitZ))
    val cx = r.x + r.w / 2; val cy = r.y + r.h / 2
    ((vw / 2 - cx * z, vh / 2 - cy * z), z)
  }

  /** The pan that keeps the content point under viewport point (mx, my) in
    * place when the zoom goes from `z` to `newZ` (the wheel zooms at the
    * pointer, the keys at the viewport's centre). */
  def zoomAt(pan: (Double, Double), z: Double, newZ: Double, mx: Double, my: Double): (Double, Double) = {
    val ratio = newZ / z
    (mx - (mx - pan._1) * ratio, my - (my - pan._2) * ratio)
  }

  /** Ease in and out (cubic), t in [0, 1]. */
  def ease(t: Double): Double =
    if (t < 0.5) 4 * t * t * t else 1 - math.pow(-2 * t + 2, 3) / 2

  /** The view at time `t` (0 to 1) of a flight from `from` to `to` in a
    * (vw, vh) viewport: the content point at the viewport's centre moves in a
    * straight line and the zoom changes geometrically, both eased, so the
    * target comes in steadily. Ends exactly on `to`. */
  def between(from: ((Double, Double), Double), to: ((Double, Double), Double), t: Double,
              vw: Double, vh: Double): ((Double, Double), Double) = {
    val ((p0x, p0y), z0) = from; val ((p1x, p1y), z1) = to
    if (t >= 1) to
    else {
      val e = ease(math.max(0.0, t))
      val c0x = (vw / 2 - p0x) / z0; val c0y = (vh / 2 - p0y) / z0
      val c1x = (vw / 2 - p1x) / z1; val c1y = (vh / 2 - p1y) / z1
      val z = z0 * math.pow(z1 / z0, e)
      val cx = c0x + (c1x - c0x) * e; val cy = c0y + (c1y - c0y) * e
      ((vw / 2 - cx * z, vh / 2 - cy * z), z)
    }
  }

  /** Whether the user asked for less motion: a flight then jumps. */
  def reducedMotion(): Boolean =
    try dom.window.matchMedia("(prefers-reduced-motion: reduce)").matches catch { case _: Throwable => false }

  /** Runs flights on the view: one `Var.set(pan, zoom)` per animation frame
    * for [[FlyMs]], or one jump with reduced motion. A new flight or
    * [[cancel]] stops the one running where it is. */
  final class Flyer(panVar: Var[(Double, Double)], zoomVar: Var[Double], viewSize: () => (Double, Double)) {
    private var frame = 0
    def running: Boolean = frame != 0
    def cancel(): Unit = if (frame != 0) { dom.window.cancelAnimationFrame(frame); frame = 0 }

    def fly(to: ((Double, Double), Double), done: () => Unit): Unit = {
      cancel()
      def put(v: ((Double, Double), Double)): Unit = Var.set(panVar -> v._1, zoomVar -> v._2)
      if (reducedMotion()) { put(to); done() }
      else {
        val from = (panVar.now(), zoomVar.now())
        val (vw, vh) = viewSize()
        val t0 = dom.window.performance.now()
        lazy val step: js.Function1[Double, Unit] = (_: Double) => {
          val t = (dom.window.performance.now() - t0) / FlyMs
          put(between(from, to, t, vw, vh))
          if (t < 1) frame = dom.window.requestAnimationFrame(step)
          else { frame = 0; done() }
        }
        frame = dom.window.requestAnimationFrame(step)
      }
    }
  }

  /** One schematic's history and flights: the entries, Back and Forward, a
    * pending fly to a box and the settle of a user's pan or zoom. The pane
    * gives it what it owns itself:
    *  - `fitPending`: the module's own view (its fit or saved view) is still
    *    to come, so the view shown is not an entry;
    *  - `takeView`: the view is set from here on, not fitted automatically;
    *  - `dragging`: a press is in progress (a drag holding still has not settled);
    *  - `navigate`: show another module, false when its path does not resolve;
    *  - `flyTarget`: the path key shown and its geometry, once that module's
    *    view is in place and laid out (a fly to a box waits for it).
    * A user's pan or zoom ([[userMoved]]) becomes an entry once it rests
    * [[SettleMs]]; a view left before that is pushed first ([[commitDirty]]). */
  final class Controller(
      panVar: Var[(Double, Double)], zoomVar: Var[Double], viewSize: () => (Double, Double),
      clamp: Double => Double, fitPending: () => Boolean, takeView: () => Unit,
      dragging: () => Boolean, navigate: List[String] => Boolean,
      flyTarget: () => Option[(String, Geom)], resolves: List[String] => Boolean = _ => true) {
    private val canVar = Var((false, false))
    /** Whether Back and Forward would land somewhere, as [[walk]] does (the
      * Back and Forward buttons): an entry that way in the module shown or
      * in a module that still resolves; Back also from a view not settled
      * yet (it becomes an entry first), and then nothing is forward. */
    val canSig: Signal[(Boolean, Boolean)] = canVar.signal
    def canNow: (Boolean, Boolean) = canVar.now()
    private var stack = Stack()
    private[schematic] def hist: Stack = stack
    private[schematic] def hist_=(s: Stack): Unit = {
      val moved = !(s eq stack) && !refitting
      stack = s; refreshCan()
      if (moved) onMove()
    }
    private var refitting = false
    /** The user or the app moved: a new entry, a walk, a reset, a pan or zoom
      * not settled yet (not a refit of the view shown). */
    var onMove: () => Unit = () => ()
    // The user moved the view since the last entry.
    private var unsettled = false
    private def dirty: Boolean = unsettled
    private def dirty_=(d: Boolean): Unit = if (d != unsettled) {
      unsettled = d; refreshCan()
      if (d) onMove()
    }
    /** The module path shown. */
    private var shown: List[String] = Nil
    def curPath: List[String] = shown
    def curPath_=(p: List[String]): Unit = if (p != shown) { shown = p; refreshCan() }
    /** Recomputes [[canSig]]; also after the design changed (a path may no longer resolve). */
    def refreshCan(): Unit = {
      def lands(e: Entry) = e.path == shown || resolves(e.path)
      val es = stack.entries
      val back = (dirty && stack.index >= 0) || (0 until math.max(0, stack.index)).exists(i => lands(es(i)))
      val fwd  = !dirty && (stack.index + 1 until es.size).exists(i => lands(es(i)))
      if (canVar.now() != ((back, fwd))) canVar.set((back, fwd))
    }
    // A Back or Forward entry whose module is being shown: its view stands in
    // for the fit (or the module's saved view) and is not pushed again.
    private var restoring = Option.empty[Entry]
    // A box to fly to, (path key, box id), once that module's layout is ready.
    private var flyPending = Option.empty[(String, String)]
    private var settleTimer = 0
    val flyer = new Flyer(panVar, zoomVar, viewSize)

    private def cancelSettle(): Unit = if (settleTimer != 0) { dom.window.clearTimeout(settleTimer); settleTimer = 0 }

    /** Pushes the view shown; not while the module's own view is still to come. */
    def pushView(): Unit = {
      cancelSettle(); dirty = false
      if (!fitPending() && curPath.nonEmpty) hist = hist.push(Entry(curPath, panVar.now(), zoomVar.now()))
    }
    /** A view left before it settled is still a place the user was: pushes it
      * before the move that leaves it. */
    def commitDirty(): Unit = if (dirty) pushView()
    private def armSettle(): Unit = {
      cancelSettle()
      settleTimer = dom.window.setTimeout(() => {
        settleTimer = 0
        if (dragging()) armSettle()
        else if (dirty && !flyer.running && !fitPending()) pushView()
      }, SettleMs)
    }

    /** The view moved (any pan or zoom): a user's move settles into an entry. */
    def viewMoved(): Unit = if (dirty) armSettle()
    /** A user pan or zoom: it stops a flight, and drops one still waiting for its layout. */
    def userMoved(): Unit = { stopFlight(); dirty = true }
    def stopFlight(): Unit = { flyer.cancel(); flyPending = None }
    /** Unmounted: no flight nor settle left running. */
    def stop(): Unit = { flyer.cancel(); cancelSettle() }

    /** Another module is shown (the one shown was not on its way: `fitPending`
      * false): its view, if not settled, is an entry before the next one's. */
    def leave(): Unit = {
      flyer.cancel()
      if (!fitPending()) commitDirty()
      cancelSettle(); dirty = false
    }
    /** The module at `path` (key `pk`) is shown: a fly to a box of another one is dropped. */
    def enter(pk: String, path: List[String]): Unit = {
      if (flyPending.exists(_._1 != pk)) flyPending = None
      curPath = path
    }

    /** The Back or Forward entry being restored for path key `pk`, taken once. */
    def takeRestoring(pk: String): Option[Entry] = {
      val r = restoring.filter(_.path.mkString("/") == pk)
      restoring = None
      r
    }
    /** A refit replaced the automatic fit shown: its entry follows it. */
    def refitted(): Unit =
      if (hist.current.exists(_.path == curPath)) {
        refitting = true
        try hist = hist.replace(Entry(curPath, panVar.now(), zoomVar.now())) finally refitting = false
      }

    /** Flies to a content rect: framed with `margin` px around it, at most
      * `maxZoom`. The flight's end is an entry; the view it leaves too, if it
      * had not settled yet. */
    def flyToRect(r: Rect, margin: Double, maxZoom: Double): Unit = {
      val (vw, vh) = viewSize()
      if (vw > 0 && vh > 0) {
        commitDirty(); takeView()
        flyer.fly(frame(r, vw, vh, margin, maxZoom, clamp), () => pushView())
      }
    }
    /** `z`: frames the selection `sel` (a box, a wire, a pin) in `geom`.
      * False when there is none or it is not in this view. */
    def zoomToSelection(sel: Option[String], geom: Option[Geom]): Boolean =
      (for { s <- sel; g <- geom; r <- selectionRect(g, s) } yield r) match {
        case Some(r) => flyToRect(r, 40, 2.0); true
        case None    => false
      }
    /** The pending fly to a box, once its module's view is in place and laid out. */
    def tryFly(): Unit = flyPending.foreach { case (pk, id) =>
      flyTarget().filter(_._1 == pk).map(_._2).filter(_.boxById.contains(id)).flatMap(selectionRect(_, id)).foreach { r =>
        flyPending = None
        flyToRect(r, 60, 1.0)
      }
    }
    /** Flies to box `id` of the module at `path`, showing that module first. */
    def flyTo(path: List[String], id: String): Unit = {
      flyPending = Some((path.mkString("/"), id))
      if (path == curPath) tryFly() else if (!navigate(path)) flyPending = None
    }

    /** Back or Forward: the entry's module first (its view replaces the fit),
      * or just its view in the module shown. Entries whose path no longer
      * resolves are skipped, so the walk never gets stuck on one. */
    def walk(step: Stack => Stack): Unit = {
      stopFlight(); commitDirty()
      val from = hist
      var next = step(from)
      var landed = false
      while (!landed && next.index != from.index && next.current.isDefined) {
        val e = next.current.get
        if (e.path == curPath) {
          hist = next; landed = true
          if (fitPending()) restoring = Some(e)
          else { restoring = None; Var.set(zoomVar -> e.zoom, panVar -> e.pan); takeView() }
        } else {
          hist = next; restoring = Some(e)
          if (navigate(e.path)) landed = true
          else {
            hist = from; restoring = None
            val further = step(next)
            next = if (further.index == next.index) from else further // the end: stay
          }
        }
      }
    }

    /** A fresh load: no history, no pending flight or settle. */
    def reset(): Unit = {
      flyer.cancel(); cancelSettle(); dirty = false
      hist = Stack(); restoring = None; flyPending = None
    }

    /** The smoke's view of the stack ({size, index, entries}). */
    def debug(): js.Dynamic =
      js.Dynamic.literal(size = hist.entries.size, index = hist.index,
        entries = js.Array(hist.entries.map(e =>
          js.Dynamic.literal(path = e.path.mkString("/"), x = e.pan._1, y = e.pan._2, z = e.zoom)): _*))
  }

  /** What the app asks of the schematic's view: the palette's Back, Forward
    * and Zoom to selection, a fly to an instance of a module (drilled to
    * first when it is not in view), and the reset of a fresh load (a new
    * design: the history and the followed net belong to the previous one; a
    * live reload keeps both). The schematic installs them when it renders. */
  // One schematic installs these: one instance per app (App's `viewCmds`).
  final class Commands {
    var back: () => Unit = () => ()
    var forward: () => Unit = () => ()
    var zoomToSelection: () => Boolean = () => false
    var flyTo: (List[String], String) => Unit = (_, _) => ()
    var reset: () => Unit = () => ()
  }

  /** Smoke hook: the stack after `ops` from empty ("p" pushes a new place,
    * "b" goes back, "f" forward), as {size, index, canForward}. */
  def modelDebug(ops: js.Array[String]): js.Dynamic = {
    var n = 0
    val s = ops.foldLeft(Stack()) {
      case (st, "p") => n += 1; st.push(Entry(List("m"), (n.toDouble, 0.0), 1.0))
      case (st, "b") => st.back
      case (st, "f") => st.forward
      case (st, _)   => st
    }
    js.Dynamic.literal(size = s.entries.size, index = s.index, canForward = s.canForward)
  }
}
