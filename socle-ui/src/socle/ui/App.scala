package socle.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.timers._
import socle.schema.{Design, ModuleDef, NodeKind, SourceRef}
import socle.layout.{InstanceFold, LayoutCore}
import socle.ui.data.{DesignLoader, Embedded, IdeLink, IdeState}
import socle.ui.theme._
import socle.ui.model._
import socle.ui.schematic._
import socle.ui.panels._

/** The Scala.js + Laminar frontend (epic #172).
  *
  * Phase 1: load design.json into a typed [[Design]], hold app state in Laminar
  * `Var`s, and render the navigable module hierarchy. The schematic + inspector
  * panes follow in later phases. The design is parsed once with the shared
  * upickle ReadWriters - no `window.DESIGN` JS objects. */
@JSExportTopLevel("SocleUI")
object App {

  // ── Navigation / view state (design + source state lives in [[DesignLoader]]) ─
  private val pathVar     = Var[List[String]](Nil)     // drilled module path (instance names)
  private val expandedVar = Var[Set[String]](Set.empty) // expanded hierarchy row ids (path-joined)
  // Schematic + inspector shared state (Phase 2 / Phase 3).
  private val selectedSigVar   = Var[Option[String]](None)   // selected wire / instance path
  private val hoveredSigVar    = Var[Option[String]](None)
  // In-box expanded bundle pin paths, per module name: each module keeps its own
  // and gets it back on return, and no module's layout ever sees another's paths.
  private val expandedPortsVar = Var(Map.empty[String, Set[String]])
  private val expandedBundlesVar = Var[Set[String]](Set.empty) // inspector bundle-tree expansion
  // Unfolded generator-copy groups per module name; session-long, groups start folded.
  private val unfoldedVar = Var(Map.empty[String, Set[String]])
  private val paletteOpenVar = Var(false)                       // command palette (Ctrl/Cmd+K)
  private val helpOpenVar    = Var(false)                       // keyboard-help overlay (?)
  private val themeVar       = Var[String]("light")             // active theme id (#2 / #275)
  // Settings panel view preferences, persisted and applied as <html> attributes
  // (also set before paint in the template): theme id (#275) + density (#198).
  private val settingsOpenVar = Var(false)
  private val densityVar      = Var[String]("comfortable")      // "compact" | "comfortable" | "comfy"
  private val labelSizeVar    = Var[String]("normal")           // "normal" | "large" | "xl" (schematic labels)
  // Colorize toggles (#266) live in the settings panel now (#274); App owns them
  // and passes them into the schematic.
  // Persisted in the browser; the editor's settings (`schematicSettings`) set
  // them too ([[applySchematicSettings]]).
  private val colorBoxesVar   = Var(stored("socle-colorize-boxes").contains("on"))
  private val colorWiresVar   = Var(stored("socle-colorize-wires").contains("on"))
  // Auto-optimize (#209): run the in-browser optimize layout on every module view
  // (off-thread) so the initial render matches Optimize. Default on; persisted.
  private val autoOptimizeVar = Var(true)
  // Declutter (#223): node kinds hidden from the schematic. Empty = show all.
  private val hiddenKindsVar  = Var[Set[String]](stored("socle-hide").fold(Set.empty[String])(_.split(',').toSet.filter(socle.schema.SchematicSettings.Kinds.contains)))
  // Verification layers drawn (the schematic's layers button).
  private val showLayersVar   = Var(stored("socle-layers").contains("on"))
  // The editor focus (the class under the editor's cursor lights its boxes):
  // on unless the settings turn it off; `focusOffVar` the class the Focus
  // chip's × stopped, until the cursor is on another class.
  private val followCursorVar = Var(true)
  private val focusOffVar     = Var(Option.empty[String])

  private def stored(key: String): Option[String] =
    try Option(dom.window.localStorage.getItem(key)) catch { case _: Throwable => None }
  private def store(key: String, v: String): Unit =
    try dom.window.localStorage.setItem(key, v) catch { case _: Throwable => () }

  /** The editor's schematic settings, when they arrive or change: they set
    * the view's toggles (the settings popover changes them until the next
    * change of a setting). */
  private def applySchematicSettings(s: socle.schema.SchematicSettings): Unit = {
    colorBoxesVar.set(s.colorizeComponents)
    colorWiresVar.set(s.colorizeWires)
    applyDensity(s.density)
    applyLabelSize(s.labelSize)
    autoOptimizeVar.set(s.autoOptimize)
    hintBar.openVar.set(s.hintBar)
    showLayersVar.set(s.showLayers)
    hiddenKindsVar.set(s.hide.toSet)
    followCursorVar.set(s.followCursor)
  }
  private val tickVar        = Var(0)                           // 1 Hz clock for the "updated Ns ago" badge
  // Panel layout (#206): toggle the side / bottom panels to focus on the
  // schematic; the bottom panel is also drag-resizable.
  private val leftOpenVar      = Var(true)
  private val rightOpenVar     = Var(true)
  private val bottomOpenVar    = Var(!Embedded.on) // embedded: only Ports is left, start closed
  private val bottomHeightVar  = Var(240.0)
  private val bottomTabVar     = Var(if (Embedded.on) "ports" else "source") // embedded: no Source tab
  // The FSM block (its id) whose state diagram is open in the schematic pane.
  // Cleared when the drill path changes and when a design change drops the block.
  private val fsmOpenVar       = Var[Option[String]](None)
  // The hint bar under the schematic (help); its open state is remembered in
  // standalone mode, for the page's life in an editor's panel.
  private val hintBar = new Help.HintBar(Embedded.editorPanel, () => helpOpenVar.set(true))
  // The schematic's Back, Forward, Zoom to selection and fly to (the palette's).
  private val viewCmds = new ViewHistory.Commands

  // Design loading + live poll + drag-drop + toasts (#219), owning the design /
  // source state. App injects how to reset navigation when a design lands.
  private def navResetToTop(d: Design): Unit = {
    val top = topOf(d)
    pathVar.set(if (top.nonEmpty) List(top) else Nil)
    expandedVar.set(if (top.nonEmpty) Set(top) else Set.empty) // top expanded -> shows its children
    fsmOpenVar.set(None)
    reconcileSelection(d)
  }
  // Live reload keeps the drill path/expansion when it still resolves (#120),
  // and an open state diagram while its block still has one.
  private def navReconcile(d: Design): Unit =
    if (currentModule(d, pathVar.now()).isEmpty) navResetToTop(d)
    else {
      reconcileSelection(d)
      if (!fsmOpenVar.now().forall(id => shownModule(d, pathVar.now()).exists(_.logic.exists(n => n.id == id && n.fsm.isDefined))))
        fsmOpenVar.set(None)
    }

  /** On a design change, keep the selection and the hover only while they
    * still name something in the shown (folded) module: a stale one blanks the
    * Inspector, which shows the selection, else the hover. A hovered box the
    * reload replaces never fires its mouseleave, so the hover is checked too. */
  private def reconcileSelection(d: Design): Unit =
    for (v <- Seq(selectedSigVar, hoveredSigVar))
      if (!v.now().forall(s => shownModule(d, pathVar.now()).exists(selectionIn(_, s)))) v.set(None)

  /** Whether selection `s` names something in `mod`, resolved like the
    * Inspector does: a logic node, a wire end (or a path under one), or an
    * instance (or one of its pins). */
  private def selectionIn(mod: ModuleDef, s: String): Boolean = {
    val head = s.takeWhile(_ != '.')
    mod.logic.exists(_.id == s) ||
    mod.wires.exists(w => w.from == s || w.to == s || s.startsWith(w.from + ".") || s.startsWith(w.to + ".")) ||
    mod.children.exists(_.name == head)
  }

