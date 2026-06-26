package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.timers._
import socless.schema.{Design, ModuleDef, ModuleKind}
import socless.ui.data.DesignLoader
import socless.ui.theme._
import socless.ui.model._
import socless.ui.schematic._
import socless.ui.panels._

/** The Scala.js + Laminar frontend (epic #172).
  *
  * Phase 1: load design.json into a typed [[Design]], hold app state in Laminar
  * `Var`s, and render the navigable module hierarchy. The schematic + inspector
  * panes follow in later phases. The design is parsed once with the shared
  * upickle ReadWriters - no `window.DESIGN` JS objects. */
@JSExportTopLevel("SoclessUI")
object App {

  // ── Navigation / view state (design + source state lives in [[DesignLoader]]) ─
  private val pathVar     = Var[List[String]](Nil)     // drilled module path (instance names)
  private val expandedVar = Var[Set[String]](Set.empty) // expanded hierarchy row ids (path-joined)
  // Schematic + inspector shared state (Phase 2 / Phase 3).
  private val selectedSigVar   = Var[Option[String]](None)   // selected wire / instance path
  private val hoveredSigVar    = Var[Option[String]](None)
  private val expandedPortsVar = Var[Set[String]](Set.empty) // in-box expanded bundle pin paths
  private val expandedBundlesVar = Var[Set[String]](Set.empty) // inspector bundle-tree expansion
  private val paletteOpenVar = Var(false)                       // command palette (Ctrl/Cmd+K)
  private val helpOpenVar    = Var(false)                       // keyboard-help overlay (?)
  private val themeVar       = Var[String]("light")             // active theme id (#2 / #275)
  // Settings panel view preferences, persisted and applied as <html> attributes
  // (also set before paint in the template): theme id (#275) + density (#198).
  private val settingsOpenVar = Var(false)
  private val densityVar      = Var[String]("comfortable")      // "compact" | "comfortable" | "comfy"
  // Colorize toggles (#266) live in the settings panel now (#274); App owns them
  // and passes them into the schematic.
  private val colorBoxesVar   = Var(false)
  private val colorWiresVar   = Var(false)
  private val tickVar        = Var(0)                           // 1 Hz clock for the "updated Ns ago" badge
  // Panel layout (#206): toggle the side / bottom panels to focus on the
  // schematic; the bottom panel is also drag-resizable.
  private val leftOpenVar      = Var(true)
  private val rightOpenVar     = Var(true)
  private val bottomOpenVar    = Var(true)
  private val bottomHeightVar  = Var(240.0)

  // Design loading + live poll + drag-drop + toasts (#219), owning the design /
  // source state. App injects how to reset navigation when a design lands.
  private def navResetToTop(d: Design): Unit = {
    val top = topOf(d)
    pathVar.set(if (top.nonEmpty) List(top) else Nil)
    expandedVar.set(if (top.nonEmpty) Set(top) else Set.empty) // top expanded -> shows its children
  }
  // Live reload keeps the drill path/expansion when it still resolves (#120).
  private def navReconcile(d: Design): Unit =
    if (currentModule(d, pathVar.now()).isEmpty) navResetToTop(d)

  private val loader = new DesignLoader(topOf = topOf, onFresh = navResetToTop, onLive = navReconcile)

