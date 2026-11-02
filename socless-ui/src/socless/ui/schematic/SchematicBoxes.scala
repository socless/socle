package socless.ui.schematic

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveElement
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import socless.schema.{PinSide, PortDir}
import socless.layout.PortModel
import socless.js.BrowserLayout.LaidNodeR
import socless.ui.theme._
import socless.ui.model._
import SchematicGeom.{Geom, opGlyph}
import LogicLabels.stripQ

/** The schematic's box/symbol renderers (#216 bullet 2), lifted out of the
  * `Schematic.render` closure so they take the interactive state they need as an
  * explicit [[Ctx]] instead of closing over a dozen render-scope `Var`s. Each
  * function builds one node's Laminar element; the render half in [[Schematic]]
  * maps the laid-out nodes to these.
  *
  * Members are `private[schematic]`. */
private[schematic] object SchematicBoxes {

  /** The interactive state the box renderers touch: the single-select model and
    * the in-box bundle expansions of the module in view (`expandedSig`, toggled
    * with `togglePort`). `hl` registers each box and pin a
    * highlight can touch: selection, hover, dimming and the colorized fill
    * (issue #266) are classes on them ([[Highlight]]), never a re-render.
    * `openFsm` opens an FSM block's state diagram, `openBlock` enters a logic block. */
  final case class Ctx(selectedVar: Var[Option[String]], expandedSig: Signal[Set[String]], togglePort: String => Unit,
                       hl: Highlight.Registry, openFsm: String => Unit = _ => (), openBlock: String => Unit = _ => ())

  private def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

  /** Toggle an id in the single-select model: select it, or clear if it is
    * already selected. Shared by every clickable box. */
  private def toggleSelect(sel: Var[Option[String]], id: String): Unit =
    sel.update(s => if (s.contains(id)) None else Some(id))

  /** Register `el` as a box a highlight touches, and return it. */
  private def reg[E <: ReactiveElement.Base](ctx: Ctx, id: String, kind: Highlight.BoxKind, el: E): E = {
    ctx.hl.box(id, kind, el.ref); el
  }

  /** The resting looks of a framed box as custom properties (see
    * [[Highlight.css]]): border colour, shadows (resting, clock peer, selected),
    * fill, and the colorized fill (issue #266). */
  def boxVars(kind: String, border: String, rest: String, peer: String, sel: String, fill: String): String =
    s"--bk0:$border;--bs0:$rest;--bsp:$peer;--bs1:$sel;--bg0:$fill;--bf:${SchematicColors.boxFill(kind)};"
  val RestShadow = "0 1px 2px rgba(0,0,0,.04)"
  val SelShadow  = s"0 0 0 3px ${Tokens.accentBg}"

  // ── register node (issue #188 B1) ──
  // A naked flip-flop symbol (`LogicSymbols.ff`) with WEST `D` / EAST `Q` pins.
  // The clock notch is intrinsic to the symbol, replacing the old box + corner
  // clock triangle. Pins are still rendered via renderPortRows (Approach A) so
  // wire anchoring is unchanged.
  def regBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val c            = g.c
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    // Draw at the actual laid pin positions (same map the wires use).
    val pinYMap = boxInfo.pinY(c.headerH, c.portPad, c.portStep)
    val inYs   = boxInfo.westRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
    val outY   = boxInfo.eastRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
                   .headOption.getOrElse(boxInfo.boxH / 2)
    // The symbol draws in `--sym`: ink2, accent when selected (Highlight.css).
    val stroke = "var(--sym)"
    // No D/Q labels: the flip-flop symbol + clock mark convey it (issue #203).
    val westBlank = boxInfo.westRows.map(_.copy(label = ""))
    val eastBlank = boxInfo.eastRows.map(_.copy(label = ""))
    reg(ctx, n.id, Highlight.BoxKind.Selectable, div(
      cls := "scl-sym",
      dataAttr("logic-node") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("symbol") := "ff",
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        s"overflow:visible;cursor:pointer;transition:opacity .15s;--bf:${SchematicColors.boxFill(n.kind)}",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      LogicSymbols.ff(n.w.toDouble, boxInfo.boxH, inYs, outY, stroke),
      renderPortRows(ctx, g, n, westBlank, PinSide.West, glyphs = false),
      renderPortRows(ctx, g, n, eastBlank, PinSide.East, glyphs = false),
    ))
  }

  // ── combinational op node (issue #188 B2) ──
  // An instance-style box like a register, but the header badge shows the op
  // glyph (`+`, `<`, `mux`, ...) and the WEST input pins are role-labelled
  // (`sel`/`a`/`b`/`amt`) instead of the raw `~in<i>` encoding.
  def opBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val c            = g.c
    val op           = g.logicById.get(n.id).map(_.op).getOrElse("")
    val glyph        = opGlyph(op)
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    val famTag = LogicSymbols.tag(op)
    // Draw the symbol stubs at the ACTUAL laid pin positions - the same map the
    // wires are routed to - so the symbol meets the wires exactly. The op's
    // EAST output is laid at the center of its inputs by LayoutCore; there is
    // no ad-hoc re-derivation here.
    val pinYMap = boxInfo.pinY(c.headerH, c.portPad, c.portStep)
    val inYs    = boxInfo.westRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
    // The mux selector is identified by its path (`~in0`), never by position,
    // so the top stub always binds the real selector (issue #246).
    val selY = boxInfo.westRows.iterator
      .filter(r => r.isTopLevel && r.path.endsWith("~in0"))
      .flatMap(r => pinYMap.get(r.path)).toSeq.headOption
    val outY    = boxInfo.eastRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
                    .headOption.getOrElse(boxInfo.boxH / 2)
    // Op nodes carry no visible pin labels: the symbol conveys the operation,
    // and the input role / output type stay available on the tooltip card and
    // in the Inspector (issue #203; the card names an input by its role,
    // issue #188 B2). Blank the row labels but keep the pins + stubs.
    val westBlank = boxInfo.westRows.map(_.copy(label = ""))
    val eastBlank = boxInfo.eastRows.map(_.copy(label = ""))
    val stroke  = "var(--sym)" // ink2, accent when selected (Highlight.css)
    val symbol  = LogicSymbols.familyOf(op) match {
      case LogicSymbols.Mux   => LogicSymbols.mux(n.w.toDouble, boxInfo.boxH, inYs, selY, outY, stroke)
      case LogicSymbols.Gate  => LogicSymbols.gate(op, negated = false, n.w.toDouble, boxInfo.boxH, inYs, outY, stroke)
      case LogicSymbols.Block => LogicSymbols.opBlock(glyph, n.w.toDouble, boxInfo.boxH, inYs, outY, stroke)
    }
    reg(ctx, n.id, Highlight.BoxKind.Selectable, div(
      cls := "scl-sym",
      dataAttr("logic-node") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("symbol") := famTag,
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        s"overflow:visible;cursor:pointer;transition:opacity .15s;--bf:${SchematicColors.boxFill(n.kind)}",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      symbol,
      renderPortRows(ctx, g, n, westBlank, PinSide.West, glyphs = false),
      renderPortRows(ctx, g, n, eastBlank, PinSide.East, glyphs = false),
    ))
  }

  // ── boundary stub box ──
  def boundaryStub(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val hasLabel = n.ports.exists(_.label.nonEmpty)
    val rowOf    = g.rows.get(n.id).fold(Map.empty[String, RowModel.Row])(br => (br.westRows ++ br.eastRows).map(r => r.path -> r).toMap)
    val stub = reg(ctx, n.id, Highlight.BoxKind.Stub, div(
      cls := "scl-b",
      dataAttr("boundary-stub") := n.id,
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${n.h}px;" +
        "transition:opacity .15s;" + boxVars(n.kind, Tokens.border, "none", "none", "none", Tokens.panelAlt) +
        "border-width:1px;border-style:dashed;border-radius:4px;" +
        s"display:flex;align-items:center;justify-content:center;font-family:${Tokens.mono};font-size:var(--sch-type);color:${Tokens.ink2};overflow:hidden;cursor:pointer",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      if (!hasLabel) span(styleAttr := "padding:0 6px;text-align:center;word-break:break-all", n.id) else emptyNode,
      n.ports.map { p =>
        val pw = if (p.interface) 8 else 3
        val ph = if (p.interface) 12 else 6
        val isWest = p.side == PinSide.West
        // The stub's rows (#332): an open stub shows its fields in place, each
        // expandable row with a toggle, as a box does.
        val row     = rowOf.get(p.path)
        val depth   = row.fold(0)(_.depth)
        val toggles = row.exists(_.isExpandable)
        val label   = row.filter(_.depth > 0).fold(p.label)(_.label)
        val inset   = pw + 3 + depth * 8
        val labelStyle = if (isWest) s"left:${inset}px;text-align:left;flex-direction:row" else s"right:${inset}px;text-align:right;flex-direction:row-reverse"
        div(
          dataAttr("row") := p.path, dataAttr("row-side") := p.side,
          if (g.wiredPins(p.path)) emptyMod else cls := "scl-nc",
          // No native title: the stub's card names its port.
          div(
            styleAttr := s"position:absolute;left:${(p.x - n.x) - pw / 2.0}px;top:${(p.y - n.y) - ph / 2.0}px;width:${pw}px;height:${ph}px;" +
              s"background:${if (p.interface) Tokens.accent else Tokens.ink4};border-radius:${if (p.interface) 3 else 1}px;" +
              (if (p.interface) s"outline:1.5px solid ${Tokens.accentLine};outline-offset:1px" else "")),
          // Only the +/- mark toggles: a click elsewhere on a stub selects it.
          if (label.nonEmpty || toggles) div(
            cls := "scl-stubl",
            styleAttr := s"position:absolute;top:${(p.y - n.y) - 5}px;$labelStyle;display:flex;align-items:center;gap:3px;" +
              s"font-family:${Tokens.mono};font-size:var(--sch-label);" +
              s"color:${if (p.interface) Tokens.ink else Tokens.ink2};white-space:nowrap;pointer-events:none;" +
              s"max-width:${math.max(12.0, n.w - inset - 5)}px;overflow:hidden;line-height:10px",
            if (toggles) span(
              dataAttr("bundle-toggle") := p.path, dataAttr("pan-block") := "1",
              dataAttr("open") <-- ctx.expandedSig.map(s => if (s(p.path)) "1" else "0"),
              styleAttr := s"flex-shrink:0;color:${Tokens.ink2};font-size:var(--sch-mark);pointer-events:auto;cursor:pointer;padding:0 2px",
              onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); ctx.togglePort(p.path) },
              child.text <-- ctx.expandedSig.map(s => if (s(p.path)) "−" else "+")) else emptyNode,
            span(styleAttr := "overflow:hidden;text-overflow:ellipsis;min-width:0", stripQ(label)),
          ) else emptyNode,
        )
      },
    ))
    // A followed net lights the stub of a port it crosses (its id is the path).
    ctx.hl.mark(n.id, stub.ref)
    stub
  }

  // ── collapsed FSM box (issue #210) ──
  // Shares the instance box shape (header + WEST/EAST pin rows) but is
  // non-drillable: it represents a state machine collapsed into a single node,
  // so there is nothing to drill into. Styled distinctly (accent tint + a
  // double border) to read as an FSM, and tagged with `data-fsm` for ui-smoke.
  // A block that carries a state diagram opens it on a double-click or from
  // the header's open control (`data-fsm-open`).
  def fsmBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val c            = g.c
    val node         = g.logicById.get(n.id)
    val label        = node.map(_.label).filter(_.nonEmpty).getOrElse(n.id)
    val hasDiagram   = node.exists(_.fsm.isDefined)
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    // Double border (inset ring) + faint accent tint mark this as an FSM, while
    // selection still promotes the outer border + shadow like other boxes.
    val ring         = s"inset 0 0 0 2px ${Tokens.accentLine}"
    reg(ctx, n.id, Highlight.BoxKind.Selectable, div(
      cls := "scl-b",
      dataAttr("fsm") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        boxVars(n.kind, Tokens.accentLine, ring, ring, s"$SelShadow, $ring", Tokens.accentBg) +
        // zoom-in where a double-click opens the state diagram.
        s"border-width:1px;border-style:solid;border-radius:6px;cursor:${if (hasDiagram) "zoom-in" else "default"};overflow:visible;" +
        "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
      if (hasDiagram) onDblClick --> { (_: dom.MouseEvent) => ctx.openFsm(n.id) } else emptyMod,
      // header (no drill handler: an FSM box is non-drillable)
      div(
        cls := "scl-hd",
        styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px solid ${Tokens.accentLine};" +
          s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:${if (hasDiagram) "zoom-in" else "pointer"}",
        // The second click of a double-click (detail 2) opens the diagram
        // instead of toggling the selection back off.
        onClick --> { (e: dom.MouseEvent) =>
          if (hasDiagram && e.detail >= 2) ctx.openFsm(n.id) else toggleSelect(ctx.selectedVar, n.id) },
        span(styleAttr := s"width:12px;display:inline-flex;justify-content:center;color:${Tokens.accent};font-size:10px", "◇"),
        span(styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-name);font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", label),
        if (hasDiagram) span(dataAttr("fsm-open") := n.id, title := "Open the state diagram",
          tabIndex := 0, role := "button",
          styleAttr := s"margin-left:auto;flex-shrink:0;font-family:${Tokens.mono};font-size:9px;color:${Tokens.accent};cursor:pointer;padding:0 2px",
          onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); ctx.openFsm(n.id) },
          onKeyDown --> { (e: dom.KeyboardEvent) =>
            if (e.key == "Enter" || e.key == " ") { e.preventDefault(); e.stopPropagation(); ctx.openFsm(n.id) } },
          onDblClick --> { (e: dom.MouseEvent) => e.stopPropagation() },
          "↵") else emptyNode,
      ),
      renderPortRows(ctx, g, n, boxInfo.westRows, PinSide.West),
      renderPortRows(ctx, g, n, boxInfo.eastRows, PinSide.East),
      overviewName(n.id, label, n.w.toDouble),
    ))
  }

  // ── logic / generator block (captured view) ──
  // Same shape as the FSM box (header + WEST/EAST pin rows): a module's logic
  // grouped into one node, or one generator construct. A double-click (or its
  // `↵`) enters it when its body is stored. Neutral
  // border, the `block` tint when components are colorized, a `{ }` glyph.
  // Tagged `data-block` (and `data-block-label` on its name) for ui-smoke.
  def blockBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val c            = g.c
    val label        = g.logicById.get(n.id).map(_.label).filter(_.nonEmpty).getOrElse(n.id)
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    val opens        = g.design.regions.contains(n.id)
    reg(ctx, n.id, Highlight.BoxKind.Selectable, div(
      cls := "scl-b",
      dataAttr("block") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        boxVars(n.kind, Tokens.border, RestShadow, RestShadow, SelShadow, Tokens.panel) +
        s"border-width:1px;border-style:solid;border-radius:6px;cursor:${if (opens) "zoom-in" else "default"};overflow:visible;" +
        "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
      if (opens) onDblClick --> { (_: dom.MouseEvent) => ctx.openBlock(n.id) } else emptyMod,
      div(
        cls := "scl-hd",
        styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px solid ${Tokens.borderSoft};" +
          s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:${if (opens) "zoom-in" else "pointer"}",
        onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
        span(styleAttr := s"display:inline-flex;justify-content:center;color:${Tokens.ink3};font-family:${Tokens.mono};font-size:10px;white-space:nowrap", "{ }"),
        span(dataAttr("block-label") := n.id,
          styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-name);font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", label),
        if (opens) span(dataAttr("block-open") := n.id, title := "Open the block (double-click)",
          styleAttr := s"margin-left:auto;font-family:${Tokens.mono};font-size:9px;color:${Tokens.accent};cursor:pointer",
          onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); ctx.openBlock(n.id) }, "↵") else emptyNode,
      ),
      renderPortRows(ctx, g, n, boxInfo.westRows, PinSide.West),
      renderPortRows(ctx, g, n, boxInfo.eastRows, PinSide.East),
      overviewName(n.id, label, n.w.toDouble),
    ))
  }

  // ── memory node (issue #205) ──
  // Instance-style box (header + WEST/EAST pin rows) marked with a stacked-rows
  // glyph and a RAM/SRAM depth×elem tag. Pins keep their role labels (R0 addr,
  // R0 data, ...). Tagged `data-mem` for ui-smoke.
  def memBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double): ReactiveElement.Base = {
    val c            = g.c
    val info         = g.logicById.get(n.id).flatMap(_.memInfo)
    val tag          = info.map(mi => s"${if (mi.sync) "SRAM" else "RAM"} ${mi.depth}x${mi.elem}").getOrElse("mem")
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    reg(ctx, n.id, Highlight.BoxKind.Selectable, div(
      cls := "scl-b",
      dataAttr("mem") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        boxVars(n.kind, Tokens.border, RestShadow, RestShadow, SelShadow, Tokens.panel) +
        "border-width:1px;border-style:solid;border-radius:6px;cursor:default;overflow:visible;" +
        "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
      div(
        cls := "scl-hd",
        styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px solid ${Tokens.borderSoft};" +
          s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:pointer",
        onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
        span(styleAttr := s"width:12px;display:inline-flex;justify-content:center;color:${Tokens.ink3};font-size:11px", "▤"),
        span(styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-name);font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", n.id),
        div(styleAttr := "margin-left:auto;flex-shrink:0;display:flex;align-items:center;gap:6px",
          span(styleAttr := s"font-family:${Tokens.mono};font-size:var(--sch-type);color:${Tokens.ink2};white-space:nowrap;max-width:120px;overflow:hidden;text-overflow:ellipsis", tag)),
      ),
      renderPortRows(ctx, g, n, boxInfo.westRows, PinSide.West),
      renderPortRows(ctx, g, n, boxInfo.eastRows, PinSide.East),
      overviewName(n.id, n.id, n.w.toDouble),
    ))
  }

  private val GroundType = """^[SU]Int<\d+>$""".r

  // ── overview (#334) ──
  /** Below this on-screen pin text size (px), the schematic shows its overview. */
  val OverviewPx = 7.0
  /** The schematic's pin label size (px), from the label size setting. */
  def labelPx(): Double =
    Option(dom.document.documentElement.getAttribute("data-labels")).getOrElse("") match {
      case "large" => 11.5
      case "xl"    => 14.0
      case _       => 9.5
    }
  /** A box's name centred at a fixed screen size, shown in the overview only;
    * never wider than its box (`boxW`), so a tiny box gets a smaller name. */
  def overviewName(id: String, text: String, boxW: Double): ReactiveElement.Base = {
    val fit = math.max(1.0, (boxW - 32) / (math.max(1, text.length) * 0.62))
    div(dataAttr("box-overview-name") := id, cls := "scl-ovn",
      styleAttr := s"font-size:min(calc(14px / var(--z)), ${fit}px)", text)
  }

  // ── port rows inside a box ──
  // Every row carries `data-row` (its port path) and `data-row-side`: a test
  // hook for the smoke's bundle-toggle check, never styled.
  // What a pin is (direction, type, width, an op input's role) is on the
  // tooltip card (Tooltip), not in native titles.
  // Each row is a laid pin (#332), drawn where the layout placed it, its
  // children opened in place below it and hung from a guide line; a row no
  // wire ends on is faint. The pin is a glyph (#334): a signal a triangle
  // pointing the way it flows, a bundle a tab (out: closed, down: open) that
  // opens it, a Vec of signals two stacked tabs. `glyphs = false` keeps plain
  // dots, for logic symbols that draw their own.
  /** An instance's clk and rst pins, in the rows the layout keeps for them at
    * the bottom of its West side (no wire): the flip-flop chevron in its clock
    * domain's colour, and a ring marked `(async)` for an AsyncReset. With a
    * domain colour, a stripe along the left edge spans these two rows, under
    * the pins. A coloured `clk` pin lights its domain on a click (`onDomain`). */
  def clockPins(id: String, boxH: Double, clk: Boolean, rst: Boolean, async: Boolean,
                clock: String, reset: String, domain: Option[String], onDomain: () => Unit): Seq[ReactiveElement.Base] = {
    val step  = socless.layout.LayoutCore.PortStep
    val lastY = boxH - socless.layout.LayoutCore.PortPad - step / 2
    val rstY  = lastY
    val clkY  = if (rst) lastY - step else lastY
    val ink   = domain.getOrElse(Tokens.ink)
    def label(y: Double, color: String, parts: Modifier[HtmlElement]*) =
      div(cls := "scl-ck", styleAttr := s"position:absolute;top:${y - 8}px;left:14px;height:16px;display:flex;align-items:center;gap:5px;" +
        s"font-family:${Tokens.mono};font-size:var(--sch-label);color:$color;white-space:nowrap;pointer-events:none", parts)
    // A click target over the pin's row (chevron and name) when it has a domain colour.
    val hit = domain.map(_ => div(dataAttr("clk-domain") := clock, dataAttr("pan-block") := "1",
      styleAttr := s"position:absolute;left:-4px;top:${clkY - 9}px;width:44px;height:18px;cursor:pointer;z-index:1",
      onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); onDomain() },
      onDblClick --> { (e: dom.MouseEvent) => e.stopPropagation() }))
    val clkEl = Option.when(clk)(div(dataAttr("clk-pin") := id,
      title := (if (clock.nonEmpty) s"clock: $clock" + (if (domain.isDefined) " (click: light this domain)" else "") else "clock input"),
      svg.svg(svg.width := "9", svg.height := "12",
        svg.style := s"position:absolute;left:-1px;top:${clkY - 6}px;overflow:visible;pointer-events:none",
        svg.path(svg.d := "M0.8 1 L7.5 6 L0.8 11", svg.fill := "none", svg.stroke := ink, svg.strokeWidth := "1.6")),
      label(clkY, domain.getOrElse(Tokens.ink2), "clk")))
    val rstEl = Option.when(rst)(div(dataAttr("rst-pin") := id, dataAttr("async") := (if (async) "1" else "0"),
      title := (if (reset.nonEmpty) s"reset: $reset" else "reset input") + (if (async) " (asynchronous)" else ""),
      div(styleAttr := s"position:absolute;left:-4px;top:${rstY - 4}px;width:8px;height:8px;box-sizing:border-box;border:1.5px solid ${Tokens.ink};border-radius:50%;background:${Tokens.panel};pointer-events:none"),
      label(rstY, Tokens.ink2, "rst", if (async) span(styleAttr := s"color:${Tokens.ink4};font-size:var(--sch-mark)", "(async)") else emptyNode)))
    val stripe = domain.filter(_ => clk).map { col =>
      val bottom = if (rst) rstY else clkY
      div(dataAttr("box-domain") := clock,
        styleAttr := s"position:absolute;left:-1px;top:${clkY - 10}px;height:${bottom - clkY + 20}px;width:3px;" +
          s"background:$col;border-radius:0 2px 2px 0;pointer-events:none")
    }
    stripe.toSeq ++ clkEl.toSeq ++ rstEl.toSeq ++ hit.filter(_ => clk).toSeq
  }

  def renderPortRows(ctx: Ctx, g: Geom, n: LaidNodeR, rows: Seq[RowModel.Row], side: String,
                     glyphs: Boolean = true): Seq[ReactiveElement.Base] = {
    val isWest = side == PinSide.West
    def togglePort(p: String): Unit = ctx.togglePort(p)
    val guides: Seq[ReactiveElement.Base] = rows.zipWithIndex.flatMap { case (r, i) =>
      val desc = rows.drop(i + 1).takeWhile(_.depth > r.depth)
      Option.when(r.open && desc.nonEmpty) {
        val x = if (isWest) 9.0 + r.depth * 8 else n.w - 9.0 - r.depth * 8
        div(dataAttr("row-guide") := r.path,
          styleAttr := s"position:absolute;left:${x}px;top:${r.pinYBaked + 8}px;width:1px;height:${math.max(0.0, desc.last.pinYBaked - r.pinYBaked - 8)}px;" +
            s"background:${Tokens.border};pointer-events:none")
      }
    }
    guides ++ rows.map { row =>
      val rowY     = row.pinYBaked
      val indentPx = row.depth * 8
      val toggles  = row.isExpandable
      val top      = row.isTopLevel
      val faint    = if (g.wiredPins(row.path)) emptyMod else cls := "scl-nc"
      val pinAbsX  = if (isWest) 0.0 else n.w.toDouble
      val isVec    = PortModel.isVecOfSignals(row.bundleKey, g.design)
      val shape =
        if (!glyphs) ""
        else if (isVec) " vec"
        else if (toggles) s" bun ${if (isWest) "w" else "e"}${if (row.open) " open" else ""}"
        else s" sig ${if (flowPointsRight(row, isWest)) "r" else "l"}"
      val (pw, ph) = if (!glyphs) (if (top && toggles) (10.0, 10.0) else (8.0, 8.0)) else if (toggles) (12.0, 12.0) else (9.0, 10.0)
      val base = if (top) (if (toggles) "scl-pin if" else "scl-pin") else "scl-fpin"
      // The glyph's colour is a class: selected, hovered, interface, plain
      // (Highlight.css), so a highlight never re-renders the row. A bundle's
      // glyph opens it, as its label row does (its card says "Click: expand or collapse").
      val dot = div(
        cls := base + shape,
        if (top) dataAttr("pin") := row.path else dataAttr("pin-field") := row.path,
        if (toggles) dataAttr("pan-block") := "1" else emptyMod,
        styleAttr := s"position:absolute;left:${pinAbsX - pw / 2}px;top:${rowY - ph / 2}px;width:${pw}px;height:${ph}px;" +
          (if (glyphs) "" else "border-radius:50%;") +
          (if (toggles) "pointer-events:auto;cursor:pointer;" else "") +
          "transition:background .12s",
        if (toggles) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod)
      if (top) ctx.hl.pin(row.path, dot.ref) else ctx.hl.mark(row.path, dot.ref)
      // The name, then the type lighter (a top row's label is `name: type`), or a
      // field's width; a closed Decoupled `bits` row sums up its fields.
      val (nameText, typeText) = row.label.split(": ", 2) match {
        case Array(a, b) => (a, b)
        case Array(a)    => (a, "")
      }
      val summary =
        if (row.bundleKey.endsWith("#bits") && !row.open)
          g.design.bundles.get(row.bundleKey.stripSuffix("#bits")).fold("")(_.fields.map(f => s"${f.name} ${f.width}b").mkString(", "))
        else ""
      // A signal's width says its type (`8b`, not `UInt<8> 8b`).
      val shownType = if (row.width > 0 && GroundType.matches(typeText)) "" else typeText
      val extra = Seq(shownType, if (row.width > 0) s"${row.width}b" else "", summary).filter(_.nonEmpty).mkString(" ")
      val name = span(cls := (if (top) "" else "scl-rl"),
        styleAttr := s"overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:${if (extra.nonEmpty) 0 else 1};text-align:${if (isWest) "left" else "right"}",
        stripQ(nameText))
      if (!top) ctx.hl.row(row.path, name.ref)
      val inset = 9 + indentPx
      val labelSide = if (isWest) s"left:${inset}px" else s"right:${inset}px"
      div(
        dataAttr("row") := row.path, dataAttr("row-side") := side, faint,
        dot,
        div(
          cls := "scl-rlab",
          dataAttr("pan-block") := "1",
          if (toggles) dataAttr("bundle-toggle") := row.path else emptyMod,
          // Test hook: whether the bundle is open (no + or − text to read, #334).
          if (toggles) dataAttr("open") <-- ctx.expandedSig.map(s => if (s(row.path)) "1" else "0") else emptyMod,
          styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;width:${math.max(24.0, n.w / 2.0 - inset - 4)}px;" +
            s"display:flex;align-items:center;flex-direction:${if (isWest) "row" else "row-reverse"};justify-content:flex-start;gap:4px;height:16px;" +
            s"font-family:${Tokens.mono};font-size:var(--sch-label);color:${if (top) Tokens.ink else Tokens.ink2};white-space:nowrap;overflow:hidden;" +
            s"pointer-events:${if (toggles) "auto" else "none"};cursor:${if (toggles) "pointer" else "default"}",
          if (toggles) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod,
          name,
          if (extra.nonEmpty) span(cls := "scl-rt",
            styleAttr := s"overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:1;color:${Tokens.ink3};font-size:var(--sch-mark)", extra)
          else emptyNode,
        ),
      )
    }
  }

  // ── wire glyphs ──

  // Signal-flow direction for a row: true = points right (east). Out flows out
  // of the box, In (and any non-Out dir) flows in; `dir` None falls back to the
  // side default: WEST input points right, EAST output points right (both right).
  private def flowPointsRight(row: RowModel.Row, isWest: Boolean): Boolean =
    row.dir match {
      case Some(PortDir.Out) => !isWest      // out: EAST points right (out), WEST points left
      case Some(_)           => isWest       // in: WEST points right (in), EAST points left
      case None              => true         // side default: WEST input and EAST output both flow right
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
}