  // A fresh load (first load, picker, dropped file) also forgets the view
  // history and the followed net; a live reload keeps them (navReconcile).
  private def navFresh(d: Design): Unit = { viewCmds.reset(); navResetToTop(d) }
  private val loader = new DesignLoader(topOf = topOf, onFresh = navFresh, onLive = navReconcile)
  // Language-server link (plan 2): run status, editor cursor focus, open on
  // select. Disables itself when the page is not served by the language server.
  private val ide = new IdeLink
  // The waveform viewer follows the selection and the drill path (wave sync);
  // a new trace for the embedded Surfer opens the Waveform tab. The VS Code
  // panel drives the Surfer tab instead (a RemotePane, fed by the relayed
  // statuses), and a new trace opens that tab.
  // Lazy: it reads currentModuleSig, defined further down.
  private lazy val wave = {
    val pane: socle.ui.wave.WavePane =
      if (Embedded.surferRemote) {
        val r = new socle.ui.wave.RemotePane(Embedded.postToParent)
        Embedded.onSurferStatus(r.onStatus)
        Embedded.onSurferClosed(() => r.onClosed())
        r.hello() // the tab's state, for a reloaded panel
        r
      } else new socle.ui.wave.SurferPane
    new socle.ui.wave.WaveFollow(
      stateSig    = ide.stateSig,
      designSig   = loader.designSig,
      pathSig     = pathVar.signal,
      selectedVar = selectedSigVar,
      moduleSig   = currentModuleSig,
      onTrace     = () => { bottomOpenVar.set(true); bottomTabVar.set("wave") },
      pane        = pane,
      onReveal    = (loc, open) => reveal(loc, open),
    )
  }

  @JSExport
  def main(rootId: String): String = {
    val container = dom.document.getElementById(rootId)
    if (container == null) return "no-root"
    themeVar.set(currentTheme()) // sync with the set-before-paint script in the template
    if (Embedded.surferView) {
      // The VS Code Surfer tab: only Surfer, driven by the parent's messages; no design, no live poll.
      Embedded.initialTheme.foreach(applyTheme)
      Embedded.onThemeMessage(applyTheme)
      Embedded.blockFileDrops()
      render(container, surferTab())
      return "mounted"
    }
    if (Embedded.on) {
      Embedded.initialTheme.foreach(applyTheme)
      Embedded.onThemeMessage(applyTheme)
      Embedded.onNavigateMessage(navigate)
    }
    densityVar.set(currentDensity()); labelSizeVar.set(currentLabelSize())
    try autoOptimizeVar.set(dom.window.localStorage.getItem("socle-autoopt") != "off") catch { case _: Throwable => () }
    render(container, shell())
    installKeyboard()
    if (Embedded.on) Embedded.blockFileDrops() else loader.installDragDrop()
    setInterval(1000d) { tickVar.update(_ + 1) } // drives the "updated Ns ago" badge
    loader.start() // load design.json + the multi-top picker index (#197)
    ide.start()    // probe /ide.json; polls only when served by the language server
    "mounted"
  }

  /** The Surfer tab page: the pane, full size, and a one-line status bar. */
  private def surferTab(): HtmlElement = {
    val pane = new socle.ui.wave.SurferPane
    val byDirection = Var(true)
    div(
      dataAttr("surfer-tab") := "1",
      styleAttr := "position:fixed;inset:0;display:flex;flex-direction:column;background:var(--bg);color:var(--ink)",
      socle.ui.wave.SurferTab.run(pane, byDirection),
      pane.canvasHost,
      div(
        styleAttr := "flex:none;display:flex;align-items:center;gap:8px;padding:2px 8px;font-size:11px",
        span(
          dataAttr("surfer-tab-status") <-- pane.statusVar.signal,
          styleAttr := "opacity:0.7",
          child.text <-- pane.statusVar.signal.map {
            case "idle"    => "Surfer: waiting for a trace"
            case "booting" => "Surfer: starting"
            case "loading" => "Surfer: loading the trace"
            case "ready"   => "Surfer: ready"
            case other     => s"Surfer: $other"
          },
        ),
        socle.ui.wave.ColorLegend.chip(byDirection.signal), // the colours chip, beside the status
      ),
    )
  }

  // Global shortcuts: Ctrl/Cmd+K (or P) opens the palette, ? toggles help, Esc
  // closes both. Mirrors the JS BenchApp keydown handler.
  private def installKeyboard(): Unit =
    dom.window.addEventListener("keydown", (e: dom.KeyboardEvent) => {
      val tag    = Option(dom.document.activeElement).map(_.tagName).getOrElse("")
      val typing = tag == "INPUT" || tag == "TEXTAREA"
      val mod    = e.metaKey || e.ctrlKey
      if (mod && Set("k", "K", "p", "P").contains(e.key)) {
        e.preventDefault(); helpOpenVar.set(false); paletteOpenVar.set(true)
      } else if (!typing && !mod && e.key == "?") {
        e.preventDefault(); helpOpenVar.update(!_)
      } else if (!typing && !mod && e.key == "/" && fsmOpenVar.now().isEmpty && !helpOpenVar.now()) {
        // Focus the schematic filter (#276). The input carries data-filter-input.
        // Not while a state diagram covers the schematic (its filter is hidden)
        // or the "?" card is open (no key acts behind it).
        Option(dom.document.querySelector("[data-filter-input]")).foreach { el =>
          e.preventDefault(); el.asInstanceOf[dom.html.Element].focus()
        }
      } else if (e.key == "Escape") {
        // An overlay closes first; with none open, Escape closes the state
        // diagram. An Escape typed in a field (the palette's input, which closes
        // the palette before this runs, or the schematic filter) is that field's.
        val inField = e.target match {
          case el: dom.Element => el.tagName == "INPUT" || el.tagName == "TEXTAREA" || el.closest("[data-command-palette]") != null
          case _               => false
        }
        val overlay = paletteOpenVar.now() || helpOpenVar.now() || settingsOpenVar.now()
        val closesDiagram = !inField && !e.defaultPrevented && !overlay && fsmOpenVar.now().isDefined
        if (closesDiagram) fsmOpenVar.set(None)
        // An Escape that closes an overlay or the diagram is theirs: marking it
        // handled keeps the schematic's Escape (stop following a net) from also
        // acting should its listener run after this one (see Gestures' Escape).
        if (overlay || closesDiagram) e.preventDefault()
        paletteOpenVar.set(false); helpOpenVar.set(false); settingsOpenVar.set(false)
      }
    })

  // ── Themes (#2 / #275) ─────────────────────────────────────────────────────
  // Each theme is a complete palette defined as a `:root[data-theme=id]` block in
  // the template; "light" is the default (no attribute). Dark themes also carry
  // data-mode="dark" so the shared dark secondary palette applies. The
  // set-before-paint script applies the saved theme; keep `darkThemes` in sync
  // with the DARK map there. Each entry: (id, label, previewBg, previewAccent).
  private val themes: List[(String, String, String, String)] = List(
    ("light",     "Paper",      "#fbfbfa", "oklch(0.55 0.16 250)"),
    ("dark",      "Carbon",     "#0d1117", "#1f6feb"),
    ("blueprint", "Blueprint",  "#08182b", "#39c2ff"),
    ("nord",      "Nord",       "#2e3440", "#88c0d0"),
    ("gruvbox",   "Gruvbox",    "#282828", "#fabd2f"),
    ("dracula",   "Dracula",    "#282a36", "#bd93f9"),
    ("solarized", "Solarized",  "#fdf6e3", "#268bd2"),
    ("sepia",     "Sepia",      "#f3ead6", "#b45309"),
    ("contrast",  "Contrast",   "#000000", "#ffd400"),
  )
  private val darkThemes: Set[String] = Set("dark", "blueprint", "nord", "gruvbox", "dracula", "contrast")

