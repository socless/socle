package socle.ui.panels

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socle.ui.schematic.{Highlight, Tooltip}
import socle.ui.theme.Tokens

/** Help that teaches as you go: the hint bar under the schematic (what the
  * mouse does on what is under it) and the first-time tips. The "?" card is
  * [[Chrome.helpOverlay]]. */
object Help {

  // Remembered where the theme and density live (standalone only; in an
  // editor's panel, VS Code or `?panel=1`, the page's origin changes with each
  // server port, so nothing is stored).
  private val HintKey = "socle-hints" // "off" once closed
  private val TipsKey = "socle-tips"  // "done" after Skip or the last tip

  private def load(key: String): Option[String] =
    try Option(dom.window.localStorage.getItem(key)) catch { case _: Throwable => None }
  private def save(key: String, v: String): Unit =
    try dom.window.localStorage.setItem(key, v) catch { case _: Throwable => () }

  /** The line off any target. */
  val Background = "Drag: pan · Wheel: zoom · Shift/Ctrl+wheel: scroll · Double-click a block: open"

  /** The hint bar's (name, actions) for what is under the pointer: the
    * tooltip card's name and actions on a target, the general line off one. */
  def hint(target: Option[(String, String)]): (String, String) = target.getOrElse(("", Background))

  /** The first-time tips, one line each. */
  def tips(mac: Boolean): Seq[String] = Seq(
    "Drag anywhere to pan. The wheel zooms at the pointer.",
    "Double-click a block to open it. The breadcrumb takes you back up.",
    "Click a block's title to inspect it, a bundle pin to expand it, a wire to follow its net. Rest on one to see what it is.",
    s"Press ? for every mouse action and key. ${if (mac) "⌘K" else "Ctrl K"} searches the design.",
  )

  private val linkBtn = s"border:none;background:transparent;cursor:pointer;padding:0 4px;font-family:inherit;font-size:inherit"

  /** The hint bar: one line under the schematic, built once; its text is set
    * through `textContent` as the pointer moves onto another target. Closing
    * it is remembered (standalone) or lasts for the page's life (embedded).
    * `openHelp` opens the "?" card. */
  final class HintBar(embedded: Boolean, openHelp: () => Unit) {
    val openVar: Var[Boolean] = Var(embedded || !load(HintKey).contains("off"))

    private val nameEl = span(dataAttr("hint-name") := "", styleAttr := s"font-family:${Tokens.mono};font-weight:600;color:${Tokens.ink2};display:none;flex-shrink:0")
    private val textEl = span(dataAttr("hint-text") := "", Background,
      styleAttr := "overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0")
    private var shown: (String, String) = ("", Background)

    private def set(h: (String, String)): Unit = if (h != shown) {
      shown = h
      nameEl.ref.textContent = h._1
      nameEl.ref.style.display = if (h._1.isEmpty) "none" else ""
      textEl.ref.textContent = if (h._1.isEmpty) h._2 else s"· ${h._2}"
    }

    val el: Div = div(
      dataAttr("hint-bar") := "1",
      styleAttr <-- openVar.signal.map(o =>
        s"display:${if (o) "flex" else "none"};align-items:center;gap:6px;flex-shrink:0;height:24px;box-sizing:border-box;padding:0 6px 0 12px;" +
          s"border-top:1px solid ${Tokens.borderSoft};background:${Tokens.panel};color:${Tokens.ink3};font-size:11px;white-space:nowrap;overflow:hidden"),
      nameEl,
      textEl,
      div(styleAttr := "flex:1"),
      button(dataAttr("hint-help") := "1", title := "All mouse actions and keys",
        styleAttr := s"$linkBtn;color:${Tokens.accent}", onClick --> { _ => openHelp() }, "? all shortcuts"),
      button(dataAttr("hint-close") := "1", title := "Hide the hint bar (Settings brings it back)",
        styleAttr := s"$linkBtn;color:${Tokens.ink3};font-size:13px;line-height:1", onClick --> { _ => openVar.set(false) }, "×"),
      // Standalone, the choice is remembered (the close button and the settings toggle).
      Option.when(!embedded)(openVar.signal.changes --> { o => save(HintKey, if (o) "on" else "off") }),
    )

    /** Follow the target under the pointer in the schematic viewport (one
      * delegated listener, as the tooltip's); returns the removal. The bar is
      * outside the schematic, so its text changes touch nothing there. */
    def install(viewport: dom.html.Element, hl: Highlight): () => Unit = {
      var last: Option[Tooltip.Target] = None
      def follow(t: Option[Tooltip.Target]): Unit = if (t != last) {
        last = t
        val g = hl.geom
        set(hint(for { x <- t; g0 <- Option(g); h <- Tooltip.hintOf(g0, x) } yield h))
      }
      val onOver: js.Function1[dom.PointerEvent, Unit] = (e) =>
        if (hl.isHoverSuspended) follow(None)
        else e.target match {
          case x: dom.Element => follow(Tooltip.targetOf(x))
          case _              => follow(None)
        }
      val onLeave: js.Function1[dom.PointerEvent, Unit] = (_) => follow(None)
      viewport.addEventListener("pointerover", onOver)
      viewport.addEventListener("pointerleave", onLeave)
      () => {
        viewport.removeEventListener("pointerover", onOver)
        viewport.removeEventListener("pointerleave", onLeave)
        last = None; set(("", Background))
      }
    }
  }

  /** The first-time tips: a small card over the bottom of the schematic (above
    * its zoom controls, positioned against a holder on its bottom edge), one
    * tip at a time, until Skip or the last tip (then never again). Not modal:
    * the schematic and the keys keep working under it. Never in an editor's panel. */
  def firstTips(embedded: Boolean, mac: Boolean): Element = {
    val all = tips(mac)
    val idxVar = Var(if (embedded || load(TipsKey).contains("done")) -1 else 0)
    def done(): Unit = { save(TipsKey, "done"); idxVar.set(-1) }
    div(styleAttr := "display:contents",
      child.maybe <-- idxVar.signal.map { i =>
        if (i < 0 || i >= all.size) None
        else Some(div(dataAttr("first-tips") := "1",
          styleAttr := s"position:absolute;left:50%;bottom:calc(100% + 48px);transform:translateX(-50%);z-index:6;width:max-content;max-width:min(460px, calc(100% - 32px));box-sizing:border-box;" +
            s"display:flex;align-items:center;gap:10px;padding:8px 10px 8px 14px;background:${Tokens.panel};border:1px solid ${Tokens.accentLine};border-radius:8px;" +
            "box-shadow:0 6px 18px rgba(0,0,0,.18);font-size:12px",
          span(styleAttr := s"font-family:${Tokens.mono};font-size:10px;color:${Tokens.ink4};flex-shrink:0", s"Tip ${i + 1}/${all.size}"),
          span(dataAttr("first-tips-text") := "", styleAttr := s"color:${Tokens.ink2}", all(i)),
          button(dataAttr("first-tips-skip") := "1", styleAttr := s"$linkBtn;color:${Tokens.ink3};flex-shrink:0", onClick --> { _ => done() }, "Skip"),
          button(dataAttr("first-tips-next") := "1",
            styleAttr := s"border:1px solid ${Tokens.accentLine};background:${Tokens.accentBg};color:${Tokens.accent};border-radius:5px;cursor:pointer;padding:2px 10px;font-size:11px;flex-shrink:0",
            onClick --> { _ => if (i + 1 >= all.size) done() else idxVar.set(i + 1) },
            if (i + 1 >= all.size) "Done" else "Next"),
        ))
      })
  }
}
