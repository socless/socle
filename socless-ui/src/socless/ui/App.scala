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
  private val themeVar       = Var[String]("light")             // "light" | "dark" (issue #2)
  // Settings panel view preferences (#198): density + accent, persisted and
  // applied as <html> attributes (also set before paint in the template).
  private val settingsOpenVar = Var(false)
  private val densityVar      = Var[String]("comfortable")      // "comfortable" | "compact"
  private val accentVar       = Var[String]("default")          // "default" | "green" | "violet" | "amber" | "rose"
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
    densityVar.set(currentDensity()); accentVar.set(currentAccent()) // ditto for #198 prefs
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
      } else if (e.key == "Escape") {
        paletteOpenVar.set(false); helpOpenVar.set(false); settingsOpenVar.set(false)
      }
    })

  // ── Theme toggle (issue #2) ────────────────────────────────────────────────
  // The set-before-paint script in the template sets data-theme="dark" from the
  // saved choice / OS preference; light is the default (no attribute). The toggle
  // flips data-theme on <html>, persists to localStorage, and mirrors into
  // themeVar so the button glyph re-renders.
  private def currentTheme(): String =
    if (dom.document.documentElement.getAttribute("data-theme") == "dark") "dark" else "light"

  private def applyTheme(theme: String): Unit = {
    if (theme == "dark") dom.document.documentElement.setAttribute("data-theme", "dark")
    else dom.document.documentElement.removeAttribute("data-theme")
    try dom.window.localStorage.setItem("socless-theme", theme) catch { case _: Throwable => () }
    themeVar.set(theme)
  }

  private def toggleTheme(): Unit = applyTheme(if (currentTheme() == "dark") "light" else "dark")

  // ── Settings panel: density + accent (#198) ────────────────────────────────
  // Same mechanism as the theme toggle: reflect an <html> attribute (also set
  // before paint), persist to localStorage, mirror into a Var so the panel
  // re-renders. "comfortable"/"default" are the no-attribute baselines.
  private def currentDensity(): String =
    if (dom.document.documentElement.getAttribute("data-density") == "compact") "compact" else "comfortable"
  private def applyDensity(d: String): Unit = {
    if (d == "compact") dom.document.documentElement.setAttribute("data-density", "compact")
    else dom.document.documentElement.removeAttribute("data-density")
    try dom.window.localStorage.setItem("socless-density", d) catch { case _: Throwable => () }
    densityVar.set(d)
  }
  private def currentAccent(): String =
    Option(dom.document.documentElement.getAttribute("data-accent")).filter(_.nonEmpty).getOrElse("default")
  private def applyAccent(a: String): Unit = {
    if (a == "default") dom.document.documentElement.removeAttribute("data-accent")
    else dom.document.documentElement.setAttribute("data-accent", a)
    try dom.window.localStorage.setItem("socless-accent", a) catch { case _: Throwable => () }
    accentVar.set(a)
  }

  // Accent preset id -> (label, light-theme preview swatch). The CSS in the
  // template re-hues the accent triple per preset for both themes; the swatch
  // shows the light-theme color so the choices read on the panel.
  private val accentPresets: List[(String, String, String)] = List(
    ("default", "Blue",   "oklch(0.55 0.16 250)"),
    ("green",   "Green",  "oklch(0.55 0.15 150)"),
    ("violet",  "Violet", "oklch(0.55 0.18 300)"),
    ("amber",   "Amber",  "oklch(0.62 0.15 70)"),
    ("rose",    "Rose",   "oklch(0.58 0.18 20)"),
  )

  private def densityBtn(id: String, label: String): Element =
    button(dataAttr("density-option") := id,
      styleAttr <-- densityVar.signal.map { d =>
        val sel = d == id
        s"flex:1;border:1px solid ${if (sel) Tokens.accent else Tokens.border};background:${if (sel) Tokens.accentBg else "transparent"};color:${if (sel) Tokens.accent else Tokens.ink2};border-radius:5px;padding:4px 6px;cursor:pointer;font-family:inherit;font-size:11px"
      },
      onClick --> { _ => applyDensity(id) }, label)

  private def accentSwatch(id: String, label: String, color: String): Element =
    button(dataAttr("accent-option") := id, title := label,
      styleAttr <-- accentVar.signal.map { a =>
        s"width:22px;height:22px;border-radius:50%;cursor:pointer;background:$color;border:2px solid ${if (a == id) Tokens.ink else Tokens.border}"
      },
      onClick --> { _ => applyAccent(id) })

  /** The gear button + its settings popover (#198). Anchored relative so the
    * popover drops under the gear; Esc closes it (see installKeyboard). */
  private def settingsControl(): Element =
    span(styleAttr := "position:relative;display:inline-flex",
      button(cls := "scl-help-btn", dataAttr("settings-toggle") := "1", title := "View settings",
        onClick --> { _ => settingsOpenVar.update(!_) }, "⚙"),
      child.maybe <-- settingsOpenVar.signal.map { open =>
        if (!open) None else Some(
          div(dataAttr("settings-panel") := "1",
            styleAttr := s"position:absolute;top:32px;right:0;z-index:50;width:200px;background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,.18);padding:10px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink2};display:flex;flex-direction:column;gap:12px",
            div(
              div(styleAttr := s"color:${Tokens.ink4};margin-bottom:5px", "Density"),
              div(styleAttr := "display:flex;gap:6px",
                densityBtn("comfortable", "Comfortable"), densityBtn("compact", "Compact"))),
            div(
              div(styleAttr := s"color:${Tokens.ink4};margin-bottom:5px", "Accent"),
              div(styleAttr := "display:flex;gap:8px;flex-wrap:wrap",
                accentPresets.map { case (id, label, color) => accentSwatch(id, label, color) })),
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
      Chrome.commandPalette(loader.designSig, paletteOpenVar, onNavigate = p => pathVar.set(p)),
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
        onClick --> { _ => leftOpenVar.update(!_) }, "⊢"),
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "bottom",
        cls.toggle("off") <-- bottomOpenVar.signal.map(!_),
        title := "Toggle the bottom panel",
        onClick --> { _ => bottomOpenVar.update(!_) }, "⊥"),
      button(cls := "scl-help-btn scl-panel-btn", dataAttr("toggle-pane") := "inspector",
        cls.toggle("off") <-- rightOpenVar.signal.map(!_),
        title := "Toggle the inspector panel",
        onClick --> { _ => rightOpenVar.update(!_) }, "⊣"),
      // View settings (#198): density + accent presets.
      settingsControl(),
      // Theme toggle (issue #2): sun while dark (click -> light), moon while light.
      button(cls := "scl-theme-btn", dataAttr("theme-toggle") := "1",
        dataAttr("theme") <-- themeVar.signal,
        title <-- themeVar.signal.map(t => if (t == "dark") "Switch to light theme" else "Switch to dark theme"),
        onClick --> { _ => toggleTheme() },
        child.text <-- themeVar.signal.map(t => if (t == "dark") "☀" else "☾")),
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