  private def currentTheme(): String =
    Option(dom.document.documentElement.getAttribute("data-theme")).filter(_.nonEmpty).getOrElse("light")

  /** Apply a theme id: set data-theme (absent for the default "light"), set
    * data-mode for dark themes (drives the shared dark secondary palette),
    * persist (not when embedded: the host's theme is not a user choice), and
    * mirror into themeVar so the gallery + toggle re-render. */
  private def applyTheme(id: String): Unit = {
    val root = dom.document.documentElement
    if (id == "light") root.removeAttribute("data-theme") else root.setAttribute("data-theme", id)
    if (darkThemes(id)) root.setAttribute("data-mode", "dark") else root.removeAttribute("data-mode")
    if (!Embedded.on) try dom.window.localStorage.setItem("socle-theme", id) catch { case _: Throwable => () }
    themeVar.set(id)
  }

  // The ☾/☀ button stays a quick light<->dark toggle for muscle memory; the
  // gallery in the settings panel picks any named theme.
  private def toggleTheme(): Unit = applyTheme(if (darkThemes(currentTheme())) "light" else "dark")

  // ── Settings panel: density (#198 / #274) ──────────────────────────────────
  // "comfortable" is the default (no attribute); "compact" and "comfy" set
  // data-density and CSS tightens/loosens the chrome accordingly.
  private val densityOpts = List(("compact", "Compact"), ("comfortable", "Comfortable"), ("comfy", "Comfy"))
  private def currentDensity(): String =
    Option(dom.document.documentElement.getAttribute("data-density")).filter(_.nonEmpty).getOrElse("comfortable")
  private def applyDensity(d: String): Unit = {
    if (d == "comfortable") dom.document.documentElement.removeAttribute("data-density")
    else dom.document.documentElement.setAttribute("data-density", d)
    try dom.window.localStorage.setItem("socle-density", d) catch { case _: Throwable => () }
    densityVar.set(d)
  }

  // Segmented control with an animated sliding thumb (#274). The thumb left/width
  // are computed from the selected index over three equal segments.
  private def densitySegment(): Element =
    div(cls := "scl-seg",
      div(cls := "scl-seg-thumb", styleAttr <-- densityVar.signal.map { d =>
        val i = densityOpts.indexWhere(_._1 == d).max(0)
        s"left:calc(3px + $i * (100% - 6px) / 3);width:calc((100% - 6px) / 3)"
      }),
      densityOpts.map { case (id, label) =>
        button(dataAttr("density-option") := id,
          cls.toggle("on") <-- densityVar.signal.map(_ == id),
          onClick --> { _ => applyDensity(id) }, label)
      })

  // Schematic label size (follow-up): scales the --sch-* CSS vars via a
  // data-labels attribute so port labels + component names stay readable when
  // zoomed out. "normal" is the no-attribute default.
  private val labelOpts = List(("normal", "Normal"), ("large", "Large"), ("xl", "XL"))
  private def currentLabelSize(): String =
    Option(dom.document.documentElement.getAttribute("data-labels")).filter(_.nonEmpty).getOrElse("normal")
  private def applyLabelSize(v: String): Unit = {
    if (v == "normal") dom.document.documentElement.removeAttribute("data-labels")
    else dom.document.documentElement.setAttribute("data-labels", v)
    try dom.window.localStorage.setItem("socle-labels", v) catch { case _: Throwable => () }
    labelSizeVar.set(v)
  }
  private def labelSegment(): Element =
    div(cls := "scl-seg",
      div(cls := "scl-seg-thumb", styleAttr <-- labelSizeVar.signal.map { v =>
        val i = labelOpts.indexWhere(_._1 == v).max(0)
        s"left:calc(3px + $i * (100% - 6px) / 3);width:calc((100% - 6px) / 3)"
      }),
      labelOpts.map { case (id, label) =>
        button(dataAttr("label-option") := id,
          cls.toggle("on") <-- labelSizeVar.signal.map(_ == id),
          onClick --> { _ => applyLabelSize(id) }, label)
      })

  // An iOS-style toggle row bound to a boolean Var (#274).
  private def toggleRow(label: String, hook: String, v: Var[Boolean]): Element =
    div(cls := "scl-tg-row",
      span(label),
      button(cls := "scl-tg", dataAttr(hook) := "1",
        dataAttr("on") <-- v.signal.map(b => if (b) "1" else "0"),
        onClick --> { _ => v.update(!_) }, i()))

  // Auto-optimize (#209): a persisted boolean toggle row.
  private def autoOptRow(): Element =
    div(cls := "scl-tg-row",
      span("Auto-optimize layout"),
      button(cls := "scl-tg", dataAttr("auto-optimize") := "1",
        dataAttr("on") <-- autoOptimizeVar.signal.map(b => if (b) "1" else "0"),
        onClick --> { _ =>
          val nv = !autoOptimizeVar.now(); autoOptimizeVar.set(nv)
          try dom.window.localStorage.setItem("socle-autoopt", if (nv) "on" else "off") catch { case _: Throwable => () }
        }, i()))

  // Declutter (#223): a visibility toggle for one node kind. ON = visible (kind
  // absent from hiddenKindsVar); toggling off hides it and re-lays-out.
  private def declutterRow(label: String, kind: String): Element =
    div(cls := "scl-tg-row",
      span(label),
      button(cls := "scl-tg", dataAttr("declutter-kind") := kind,
        dataAttr("on") <-- hiddenKindsVar.signal.map(h => if (h.contains(kind)) "0" else "1"),
        onClick --> { _ => hiddenKindsVar.update(h => if (h.contains(kind)) h - kind else h + kind) }, i()))

  // A theme card: a live palette preview (bg fill + accent dot) over the name,
  // highlighted when active.
  private def themeCard(id: String, label: String, bg: String, accent: String): Element =
    button(dataAttr("theme-option") := id, title := label,
      styleAttr <-- themeVar.signal.map { t =>
        val sel = t == id
        s"position:relative;display:flex;flex-direction:column;gap:0;padding:0;overflow:hidden;cursor:pointer;border-radius:7px;border:1px solid ${if (sel) Tokens.accent else Tokens.border};outline:${if (sel) s"1px solid ${Tokens.accent}" else "none"};outline-offset:-2px"
      },
      onClick --> { _ => applyTheme(id) },
      div(styleAttr := s"height:26px;background:$bg;position:relative",
        div(styleAttr := s"position:absolute;top:6px;right:6px;width:8px;height:8px;border-radius:50%;background:$accent;box-shadow:0 0 0 1px rgba(0,0,0,.18)")),
      div(styleAttr := s"font-size:9.5px;font-weight:500;color:${Tokens.ink2};padding:3px 5px;text-align:left;background:${Tokens.panel}", label))

