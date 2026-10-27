package socless.ui.schematic

import socless.ui.theme._
import socless.ui.model._
import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.{ReactiveElement, ChildNode}
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scala.util.control.NonFatal
import socless.schema.{Design, ModuleDef, NodeKind, PinSide, ModuleKind, SourceRef}
import socless.layout.{InstanceFold, LayoutCore}
import socless.js.BrowserLayout
import socless.js.BrowserLayout.{LaidOut, LaidNodeR, LaidPortR, BoxOverride}

/** The schematic (center) pane: a block diagram of the current module's children
  * and their wires (epic #172 Phase 2). Behavioral port of the JS `BenchSchematic`
  * (ui/SoCLess.html:2994+). Unlike the JS UI, the layout engine runs **in-process**:
  * the baked layout comes from the typed `Design.layouts`, and interactive
  * re-layout (bundle expand / Optimize) calls [[BrowserLayout.runTyped]] directly.
  * The expansion row model is computed in Scala by [[RowModel]] (issue #170 B/C). */
object Schematic {

  // The pure geometry/model layer (wire/box types + side-effect-free helpers)
  // lives in the sibling [[SchematicGeom]] (#216); pull its members in unqualified
  // so the render half below reads as before.
  import SchematicGeom._

  /** Toggle an id in the single-select model: select it, or clear if it is
    * already selected. Shared by every clickable box/wire. */
  private def toggleSelect(sel: Var[Option[String]], id: String): Unit =
    sel.update(s => if (s.contains(id)) None else Some(id))

  /** Conditional child: the element when `c`, else a no-op comment node. The
    * explicit [[ChildNode.Base]] result type keeps the if/else from widening to
    * `Object` in argument position. */
  private def opt(c: Boolean)(el: => ChildNode.Base): ChildNode.Base = if (c) el else emptyNode

  // ── Render ──────────────────────────────────────────────────────────────────