  @JSExport
  def main(rootId: String): String = {
    val container = dom.document.getElementById(rootId)
    if (container == null) return "no-root"
    themeVar.set(currentTheme()) // sync with the set-before-paint script in the template
    densityVar.set(currentDensity())
    render(container, shell())
    installKeyboard()
    loader.installDragDrop()
    setInterval(1000d) { tickVar.update(_ + 1) } // drives the "updated Ns ago" badge
    loader.start() // load design.json + the multi-top picker index (#197)
    "mounted"
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
      } else if (!typing && !mod && e.key == "/") {
        // Focus the schematic filter (#276). The input carries data-filter-input.
        Option(dom.document.querySelector("[data-filter-input]")).foreach { el =>
          e.preventDefault(); el.asInstanceOf[dom.html.Element].focus()
        }
      } else if (e.key == "Escape") {
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
    * persist, and mirror into themeVar so the gallery + toggle re-render. */
  private def applyTheme(id: String): Unit = {
    val root = dom.document.documentElement
    if (id == "light") root.removeAttribute("data-theme") else root.setAttribute("data-theme", id)
    if (darkThemes(id)) root.setAttribute("data-mode", "dark") else root.removeAttribute("data-mode")
    try dom.window.localStorage.setItem("socless-theme", id) catch { case _: Throwable => () }
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
    try dom.window.localStorage.setItem("socless-density", d) catch { case _: Throwable => () }
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

  // An iOS-style toggle row bound to a boolean Var (#274).
  private def toggleRow(label: String, hook: String, v: Var[Boolean]): Element =
    div(cls := "scl-tg-row",
      span(label),
      button(cls := "scl-tg", dataAttr(hook) := "1",
        dataAttr("on") <-- v.signal.map(b => if (b) "1" else "0"),
        onClick --> { _ => v.update(!_) }, i()))

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
  private val iconStyle =
    "width:15px;height:15px;display:block;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round"
  private def ico(shapes: com.raquo.laminar.nodes.ChildNode.Base*): Element =
    svg.svg(svg.viewBox := "0 0 24 24", svg.style := iconStyle, shapes)
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
            toggleRow("Colorize components", "toggle-colorize", colorBoxesVar),
            toggleRow("Colorize wires", "toggle-colorize-wires", colorWiresVar),
            div(cls := "scl-set-sect", "Theme"),
            div(cls := "scl-set-gallery",
              themes.map { case (id, label, bg, accent) => themeCard(id, label, bg, accent) }),
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
      // palette); the rest are instance names walked down from there.
      path.tail.foldLeft(byName.get(path.head)) { (acc, instName) =>
        for { m <- acc; c <- m.children.find(_.name == instName); cm <- byName.get(c.`type`) } yield cm
      }
    }

  private val currentModuleSig: Signal[Option[ModuleDef]] =
    Signal.combine(loader.designSig, pathVar.signal).map {
      case (Some(d), p) => currentModule(d, p)
      case _            => None
    }

  /** Drill into a child instance: append it to the path (mirrors the JS
    * drillInto). Leaves and blackboxes have no internals and are not drilled. */
  private def drillInto(inst: String): Unit =
    for {
      d        <- loader.currentDesign
      mod      <- currentModule(d, pathVar.now())
      child    <- mod.children.find(_.name == inst)
      childMod <- d.modules.find(_.name == child.`type`)
      if childMod.kind != ModuleKind.External && childMod.kind != ModuleKind.Intrinsic
    } pathVar.update(_ :+ inst) // selectedSig / expandedPorts cleared by the path-change binder

  /** Navigate up to the breadcrumb segment at `idx`. */
  private def navTo(idx: Int): Unit = pathVar.update(_.take(idx + 1))

  // ── Hierarchy model ────────────────────────────────────────────────────────
  private case class Row(id: String, label: String, tpe: String, depth: Int, isLeaf: Boolean, path: List[String])

  private def hierarchyRows(d: Design, expanded: Set[String]): List[Row] = {
    val byName = d.modules.iterator.map(m => m.name -> m).toMap
    val out    = scala.collection.mutable.ListBuffer.empty[Row]
    def walk(instName: String, modName: String, p: List[String], depth: Int): Unit = {
      val mod    = byName.get(modName)
      val isLeaf = mod.forall(_.children.isEmpty)
      val id     = p.mkString("/")
      out += Row(id, instName, modName, depth, isLeaf, p)
      if (mod.isDefined && expanded.contains(id))
        mod.get.children.foreach(c => walk(c.name, c.`type`, p :+ c.name, depth + 1))
    }
    val top = topOf(d)
    if (top.nonEmpty) walk(top, top, List(top), 0)
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
        child.maybe <-- leftOpenVar.signal.map(o => if (o) Some(hierEl) else None),
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
            colorBoxesVar    = colorBoxesVar,
            colorWiresVar    = colorWiresVar,
            designSnapshot   = () => loader.snapshot(),
          ),
          child.maybe <-- bottomOpenVar.signal.map(o => if (o) Some(bottomEl) else None),
        ),
        child.maybe <-- rightOpenVar.signal.map(o => if (o) Some(inspectorEl) else None),
      ),
      // Expansion paths are instance-relative, not scoped to the current module:
      // clear them (and the selection) on navigation so expanding a port in one
      // module does not auto-expand an identically named port elsewhere (issue #94).
      pathVar.signal.changes --> { _ =>
        expandedPortsVar.set(Set.empty); expandedBundlesVar.set(Set.empty); selectedSigVar.set(None)
      },
      // Command palette + keyboard-help overlays (fixed-position modals).
      Chrome.commandPalette(loader.designSig, paletteOpenVar, onNavigate = p => pathVar.set(p),
        actions = Seq(
          ("Toggle theme",           "dark / light",              () => toggleTheme()),
          ("Open settings",          "density, theme, color",     () => settingsOpenVar.set(true)),
          ("Keyboard shortcuts",     "help overlay",              () => helpOpenVar.set(true)),
          ("Toggle hierarchy panel", "left pane",                 () => leftOpenVar.update(!_)),
          ("Toggle bottom panel",    "source / waveform / ports", () => bottomOpenVar.update(!_)),
          ("Toggle inspector panel", "right pane",                () => rightOpenVar.update(!_)),
          ("Clear selection",        "deselect wire / node",      () => selectedSigVar.set(None)),
        )),
      Chrome.helpOverlay(helpOpenVar),
      // Design-input chrome (#194): drag-drop overlay + transient toasts.
      dropOverlay(),
      toastEl(),
    )
  }

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

  /** Full-window drop-zone overlay, shown while a file is dragged over the page. */
  private def dropOverlay(): Element =
    div(cls := "scl-dropzone", dataAttr("dropzone") := "1",
      cls.toggle("on") <-- loader.dragActiveSig,
      dataAttr("on")   <-- loader.dragActiveSig.map(if (_) "1" else "0"),
      div(cls := "scl-dropzone-card",
        strong("Drop a design.json or .fir file"),
        div(cls := "scl-dropzone-sub", "SoCLess loads it into the current view (.fir needs a running server)")))

  /** Transient toast for drag-drop / conversion feedback. */
  private def toastEl(): Element =
    div(cls := "scl-toasts",
      child.maybe <-- loader.toastSig.map(_.map { case (msg, kind) =>
        div(cls := s"scl-toast scl-toast-$kind", dataAttr("toast") := kind, msg)
      }))

  private def topBar(): Element =
    div(
      cls := "scl-topbar", dataAttr("topbar") := "1",
      span(cls := "scl-logo", "SoCLess"),
      // Breadcrumb: clickable segments navigate up the hierarchy.
      div(cls := "scl-crumbs",
        children <-- pathVar.signal.map { p =>
          p.zipWithIndex.flatMap { case (seg, i) =>
            val sep: Seq[Element] = if (i > 0) Seq(span(cls := "scl-crumb-sep", "›")) else Nil
            sep :+ button(cls := "scl-crumb", cls.toggle("active") := (i == p.length - 1),
              title := p.take(i + 1).mkString(" > "),
              onClick --> { _ => navTo(i) }, seg)
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
        child.text <-- Signal.combine(loader.designSig, loader.sourceSig).map {
          case (Some(d), src) =>
            val label = src.map(_._2).getOrElse("")
            s"${topOf(d)}  -  ${d.modules.length} modules" + (if (label.nonEmpty) s"  ·  $label" else "")
          case (None, _) => "loading design.json…"
        },
      ),
      // Multi-top design picker (#197): switch between elaborated tops in place.
      // Hidden for a single-top build (designs.json absent -> designsSig empty).
      child.maybe <-- loader.designsSig.map { ds =>
        if (ds.isEmpty) None
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
      // Panel toggles (#206): hide the hierarchy / bottom / inspector panel to
      // focus on the schematic. The `off` class dims a button when its pane is hidden.
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "hierarchy",
        cls.toggle("off") <-- leftOpenVar.signal.map(!_),
        title := "Toggle the hierarchy panel",
        onClick --> { _ => leftOpenVar.update(!_) }, iconPanelLeft),
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "bottom",
        cls.toggle("off") <-- bottomOpenVar.signal.map(!_),
        title := "Toggle the bottom panel",
        onClick --> { _ => bottomOpenVar.update(!_) }, iconPanelBottom),
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "inspector",
        cls.toggle("off") <-- rightOpenVar.signal.map(!_),
        title := "Toggle the inspector panel",
        onClick --> { _ => rightOpenVar.update(!_) }, iconPanelRight),
      // View settings (#198): density + accent presets.
      settingsControl(),
      // Theme toggle (issue #2): sun while dark (click -> light), moon while light.
      button(cls := "scl-theme-btn", dataAttr("theme-toggle") := "1",
        dataAttr("theme") <-- themeVar.signal,
        title <-- themeVar.signal.map(t => if (darkThemes(t)) "Switch to light theme" else "Switch to dark theme"),
        onClick --> { _ => toggleTheme() },
        child <-- themeVar.signal.map(t => if (darkThemes(t)) iconSun else iconMoon)),
      // Help affordance.
      button(cls := "scl-help-btn", dataAttr("help-trigger") := "1", title := "Keyboard shortcuts (?)",
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
        child.maybe <-- Signal.combine(loader.designSig, loader.errorSig).map {
          case (None, Some(err)) => Some(div(cls := "scl-pane-body", s"no design: $err"))
          case (None, None)      => Some(div(cls := "scl-pane-body", "loading…"))
          case _                 => None
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
    )
  }

}