  // ── Inline SVG icon set (#277) ─────────────────────────────────────────────
  // Stroke icons (currentColor) replacing the obscure chrome glyphs. Stroke
  // styling is set via CSS so we only need shape elements + viewBox.
  // Size via the width/height presentation attributes (CSS width on an inline
  // <svg> was being ignored, rendering the icons ~6px); stroke styling stays CSS.
  private val iconStyle =
    "display:block;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round"
  private def ico(shapes: com.raquo.laminar.nodes.ChildNode.Base*): Element =
    svg.svg(svg.viewBox := "0 0 24 24", svg.width := "18", svg.height := "18", svg.style := iconStyle, shapes)
  private def panelRect = svg.rect(svg.x := "3", svg.y := "4", svg.width := "18", svg.height := "16", svg.rx := "2")
  private def iconPanelLeft   = ico(panelRect, svg.line(svg.x1 := "9",  svg.y1 := "4", svg.x2 := "9",  svg.y2 := "20"))
  private def iconPanelBottom = ico(panelRect, svg.line(svg.x1 := "3",  svg.y1 := "15", svg.x2 := "21", svg.y2 := "15"))
  private def iconPanelRight  = ico(panelRect, svg.line(svg.x1 := "15", svg.y1 := "4", svg.x2 := "15", svg.y2 := "20"))
  private def iconMoon = ico(svg.path(svg.d := "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"))
  private def iconSun = ico(
    svg.circle(svg.cx := "12", svg.cy := "12", svg.r := "4"),
    svg.line(svg.x1 := "12", svg.y1 := "1.5", svg.x2 := "12", svg.y2 := "4"),
    svg.line(svg.x1 := "12", svg.y1 := "20", svg.x2 := "12", svg.y2 := "22.5"),
    svg.line(svg.x1 := "1.5", svg.y1 := "12", svg.x2 := "4", svg.y2 := "12"),
    svg.line(svg.x1 := "20", svg.y1 := "12", svg.x2 := "22.5", svg.y2 := "12"),
    svg.line(svg.x1 := "4.8", svg.y1 := "4.8", svg.x2 := "6.5", svg.y2 := "6.5"),
    svg.line(svg.x1 := "17.5", svg.y1 := "17.5", svg.x2 := "19.2", svg.y2 := "19.2"),
    svg.line(svg.x1 := "4.8", svg.y1 := "19.2", svg.x2 := "6.5", svg.y2 := "17.5"),
    svg.line(svg.x1 := "17.5", svg.y1 := "6.5", svg.x2 := "19.2", svg.y2 := "4.8"))
  private def iconSettings = ico( // sliders
    svg.line(svg.x1 := "4", svg.y1 := "7",  svg.x2 := "20", svg.y2 := "7"),
    svg.line(svg.x1 := "4", svg.y1 := "12", svg.x2 := "20", svg.y2 := "12"),
    svg.line(svg.x1 := "4", svg.y1 := "17", svg.x2 := "20", svg.y2 := "17"),
    svg.circle(svg.cx := "15", svg.cy := "7",  svg.r := "2.3"),
    svg.circle(svg.cx := "9",  svg.cy := "12", svg.r := "2.3"),
    svg.circle(svg.cx := "16", svg.cy := "17", svg.r := "2.3"))

  /** The gear button + its frosted settings popover (#274). Sectioned into
    * Display (density + colorize) and Theme (gallery). Esc closes it. */
  private def settingsControl(): Element =
    span(styleAttr := "position:relative;display:inline-flex",
      button(cls := "scl-help-btn", dataAttr("settings-toggle") := "1", title := "View settings",
        onClick --> { _ => settingsOpenVar.update(!_) }, iconSettings),
      child.maybe <-- settingsOpenVar.signal.map { open =>
        if (!open) None else Some(
          div(cls := "scl-set-panel", dataAttr("settings-panel") := "1",
            div(cls := "scl-set-sect", "Display"),
            div(cls := "scl-set-row-label", "Density"),
            densitySegment(),
            div(cls := "scl-set-row-label", styleAttr := "margin-top:8px", "Label size"),
            labelSegment(),
            toggleRow("Colorize components", "toggle-colorize", colorBoxesVar),
            toggleRow("Colorize wires", "toggle-colorize-wires", colorWiresVar),
            toggleRow("Hint bar", "hint-toggle", hintBar.openVar),
            autoOptRow(),
            // Only with an editor: the class under its cursor lights its boxes.
            child.maybe <-- ide.enabledSig.map(on => Option.when(on)(toggleRow("Follow the editor's cursor", "follow-cursor", followCursorVar))),
            div(cls := "scl-set-sect", "Show"),
            declutterRow("Operators", "op"),
            declutterRow("Registers", "reg"),
            declutterRow("Constants", "const"),
            declutterRow("Memories", "mem"),
            declutterRow("State machines", "fsm"),
            declutterRow("Logic blocks", "block"),
            toggleRow("Verification layers", "toggle-layers", showLayersVar),
            // Embedded, the theme follows the host editor: no gallery.
            Option.when(!Embedded.on)(div(cls := "scl-set-sect", "Theme")),
            Option.when(!Embedded.on)(div(cls := "scl-set-gallery",
              themes.map { case (id, label, bg, accent) => themeCard(id, label, bg, accent) })),
          ))
      })

  private def topOf(d: Design): String =
    if (d.top.nonEmpty) d.top else d.modules.headOption.map(_.name).getOrElse("")

  /** Resolve the [[ModuleDef]] currently in view: walk the drilled instance-name
    * path (top, child, grandchild, ...) following each child's declared type. */
  private def currentModule(d: Design, path: List[String]): Option[ModuleDef] =
    if (path.isEmpty) None
    else {
      val byName = d.modules.iterator.map(m => m.name -> m).toMap
      // path.head is a MODULE name (the top, or a module jumped to via the
      // palette); the rest are instance names walked down from there, or the id
      // of an open logic block (its body as a module).
      path.tail.foldLeft(byName.get(path.head)) { (acc, seg) =>
        acc.flatMap { m =>
          m.children.find(_.name == seg).flatMap(c => byName.get(c.`type`))
            .orElse(openBlock(d, m, seg))
        }
      }
    }

  /** Logic block `id` of `m`, as a module to open, when its body is stored. */
  private def openBlock(d: Design, m: ModuleDef, id: String): Option[ModuleDef] =
    m.logic.find(n => n.id == id && n.kind == NodeKind.Block)
      .flatMap(n => d.regions.get(id).map(LayoutCore.blockAsModule(id, _, n.label)))

  /** `mod` as shown: its generator copies folded, but the groups unfolded in it. */
  private def folded(mod: ModuleDef, unfolded: Map[String, Set[String]]): ModuleDef =
    InstanceFold.fold(mod, unfolded.getOrElse(mod.name, Set.empty))

  /** The module in view, folded as shown (for lookups outside the signals). */
  private def shownModule(d: Design, path: List[String]): Option[ModuleDef] =
    currentModule(d, path).map(folded(_, unfoldedVar.now()))

  private val currentModuleSig: Signal[Option[ModuleDef]] =
    Signal.combine(loader.designSig, pathVar.signal, unfoldedVar.signal).map {
      case (Some(d), p, u) => currentModule(d, p).map(folded(_, u))
      case _               => None
    }

  /** Fold or unfold group `id` of the module in view. A selection and expanded
    * bundle pins under a box the change removes (a folded box when unfolding, a
    * copy when folding) are cleared, so the folded view returns to the baked layout. */
  private def toggleFold(module: String, id: String): Unit = {
    val wasUnfolded = unfoldedVar.now().getOrElse(module, Set.empty).contains(id)
    val gone: Set[String] =
      if (!wasUnfolded) Set(id)
      else (for {
        d   <- loader.currentDesign
        mod <- d.modules.find(_.name == module)
        ms  <- InstanceFold.groups(mod).collectFirst { case (`id`, ms) => ms }
      } yield ms.toSet).getOrElse(Set.empty)
    def under(path: String): Boolean = gone(path.takeWhile(_ != '.'))
    // One transaction, so the schematic lays out the new fold state once.
    Var.update(
      unfoldedVar -> { (u: Map[String, Set[String]]) =>
        val s = u.getOrElse(module, Set.empty)
        u.updated(module, if (wasUnfolded) s - id else s + id)
      },
      expandedPortsVar -> { (e: Map[String, Set[String]]) =>
        e.get(module).fold(e)(s => e.updated(module, s.filterNot(under)))
      },
    )
    if (selectedSigVar.now().exists(under)) selectedSigVar.set(None)
  }

