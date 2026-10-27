package socless.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.collection.mutable
import SchematicGeom.{Geom, Wire, ownerBoxId, fuzzyHas}
import socless.layout.LayoutCore
import socless.ui.theme.Tokens

/** The schematic's highlight state, applied as styling on elements built once
  * (perf). Hover, selection, filter and editor-focus dimming and the resting
  * colourization (#266) never re-run `renderContent`: they toggle classes.
  *
  * How it works:
  *  - `renderContent` builds a fresh [[Registry]] of the elements a highlight
  *    can touch (wire groups, boxes, pins) as it creates them, and hands it to
  *    [[install]], which applies the current state to the new elements.
  *  - One observer per input (selection, hover, filter, focus class) computes
  *    the elements that should carry each class and toggles the class on the
  *    difference only (a [[Channel]]): resting, re-entering the same wire or
  *    re-setting the same value touches nothing. Matches per key are memoized
  *    per registry, so a repeated hover costs only its delta.
  *  - The looks live in [[css]] (rules keyed on these classes and on the
  *    content layer's `hl-sel` / `hl-cw` / `hl-cb` classes). Elements keep only
  *    their per-element values inline, as custom properties (`--wc` a wire's
  *    colorized stroke, `--bf` a box's colorized fill, the box's resting border
  *    and shadows), so a class flip can never lose to an inline style. The
  *    export embeds the same rules ([[Export]]).
  *
  * Matching keeps the exact predicates of the old per-render code:
  *  - a wire is lit by a selection `s` when one path is a prefix of the other
  *    (`from`/`to` vs `s`, both ways), hovered by `h` when `from` or `to` starts
  *    with `h`;
  *  - a box is selected when `s` is its id or under it (`id.`), and an
  *    instance is a clock peer when it shares the selected instance's clock;
  *  - a pin is selected (or hovered) when its path and the key are prefixes
  *    of one another.
  *
  * The selection and hover keys stay App's `Var[Option[String]]`, shared with
  * the Inspector, the bottom panel, the waveform follow and the palette.
  *
  * The followed net ([[NetFollow]]) is its own state, apart from the
  * selection: the wire clicked and its level. Each registry knows the drill
  * path it was rendered for, so the net is resolved against the level whose
  * elements it lights (never another level's paths, which can share names). */
final class Highlight(val selection: Var[Option[String]], val hover: Var[Option[String]]) {
  import Highlight._

  private var reg: Registry = Registry.empty
  private var filter: String = ""
  private var focusClass: String = ""
  /** The editor focus while it dims the view: its class and how many boxes it
    * lights (the Focus chip); None while it dims nothing. */
  val focusShownVar: Var[Option[(String, Int)]] = Var(None)

  private val wireHl  = new Channel("hl")
  private val wireHov = new Channel("hov")
  private val wireOff = new Channel("off")
  private val boxSel  = new Channel("sel")
  private val boxPeer = new Channel("peer")
  private val boxOff  = new Channel("off")
  private val boxFocus = new AttrChannel("data-box-focus")
  private val pinSel  = new Channel("sel")
  private val pinHov  = new Channel("hov")
  // The hovered block's outline and its connected wires (A of the hover feedback).
  private val boxOut  = new Channel("ohov")
  private val wireOut = new Channel("ohov")
  // The followed net: its wires, and its pins, boundary stubs and field rows.
  private val wireFol = new Channel("follow")
  private val markFol = new Channel("follow")
  private val all = Seq(wireHl, wireHov, wireOff, boxSel, boxPeer, boxOff, pinSel, pinHov, boxOut, wireOut, wireFol, markFol)

  /** Swap in the registry of a fresh render and apply the current state to it.
    * The old registry's elements are gone, so the channels start empty. */
  def install(r: Registry): Unit = {
    reg = r
    all.foreach(_.reset()); boxFocus.reset()
    applySelection(selection.now())
    applyHover(hover.now())
    applyBoxHover()
    applyDim()
    applyFollow()
  }

  /** The geometry of the elements shown (null before the first render). */
  def geom: Geom = reg.geom

  /** Test hook: the current registry's indexed matches against a full scan. */
  def selfCheck(): (Int, Seq[String]) = reg.selfCheck()

