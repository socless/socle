package socle.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socle.schema.{ModuleDef, NodeKind}
import socle.js.BrowserLayout.LaidOut
import socle.ui.theme._
import SchematicGeom.Geom

/** The schematic pane's chrome overlays (#216 bullet 2), lifted out of the
  * `Schematic.render` closure so each takes the state it needs as explicit
  * params instead of closing over render scope: the top-left info/filter chips
  * ([[chrome]]), the bottom-right zoom/optimize/export controls ([[controls]]),
  * and the bottom-left overview [[minimap]].
  *
  * Members are `private[schematic]`. */
private[schematic] object SchematicChrome {

  /** Everything [[controls]] needs from the render scope: read-only signals for
    * the button states plus callbacks for each action (the export/share wiring,
    * which needs the live content node, stays in `render`). */
  final case class ControlsCtx(
    pendingSig:    Signal[Boolean],
    engineSig:     Signal[Option[LaidOut]],
    layerHasSig:   Signal[Boolean],
    showLayersVar: Var[Boolean],
    zoomSig:       Signal[Double],
    onOptimize:    () => Unit,
    onFit:         () => Unit,
    zoomBy:        Double => Unit,
    onResetView:   () => Unit,
    onExportSvg:   () => Unit,
    onExportPng:   () => Unit,
    onShare:       Option[() => Unit], // None: no share button
  )

  // ── chrome (top-left chips) ──
  /** `blockSig`: the module in view is an open logic block (its chip reads `block`). */
  def chrome(moduleSig: Signal[Option[ModuleDef]], filterVar: Var[String],
             matchSig: Signal[Option[(Int, Int)]] = Val(None),
             focusSig: Signal[Option[(String, Int)]] = Val(None), onFocusOff: () => Unit = () => (),
             blockSig: Signal[Boolean] = Val(false)): Element =
    div(styleAttr := "position:absolute;top:12px;left:12px;right:12px;display:flex;align-items:center;z-index:2;pointer-events:none",
      div(styleAttr := "display:flex;gap:6px;pointer-events:auto",
        child <-- Signal.combine(moduleSig, blockSig).map {
          case (Some(mod), block) => div(dataAttr("module-chip") := (if (block) "block" else "module"),
            styleAttr := s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:4px 10px;font-size:11px;color:${Tokens.ink2};display:flex;align-items:center;gap:6px;font-family:${Tokens.mono}",
            span(styleAttr := s"color:${Tokens.ink4}", if (block) "block" else "module"), span(styleAttr := s"color:${Tokens.ink}", mod.`type`))
          case (None, _) => emptyNode
        },
        // A block holds logic, not instances: its registers and operators.
        child <-- Signal.combine(moduleSig, blockSig).map {
          case (Some(mod), block) => div(styleAttr := s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:4px 10px;font-size:11px;color:${Tokens.ink3};font-family:${Tokens.mono}",
            if (block) s"${mod.logic.count(_.kind == NodeKind.Reg)} reg · ${mod.logic.count(_.kind == NodeKind.Op)} op · ${mod.wires.length} connects"
            else s"${mod.children.length} inst · ${mod.wires.length} connects")
          case (None, _) => emptyNode
        },
        // Filter box (issue #121): dims non-matching boxes + wires.
        div(styleAttr <-- filterVar.signal.map(f =>
            s"display:flex;align-items:center;gap:4px;background:${Tokens.panel};border:1px solid ${if (f.nonEmpty) Tokens.accent else Tokens.border};border-radius:5px;padding:0 8px;font-family:${Tokens.mono};font-size:11px"),
          input(dataAttr("filter-input") := "1", placeholder := "filter…",
            styleAttr := s"border:none;outline:none;background:transparent;width:90px;height:24px;font-family:inherit;font-size:11px;color:${Tokens.ink}",
            controlled(value <-- filterVar.signal, onInput.mapToValue --> filterVar.writer),
            onKeyDown --> { (e: dom.KeyboardEvent) => if (e.key == "Escape") { filterVar.set(""); e.target.asInstanceOf[dom.html.Element].blur() } }),
          // Live match count "N / M" while filtering (#276).
          child.maybe <-- matchSig.map {
            case Some((m, t)) => Some(span(dataAttr("filter-count") := "1",
              styleAttr := s"color:${if (m == 0) Tokens.warn else Tokens.ink3};font-variant-numeric:tabular-nums;flex-shrink:0", s"$m / $t"))
            case None => None
          },
          child.maybe <-- filterVar.signal.map(f => if (f.nonEmpty)
            Some(button(title := "Clear filter (Esc)", styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:13px;line-height:1;padding:0",
              onClick --> { _ => filterVar.set("") }, "×")) else None)),
        // The editor focus (the class under the editor's cursor) while it dims
        // the view: what dims it, and × to stop it until the cursor moves to
        // another class (or the Follow the editor's cursor setting).
        child.maybe <-- focusSig.map(_.map { case (cls, n) =>
          val short = cls.split('.').last
          div(dataAttr("focus-chip") := cls,
            title := s"$cls is the class under your editor's cursor: its ${if (n == 1) "box is" else s"$n boxes are"} lit, the rest dimmed. Select something to see everything, or × to stop until the cursor moves to another class.",
            styleAttr := s"display:flex;align-items:center;gap:6px;background:${Tokens.panel};border:1px solid ${Tokens.accent};border-radius:5px;padding:0 4px 0 10px;height:26px;font-size:11px;color:${Tokens.ink2};font-family:${Tokens.mono}",
            span(styleAttr := s"color:${Tokens.ink4}", "focus"), span(styleAttr := s"color:${Tokens.ink}", short),
            button(dataAttr("focus-off") := "1", title := "Stop lighting this class (until the cursor moves to another class)",
              styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:13px;line-height:1;padding:0 4px",
              onClick --> { _ => onFocusOff() }, "×"))
        }),
      ),
    )

  /** The clock domains of `m` and their instances, when it has two or more
    * (bottom-right, above the zoom controls). A click on a domain lights it
    * (`toggle`), and the lit one (`active`) reads bold. */
  def clockLegend(m: socle.schema.ModuleDef, active: Signal[Option[String]], toggle: String => Unit): Option[Element] = {
    val colors = SchematicColors.clockDomains(m)
    Option.when(colors.nonEmpty)(
      div(dataAttr("clock-legend") := "1",
        styleAttr := s"position:absolute;bottom:46px;right:12px;z-index:3;max-width:320px;display:flex;flex-direction:column;gap:3px;" +
          s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:6px 9px;font-family:${Tokens.mono};font-size:10.5px;color:${Tokens.ink2}",
        socle.layout.LayoutCore.clockDomains(m).map { d =>
          val insts = m.children.filter(_.clock == d).map(_.name) ++
            m.logic.filter(n => n.kind == NodeKind.Reg && n.clock == d).map(_.id)
          div(dataAttr("legend-domain") := d, title := s"Light the $d domain",
            dataAttr("lit") <-- active.map(a => if (a.contains(d)) "1" else "0"),
            styleAttr <-- active.map(a => "display:flex;align-items:center;gap:6px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer;" +
              (if (a.contains(d)) "font-weight:700" else "")),
            onClick --> { _ => toggle(d) },
            span(styleAttr := s"width:10px;height:10px;border-radius:2px;flex-shrink:0;background:${colors(d)}"),
            span(styleAttr := s"color:${Tokens.ink}", d),
            span(styleAttr := "overflow:hidden;text-overflow:ellipsis", s"(${insts.mkString(", ")})"))
        }))
  }

  // ── zoom / optimize controls (bottom-right) ──
  private val zbtn = s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;padding:3px 6px;font-family:${Tokens.mono};font-size:11px;border-radius:3px"

  def controls(c: ControlsCtx): Element =
    div(styleAttr := s"position:absolute;bottom:12px;right:12px;z-index:3;display:flex;align-items:center;gap:2px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:5px;padding:2px;font-family:${Tokens.mono};font-size:11px",
      button(dataAttr("optimize") := "1", title := "Re-layout to minimize wire length (in-browser elkjs, issue #167)",
        styleAttr <-- Signal.combine(c.pendingSig, c.engineSig).map { case (p, e) =>
          zbtn + s";color:${if (p) Tokens.ink4 else if (e.isDefined) Tokens.accent else Tokens.ink3};cursor:${if (p) "default" else "pointer"}" },
        disabled <-- c.pendingSig,
        onClick --> { (_: dom.MouseEvent) => c.onOptimize() },
        child.text <-- c.pendingSig.map(p => if (p) "…" else "optimize")),
      // Show/hide Chisel verification-layer content, only offered when the
      // current module actually has any (issue #212).
      child.maybe <-- c.layerHasSig.map { has =>
        if (!has) None
        else Some(button(
          dataAttr("toggle-layers") := "1",
          styleAttr <-- c.showLayersVar.signal.map(s => zbtn + s";color:${if (s) Tokens.accent else Tokens.ink3}"),
          title := "Show Chisel verification layers (assert / assume / cover)",
          onClick --> { (_: dom.MouseEvent) => c.showLayersVar.update(!_) },
          child.text <-- c.showLayersVar.signal.map(s => if (s) "layers ✓" else "layers")))
      },
      // The colorize-components / colorize-wires toggles moved into the settings
      // panel (#274); they live in App now and the schematic reads them via params.
      span(styleAttr := s"width:1px;align-self:stretch;background:${Tokens.borderSoft};margin:0 2px"),
      button(styleAttr := zbtn, title := "Fit to view", onClick --> { (_: dom.MouseEvent) => c.onFit() }, "fit"),
      button(styleAttr := zbtn, title := "Zoom out", onClick --> { (_: dom.MouseEvent) => c.zoomBy(0.8) }, "−"),
      span(styleAttr := s"padding:3px 6px;color:${Tokens.ink2};min-width:38px;text-align:center",
        child.text <-- c.zoomSig.map(z => s"${math.round(z * 100)}%")),
      button(styleAttr := zbtn, title := "Zoom in", onClick --> { (_: dom.MouseEvent) => c.zoomBy(1.25) }, "+"),
      button(styleAttr := zbtn, title := "Reset to 100%", onClick --> { (_: dom.MouseEvent) => c.onResetView() }, "1:1"),
      span(styleAttr := s"width:1px;align-self:stretch;background:${Tokens.borderSoft};margin:0 2px"),
      button(styleAttr := zbtn, dataAttr("export-svg") := "1", title := "Export schematic as SVG",
        onClick --> { _ => c.onExportSvg() }, "svg"),
      button(styleAttr := zbtn, dataAttr("export-png") := "1", title := "Export schematic as PNG (2x)",
        onClick --> { _ => c.onExportPng() }, "png"),
      c.onShare.map(share =>
        button(styleAttr := zbtn, dataAttr("export-snapshot") := "1", title := "Save a self-contained interactive HTML snapshot (opens from file://)",
          onClick --> { _ => share() }, "share")),
    )

  // ── minimap (issue #5) ──
  // Overview + draggable viewport rect; the caller decides when to show it (only
  // when content overflows the viewport at the current zoom) and supplies
  // `centerOnContent` to recenter the main view on a content-space point. The
  // node rects are built once per call (once per geometry); only the viewport
  // rect follows `viewSig` (pan, zoom, viewport size), so panning or zooming
  // never rebuilds the overview (perf).
  def minimap(g: Geom, showLayers: Boolean, viewSig: Signal[((Double, Double), Double, (Double, Double))],
              centerOnContent: (Double, Double) => Unit): Element = {
    // With layers hidden, the overview is the design alone (no debug band).
    val alone  = !showLayers && g.layout.debugY > 0
    val layer  = g.mod.logic.iterator.filter(_.layer).map(_.id).toSet
    val shown  = if (showLayers) g.layout.nodes else g.layout.nodes.filterNot(n => layer(n.id))
    val cw     = if (alone) g.layout.mainW.toDouble else g.layout.w.toDouble
    val ch     = if (alone) g.layout.debugY + 40.0 + g.boxShift.total else g.canvasH
    val maxW = 190.0; val maxH = 140.0
    val s = math.min(maxW / cw, maxH / ch)
    val w = math.max(1.0, cw * s); val h = math.max(1.0, ch * s)
    def centerAt(clientX: Double, clientY: Double, rect: dom.DOMRect): Unit = {
      val mx = math.max(0.0, math.min(w, clientX - rect.left))
      val my = math.max(0.0, math.min(h, clientY - rect.top))
      centerOnContent(mx / s, my / s)
    }
    div(dataAttr("minimap") := "1", dataAttr("pan-block") := "1", dataAttr("no-pan") := "1", title := "Click or drag to pan",
      styleAttr := s"position:absolute;bottom:12px;left:12px;z-index:3;width:${w}px;height:${h}px;background:color-mix(in srgb, ${Tokens.panel} 80%, transparent);-webkit-backdrop-filter:blur(14px) saturate(140%);backdrop-filter:blur(14px) saturate(140%);border:1px solid ${Tokens.border};border-radius:7px;cursor:pointer;overflow:hidden;box-shadow:0 6px 18px rgba(0,0,0,.2)",
      onMouseDown --> { (e: dom.MouseEvent) =>
        if (e.button == 0) {
          e.preventDefault(); e.stopPropagation()
          val rect = e.currentTarget.asInstanceOf[dom.html.Element].getBoundingClientRect()
          centerAt(e.clientX, e.clientY, rect)
          // Apply the latest drag point once per animation frame (flushed on mouseup).
          var next: Option[(Double, Double)] = None
          var frame = 0
          def flush(): Unit = {
            if (frame != 0) dom.window.cancelAnimationFrame(frame)
            frame = 0; next.foreach { case (x, y) => centerAt(x, y, rect) }; next = None
          }
          lazy val moveL: js.Function1[dom.MouseEvent, Unit] = (ev) => {
            next = Some((ev.clientX, ev.clientY))
            if (frame == 0) frame = dom.window.requestAnimationFrame { _ => frame = 0; flush() }
          }
          lazy val upL: js.Function1[dom.MouseEvent, Unit] = (_) => {
            dom.window.removeEventListener("mousemove", moveL); dom.window.removeEventListener("mouseup", upL)
            flush()
          }
          dom.window.addEventListener("mousemove", moveL); dom.window.addEventListener("mouseup", upL)
        }
      },
      shown.map(n => div(styleAttr := s"position:absolute;left:${n.x * s}px;top:${n.y * s}px;width:${math.max(1.0, n.w * s)}px;height:${math.max(1.0, n.h * s)}px;background:${Tokens.ink4};opacity:0.45;border-radius:1px")),
      // Moved by a transform (no layout per pan step); its size changes only
      // with the zoom or the viewport.
      div(dataAttr("minimap-viewport") := "1",
        styleAttr <-- viewSig.map { case ((px, py), z, (vw, vh)) =>
          val rx = (-px / z) * s; val ry = (-py / z) * s
          val rw = (vw / z) * s;  val rh = (vh / z) * s
          s"position:absolute;left:0;top:0;transform:translate(${rx}px,${ry}px);width:${rw}px;height:${rh}px;border:1.5px solid ${Tokens.accent};background:${Tokens.accentLine};box-sizing:border-box;pointer-events:none"
        }),
    )
  }
}