  /** Drill into a child instance: append it to the path (mirrors the JS
    * drillInto). Only a module with something inside opens
    * ([[LayoutCore.opensAsModule]]: not a blackbox, and instances, logic or wires). */
  private def drillInto(inst: String): Unit =
    for {
      d        <- loader.currentDesign
      mod      <- currentModule(d, pathVar.now())
      if openBlock(d, mod, inst).isDefined || mod.children.find(_.name == inst).flatMap(c => d.modules.find(_.name == c.`type`))
                                                .exists(LayoutCore.opensAsModule)
    } pathVar.update(_ :+ inst) // selectedSig cleared by the path-change binder

  /** Show `path` when it resolves in the design (the view history's Back and
    * Forward, a fly to another module). */
  private def showPath(path: List[String]): Boolean =
    loader.currentDesign.exists(d => currentModule(d, path).isDefined) && { pathVar.set(path); true }

  /** Instance `inst` of module `module` (the palette, a hierarchy row's
    * locate button): show that module at `at`, else the one in view when it is
    * that module, else the module itself; unfold the copy's group if it is
    * folded away, select the instance and fly to it. The selection does not
    * ask the editor to open the source (a navigation, not an inspection). */
  private def flyToInstance(module: String, inst: String, at: Option[List[String]] = None): Unit =
    for (d <- loader.currentDesign) {
      val cur  = pathVar.now()
      val path = at.getOrElse(if (currentModule(d, cur).exists(_.name == module)) cur else List(module))
      for (mod <- currentModule(d, path)) {
        if (path != cur) pathVar.set(path) // its binder clears the selection first
        InstanceFold.groups(mod).collectFirst { case (g, ms) if ms.contains(inst) => g }
          .filterNot(g => unfoldedVar.now().getOrElse(mod.name, Set.empty).contains(g))
          .foreach(g => toggleFold(mod.name, g))
        if (mod.children.exists(_.name == inst)) { navSelect = Some(inst); selectedSigVar.set(Some(inst)) }
        viewCmds.flyTo(path, inst)
      }
    }

  /** The selection a `socle-navigate` made: the editor already opens its
    * source, so the open-on-select binder skips it once. */
  private var navSelect = Option.empty[String]

  /** `socle-navigate` from the editor's side panel: show `path` (when it
    * resolves) and select `select` when it is an instance there (else clear). */
  private def navigate(path: List[String], select: Option[String]): Unit =
    for {
      d   <- loader.currentDesign
      mod <- currentModule(d, path)
    } {
      pathVar.set(path) // its binder clears the selection first
      // A copy folded away in the schematic is unfolded so it can be shown selected.
      select.foreach { n =>
        InstanceFold.groups(mod).collectFirst { case (g, ms) if ms.contains(n) => g }
          .filterNot(g => unfoldedVar.now().getOrElse(mod.name, Set.empty).contains(g))
          .foreach(g => toggleFold(mod.name, g))
      }
      val sel = select.filter(n => mod.children.exists(_.name == n))
      navSelect = sel
      selectedSigVar.set(sel)
    }

  /** Surfer's Go to declaration: show the module at `loc.instPath`, select
    * what stands for `loc.leaf` there ([[revealSelection]]) and fly to it when
    * it is a box, then open the signal's source when `open` (the Surfer app's
    * language server opened it already). The selection itself opens nothing.
    * Returns the selection made. */
  private def reveal(loc: socle.wave.WaveLinks.Located, open: Boolean): Option[String] =
    loader.currentDesign.flatMap { d =>
      currentModule(d, loc.instPath).flatMap { _ =>
        if (pathVar.now() != loc.instPath) pathVar.set(loc.instPath) // its binder clears the selection first
        val sel = shownModule(d, loc.instPath).flatMap(revealSelection(d, _, loc.leaf))
        navSelect = sel
        selectedSigVar.set(sel)
        sel.filter(s => shownModule(d, loc.instPath).exists(_.logic.exists(_.id == s))).foreach(viewCmds.flyTo(loc.instPath, _))
        if (open) openSource(loc.source)
        sel
      }
    }

  /** What to select in `mod` for a port leaf or a signal: its own logic node
    * (a register), else the box whose region holds it, else the leaf or the
    * nearest bundle above it that [[selectionIn]] resolves. */
  private def revealSelection(d: Design, mod: ModuleDef, leaf: String): Option[String] = {
    def holds(m: ModuleDef, depth: Int): Boolean =
      m.logic.exists(_.id == leaf) || (depth < 8 && m.logic.exists(n => NodeKind.isBox(n.kind) && d.regions.get(n.id).exists(holds(_, depth + 1))))
    def ups(p: String): List[String] = {
      val i = math.max(p.lastIndexOf('.'), p.lastIndexOf('['))
      p :: (if (i > 0) ups(p.substring(0, i)) else Nil)
    }
    mod.logic.find(_.id == leaf).map(_.id)
      .orElse(mod.logic.find(n => NodeKind.isBox(n.kind) && d.regions.get(n.id).exists(holds(_, 0))).map(_.id))
      .orElse(ups(leaf).find(selectionIn(mod, _)))
  }

  /** The `socle-state` message for the editor's side panel: the drill path,
    * the selection and the details of the selected instance, else of the current
    * module; plus, only when the design changed since the last message, the
    * design's hierarchy (`design`: each module's Scala class, kind, children
    * and, when the editor can open it, its source; null without a design). */
  private def sideState(d: Option[Design], path: List[String], sel: Option[String]): js.Any = {
    def design(d: Design) = ujson.Obj(
      "top" -> topOf(d),
      "modules" -> d.modules.map { m =>
        ujson.Obj(
          "name" -> m.name, "scalaClass" -> m.scalaClass, "kind" -> m.kind,
          "source" -> (if (openableSource(d, m.source.file)) ujson.Obj("file" -> m.source.file, "line" -> m.source.line) else ujson.Null),
          "children" -> m.children.map(c => ujson.Obj("name" -> c.name, "type" -> c.`type`)))
      })
    def details(d: Design): ujson.Value = shownModule(d, path).fold[ujson.Value](ujson.Null) { mod =>
      val inst = sel.flatMap(s => mod.children.find(_.name == s))
      val (name, target) = inst match {
        case Some(i) => (i.name, d.modules.find(_.name == i.`type`))
        case None    => (path.lastOption.getOrElse(mod.name), Some(mod))
      }
      target.fold[ujson.Value](ujson.Null) { m =>
        val params =
          if (m.parameterDecls.nonEmpty) m.parameterDecls.map(p => (p.name, p.tpe, p.value))
          else inst.map(_.params).filter(_.nonEmpty).getOrElse(m.parameters).toSeq.sortBy(_._1).map { case (k, v) => (k, "", v) }
        ujson.Obj(
          "name" -> name, "module" -> m.name, "scalaClass" -> m.scalaClass, "kind" -> m.kind,
          "params" -> params.map { case (n, t, v) => ujson.Obj("name" -> n, "type" -> t, "value" -> v) },
          "ports"  -> m.ports.map(p => ujson.Obj("name" -> p.name, "dir" -> p.dir, "type" -> p.bundle)))
      }
    }
    val msg = ujson.Obj(
      "type"      -> "socle-state",
      "path"      -> path,
      "selection" -> sel.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "details"   -> d.fold[ujson.Value](ujson.Null)(details))
    // The hierarchy (about 200 bytes a module) only when the design changed since the last post.
    val first = !postedDesign.exists {
      case Some(x) => d.exists(_ eq x)
      case None    => d.isEmpty
    }
    if (first) {
      msg("design") = d.fold[ujson.Value](ujson.Null)(design)
      postedDesign = Some(d)
    }
    js.JSON.parse(ujson.write(msg))
  }

  /** The design whose hierarchy was last posted to the side panel (compared by identity). */
  private var postedDesign = Option.empty[Option[Design]]