  // While the view pans (Gestures), the pointer crosses elements: their enter
  // and leave are ignored, so no class toggles mid-drag.
  private var hoverSuspended = false

  /** Whether a pan holds the hover (the tooltip waits too). */
  def isHoverSuspended: Boolean = hoverSuspended

  /** Set the hover key, only when it changes (a re-fired enter is a no-op). */
  def enter(key: String): Unit = if (!hoverSuspended && !hover.now().contains(key)) hover.set(Some(key))
  /** Clear the hover, only when it is still this key. */
  def leave(key: String): Unit = if (!hoverSuspended && hover.now().contains(key)) hover.set(None)
  /** A pan starts: clear the hover once and ignore enter and leave until
    * [[resumeHover]]. Cleared rather than kept, as a drag ending off the
    * hovered element would otherwise leave it lit. */
  def suspendHover(): Unit = { if (hover.now().isDefined) hover.set(None); clearBox(); hoverSuspended = true }
  /** The pan ended: the next boundary events set the hover again. */
  def resumeHover(): Unit = hoverSuspended = false

  // The hovered block (an instance, logic or stub box id): kept here and not in
  // the shared `hover`, which the Inspector shows (it would rebuild on every
  // box the pointer crosses).
  private var boxHover: Option[String] = None

  /** The pointer is over the box `id`: outline it and its connected wires. */
  def enterBox(id: String): Unit = if (!hoverSuspended && !boxHover.contains(id)) { boxHover = Some(id); applyBoxHover() }
  /** The pointer is over no box. */
  def leaveBox(): Unit = if (!hoverSuspended) clearBox()
  /** Drop the box hover whatever the state (a pan, an export). */
  def clearBox(): Unit = if (boxHover.isDefined) { boxHover = None; applyBoxHover() }

  private def applyBoxHover(): Unit = boxHover match {
    case None     => boxOut.set(Set.empty); wireOut.set(Set.empty)
    case Some(id) => val m = reg.boxMatch(id); boxOut.set(m.boxes); wireOut.set(m.wires)
  }

  private var net: Option[NetFollow.Net] = None
  private var followShown = true
  private var lit: NetFollow.Lit = NetFollow.Lit.empty

  /** The followed net, if any. */
  def followed: Option[NetFollow.Net] = net
  /** What it lights at the level shown. */
  def followedLit: NetFollow.Lit = lit
  /** The drill path of the level shown. */
  def path: List[String] = reg.path

  /** Follow `n` (None: stop following). */
  def follow(n: Option[NetFollow.Net]): Unit = if (n != net) { net = n; applyFollow() }

  /** A click on the drawn wire `from <= to` that left it `selected` (or not):
    * a click that selects a wire follows it; one that deselects the wire
    * followed from this level stops following it. */
  def clickWire(from: String, to: String, selected: Boolean): Unit = {
    val n = NetFollow.Net(reg.path, from, to)
    if (selected) follow(Some(n)) else if (net.contains(n)) follow(None)
  }

  /** Run `f` with the followed net unlit (an export), then light it again. */
  def withoutFollow(f: () => Unit): Unit = {
    followShown = false; applyFollow()
    try f() finally { followShown = true; applyFollow() }
  }

  private def applyFollow(): Unit = {
    val g = reg.geom
    lit = net match {
      case Some(n) if followShown && g != null =>
        NetFollow.resolve(g.design, n, reg.path, reg.wires.iterator.map(w => (w.from, w.to)).toSeq)
      case _ => NetFollow.Lit.empty
    }
    val m = reg.followMatch(lit)
    wireFol.set(m.wires); markFol.set(m.pins)
  }

  private var selDefined = false
  private def applySelection(sel: Option[String]): Unit = {
    sel match {
      case None =>
        wireHl.set(Set.empty); boxSel.set(Set.empty); boxPeer.set(Set.empty); pinSel.set(Set.empty)
      case Some(s) =>
        val m = reg.selMatch(s)
        wireHl.set(m.wires); boxSel.set(m.boxes); boxPeer.set(m.peers); pinSel.set(m.pins)
    }
    // The editor-focus dim is ignored while something is selected.
    if (sel.isDefined != selDefined) { selDefined = sel.isDefined; applyDim() }
  }

