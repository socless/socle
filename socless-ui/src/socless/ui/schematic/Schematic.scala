package socless.ui.schematic

import socless.ui.theme._
import socless.ui.model._
import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.{ReactiveElement, ChildNode}
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Success, Failure}
import socless.schema.{Design, ModuleDef, NodeKind, PinSide, ModuleKind}
import socless.js.BrowserLayout
import socless.js.BrowserLayout.{LaidOut, LaidNodeR, BoxOverride}

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
    expandedPortsVar: Var[Set[String]],
    drillInto: String => Unit,
    designSnapshot: () => Option[(String, String)] = () => None, // (rawJson, top) for the share button
  ): Element = {

    // Schematic-internal state (persists for the life of the pane).
    val zoomVar     = Var(1.0)
    val panVar      = Var((40.0, 40.0))
    // The in-process engine (elkjs) layout, TAGGED with the module name it was
    // computed for. The tag is essential: `geomSig` and the re-layout trigger
    // both fire on a module change, and an engine result for the PREVIOUS module
    // must never be used to place the new module's wires (it would leave every
    // wire unanchored until the async relayout lands, issue #258 follow-up).
    val engineVar   = Var[Option[(String, LaidOut)]](None)
    val pendingVar  = Var(false)
    val optNonceVar = Var(0)
    val showLayersVar = Var(false) // verification layers hidden by default (issue #212)
    val totalWVar   = Var(1.0)
    val canvasHVar  = Var(1.0)
    val filterVar   = Var("")              // schematic filter query (issue #121)
    val viewSizeVar = Var((0.0, 0.0))      // viewport client size (for the minimap)

    val viewState = scala.collection.mutable.Map.empty[String, (Double, (Double, Double))]
    var prevPathKey: String = null
    var viewportNode: dom.html.Element = null
    var contentNode: dom.html.Element = null   // the transformed content div, for export
    var curModName = ""                        // current module name, for export filenames
    var engineGen = 0
    var lastNonce = 0
    var pendingFit = true   // armed on a real path change; performed once dims are known (issue #207)

    def clamp(z: Double): Double = math.max(0.08, math.min(4.0, z))
    // Interactive state the box/port renderers in [[SchematicBoxes]] need (#216).
    val boxCtx = SchematicBoxes.Ctx(selectedVar, expandedPortsVar)

    // ── geometry signal ──
    def computeGeom(d: Design, mod: ModuleDef, engine: Option[LaidOut], expanded: Set[String]): Geom = {
      val c       = Tokens.consts(d)
      val layout  = engine.orElse(bakedOf(d, mod)).getOrElse(LaidOut(Nil, Nil, 1, 1))
      val rows    = RowModel.build(layout, mod, d, expanded, c.headerH, c.portPad, c.portStep)
      val pinYMap = rows.map { case (i, br) => i -> br.pinY(c.headerH, c.portPad, c.portStep) }
      val boxById = layout.nodes.map(n => n.id -> n).toMap
      val shift   = computeBoxShift(layout, rows)
      val wires   = buildWires(displayWires(mod, expanded), layout, boxById, pinYMap, shift)
      val canvasH = (layout.h + 40) + shift.total
      Geom(layout, mod, d, rows, wires, shift, canvasH, c, boxById)
    }

    // Paths that are expanded unconditionally: scattered-bundle groups and any
    // locked sub-group headers. Derived from the baked rows so it matches RowModel.
    // The header/spacing constants are irrelevant to which paths are locked, so 0 is fine.
    def lockedExpansions(d: Design, mod: ModuleDef): Set[String] = {
      val baked = bakedOf(d, mod).getOrElse(LaidOut(Nil, Nil, 1, 1))
      RowModel.build(baked, mod, d, Set.empty, 0, 0, 0)
        .values.flatMap(br => (br.westRows ++ br.eastRows).filter(_.locked).map(_.path)).toSet
    }

    val geomSig: Signal[Option[Geom]] =
      Signal.combine(designSig, moduleSig, engineVar.signal, expandedPortsVar.signal).map {
        case (Some(d), Some(mod), engine, expanded) =>
          // Use the engine layout only when it was computed for THIS module;
          // otherwise fall back to the baked layout for the current module so a
          // stale engine result never mis-anchors the wires.
          val forThis = engine.collect { case (m, l) if m == mod.name => l }
          Some(computeGeom(d, mod, forThis, expanded ++ lockedExpansions(d, mod)))
        case _                                      => None
      }

    // ── in-process engine re-layout (issue #167) ──
    def runEngine(dO: Option[Design], mO: Option[ModuleDef], expanded: Set[String], optimize: Boolean): Unit =
      (dO, mO) match {
        case (Some(d), Some(mod)) =>
          val hasExpansion = expanded.nonEmpty
          if (!hasExpansion && !optimize) { engineVar.set(None); return }
          bakedOf(d, mod) match {
            case None => engineVar.set(None)
            case Some(bl) =>
              val c    = Tokens.consts(d)
              val rows = RowModel.build(bl, mod, d, expanded, c.headerH, c.portPad, c.portStep)
              val overrides = rows.flatMap { case (inst, br) =>
                val baseH = bl.nodes.find(_.id == inst).map(_.h.toDouble).getOrElse(0.0)
                if (br.boxH > baseH)
                  Some(inst -> BoxOverride(Some(br.boxH), br.pinY(c.headerH, c.portPad, c.portStep)))
                else None
              }
              engineGen += 1
              val myGen = engineGen
              pendingVar.set(true)
              BrowserLayout.runTyped(mod, d, if (optimize) "optimize" else "reroute", overrides, expanded).onComplete {
                case Success(res) => if (myGen == engineGen) { engineVar.set(Some((mod.name, res))); pendingVar.set(false) }
                case Failure(e)   => if (myGen == engineGen) {
                  engineVar.set(None); pendingVar.set(false)
                  dom.console.warn("[socless] in-browser layout failed, using baked: " + e.getMessage)
                }
              }
          }
        case _ => engineVar.set(None)
      }

    // ── view fit / pan / zoom ──
    def doFit(): Unit = {
      if (viewportNode == null) return
      val tw = totalWVar.now(); val ch = canvasHVar.now()
      val w = viewportNode.clientWidth.toDouble; val h = viewportNode.clientHeight.toDouble
      if (tw <= 0 || ch <= 0 || w <= 0 || h <= 0) return
      val margin = 40.0
      val z = math.min(1.0, math.min((w - 2 * margin) / tw, (h - 2 * margin) / ch))
      zoomVar.set(z); panVar.set(((w - tw * z) / 2, (h - ch * z) / 2))
    }


    def zoomBy(factor: Double): Unit = {
      val z = zoomVar.now(); val newZ = clamp(z * factor)
      if (viewportNode != null) {
        val cx = viewportNode.clientWidth / 2.0; val cy = viewportNode.clientHeight / 2.0
        val ratio = newZ / z; val (px, py) = panVar.now()
        panVar.set((cx - (cx - px) * ratio, cy - (cy - py) * ratio))
      }
      zoomVar.set(newZ)
    }

    val handlePanStart: dom.MouseEvent => Unit = (e) => {
      val isMiddle = e.button == 1
      val tgt = e.target.asInstanceOf[dom.Element]
      val isLeftOnBg = e.button == 0 && (tgt == null || tgt.closest("[data-pan-block]") == null)
      if (isMiddle || isLeftOnBg) {
        e.preventDefault()
        val (px, py) = panVar.now(); val sx = e.clientX; val sy = e.clientY
        var moved = false
        lazy val moveL: js.Function1[dom.MouseEvent, Unit] = (ev) => {
          if (math.abs(ev.clientX - sx) + math.abs(ev.clientY - sy) > 3) moved = true
          panVar.set((px + (ev.clientX - sx), py + (ev.clientY - sy)))
        }
        lazy val upL: js.Function1[dom.MouseEvent, Unit] = (_) => {
          dom.window.removeEventListener("mousemove", moveL)
          dom.window.removeEventListener("mouseup", upL)
          // A left-click on empty canvas (no drag) clears the selection, so a
          // selected wire/component can be unselected (issue #203 follow-up).
          if (!moved && isLeftOnBg) selectedVar.set(None)
        }
        dom.window.addEventListener("mousemove", moveL)
        dom.window.addEventListener("mouseup", upL)
      }
    }

    // ── content (boxes + wires), rebuilt on geom / selection / hover ──
    def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

    def renderContent(g: Geom, sel: Option[String], hov: Option[String], filter: String, showLayers: Boolean): Seq[ReactiveElement.Base] = {
      // Verification-layer nodes (issue #212) and their wires are hidden unless
      // "show layers" is on. layerIds empty for a design with no layer content.
      val layerIds: Set[String] = g.mod.logic.iterator.filter(_.layer).map(_.id).toSet
      val hideLayer = !showLayers && layerIds.nonEmpty
      val visWires = if (hideLayer) g.wires.filterNot(w => layerIds.contains(w.fromInst) || layerIds.contains(w.toInst)) else g.wires
      val c = g.c
      val selInst  = sel.map(s => ownerBoxId(g.boxById, s)).getOrElse("")
      val selClock = g.mod.children.find(_.name == selInst).map(_.clock).getOrElse("")

      // Filter dimming (issue #121): fuzzy-match boxes on inst+type; matches AND
      // their direct wire neighbors stay lit, everything else dims. None = no filter.
      val filterActive: Option[Set[String]] = {
        val q = filter.trim.toLowerCase
        if (q.isEmpty) None
        else {
          val matched = g.layout.nodes.iterator.map(_.id).filter { inst =>
            val hay = (inst + " " + g.mod.children.find(_.name == inst).map(_.`type`).getOrElse("")).toLowerCase
            fuzzyHas(hay, q)
          }.toSet
          val act = scala.collection.mutable.Set.empty[String] ++= matched
          g.wires.foreach { w =>
            val a = ownerBoxId(g.boxById, w.fromPath); val b = ownerBoxId(g.boxById, w.toPath)
            if (matched(a)) act += b
            if (matched(b)) act += a
          }
          Some(act.toSet)
        }
      }
      def boxActive(inst: String): Boolean = filterActive.forall(_.contains(inst))
      def wireActive(w: Wire): Boolean =
        filterActive.forall(s => s.contains(ownerBoxId(g.boxById, w.fromPath)) || s.contains(ownerBoxId(g.boxById, w.toPath)))

      // Wire highlight predicates (JS WireVisualLayer).
      def isHl(w: Wire): Boolean = sel.exists(s =>
        w.fromPath.startsWith(s) || w.toPath.startsWith(s) || s.startsWith(w.fromPath) || s.startsWith(w.toPath))
      def isHov(w: Wire): Boolean = hov.exists(s => w.fromPath.startsWith(s) || w.toPath.startsWith(s))

      // ── hit layer (transparent fat paths, captures clicks) ──
      val hit = svg.svg(
        sa("style") := s"position:absolute;top:0;left:0;width:${g.layout.w}px;height:${g.canvasH}px;overflow:visible",
        visWires.map { w =>
          svg.path(
            sa("d") := routePath(w.points),
            sa("data-wire") := w.fromPath,
            sa("data-pan-block") := "1",
            sa("style") := "stroke:transparent;stroke-width:14;fill:none;cursor:pointer;pointer-events:stroke",
            onClick --> { (_: dom.MouseEvent) => toggleSelect(selectedVar, w.fromPath) },
            onMouseEnter --> { (_: dom.MouseEvent) => hoveredVar.set(Some(w.fromPath)) },
            onMouseLeave --> { (_: dom.MouseEvent) => hoveredVar.set(None) },
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
        g.layout.nodes.iterator.flatMap(b => b.ports.find(_.path == sinkPath).map(p => (b, p))).toSeq.headOption.map { case (b, p) =>
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
          } else {
            val isWest = p.side == PinSide.West
            val px     = p.x.toDouble
            val len    = 10.0
            // Stub: a short wire out of the port ending in a perpendicular bar (|-).
            val d = if (isWest) s"M $px $py L ${px - len} $py M ${px - len} ${py - 5} L ${px - len} ${py + 5}"
                    else        s"M $px $py L ${px + len} $py M ${px + len} ${py - 5} L ${px + len} ${py + 5}"
            val labelStyle =
              if (isWest) s"position:absolute;left:${px - len - 4}px;top:${py - 7}px;transform:translateX(-100%);text-align:right"
              else        s"position:absolute;left:${px + len + 4}px;top:${py - 7}px;text-align:left"
            div(
              dataAttr("const-stub") := cn.id, dataAttr("pan-block") := "1",
              title := s"constant $value",
              svg.svg(sa("style") := "position:absolute;left:0;top:0;width:0;height:0;overflow:visible;pointer-events:none",
                svg.path(sa("d") := d, sa("style") := s"stroke:${Tokens.ink3};stroke-width:1.2;fill:none")),
              span(styleAttr := s"$labelStyle;$lblBase;background:${Tokens.panel};padding:0 3px;border-radius:2px", value),
            )
          }
        }
      }.toSeq

      // ── boxes ──
      val boxEls: Seq[ReactiveElement.Base] = g.layout.nodes.filterNot(n => n.kind == NodeKind.Const || (hideLayer && layerIds.contains(n.id))).map { n =>
        val child = g.mod.children.find(_.name == n.id)
        val shift = g.boxShift.shift.getOrElse(n.id, 0.0)
        val top   = n.y + shift
        if (n.kind == NodeKind.Reg) SchematicBoxes.regBox(boxCtx, g, n, top, boxActive(n.id), sel, hov)
        // Op nodes have no `children` entry, so (like reg) they must be
        // intercepted before the childless-boundary arm below (issue #188 B2).
        else if (n.kind == NodeKind.Op) SchematicBoxes.opBox(boxCtx, g, n, top, boxActive(n.id), sel, hov)
        // A collapsed FSM (issue #210): an instance-style box with boundary pins,
        // but non-drillable and tinted to read as a state machine. Intercepted
        // before the childless-boundary arm since it has no `children` entry.
        else if (n.kind == NodeKind.Fsm) SchematicBoxes.fsmBox(boxCtx, g, n, top, boxActive(n.id), sel, hov)
        // Any childless node (a boundary stub, or any non-instance kind without a
        // child entry) renders as a stub; only a node with a child uses the
        // instance box below (which needs the child for its type/drilldown).
        else if (child.isEmpty || n.kind == NodeKind.Boundary) SchematicBoxes.boundaryStub(boxCtx, g, n, top, boxActive(n.id))
        else {
          val ch         = child.get
          val childMod   = g.design.modules.find(_.name == ch.`type`)
          val isBlackbox = childMod.exists(m => m.kind == ModuleKind.External || m.kind == ModuleKind.Intrinsic)
          val extLabel: String = childMod.map(m => if (m.kind == ModuleKind.Intrinsic) "intrinsic" else "ext").getOrElse("ext")
          val drillable  = childMod.exists(m => !isBlackbox && m.children.nonEmpty)
          // Clock/reset are filtered from the wire graph (no laid pin, see
          // displayWires), so a clocked instance carries a compact corner cue
          // instead of a routed clock tree: a clock-input notch bottom-left and a
          // reset ring bottom-right (issue #188 follow-up).
          val hasClock = childMod.exists(_.ports.exists(_.bundle == "Clock"))
          val hasReset = childMod.exists(_.ports.exists(p => p.bundle == "Reset" || p.bundle == "AsyncReset"))
          val selectedHere = sel.exists(s => s == n.id || s.startsWith(n.id + "."))
          val isClockPeer  = selClock.nonEmpty && n.id != selInst && child.exists(_.clock == selClock)
          val boxInfo    = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
          val borderCol  = if (selectedHere) Tokens.accent else if (isClockPeer) Tokens.accentLine else Tokens.border
          val boxShadow  = if (selectedHere) s"0 0 0 3px ${Tokens.accentBg}"
                           else if (isClockPeer) s"0 0 0 2px ${Tokens.accentLine}" else "0 1px 2px rgba(0,0,0,.04)"
          div(
            dataAttr("box-inst") := n.id,
            dataAttr("box-drillable") := (if (drillable) "1" else "0"),
            dataAttr("pan-block") := "1",
            styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
              s"opacity:${if (boxActive(n.id)) "1" else "0.18"};" +
              s"background:${Tokens.panel};border:1px ${if (isBlackbox) "dashed" else "solid"} $borderCol;border-radius:6px;" +
              s"box-shadow:$boxShadow;cursor:${if (drillable) "pointer" else "default"};overflow:visible;" +
              "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
            onDblClick --> { (_: dom.MouseEvent) => if (drillable) drillInto(n.id) },
            // header
            div(
              styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px ${if (isBlackbox) "dashed" else "solid"} ${Tokens.borderSoft};" +
                s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:pointer",
              onClick --> { (_: dom.MouseEvent) => toggleSelect(selectedVar, n.id) },
              span(styleAttr := s"width:12px;display:inline-flex;justify-content:center;color:${if (selectedHere) Tokens.accent else Tokens.ink3};font-size:10px", Glyphs.of(g.design, ch.`type`)),
              span(styleAttr := s"font-family:${Tokens.mono};font-size:11.5px;font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", n.id),
              div(styleAttr := "margin-left:auto;flex-shrink:0;display:flex;align-items:center;gap:6px",
                opt(isBlackbox)(span(styleAttr := s"font-family:${Tokens.mono};font-size:8px;font-weight:600;letter-spacing:.4px;text-transform:uppercase;color:${Tokens.ink3};background:${Tokens.panel};border:1px dashed ${Tokens.border};border-radius:3px;padding:0 4px;line-height:13px",
                  extLabel)),
                span(styleAttr := s"font-family:${Tokens.mono};font-size:10px;color:${Tokens.ink3};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:80px", ch.`type`),
                opt(drillable)(span(styleAttr := s"font-family:${Tokens.mono};font-size:9px;color:${Tokens.accent}", "↵")),
              ),
            ),
            // port rows
            SchematicBoxes.renderPortRows(boxCtx, g, n, boxInfo.westRows, PinSide.West, sel, hov),
            SchematicBoxes.renderPortRows(boxCtx, g, n, boxInfo.eastRows, PinSide.East, sel, hov),
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
          val hl  = isHl(w)
          val hov2 = isHov(w) && !hl
          val dim = sel.isDefined && !hl
          val stroke = if (dim) Tokens.ink4 else if (hl) Tokens.accent else if (hov2) Tokens.ink else Tokens.ink2
          val isField = fieldLevelWires.contains((w.fromPath, w.toPath))
          val sw     = if (isField) 1.0 else if (hl) 4 else if (hov2) 3.5 else 3
          val halo   = if (hl) Tokens.accent else if (hov2) Tokens.ink else Tokens.ink2
          val haloOp = if (hl) 0.18 else if (hov2) 0.10 else 0.07
          val groupOp = if (!wireActive(w)) 0.12 else if (dim) 0.32 else 1.0
          val d = routePath(w.points)
          // Dangling wires (no routed ELK edge, issue #188 follow-up 2a) render
          // dashed with a small open circle at the unresolved (sink) end, so an
          // unrouted connection reads as visibly incomplete rather than absent.
          val dashStyle = if (w.dangling) ";stroke-dasharray:5 4" else ""
          svg.g(
            sa("style") := s"opacity:$groupOp;transition:opacity .15s",
            svg.path(sa("d") := d, sa("style") := s"stroke:$halo;stroke-width:${sw + 6};fill:none;stroke-linecap:round;stroke-linejoin:round;opacity:$haloOp"),
            svg.path(sa("d") := d, sa("style") := s"stroke:$stroke;stroke-width:$sw;fill:none;stroke-linecap:round;stroke-linejoin:round$dashStyle"),
            if (w.dangling)
              svg.circle(sa("cx") := w.x2.toString, sa("cy") := w.y2.toString, sa("r") := "3.5",
                sa("style") := s"fill:${Tokens.bg};stroke:$stroke;stroke-width:1.5",
                svg.titleTag(s"unrouted wire: ${w.fromPath} ↔ ${w.toPath}"))
            else if (w.bidir) SchematicBoxes.bidirGlyph(w.points, stroke) else emptyNode,
          )
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

      // Constant annotations render after the wire layer so their labels sit on
      // top of crossing wires instead of being covered (#229).
      ((Seq[ReactiveElement.Base](hit) ++ boxEls) :+ (vis: ReactiveElement.Base)) ++ constEls
    }


    // Run `capture` with selection/hover highlight cleared, then restore it, so a
    // selected net doesn't export a washed-out one-net view (issue #131). Laminar
    // applies the Var change synchronously, so the cloned DOM is already cleared.
    def withCleared(capture: () => Unit): Unit = {
      val ps = selectedVar.now(); val ph = hoveredVar.now()
      if (ps.isEmpty && ph.isEmpty) capture()
      else { selectedVar.set(None); hoveredVar.set(None); capture(); selectedVar.set(ps); hoveredVar.set(ph) }
    }
    def expW: Int = math.max(1, math.ceil(totalWVar.now()).toInt)
    def expH: Int = math.max(1, math.ceil(canvasHVar.now()).toInt)


    val transformSig = Signal.combine(panVar.signal, zoomVar.signal, totalWVar.signal, canvasHVar.signal).map {
      case ((px, py), z, w, h) =>
        s"position:absolute;left:0;top:0;width:${w}px;height:${h}px;transform:translate(${px}px,${py}px) scale($z);transform-origin:0 0;transition:width .3s,height .3s"
    }

    val contentSig: Signal[Seq[ReactiveElement.Base]] =
      Signal.combine(geomSig, selectedVar.signal, hoveredVar.signal, filterVar.signal, showLayersVar.signal).map {
        case (Some(g), sel, hov, filter, showLayers) => renderContent(g, sel, hov, filter, showLayers)
        case _                                       => Nil
      }

    // Recenter the main view on a content-space point (used by the minimap).
    def centerOnContent(cx: Double, cy: Double): Unit = {
      val (vw, vh) = viewSizeVar.now(); val z = zoomVar.now()
      panVar.set((vw / 2 - cx * z, vh / 2 - cy * z))
    }

    // Wiring for the bottom-right controls (#216): read-only signals + action
    // callbacks. The export/share actions keep their content-node + Export
    // wiring here in render scope; [[SchematicChrome.controls]] only invokes them.
    val ctrlCtx = SchematicChrome.ControlsCtx(
      pendingSig    = pendingVar.signal,
      engineSig     = engineVar.signal.map(_.map(_._2)),
      layerHasSig   = geomSig.map(_.exists(_.mod.logic.exists(_.layer))),
      showLayersVar = showLayersVar,
      zoomSig       = zoomVar.signal,
      onOptimize    = () => optNonceVar.update(_ + 1),
      onFit         = () => doFit(),
      zoomBy        = (f: Double) => zoomBy(f),
      onResetView   = () => { zoomVar.set(1.0); panVar.set((40.0, 40.0)) },
      onExportSvg   = () => if (contentNode != null) withCleared(() => Export.exportSvg(contentNode, expW, expH, curModName)),
      onExportPng   = () => if (contentNode != null) withCleared(() => Export.exportPng(contentNode, expW, expH, curModName)),
      onShare       = () => designSnapshot().foreach { case (raw, top) => Export.snapshot(raw, top) },
    )

    // ── pane root ──
    div(
      dataAttr("pane") := "schematic",
      dataAttr("layout-ready") <-- pendingVar.signal.map(p => if (p) "0" else "1"),
      cls := "scl-pane scl-pane-schematic",
      styleAttr := "flex:1;position:relative;overflow:hidden",
      SchematicChrome.chrome(moduleSig, filterVar),
      div(
        styleAttr := "position:absolute;inset:0;overflow:hidden;cursor:grab;touch-action:none",
        onMouseDown --> handlePanStart,
        onMountCallback { ctx =>
          viewportNode = ctx.thisNode.ref
          totalWVar.set(1.0); canvasHVar.set(1.0)
          // Track viewport size for the minimap (initial + on window resize).
          def measure(): Unit = viewSizeVar.set((viewportNode.clientWidth.toDouble, viewportNode.clientHeight.toDouble))
          measure()
          dom.window.addEventListener("resize", (_: dom.Event) => measure())
          // wheel-to-zoom, non-passive so preventDefault works
          val onWheel: js.Function1[dom.WheelEvent, Unit] = (e) => {
            e.preventDefault()
            val rect = viewportNode.getBoundingClientRect()
            val mx = e.clientX - rect.left; val my = e.clientY - rect.top
            val z = zoomVar.now(); val newZ = clamp(z * math.exp(-e.deltaY * 0.0015))
            val ratio = newZ / z; val (px, py) = panVar.now()
            panVar.set((mx - (mx - px) * ratio, my - (my - py) * ratio))
            zoomVar.set(newZ)
          }
          // Register non-passively (via js.Dynamic to pass {passive:false}) so
          // preventDefault stops the page scrolling under the schematic.
          viewportNode.asInstanceOf[js.Dynamic].addEventListener("wheel", onWheel, js.Dynamic.literal(passive = false))
        },
        div(styleAttr <-- transformSig, children <-- contentSig,
          dataAttr("content") := "1",   // test hook: the pan/zoom-transformed content layer
          onMountCallback { ctx => contentNode = ctx.thisNode.ref }),
        // empty / missing notices
        child.maybe <-- Signal.combine(geomSig, designSig).map {
          case (Some(g), _) if g.layout.nodes.isEmpty && g.mod.children.isEmpty =>
            Some(div(styleAttr := "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none",
              span(dataAttr("empty-module") := "1", styleAttr := s"font-family:${Tokens.mono};font-size:12px;color:${Tokens.ink4}",
                "This module has no instances, ports, or wires to display.")))
          case _ => None
        },
      ),
      SchematicChrome.controls(ctrlCtx),
      // Minimap, shown only when content overflows the viewport at this zoom.
      child.maybe <-- Signal.combine(geomSig, panVar.signal, zoomVar.signal, viewSizeVar.signal).map {
        case (Some(g), (px, py), z, (vw, vh))
          if g.layout.nodes.nonEmpty && vw > 0 && (g.layout.w * z > vw + 1 || g.canvasH * z > vh + 1) =>
          Some(SchematicChrome.minimap(g, px, py, z, vw, vh, centerOnContent))
        case _ => None
      },
      // keep totalW/canvasH mirrors + current module name in sync with geometry
      geomSig --> { (gO: Option[Geom]) => gO.foreach { g => totalWVar.set(g.layout.w.toDouble); canvasHVar.set(g.canvasH); curModName = g.mod.name } },
      // Clear the filter on navigation (JS clears it per path change).
      pathSig.map(_.mkString("/")).changes --> { _ => filterVar.set("") },
      // Track the current module path; arm a fit/restore only on a real path
      // change. Stash the view of the module being left so returning restores it.
      // Bundle expansion grows the box (changes totalW/canvasH) but does NOT
      // change the path, so it must not re-fit the view (issue #207).
      pathSig.map(_.mkString("/")) --> { pk =>
        if (prevPathKey != null && prevPathKey != pk)
          viewState(prevPathKey) = (zoomVar.now(), panVar.now())
        if (prevPathKey != pk) pendingFit = true
        prevPathKey = pk
      },
      // Perform the armed fit/restore once the geometry dimensions are known,
      // then disarm. Later dimension changes (expansion, Optimize) leave the
      // user's pan/zoom untouched.
      Signal.combine(totalWVar.signal, canvasHVar.signal) --> { (t: (Double, Double)) =>
        val (tw, ch) = t
        if (pendingFit && viewportNode != null && tw > 0 && ch > 0) {
          viewState.get(prevPathKey) match {
            case Some((z, p)) => zoomVar.set(z); panVar.set(p)
            case None         => doFit()
          }
          pendingFit = false
        }
      },
      // in-process engine re-layout on module / expansion / Optimize
      Signal.combine(designSig, moduleSig, expandedPortsVar.signal, optNonceVar.signal) --> {
        (t: (Option[Design], Option[ModuleDef], Set[String], Int)) =>
          val optimize = t._4 != lastNonce
          lastNonce = t._4
          val eff = t._3 ++ (for { d <- t._1; m <- t._2 } yield lockedExpansions(d, m)).getOrElse(Set.empty)
          runEngine(t._1, t._2, eff, optimize)
      },
    )
  }
}