  /** Navigate up to the breadcrumb segment at `idx`; closes an open state
    * diagram even when the path stays (the current module's segment). */
  private def navTo(idx: Int): Unit = { fsmOpenVar.set(None); pathVar.update(_.take(idx + 1)) }

  // ── Hierarchy model ────────────────────────────────────────────────────────
  // `parentMod`: the module the instance is in (None for the top).
  private case class Row(id: String, label: String, tpe: String, depth: Int, isLeaf: Boolean, path: List[String],
                         parentMod: Option[String])

  private def hierarchyRows(d: Design, expanded: Set[String]): List[Row] = {
    val byName = d.modules.iterator.map(m => m.name -> m).toMap
    val out    = scala.collection.mutable.ListBuffer.empty[Row]
    def walk(instName: String, modName: String, p: List[String], depth: Int, parent: Option[String]): Unit = {
      val mod    = byName.get(modName)
      val isLeaf = mod.forall(_.children.isEmpty)
      val id     = p.mkString("/")
      out += Row(id, instName, modName, depth, isLeaf, p, parent)
      if (mod.isDefined && expanded.contains(id))
        mod.get.children.foreach(c => walk(c.name, c.`type`, p :+ c.name, depth + 1, Some(modName)))
    }
    val top = topOf(d)
    if (top.nonEmpty) walk(top, top, List(top), 0, None)
    out.toList
  }

  private def toggle(id: String): Unit =
    expandedVar.update(s => if (s(id)) s - id else s + id)

  // ── View ───────────────────────────────────────────────────────────────────
  private def shell(): Element = {
    // Build the toggleable panes once so hiding/showing them (child.maybe below)
    // preserves their internal state rather than rebuilding (#206).
    val hierEl = hierarchyPane()
    val bottomEl = BottomPanel.render(
      designSig   = loader.designSig,
      moduleSig   = currentModuleSig,
      selectedVar = selectedSigVar,
      hoveredVar  = hoveredSigVar,
      drillInto   = drillInto,
      heightVar   = bottomHeightVar,
      embedded    = Embedded.on,
      wave        = Some(wave),
      tabVar      = bottomTabVar,
    )
    val inspectorEl = Inspector.render(
      designSig          = loader.designSig,
      moduleSig          = currentModuleSig,
      selectedVar        = selectedSigVar,
      hoveredVar         = hoveredSigVar,
      expandedBundlesVar = expandedBundlesVar,
    )
    div(
      dataAttr("app-shell") := "1",
      cls := "scl-shell",
      topBar(),
      Chrome.warningsBanner(loader.designSig),
      div(
        cls := "scl-panes",
        // Embedded, VS Code's side panel shows the hierarchy and the details: no side panes.
        Option.when(!Embedded.on)(child.maybe <-- leftOpenVar.signal.map(o => if (o) Some(hierEl) else None)),
        // Center column: schematic above, the Source/Waveform/Ports panel below.
        div(cls := "scl-center",
          Schematic.render(
            designSig        = loader.designSig,
            moduleSig        = currentModuleSig,
            pathSig          = pathVar.signal,
            selectedVar      = selectedSigVar,
            hoveredVar       = hoveredSigVar,
            expandedPortsVar = expandedPortsVar,
            drillInto        = drillInto,
            toggleFold       = id => for { d <- loader.currentDesign; m <- currentModule(d, pathVar.now()) } toggleFold(m.name, id),
            colorBoxesVar    = colorBoxesVar,
            colorWiresVar    = colorWiresVar,
            hiddenKindsVar   = hiddenKindsVar,
            autoOptimizeVar  = autoOptimizeVar,
            designSnapshot   = () => loader.snapshot(),
            focusClassSig    = Signal.combine(ide.focusClassSig, followCursorVar.signal, focusOffVar.signal).map {
                                 case (c, on, off) => if (!on || off.contains(c)) "" else c
                               },
            onFocusOff       = () => focusOffVar.set(Some(ide.currentFocusClass)),
            showLayersVar    = showLayersVar,
            showShare        = !Embedded.on,
            fsmOpenVar       = fsmOpenVar,
            openSource       = openSource,
            hintBar          = Some(hintBar),
            keysBlocked      = () => helpOpenVar.now(),
            commands         = viewCmds,
            navigate         = showPath,
          ),
          // The first-time tips float above the schematic's bottom edge, over it.
          div(styleAttr := "position:relative;flex-shrink:0",
            Help.firstTips(Embedded.editorPanel, Chrome.macHint),
            hintBar.el),
          child.maybe <-- bottomOpenVar.signal.map(o => if (o) Some(bottomEl) else None),
        ),
        Option.when(!Embedded.on)(child.maybe <-- rightOpenVar.signal.map(o => if (o) Some(inspectorEl) else None)),
      ),
      // Clear the selection and the inspector's bundle tree on navigation. The
      // in-box expansions are kept per module name instead (expandedPortsVar), so
      // a port expanded in one module never expands an identically named port
      // elsewhere (issue #94), and a module shows its own again on return.
      pathVar.signal.changes --> { _ =>
        expandedBundlesVar.set(Set.empty); selectedSigVar.set(None); fsmOpenVar.set(None)
      },
      // Open on select (plan 2): selecting an instance asks the editor to show
      // its module's source: an absolute file, or a repo-relative one embedded
      // in design.sources (the language server resolves it against the
      // workspace). Library modules (e.g. chisel3.util.Queue) are neither.
      // debounce(0) + distinct: only a real selection change opens. Export's
      // withCleared clears and restores the selection synchronously, which
      // coalesces to the unchanged value and is dropped; a user deselect (None)
      // lands as its own event, so selecting the same box again re-opens.
      // A selection made by the side panel's socle-navigate is not opened again.
      // The editor's schematic settings, once they come and on each change.
      ide.stateSig.map(_.flatMap(_.schematicSettings)).distinct --> { (s: Option[socle.schema.SchematicSettings]) => s.foreach(applySchematicSettings) },
      // The cursor on another class (or none) ends the Focus chip's ×.
      ide.focusClassSig.changes --> { (c: String) => if (!focusOffVar.now().contains(c)) focusOffVar.set(None) },
      // The view toggles the browser remembers (the editor's settings set them too).
      colorBoxesVar.signal.changes --> { (b: Boolean) => store("socle-colorize-boxes", if (b) "on" else "off") },
      colorWiresVar.signal.changes --> { (b: Boolean) => store("socle-colorize-wires", if (b) "on" else "off") },
      showLayersVar.signal.changes --> { (b: Boolean) => store("socle-layers", if (b) "on" else "off") },
      hiddenKindsVar.signal.changes --> { (h: Set[String]) => store("socle-hide", h.toSeq.sorted.mkString(",")) },
      selectedSigVar.signal.changes.debounce(0).distinct --> { sel =>
        val fromNav = sel.isDefined && sel == navSelect
        navSelect = None
        if (!fromNav) sel.foreach(openSelectionSource)
      },
      // The editor's side panel follows the design, the drill path and the selection.
      // One post per burst (a navigate sets the path, then the selection).
      Option.when(Embedded.parentOrigin.isDefined)(
        Signal.combine(loader.designSig, pathVar.signal, selectedSigVar.signal).changes.debounce(0) --> {
          case (d, p, sel) => Embedded.postToParent(sideState(d, p, sel))
        }),
      // Command palette + keyboard-help overlays (fixed-position modals).
      Chrome.commandPalette(loader.designSig, paletteOpenVar, onNavigate = p => pathVar.set(p),
        onFlyTo = Some((m: String, c: String) => flyToInstance(m, c)),
        actions = viewActions ++ (if (Embedded.on) Seq(
          ("Open settings",          "density, color",            () => settingsOpenVar.set(true)),
          ("Mouse and keyboard",     "help overlay",              () => helpOpenVar.set(true)),
          ("Toggle bottom panel",    "ports / waveform",          () => bottomOpenVar.update(!_)),
          ("Clear selection",        "deselect wire / node",      () => selectedSigVar.set(None)),
        ) else Seq(
          ("Toggle theme",           "dark / light",              () => toggleTheme()),
          ("Open settings",          "density, theme, color",     () => settingsOpenVar.set(true)),
          ("Mouse and keyboard",     "help overlay",              () => helpOpenVar.set(true)),
          ("Toggle hierarchy panel", "left pane",                 () => leftOpenVar.update(!_)),
          ("Toggle bottom panel",    "source / waveform / ports", () => bottomOpenVar.update(!_)),
          ("Toggle inspector panel", "right pane",                () => rightOpenVar.update(!_)),
          ("Clear selection",        "deselect wire / node",      () => selectedSigVar.set(None)),
        ))),
      Chrome.helpOverlay(helpOpenVar),
      wave.binders,
      // Design-input chrome (#194): drag-drop overlay + transient toasts.
      // Embedded, designs come from the editor's runs only: no drop zone.
      Option.when(!Embedded.on)(dropOverlay()),
      toastEl(),
    )
  }

