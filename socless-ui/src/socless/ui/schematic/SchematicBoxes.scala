package socless.ui.schematic

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveElement
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import socless.schema.{PinSide, PortDir}
import socless.js.BrowserLayout.LaidNodeR
import socless.ui.theme._
import socless.ui.model._
import SchematicGeom.{Geom, opGlyph}
import LogicLabels.{OpInRe, stripQ, inRole}

/** The schematic's box/symbol renderers (#216 bullet 2), lifted out of the
  * `Schematic.render` closure so they take the interactive state they need as an
  * explicit [[Ctx]] instead of closing over a dozen render-scope `Var`s. Each
  * function builds one node's Laminar element; the render half in [[Schematic]]
  * maps the laid-out nodes to these.
  *
  * Members are `private[schematic]`. */
private[schematic] object SchematicBoxes {

  /** The interactive state the box renderers touch: the single-select model and
    * the in-box bundle-expansion set. */
  final case class Ctx(selectedVar: Var[Option[String]], expandedPortsVar: Var[Set[String]])

  private def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

  /** Toggle an id in the single-select model: select it, or clear if it is
    * already selected. Shared by every clickable box. */
  private def toggleSelect(sel: Var[Option[String]], id: String): Unit =
    sel.update(s => if (s.contains(id)) None else Some(id))

  // ── register node (issue #188 B1) ──
  // A naked flip-flop symbol (`LogicSymbols.ff`) with WEST `D` / EAST `Q` pins.
  // The clock notch is intrinsic to the symbol, replacing the old box + corner
  // clock triangle. Pins are still rendered via renderPortRows (Approach A) so
  // wire anchoring is unchanged.
  def regBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double, active: Boolean,
             sel: Option[String], hov: Option[String]): ReactiveElement.Base = {
    val c            = g.c
    val selectedHere = sel.exists(s => s == n.id || s.startsWith(n.id + "."))
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    // Draw at the actual laid pin positions (same map the wires use).
    val pinYMap = boxInfo.pinY(c.headerH, c.portPad, c.portStep)
    val inYs   = boxInfo.westRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
    val outY   = boxInfo.eastRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
                   .headOption.getOrElse(boxInfo.boxH / 2)
    val stroke = if (selectedHere) Tokens.accent else Tokens.ink2
    // No D/Q labels: the flip-flop symbol + clock mark convey it (issue #203).
    val westBlank = boxInfo.westRows.map(_.copy(label = ""))
    val eastBlank = boxInfo.eastRows.map(_.copy(label = ""))
    div(
      dataAttr("logic-node") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("symbol") := "ff",
      dataAttr("pan-block") := "1",
      title := s"register (${n.id})",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        s"opacity:${if (active) "1" else "0.18"};overflow:visible;cursor:default;transition:opacity .15s",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      LogicSymbols.ff(n.w.toDouble, boxInfo.boxH, inYs, outY, stroke),
      renderPortRows(ctx, g, n, westBlank, PinSide.West, sel, hov),
      renderPortRows(ctx, g, n, eastBlank, PinSide.East, sel, hov),
    )
  }

  // ── combinational op node (issue #188 B2) ──
  // An instance-style box like a register, but the header badge shows the op
  // glyph (`+`, `<`, `mux`, ...) and the WEST input pins are role-labelled
  // (`sel`/`a`/`b`/`amt`) instead of the raw `~in<i>` encoding.
  def opBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double, active: Boolean,
            sel: Option[String], hov: Option[String]): ReactiveElement.Base = {
    val c            = g.c
    val op           = g.mod.logic.find(_.id == n.id).map(_.op).getOrElse("")
    val glyph        = opGlyph(op)
    val selectedHere = sel.exists(s => s == n.id || s.startsWith(n.id + "."))
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    // Tooltip must match the visible role label (issue #188 B2): the row's
    // `path` is still the raw `<id>~in<i>`, so stripQ alone would show `in<i>`.
    def westTitle(r: RowModel.Row): String = r.path match {
      case OpInRe(_, i) => inRole(op, i.toInt)
      case _            => stripQ(r.path)
    }
    val famTag = LogicSymbols.tag(op)
    // Draw the symbol stubs at the ACTUAL laid pin positions - the same map the
    // wires are routed to - so the symbol meets the wires exactly. The op's
    // EAST output is laid at the center of its inputs by LayoutCore; there is
    // no ad-hoc re-derivation here.
    val pinYMap = boxInfo.pinY(c.headerH, c.portPad, c.portStep)
    val inYs    = boxInfo.westRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
    val outY    = boxInfo.eastRows.iterator.filter(_.isTopLevel).flatMap(r => pinYMap.get(r.path)).toSeq
                    .headOption.getOrElse(boxInfo.boxH / 2)
    // Op nodes carry no visible pin labels: the symbol conveys the operation,
    // and the input role / output type stay available on hover and in the
    // Inspector (issue #203). Blank the row labels but keep the pins + stubs.
    val westBlank = boxInfo.westRows.map(_.copy(label = ""))
    val eastBlank = boxInfo.eastRows.map(_.copy(label = ""))
    val stroke  = if (selectedHere) Tokens.accent else Tokens.ink2
    val symbol  = LogicSymbols.familyOf(op) match {
      case LogicSymbols.Mux   => LogicSymbols.mux(n.w.toDouble, boxInfo.boxH, inYs, outY, stroke)
      case LogicSymbols.Gate  => LogicSymbols.gate(op, negated = false, n.w.toDouble, boxInfo.boxH, inYs, outY, stroke)
      case LogicSymbols.Block => LogicSymbols.opBlock(glyph, n.w.toDouble, boxInfo.boxH, inYs, outY, stroke)
    }
    div(
      dataAttr("logic-node") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("symbol") := famTag,
      dataAttr("pan-block") := "1",
      title := (if (op.nonEmpty) s"op $op (${n.id})" else s"op (${n.id})"),
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        s"opacity:${if (active) "1" else "0.18"};overflow:visible;cursor:default;transition:opacity .15s",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      symbol,
      renderPortRows(ctx, g, n, westBlank, PinSide.West, sel, hov, westTitle),
      renderPortRows(ctx, g, n, eastBlank, PinSide.East, sel, hov),
    )
  }

  // ── boundary stub box ──
  def boundaryStub(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double, active: Boolean): ReactiveElement.Base = {
    val hasLabel = n.ports.exists(_.label.nonEmpty)
    div(
      dataAttr("boundary-stub") := n.id,
      dataAttr("pan-block") := "1",
      title := n.id,
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${n.h}px;" +
        s"opacity:${if (active) "1" else "0.18"};transition:opacity .15s;" +
        s"background:${Tokens.panelAlt};border:1px dashed ${Tokens.border};border-radius:4px;" +
        s"display:flex;align-items:center;justify-content:center;font-family:${Tokens.mono};font-size:9.5px;color:${Tokens.ink3};overflow:hidden;cursor:default",
      onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
      if (!hasLabel) span(styleAttr := "padding:0 6px;text-align:center;word-break:break-all", n.id) else emptyNode,
      n.ports.map { p =>
        val pw = if (p.interface) 8 else 3
        val ph = if (p.interface) 12 else 6
        val isWest = p.side == PinSide.West
        val labelStyle = if (isWest) s"left:${pw + 3}px;text-align:left" else s"right:${pw + 3}px;text-align:right"
        div(
          div(title := stripQ(p.path),
            styleAttr := s"position:absolute;left:${(p.x - n.x) - pw / 2.0}px;top:${(p.y - n.y) - ph / 2.0}px;width:${pw}px;height:${ph}px;" +
              s"background:${if (p.interface) Tokens.accent else Tokens.ink4};border-radius:${if (p.interface) 3 else 1}px;" +
              (if (p.interface) s"outline:1.5px solid ${Tokens.accentLine};outline-offset:1px" else "")),
          if (p.label.nonEmpty) span(
            styleAttr := s"position:absolute;top:${(p.y - n.y) - 5}px;$labelStyle;font-family:${Tokens.mono};font-size:8.5px;" +
              s"color:${if (p.interface) Tokens.ink2 else Tokens.ink3};white-space:nowrap;pointer-events:none;" +
              s"max-width:${math.max(12.0, n.w - pw - 8)}px;overflow:hidden;text-overflow:ellipsis;line-height:10px",
            stripQ(p.label)) else emptyNode,
        )
      },
    )
  }

  // ── collapsed FSM box (issue #210) ──
  // Shares the instance box shape (header + WEST/EAST pin rows) but is
  // non-drillable: it represents a state machine collapsed into a single node,
  // so there is nothing to drill into. Styled distinctly (accent tint + a
  // double border) to read as an FSM, and tagged with `data-fsm` for ui-smoke.
  def fsmBox(ctx: Ctx, g: Geom, n: LaidNodeR, top: Double, active: Boolean,
             sel: Option[String], hov: Option[String]): ReactiveElement.Base = {
    val c            = g.c
    val label        = g.mod.logic.find(_.id == n.id).map(_.label).filter(_.nonEmpty).getOrElse(n.id)
    val selectedHere = sel.exists(s => s == n.id || s.startsWith(n.id + "."))
    val boxInfo      = g.rows.getOrElse(n.id, RowModel.BoxRows(Nil, Nil, n.h.toDouble))
    val borderCol    = if (selectedHere) Tokens.accent else Tokens.accentLine
    // Double border (inset ring) + faint accent tint mark this as an FSM, while
    // selection still promotes the outer border + shadow like other boxes.
    val boxShadow    = if (selectedHere) s"0 0 0 3px ${Tokens.accentBg}, inset 0 0 0 2px ${Tokens.accentLine}"
                       else s"inset 0 0 0 2px ${Tokens.accentLine}"
    div(
      dataAttr("fsm") := n.id,
      dataAttr("box-inst") := n.id,
      dataAttr("pan-block") := "1",
      title := s"FSM ($label)",
      styleAttr := s"position:absolute;left:${n.x}px;top:${top}px;width:${n.w}px;height:${boxInfo.boxH}px;" +
        s"opacity:${if (active) "1" else "0.18"};" +
        s"background:${Tokens.accentBg};border:1px solid $borderCol;border-radius:6px;" +
        s"box-shadow:$boxShadow;cursor:default;overflow:visible;" +
        "display:flex;flex-direction:column;transition:box-shadow .12s,border-color .12s,opacity .15s",
      // header (no drill handler: an FSM box is non-drillable)
      div(
        styleAttr := s"height:${c.headerH}px;box-sizing:border-box;padding:0 10px;border-bottom:1px solid ${Tokens.accentLine};" +
          s"display:flex;align-items:center;gap:6px;background:${Tokens.panelAlt};flex-shrink:0;min-width:0;overflow:hidden;cursor:pointer",
        onClick --> { (_: dom.MouseEvent) => toggleSelect(ctx.selectedVar, n.id) },
        span(styleAttr := s"width:12px;display:inline-flex;justify-content:center;color:${Tokens.accent};font-size:10px", "◇"),
        span(styleAttr := s"font-family:${Tokens.mono};font-size:11.5px;font-weight:600;color:${Tokens.ink};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0", label),
      ),
      renderPortRows(ctx, g, n, boxInfo.westRows, PinSide.West, sel, hov),
      renderPortRows(ctx, g, n, boxInfo.eastRows, PinSide.East, sel, hov),
    )
  }

  // ── port rows inside an instance box ──
  def renderPortRows(ctx: Ctx, g: Geom, n: LaidNodeR, rows: Seq[RowModel.Row], side: String,
                     sel: Option[String], hov: Option[String],
                     titleOf: RowModel.Row => String = r => stripQ(r.path)): Seq[ReactiveElement.Base] = {
    val c = g.c
    val isWest = side == PinSide.West
    def togglePort(p: String): Unit = ctx.expandedPortsVar.update(s => if (s(p)) s - p else s + p)
    // Draw each pin where the layout actually placed it (row.pinYBaked) so the
    // dots line up with the wires and symbol stubs - including an op's output
    // pin, laid at the center of its inputs. Only fall back to index positions
    // once an expansion has re-flowed the rows.
    val expanded = rows.exists(!_.isTopLevel)
    rows.zipWithIndex.map { case (row, rowIdx) =>
      val rowY = if (!expanded && row.isTopLevel) row.pinYBaked
                 else c.headerH + c.portPad + rowIdx * c.portStep + c.portStep / 2
      val indentPx = row.depth * 8
      if (row.isGroupHeader) {
        val labelSide = if (isWest) s"left:${12 + indentPx}px" else s"right:${12 + indentPx}px"
        div(
          dataAttr("bundle-group") := row.path,
          dataAttr("bundle-group-dir") := (if (isWest) "in" else "out"),
          dataAttr("pan-block") := "1",
          styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;display:flex;align-items:center;height:16px;" +
            s"font-family:${Tokens.mono};font-size:8.5px;font-weight:600;color:${Tokens.ink3};white-space:nowrap;pointer-events:none",
          span(stripQ(row.label)),
        )
      } else if (row.isTopLevel) {
        val isInterface = row.isExpandable
        val pw = if (isInterface) 8 else 3
        val ph = if (isInterface) 12 else 6
        val pinAbsX = if (isWest) 0.0 else n.w.toDouble
        val pinSel = sel.exists(s => row.path.startsWith(s) || s.startsWith(row.path))
        val pinHov = !pinSel && hov.exists(s => row.path.startsWith(s) || s.startsWith(row.path))
        val pinBg  = if (pinSel) Tokens.accent else if (pinHov) Tokens.ink else if (isInterface) Tokens.accent else Tokens.ink3
        val labelSide = if (isWest) s"left:${pw + 4 + indentPx}px" else s"right:${pw + 4 + indentPx}px"
        div(
          div(title := titleOf(row),
            styleAttr := s"position:absolute;left:${pinAbsX - pw / 2.0}px;top:${rowY - ph / 2.0}px;width:${pw}px;height:${ph}px;" +
              s"background:$pinBg;border-radius:${if (isInterface) 3 else 1}px;" +
              (if (isInterface) s"outline:1.5px solid ${Tokens.accentLine};outline-offset:1px;" else "") + "transition:background .12s"),
          div(
            dataAttr("pan-block") := "1",
            if (row.isExpandable && !row.locked) dataAttr("bundle-toggle") := row.path else emptyMod,
            styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;width:${math.max(24.0, n.w / 2.0 - (pw + 4 + indentPx) - 4)}px;" +
              s"display:flex;align-items:center;flex-direction:${if (isWest) "row" else "row-reverse"};justify-content:flex-start;gap:3px;height:16px;" +
              s"font-family:${Tokens.mono};font-size:8.5px;color:${Tokens.ink2};white-space:nowrap;overflow:hidden;" +
              s"pointer-events:${if (row.isExpandable && !row.locked) "auto" else "none"};cursor:${if (row.isExpandable && !row.locked) "pointer" else "default"}",
            if (row.isExpandable && !row.locked) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod,
            if (row.isExpandable && !row.locked) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink2};font-size:8px;line-height:1",
              child.text <-- ctx.expandedPortsVar.signal.map(s => if (s(row.path)) "−" else "+")) else emptyNode,
            span(title := titleOf(row), styleAttr := "overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:1", stripQ(row.label)),
          ),
        )
      } else {
        val labelSide = if (isWest) s"left:${12 + indentPx}px" else s"right:${12 + indentPx}px"
        val pinAbsX = if (isWest) 0.0 else n.w.toDouble
        div(
          if (row.drawPin) div(
            styleAttr := s"position:absolute;left:${pinAbsX - 1.5}px;top:${rowY - 3}px;width:3px;height:6px;" +
              s"background:${Tokens.ink3};border-radius:1px"
          ) else emptyNode,
          dataAttr("pan-block") := "1",
          if (row.isExpandable && !row.locked) dataAttr("bundle-toggle") := row.path else emptyMod,
          styleAttr := s"position:absolute;top:${rowY - 8}px;$labelSide;width:${math.max(24.0, n.w / 2.0 - (12 + indentPx) - 4)}px;" +
            s"display:flex;align-items:center;flex-direction:${if (isWest) "row" else "row-reverse"};justify-content:flex-start;gap:3px;height:16px;" +
            s"font-family:${Tokens.mono};font-size:8px;color:${Tokens.ink3};white-space:nowrap;overflow:hidden;" +
            s"pointer-events:${if (row.isExpandable && !row.locked) "auto" else "none"};cursor:${if (row.isExpandable && !row.locked) "pointer" else "default"}",
          if (row.isExpandable && !row.locked) onClick --> { (e: dom.MouseEvent) => e.stopPropagation(); togglePort(row.path) } else emptyMod,
          if (row.isExpandable && !row.locked) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:8px",
            child.text <-- ctx.expandedPortsVar.signal.map(s => if (s(row.path)) "−" else "+")) else emptyNode,
          span(title := titleOf(row), styleAttr := "overflow:hidden;text-overflow:ellipsis;min-width:0;flex-shrink:1", stripQ(row.label)),
          row.dir match {
            case Some(d) => span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:7px", if (d == PortDir.Out) "→" else "←")
            case None    => emptyNode
          },
          if (row.width > 0) span(styleAttr := s"flex-shrink:0;color:${Tokens.ink4};font-size:7px", s"${row.width}b") else emptyNode,
        )
      }
    }
  }

  // ── wire glyphs ──

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