  private def applyHover(hov: Option[String]): Unit = hov match {
    case None    => wireHov.set(Set.empty); pinHov.set(Set.empty)
    case Some(h) => val m = reg.hovMatch(h); wireHov.set(m.wires); pinHov.set(m.pins)
  }

  private def applyDim(): Unit = {
    val g = reg.geom
    val (dim, focus) = if (g == null) (None, None) else dimSets(g, filter, focusClass, selection.now().isDefined)
    dim match {
      case None => wireOff.set(Set.empty); boxOff.set(Set.empty)
      case Some(act) =>
        boxOff.set(reg.boxes.iterator.filterNot(b => act.contains(b.id)).map(_.el).toSet)
        wireOff.set(reg.wires.iterator.filterNot(w => act.contains(w.fromBox) || act.contains(w.toBox)).map(_.el).toSet)
    }
    boxFocus.set(focus match {
      case None    => Set.empty
      case Some(f) => reg.boxes.iterator.filter(b => b.kind == BoxKind.Instance && f.contains(b.id)).map(_.el).toSet
    })
    // the focus dims the view only when it is the dim (no filter)
    val shown = focus.filter(_ => dim == focus).map(f => (focusClass, f.size))
    if (focusShownVar.now() != shown) focusShownVar.set(shown)
  }

  /** The observers, bound on the schematic pane. `filterSig` is the debounced
    * filter text, `focusClassSig` the generator class under the editor cursor. */
  def bind(filterSig: Signal[String], focusClassSig: Signal[String]): Modifier[HtmlElement] = Seq(
    selection.signal.distinct --> { (s: Option[String]) => applySelection(s) },
    hover.signal.distinct --> { (h: Option[String]) => applyHover(h) },
    filterSig.distinct --> { (f: String) => filter = f; applyDim() },
    focusClassSig.distinct --> { (c: String) => focusClass = c; applyDim() },
  )
}

object Highlight {

  sealed trait BoxKind
  object BoxKind {
    /** An instance box: selected, clock peer, dimmed, editor focus. */
    case object Instance extends BoxKind
    /** A logic, FSM, block or memory box: selected and dimmed. */
    case object Selectable extends BoxKind
    /** A boundary stub: dimmed only. */
    case object Stub extends BoxKind
  }

  final class WireEntry(val from: String, val to: String, boxById: Map[String, socless.js.BrowserLayout.LaidNodeR], val el: dom.Element) {
    // Owner box ids, only needed when dimming (filter or editor focus).
    lazy val fromBox: String = ownerBoxId(boxById, from)
    lazy val toBox: String   = ownerBoxId(boxById, to)
  }
  final class BoxEntry(val id: String, val kind: BoxKind, val el: dom.Element)
  final class PinEntry(val path: String, val el: dom.Element)

  final class Match(val wires: Set[dom.Element], val boxes: Set[dom.Element], val peers: Set[dom.Element], val pins: Set[dom.Element]) {
    def sameAs(o: Match): Boolean = wires == o.wires && boxes == o.boxes && peers == o.peers && pins == o.pins
  }
  private val NoMatch = new Match(Set.empty, Set.empty, Set.empty, Set.empty)

  /** Paths to elements, answering the two prefix questions without a scan:
    * the paths that start with `k` (a binary-searched range of the sorted
    * paths, O(log n + hits)), and the paths `k` starts with (a map lookup per
    * prefix of `k`, O(|k|)). */
  final class PathIndex(entries: Iterable[(String, dom.Element)]) {
    private val sorted: Array[(String, dom.Element)] = entries.toArray.sortBy(_._1)
    private val byPath: Map[String, Seq[dom.Element]] = entries.groupMap(_._1)(_._2).map { case (k, v) => k -> v.toSeq }
    /** The elements whose path starts with `k`. */
    def startingWith(k: String): Iterator[dom.Element] = {
      var lo = 0; var hi = sorted.length
      while (lo < hi) { val mid = (lo + hi) >>> 1; if (sorted(mid)._1.compareTo(k) < 0) lo = mid + 1 else hi = mid }
      sorted.iterator.drop(lo).takeWhile(_._1.startsWith(k)).map(_._2)
    }
    /** The elements whose path is a prefix of `k` (`k.startsWith(path)`). */
    def prefixesOf(k: String): Iterator[dom.Element] =
      (0 to k.length).iterator.flatMap(i => byPath.getOrElse(k.substring(0, i), Nil))
    def exactly(k: String): Seq[dom.Element] = byPath.getOrElse(k, Nil)
  }

