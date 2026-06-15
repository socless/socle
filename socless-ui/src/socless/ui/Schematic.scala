package socless.ui

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.{ReactiveElement, ChildNode}
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Success, Failure}
import socless.{Design, ModuleDef}
import socless.js.BrowserLayout
import socless.js.BrowserLayout.{LaidOut, LaidNodeR, BoxOverride}

/** The schematic (center) pane: a block diagram of the current module's children
  * and their wires (epic #172 Phase 2). Behavioral port of the JS `BenchSchematic`
  * (ui/SoCLess.html:2994+). Unlike the JS UI, the layout engine runs **in-process**:
  * the baked layout comes from the typed `Design.layouts`, and interactive
  * re-layout (bundle expand / Optimize) calls [[BrowserLayout.runTyped]] directly.
  * The expansion row model is computed in Scala by [[RowModel]] (issue #170 B/C). */
object Schematic {

  // ── Geometry model (pure) ──────────────────────────────────────────────────

  private final case class Wire(
    id: String, fromPath: String, toPath: String, fromInst: String, toInst: String,
    bundle: String, bidir: Boolean, points: Seq[(Double, Double)],
  ) {
    def x1: Double = points.head._1; def y1: Double = points.head._2
    def x2: Double = points.last._1; def y2: Double = points.last._2
  }

  private final case class BoxShift(shift: Map[String, Double], shiftAt: Double => Double, total: Double)

  private final case class Geom(
    layout: LaidOut, mod: ModuleDef, design: Design,
    rows: Map[String, RowModel.BoxRows], wires: Seq[Wire], boxShift: BoxShift,
    canvasH: Double, c: Tokens.Consts, boxById: Map[String, LaidNodeR],
  )

  /** A mutable display wire (bidir folding, ported from JS `displayWires`). */
  private final class DWire(var from: String, var to: String, var bundle: String,
                            var label: String, var bidir: Boolean, var count: Int)

  private val CountRe = """(\d+)\s*×""".r
  private def groundCount(label: String): Int =
    CountRe.findFirstMatchIn(if (label == null) "" else label).map(_.group(1).toInt).getOrElse(1)

  /** Fold matched A->B / B->A pairs into one bidirectional wire so the schematic
    * reads as Chisel-level connects, not ground-signal flow (JS displayWires). */
  private def displayWires(mod: ModuleDef): Seq[DWire] = {
    def pairKey(a: String, b: String) = if (a < b) a + "|" + b else b + "|" + a
    val seen = scala.collection.mutable.Map.empty[String, scala.collection.mutable.ListBuffer[DWire]]
    val out  = scala.collection.mutable.ListBuffer.empty[DWire]
    mod.wires.foreach { w =>
      val a = w.from.split('.').headOption.getOrElse("")
      val b = w.to.split('.').headOption.getOrElse("")
      if (a.isEmpty || b.isEmpty || a == b) out += new DWire(w.from, w.to, w.bundle, w.label, false, groundCount(w.label))
      else {
        val peers = seen.getOrElseUpdate(pairKey(a, b), scala.collection.mutable.ListBuffer.empty)
        peers.find(p => p.from.split('.').headOption.contains(b) && p.to.split('.').headOption.contains(a)) match {
          case Some(rev) =>
            rev.bidir = true
            rev.count += groundCount(w.label)
            if (rev.bundle == "Wire" && w.bundle.nonEmpty && w.bundle != "Wire") rev.bundle = w.bundle
            rev.label = if (rev.count > 1) s"${rev.count}× ground (bidir)" else "bidirectional"
          case None =>
            val fresh = new DWire(w.from, w.to, w.bundle, w.label, false, groundCount(w.label))
            peers += fresh; out += fresh
        }
      }
    }
    out.toSeq
  }

  /** Restore strict orthogonality: any diagonal segment becomes an H-V-H jog
    * through its horizontal midpoint (JS orthogonalizePts). */
  private def orthogonalize(pts: Seq[(Double, Double)]): Seq[(Double, Double)] = {
    if (pts.isEmpty) return pts
    val out = scala.collection.mutable.ListBuffer(pts.head)
    for (i <- 1 until pts.length) {
      val (x0, y0) = out.last; val (x1, y1) = pts(i)
      if (x0 == x1 && y0 == y1) {} // drop exact duplicate
      else {
        if (x0 != x1 && y0 != y1) { val mx = (x0 + x1) / 2; out += ((mx, y0)); out += ((mx, y1)) }
        out += ((x1, y1))
      }
    }
    out.toSeq
  }