  def render(
    designSig: Signal[Option[Design]],
    moduleSig: Signal[Option[ModuleDef]],
    pathSig: Signal[List[String]],
    selectedVar: Var[Option[String]],
    hoveredVar: Var[Option[String]],
    // In-box expanded bundle pin paths per module name (App owns them); the
    // module in view reads and toggles its own entry only.
    expandedPortsVar: Var[Map[String, Set[String]]],
    drillInto: String => Unit,
    // Fold or unfold a generator-copy group of the module in view (App owns the state).
    toggleFold: String => Unit,
    // Colorize toggles live in the settings panel (#274), so App owns them and
    // passes them in; the schematic reads them per-render (#266).
    colorBoxesVar: Var[Boolean],
    colorWiresVar: Var[Boolean],
    // Declutter (#223): node kinds hidden from the schematic; App owns the set so
    // the settings panel can drive it. Empty = show everything.
    hiddenKindsVar: Var[Set[String]],
    // Auto-optimize (#209): when on, run the optimize layout on every module view
    // so the initial render matches Optimize (off-thread, so it stays responsive).
    autoOptimizeVar: Var[Boolean],
    // The FSM block whose state diagram replaces the schematic (App owns it, so
    // the breadcrumb, Escape and navigation can close it); None = the schematic.
    fsmOpenVar: Var[Option[String]],
    // Open a source position in the editor (a diagram's state or transition).
    openSource: SourceRef => Unit,
    designSnapshot: () => Option[(String, String)] = () => None, // (rawJson, top) for the share button
    // Generator class under the editor cursor (plan 2, language server); "" = none.
    focusClassSig: Signal[String] = Signal.fromValue(""),
    // The HTML snapshot ("share") button; off when embedded in an editor.
    showShare: Boolean = true,
    // The hint bar under the schematic (App mounts it): it follows the target
    // under the pointer in this viewport.
    hintBar: Option[socless.ui.panels.Help.HintBar] = None,
    // An overlay over the page (the "?" card): the navigation keys do nothing.
    keysBlocked: () => Boolean = () => false,
    // Back, Forward, Zoom to selection and fly to, for the app (the palette);
    // installed here.
    history: ViewHistory.Commands = new ViewHistory.Commands,
    // Show a drill path (Back / Forward, a fly to another module); false when
    // it does not resolve in the design.
    navigate: List[String] => Boolean = _ => false,
  ): Element = {

    // Schematic-internal state (persists for the life of the pane).
    val zoomVar     = Var(1.0)
    val panVar      = Var((40.0, 40.0))
    // The in-process engine (elkjs) layout, TAGGED with the module (name and
    // unfolded copies, `viewTag`) it was computed for. The tag is essential:
    // `geomSig` and the re-layout trigger both fire on a module change, and an
    // engine result for the PREVIOUS module must never be used to place the new
    // module's wires (it would leave every wire unanchored until the async
    // relayout lands, issue #258 follow-up). The same holds across a design
    // change (live reload): the result also records the design it was laid out for.
    val engineVar   = Var[Option[(String, Design, LaidOut)]](None)
    val pendingVar  = Var(false)
    val optNonceVar = Var(0)
    val showLayersVar = Var(false) // verification layers hidden by default (issue #212)
    val totalWVar   = Var(1.0)
    val canvasHVar  = Var(1.0)
    val filterVar   = Var("")              // schematic filter query (issue #121)
    // Hover, selection, dimming and colourization as styling on the elements
    // `renderContent` builds, never a rebuild (perf; see Highlight).
    val hl = new Highlight(selectedVar, hoveredVar)
    // The hovered block's outline and the tooltip card (delegated listeners on
    // the viewport, installed at mount).
    val tooltip = new Tooltip(hl)
    var tooltipOff: () => Unit = () => ()
    var hintOff: () => Unit = () => ()
    val viewSizeVar = Var((0.0, 0.0))      // viewport client size (for the minimap)

    val viewState = scala.collection.mutable.Map.empty[String, (Double, (Double, Double))]
    var prevPathKey: String = null
    var viewportNode: dom.html.Element = null
    var viewObserver: dom.ResizeObserver = null
    var contentNode: dom.html.Element = null   // the transformed content div, for export
    var curModName = ""                        // current module name, for export filenames
    var engineGen = 0
    var lastNonce = 0
    // The path key whose view is in place (see `settle`); None before the first.
    val settledPathVar = Var[Option[String]](None)
    var pendingFit = true   // armed on a real path change; performed once dims are known (issue #207)
    // The view is the automatic fit and the user has not panned or zoomed since:
    // a later relayout (auto-optimize landing after the baked first paint) refits.
    var autoFitted = false

    // Drag, wheel and keys (Gestures), installed on the viewport at mount.
    var gestures: Gestures.Controller = null
    var gesturesOff: () => Unit = () => ()

    // ── view history and flights (ViewHistory) ──
    var hist = ViewHistory.Stack()
    var curPath: List[String] = Nil
    // A Back or Forward entry whose module is being shown: its view stands in
    // for the fit (or the module's saved view) and is not pushed again.
    var restoring = Option.empty[ViewHistory.Entry]
    // A box to fly to, (path key, box id), once that module's layout is ready.
    var flyPending = Option.empty[(String, String)]
    // The user moved the view since the last entry; once it rests for
    // SettleMs it becomes one.
    var dirty = false
    var settleTimer = 0
    val flyer = new ViewHistory.Flyer(panVar, zoomVar, () => viewSizeVar.now())
    def cancelSettle(): Unit = if (settleTimer != 0) { dom.window.clearTimeout(settleTimer); settleTimer = 0 }
    // Push the view shown; not while the module's own view is still to come.
    def pushView(): Unit = {
      cancelSettle(); dirty = false
      if (!pendingFit && curPath.nonEmpty) hist = hist.push(ViewHistory.Entry(curPath, panVar.now(), zoomVar.now()))
    }
    // A view left before it settled is still a place the user was: push it
    // before the move that leaves it.
    def commitDirty(): Unit = if (dirty) pushView()
    def armSettle(): Unit = {
      cancelSettle()
      settleTimer = dom.window.setTimeout(() => {
        settleTimer = 0
        // A drag holding still is not a settled view.
        if (gestures != null && gestures.busy) armSettle()
        else if (dirty && !flyer.running && !pendingFit) pushView()
      }, ViewHistory.SettleMs)
    }

    def clamp(z: Double): Double = math.max(0.08, math.min(4.0, z))

    // Memoize elkjs layout results so repeated drill / expand / collapse and
    // Optimize-off return instantly instead of re-running the engine on the main
    // thread (perf). Keyed by module + mode + expansion + box overrides, and
    // cleared when the design changes (live reload), since the same module name
    // can then describe different internals. Bounded so pathological expand
    // toggling cannot grow it without limit.
    val engineCache    = scala.collection.mutable.LinkedHashMap.empty[String, LaidOut]
    val engineCacheCap = 96
    def engineKey(modTag: String, mode: String, expanded: Set[String], overrides: Map[String, BoxOverride], hidden: Set[String]): String = {
      val exp = expanded.toList.sorted.mkString(",")
      val ovr = overrides.toList.sortBy(_._1).map { case (k, o) => s"$k=${o.height.getOrElse(0.0)}" }.mkString(",")
      val hid = hidden.toList.sorted.mkString(",")
      s"$modTag|$mode|$exp|$ovr|$hid"
    }
    // A module as shown: its name plus the generator copies unfolded in it, so an
    // engine layout (and its cache entry) never serves another fold state.
    def unfoldedMembers(mod: ModuleDef): List[String] = mod.children.filter(_.foldGroup.nonEmpty).map(_.name).toList.sorted
    def viewTag(mod: ModuleDef): String = (mod.name :: unfoldedMembers(mod)).mkString("|")
    // The pre-engine geometry the rows and box overrides are measured against:
    // the baked layout, or, with copies unfolded (absent from the all-folded
    // baked layout), the prepared boxes and pins the engine starts from.
    def baseOf(d: Design, mod: ModuleDef): Option[LaidOut] =
      bakedOf(d, mod).map { bl =>
        if (unfoldedMembers(mod).isEmpty) bl
        else LaidOut(LayoutCore.prepare(mod, d).nodes.map { n =>
          LaidNodeR(n.id, n.kind, 0, 0, n.width.toInt, n.height.toInt,
            n.pins.map(p => LaidPortR(p.path, p.x.toInt, p.y.toInt, p.side, p.interface, p.label, p.bundle, p.expandable)))
        }, Nil, bl.w, bl.h)
      }
    def engineCachePut(key: String, v: LaidOut): Unit = {
      engineCache(key) = v
      if (engineCache.size > engineCacheCap) engineCache.remove(engineCache.head._1)
    }

    // ── geometry signal ──
    def computeGeom(d: Design, mod: ModuleDef, engine: Option[LaidOut], expanded: Set[String]): Geom = {
      val c       = Tokens.consts(d)
      val layout  = engine.orElse(bakedOf(d, mod)).getOrElse(LaidOut(Nil, Nil, 1, 1))
      val rows    = RowModel.build(layout, mod, d, expanded, c.headerH, c.portPad, c.portStep)
      val pinYMap = rows.map { case (i, br) => i -> br.pinY(c.headerH, c.portPad, c.portStep) }
      val boxById = layout.nodes.map(n => n.id -> n).toMap
      val shift   = computeBoxShift(layout, rows)
      // Node ids hidden by declutter (#223): wires that ran solely between them are
      // dropped silently rather than warned (an expected drop, not an anomaly).
      val hk = hiddenKindsVar.now()
      val hiddenIds = if (hk.isEmpty) Set.empty[String] else mod.logic.iterator.filter(n => hk.contains(n.kind)).map(_.id).toSet
      // A wire running solely between two hidden nodes is dropped outright; one
      // with a single hidden end stays so buildWires can stub it (#223). Resolve
      // synthetic pin heads (`x~q`, `_T~in0`) to their node id to test membership.
      def wireNode(p: String): String = LayoutCore.nodeBaseId(p.split('.').headOption.getOrElse(p))
      val dw0 = displayWires(mod, expanded)
      val dw  = if (hiddenIds.isEmpty) dw0
                else dw0.filterNot(w => hiddenIds.contains(wireNode(w.from)) && hiddenIds.contains(wireNode(w.to)))
      val wires   = buildWires(dw, layout, boxById, pinYMap, shift, hiddenIds)
      val canvasH = (layout.h + 40) + shift.total
      Geom(layout, mod, d, rows, wires, shift, canvasH, c, boxById)
    }

    // Paths that are expanded unconditionally: scattered-bundle groups and any
    // locked sub-group headers. Derived from the base rows (baseOf) so it matches RowModel.
    // The header/spacing constants are irrelevant to which paths are locked, so 0 is fine.
    def lockedExpansions(d: Design, mod: ModuleDef): Set[String] = {
      val baked = baseOf(d, mod).getOrElse(LaidOut(Nil, Nil, 1, 1))
      RowModel.build(baked, mod, d, Set.empty, 0, 0, 0)
        .values.flatMap(br => (br.westRows ++ br.eastRows).filter(_.locked).map(_.path)).toSet
    }

    // The expanded pins of the module in view: the geometry, the engine runs and
    // the pin marks all read this one signal, so they never see another module's.
    val expandedSig: Signal[Set[String]] =
      Signal.combine(moduleSig, expandedPortsVar.signal).map {
        case (Some(mod), e) => e.getOrElse(mod.name, Set.empty)
        case _              => Set.empty
      }
    def togglePort(module: String, p: String): Unit = expandedPortsVar.update { e =>
      val s = e.getOrElse(module, Set.empty)
      e.updated(module, if (s(p)) s - p else s + p)
    }

    val geomSig: Signal[Option[Geom]] =
      Signal.combine(designSig, moduleSig, engineVar.signal, expandedSig).map {
        case (Some(d), Some(mod), engine, expanded) =>
          // Use the engine layout only when it was computed for THIS module of
          // THIS design; otherwise fall back to the baked layout for the current
          // module so a stale engine result never mis-anchors the wires.
          val forThis = engine.collect { case (m, ed, l) if m == viewTag(mod) && (ed eq d) => l }
          // The baked layout is the all-folded module: until the engine lays out
          // an unfolded group, show the module folded rather than unanchored copies.
          val shown = if (forThis.isEmpty && unfoldedMembers(mod).nonEmpty) InstanceFold.fold(mod, Set.empty) else mod
          Some(computeGeom(d, shown, forThis, expanded ++ lockedExpansions(d, shown)))
        case _                                      => None
      }

    // ── in-process engine re-layout (issue #167) ──
    // The run whose layout is awaited (its generation), None when none is: the
    // spinner shows exactly while one is. A run starts in an observer, so its
    // writes to pendingVar and engineVar land in nested transactions, which can
    // apply out of order: a stale run's "pending" once landed after the newer
    // run's cache hit had cleared it, and the stale run's completion, superseded,
    // never cleared it again (the stuck "laying out..."). So every write reads
    // the state when it applies (Var.update), and only the latest run places.
    var awaiting = Option.empty[Int]
    // Smoke test hook: every engine run that missed the cache (key, time).
    val engineRuns = js.Array[js.Dynamic]()
    dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessEngineRuns")(engineRuns)
    def syncPending(): Unit = pendingVar.update(_ => awaiting.isDefined)
    def place(gen: Int, e: Option[(String, Design, LaidOut)]): Unit =
      engineVar.update(cur => if (gen == engineGen) e else cur)

    def runEngine(dO: Option[Design], mO: Option[ModuleDef], expanded: Set[String], optimize: Boolean, hidden: Set[String]): Unit = {
      // Every run supersedes the one in flight, whatever it ends on.
      engineGen += 1
      val myGen = engineGen
      awaiting = None
      def done(e: Option[(String, Design, LaidOut)]): Unit = { place(myGen, e); syncPending() }
      (dO, mO) match {
        case (Some(d), Some(mod)) =>
          val hasExpansion = expanded.nonEmpty
          // An unfolded group of generator copies is absent from the (all-folded)
          // baked layout, so it needs the engine too.
          val hasUnfolded = mod.children.exists(_.foldGroup.nonEmpty)
          // Declutter (#223) forces the engine too: the baked layout has every
          // node, so hiding kinds needs a re-layout over the visible subset.
          if (!hasExpansion && !hasUnfolded && !optimize && hidden.isEmpty) { done(None); return }
          baseOf(d, mod) match {
            case None => done(None)
            case Some(bl) =>
              val c    = Tokens.consts(d)
              val rows = RowModel.build(bl, mod, d, expanded, c.headerH, c.portPad, c.portStep)
              val overrides = rows.flatMap { case (inst, br) =>
                val baseH = bl.nodes.find(_.id == inst).map(_.h.toDouble).getOrElse(0.0)
                if (br.boxH > baseH)
                  Some(inst -> BoxOverride(Some(br.boxH), br.pinY(c.headerH, c.portPad, c.portStep)))
                else None
              }
              val mode = if (optimize) "optimize" else "reroute"
              val tag  = viewTag(mod)
              val key  = engineKey(tag, mode, expanded, overrides, hidden)
              engineCache.get(key) match {
                case Some(cached) =>
                  // Cache hit: place synchronously, no engine run, no spinner.
                  done(Some((tag, d, cached)))
                case None =>
                  engineRuns.push(js.Dynamic.literal(key = key, t = dom.window.performance.now(), cached = engineCache.size))
                  awaiting = Some(myGen); syncPending()
                  // runTyped prepares the graph before its Future exists: a throw
                  // there is a failed run too, never a spinner left on.
                  val laid = try BrowserLayout.runTyped(mod, d, mode, overrides, expanded, hidden)
                             catch { case NonFatal(e) => Future.failed(e) }
                  laid.onComplete { r =>
                    if (myGen == engineGen) {
                      awaiting = None; syncPending()
                      r match {
                        case Success(res) => engineCachePut(key, res); done(Some((tag, d, res)))
                        case Failure(e)   =>
                          done(None)
                          dom.console.warn("[socless] in-browser layout failed, using baked: " + e.getMessage)
                      }
                    }
                  }
              }
          }
        case _ => done(None)
      }
    }

    // ── view fit / pan / zoom ──
    // The viewport size comes from a ResizeObserver (`viewSizeVar`), never from
    // a clientWidth/clientHeight read, which forced a reflow mid-update (perf).
    // Pan and zoom always change in one transaction, so the transform (and the
    // minimap's viewport rect) recompute once per gesture step.

    // Fit the content to the viewport. False when a size is not known yet (the
    // first ResizeObserver report has not landed), so an armed fit stays armed.
    def doFit(): Boolean = {
      val tw = totalWVar.now(); val ch = canvasHVar.now()
      val (w, h) = viewSizeVar.now()
      if (tw <= 0 || ch <= 0 || w <= 0 || h <= 0) false
      else {
        val margin = 40.0
        val z = math.min(1.0, math.min((w - 2 * margin) / tw, (h - 2 * margin) / ch))
        Var.set(zoomVar -> z, panVar -> ((w - tw * z) / 2, (h - ch * z) / 2))
        autoFitted = true
        true
      }
    }

    // The automatic fit (or the restore of a module's saved view) runs in the
    // next animation frame, once the geometry and the viewport size of this
    // update have both landed. Any user pan or zoom cancels a scheduled one.
    var fitFrame = 0 // the requestAnimationFrame handle, 0 = none scheduled
    def cancelFit(): Unit = if (fitFrame != 0) { dom.window.cancelAnimationFrame(fitFrame); fitFrame = 0 }
    def scheduleFit(): Unit = if (fitFrame == 0) fitFrame = dom.window.requestAnimationFrame { _ =>
      fitFrame = 0
      runFit()
    }
    // A new module's first view is an entry of the history, unless Back or
    // Forward brought it (its entry is already there). A refit when a
    // relayout lands only corrects the entry of the fit it replaces.
    def runFit(): Unit = {
      if (pendingFit) {
        val back = restoring.filter(_.path.mkString("/") == prevPathKey)
        restoring = None
        back match {
          case Some(e) => Var.set(zoomVar -> e.zoom, panVar -> e.pan); autoFitted = false; settle()
          case None    => viewState.get(prevPathKey) match {
            case Some((z, p)) => Var.set(zoomVar -> z, panVar -> p); autoFitted = false; settle(); pushView()
            case None         => if (doFit()) { settle(); pushView() }
          }
        }
      }
      else if (autoFitted && doFit() && hist.current.exists(_.path == curPath))
        hist = hist.replace(ViewHistory.Entry(curPath, panVar.now(), zoomVar.now()))
    }
    // The current module's view is in place (fitted, restored, or set by the
    // user): disarm the fit and let the minimap show for this module. Until
    // then the minimap stays hidden, so it is never built for the previous
    // module's zoom while the fit is a frame away.
    def settle(): Unit = {
      pendingFit = false
      if (!settledPathVar.now().contains(prevPathKey)) settledPathVar.set(Some(prevPathKey))
    }
    // The view is set from here on, not fitted automatically.
    def takeView(): Unit = { autoFitted = false; cancelFit(); settle() }
    // A user pan or zoom: the view is theirs from now on. It stops a flight
    // (and drops one still waiting for its layout), and becomes an entry of
    // the history once it settles.
    def userView(): Unit = { flyer.cancel(); flyPending = None; dirty = true; takeView() }

    def zoomBy(factor: Double): Unit = {
      userView()
      val z = zoomVar.now(); val newZ = clamp(z * factor)
      val (vw, vh) = viewSizeVar.now()
      val cx = vw / 2.0; val cy = vh / 2.0
      val ratio = newZ / z; val (px, py) = panVar.now()
      Var.set(panVar -> (cx - (cx - px) * ratio, cy - (cy - py) * ratio), zoomVar -> newZ)
    }

    // Fit now (the Fit button and the f and 0 keys).
    def fitNow(): Unit = { flyer.cancel(); flyPending = None; commitDirty(); cancelFit(); if (doFit()) { settle(); pushView() } }

    // Fly to a content rect: framed with `margin` px around it, at most
    // `maxZoom`. The flight's end is an entry; the view it leaves too, if it
    // had not settled yet.
    def flyToRect(r: ViewHistory.Rect, margin: Double, maxZoom: Double): Unit = {
      val (vw, vh) = viewSizeVar.now()
      if (vw > 0 && vh > 0) {
        commitDirty(); takeView()
        flyer.fly(ViewHistory.frame(r, vw, vh, margin, maxZoom, clamp), () => pushView())
      }
    }
    // `z`: frame the selection (a box, a wire, a pin). Nothing selected, or
    // not in this view: nothing (false), the fit keys are there for that.
    def zoomToSelection(): Boolean =
      (for { s <- selectedVar.now(); g <- Option(hl.geom); r <- ViewHistory.selectionRect(g, s) } yield r) match {
        case Some(r) => flyToRect(r, 40, 2.0); true
        case None    => false
      }
    // The pending fly to a box, once its module's view is in place and laid out.
    def tryFly(): Unit = flyPending.foreach { case (pk, id) =>
      if (prevPathKey == pk && settledPathVar.now().contains(pk) && !pendingVar.now())
        Option(hl.geom).filter(_.boxById.contains(id)).flatMap(ViewHistory.selectionRect(_, id)).foreach { r =>
          flyPending = None
          flyToRect(r, 60, 1.0)
        }
    }
    // Back or Forward: the entry's module first (its view replaces the fit),
    // or just its view in the module shown. Entries whose path no longer
    // resolves are skipped, so the walk never gets stuck on one.
    def walk(step: ViewHistory.Stack => ViewHistory.Stack): Unit = {
      flyer.cancel(); flyPending = None; commitDirty()
      val from = hist
      var next = step(from)
      var landed = false
      while (!landed && next.index != from.index && next.current.isDefined) {
        val e = next.current.get
        if (e.path == curPath) {
          hist = next; landed = true
          if (pendingFit) restoring = Some(e)
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
    history.back = () => walk(_.back)
    history.forward = () => walk(_.forward)
    history.zoomToSelection = () => zoomToSelection()
    // A fresh load: forget the history, any pending flight or settle, and the
    // followed net. Called before the new design's path is set, so the view
    // it leaves is not pushed and the new path's fit is the first entry.
    history.reset = () => {
      flyer.cancel(); cancelSettle(); dirty = false
      hist = ViewHistory.Stack(); restoring = None; flyPending = None
      hl.follow(None)
    }
    history.flyTo = (path, id) => {
      flyPending = Some((path.mkString("/"), id))
      if (path == curPath) tryFly() else if (!navigate(path)) flyPending = None
    }

    // ── content (boxes + wires), rebuilt on geometry (and the layers toggle) only ──
    def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

    // A value stub at a pin: a short wire out of the port ending in a
    // perpendicular bar (|-), on the pin's outer side, with the value label
    // past it. Shared by constant annotations and tied ports.
    def pinStub(p: LaidPortR, py: Double, label: String, tip: String, tag: Modifier[HtmlElement]): HtmlElement = {
      val isWest = p.side == PinSide.West
      val px     = p.x.toDouble
      val len    = 10.0
      val d = if (isWest) s"M $px $py L ${px - len} $py M ${px - len} ${py - 5} L ${px - len} ${py + 5}"
              else        s"M $px $py L ${px + len} $py M ${px + len} ${py - 5} L ${px + len} ${py + 5}"
      val labelStyle =
        if (isWest) s"position:absolute;left:${px - len - 4}px;top:${py - 7}px;transform:translateX(-100%);text-align:right"
        else        s"position:absolute;left:${px + len + 4}px;top:${py - 7}px;text-align:left"
      val lblBase = s"font-family:${Tokens.mono};font-size:9px;font-weight:600;color:${Tokens.ink2};white-space:nowrap"
      div(
        tag, dataAttr("pan-block") := "1",
        title := tip,
        svg.svg(sa("style") := "position:absolute;left:0;top:0;width:0;height:0;overflow:visible;pointer-events:none",
          svg.path(sa("d") := d, sa("style") := s"stroke:${Tokens.ink3};stroke-width:1.2;fill:none")),
        span(styleAttr := s"$labelStyle;$lblBase;background:${Tokens.panel};padding:0 3px;border-radius:2px", label),
      )
    }

    def renderContent(g: Geom, showLayers: Boolean, path: List[String]): Seq[ReactiveElement.Base] = {
      // Every element a highlight touches registers here as it is built; the
      // current hover, selection, dimming and followed net are applied to it
      // at the end (the net for the drill path `g` is shown at).
      val reg = new Highlight.Registry(g, path)
      // Interactive state the box/port renderers in [[SchematicBoxes]] need (#216).
      val boxCtx = SchematicBoxes.Ctx(selectedVar, expandedSig, p => togglePort(g.mod.name, p), reg, id => fsmOpenVar.set(Some(id)))
      // Verification-layer nodes (issue #212) and their wires are hidden unless
      // "show layers" is on. layerIds empty for a design with no layer content.
      // A wire's fromInst/toInst is a raw pin head (`L~in<i>`, `L~q`); resolve it
      // to the owning node id (LayoutCore.isLayerHead) so a layer node's incoming
      // wires hide WITH its box instead of stranding as dangling stubs.
      val layerIds: Set[String] = g.mod.logic.iterator.filter(_.layer).map(_.id).toSet
      val hideLayer = !showLayers && layerIds.nonEmpty
      val visWires = if (hideLayer) g.wires.filterNot(w => LayoutCore.isLayerHead(w.fromInst, layerIds) || LayoutCore.isLayerHead(w.toInst, layerIds)) else g.wires
      val c = g.c

      // ── hit layer (transparent fat paths, captures clicks) ──
      val hit = svg.svg(
        sa("style") := s"position:absolute;top:0;left:0;width:${g.layout.w}px;height:${g.canvasH}px;overflow:visible",
        visWires.map { w =>
          svg.path(
            sa("d") := routePath(w.points),
            sa("data-wire") := w.fromPath,
            sa("data-wire-to") := w.toPath,
            sa("data-pan-block") := "1",
            // The tooltip card names the bundle, both ends and the width (#276).
            sa("style") := "stroke:transparent;stroke-width:14;fill:none;cursor:pointer;pointer-events:stroke",
            // A click selects the wire and follows its net across levels
            // (NetFollow); a click that deselects it stops following it.
            onClick --> { (_: dom.MouseEvent) =>
              toggleSelect(selectedVar, w.fromPath)
              hl.clickWire(w.fromPath, w.toPath, selectedVar.now().contains(w.fromPath))
            },
            onMouseEnter --> { (_: dom.MouseEvent) => hl.enter(w.fromPath) },
            onMouseLeave --> { (_: dom.MouseEvent) => hl.leave(w.fromPath) },
          )
        },
      )

      // ── constant annotations (issue #203) ──
      // A constant is not a node: it renders as a small value label at the port
      // it drives (const logic id is `const$<sinkPath>`). For a register the
      // constant is the init value, so it sits as a label ABOVE the symbol;
      // otherwise it is a `|-` stub on the driven side of the port.
      val constEls: Seq[ReactiveElement.Base] = g.mod.logic.iterator.filter(c => c.kind == NodeKind.Const && !(hideLayer && layerIds.contains(c.id))).flatMap { cn =>
        val sinkPath = cn.id.stripPrefix("const$")
        val raw = if (cn.label.nonEmpty) cn.label else cn.id
        val value = raw.indexOf(": ") match { case i if i > 0 => raw.take(i); case _ => raw }
        g.pinOwner.get(sinkPath).map { case (b, p) =>
          val shift  = g.boxShift.shift.getOrElse(b.id, 0.0)
          val py     = p.y + shift
          val lblBase = s"font-family:${Tokens.mono};font-size:9px;font-weight:600;color:${Tokens.ink2};white-space:nowrap"
          if (b.kind == NodeKind.Reg) {
            // Register init value: label centered just above the flip-flop symbol.
            val cx     = b.x + b.w / 2.0
            val symTop = py - 18.0
            div(
              dataAttr("const-stub") := cn.id, dataAttr("pan-block") := "1",
              title := s"register init $value",
              span(styleAttr := s"position:absolute;left:${cx}px;top:${symTop - 13}px;transform:translateX(-50%);text-align:center;$lblBase", value),
            )
          } else pinStub(p, py, value, s"constant $value", dataAttr("const-stub") := cn.id)
        }
      }.toSeq

      // ── tied ports (captured view) ──
      // A constant tie-off is dropped from the layout and recorded in
      // `tieOffs`; it shows as the same `|-` value stub at the pin it lands on:
      // the pin whose path is the sink, else the nearest laid pin covering it
      // (a folded bundle row, a Vec group, a boundary stub). One stub per pin.
      val tiedEls: Seq[ReactiveElement.Base] = if (g.mod.tieOffs.isEmpty) Nil else {
        val pins = g.layout.nodes.iterator
          .filterNot(b => b.kind == NodeKind.Const || (hideLayer && layerIds.contains(b.id)))
          .flatMap(b => b.ports.map(p => (b, p))).toSeq.groupBy(_._2.path)
          // Two pins may share a path (a direction-split bundle, #237): keep the
          // driven one, which faces WEST (an instance input, or the boundary pin
          // of this module's output port).
          .map { case (path, bps) => path -> bps.find(_._2.side == PinSide.West).getOrElse(bps.head) }
        // The sink itself, then each prefix ending at a `.` or `[`, longest first.
        def pinOf(sink: String): Option[(LaidNodeR, LaidPortR)] =
          (sink.length +: (sink.length - 1 to 1 by -1).filter(i => sink(i) == '.' || sink(i) == '['))
            .iterator.flatMap(i => pins.get(sink.take(i))).nextOption()
        g.mod.tieOffs.flatMap(t => pinOf(t.sink).map(_ -> t)).groupBy(_._1._2.path).values.toSeq
          .sortBy(_.head._1._2.path).map { group =>
            val (b, p) = group.head._1
            val ties   = group.map(_._2)
            val values = ties.map(_.value).distinct
            // At most 10 listed lines, then `… and N more`.
            def capped(lines: Seq[String]): Seq[String] =
              if (lines.size <= 10) lines else lines.take(10) :+ s"… and ${lines.size - 10} more"
            val (label, tip) =
              if (values.size == 1)
                (values.head, (s"tied to ${values.head}" +: (if (ties.size > 1) capped(ties.map(_.sink)) else Nil)).mkString("\n"))
              else (s"${ties.size} tied", capped(ties.map(t => s"${t.sink} = ${t.value}")).mkString("\n"))
            pinStub(p, p.y + g.boxShift.shift.getOrElse(b.id, 0.0), label, tip, dataAttr("tied") := p.path)
          }
      }

      // ── boxes ──
      val boxEls: Seq[ReactiveElement.Base] = g.layout.nodes.filterNot(n => n.kind == NodeKind.Const || (hideLayer && layerIds.contains(n.id))).map { n =>
        val child = g.childByName.get(n.id)
        val shift = g.boxShift.shift.getOrElse(n.id, 0.0)
        val top   = n.y + shift
        if (n.kind == NodeKind.Reg) SchematicBoxes.regBox(boxCtx, g, n, top)
        // Op nodes have no `children` entry, so (like reg) they must be
        // intercepted before the childless-boundary arm below (issue #188 B2).
        else if (n.kind == NodeKind.Op) SchematicBoxes.opBox(boxCtx, g, n, top)
        // A collapsed FSM (issue #210): an instance-style box with boundary pins,
        // but non-drillable and tinted to read as a state machine. Intercepted
        // before the childless-boundary arm since it has no `children` entry.
        else if (n.kind == NodeKind.Fsm) SchematicBoxes.fsmBox(boxCtx, g, n, top)
        // A logic or generator block (captured view): FSM-shaped, no `children` entry.
        else if (n.kind == NodeKind.Block) SchematicBoxes.blockBox(boxCtx, g, n, top)
        // A memory node (issue #205): instance-style box with a memory glyph and a
        // RAM/SRAM depth×elem tag. Has no `children` entry, so it must be
        // intercepted before the childless-boundary arm like reg/op/fsm.
        else if (n.kind == NodeKind.Mem) SchematicBoxes.memBox(boxCtx, g, n, top)
        // Any childless node (a boundary stub, or any non-instance kind without a
        // child entry) renders as a stub; only a node with a child uses the
        // instance box below (which needs the child for its type/drilldown).
        else if (child.isEmpty || n.kind == NodeKind.Boundary) SchematicBoxes.boundaryStub(boxCtx, g, n, top)
        else {
          val ch         = child.get
          // A folded group of generator copies stands for several instances:
          // not drillable, its double-click unfolds it instead.
          val info       = g.instInfo(ch)
          val childMod   = info.mod
          val isBlackbox = info.blackbox
          val extLabel: String = childMod.map(m => if (m.kind == ModuleKind.Intrinsic) "intrinsic" else "ext").getOrElse("ext")
          val isFolded   = info.folded
          val drillable  = info.drillable
          // The cursor says what a click does: zoom-in where a double-click
          // opens (or unfolds), else the title's pointer selects.
          val opens      = drillable || isFolded
          // Generator signature of a captured module; empty from CHIRRTL.
          val sigFull    = childMod.filter(_.scalaClass.nonEmpty).map(ScalaClassLabel.signature).getOrElse("")
          // Clock/reset are filtered from the wire graph (no laid pin, see
          // displayWires), so a clocked instance carries a compact corner cue
          // instead of a routed clock tree: a clock-input notch bottom-left and a
          // reset ring bottom-right (issue #188 follow-up).
          val hasClock = childMod.exists(_.ports.exists(_.bundle == "Clock"))
          val hasReset = childMod.exists(_.ports.exists(p => p.bundle == "Reset" || p.bundle == "AsyncReset"))
          val boxInfo    = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
          // Selected, clock peer (of the selected instance) and resting looks
          // are classes over these values (Highlight.css). Stacked edge: a
          // folded box reads as several copies, in every state.
          val stacked    = if (isFolded) s", 4px 4px 0 ${Tokens.borderSoft}" else ""
          val looks      = SchematicBoxes.boxVars(n.kind, Tokens.border, SchematicBoxes.RestShadow + stacked,
                             s"0 0 0 2px ${Tokens.accentLine}$stacked", SchematicBoxes.SelShadow + stacked, Tokens.panel)
          // `+` on a folded box unfolds its group, `−` on a copy folds it back.
          val foldGroup  = if (isFolded) n.id else ch.foldGroup
          def foldToggle(glyph: String, tip: String) =
            span(dataAttr("fold-toggle") := foldGroup, title := tip,
              styleAttr := s"font-family:${Tokens.mono};font-size:11px;font-weight:700;line-height:13px;color:${Tokens.ink2};" +
                s"border:1px solid ${Tokens.border};border-radius:3px;padding:0 4px;background:${Tokens.panel};cursor:pointer;flex-shrink:0",
              onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); toggleFold(foldGroup) },
              onDblClick --> { (e: dom.MouseEvent) => e.stopPropagation() },
              glyph)
          val box = div(
            cls := "scl-b",
            dataAttr("box-inst") := n.id,
            dataAttr("box-drillable") := (if (drillable) "1" else "0"),
            // Editor focus (plan 2) test hook, kept up to date by Highlight.
            dataAttr("box-focus") := "0",
            Option.when(isFolded)(dataAttr("fold-group") := n.id),
            Option.when(ch.foldGroup.nonEmpty)(dataAttr("fold-member") := ch.foldGroup),
            // Test hook: how far the renderer pushed this box down to make room
            // for a grown box above that the layout did not size (0 once laid out).
            dataAttr("box-shift") := shift.toString,
            dataAttr("pan-block") := "1",
            styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
              looks + s"border-width:1px;border-style:${if (isBlackbox) "dashed" else "solid"};border-radius:6px;" +
              s"cursor:${if (opens) "zoom-in" else "default"};overflow:visible;" +
              "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
            onDblClick --> { (_: dom.MouseEvent) => if (isFolded) toggleFold(n.id) else if (drillable) drillInto(n.id) },
            // header
            div(
              styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px ${if (isBlackbox) "dashed" else "solid"} ${Tokens.borderSoft};" +
                s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:${if (opens) "zoom-in" else "pointer"}",
              onClick --> { (_: dom.MouseEvent) => toggleSelect(selectedVar, n.id) },
              span(cls := "scl-glyph", styleAttr := "width:12px;display:inline-flex;justify-content:center;font-size:10px", Glyphs.of(childMod)),
              // A fold box's name (and count) never shrinks: only the signature ellipsizes.
              span(dataAttr("box-name") := n.id,
                styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-name);font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0" +
                  (if (isFolded || ch.foldGroup.nonEmpty) ";flex-shrink:0" else ""),
                if (isFolded) s"${n.id} × ${ch.foldCount}" else n.id),
              // A captured design names the Scala generator and its parameter
              // values instead of the module name; the right group then shrinks
              // (ellipsis) far ahead of the instance name, which stays readable.
              // CHIRRTL designs (no scalaClass) keep the exact markup below.
              div(styleAttr := (if (sigFull.isEmpty) "margin-left:auto;flex-shrink:0;display:flex;align-items:center;gap:6px"
                                else "margin-left:auto;flex-shrink:1000;min-width:0;display:flex;align-items:center;gap:6px"),
                opt(isBlackbox)(span(styleAttr := s"font-family:${Tokens.mono};font-size:8px;font-weight:600;letter-spacing:.4px;text-transform:uppercase;color:${Tokens.ink3};background:${Tokens.panel};border:1px dashed ${Tokens.border};border-radius:3px;padding:0 4px;line-height:13px",
                  extLabel)),
                if (sigFull.isEmpty)
                  span(styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-type);color:${Tokens.ink2};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:80px", ch.`type`)
                else
                  span(dataAttr("box-class") := n.id,
                    styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-type);color:${Tokens.ink2};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0",
                    ScalaClassLabel.truncate(sigFull, ScalaClassLabel.HeaderMax)),
                opt(drillable)(span(styleAttr := s"font-family:${Tokens.mono};font-size:9px;color:${Tokens.accent}", "↵")),
              ),
              // Outside the shrinking group, so the toggle is never clipped.
              opt(isFolded)(foldToggle("+", s"Unfold the ${ch.foldCount} copies")),
              opt(ch.foldGroup.nonEmpty)(foldToggle("−", "Fold the copies back")),
            ),
            // port rows
            SchematicBoxes.renderPortRows(boxCtx, g, n, boxInfo.westRows, PinSide.West),
            SchematicBoxes.renderPortRows(boxCtx, g, n, boxInfo.eastRows, PinSide.East),
            // clock-input notch (bottom-left) and reset ring (bottom-right)
            opt(hasClock)(div(
              dataAttr("clk-cue") := n.id, title := "clock input",
              styleAttr := s"position:absolute;left:7px;bottom:3px;display:flex;align-items:center;gap:2px;" +
                s"font-family:${Tokens.mono};font-size:7.5px;line-height:1;color:${Tokens.ink4};pointer-events:none",
              span(styleAttr := "font-size:8px", "▷"), span("clk"))),
            opt(hasReset)(div(
              dataAttr("rst-cue") := n.id, title := "reset input",
              styleAttr := s"position:absolute;right:7px;bottom:3px;display:flex;align-items:center;gap:2px;" +
                s"font-family:${Tokens.mono};font-size:7.5px;line-height:1;color:${Tokens.ink4};pointer-events:none",
              span("rst"), span(styleAttr := "font-size:8px", "○"))),
          )
          reg.box(n.id, Highlight.BoxKind.Instance, box.ref)
          box
        }
      }

      // ── junctions (fan-out filled / fan-in hollow, issue #166; #247) ──
      // Fan-out: many wires share ONE driver (fromPath) -> dot where routes
      // diverge. Fan-in: many wires share ONE sink (toPath) -> dot where routes
      // converge. Fall back to the shared endpoint when routes diverge
      // immediately (no shared interior point).
      val fanOut = visWires.groupBy(_.fromPath).values.filter(_.length > 1).map { ws =>
        val pt = SchematicBoxes.branchPoint(ws.map(_.points).toSeq, fromStart = true)
          .getOrElse((ws.head.x1, ws.head.y1))
        (pt._1, pt._2, ws.length)
      }.toSeq
      val fanIn = visWires.groupBy(_.toPath).values.filter(_.length > 1).map { ws =>
        val pt = SchematicBoxes.branchPoint(ws.map(_.points).toSeq, fromStart = false)
          .getOrElse((ws.head.x2, ws.head.y2))
        (pt._1, pt._2, ws.length)
      }.toSeq

      // Field-level (split) wires render thinner so a scattered bundle reads as
      // many fine wires vs one bold bundle wire (issue #182).
      val fieldLevelWires: Set[(String, String)] =
        g.mod.wires.iterator.filter(_.fieldLevel).map(w => (w.from, w.to)).toSet

      // ── visual layer (stroke + halo + junctions) ──
      val vis = svg.svg(
        sa("style") := s"position:absolute;top:0;left:0;width:${g.layout.w}px;height:${g.canvasH}px;pointer-events:none;overflow:visible",
        visWires.map { w =>
          // Stroke, width, halo and opacity are classes over custom properties
          // (Highlight.css): selected, hovered, dimmed by a selection, by the
          // filter or the editor focus, and colorized at rest (#266) from the
          // wire's own `--wc`, computed here once.
          val isField = fieldLevelWires.contains((w.fromPath, w.toPath))
          val d = routePath(w.points)
          // Dangling wires (no routed ELK edge, issue #188 follow-up 2a) render
          // dashed with a small open circle at the unresolved (sink) end, so an
          // unrouted connection reads as visibly incomplete rather than absent.
          val dashStyle = if (w.dangling) ";stroke-dasharray:5 4" else ""
          val el = svg.g(
            sa("class") := (if (isField) "scl-w fl" else "scl-w"),
            // Test hooks: the wire's ends (the hover outline lights a box's wires).
            sa("data-from") := w.fromPath, sa("data-to") := w.toPath,
            sa("style") := s"--wc:${SchematicColors.wireStroke(w.bundle, w.toPath)};transition:opacity .15s",
            svg.path(sa("d") := d, sa("style") := "stroke:var(--whk);stroke-width:calc(var(--wsw) + 6px);fill:none;stroke-linecap:round;stroke-linejoin:round;opacity:var(--who)"),
            svg.path(sa("d") := d, sa("style") := s"stroke:var(--wk);stroke-width:var(--wsw);fill:none;stroke-linecap:round;stroke-linejoin:round$dashStyle"),
            if (w.dangling)
              svg.circle(sa("cx") := w.x2.toString, sa("cy") := w.y2.toString, sa("r") := "3.5",
                sa("style") := s"fill:${Tokens.bg};stroke:var(--wk);stroke-width:1.5",
                svg.titleTag(s"unrouted wire: ${w.fromPath} ↔ ${w.toPath}"))
            else if (w.bidir) SchematicBoxes.bidirGlyph(w.points, "var(--wk)") else emptyNode,
          )
          reg.wire(w, el.ref)
          el
        },
        fanOut.map { case (x, y, count) =>
          svg.circle(sa("data-junction") := "1", sa("cx") := x.toString, sa("cy") := y.toString, sa("r") := "3.5",
            sa("style") := s"fill:${Tokens.ink2};stroke:${Tokens.bg};stroke-width:1.5",
            svg.titleTag(s"$count-way fan-out"))
        },
        fanIn.map { case (x, y, count) =>
          svg.circle(sa("data-fanin") := "1", sa("cx") := x.toString, sa("cy") := y.toString, sa("r") := "3.5",
            sa("style") := s"fill:${Tokens.bg};stroke:${Tokens.ink2};stroke-width:1.75",
            svg.titleTag(s"$count-way fan-in"))
        },
      )

      hl.install(reg)
      // Constant annotations render after the wire layer so their labels sit on
      // top of crossing wires instead of being covered (#229).
      ((Seq[ReactiveElement.Base](hit) ++ boxEls) :+ (vis: ReactiveElement.Base)) ++ constEls ++ tiedEls
    }