  // The view history and zoom to the selection, in the palette too: an editor
  // (VS Code) may keep Alt+Left / Alt+Right and the mouse's side buttons.
  private val viewActions: Seq[(String, String, () => Unit)] = Seq(
    ("Back",              "previous view (Alt ←)",          () => viewCmds.back()),
    ("Forward",           "next view (Alt →)",              () => viewCmds.forward()),
    ("Zoom to selection", "frame the selected block or wire (z)", () => { viewCmds.zoomToSelection(); () }),
  )

  private val kHint = if (Chrome.macHint) "⌘K" else "Ctrl K"

  /** "LIVE · updated Ns ago" badge. Mounted only while the source is live; the
    * 1 Hz `tickVar` re-renders the elapsed time. */
  private def liveBadge(): Element = {
    val agoSig: Signal[String] =
      Signal.combine(loader.liveUpdatedAtSig, tickVar.signal).map { case (at, _) =>
        if (at <= 0d) ""
        else {
          val secs = math.max(0L, ((js.Date.now() - at) / 1000d).round)
          if (secs < 60) s"updated ${secs}s ago" else s"updated ${secs / 60}m ago"
        }
      }
    span(cls := "scl-live", dataAttr("live") := "1",
      span(cls := "scl-live-dot"),
      span("LIVE"),
      span(cls := "scl-live-ago", dataAttr("live-ago") <-- agoSig, child.text <-- agoSig),
    )
  }

  /** Top-bar run status from the language server: `running…` while a view run
    * is in flight, `simulating…` while a Simulate run is, the summary line of the error while the last one failed
    * ([[IdeState.errorSummary]]), with the full text in the tooltip. None for
    * idle / ok. */
  private def ideStatus(s: IdeState): Option[Element] = s.status match {
    case "running" =>
      Some(span(cls := "scl-ide-status scl-ide-running", dataAttr("ide-status") := "running",
        title := (if (s.test.nonEmpty) s"running ${s.test}" else "running"), "running…"))
    case "simulating" =>
      Some(span(cls := "scl-ide-status scl-ide-running", dataAttr("ide-status") := "simulating",
        title := (if (s.test.nonEmpty) s"simulating ${s.test}" else "simulating"), "simulating…"))
    case "error" =>
      val short = IdeState.errorSummary(s.message)
      Some(span(cls := "scl-ide-status scl-ide-error", dataAttr("ide-status") := "error",
        title := s.message, short))
    case _ => None
  }

  /** `POST /open` for a selection of the current module, at its own line, when
    * its file is absolute or a key of `design.sources` ([[openableSource]]):
    *  - an instance: its module's class;
    *  - a logic node: its own source (a block or FSM, a register, an operator,
    *    a memory);
    *  - a pin or a wire (a wire selects its sink end, at the row shown): the
    *    first wire that ends there or below, so a block pin opens the crossing
    *    wire's line;
    *  - a port of the module: its declaration, when known. */
  private def openSelectionSource(sel: String): Unit = {
    def at(e: String) = e == sel || e.startsWith(sel + ".") || e.startsWith(sel + "[")
    for {
      d   <- loader.currentDesign
      mod <- shownModule(d, pathVar.now())
      src <- mod.children.find(_.name == sel).flatMap(c => d.modules.find(_.name == c.`type`)).map(_.source)
               .orElse(mod.logic.find(_.id == sel).map(_.source))
               .orElse(mod.wires.find(w => at(w.from)).orElse(mod.wires.find(w => at(w.to))).map(_.source).filter(_.line > 0))
               .orElse(mod.ports.find(_.name == sel).map(_.source))
      if openableSource(d, src.file)
    } ide.open(src.file, src.line)
  }

  /** `POST /open` for a source position (a state diagram's state or
    * transition), under the same [[openableSource]] gate. */
  private def openSource(src: SourceRef): Unit =
    loader.currentDesign.filter(d => openableSource(d, src.file)).foreach(_ => ide.open(src.file, src.line))

  /** Whether the editor can open a module source: an absolute file, or a
    * repo-relative one embedded in design.sources (the language server resolves
    * it against the workspace). Library modules (e.g. chisel3.util.Queue) are neither. */
  private def openableSource(d: Design, file: String): Boolean =
    file.startsWith("/") || file.matches("^[A-Za-z]:[\\\\/].*") || d.sources.contains(file)

  /** Full-window drop-zone overlay, shown while a file is dragged over the page. */
  private def dropOverlay(): Element =
    div(cls := "scl-dropzone", dataAttr("dropzone") := "1",
      cls.toggle("on") <-- loader.dragActiveSig,
      dataAttr("on")   <-- loader.dragActiveSig.map(if (_) "1" else "0"),
      div(cls := "scl-dropzone-card",
        strong("Drop a design.json or .fir file"),
        div(cls := "scl-dropzone-sub", "Socle loads it into the current view (.fir needs a running server)")))

  /** Transient toast for drag-drop / conversion feedback. */
  private def toastEl(): Element =
    div(cls := "scl-toasts",
      child.maybe <-- loader.toastSig.map(_.map { case (msg, kind) =>
        div(cls := s"scl-toast scl-toast-$kind", dataAttr("toast") := kind, msg)
      }))

