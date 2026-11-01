package socless.ui.panels

import com.raquo.laminar.api.L._
import org.scalajs.dom
import socless.schema.{Design, BundleKind}
import socless.ui.theme._

/** App-level chrome (epic #172 Phase 4): the command palette, the keyboard-help
  * overlay, and the export-warnings banner. Ports the JS `CommandPalette`,
  * `KeyboardHelp`, and `WarningsBanner`. */
object Chrome {

  private def isMac: Boolean =
    scala.scalajs.js.typeOf(dom.window.navigator.platform) == "string" &&
      dom.window.navigator.platform.matches("(?s).*(Mac|iP(hone|ad|od)).*")

  /** True when on a Mac (so the topbar can show ⌘K instead of Ctrl K). */
  def macHint: Boolean = isMac

  // ── Command palette ─────────────────────────────────────────────────────────
  // payload drives navigation (module path); `run` is set for action items
  // (command hub, #276) and takes precedence over navigation; `inst` (module,
  // instance) makes an instance a fly to its box, when the palette has one.
  private final case class Item(kind: String, label: String, sub: String, payload: List[String], run: Option[() => Unit] = None,
                                inst: Option[(String, String)] = None)

  private val VecRe = """^Vec\[(.+),\s*(\d+)\]$""".r

  private def buildItems(d: Design): Seq[Item] = {
    val out = scala.collection.mutable.ListBuffer.empty[Item]
    d.modules.foreach { m =>
      out += Item("module", m.name, if (m.doc.nonEmpty) m.doc else m.`type`, List(m.name))
      m.children.foreach { c =>
        val payload = if (d.modules.exists(_.name == c.`type`)) List(c.`type`) else List(m.name)
        out += Item("instance", s"${m.name}.${c.name}", c.`type` + (if (c.doc.nonEmpty) " · " + c.doc else ""), payload,
          inst = Some((m.name, c.name)))
      }
      m.ports.foreach { p =>
        out += Item("port", s"${m.name}:${p.name}", s"${p.dir} ${Inspector.typeName(p.bundle, d)}", List(m.name))
        def addSub(name: String, key: String): Unit = out += Item("port", s"${m.name}:$name", Inspector.typeName(key, d), List(m.name))
        d.bundles.get(p.bundle) match {
          case Some(b) if b.kind == BundleKind.Bundle =>
            b.channels.foreach { ch =>
              ch.`type` match {
                case VecRe(inner, n) => (0 until math.min(n.toInt, 16)).foreach(i => addSub(s"${p.name}.${ch.name}[$i]", inner.trim))
                case _               => addSub(s"${p.name}.${ch.name}", ch.`type`)
              }
            }
          case _ => p.bundle match {
            case VecRe(inner, n) => (0 until math.min(n.toInt, 16)).foreach(i => addSub(s"${p.name}[$i]", inner.trim))
            case _               =>
          }
        }
      }
    }
    out.toSeq
  }

  /** Fuzzy subsequence score (lower is better); None when not all needle chars
    * appear in order. Ports the JS palette scoring. */
  private def score(item: Item, needle: String): Option[Double] = {
    val target = (item.label + " " + item.sub).toLowerCase
    var ni = 0; var sc = 0.0; var lastHit = -1; var run = 0
    var ti = 0
    while (ti < target.length && ni < needle.length) {
      if (target(ti) == needle(ni)) {
        val gap = if (lastHit < 0) 0 else ti - lastHit - 1
        sc += gap
        if (gap == 0) run += 1
        lastHit = ti; ni += 1
      }
      ti += 1
    }
    if (ni < needle.length) None
    else {
      if (target.contains(needle)) sc -= 1000
      if (target.startsWith(needle) || item.label.toLowerCase.startsWith(needle)) sc -= 200
      if (target.headOption.contains(needle.head)) sc -= 100
      sc -= run * 5
      sc += (if (item.kind == "module") 0 else 1)
      Some(sc)
    }
  }

  private def results(items: Seq[Item], q: String): Seq[Item] = {
    val needle = q.trim.toLowerCase
    if (needle.isEmpty) items.take(60)
    else items.flatMap(it => score(it, needle).map(s => (it, s))).sortBy(_._2).map(_._1).take(100)
  }

  private val kindColor = Map("module" -> Tokens.accent, "instance" -> Tokens.kindInstance,
    "port" -> Tokens.kindPort, "action" -> Tokens.ok)