  /** The elements of one render that a highlight can touch, registered as the
    * render builds them (a Laminar element's DOM node exists from construction).
    * `geom` is the geometry they were built from (null for the empty registry).
    * The indexes are built once, on the first match. */
  final class Registry(val geom: Geom, val path: List[String] = Nil) {
    val wires = mutable.ArrayBuffer.empty[WireEntry]
    val boxes = mutable.ArrayBuffer.empty[BoxEntry]
    val pins  = mutable.ArrayBuffer.empty[PinEntry]
    // Lit only by a followed net: field pin dots, boundary stubs, field rows.
    private val marks = mutable.ArrayBuffer.empty[PinEntry]
    private val rows  = mutable.ArrayBuffer.empty[PinEntry]

    def wire(w: Wire, el: dom.Element): Unit = wires += new WireEntry(w.fromPath, w.toPath, geom.boxById, el)
    def box(id: String, kind: BoxKind, el: dom.Element): Unit = boxes += new BoxEntry(id, kind, el)
    def pin(path: String, el: dom.Element): Unit = pins += new PinEntry(path, el)
    /** A pin a followed net lights, beside the top-level ones: a field pin
      * dot, a boundary stub (its path is its id). */
    def mark(path: String, el: dom.Element): Unit = marks += new PinEntry(path, el)
    /** The label of an expanded bundle's field row (a followed net lights it). */
    def row(path: String, el: dom.Element): Unit = rows += new PinEntry(path, el)

    private lazy val wireFrom = new PathIndex(wires.map(w => w.from -> w.el))
    private lazy val wireTo   = new PathIndex(wires.map(w => w.to -> w.el))
    private lazy val pinIdx   = new PathIndex(pins.map(p => p.path -> p.el))
    // Selectable boxes by id; instance boxes by the clock of their child.
    private lazy val selBoxes = new PathIndex(boxes.filter(_.kind != BoxKind.Stub).map(b => b.id -> b.el))
    private lazy val byClock: Map[String, Seq[BoxEntry]] =
      boxes.filter(_.kind == BoxKind.Instance)
        .flatMap(b => geom.childByName.get(b.id).map(ch => ch.clock -> b)).toSeq.groupMap(_._1)(_._2)

    private lazy val wiresByPair: Map[(String, String), Seq[dom.Element]] =
      wires.groupMap(w => (w.from, w.to))(_.el).map { case (k, v) => k -> v.toSeq }
    private lazy val markIdx = new PathIndex((pins ++ marks).map(p => p.path -> p.el))
    private lazy val rowIdx  = new PathIndex(rows.map(p => p.path -> p.el))

    /** What a followed net lights here: its wires, and every pin, stub and
      * field row whose path is one of its ends, under one or over one (a
      * collapsed bundle pin for a field, the field pins of a bundle). */
    def followMatch(l: NetFollow.Lit): Match = if (geom == null || (l.wires.isEmpty && l.ends.isEmpty)) NoMatch else {
      def related(idx: PathIndex, k: String): Iterator[dom.Element] =
        idx.exactly(k).iterator ++ idx.startingWith(k + ".") ++ idx.startingWith(k + "[") ++
          k.indices.iterator.filter(i => k.charAt(i) == '.' || k.charAt(i) == '[').flatMap(i => idx.exactly(k.substring(0, i)))
      new Match(
        l.wires.iterator.flatMap(p => wiresByPair.getOrElse(p, Nil)).toSet,
        Set.empty, Set.empty,
        l.ends.iterator.flatMap(k => related(markIdx, k) ++ related(rowIdx, k)).toSet,
      )
    }

    private val selCache = mutable.HashMap.empty[String, Match]
    private val hovCache = mutable.HashMap.empty[String, Match]