  private def topBar(): Element =
    div(
      cls := "scl-topbar", dataAttr("topbar") := "1",
      // Embedded, the editor tab names the panel: no logo, picker or theme toggle.
      Option.when(!Embedded.on)(span(cls := "scl-logo", title := "Socle",
        foreignSvgElement(com.raquo.laminar.DomApi.unsafeParseSvgString(Logo.svg)))),
      // Breadcrumb: clickable segments navigate up the hierarchy.
      div(cls := "scl-crumbs",
        // An open state diagram adds a last segment `FSM: <register>`.
        children <-- Signal.combine(pathVar.signal, fsmOpenVar.signal).map { case (p, fsm) =>
          val path = p.zipWithIndex.flatMap { case (seg, i) =>
            val sep: Seq[Element] = if (i > 0) Seq(span(cls := "scl-crumb-sep", "›")) else Nil
            // An open logic block shows its label (`logic`), not its id.
            val block = loader.currentDesign.flatMap(d => currentModule(d, p.take(i)))
              .flatMap(_.logic.find(n => n.id == seg && n.kind == NodeKind.Block))
            sep :+ button(cls := "scl-crumb", cls.toggle("active") := (fsm.isEmpty && i == p.length - 1),
              title := p.take(i + 1).mkString(" > "),
              block.map(n => dataAttr("crumb-block") := n.id),
              onClick --> { _ => navTo(i) }, block.map(_.label).filter(_.nonEmpty).getOrElse(seg): String)
          }
          path ++ fsm.toSeq.flatMap { id =>
            // The FSM box's register (`state`; it may sit in a block's body),
            // else the one an older design's id names (`blk$<module>$fsm$<register>`).
            val state = loader.currentDesign.toSeq
              .flatMap(d => (d.modules.iterator.flatMap(_.logic) ++ d.regions.valuesIterator.flatMap(_.logic)).find(_.id == id))
              .map(_.state).find(_.nonEmpty)
            val reg = state.getOrElse(id.lastIndexOf("$fsm$") match { case i if i >= 0 => id.drop(i + 5); case _ => id })
            Seq(span(cls := "scl-crumb-sep", "›"),
              button(cls := "scl-crumb", cls.toggle("active") := true, dataAttr("crumb-fsm") := id,
                title := "State diagram (Esc closes it)", s"FSM: $reg"))
          }
        }),
      // Search trigger (opens the palette).
      button(cls := "scl-search", dataAttr("search-trigger") := "1",
        title := "Search modules, instances, ports",
        onClick --> { _ => paletteOpenVar.set(true) },
        span("Search modules…"),
        span(cls := "scl-kbd", kHint)),
      span(
        cls := "scl-engine",
        dataAttr("design-loaded") <-- loader.designSig.map(_.isDefined.toString),
        // SOURCE indicator (#194): kind drives the live poller + badge; top is a
        // stable hook the smoke watches to confirm a live reload swapped designs.
        dataAttr("source") <-- loader.sourceSig.map(_.map(_._1).getOrElse("none")),
        dataAttr("top")    <-- loader.designSig.map(_.map(topOf).getOrElse("")),
        child.text <-- Signal.combine(loader.designSig, loader.sourceSig, loader.waitingSig).map {
          case (Some(d), src, _) =>
            val label = src.map(_._2).getOrElse("")
            s"${topOf(d)}  -  ${d.modules.length} modules" + (if (label.nonEmpty) s"  ·  $label" else "")
          case (None, _, true)  => "waiting for the first design…"
          case (None, _, false) => "loading design.json…"
        },
      ),
      // Multi-top design picker (#197): switch between elaborated tops in place.
      // Hidden for a single-top build (designs.json absent -> designsSig empty).
      child.maybe <-- loader.designsSig.map { ds =>
        if (ds.isEmpty || Embedded.on) None
        else Some(
          select(
            cls := "scl-design-picker", dataAttr("design-picker") := "1",
            title := "Switch design (multi-top build)",
            value <-- loader.activeDesignSig,
            disabled <-- loader.loadingSig,
            onChange.mapToValue --> { p =>
              loader.selectDesign(p, ds.find(_._3 == p).map(_._2).getOrElse(p))
            },
            ds.map { case (_, label, path) =>
              option(value := path, dataAttr("design-option") := path, label)
            },
          ),
        )
      },
      // LIVE badge + "updated Ns ago" (#120): only while showing the live design.
      child.maybe <-- loader.sourceSig.map(s => if (s.exists(_._1 == "live")) Some(liveBadge()) else None),
      // Language-server run status (plan 2): nothing unless a run is in flight or failed.
      child.maybe <-- ide.stateSig.map(_.flatMap(ideStatus)),
      // Panel toggles (#206): hide the hierarchy / bottom / inspector panel to
      // focus on the schematic. The `off` class dims a button when its pane is hidden.
      Option.when(!Embedded.on)(button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "hierarchy",
        cls.toggle("off") <-- leftOpenVar.signal.map(!_),
        title := "Toggle the hierarchy panel",
        onClick --> { _ => leftOpenVar.update(!_) }, iconPanelLeft)),
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "bottom",
        cls.toggle("off") <-- bottomOpenVar.signal.map(!_),
        title := "Toggle the bottom panel",
        onClick --> { _ => bottomOpenVar.update(!_) }, iconPanelBottom),
      Option.when(!Embedded.on)(button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "inspector",
        cls.toggle("off") <-- rightOpenVar.signal.map(!_),
        title := "Toggle the inspector panel",
        onClick --> { _ => rightOpenVar.update(!_) }, iconPanelRight)),
      // View settings (#198): density + accent presets.
      settingsControl(),
      // Theme toggle (issue #2): sun while dark (click -> light), moon while light.
      Option.when(!Embedded.on)(button(cls := "scl-theme-btn", dataAttr("theme-toggle") := "1",
        dataAttr("theme") <-- themeVar.signal,
        title <-- themeVar.signal.map(t => if (darkThemes(t)) "Switch to light theme" else "Switch to dark theme"),
        onClick --> { _ => toggleTheme() },
        child <-- themeVar.signal.map(t => if (darkThemes(t)) iconSun else iconMoon))),
      // Help affordance.
      button(cls := "scl-help-btn", dataAttr("help-trigger") := "1", title := "Mouse and keyboard (?)",
        onClick --> { _ => helpOpenVar.set(true) }, "?"),
    )

  private def hierarchyPane(): Element = {
    val rowsSignal = Signal.combine(loader.designSig, expandedVar.signal).map {
      case (Some(d), exp) => hierarchyRows(d, exp).map(r => (d, r))
      case (None, _)      => Nil
    }
    div(
      cls := "scl-pane scl-pane-hierarchy", dataAttr("pane") := "hierarchy",
      div(cls := "scl-pane-title",
        span("HIERARCHY"),
        span(cls := "scl-count", child.text <-- rowsSignal.map(_.length.toString))),
      div(cls := "scl-hier",
        // Error / empty note when nothing loaded.
        child.maybe <-- Signal.combine(loader.designSig, loader.errorSig, loader.waitingSig).map {
          case (None, _, true)      =>
            Some(div(cls := "scl-pane-body", dataAttr("design-waiting") := "1", "waiting for the first design…"))
          case (None, Some(err), _) => Some(div(cls := "scl-pane-body", s"no design: $err"))
          case (None, None, _)      => Some(div(cls := "scl-pane-body", "loading…"))
          case _                    => None
        },
        children <-- rowsSignal.map(_.map { case (d, r) => row(d, r) }),
      ),
    )
  }

  private def row(d: Design, r: Row): Element = {
    val toggleMods: Seq[Modifier[HtmlElement]] =
      if (r.isLeaf) Seq.empty
      else Seq(
        dataAttr("hier-toggle") := r.id,
        onClick --> { (ev: dom.MouseEvent) => ev.stopPropagation(); toggle(r.id) },
        child.text <-- expandedVar.signal.map(s => if (s(r.id)) "▾" else "▸"),
      )
    div(
      cls := "scl-hier-row",
      cls.toggle("active") <-- pathVar.signal.map(_.mkString("/") == r.id),
      dataAttr("hier-path") := r.id,
      dataAttr("hier-leaf") := (if (r.isLeaf) "1" else "0"),
      styleAttr := s"padding-left: ${4 + r.depth * 14}px",
      title := r.path.mkString(" > "),
      onClick --> { _ => pathVar.set(r.path) },
      span(cls := "scl-hier-toggle", toggleMods),
      span(cls := "scl-hier-glyph", Glyphs.of(d, r.tpe)),
      span(cls := "scl-hier-label", r.label),
      span(cls := "scl-hier-type", r.tpe),
      // Show in parent: the parent module, this instance's box selected and flown to.
      r.parentMod.map { pm =>
        button(cls := "scl-hier-locate", dataAttr("hier-locate") := r.id, title := "Show in parent",
          aria.label := s"Show ${r.label} in its parent",
          onClick --> { (ev: dom.MouseEvent) => ev.stopPropagation(); flyToInstance(pm, r.label, Some(r.path.init)) },
          "◎")
      },
    )
  }

}