    // Run `capture` with selection/hover highlight cleared, then restore it, so a
    // selected net doesn't export a washed-out one-net view (issue #131). The
    // Var change applies synchronously (Highlight's observers toggle the classes),
    // so the cloned DOM is already cleared.
    // The followed net is left out too, so an export looks as it did before.
    def withCleared(capture: () => Unit): Unit = hl.withoutFollow { () =>
      val ps = selectedVar.now(); val ph = hoveredVar.now()
      hl.clearBox()
      if (ps.isEmpty && ph.isEmpty) capture()
      else { selectedVar.set(None); hoveredVar.set(None); capture(); selectedVar.set(ps); hoveredVar.set(ph) }
    }
    def expW: Int = math.max(1, math.ceil(totalWVar.now()).toInt)
    def expH: Int = math.max(1, math.ceil(canvasHVar.now()).toInt)


    val transformSig = Signal.combine(panVar.signal, zoomVar.signal, totalWVar.signal, canvasHVar.signal).map {
      case ((px, py), z, w, h) =>
        s"position:absolute;left:0;top:0;width:${w}px;height:${h}px;transform:translate(${px}px,${py}px) scale($z);transform-origin:0 0;transition:width .3s,height .3s"
    }

    // Debounce the filter so each keystroke does not re-run the fuzzy match and
    // re-dim: the input itself stays responsive (it binds `filterVar` directly
    // in the chrome), only the dim recompute waits for a brief pause in typing
    // (perf).
    val filterDimSig: Signal[String] = filterVar.signal.composeChanges(_.debounce(140))