    /** The box a wire end belongs to: the path itself when it is a box id (a
      * boundary stub), else its head, or the node a logic pin head names
      * (`x~q`, `x~in0` are pins of `x`). */
    def endBox(p: String): String =
      if (geom.boxById.contains(p)) p
      else {
        val head = p.takeWhile(_ != '.')
        if (geom.boxById.contains(head)) head else LayoutCore.nodeBaseId(head)
      }
    private lazy val wiresByBox: Map[String, Set[dom.Element]] =
      wires.iterator.flatMap(w => Iterator(endBox(w.from) -> w.el, endBox(w.to) -> w.el)).toSeq
        .groupMap(_._1)(_._2).map { case (k, v) => k -> v.toSet }
    private lazy val boxesById: Map[String, Set[dom.Element]] =
      boxes.groupMap(_.id)(_.el).map { case (k, v) => k -> v.toSet }

    /** What hovering the box `id` lights: the box, and the wires with an end on it. */
    def boxMatch(id: String): Match = if (geom == null) NoMatch else
      new Match(wiresByBox.getOrElse(id, Set.empty), boxesById.getOrElse(id, Set.empty), Set.empty, Set.empty)

    /** What a selection `s` lights. Wires and pins: either path a prefix of the
      * other. Boxes: `s` is the id or under it (`id.`). Peers: the instances
      * sharing the selected instance's clock. */
    def selMatch(s: String): Match = if (geom == null) NoMatch else selCache.getOrElseUpdate(s, {
      val selInst  = ownerBoxId(geom.boxById, s)
      val selClock = geom.childByName.get(selInst).map(_.clock).getOrElse("")
      // `s == id` or `s.startsWith(id + ".")`: the ids among `s` and its prefixes ending before a dot.
      val boxIds = Iterator.single(s) ++ s.indices.iterator.filter(i => s.charAt(i) == '.').map(s.substring(0, _))
      new Match(
        (wireFrom.startingWith(s) ++ wireTo.startingWith(s) ++ wireFrom.prefixesOf(s) ++ wireTo.prefixesOf(s)).toSet,
        boxIds.flatMap(selBoxes.exactly).toSet,
        if (selClock.isEmpty) Set.empty
        else byClock.getOrElse(selClock, Nil).iterator.filter(_.id != selInst).map(_.el).toSet,
        (pinIdx.startingWith(s) ++ pinIdx.prefixesOf(s)).toSet,
      )
    })

    /** What a hover `h` lights: wires whose `from` or `to` starts with `h`, and
      * pins related to `h` both ways. */
    def hovMatch(h: String): Match = if (geom == null) NoMatch else hovCache.getOrElseUpdate(h, new Match(
      (wireFrom.startingWith(h) ++ wireTo.startingWith(h)).toSet,
      Set.empty, Set.empty,
      (pinIdx.startingWith(h) ++ pinIdx.prefixesOf(h)).toSet,
    ))

    // The same matches by a full scan, the reference for the self-check below.
    private def selScan(s: String): Match = {
      val selInst  = ownerBoxId(geom.boxById, s)
      val selClock = geom.childByName.get(selInst).map(_.clock).getOrElse("")
      new Match(
        wires.iterator.filter(w => w.from.startsWith(s) || w.to.startsWith(s) || s.startsWith(w.from) || s.startsWith(w.to)).map(_.el).toSet,
        boxes.iterator.filter(b => b.kind != BoxKind.Stub && (s == b.id || s.startsWith(b.id + "."))).map(_.el).toSet,
        if (selClock.isEmpty) Set.empty
        else boxes.iterator.filter(b => b.kind == BoxKind.Instance && b.id != selInst && geom.childByName.get(b.id).exists(_.clock == selClock)).map(_.el).toSet,
        pins.iterator.filter(p => p.path.startsWith(s) || s.startsWith(p.path)).map(_.el).toSet,
      )
    }
    private def hovScan(h: String): Match = new Match(
      wires.iterator.filter(w => w.from.startsWith(h) || w.to.startsWith(h)).map(_.el).toSet,
      Set.empty, Set.empty,
      pins.iterator.filter(p => p.path.startsWith(h) || h.startsWith(p.path)).map(_.el).toSet,
    )