  /** The command palette (Ctrl/Cmd+K). `actions` are command-hub entries
    * (label, sub, run) searchable alongside modules/instances/ports (#276).
    * With `onFlyTo`, an instance shows its module and flies to its box. */
  def commandPalette(designSig: Signal[Option[Design]], openVar: Var[Boolean],
                     onNavigate: List[String] => Unit,
                     actions: Seq[(String, String, () => Unit)] = Nil,
                     onFlyTo: Option[(String, String) => Unit] = None): Element = {
    val qVar       = Var("")
    val cursorVar  = Var(0)
    val resultsVar = Var[Seq[Item]](Nil) // mirror of resultsSig so the keydown handler can read it
    val actionItems = actions.map { case (label, sub, run) => Item("action", label, sub, Nil, Some(run)) }
    def close(): Unit = openVar.set(false)
    def navigate(it: Item): Unit = {
      (it.run, it.inst, onFlyTo) match {
        case (Some(r), _, _)                 => r()
        case (None, Some((m, c)), Some(fly)) => fly(m, c)
        case _                               => onNavigate(it.payload)
      }
      close()
    }

    val resultsSig: Signal[Seq[Item]] =
      Signal.combine(designSig, qVar.signal).map { case (dO, q) =>
        results(actionItems ++ dO.map(buildItems).getOrElse(Nil), q)
      }

    // `display:contents` holder: keeps the results mirror live and renders the
    // modal (a fixed-position overlay) only when open.
    div(styleAttr := "display:contents",
      resultsSig --> resultsVar.writer,
      qVar.signal --> { _ => cursorVar.set(0) },
    child.maybe <-- openVar.signal.map { open =>
      if (!open) None
      else Some(
        div(dataAttr("command-palette") := "1",
          styleAttr := "position:fixed;inset:0;background:rgba(20,20,18,0.32);-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);display:flex;justify-content:center;padding-top:12vh;z-index:100",
          onClick --> { _ => close() },
          div(cls := "scl-pop",
            styleAttr := s"width:580px;max-width:90vw;max-height:70vh;background:color-mix(in srgb, ${Tokens.panel} 82%, transparent);-webkit-backdrop-filter:blur(26px) saturate(150%);backdrop-filter:blur(26px) saturate(150%);border:1px solid ${Tokens.border};border-radius:12px;box-shadow:0 1px 0 rgba(255,255,255,.06) inset,0 24px 60px rgba(0,0,0,0.32);display:flex;flex-direction:column;overflow:hidden",
            onClick --> { (e: dom.MouseEvent) => e.stopPropagation() },
            div(styleAttr := s"display:flex;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid ${Tokens.borderSoft}",
              input(dataAttr("cmd-input") := "1", placeholder := "Search modules, instances, ports, actions…",
                styleAttr := s"flex:1;border:none;outline:none;background:transparent;font-size:14px;color:${Tokens.ink}",
                controlled(value <-- qVar.signal, onInput.mapToValue --> qVar.writer),
                onMountCallback { ctx => ctx.thisNode.ref.focus() },
                onKeyDown --> { (e: dom.KeyboardEvent) =>
                  e.key match {
                    // Handled: the schematic's Escape (stop following a net) must not act too.
                    case "Escape"    => e.preventDefault(); close()
                    case "ArrowDown" => e.preventDefault(); cursorVar.update(c => math.min(c + 1, resultsVar.now().length - 1))
                    case "ArrowUp"   => e.preventDefault(); cursorVar.update(c => math.max(c - 1, 0))
                    case "Enter"     => resultsVar.now().lift(cursorVar.now()).foreach(it => navigate(it))
                    case _           =>
                  }
                }),
              span(styleAttr := s"font-family:${Tokens.mono};font-size:10px;color:${Tokens.ink4}",
                child.text <-- resultsVar.signal.map(r => s"${r.length} ${if (r.length == 1) "result" else "results"}"))),
            // Build the list only on results change; bind each row's active
            // highlight reactively to the cursor. (Rebuilding the list on cursor
            // change destroyed the row a hover was about to click, issue: the
            // palette never navigated under a real mouse click.)
            div(styleAttr := "overflow-y:auto;padding:4px",
              children <-- resultsVar.signal.map { rs =>
                if (rs.isEmpty) List(div(styleAttr := s"padding:24px 14px;color:${Tokens.ink3};font-size:12px;text-align:center",
                  child.text <-- qVar.signal.map(q => s"""No matches for "$q".""")))
                else rs.zipWithIndex.map { case (r, i) =>
                  val activeSig = cursorVar.signal.map(_ == i)
                  button(dataAttr("cmd-result") := r.label,
                    styleAttr <-- activeSig.map(a => s"display:flex;align-items:center;gap:10px;width:100%;padding:7px 10px;border-radius:6px;border:none;cursor:pointer;text-align:left;background:${if (a) Tokens.accentBg else "transparent"}"),
                    onMouseEnter --> { _ => cursorVar.set(i) },
                    onClick --> { _ => navigate(r) },
                    span(styleAttr := s"font-family:${Tokens.mono};font-size:9.5px;padding:1px 5px;border-radius:3px;color:${kindColor.getOrElse(r.kind, Tokens.ink3)};background:${Tokens.bg};border:1px solid ${Tokens.borderSoft};text-transform:uppercase;letter-spacing:.4px;min-width:60px;text-align:center", r.kind),
                    span(title := r.label, styleAttr <-- activeSig.map(a => s"font-family:${Tokens.mono};font-size:12px;color:${if (a) Tokens.accent else Tokens.ink};font-weight:${if (a) 600 else 400};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0"), r.label),
                    span(styleAttr := s"margin-left:auto;font-family:${Tokens.mono};font-size:10.5px;color:${Tokens.ink4};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex-shrink:0;max-width:40%", r.sub))
                }
              }),
          )))
    })
  }

