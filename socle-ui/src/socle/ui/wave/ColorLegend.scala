package socle.ui.wave

import com.raquo.laminar.api.L._
import org.scalajs.dom
import socle.ui.theme.Tokens
import socle.wave.WaveLayout

/** The "● colours" chip beside Surfer's status (the panel's Waveform tab, the
  * VS Code Surfer tab's status line). Hovering or focusing it opens its legend:
  * what each colour of the signal list means and how the groups are built,
  * read from [[WaveLayout.legend]], the mapping the plan paints with. A click
  * (or Enter) keeps it open, Esc closes it. `byDirectionSig`: whether the
  * ports are coloured by direction (the setting, or the plan shown).
  *
  * The card is fixed-positioned from the chip, above it in the lower half of
  * the page, so the status line's clipping or the page's bottom edge does not
  * cut it. The colour names are Surfer's: its theme sets the shade. */
object ColorLegend {

  private val Width = 260 // the card's width, px

  def chip(byDirectionSig: Signal[Boolean]): Element = {
    val openVar = Var(false)
    val posVar  = Var("")
    var pinned   = false
    var hovering = false

    def show(el: dom.Element, on: Boolean): Unit = {
      if (on) {
        val r = el.getBoundingClientRect()
        // Its right edge on the chip's, kept 8 px inside the page.
        val left = math.max(8.0, math.min(r.right - Width, dom.window.innerWidth - Width - 8))
        posVar.set(
          if (r.top > dom.window.innerHeight / 2) s"bottom:${dom.window.innerHeight - r.top + 6}px;left:${left}px"
          else s"top:${r.bottom + 6}px;left:${left}px")
      }
      if (openVar.now() != on) openVar.set(on)
    }

    val rowStyle  = s"padding:2px 0;color:${Tokens.ink2}"
    val noteStyle = s"margin-top:6px;color:${Tokens.ink3};white-space:normal"
    val card = div(
      dataAttr("wave-legend-card") := "1", role := "tooltip",
      styleAttr <-- Signal.combine(openVar.signal, posVar.signal).map { case (o, pos) =>
        s"display:${if (o) "block" else "none"};position:fixed;$pos;z-index:300;width:${Width}px;box-sizing:border-box;padding:8px 10px;" +
          s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:6px;box-shadow:0 6px 18px rgba(0,0,0,0.18);" +
          s"font-family:${Tokens.mono};font-size:11px;line-height:1.5;text-align:left;cursor:default"
      },
      div(styleAttr := s"font-weight:600;color:${Tokens.ink};margin-bottom:4px", "Waveform colours"),
      children <-- byDirectionSig.map(by => WaveLayout.legend(by).map { case (what, color) =>
        div(dataAttr("wave-legend-row") := what, dataAttr("wave-color") := color, styleAttr := rowStyle,
          span(styleAttr := "display:inline-block;min-width:84px", what), " ", span(styleAttr := s"color:${Tokens.ink}", color))
      }),
      child.maybe <-- byDirectionSig.map(by => Option.when(!by)(div(dataAttr("wave-legend-note") := "1", styleAttr := noteStyle,
        s"Colour by direction is off: ports are all ${WaveLayout.InputColor}."))),
      div(styleAttr := noteStyle, WaveLayout.groupsNote),
      div(styleAttr := noteStyle, "The names are Surfer's colours; its theme sets the shade."),
    )

    span(styleAttr := "display:inline-flex;align-items:center",
      button(
        // No native title: the chip's own card shows on hover.
        dataAttr("wave-legend") := "1", aria.label := "What the waveform colours mean",
        aria.expanded <-- openVar.signal, aria.hasPopup := true,
        styleAttr := s"border:1px solid ${Tokens.borderSoft};background:${Tokens.panel};color:${Tokens.ink3};border-radius:9px;" +
          s"padding:0 7px;font-family:inherit;font-size:10.5px;line-height:16px;cursor:pointer;white-space:nowrap",
        onMouseEnter --> { e => hovering = true; show(e.target.asInstanceOf[dom.Element], on = true) },
        onMouseLeave --> { e => hovering = false; if (!pinned) show(e.target.asInstanceOf[dom.Element], on = false) },
        onFocus --> { e => show(e.target.asInstanceOf[dom.Element], on = true) },
        onBlur --> { e => pinned = false; show(e.target.asInstanceOf[dom.Element], on = hovering) },
        onClick --> { e => pinned = !pinned; show(e.target.asInstanceOf[dom.Element], on = pinned || hovering) },
        onKeyDown.filter(_.key == "Escape") --> { e => pinned = false; show(e.target.asInstanceOf[dom.Element], on = false) },
        "● colours",
      ),
      card,
    )
  }
}