  private def ownerBoxId(boxById: Map[String, LaidNodeR], path: String): String =
    if (boxById.contains(path)) path else path.split('.').headOption.getOrElse(path)

  private def computeBoxShift(layout: LaidOut, rows: Map[String, RowModel.BoxRows]): BoxShift = {
    val raw = layout.nodes.flatMap { b =>
      rows.get(b.id).filter(_.boxH > b.h).map(br => (b.y + b.h).toDouble -> (br.boxH - b.h))
    }
    val byBottom = raw.groupBy(_._1).map { case (bottom, gs) => bottom -> gs.map(_._2).max }
    val inserts  = byBottom.toSeq
    def shiftAt(y: Double): Double = inserts.collect { case (bottom, delta) if y >= bottom - 1 => delta }.sum
    val shift = layout.nodes.map(b => b.id -> shiftAt(b.y.toDouble)).filter(_._2 > 0).toMap
    BoxShift(shift, shiftAt, inserts.map(_._2).sum)
  }

  private def buildWires(dwires: Seq[DWire], layout: LaidOut, boxById: Map[String, LaidNodeR],
                         pinYMap: Map[String, Map[String, Double]], boxShift: BoxShift): Seq[Wire] = {
    val edgeByKey = layout.edges.map(e => (e.from + " " + e.to) -> e).toMap
    dwires.zipWithIndex.flatMap { case (w, i) =>
      val fromInst = w.from.split('.').headOption.getOrElse("")
      val toInst   = w.to.split('.').headOption.getOrElse("")
      edgeByKey.get(w.from + " " + w.to).map(_.points).filter(_.length >= 2).map { ptsInt =>
        val pts     = ptsInt.map { case (x, y) => (x.toDouble, y.toDouble) }
        val fromBox = boxById.get(fromInst); val toBox = boxById.get(toInst)
        val fromShift = boxShift.shift.getOrElse(ownerBoxId(boxById, w.from), 0.0)
        val toShift   = boxShift.shift.getOrElse(ownerBoxId(boxById, w.to), 0.0)
        // pts run source->sink: pts.head = w.to (source), pts.last = w.from (sink).
        val toPinY = pinYMap.get(toInst).flatMap(_.get(w.to)) match {
          case Some(y) => toBox.map(_.y + toShift + y).getOrElse(pts.head._2)
          case None    => pts.head._2 + toShift
        }
        val fromPinY = pinYMap.get(fromInst).flatMap(_.get(w.from)) match {
          case Some(y) => fromBox.map(_.y + fromShift + y).getOrElse(pts.last._2)
          case None    => pts.last._2 + fromShift
        }
        val moved = pts.zipWithIndex.map { case ((x, y), idx) =>
          if (idx == 0) (x, toPinY)
          else if (idx == pts.length - 1) (x, fromPinY)
          else (x, y + boxShift.shiftAt(y))
        }
        Wire(s"w$i", w.from, w.to, fromInst, toInst, w.bundle, w.bidir, orthogonalize(moved))
      }
    }
  }

  private def bakedOf(d: Design, mod: ModuleDef): Option[LaidOut] = {
    val key = d.layoutOf.getOrElse(mod.name, mod.name)
    d.layouts.get(key).map(LaidOut.fromBaked)
  }

  private def glyph(d: Design, tpe: String): String =
    d.modules.find(_.name == tpe) match {
      case None => "·"
      case Some(m) =>
        if (m.children.length >= 3) "⬢"
        else if (m.metrics.registers > 0) "◆"
        else if (m.kind == "external" || m.kind == "intrinsic") "■"
        else "·"
    }

  /** Conditional child: the element when `c`, else a no-op comment node. The
    * explicit [[ChildNode.Base]] result type keeps the if/else from widening to
    * `Object` in argument position. */
  private def opt(c: Boolean)(el: => ChildNode.Base): ChildNode.Base = if (c) el else emptyNode

  // SVG path data for the wire polyline.
  private def routePath(pts: Seq[(Double, Double)]): String =
    if (pts.length >= 2) "M " + pts.map { case (x, y) => s"$x $y" }.mkString(" L ")
    else ""

  /** Subsequence fuzzy test: does `needle` appear in `hay` in order? (JS fuzzyHas) */
  private def fuzzyHas(hay: String, needle: String): Boolean = {
    if (needle.isEmpty) return true
    var i = 0
    hay.foreach(ch => if (i < needle.length && ch == needle(i)) i += 1)
    i == needle.length
  }

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
    val engineVar   = Var[Option[LaidOut]](None)
    val pendingVar  = Var(false)
    val optNonceVar = Var(0)
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