  // ── Help card (keyboard and mouse) ───────────────────────────────────────
  // Two columns: what the mouse does on each element of the schematic, and
  // the keys. Each row: the keys or gesture (chips), then what it does.
  private type Rows = Seq[(Seq[String], String)]
  private val mouseHelp: Seq[(String, Rows)] = Seq(
    "Anywhere" -> Seq(
      Seq("Drag")          -> "Pan",
      Seq("Middle drag")   -> "Pan",
      Seq("Wheel")         -> "Zoom at the pointer",
      Seq("Shift + wheel") -> "Scroll sideways",
      Seq("Ctrl + wheel")  -> "Scroll up and down",
      Seq("Pinch")         -> "Zoom (trackpad)",
      Seq("Rest")          -> "Show what is under the pointer",
      Seq("Click")         -> "On the background: clear the selection, stop following",
      Seq("Back", "Forward") -> "Over the schematic: back, forward (mouse side buttons)",
      Seq("◎")             -> "On a hierarchy row: show it in its parent",
    ),
    "Blocks" -> Seq(
      Seq("Double-click") -> "Open the module, or unfold a group",
      Seq("Click title")  -> "Inspect",
      Seq("Breadcrumb")   -> "Go back up",
    ),
    "Wires" -> Seq(Seq("Click") -> "Select and follow the net (it stays lit across levels)"),
    "Pins" -> Seq(Seq("Click") -> "Expand or collapse a bundle"),
    "Logic blocks" -> Seq(
      Seq("Click")        -> "Inspect a register or an operator",
      Seq("Click title")  -> "Inspect a block or a state machine",
      Seq("Double-click") -> "Open a state machine's diagram",
    ),
  )
  private val keyHelp: Seq[(String, Rows)] = Seq(
    "Schematic" -> Seq(
      Seq("← ↑ → ↓") -> "Pan",
      Seq("+ / -")   -> "Zoom",
      Seq("0 / f")   -> "Fit to view",
      Seq("z")       -> "Zoom to the selection",
      Seq("Alt ←", "Alt →") -> "Back, forward",
      Seq("/")       -> "Focus the filter",
      Seq("Esc")     -> "Close a card, the palette or a diagram; stop following a net",
    ),
    "Search" -> Seq(
      Seq("⌘K", "Ctrl K") -> "Open command palette / search",
      Seq("↑ ↓")          -> "Move selection in palette",
      Seq("↵")            -> "Navigate to selected result",
    ),
    "Help" -> Seq(Seq("?") -> "Open this card"),
  )

  private def displayKeys(keys: Seq[String]): Seq[String] =
    keys.filterNot(k => (isMac && k == "Ctrl K") || (!isMac && k == "⌘K"))