    /** Test hook (smoke): the indexed matches against a full scan, for every
      * wire end, pin and box key of this view and each of their dot prefixes.
      * Returns (keys checked, the keys that differ). */
    def selfCheck(): (Int, Seq[String]) = if (geom == null) (0, Nil) else {
      val base = (wires.flatMap(w => Seq(w.from, w.to)) ++ pins.map(_.path) ++ boxes.map(_.id)).toSet
      val keys = base ++ base.flatMap(k => k.indices.filter(i => k.charAt(i) == '.' || k.charAt(i) == '[').map(k.substring(0, _))) - ""
      val bad = keys.toSeq.sorted.filterNot(k => selMatch(k).sameAs(selScan(k)) && hovMatch(k).sameAs(hovScan(k)))
      val ids = boxes.map(_.id).distinct
      val badBox = ids.sorted.filterNot(id => boxMatch(id).sameAs(boxScan(id))).map("box " + _)
      (keys.size + ids.size, bad ++ badBox)
    }
    private def boxScan(id: String): Match = new Match(
      wires.iterator.filter(w => endBox(w.from) == id || endBox(w.to) == id).map(_.el).toSet,
      boxes.iterator.filter(_.id == id).map(_.el).toSet, Set.empty, Set.empty)
  }
  object Registry { def empty: Registry = new Registry(null) }

  /** A class carried by a set of elements: a new set toggles the class on the
    * difference only. */
  private final class Channel(cls: String) {
    private var on: Set[dom.Element] = Set.empty
    def set(next: Set[dom.Element]): Unit = if (next ne on) {
      on.foreach(e => if (!next(e)) e.classList.remove(cls))
      next.foreach(e => if (!on(e)) e.classList.add(cls))
      on = next
    }
    def reset(): Unit = on = Set.empty
  }

  /** An attribute that is "1" on a set of elements and "0" elsewhere (a test hook). */
  private final class AttrChannel(attr: String) {
    private var on: Set[dom.Element] = Set.empty
    def set(next: Set[dom.Element]): Unit = {
      on.foreach(e => if (!next(e)) e.setAttribute(attr, "0"))
      next.foreach(e => if (!on(e)) e.setAttribute(attr, "1"))
      on = next
    }
    def reset(): Unit = on = Set.empty
  }

  /** The dimming of one view: the box ids left lit (None = no dimming), and the
    * editor-focus hits (None = no focus). Filter dimming (issue #121) lights the
    * fuzzy matches on inst+type and their direct wire neighbours. The editor
    * focus (plan 2) lights the instances generated by `focusClass`, through the
    * same dim; it is ignored with a selection or a filter, and when nothing in
    * the view matches (no all-dimmed view). */
  def dimSets(g: Geom, filter: String, focusClass: String, selDefined: Boolean): (Option[Set[String]], Option[Set[String]]) = {
    val filterActive: Option[Set[String]] = {
      val q = filter.trim.toLowerCase
      if (q.isEmpty) None
      else {
        val matched = g.layout.nodes.iterator.map(_.id).filter { inst =>
          val hay = (inst + " " + g.childByName.get(inst).map(_.`type`).getOrElse("")).toLowerCase
          fuzzyHas(hay, q)
        }.toSet
        val act = mutable.Set.empty[String] ++= matched
        g.wires.foreach { w =>
          val a = ownerBoxId(g.boxById, w.fromPath); val b = ownerBoxId(g.boxById, w.toPath)
          if (matched(a)) act += b
          if (matched(b)) act += a
        }
        Some(act.toSet)
      }
    }
    val focusActive: Option[Set[String]] =
      if (focusClass.isEmpty || selDefined || filterActive.isDefined) None
      else {
        val hits = g.mod.children.iterator
          .filter(ch => g.moduleByName.get(ch.`type`).exists(_.scalaClass == focusClass)).map(_.name).toSet
        if (hits.isEmpty) None else Some(hits)
      }
    (filterActive.orElse(focusActive), focusActive)
  }