    // Matched/total box count for the filter chip (#276): None when no filter.
    val matchSig: Signal[Option[(Int, Int)]] =
      Signal.combine(geomSig, filterDimSig).map {
        case (Some(g), f) if f.trim.nonEmpty =>
          val q = f.trim.toLowerCase
          val matched = g.layout.nodes.count(n =>
            fuzzyHas((n.id + " " + g.childByName.get(n.id).map(_.`type`).getOrElse("")).toLowerCase, q))
          Some((matched, g.layout.nodes.size))
        case _ => None
      }

    // The content is rebuilt on a geometry change (module, design, engine
    // layout, expansion, fold) and the layers toggle, which adds or removes
    // elements. Hover, selection, filter, editor focus, colourization and the
    // followed net are styling (Highlight) and never rebuild it. The path
    // changes with the geometry (in the same transaction): it tags the
    // registry with the level it shows.
    val contentSig: Signal[Seq[ReactiveElement.Base]] =
      Signal.combine(geomSig, showLayersVar.signal, pathSig).map {
        case (Some(g), showLayers, path) => renderContent(g, showLayers, path)
        // No content: drop the previous view's elements from the highlight.
        case _                           => hl.install(Highlight.Registry.empty); Nil
      }

    // The minimap's viewport rect inputs: pan, zoom and the viewport size.
    val minimapViewSig: Signal[((Double, Double), Double, (Double, Double))] =
      Signal.combine(panVar.signal, zoomVar.signal, viewSizeVar.signal)