    def clamp(z: Double): Double = math.max(0.08, math.min(4.0, z))
    def togglePort(p: String): Unit = expandedPortsVar.update(s => if (s(p)) s - p else s + p)

    // ── geometry signal ──
    def computeGeom(d: Design, mod: ModuleDef, engine: Option[LaidOut], expanded: Set[String]): Geom = {
      val c       = Tokens.consts(d)
      val layout  = engine.orElse(bakedOf(d, mod)).getOrElse(LaidOut(Nil, Nil, 1, 1))
      val rows    = RowModel.build(layout, mod, d, expanded, c.headerH, c.portPad, c.portStep)
      val pinYMap = rows.map { case (i, br) => i -> br.pinY(c.headerH, c.portPad, c.portStep) }
      val boxById = layout.nodes.map(n => n.id -> n).toMap
      val shift   = computeBoxShift(layout, rows)
      val wires   = buildWires(displayWires(mod), layout, boxById, pinYMap, shift)
      val canvasH = (layout.h + 40) + shift.total
      Geom(layout, mod, d, rows, wires, shift, canvasH, c, boxById)
    }

    val geomSig: Signal[Option[Geom]] =
      Signal.combine(designSig, moduleSig, engineVar.signal, expandedPortsVar.signal).map {
        case (Some(d), Some(mod), engine, expanded) => Some(computeGeom(d, mod, engine, expanded))
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
              BrowserLayout.runTyped(mod, d, if (optimize) "optimize" else "reroute", overrides).onComplete {
                case Success(res) => if (myGen == engineGen) { engineVar.set(Some(res)); pendingVar.set(false) }
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

    def handleFit(pk: String, tw: Double, ch: Double): Unit = {
      if (viewportNode == null || tw <= 0 || ch <= 0) return
      if (prevPathKey != null && prevPathKey != pk) viewState(prevPathKey) = (zoomVar.now(), panVar.now())
      prevPathKey = pk
      viewState.get(pk) match {
        case Some((z, p)) => zoomVar.set(z); panVar.set(p)
        case None         => doFit()
      }
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
        lazy val moveL: js.Function1[dom.MouseEvent, Unit] = (ev) => panVar.set((px + (ev.clientX - sx), py + (ev.clientY - sy)))
        lazy val upL: js.Function1[dom.MouseEvent, Unit] = (_) => {
          dom.window.removeEventListener("mousemove", moveL)
          dom.window.removeEventListener("mouseup", upL)
        }
        dom.window.addEventListener("mousemove", moveL)
        dom.window.addEventListener("mouseup", upL)
      }
    }

    // ── content (boxes + wires), rebuilt on geom / selection / hover ──
    def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

    def renderContent(g: Geom, sel: Option[String], hov: Option[String], filter: String): Seq[ReactiveElement.Base] = {
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
        g.wires.map { w =>
          svg.path(
            sa("d") := routePath(w.points),
            sa("data-wire") := w.fromPath,
            sa("data-pan-block") := "1",
            sa("style") := "stroke:transparent;stroke-width:14;fill:none;cursor:pointer;pointer-events:stroke",
            onClick --> { (_: dom.MouseEvent) => selectedVar.set(Some(w.fromPath)) },
            onMouseEnter --> { (_: dom.MouseEvent) => hoveredVar.set(Some(w.fromPath)) },
            onMouseLeave --> { (_: dom.MouseEvent) => hoveredVar.set(None) },
          )
        },
      )

      // ── boxes ──
      val boxEls: Seq[ReactiveElement.Base] = g.layout.nodes.map { n =>
        val child = g.mod.children.find(_.name == n.id)
        val shift = g.boxShift.shift.getOrElse(n.id, 0.0)
        val top   = n.y + shift
        if (child.isEmpty || n.kind == "boundary") boundaryStub(g, n, top, boxActive(n.id))
        else {
          val ch         = child.get
          val childMod   = g.design.modules.find(_.name == ch.`type`)
          val isBlackbox = childMod.exists(m => m.kind == "external" || m.kind == "intrinsic")
          val extLabel: String = childMod.map(m => if (m.kind == "intrinsic") "intrinsic" else "ext").getOrElse("ext")
          val drillable  = childMod.exists(m => !isBlackbox && m.children.nonEmpty)
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
              onClick --> { (_: dom.MouseEvent) => selectedVar.set(Some(n.id)) },
              span(styleAttr := s"width:12px;display:inline-flex;justify-content:center;color:${if (selectedHere) Tokens.accent else Tokens.ink3};font-size:10px", glyph(g.design, ch.`type`)),
              span(styleAttr := s"font-family:${Tokens.mono};font-size:11.5px;font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", n.id),
              div(styleAttr := "margin-left:auto;flex-shrink:0;display:flex;align-items:center;gap:6px",
                opt(isBlackbox)(span(styleAttr := s"font-family:${Tokens.mono};font-size:8px;font-weight:600;letter-spacing:.4px;text-transform:uppercase;color:${Tokens.ink3};background:${Tokens.panel};border:1px dashed ${Tokens.border};border-radius:3px;padding:0 4px;line-height:13px",
                  extLabel)),
                span(styleAttr := s"font-family:${Tokens.mono};font-size:10px;color:${Tokens.ink3};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:80px", ch.`type`),
                opt(drillable)(span(styleAttr := s"font-family:${Tokens.mono};font-size:9px;color:${Tokens.accent}", "↵")),
              ),
            ),
            // port rows
            renderPortRows(g, n, boxInfo.westRows, "WEST", sel, hov),
            renderPortRows(g, n, boxInfo.eastRows, "EAST", sel, hov),
          )
        }
      }

      // ── junctions (fan-out filled / fan-in hollow, issue #166) ──
      val bySource = g.wires.groupBy(_.toPath).values.filter(_.length > 1).map(ws => (ws.head.x1, ws.head.y1, ws.length)).toSeq
      val bySink   = g.wires.groupBy(_.fromPath).values.filter(_.length > 1).map(ws => (ws.head.x2, ws.head.y2, ws.length)).toSeq

      // Field-level (split) wires render thinner so a scattered bundle reads as
      // many fine wires vs one bold bundle wire (issue #182).
      val fieldLevelWires: Set[(String, String)] =
        g.mod.wires.iterator.filter(_.fieldLevel).map(w => (w.from, w.to)).toSet

      // ── visual layer (stroke + halo + junctions) ──
      val vis = svg.svg(
        sa("style") := s"position:absolute;top:0;left:0;width:${g.layout.w}px;height:${g.canvasH}px;pointer-events:none;overflow:visible",
        g.wires.map { w =>
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
          svg.g(
            sa("style") := s"opacity:$groupOp;transition:opacity .15s",
            svg.path(sa("d") := d, sa("style") := s"stroke:$halo;stroke-width:${sw + 6};fill:none;stroke-linecap:round;stroke-linejoin:round;opacity:$haloOp"),
            svg.path(sa("d") := d, sa("style") := s"stroke:$stroke;stroke-width:$sw;fill:none;stroke-linecap:round;stroke-linejoin:round"),
            if (w.bidir) bidirGlyph(w.points, stroke) else emptyNode,
          )
        },
        bySource.zipWithIndex.map { case ((x, y, count), _) =>
          svg.circle(sa("data-junction") := "1", sa("cx") := x.toString, sa("cy") := y.toString, sa("r") := "3.5",
            sa("style") := s"fill:${Tokens.ink2};stroke:${Tokens.bg};stroke-width:1.5",
            svg.titleTag(s"$count-way fan-out"))
        },
        bySink.zipWithIndex.map { case ((x, y, count), _) =>
          svg.circle(sa("data-fanin") := "1", sa("cx") := x.toString, sa("cy") := y.toString, sa("r") := "3.5",
            sa("style") := s"fill:${Tokens.bg};stroke:${Tokens.ink2};stroke-width:1.75",
            svg.titleTag(s"$count-way fan-in"))
        },
      )

      (Seq[ReactiveElement.Base](hit) ++ boxEls) :+ (vis: ReactiveElement.Base)
    }

    // Bidirectional cue: a mid-wire opposed double-chevron (JS bidirChevrons).
    def bidirGlyph(pts: Seq[(Double, Double)], stroke: String): ReactiveElement.Base = {
      polylineMidpoint(pts) match {
        case None => emptyNode.asInstanceOf[ReactiveElement.Base]
        case Some((x, y, dx, dy)) =>
          val px = -dy; val py = dx; val gap = 2.5; val s = 3.0; val w = 3.0
          val tax = x + gap * dx; val tay = y + gap * dy
          val tbx = x - gap * dx; val tby = y - gap * dy
          val a = s"M ${tax - s * dx + w * px} ${tay - s * dy + w * py} L $tax $tay L ${tax - s * dx - w * px} ${tay - s * dy - w * py}"
          val b = s"M ${tbx + s * dx + w * px} ${tby + s * dy + w * py} L $tbx $tby L ${tbx + s * dx - w * px} ${tby + s * dy - w * py}"
          svg.path(sa("d") := a + " " + b, sa("style") := s"stroke:$stroke;stroke-width:1.3;fill:none;stroke-linecap:round;stroke-linejoin:round;opacity:.9")
      }
    }

    def polylineMidpoint(pts: Seq[(Double, Double)]): Option[(Double, Double, Double, Double)] = {
      if (pts.length < 2) return None
      val segLen = (1 until pts.length).map(i => math.hypot(pts(i)._1 - pts(i - 1)._1, pts(i)._2 - pts(i - 1)._2))
      val total  = segLen.sum
      if (total < 1e-6) return None
      var half = total / 2
      for (i <- segLen.indices) {
        if (half <= segLen(i) || i == segLen.length - 1) {
          val len = if (segLen(i) == 0) 1.0 else segLen(i)
          val t = half / len
          val (x0, y0) = pts(i); val (x1, y1) = pts(i + 1)
          return Some((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, (x1 - x0) / len, (y1 - y0) / len))
        }
        half -= segLen(i)
      }
      None
    }

    // ── boundary stub box ──
    def boundaryStub(g: Geom, n: LaidNodeR, top: Double, active: Boolean): ReactiveElement.Base = {
      val hasLabel = n.ports.exists(_.label.nonEmpty)
      div(
        dataAttr("boundary-stub") := n.id,
        dataAttr("pan-block") := "1",
        title := n.id,
        styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${n.h}px;" +
          s"opacity:${if (active) "1" else "0.18"};transition:opacity .15s;" +
          s"background:${Tokens.panelAlt};border:1px dashed ${Tokens.border};border-radius:4px;" +
          s"display:flex;align-items:center;justify-content:center;font-family:${Tokens.mono};font-size:9.5px;color:${Tokens.ink3};overflow:hidden;cursor:default",
        onClick --> { (_: dom.MouseEvent) => selectedVar.set(Some(n.id)) },
        if (!hasLabel) span(styleAttr := "padding:0 6px;text-align:center;word-break:break-all", n.id) else emptyNode,
        n.ports.map { p =>
          val pw = if (p.interface) 8 else 3
          val ph = if (p.interface) 12 else 6
          val isWest = p.side == "WEST"
          val labelStyle = if (isWest) s"left:${pw + 3}px;text-align:left" else s"right:${pw + 3}px;text-align:right"
          div(
            div(title := p.path,
              styleAttr := s"position:absolute;left:${(p.x - n.x) - pw / 2.0}px;top:${(p.y - n.y) - ph / 2.0}px;width:${pw}px;height:${ph}px;" +
                s"background:${if (p.interface) Tokens.accent else Tokens.ink4};border-radius:${if (p.interface) 3 else 1}px;" +
                (if (p.interface) s"outline:1.5px solid ${Tokens.accentLine};outline-offset:1px" else "")),
            if (p.label.nonEmpty) span(
              styleAttr := s"position:absolute;top:${(p.y - n.y) - 5}px;$labelStyle;font-family:${Tokens.mono};font-size:8.5px;" +
                s"color:${if (p.interface) Tokens.ink2 else Tokens.ink3};white-space:nowrap;pointer-events:none;" +
                s"max-width:${math.max(12.0, n.w - pw - 8)}px;overflow:hidden;text-overflow:ellipsis;line-height:10px",
              p.label) else emptyNode,
          )
        },
      )
    }

    // ── port rows inside an instance box ──
    def renderPortRows(g: Geom, n: LaidNodeR, rows: Seq[RowModel.Row], side: String,
                       sel: Option[String], hov: Option[String]): Seq[ReactiveElement.Base] = {
      val c = g.c
      val isWest = side == "WEST"
      rows.zipWithIndex.map { case (row, rowIdx) =>
        val rowY = c.headerH + c.portPad + rowIdx * c.portStep + c.portStep / 2
        val indentPx = row.depth * 8
        if (row.isTopLevel) {
          val isInterface = row.isExpandable
          val pw = if (isInterface) 8 else 3
          val ph = if (isInterface) 12 else 6
          val pinAbsX = if (isWest) 0.0 else n.w.toDouble
          val pinSel = sel.exists(s => row.path.startsWith(s) || s.startsWith(row.path))
          val pinHov = !pinSel && hov.exists(s => row.path.startsWith(s) || s.startsWith(row.path))
          val pinBg  = if (pinSel) Tokens.accent else if (pinHov) Tokens.ink else if (isInterface) Tokens.accent else Tokens.ink3
          val labelSide = if (isWest) s"left:${pw + 4 + indentPx}px" else s"right:${pw + 4 + indentPx}px"
          div(
            div(title := row.path,
              styleAttr := s"position:absolute;left:${pinAbsX - pw / 2.0}px;top:${rowY - ph / 2.0}px;width:${pw}px;height:${ph}px;" +
                s"background:$pinBg;border-radius:${if (isInterface) 3 else 1}px;" +
                (if (isInterface) s"outline:1.5px solid ${Tokens.accentLine};outline-offset:1px;" else "") + "transition:background .12s"),
            div(
              dataAttr("pan-block") := "1",
              if (row.isExpandable) dataAttr("bundle-toggle") := row.path else emptyMod,
              styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;width:${math.max(24.0, n.w / 2.0 - (pw + 4 + indentPx) - 4)}px;" +
                s"display:flex;align-items:center;flex-direction:${if (isWest) "row" else "row-reverse"};justify-content:flex-start;gap:3px;height:16px;" +
                s"font-family:${Tokens.mono};font-size:8.5px;color:${Tokens.ink2};white-space:nowrap;overflow:hidden;" +
                s"pointer-events:${if (row.isExpandable) "auto" else "none"};cursor:${if (row.isExpandable) "pointer" else "default"}",
              if (row.isExpandable) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod,
              if (row.isExpandable) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink2};font-size:8px;line-height:1",
                child.text <-- expandedPortsVar.signal.map(s => if (s(row.path)) "−" else "+")) else emptyNode,
              span(title := row.path, styleAttr := "overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:1", row.label),
            ),
          )
        } else {
          val labelSide = if (isWest) s"left:${12 + indentPx}px" else s"right:${12 + indentPx}px"
          div(
            dataAttr("pan-block") := "1",
            if (row.isExpandable) dataAttr("bundle-toggle") := row.path else emptyMod,
            styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;width:${math.max(24.0, n.w / 2.0 - (12 + indentPx) - 4)}px;" +
              s"display:flex;align-items:center;flex-direction:${if (isWest) "row" else "row-reverse"};justify-content:flex-start;gap:3px;height:16px;" +
              s"font-family:${Tokens.mono};font-size:8px;color:${Tokens.ink3};white-space:nowrap;overflow:hidden;" +
              s"pointer-events:${if (row.isExpandable) "auto" else "none"};cursor:${if (row.isExpandable) "pointer" else "default"}",
            if (row.isExpandable) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod,
            if (row.isExpandable) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:8px",
              child.text <-- expandedPortsVar.signal.map(s => if (s(row.path)) "−" else "+")) else emptyNode,
            span(title := row.path, styleAttr := "overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:1", row.label),
            row.dir match {
              case Some(d) => span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:7px", if (d == "out") "→" else "←")
              case None    => emptyNode
            },
            if (row.width > 0) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:7px", s"${row.width}b") else emptyNode,
          )
        }
      }
    }

    // ── chrome (top-left chips) ──
    def chrome(): Element =
      div(styleAttr := "position:absolute;top:12px;left:12px;right:12px;display:flex;align-items:center;z-index:2;pointer-events:none",
        div(styleAttr := "display:flex;gap:6px;pointer-events:auto",
          child <-- moduleSig.map {
            case Some(mod) => div(styleAttr := s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:4px 10px;font-size:11px;color:${Tokens.ink2};display:flex;align-items:center;gap:6px;font-family:${Tokens.mono}",
              span(styleAttr := s"color:${Tokens.ink4}", "module"), span(styleAttr := s"color:${Tokens.ink}", mod.`type`))
            case None => emptyNode
          },
          child <-- moduleSig.map {
            case Some(mod) => div(styleAttr := s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:4px 10px;font-size:11px;color:${Tokens.ink3};font-family:${Tokens.mono}",
              s"${mod.children.length} inst · ${mod.wires.length} connects")
            case None => emptyNode
          },
          // Filter box (issue #121): dims non-matching boxes + wires.
          div(styleAttr <-- filterVar.signal.map(f =>
              s"display:flex;align-items:center;gap:4px;background:${Tokens.panel};border:1px solid ${if (f.nonEmpty) Tokens.accent else Tokens.border};border-radius:5px;padding:0 8px;font-family:${Tokens.mono};font-size:11px"),
            input(dataAttr("filter-input") := "1", placeholder := "filter…",
              styleAttr := s"border:none;outline:none;background:transparent;width:90px;height:24px;font-family:inherit;font-size:11px;color:${Tokens.ink}",
              controlled(value <-- filterVar.signal, onInput.mapToValue --> filterVar.writer),
              onKeyDown --> { (e: dom.KeyboardEvent) => if (e.key == "Escape") { filterVar.set(""); e.target.asInstanceOf[dom.html.Element].blur() } }),
            child.maybe <-- filterVar.signal.map(f => if (f.nonEmpty)
              Some(button(title := "Clear filter (Esc)", styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:13px;line-height:1;padding:0",
                onClick --> { _ => filterVar.set("") }, "×")) else None)),
        ),
      )

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

    // ── zoom / optimize controls (bottom-right) ──
    val zbtn = s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;padding:3px 6px;font-family:${Tokens.mono};font-size:11px;border-radius:3px"
    def controls(): Element =
      div(styleAttr := s"position:absolute;bottom:12px;right:12px;z-index:3;display:flex;align-items:center;gap:2px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:2px;font-family:${Tokens.mono};font-size:11px",
        button(dataAttr("optimize") := "1", title := "Re-layout to minimize wire length (in-browser elkjs, issue #167)",
          styleAttr <-- Signal.combine(pendingVar.signal, engineVar.signal).map { case (p, e) =>
            zbtn + s";color:${if (p) Tokens.ink4 else if (e.isDefined) Tokens.accent else Tokens.ink3};cursor:${if (p) "default" else "pointer"}" },
          disabled <-- pendingVar.signal,
          onClick --> { (_: dom.MouseEvent) => optNonceVar.update(_ + 1) },
          child.text <-- pendingVar.signal.map(p => if (p) "…" else "optimize")),
        span(styleAttr := s"width:1px;align-self:stretch;background:${Tokens.borderSoft};margin:0 2px"),
        button(styleAttr := zbtn, title := "Fit to view", onClick --> { (_: dom.MouseEvent) => doFit() }, "fit"),
        button(styleAttr := zbtn, title := "Zoom out", onClick --> { (_: dom.MouseEvent) => zoomBy(0.8) }, "−"),
        span(styleAttr := s"padding:3px 6px;color:${Tokens.ink2};min-width:38px;text-align:center",
          child.text <-- zoomVar.signal.map(z => s"${math.round(z * 100)}%")),
        button(styleAttr := zbtn, title := "Zoom in", onClick --> { (_: dom.MouseEvent) => zoomBy(1.25) }, "+"),
        button(styleAttr := zbtn, title := "Reset to 100%", onClick --> { (_: dom.MouseEvent) => zoomVar.set(1.0); panVar.set((40.0, 40.0)) }, "1:1"),
        span(styleAttr := s"width:1px;align-self:stretch;background:${Tokens.borderSoft};margin:0 2px"),
        button(styleAttr := zbtn, dataAttr("export-svg") := "1", title := "Export schematic as SVG",
          onClick --> { _ => if (contentNode != null) withCleared(() => Export.exportSvg(contentNode, expW, expH, curModName)) }, "svg"),
        button(styleAttr := zbtn, dataAttr("export-png") := "1", title := "Export schematic as PNG (2x)",
          onClick --> { _ => if (contentNode != null) withCleared(() => Export.exportPng(contentNode, expW, expH, curModName)) }, "png"),
        button(styleAttr := zbtn, dataAttr("export-snapshot") := "1", title := "Save a self-contained interactive HTML snapshot (opens from file://)",
          onClick --> { _ => designSnapshot().foreach { case (raw, top) => Export.snapshot(raw, top) } }, "share"),
      )

    val transformSig = Signal.combine(panVar.signal, zoomVar.signal, totalWVar.signal, canvasHVar.signal).map {
      case ((px, py), z, w, h) =>
        s"position:absolute;left:0;top:0;width:${w}px;height:${h}px;transform:translate(${px}px,${py}px) scale($z);transform-origin:0 0;transition:width .3s,height .3s"
    }

    val contentSig: Signal[Seq[ReactiveElement.Base]] =
      Signal.combine(geomSig, selectedVar.signal, hoveredVar.signal, filterVar.signal).map {
        case (Some(g), sel, hov, filter) => renderContent(g, sel, hov, filter)
        case _                           => Nil
      }

    // Recenter the main view on a content-space point (used by the minimap).
    def centerOnContent(cx: Double, cy: Double): Unit = {
      val (vw, vh) = viewSizeVar.now(); val z = zoomVar.now()
      panVar.set((vw / 2 - cx * z, vh / 2 - cy * z))
    }

    // Minimap (issue #5): overview + draggable viewport rect, shown only when the
    // content overflows the viewport at the current zoom.
    def minimap(g: Geom, px: Double, py: Double, z: Double, vw: Double, vh: Double): Element = {
      val maxW = 190.0; val maxH = 140.0
      val s = math.min(maxW / g.layout.w, maxH / g.canvasH)
      val w = math.max(1.0, g.layout.w * s); val h = math.max(1.0, g.canvasH * s)
      val rx = (-px / z) * s; val ry = (-py / z) * s
      val rw = (vw / z) * s;  val rh = (vh / z) * s
      def centerAt(clientX: Double, clientY: Double, rect: dom.DOMRect): Unit = {
        val mx = math.max(0.0, math.min(w, clientX - rect.left))
        val my = math.max(0.0, math.min(h, clientY - rect.top))
        centerOnContent(mx / s, my / s)
      }
      div(dataAttr("minimap") := "1", dataAttr("pan-block") := "1", title := "Click or drag to pan",
        styleAttr := s"position:absolute;bottom:12px;left:12px;z-index:3;width:${w}px;height:${h}px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:4px;cursor:pointer;overflow:hidden;box-shadow:0 1px 5px rgba(0,0,0,.14)",
        onMouseDown --> { (e: dom.MouseEvent) =>
          if (e.button == 0) {
            e.preventDefault(); e.stopPropagation()
            val rect = e.currentTarget.asInstanceOf[dom.html.Element].getBoundingClientRect()
            centerAt(e.clientX, e.clientY, rect)
            lazy val moveL: js.Function1[dom.MouseEvent, Unit] = (ev) => centerAt(ev.clientX, ev.clientY, rect)
            lazy val upL: js.Function1[dom.MouseEvent, Unit] = (_) => {
              dom.window.removeEventListener("mousemove", moveL); dom.window.removeEventListener("mouseup", upL)
            }
            dom.window.addEventListener("mousemove", moveL); dom.window.addEventListener("mouseup", upL)
          }
        },
        g.layout.nodes.map(n => div(styleAttr := s"position:absolute;left:${n.x * s}px;top:${n.y * s}px;width:${math.max(1.0, n.w * s)}px;height:${math.max(1.0, n.h * s)}px;background:${Tokens.ink4};opacity:0.45;border-radius:1px")),
        div(dataAttr("minimap-viewport") := "1",
          styleAttr := s"position:absolute;left:${rx}px;top:${ry}px;width:${rw}px;height:${rh}px;border:1.5px solid ${Tokens.accent};background:${Tokens.accentLine};box-sizing:border-box;pointer-events:none"),
      )
    }

    // ── pane root ──
    div(
      dataAttr("pane") := "schematic",
      dataAttr("layout-ready") <-- pendingVar.signal.map(p => if (p) "0" else "1"),
      cls := "scl-pane scl-pane-schematic",
      styleAttr := "flex:1;position:relative;overflow:hidden",
      chrome(),
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
      controls(),
      // Minimap, shown only when content overflows the viewport at this zoom.
      child.maybe <-- Signal.combine(geomSig, panVar.signal, zoomVar.signal, viewSizeVar.signal).map {
        case (Some(g), (px, py), z, (vw, vh))
          if g.layout.nodes.nonEmpty && vw > 0 && (g.layout.w * z > vw + 1 || g.canvasH * z > vh + 1) =>
          Some(minimap(g, px, py, z, vw, vh))
        case _ => None
      },
      // keep totalW/canvasH mirrors + current module name in sync with geometry
      geomSig --> { (gO: Option[Geom]) => gO.foreach { g => totalWVar.set(g.layout.w.toDouble); canvasHVar.set(g.canvasH); curModName = g.mod.name } },
      // Clear the filter on navigation (JS clears it per path change).
      pathSig.map(_.mkString("/")).changes --> { _ => filterVar.set("") },
      // auto-fit / restore per path
      Signal.combine(pathSig.map(_.mkString("/")), totalWVar.signal, canvasHVar.signal) --> {
        (t: (String, Double, Double)) => handleFit(t._1, t._2, t._3)
      },
      // in-process engine re-layout on module / expansion / Optimize
      Signal.combine(designSig, moduleSig, expandedPortsVar.signal, optNonceVar.signal) --> {
        (t: (Option[Design], Option[ModuleDef], Set[String], Int)) =>
          val optimize = t._4 != lastNonce
          lastNonce = t._4
          runEngine(t._1, t._2, t._3, optimize)
      },
    )
  }
}