  /** The highlight rules. On the page (a style element in the pane) and in every
    * export (inside the SVG's foreignObject), so both draw the same.
    *
    * Wires (`.scl-w` groups, children `.scl-wh` halo / `.scl-ws` stroke / end
    * marks) read `--wk` (stroke colour), `--wsw` (stroke width), `--whk` and
    * `--who` (halo colour and opacity); the rules set them, in this order:
    * resting ink2, hovered ink, selected accent; colorized resting wires take
    * their `--wc` when nothing is selected; with a selection every unlit wire
    * draws ink4 at .32 (a hovered one keeps its width and halo); field-level
    * wires stay 1px; a filter or focus dim (`.off`) wins at .12. The wires of
    * a hovered block (`.ohov`) draw thicker in the hover colour, colorized or
    * not, selection or not. The followed net's wires (`.follow`) draw in
    * `--fol`, undimmed by a selection; a selected one keeps the accent, a
    * hovered block's the hover colour, and a filter dim still applies. Its
    * pins, field pins (`.scl-fpin`), boundary stubs and field row labels
    * (`.scl-rl`) take `--fol` too.
    *
    * A hovered block (`.scl-b.ohov`, `.scl-sym.ohov`) gets an outline in the
    * hover colour (`--hov`), which composes with the selection's border.
    *
    * Boxes (`.scl-b`) keep their resting border colour, shadows and fill as
    * custom properties (`--bk0`, `--bs0`, `--bsp` clock peer, `--bs1` selected,
    * `--bg0`, `--bf` colorized). Register and op symbols (`.scl-sym`) draw in
    * `--sym`. Pins (`.scl-pin`): selected accent, hovered ink, interface accent,
    * else ink3. Nothing here may use `<` or `&` (it is serialized as XHTML). */
  val css: String = Seq(
    ".scl-w{--wk:var(--ink2);--wsw:3px;--whk:var(--ink2);--who:.07;opacity:1}",
    ".scl-w.hov{--wk:var(--ink);--wsw:3.5px;--whk:var(--ink);--who:.1}",
    ".scl-w.hl{--wk:var(--accent);--wsw:4px;--whk:var(--accent);--who:.18}",
    ".scl-w.fl{--wsw:1px}",
    // A hovered block's wires: thicker, in its outline colour, over a selection's dim.
    s".scl-w.ohov{--wk:${Tokens.hov};--wsw:4.5px;--whk:${Tokens.hov};--who:.2}",
    ".scl-w.fl.ohov{--wsw:2px}",
    ".hl-cw:not(.hl-sel) .scl-w:not(.hov):not(.ohov):not(.follow){--wk:var(--wc);--whk:var(--wc)}",
    ".hl-sel .scl-w:not(.hl):not(.ohov):not(.follow){--wk:var(--ink4);opacity:.32}",
    // The followed net, over a selection's dim and the colourization; the
    // selected wire keeps the accent, a hovered block's wires the hover colour.
    s".scl-w.follow:not(.ohov){--wk:${Tokens.fol};--wsw:4px;--whk:${Tokens.fol};--who:.2}",
    ".scl-w.fl.follow:not(.ohov){--wsw:2px}",
    ".scl-w.follow.hl:not(.ohov){--wk:var(--accent);--whk:var(--accent);--who:.18}",
    ".scl-w.off,.hl-sel .scl-w.off{opacity:.12}",
    ".scl-b{border-color:var(--bk0);box-shadow:var(--bs0);background:var(--bg0)}",
    s".scl-b.follow{border-color:${Tokens.fol}}",
    ".scl-b.peer{border-color:var(--accentLine);box-shadow:var(--bsp)}",
    ".scl-b.sel{border-color:var(--accent);box-shadow:var(--bs1)}",
    ".hl-cb .scl-b{background:var(--bf)}",
    ".scl-glyph{color:var(--ink3)}",
    ".scl-b.sel .scl-glyph{color:var(--accent)}",
    ".scl-sym{--sym:var(--ink2)}",
    ".scl-sym.sel{--sym:var(--accent)}",
    ".hl-cb .scl-sym{background:var(--bf);border:1px solid var(--borderSoft);border-radius:5px}",
    ".scl-b.off,.scl-sym.off{opacity:.18}",
    // The hovered block: an outline, so it composes with the selection's border and shadow.
    s".scl-b.ohov,.scl-sym.ohov{outline:2px solid ${Tokens.hov};outline-offset:2px}",
    ".scl-pin{background:var(--ink3)}",
    ".scl-pin.if{background:var(--accent)}",
    ".scl-fpin{background:var(--ink3)}",
    s".scl-pin.follow,.scl-fpin.follow{background:${Tokens.fol}}",
    s".scl-rl.follow{color:${Tokens.fol};font-weight:600}",
    ".scl-pin.hov{background:var(--ink)}",
    ".scl-pin.sel{background:var(--accent)}",
  ).mkString("\n")
}