    // Recenter the main view on a content-space point (used by the minimap).
    def centerOnContent(cx: Double, cy: Double): Unit = {
      userView()
      val (vw, vh) = viewSizeVar.now(); val z = zoomVar.now()
      panVar.set((vw / 2 - cx * z, vh / 2 - cy * z))
    }

    // Wiring for the bottom-right controls (#216): read-only signals + action
    // callbacks. The export/share actions keep their content-node + Export
    // wiring here in render scope; [[SchematicChrome.controls]] only invokes them.
    val ctrlCtx = SchematicChrome.ControlsCtx(
      pendingSig    = pendingVar.signal,
      engineSig     = engineVar.signal.map(_.map(_._3)),
      layerHasSig   = geomSig.map(_.exists(_.mod.logic.exists(_.layer))),
      showLayersVar = showLayersVar,
      zoomSig       = zoomVar.signal,
      onOptimize    = () => optNonceVar.update(_ + 1),
      onFit         = () => fitNow(),
      zoomBy        = (f: Double) => zoomBy(f),
      onResetView   = () => { userView(); Var.set(zoomVar -> 1.0, panVar -> (40.0, 40.0)) },
      onExportSvg   = () => if (contentNode != null) withCleared(() => Export.exportSvg(contentNode, expW, expH, curModName)),
      onExportPng   = () => if (contentNode != null) withCleared(() => Export.exportPng(contentNode, expW, expH, curModName)),
      onShare       = Option.when(showShare)(() => designSnapshot().foreach { case (raw, top) => Export.snapshot(raw, top) }),
    )

