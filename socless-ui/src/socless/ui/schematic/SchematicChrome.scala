package socless.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socless.schema.ModuleDef
import socless.js.BrowserLayout.LaidOut
import socless.ui.theme._
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
    onShare:       () => Unit,
  )

  // ── chrome (top-left chips) ──
  def chrome(moduleSig: Signal[Option[ModuleDef]], filterVar: Var[String],
             matchSig: Signal[Option[(Int, Int)]] = Val(None)): Element =
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
          // Live match count "N / M" while filtering (#276).
          child.maybe <-- matchSig.map {
            case Some((m, t)) => Some(span(dataAttr("filter-count") := "1",
              styleAttr := s"color:${if (m == 0) Tokens.warn else Tokens.ink3};font-variant-numeric:tabular-nums;flex-shrink:0", s"$m / $t"))
            case None => None
          },
          child.maybe <-- filterVar.signal.map(f => if (f.nonEmpty)
            Some(button(title := "Clear filter (Esc)", styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:13px;line-height:1;padding:0",
              onClick --> { _ => filterVar.set("") }, "×")) else None)),
      ),
    )

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
      button(styleAttr := zbtn, dataAttr("export-snapshot") := "1", title := "Save a self-contained interactive HTML snapshot (opens from file://)",
        onClick --> { _ => c.onShare() }, "share"),
    )

  // ── minimap (issue #5) ──
  // Overview + draggable viewport rect; the caller decides when to show it (only
  // when content overflows the viewport at the current zoom) and supplies
  // `centerOnContent` to recenter the main view on a content-space point.
  def minimap(g: Geom, px: Double, py: Double, z: Double, vw: Double, vh: Double,
              centerOnContent: (Double, Double) => Unit): Element = {
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
      styleAttr := s"position:absolute;bottom:12px;left:12px;z-index:3;width:${w}px;height:${h}px;background:color-mix(in srgb, ${Tokens.panel} 80%, transparent);-webkit-backdrop-filter:blur(14px) saturate(140%);backdrop-filter:blur(14px) saturate(140%);border:1px solid ${Tokens.border};border-radius:7px;cursor:pointer;overflow:hidden;box-shadow:0 6px 18px rgba(0,0,0,.2)",
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
}
