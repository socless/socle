package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.js.timers._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.util.{Success, Failure}
import socless.schema.{Design, ModuleDef}
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

  // ── State ──────────────────────────────────────────────────────────────────
  private val designVar   = Var[Option[Design]](None)
  private val pathVar     = Var[List[String]](Nil)     // drilled module path (instance names)
  private val expandedVar = Var[Set[String]](Set.empty) // expanded hierarchy row ids (path-joined)
  private val errorVar    = Var[Option[String]](None)
  // Schematic + inspector shared state (Phase 2 / Phase 3).
  private val selectedSigVar   = Var[Option[String]](None)   // selected wire / instance path
  private val hoveredSigVar    = Var[Option[String]](None)
  private val expandedPortsVar = Var[Set[String]](Set.empty) // in-box expanded bundle pin paths
  private val expandedBundlesVar = Var[Set[String]](Set.empty) // inspector bundle-tree expansion
  private val paletteOpenVar = Var(false)                       // command palette (Ctrl/Cmd+K)
  private val helpOpenVar    = Var(false)                       // keyboard-help overlay (?)
  private val themeVar       = Var[String]("light")             // "light" | "dark" (issue #2)
  // Where the active design came from (#194): "live" (fetched design.json),
  // "snapshot" (embedded), "file" (dropped .json), "fir" (dropped .fir). The
  // tuple is (kind, human label). None until the first design loads.
  private val sourceVar       = Var[Option[(String, String)]](None)
  private val liveUpdatedAtVar = Var[Double](0d)                // ms of the last live auto-reload; 0 = never
  private val dragActiveVar    = Var(false)                     // drop-zone overlay visibility
  private val toastVar         = Var[Option[(String, String)]](None) // (message, kind: info|ok|err)
  private val tickVar          = Var(0)                         // 1 Hz clock for the "updated Ns ago" badge
  private var toastTimer       = Option.empty[SetTimeoutHandle]
  private var liveWatchStarted = false
  // Last-Modified of the live design currently shown. The poller compares the
  // server's stamp against this, so a change that lands between the initial
  // load and the first poll is still caught (no baseline race). None = unknown.
  private var liveBaseline     = Option.empty[String]

  @JSExport
  def main(rootId: String): String = {
    val container = dom.document.getElementById(rootId)
    if (container == null) return "no-root"
    themeVar.set(currentTheme()) // sync with the set-before-paint script in the template
    render(container, shell())
    installKeyboard()
    installDragDrop()
    setInterval(1000d) { tickVar.update(_ + 1) } // drives the "updated Ns ago" badge
    loadDesign()
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
        paletteOpenVar.set(false); helpOpenVar.set(false)
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

  // ── Design loading ───────────────────────────────────────────────────────
  private def msgOf(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  /** Install a freshly loaded design, resetting navigation to its top. `source`
    * is (kind, label) recording where it came from (#194). */
  private def setDesign(d: Design, source: (String, String)): Unit = {
    val top = topOf(d)
    designVar.set(Some(d))
    pathVar.set(if (top.nonEmpty) List(top) else Nil)
    expandedVar.set(if (top.nonEmpty) Set(top) else Set.empty) // top expanded -> shows its children
    sourceVar.set(Some(source))
    errorVar.set(None)
  }

  /** Live reload (#120): swap in a re-converted design but keep the user's
    * drill path/expansion when it still resolves, so re-elaboration does not
    * throw away navigation. Stamps the "updated Ns ago" clock. */
  private def reloadLiveDesign(d: Design): Unit = {
    designVar.set(Some(d))
    if (currentModule(d, pathVar.now()).isEmpty) {
      val top = topOf(d)
      pathVar.set(if (top.nonEmpty) List(top) else Nil)
      expandedVar.set(if (top.nonEmpty) Set(top) else Set.empty)
    }
    sourceVar.set(Some(("live", "live (design.json)")))
    liveUpdatedAtVar.set(js.Date.now())
    errorVar.set(None)
  }

  private def loadDesign(): Unit = {
    // A self-contained snapshot (the share button) inlines the design as an
    // embedded block; prefer it so the page opens from file:// with no fetch.
    val embedded = Option(dom.document.getElementById("socless-embedded-design"))
      .map(_.textContent).filter(_.trim.nonEmpty)
    embedded match {
      case Some(txt) =>
        try { val d = upickle.default.read[Design](txt); setDesign(d, ("snapshot", s"${topOf(d)} (snapshot)")) }
        catch { case e: Throwable => errorVar.set(Some(msgOf(e))) }
      case None =>
        dom.fetch("design.json").flatMap { r =>
          if (r.ok) { liveBaseline = headerStamp(r); r.text() }
          else Future.failed(new RuntimeException(s"HTTP ${r.status}"))
        }.map(txt => upickle.default.read[Design](txt)).onComplete {
          case Success(d) => setDesign(d, ("live", "live (design.json)")); startLiveWatch()
          case Failure(e) => errorVar.set(Some(msgOf(e)))
        }
    }
  }

  /** The freshness stamp of a response: Last-Modified, else ETag, else None. */
  private def headerStamp(r: dom.Response): Option[String] =
    Option(r.headers.get("Last-Modified")).filter(_.nonEmpty)
      .orElse(Option(r.headers.get("ETag")).filter(_.nonEmpty))

  // ── Design input: drag-drop a .json / .fir (#194) ──────────────────────────
  private def hasFiles(e: dom.DragEvent): Boolean = {
    val dt = e.dataTransfer
    dt != null && dt.types != null && dt.types.exists(_ == "Files")
  }

  /** Window-level drag-drop: a dropped `.json` loads directly; a dropped `.fir`
    * is POSTed to the standalone server's `/convert`. Ref-counts enter/leave so
    * the overlay does not flicker across nested children (mirrors the JS UI). */
  private def installDragDrop(): Unit = {
    var dragDepth = 0
    dom.window.addEventListener("dragenter", (e: dom.DragEvent) =>
      if (hasFiles(e)) { e.preventDefault(); dragDepth += 1; dragActiveVar.set(true) })
    dom.window.addEventListener("dragover", (e: dom.DragEvent) =>
      if (hasFiles(e)) { e.preventDefault(); if (e.dataTransfer != null) e.dataTransfer.dropEffect = dom.DataTransferDropEffectKind.copy })
    dom.window.addEventListener("dragleave", (e: dom.DragEvent) =>
      if (hasFiles(e)) { dragDepth = math.max(0, dragDepth - 1); if (dragDepth == 0) dragActiveVar.set(false) })
    dom.window.addEventListener("drop", (e: dom.DragEvent) => {
      val dt = e.dataTransfer
      if (dt != null && dt.files.length > 0) {
        e.preventDefault(); dragDepth = 0; dragActiveVar.set(false)
        val f    = dt.files(0)
        val name = if (f.name.nonEmpty) f.name else "file"
        if (name.toLowerCase.endsWith(".fir")) loadFirFromFile(f, name)
        else if (name.toLowerCase.endsWith(".json")) loadJsonFromFile(f, name)
        else toast(s"expected a .json or .fir file, got $name", "err")
      }
    })
  }

  private def loadJsonFromFile(f: dom.File, name: String): Unit =
    f.text().onComplete {
      case Success(text) =>
        try setDesign(upickle.default.read[Design](text), ("file", name))
        catch { case e: Throwable => toast(s"couldn't read $name: ${msgOf(e)}", "err") }
      case Failure(e) => toast(s"couldn't read $name: ${msgOf(e)}", "err")
    }

  /** Convert a dropped CHIRRTL `.fir` via `POST /convert` (served by the
    * standalone server). A network failure means no server is running. */
  private def loadFirFromFile(f: dom.File, name: String): Unit = {
    toast(s"converting $name…", "info")
    f.text().onComplete {
      case Failure(e) => toast(s"couldn't read $name: ${msgOf(e)}", "err")
      case Success(text) =>
        val init = js.Dynamic.literal(
          method  = "POST",
          headers = js.Dynamic.literal("Content-Type" -> "text/plain; charset=utf-8"),
          body    = text,
        ).asInstanceOf[dom.RequestInit]
        dom.fetch("/convert", init).onComplete {
          case Failure(_) =>
            toast("POST /convert failed: no SoCLess server running. Start one with: mill example.soclessServe", "err")
          case Success(resp) if resp.status == 200 =>
            resp.text().onComplete {
              case Success(json) =>
                try setDesign(upickle.default.read[Design](json), ("fir", name))
                catch { case e: Throwable => toast(s"couldn't load $name: ${msgOf(e)}", "err") }
              case Failure(e) => toast(s"couldn't load $name: ${msgOf(e)}", "err")
            }
          case Success(resp) =>
            resp.text().recover { case _ => resp.statusText }.foreach { m =>
              toast(s"conversion failed (${resp.status}): ${m.take(120)}", "err")
            }
        }
    }
  }

  private def toast(msg: String, kind: String): Unit = {
    toastVar.set(Some((msg, kind)))
    toastTimer.foreach(clearTimeout)
    toastTimer = Some(setTimeout(if (kind == "err") 6000d else 3000d) { toastVar.set(None) })
  }

  /** Live watch (#120): while showing the live design and the tab is visible,
    * poll design.json's Last-Modified and reload in place when it changes, so
    * re-running elaboration (`serve --watch`, `soclessUI -w`) refreshes the
    * schematic with no manual reload. Started once, after the first live load. */
  private def startLiveWatch(): Unit = {
    if (liveWatchStarted) return
    liveWatchStarted = true
    def poll(): Unit = {
      if (!sourceVar.now().exists(_._1 == "live")) return
      if (dom.document.visibilityState.asInstanceOf[String] != "visible") return
      val init = js.Dynamic.literal(method = "HEAD", cache = "no-store").asInstanceOf[dom.RequestInit]
      dom.fetch("design.json", init).foreach { r =>
        if (r.ok) headerStamp(r).foreach { stamp =>
          liveBaseline match {
            case None              => liveBaseline = Some(stamp)     // no stamp from load; baseline now
            case Some(s) if s != stamp =>
              liveBaseline = Some(stamp)                             // changed: reload in place
              dom.fetch("design.json").flatMap(rr => rr.text())
                .map(t => upickle.default.read[Design](t))
                .foreach(d => reloadLiveDesign(d))
            case _ => ()                                            // unchanged
          }
        }
      }
    }
    setInterval(1500d) { poll() }
  }

  /** Raw JSON + top of the active design, for the snapshot/share button. */
  private def designSnapshot(): Option[(String, String)] =
    designVar.now().map(d => (upickle.default.write(d), topOf(d)))

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
    Signal.combine(designVar.signal, pathVar.signal).map {
      case (Some(d), p) => currentModule(d, p)
      case _            => None
    }

  /** Drill into a child instance: append it to the path (mirrors the JS
    * drillInto). Leaves and blackboxes have no internals and are not drilled. */
  private def drillInto(inst: String): Unit =
    for {
      d        <- designVar.now()
      mod      <- currentModule(d, pathVar.now())
      child    <- mod.children.find(_.name == inst)
      childMod <- d.modules.find(_.name == child.`type`)
      if childMod.kind != "external" && childMod.kind != "intrinsic"
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
  private def shell(): Element =
    div(
      dataAttr("app-shell") := "1",
      cls := "scl-shell",
      topBar(),
      Chrome.warningsBanner(designVar.signal),
      div(
        cls := "scl-panes",
        hierarchyPane(),
        // Center column: schematic above, the Source/Waveform/Ports panel below.
        div(cls := "scl-center",
          Schematic.render(
            designSig        = designVar.signal,
            moduleSig        = currentModuleSig,
            pathSig          = pathVar.signal,
            selectedVar      = selectedSigVar,
            hoveredVar       = hoveredSigVar,
            expandedPortsVar = expandedPortsVar,
            drillInto        = drillInto,
            designSnapshot   = () => designSnapshot(),
          ),
          BottomPanel.render(
            designSig   = designVar.signal,
            moduleSig   = currentModuleSig,
            selectedVar = selectedSigVar,
            hoveredVar  = hoveredSigVar,
            drillInto   = drillInto,
          ),
        ),
        Inspector.render(
          designSig          = designVar.signal,
          moduleSig          = currentModuleSig,
          selectedVar        = selectedSigVar,
          hoveredVar         = hoveredSigVar,
          expandedBundlesVar = expandedBundlesVar,
        ),
      ),
      // Expansion paths are instance-relative, not scoped to the current module:
      // clear them (and the selection) on navigation so expanding a port in one
      // module does not auto-expand an identically named port elsewhere (issue #94).
      pathVar.signal.changes --> { _ =>
        expandedPortsVar.set(Set.empty); expandedBundlesVar.set(Set.empty); selectedSigVar.set(None)
      },
      // Command palette + keyboard-help overlays (fixed-position modals).
      Chrome.commandPalette(designVar.signal, paletteOpenVar, onNavigate = p => pathVar.set(p)),
      Chrome.helpOverlay(helpOpenVar),
      // Design-input chrome (#194): drag-drop overlay + transient toasts.
      dropOverlay(),
      toastEl(),
    )

  private val kHint = if (Chrome.macHint) "⌘K" else "Ctrl K"

  /** "LIVE · updated Ns ago" badge. Mounted only while the source is live; the
    * 1 Hz `tickVar` re-renders the elapsed time. */
  private def liveBadge(): Element = {
    val agoSig: Signal[String] =
      Signal.combine(liveUpdatedAtVar.signal, tickVar.signal).map { case (at, _) =>
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
      cls.toggle("on") <-- dragActiveVar.signal,
      dataAttr("on")   <-- dragActiveVar.signal.map(if (_) "1" else "0"),
      div(cls := "scl-dropzone-card",
        strong("Drop a design.json or .fir file"),
        div(cls := "scl-dropzone-sub", "SoCLess loads it into the current view (.fir needs a running server)")))

  /** Transient toast for drag-drop / conversion feedback. */
  private def toastEl(): Element =
    div(cls := "scl-toasts",
      child.maybe <-- toastVar.signal.map(_.map { case (msg, kind) =>
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
        dataAttr("design-loaded") <-- designVar.signal.map(_.isDefined.toString),
        // SOURCE indicator (#194): kind drives the live poller + badge; top is a
        // stable hook the smoke watches to confirm a live reload swapped designs.
        dataAttr("source") <-- sourceVar.signal.map(_.map(_._1).getOrElse("none")),
        dataAttr("top")    <-- designVar.signal.map(_.map(topOf).getOrElse("")),
        child.text <-- Signal.combine(designVar.signal, sourceVar.signal).map {
          case (Some(d), src) =>
            val label = src.map(_._2).getOrElse("")
            s"${topOf(d)}  -  ${d.modules.length} modules" + (if (label.nonEmpty) s"  ·  $label" else "")
          case (None, _) => "loading design.json…"
        },
      ),
      // LIVE badge + "updated Ns ago" (#120): only while showing the live design.
      child.maybe <-- sourceVar.signal.map(s => if (s.exists(_._1 == "live")) Some(liveBadge()) else None),
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
    val rowsSignal = Signal.combine(designVar.signal, expandedVar.signal).map {
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
        child.maybe <-- Signal.combine(designVar.signal, errorVar.signal).map {
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