    // ── pane root ──
    div(
      dataAttr("pane") := "schematic",
      dataAttr("layout-ready") <-- pendingVar.signal.map(p => if (p) "0" else "1"),
      cls := "scl-pane scl-pane-schematic",
      styleAttr := "flex:1;position:relative;overflow:hidden",
      styleTag(Highlight.css),
      hl.bind(filterDimSig, focusClassSig),
      SchematicChrome.chrome(moduleSig, filterVar, matchSig),
      div(
        // Focusable (not by Tab), so the navigation keys act after a click here.
        tabIndex := -1,
        styleAttr := "position:absolute;inset:0;overflow:hidden;cursor:grab;touch-action:none;outline:none",
        onMountUnmountCallback(
          mount = { ctx =>
            viewportNode = ctx.thisNode.ref
            Var.set(totalWVar -> 1.0, canvasHVar -> 1.0)
            // The viewport size (fit, zoom buttons, minimap), whatever changes it
            // (window, side panels, bottom panel). Reported after layout, so no
            // forced reflow; the first report arrives before the first paint.
            viewObserver = new dom.ResizeObserver((entries, _) => entries.lastOption.foreach { en =>
              viewSizeVar.set((en.contentRect.width, en.contentRect.height))
            })
            viewObserver.observe(viewportNode)
            // Smoke test hook: the highlight's indexed matching against a full
            // scan on the view shown ({keys, bad}).
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessHighlightCheck")((() => {
              val (n, bad) = hl.selfCheck()
              js.Dynamic.literal(keys = n, bad = js.Array(bad: _*))
            }): js.Function0[js.Dynamic])
            // Smoke test hook: the wheel classification on raw values.
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessWheelClassify")(
              ((dx: Double, dy: Double, mode: Int, ctrl: Boolean, shift: Boolean) =>
                Gestures.classifyDebug(dx, dy, mode, ctrl, shift)): js.Function5[Double, Double, Int, Boolean, Boolean, js.Dynamic])
            // Drag to pan from anywhere, the wheel, the navigation keys.
            gestures = new Gestures.Controller(viewportNode, Gestures.View(
              panVar         = panVar,
              zoomVar        = zoomVar,
              viewSize       = () => viewSizeVar.now(),
              clamp          = clamp,
              userView       = () => userView(),
              zoomBy         = zoomBy,
              fit            = () => fitNow(),
              // A click on the background also stops following a net.
              clearSelection = () => { selectedVar.set(None); hl.follow(None) },
              clearFollow    = () => hl.follow(None),
              keysBlocked    = () => fsmOpenVar.now().isDefined || keysBlocked(),
              back           = () => walk(_.back),
              forward        = () => walk(_.forward),
              zoomToSelection = () => zoomToSelection(),
            ), hl)
            gesturesOff = gestures.install()
            tooltipOff = tooltip.install(viewportNode)
            hintOff = hintBar.fold(() => ())(_.install(viewportNode, hl))
            // Smoke test hooks: the view history ({size, index, entries}), the
            // stack model on scripted pushes, whether a flight is running.
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessHistory")((() =>
              js.Dynamic.literal(size = hist.entries.size, index = hist.index,
                entries = js.Array(hist.entries.map(e =>
                  js.Dynamic.literal(path = e.path.mkString("/"), x = e.pan._1, y = e.pan._2, z = e.zoom)): _*))
            ): js.Function0[js.Dynamic])
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessHistoryModel")(
              ((ops: js.Array[String]) => ViewHistory.modelDebug(ops)): js.Function1[js.Array[String], js.Dynamic])
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessFlying")((() => flyer.running): js.Function0[Boolean])
            // Smoke test hook: the tooltip card of every box, wire and pin of the view ({n, bad}).
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessTooltipCheck")((() => {
              val (n, bad) = Tooltip.selfCheck(hl.geom)
              js.Dynamic.literal(n = n, bad = js.Array(bad: _*))
            }): js.Function0[js.Dynamic])
            // Smoke test hooks: NetFollow.resolve round trips through every
            // instance port of the view ({n, bad}); the followed net and what
            // it lights here.
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessFollowCheck")((() => {
              val (n, bad) = Option(hl.geom).fold((0, Seq.empty[String]))(g =>
                NetFollow.selfCheck(g.design, hl.path, g.wires.map(w => (w.fromPath, w.toPath))))
              js.Dynamic.literal(n = n, bad = js.Array(bad: _*))
            }): js.Function0[js.Dynamic])
            dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessFollow")((() => {
              val l = hl.followedLit
              js.Dynamic.literal(
                net = hl.followed.fold[js.Any](null)(n => js.Dynamic.literal(origin = n.origin.mkString("/"), from = n.from, to = n.to)),
                path = hl.path.mkString("/"),
                wires = js.Array(l.wires.toSeq.map { case (f, t) => js.Array(f, t) }: _*),
                ends = js.Array(l.ends.toSeq: _*))
            }): js.Function0[js.Dynamic])
          },
          unmount = { _ =>
            if (viewObserver != null) viewObserver.disconnect()
            viewObserver = null
            gesturesOff(); gesturesOff = () => (); gestures = null
            tooltipOff(); tooltipOff = () => ()
            hintOff(); hintOff = () => ()
            cancelFit(); flyer.cancel(); cancelSettle()
          }),
        div(styleAttr <-- transformSig, children <-- contentSig,
          dataAttr("content") := "1",   // test hook: the pan/zoom-transformed content layer
          // View-wide highlight state the rules of Highlight.css key on; on this
          // element so an export's clone carries it.
          cls("hl-sel") <-- selectedVar.signal.map(_.isDefined),
          cls("hl-cw") <-- colorWiresVar.signal,
          cls("hl-cb") <-- colorBoxesVar.signal,
          onMountCallback { ctx => contentNode = ctx.thisNode.ref }),
        // empty / missing notices
        child.maybe <-- Signal.combine(geomSig, designSig).map {
          case (Some(g), _) if g.layout.nodes.isEmpty && g.mod.children.isEmpty =>
            Some(div(styleAttr := "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none",
              span(dataAttr("empty-module") := "1", styleAttr := s"font-family:${Tokens.mono};font-size:12px;color:${Tokens.ink4}",
                "This module has no instances, ports, or wires to display.")))
          case _ => None
        },
        // Shown over the content while panning (Gestures): the grabbing cursor
        // and no text selection wherever the pointer goes, without restyling
        // the content's elements.
        div(dataAttr("pan-overlay") := "1",
          styleAttr := "position:absolute;inset:0;cursor:grabbing;user-select:none;-webkit-user-select:none;display:none"),
      ),
      SchematicChrome.controls(ctrlCtx),
      tooltip.el,
      // Non-blocking "laying out…" badge while the in-browser engine runs, so a
      // re-layout reads as progress instead of a freeze (perf feedback). Click-
      // through (pointer-events:none); the last-good schematic stays interactive.
      child.maybe <-- pendingVar.signal.map(p => if (p) Some(
        div(dataAttr("layout-spinner") := "1",
          styleAttr := s"position:absolute;top:12px;left:50%;transform:translateX(-50%);z-index:4;display:flex;align-items:center;gap:6px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:4px 10px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink3};pointer-events:none;box-shadow:0 1px 5px rgba(0,0,0,.14)",
          span(cls := "scl-spinner"), span("laying out…"))
      ) else None),
      // The open FSM block's state diagram, over the schematic (which stays
      // mounted, keeping its view). A new element per diagram, so a live reload
      // that changes the diagram lays it out again.
      child.maybe <-- Signal.combine(moduleSig, fsmOpenVar.signal).map {
        case (Some(mod), Some(id)) => mod.logic.find(_.id == id).flatMap(n => n.fsm.map(id -> _))
        case _                     => None
      }.distinct.map(_.map { case (id, fsm) => FsmDiagramView.render(id, fsm, openSource) }),
      // Drop the memoized engine layouts when the design itself changes (live
      // reload / picker switch): a reused module name can describe new internals.
      // The history and the followed net stay across a live reload (Back skips
      // entries that no longer resolve, a vanished net lights nothing); a fresh
      // load clears them (`history.reset`, from App).
      designSig.changes --> { _ => engineCache.clear() },
      // Minimap, shown only when content overflows the viewport at this zoom.
      // Its node rects are built once per geometry (the element is rebuilt only
      // when the geometry changes or it shows or hides); pan and zoom move just
      // its viewport rect, through `minimapViewSig`. Hidden until the module's
      // view is in place (`settledPathVar`).
      child.maybe <-- Signal.combine(geomSig, zoomVar.signal, viewSizeVar.signal, pathSig.map(_.mkString("/")), settledPathVar.signal).map {
        case (Some(g), z, (vw, vh), pk, settled)
          if settled.contains(pk) && g.layout.nodes.nonEmpty && vw > 0 && (g.layout.w * z > vw + 1 || g.canvasH * z > vh + 1) => Some(g)
        case _ => None
      }.distinctByFn {
        case (Some(a), Some(b)) => a eq b
        case (a, b)             => a.isEmpty && b.isEmpty
      }.map(_.map(g => SchematicChrome.minimap(g, minimapViewSig, centerOnContent))),
      // keep totalW/canvasH mirrors + current module name in sync with geometry.
      // Both dims are set in ONE transaction: two separate sets made the armed fit
      // below run on the new width paired with the previous (placeholder) height.
      geomSig --> { (gO: Option[Geom]) => gO.foreach { g => Var.set(totalWVar -> g.layout.w.toDouble, canvasHVar -> g.canvasH); curModName = g.mod.name } },
      // Clear the filter on navigation (JS clears it per path change).
      // A drag in progress ends there too, so a stale drag cannot undo the new
      // module's fit.
      // The tooltip and the box outline go too (the box is gone).
      pathSig.map(_.mkString("/")).changes --> { _ =>
        filterVar.set(""); if (gestures != null) gestures.cancel(); tooltip.hide(); hl.clearBox()
      },
      // A rebuild replaces what the card describes; a pan or zoom moves it away
      // from the pointer.
      geomSig.changes --> { _ => tooltip.hide() },
      // A user's pan or zoom becomes a history entry once it rests.
      Signal.combine(panVar.signal, zoomVar.signal).changes --> { _ => tooltip.hide(); if (dirty) armSettle() },
      // Track the current module path; arm a fit/restore only on a real path
      // change. Stash the view of the module being left so returning restores it.
      // Bundle expansion grows the box (changes totalW/canvasH) but does NOT
      // change the path, so it must not re-fit the view (issue #207).
      pathSig --> { p =>
        val pk = p.mkString("/")
        if (prevPathKey != null && prevPathKey != pk) {
          flyer.cancel()
          // While a fit is still pending, the view shown is not that module's yet
          // (the fit runs a frame later): do not save it as that module's view.
          if (!pendingFit) {
            commitDirty() // the view left, before the new module's entry
            viewState(prevPathKey) = (zoomVar.now(), panVar.now())
          }
          cancelSettle(); dirty = false
        }
        if (prevPathKey != pk) {
          pendingFit = true
          if (flyPending.exists(_._1 != pk)) flyPending = None
        }
        prevPathKey = pk; curPath = p
      },
      // A fly to a box waits for its module's view and layout.
      Signal.combine(settledPathVar.signal, pendingVar.signal) --> { _ => tryFly() },
      // Perform the armed fit/restore once the geometry dimensions are known,
      // then disarm. Later dimension changes (expansion, Optimize, the engine
      // relayout landing) refit only while the view is still the automatic fit;
      // once the user pans or zooms, their view is left untouched.
      // The fit itself runs in the next animation frame (scheduleFit); a pending
      // one also waits for the first viewport size report.
      Signal.combine(totalWVar.signal, canvasHVar.signal) --> { (t: (Double, Double)) =>
        val (tw, ch) = t
        if (viewportNode != null && tw > 0 && ch > 0 && (pendingFit || autoFitted)) scheduleFit()
      },
      // A size report comes after layout and before paint: run a pending fit
      // right away, so the first view never paints unfitted for a frame.
      viewSizeVar.signal.changes --> { _ => if (pendingFit) { cancelFit(); runFit() } },
      // in-process engine re-layout on module / expansion / Optimize / declutter /
      // auto-optimize. When auto-optimize (#209) is on, every view runs the
      // optimize pass so the initial render matches Optimize (off-thread).
      Signal.combine(designSig, moduleSig, expandedSig, optNonceVar.signal, hiddenKindsVar.signal, autoOptimizeVar.signal) --> {
        (t: (Option[Design], Option[ModuleDef], Set[String], Int, Set[String], Boolean)) =>
          val manualOpt = t._4 != lastNonce
          lastNonce = t._4
          val eff = t._3 ++ (for { d <- t._1; m <- t._2 } yield lockedExpansions(d, m)).getOrElse(Set.empty)
          runEngine(t._1, t._2, eff, manualOpt || t._6, t._5)
      },
    )
  }
}