  private def helpColumn(sections: Seq[(String, Rows)]): Element =
    div(styleAttr := "flex:1 1 300px;min-width:0",
      sections.map { case (cat, rows) =>
        div(dataAttr("help-section") := cat,
          div(styleAttr := s"padding:8px 16px 3px;font-weight:600;font-size:10px;color:${Tokens.ink4};text-transform:uppercase;letter-spacing:.6px", cat),
          rows.map { case (keys, desc) =>
            div(styleAttr := "display:flex;align-items:center;justify-content:space-between;padding:4px 16px",
              span(styleAttr := s"font-size:12px;color:${Tokens.ink2}", desc),
              span(styleAttr := "display:flex;gap:4px;flex-shrink:0;margin-left:12px",
                displayKeys(keys).map(k => span(styleAttr := s"font-family:${Tokens.mono};font-size:10.5px;padding:1px 6px;border-radius:4px;background:${Tokens.panelAlt};border:1px solid ${Tokens.border};color:${Tokens.ink2};line-height:1.6;white-space:nowrap", k))))
          })
      })

  def helpOverlay(openVar: Var[Boolean]): Element =
    div(styleAttr := "display:contents",
    child.maybe <-- openVar.signal.map { open =>
      if (!open) None
      else Some(
        div(dataAttr("help-overlay") := "1",
          styleAttr := "position:fixed;inset:0;background:rgba(20,20,18,0.38);-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);display:flex;justify-content:center;align-items:flex-start;padding-top:8vh;z-index:200",
          onClick --> { _ => openVar.set(false) },
          div(cls := "scl-pop",
            styleAttr := s"width:720px;max-width:92vw;max-height:84vh;display:flex;flex-direction:column;background:color-mix(in srgb, ${Tokens.panel} 82%, transparent);-webkit-backdrop-filter:blur(26px) saturate(150%);backdrop-filter:blur(26px) saturate(150%);border:1px solid ${Tokens.border};border-radius:12px;box-shadow:0 1px 0 rgba(255,255,255,.06) inset,0 24px 60px rgba(0,0,0,0.32);overflow:hidden",
            onClick --> { (e: dom.MouseEvent) => e.stopPropagation() },
            div(styleAttr := s"display:flex;align-items:center;justify-content:space-between;padding:11px 16px;border-bottom:1px solid ${Tokens.borderSoft};flex-shrink:0",
              span(styleAttr := s"font-weight:600;font-size:13px;color:${Tokens.ink}", "Mouse and keyboard"),
              button(styleAttr := s"border:none;background:transparent;cursor:pointer;color:${Tokens.ink3};font-family:${Tokens.mono};font-size:11px",
                onClick --> { _ => openVar.set(false) }, "Esc")),
            div(styleAttr := "padding:4px 0 8px;display:flex;flex-wrap:wrap;overflow-y:auto",
              helpColumn(mouseHelp), helpColumn(keyHelp)))))
    })

  // ── Warnings banner ─────────────────────────────────────────────────────────
  def warningsBanner(designSig: Signal[Option[Design]]): Element = {
    val dismissedVar = Var(false)
    val expandedVar  = Var(false)
    // Reset dismissal whenever a new design loads.
    div(
      designSig --> { _ => dismissedVar.set(false); expandedVar.set(false) },
      child.maybe <-- Signal.combine(designSig, dismissedVar.signal, expandedVar.signal).map {
        case (Some(d), false, expanded) if d.warnings.nonEmpty =>
          val total = d.warnings.map(_.count).sum
          Some(div(dataAttr("warnings-banner") := "1",
            styleAttr := s"display:flex;flex-direction:column;background:${Tokens.panelAlt};border-bottom:1px solid ${Tokens.border};font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink2}",
            div(styleAttr := "display:flex;align-items:center;gap:8px;padding:5px 12px",
              span(styleAttr := s"color:${Tokens.warn};font-weight:600", s"⚠ $total export warning${if (total == 1) "" else "s"}"),
              button(styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:11px;padding:0",
                onClick --> { _ => expandedVar.update(!_) }, if (expanded) "hide details" else "show details"),
              div(styleAttr := "flex:1"),
              button(dataAttr("warnings-dismiss") := "1", title := "Dismiss",
                styleAttr := s"border:none;background:transparent;color:${Tokens.ink3};cursor:pointer;font-family:inherit;font-size:13px;line-height:1;padding:0",
                onClick --> { _ => dismissedVar.set(true) }, "×")),
            if (expanded) ul(styleAttr := s"margin:0;padding:0 12px 8px 30px;color:${Tokens.ink3}",
              d.warnings.map(w => li(styleAttr := "margin-bottom:2px",
                span(styleAttr := s"color:${Tokens.ink4}", s"[${w.kind}] "), w.message,
                if (w.module.nonEmpty) span(styleAttr := s"color:${Tokens.ink4}", s" (${w.module})") else emptyNode)))
            else emptyNode))
        case _ => None
      })
  }
}
