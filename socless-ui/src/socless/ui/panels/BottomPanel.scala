package socless.ui.panels

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ChildNode
import org.scalajs.dom
import scala.scalajs.js
import socless.schema.{Design, ModuleDef, PortDir}
import socless.ui.theme._
import socless.ui.wave.{ColorLegend, RemotePane, SurferPane, WaveFollow}

/** The bottom panel under the schematic (epic #172 Phase 3 follow-up): a tabbed
  * Source / Waveform / Ports view. Behavioral port of the JS `BenchBottomPanel`
  * family (ui/SoCLess.html:4351+).
  *
  *   - Source   : the module's `.scala` source (from `Design.sources`, keyed by
  *                `ModuleDef.source.file`) with line numbers, target-line +
  *                selected-signal highlighting, auto-scroll, and a lightweight
  *                Scala tokenizer whose instance names drill in. Falls back to a
  *                locator notice when no source was embedded. Each file renders
  *                once; a selection or module change only moves the highlight.
  *   - Waveform : the embedded Surfer ([[socless.ui.wave.SurferPane]]) when the
  *                language server's viewer is `surfer-panel` and a trace exists,
  *                following the selection ([[socless.ui.wave.WaveFollow]]);
  *                otherwise a note on how to get a trace (Simulate). Its canvas is
  *                mounted once, outside the per-selection body. The VS Code panel
  *                mounts no Surfer: it drives the Surfer tab
  *                ([[socless.ui.wave.RemotePane]]) and shows its notes, its state
  *                and a Show Surfer button.
  *   - Ports    : a table of this module's ports plus each child instance's. */
object BottomPanel {

  // Open-in-editor presets (#123): id, label, URL template ({path}/{line}).
  // The chosen editor persists in localStorage so it survives reloads; an empty
  // template means "off". Right-clicking the chip cycles through them.
  private val EditorPresets: List[(String, String, String)] = List(
    ("vscode", "VS Code",   "vscode://file/{path}:{line}"),
    ("idea",   "IntelliJ",  "idea://open?file={path}&line={line}"),
    ("off",    "no editor", ""),
  )
  private def presetOf(id: String): (String, String, String) =
    EditorPresets.find(_._1 == id).getOrElse(EditorPresets.head)
  private def loadEditor(): String =
    try Option(dom.window.localStorage.getItem("socless-editor"))
      .filter(id => EditorPresets.exists(_._1 == id)).getOrElse("vscode")
    catch { case _: Throwable => "vscode" }
  private val editorVar = Var[String](loadEditor())
  private def cycleEditor(): Unit = {
    val ids  = EditorPresets.map(_._1)
    val next = ids((ids.indexOf(editorVar.now()) + 1) % ids.size)
    editorVar.set(next)
    try dom.window.localStorage.setItem("socless-editor", next) catch { case _: Throwable => () }
  }

  // Scala token colors (port the JS tokenizeScala palette); resolved from `:root`
  // via `Tokens` so they recolor under dark theme (#220).
  private val KwCol   = Tokens.syntaxKw
  private val TyCol   = Tokens.syntaxTy
  private val LitCol  = Tokens.syntaxLit
  private val Keywords = Set("class","extends","val","def","new","for","until","object","trait","case","match","if","else","when","otherwise")
  private val Types    = Set("Module","IO","Bundle","Vec","Flipped","Decoupled","Queue","RRArbiter","UInt","SInt","Bool","Clock","Reset","Reg","RegInit","Mem","DontCare")
  private val TokRe    = """[^\s(){}.,;]+|\s+|[(){}.,;]""".r

  def render(
    designSig: Signal[Option[Design]],
    moduleSig: Signal[Option[ModuleDef]],
    selectedVar: Var[Option[String]],
    hoveredVar: Var[Option[String]],
    drillInto: String => Unit,
    heightVar: Var[Double],
    // Embedded in an editor (VS Code): the editor shows the source and runs
    // the simulations, so only the Ports tab is left.
    embedded: Boolean = false,
    // The waveform follower (None: no Waveform viewer) and the selected tab,
    // owned by the caller so a new trace can show the Waveform tab.
    wave: Option[WaveFollow] = None,
    tabVar: Var[String] = Var("source"),
  ): Element = {
    def setSel(p: String): Unit = selectedVar.set(Some(p))
    val waveViewSig: Signal[Option[WaveFollow.View]] =
      wave.fold[Signal[Option[WaveFollow.View]]](Val(None))(_.viewSig.map(Some(_)))
    // Embedded, the Waveform tab shows only for the panel viewer (the editor runs the app otherwise).
    val waveTabSig: Signal[Boolean] =
      if (!embedded) Val(true) else waveViewSig.map(_.exists(_.viewer == WaveFollow.Panel))
    // The tab shown: a hidden Waveform tab falls back to Ports.
    val shownTabSig: Signal[String] = Signal.combine(tabVar.signal, waveTabSig).map {
      case ("wave", false) => "ports"
      case (t, _)          => t
    }

    // Drag the top edge to resize the panel (#206). Document-level listeners so
    // the drag keeps tracking even if the cursor leaves the thin handle.
    def onResizeDown(e: dom.MouseEvent): Unit = {
      e.preventDefault()
      val startY = e.clientY
      val startH = heightVar.now()
      val move: js.Function1[dom.MouseEvent, Unit] = (ev: dom.MouseEvent) =>
        heightVar.set(math.max(80.0, math.min(700.0, startH - (ev.clientY - startY))))
      var up: js.Function1[dom.MouseEvent, Unit] = null
      up = (_: dom.MouseEvent) => {
        dom.document.removeEventListener("mousemove", move)
        dom.document.removeEventListener("mouseup", up)
      }
      dom.document.addEventListener("mousemove", move)
      dom.document.addEventListener("mouseup", up)
    }

    def tabButton(id: String, icon: String, label: String): Element =
      button(
        dataAttr("bottom-tab") := id,
        styleAttr <-- shownTabSig.map(t =>
          s"border:none;background:transparent;cursor:pointer;padding:0 14px;font-size:11.5px;font-family:inherit;" +
            s"color:${if (t == id) Tokens.ink else Tokens.ink3};font-weight:${if (t == id) 600 else 500};" +
            s"border-bottom:2px solid ${if (t == id) Tokens.accent else "transparent"};display:flex;align-items:center;gap:6px"),
        onClick --> { _ => tabVar.set(id) },
        span(styleAttr := s"font-family:${Tokens.mono};color:${Tokens.ink4}", icon), label)

    val infoSig: Signal[ChildNode.Base] =
      Signal.combine(shownTabSig, moduleSig, designSig).map {
        case ("source", Some(mod), Some(d)) => sourcePathChip(mod.source.file, mod.source.line, d)
        case ("wave", _, _)                 => span(styleAttr := "display:inline-flex;align-items:center;gap:8px",
          span(child.text <-- waveInfo(wave)),
          // The colours chip, beside Surfer's status.
          child.maybe <-- wave.fold[Signal[Option[Element]]](Val(None))(w => surferShown(w).map(on =>
            Option.when(on)(ColorLegend.chip(w.colorByDirectionSig)))))
        case ("ports", Some(mod), _)        => span(s"${mod.children.length} instances")
        case _                              => emptyNode
      }

    // The Ports tab is rebuilt per module and selection, only while it shows.
    val portsSig: Signal[ChildNode.Base] =
      Signal.combine(shownTabSig, moduleSig, selectedVar.signal, designSig).map {
        case ("ports", Some(mod), sel, Some(d)) => Some((mod, sel, d))
        case _                                  => None
      }.distinctByFn {
        case (Some((m1, s1, d1)), Some((m2, s2, d2))) => (m1 eq m2) && s1 == s2 && (d1 eq d2)
        case (a, b)                                   => a.isEmpty && b.isEmpty
      }.map {
        case Some((mod, _, d)) => portsView(mod, d, setSel)
        case None              => emptyNode // the Source and Waveform panes are not rebuilt per selection
      }
    // The Source tab renders a file once and moves its highlight: mounted only
    // while it shows (never when embedded, where the tab is hidden).
    val source: Option[SourcePane] =
      Option.when(!embedded)(new SourcePane(moduleSig, selectedVar.signal, designSig, drillInto))
    val sourceSig: Signal[ChildNode.Base] =
      shownTabSig.map(_ == "source").distinct.map(on => source.filter(_ => on).fold[ChildNode.Base](emptyNode)(_.root))

    div(
      cls := "scl-bottom", dataAttr("bottom-panel") := "1",
      styleAttr <-- heightVar.signal.map(h => s"height:${h}px"),
      div(cls := "scl-bottom-resize", dataAttr("bottom-resize") := "1",
        onMouseDown --> { (e: dom.MouseEvent) => onResizeDown(e) }),
      div(cls := "scl-bottom-tabs",
        Option.when(!embedded)(tabButton("source", "⌥", "Source")),
        child.maybe <-- waveTabSig.map(on => Option.when(on)(tabButton("wave", "∿", "Waveform"))),
        tabButton("ports", "⊕", "Ports"),
        div(styleAttr := "flex:1"),
        div(styleAttr := s"display:flex;align-items:center;gap:6px;padding:0 12px;font-size:11px;font-family:${Tokens.mono};color:${Tokens.ink4};overflow:hidden;white-space:nowrap;max-width:50%",
          child <-- infoSig),
      ),
      div(cls := "scl-bottom-body", child <-- sourceSig, child <-- portsSig,
        waveformPane(wave, waveViewSig, shownTabSig.map(_ == "wave"))),
    )
  }

  /** Source-tab header chip: the file path plus an open-in-editor link (#123).
    * The link resolves the absolute path against `Design.sourceRoot` and hands
    * off to the OS via the chosen editor's URL scheme; right-click cycles the
    * editor (VS Code / IntelliJ / off, persisted). Disabled when off or when no
    * absolute path is available (e.g. a shared snapshot). */
  private def sourcePathChip(file: String, line: Int, d: Design): Element = {
    val absPath =
      if (file.startsWith("/")) Some(file)
      else if (d.sourceRoot.nonEmpty && file.nonEmpty) Some(d.sourceRoot.replaceAll("/$", "") + "/" + file)
      else None
    val ln = if (line > 0) line else 1
    // The OS-handoff URL for an editor id, or None when off / no absolute path.
    def urlFor(id: String): Option[String] = {
      val tmpl = presetOf(id)._3
      if (tmpl.isEmpty) None
      else absPath.map(p => tmpl.replace("{path}", p).replace("{line}", ln.toString))
    }
    div(styleAttr := "display:flex;align-items:center;gap:6px;min-width:0;overflow:hidden",
      span(title := absPath.getOrElse(file), styleAttr := "overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:420px", file),
      if (file.nonEmpty) button(dataAttr("open-editor") := "1",
        dataAttr("editor") <-- editorVar.signal,
        title <-- editorVar.signal.map { id =>
          val (_, label, tmpl) = presetOf(id)
          if (urlFor(id).isDefined) s"Open in $label at line $ln (right-click to switch editor)"
          else if (tmpl.nonEmpty) "No absolute path available (re-export with --source-root); right-click to switch editor"
          else "Open-in-editor is off; right-click to choose an editor"
        },
        disabled <-- editorVar.signal.map(id => urlFor(id).isEmpty),
        styleAttr <-- editorVar.signal.map { id =>
          val on = urlFor(id).isDefined
          s"border:1px solid ${Tokens.borderSoft};border-radius:4px;background:transparent;color:${if (on) Tokens.ink2 else Tokens.ink4};cursor:${if (on) "pointer" else "default"};font-family:${Tokens.mono};font-size:10px;line-height:14px;padding:0 5px"
        },
        onClick      --> { _ => urlFor(editorVar.now()).foreach(u => dom.window.location.href = u) },
        onContextMenu --> { (e: dom.MouseEvent) => e.preventDefault(); cycleEditor() },
        "↗") else emptyNode)
  }

  // ── Source ──────────────────────────────────────────────────────────────────
  /** The Source tab. A file is tokenized and rendered once, then kept in a small
    * cache keyed by its path and text (a live reload brings new text for the
    * same path). A selection or a module change rewrites only one stylesheet:
    * the lit lines, the selected instance's name and the current module's
    * children as drill links. Every colour is a theme variable, so a theme
    * change needs no render. The pane is mounted only while its tab shows. */
  private final class SourcePane(
    moduleSig: Signal[Option[ModuleDef]],
    selectedSig: Signal[Option[String]],
    designSig: Signal[Option[Design]],
    drillInto: String => Unit,
  ) {
    private final class FileView(val file: String, val text: String, val lines: Array[String], val grid: ChildNode.Base)
    private val CacheSize = 4
    private var cache: List[FileView] = Nil // the most recent first
    private val blank: ChildNode.Base = emptyNode
    private val contentVar = Var[ChildNode.Base](blank)
    private var shown: ChildNode.Base = blank
    private var noticeKey: String = null
    private var notice: ChildNode.Base = blank
    // The shown module's instance names: the words that drill in.
    private var children: Set[String] = Set.empty
    private val hlStyle = styleTag()
    private var hlCss = ""
    // The file:line last scrolled to; reset when the pane leaves the page (a
    // new mount starts at the top, so it scrolls to the target again).
    private var scrolledFor: String = null
    private var scrollSeq = 0
    // The module the grid last showed: a new one scrolls to its declaration.
    private var shownModule: String = null

    val root: Element = div(cls := "scl-src", dataAttr("source-view") := "1", styleAttr := "height:100%;overflow:auto",
      styleTag(SourceCss), hlStyle,
      child <-- contentVar.signal,
      // A child's name drills in; the selected instance's name does nothing.
      onClick --> { (e: dom.MouseEvent) => wordOf(e).filter(children).foreach(drillInto) },
      onMouseOver --> { (e: dom.MouseEvent) =>
        wordOf(e).foreach { w =>
          val el = e.target.asInstanceOf[dom.Element]
          if (children(w)) el.setAttribute("title", s"Open $w")
          else if (el.hasAttribute("title")) el.removeAttribute("title")
        }
      },
      onUnmountCallback(_ => scrolledFor = null),
      Signal.combine(moduleSig, selectedSig, designSig) --> { case (m, s, d) => update(m, s, d) },
    )

    private def wordOf(e: dom.Event): Option[String] = e.target match {
      case el: dom.Element if el.hasAttribute("data-w") => Some(el.getAttribute("data-w"))
      case _                                            => None
    }

    // A new content starts at the top: forget the last scroll, so the next
    // target scrolls into view even if it is the same file:line as before.
    private def show(n: ChildNode.Base): Unit = if (n ne shown) { shown = n; scrolledFor = null; contentVar.set(n) }

    private def setHighlight(css: String): Unit = if (css != hlCss) { hlCss = css; hlStyle.ref.textContent = css }

    private def update(mod: Option[ModuleDef], selected: Option[String], design: Option[Design]): Unit =
      (mod, design) match {
        case (Some(m), Some(d)) =>
          val file = m.source.file
          // Jump to the SELECTED component's source line when it lives in this file
          // (#228): look the selection up among instances / logic nodes / wires. Fall
          // back to the module declaration line.
          val selLine: Option[Int] = selected.flatMap { s =>
            val head = s.split('.').head
            m.children.find(_.name == head).map(_.source)
              .orElse(m.logic.find(_.id == head).map(_.source))
              .orElse(m.wires.find(w => w.from == s || w.to == s).map(_.source))
              .filter(r => r.line > 0 && r.file == file).map(_.line)
          }
          val targetLine = selLine.getOrElse(m.source.line)
          (if (file.nonEmpty) d.sources.get(file) else None) match {
            case None =>
              setHighlight("")
              val key = s"$file:$targetLine"
              if (key != noticeKey) { noticeKey = key; notice = noticeView(file, targetLine) }
              show(notice)
            case Some(text) =>
              val f = fileView(file, text)
              children = m.children.map(_.name).toSet
              show(f.grid)
              setHighlight(highlightCss(f, selected, targetLine))
              // Scroll on navigating to a new module (its decl line) or selecting a
              // component; not on merely deselecting, which would yank back up. Once
              // per file:line, a frame later (the grid is laid out by then), and only
              // for the latest update.
              val key           = s"$file:$targetLine"
              val fileChanged   = scrolledFor == null || !scrolledFor.startsWith(s"$file:")
              val moduleChanged = m.name != shownModule
              shownModule = m.name
              if (targetLine > 0 && (selLine.isDefined || fileChanged || moduleChanged)) {
                scrollSeq += 1
                val seq = scrollSeq
                dom.window.requestAnimationFrame { _ =>
                  val parent = root.ref
                  if (seq == scrollSeq && dom.document.contains(parent) && key != scrolledFor) {
                    scrolledFor = key
                    val el = parent.querySelector(s"""[data-line="$targetLine"]""").asInstanceOf[dom.html.Element]
                    if (el != null) {
                      val delta = el.getBoundingClientRect().top - parent.getBoundingClientRect().top
                      parent.scrollTop = math.max(0.0, parent.scrollTop + delta - parent.clientHeight / 4.0)
                    }
                  }
                }
              }
          }
        case _ =>
          setHighlight("")
          show(blank)
      }

    private def fileView(file: String, text: String): FileView =
      cache.find(v => v.file == file && v.text == text) match {
        case Some(v) =>
          cache = v :: cache.filterNot(_ eq v)
          v
        case None =>
          val v = render(file, text)
          cache = (v :: cache).take(CacheSize)
          v
      }

    /** The file as a two-column grid (line number, code), built with the DOM
      * directly: it is static, so it needs no Laminar element per token. The
      * identifiers carry `data-w`, which the highlight rules and the clicks key on. */
    private def render(file: String, text: String): FileView = {
      val doc   = dom.document
      val lines = text.split("\n", -1)
      val grid  = doc.createElement("div").asInstanceOf[dom.html.Element]
      grid.className = "scl-src-grid"
      lines.indices.foreach { i =>
        val n = doc.createElement("div")
        n.setAttribute("class", "scl-src-n")
        n.setAttribute("data-line", (i + 1).toString)
        n.textContent = (i + 1).toString
        val c = doc.createElement("div")
        c.setAttribute("class", "scl-src-c")
        TokRe.findAllIn(lines(i)).foreach(p => c.appendChild(token(p)))
        grid.appendChild(n)
        grid.appendChild(c)
      }
      new FileView(file, text, lines, foreignHtmlElement(grid))
    }

    private def token(p: String): dom.Node = {
      def styled(style: String): dom.Node = {
        val s = dom.document.createElement("span")
        s.setAttribute("style", style)
        s.textContent = p
        s
      }
      if (p.trim.isEmpty || p.length == 1 && "(){}.,;".contains(p)) dom.document.createTextNode(p)
      else if (Keywords.contains(p)) styled(s"color:$KwCol;font-weight:500")
      else if (Types.contains(p)) styled(s"color:$TyCol")
      else if (p == "true" || p == "false" || p.forall(_.isDigit)) styled(s"color:$LitCol")
      else if (p.startsWith("//")) styled(s"color:${Tokens.ink4};font-style:italic")
      else {
        val s = dom.document.createElement("span")
        s.setAttribute("data-w", p)
        s.textContent = p
        s
      }
    }

    /** The rules for the current module and selection: the lines that mention the
      * selected instance or port, plus the target line; the selected instance's
      * name; the module's children as drill links (they win over the selection). */
    private def highlightCss(f: FileView, selected: Option[String], targetLine: Int): String = {
      val selInst = selected.map(_.split('.').head).filter(_.nonEmpty)
      val selPort = selected.map(_.split('.').toSeq.take(2).mkString(".")).filter(_.nonEmpty)
      val lit = f.lines.indices.collect {
        case i if i + 1 == targetLine || selInst.exists(f.lines(i).contains(_)) || selPort.exists(f.lines(i).contains(_)) => i + 1
      }
      def esc(w: String): String = js.Dynamic.global.CSS.applyDynamic("escape")(w).asInstanceOf[String]
      def words(ws: Iterable[String]): String = ws.map(w => s""".scl-src [data-w="${esc(w)}"]""").mkString(",")
      val sb = new StringBuilder
      if (lit.nonEmpty) {
        sb ++= lit.map(l => s""".scl-src [data-line="$l"]""").mkString(",")
        sb ++= s"{color:${Tokens.accent};background:${Tokens.accentBg}}"
        sb ++= lit.map(l => s""".scl-src [data-line="$l"]+div""").mkString(",")
        sb ++= s"{background:${Tokens.accentBg};border-left-color:${Tokens.accent}}"
      }
      if (targetLine > 0) sb ++= s""".scl-src [data-line="$targetLine"]{font-weight:600}"""
      selInst.foreach { s =>
        sb ++= words(Seq(s))
        sb ++= s"{background:${Tokens.linkSelBg};border-radius:2px;padding:0 1px;cursor:pointer;color:${Tokens.ink}}"
      }
      if (children.nonEmpty) {
        sb ++= words(children.toSeq.sorted)
        sb ++= s"{background:${Tokens.linkChildBg};color:${Tokens.linkChildFg};border-radius:2px;padding:0 2px;cursor:pointer;text-decoration:underline dotted;text-underline-offset:2px}"
      }
      sb.result()
    }
  }

  // The Source tab's fixed rules; the lit lines and words come per selection.
  private val SourceCss: String =
    s".scl-src-grid{font-family:${Tokens.mono};font-size:11.5px;line-height:1.6;padding:8px 0;display:grid;grid-template-columns:auto 1fr;column-gap:12px}" +
    s".scl-src-n{text-align:right;padding:0 4px;user-select:none;color:${Tokens.ink4};font-variant-numeric:tabular-nums}" +
    s".scl-src-c{padding:0 14px 0 6px;border-left:2px solid transparent;white-space:pre;color:${Tokens.ink2}}"

  private def noticeView(file: String, targetLine: Int): Element =
    div(styleAttr := s"padding:20px 22px;font-size:12px;color:${Tokens.ink2};line-height:1.55;max-width:520px",
      div(styleAttr := s"font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink};margin-bottom:6px",
        s"${if (file.nonEmpty) file else "(no source recorded)"}${if (targetLine > 0) s":$targetLine" else ""}"),
      div(styleAttr := s"color:${Tokens.ink3}",
        "Source text was not embedded in design.json. Re-run elaboration with --source-root (or use the Mill plugin) so the exporter includes the files."))

  // ── Waveform ────────────────────────────────────────────────────────────────
  /** The Waveform tab's body, built once: the notes (paused, missing signals),
    * the hint when there is nothing to show, and the Surfer canvas, which stays
    * mounted (hidden on other tabs) so it survives selection and tab changes. */
  private def waveformPane(wave: Option[WaveFollow], viewSig: Signal[Option[WaveFollow.View]], shownSig: Signal[Boolean]): Element = {
    val surferSig = viewSig.map(_.exists(v => v.viewer == WaveFollow.Panel && v.trace.nonEmpty))
    val noteStyle = s"padding:4px 12px;font-size:11px;font-family:${Tokens.mono};color:${Tokens.ink3};border-bottom:1px solid ${Tokens.borderSoft}"
    div(dataAttr("wave-pane") := "1",
      styleAttr <-- shownSig.map(on => s"position:absolute;inset:0;display:${if (on) "flex" else "none"};flex-direction:column"),
      child.maybe <-- viewSig.map(_.flatMap(_.pausedFor).map(t =>
        div(dataAttr("wave-paused") := "1", styleAttr := noteStyle,
          s"Following paused: the trace is of $t, not of the test the panel shows. Simulate this test to follow it."))),
      child.maybe <-- viewSig.map(_.filter(v => v.missing > 0 && v.pausedFor.isEmpty).map(v =>
        div(dataAttr("wave-missing") := "1", styleAttr := noteStyle,
          s"${v.missing} signal${if (v.missing == 1) "" else "s"} not in the trace"))),
      child.maybe <-- Signal.combine(viewSig, surferSig).map { case (v, surfer) =>
        Option.when(!surfer)(div(dataAttr("wave-hint") := "1",
          styleAttr := s"display:flex;align-items:center;justify-content:center;flex:1;font-size:12px;color:${Tokens.ink4};padding:24px;text-align:center",
          waveHint(v, remote = wave.exists(_.pane.isInstanceOf[RemotePane]))))
      },
      wave.map(w => w.pane match {
        case p: SurferPane =>
          div(styleAttr <-- surferSig.map(on => s"display:${if (on) "flex" else "none"};flex:1;min-height:0"), p.canvasHost)
        case r: RemotePane =>
          // VS Code: Surfer is in its own editor tab; this one only reveals or reopens it.
          div(dataAttr("wave-remote") := "1",
            styleAttr <-- surferSig.map(on => s"display:${if (on) "flex" else "none"};flex:1;flex-direction:column;align-items:center;justify-content:center;gap:10px;font-size:12px;color:${Tokens.ink4};padding:24px;text-align:center"),
            span("The waveforms are in the Surfer tab, which follows the selection."),
            button(dataAttr("show-surfer") := "1",
              styleAttr := s"border:1px solid ${Tokens.border};background:${Tokens.panel};color:${Tokens.ink};border-radius:5px;padding:4px 12px;font-family:inherit;font-size:12px;cursor:pointer",
              onClick.compose(_.sample(viewSig)) --> { v =>
                r.open(v.fold("")(x => SurferPane.absolute(x.trace)), w.traceTest, force = true)
              }, "Show Surfer"))
        case _ => emptyNode
      }),
    )
  }

  /** Whether the Waveform tab's header shows Surfer's status (Surfer has a trace). */
  private def surferShown(w: WaveFollow): Signal[Boolean] =
    w.viewSig.map(v => v.viewer == WaveFollow.Panel && v.trace.nonEmpty).distinct

  /** The Waveform tab's header text: Surfer's status, or "no trace". */
  private def waveInfo(wave: Option[WaveFollow]): Signal[String] = wave match {
    case None => Val("no trace")
    case Some(w) => Signal.combine(surferShown(w), w.pane.statusVar.signal).map {
      case (true, st) => s"Surfer: $st"
      case _          => "no trace"
    }
  }

  private def waveHint(v: Option[WaveFollow.View], remote: Boolean): String = v match {
    case Some(x) if remote && x.viewer == WaveFollow.Panel && x.trace.isEmpty =>
      "No trace yet: run Simulate on a ChiselSim test (the Simulate lens above it in the editor); its waveforms open in a Surfer tab."
    case Some(x) if x.viewer == WaveFollow.App && x.trace.nonEmpty =>
      "The waveforms are in the Surfer app, which follows the selection."
    case Some(x) if x.viewer == "custom" && x.trace.nonEmpty =>
      "The waveforms are in your viewer (socless.waveViewerCommand)."
    case Some(x) if x.viewer.nonEmpty =>
      "No trace yet: run Simulate on a ChiselSim test (the Simulate lens above it in the editor) to see its waveforms here."
    case _ =>
      "No waveform data: open this design from the editor (the SoCLess language server) and run Simulate on its test to see its waveforms."
  }

  // ── Ports ─────────────────────────────────────────────────────────────────
  private final case class PortRow(inst: String, tpe: String, port: String, dir: String, bundle: String, doc: String, connected: Boolean)

  private def portsView(mod: ModuleDef, d: Design, setSel: String => Unit): Element = {
    val rows = scala.collection.mutable.ListBuffer.empty[PortRow]
    mod.ports.foreach(p => rows += PortRow("(this)", mod.name, p.name, p.dir, p.bundle, p.doc, p.connected))
    mod.children.foreach { c =>
      d.modules.find(_.name == c.`type`).foreach(_.ports.foreach(p =>
        rows += PortRow(c.name, c.`type`, p.name, p.dir, p.bundle, p.doc, p.connected)))
    }
    val all = rows.toSeq
    // Sortable + filterable ports table (#276). Sort by any column (click to
    // toggle direction); a quick filter narrows by any text column. Width sorts
    // numerically. State resets when the module (hence this view) changes.
    val headers = Seq("Instance", "Type", "Port", "Dir", "Bundle", "Width", "Notes")
    val sortVar = Var((0, true)) // (column, ascending)
    val qVar    = Var("")
    def colText(r: PortRow, c: Int): String = c match {
      case 0 => r.inst; case 1 => r.tpe; case 2 => r.port; case 3 => r.dir; case 4 => r.bundle; case _ => r.doc
    }
    def rowText(r: PortRow): String = Seq(r.inst, r.tpe, r.port, r.dir, r.bundle, r.doc).mkString(" ").toLowerCase
    val viewSig: Signal[Seq[PortRow]] = Signal.combine(sortVar.signal, qVar.signal).map { case ((col, asc), q) =>
      val nq = q.trim.toLowerCase
      val filtered = if (nq.isEmpty) all else all.filter(r => rowText(r).contains(nq))
      val sorted = if (col == 5) filtered.sortBy(r => Inspector.totalWidth(r.bundle, d))
                   else filtered.sortBy(r => colText(r, col).toLowerCase)
      if (asc) sorted else sorted.reverse
    }
    def sortBy(c: Int): Unit = sortVar.update { case (cur, asc) => if (cur == c) (c, !asc) else (c, true) }
    val thStyle = s"text-align:left;padding:7px 12px;font-size:10px;color:${Tokens.ink3};font-weight:600;text-transform:uppercase;letter-spacing:.6px;border-bottom:1px solid ${Tokens.borderSoft};position:sticky;top:0;background:${Tokens.panelAlt};cursor:pointer;user-select:none"
    def portRowEl(r: PortRow): Element = {
      val dirBg  = if (r.dir == PortDir.Master) Tokens.dirMasterBg else Tokens.dirSlaveBg
      val dirCol = if (r.dir == PortDir.Master) Tokens.dirMasterFg else Tokens.dirSlaveFg
      tr(cls := "scl-ports-row", styleAttr := s"cursor:pointer;border-bottom:1px solid ${Tokens.borderSoft}",
        dataAttr("port-row") := s"${r.inst}.${r.port}",
        onClick --> { _ => setSel(s"${r.inst}.${r.port}") },
        td(styleAttr := s"padding:6px 12px;font-weight:600;color:${Tokens.ink}", r.inst),
        td(styleAttr := s"padding:6px 12px;color:${Tokens.ink3}", r.tpe),
        td(styleAttr := s"padding:6px 12px;color:${Tokens.ink2}", r.port),
        td(styleAttr := "padding:6px 12px", span(styleAttr := s"padding:1px 6px;border-radius:2px;background:$dirBg;color:$dirCol;font-size:10px", r.dir)),
        td(styleAttr := s"padding:6px 12px;color:${Tokens.ink2}", r.bundle),
        td(styleAttr := s"padding:6px 12px;color:${Tokens.ink3}", s"${Inspector.totalWidth(r.bundle, d)}b"),
        td(styleAttr := s"padding:6px 12px;color:${Tokens.ink4};font-size:11px",
          if (!r.connected) span(title := "This port is not referenced anywhere in the source (genuinely unconnected).",
            styleAttr := s"padding:1px 6px;border-radius:2px;background:${Tokens.badgeUnconnBg};color:${Tokens.badgeUnconnFg};font-family:${Tokens.mono};font-size:9.5px;margin-right:6px", "unconnected") else emptyNode,
          r.doc),
      )
    }
    div(
      div(styleAttr := s"display:flex;align-items:center;gap:6px;padding:6px 12px;border-bottom:1px solid ${Tokens.borderSoft}",
        input(dataAttr("ports-filter") := "1", placeholder := "filter ports…",
          styleAttr := s"border:1px solid ${Tokens.border};outline:none;background:${Tokens.panel};border-radius:5px;padding:3px 8px;width:160px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink}",
          controlled(value <-- qVar.signal, onInput.mapToValue --> qVar.writer)),
        span(styleAttr := s"font-family:${Tokens.mono};font-size:10.5px;color:${Tokens.ink4};font-variant-numeric:tabular-nums",
          child.text <-- viewSig.map(rs => s"${rs.length} / ${all.length}"))),
      table(styleAttr := s"width:100%;border-collapse:collapse;font-family:${Tokens.mono};font-size:11px",
        thead(tr(
          headers.zipWithIndex.map { case (h, i) =>
            th(styleAttr := thStyle, dataAttr("ports-sort") := i.toString, onClick --> { _ => sortBy(i) },
              h, span(styleAttr := s"color:${Tokens.accent}",
                child.text <-- sortVar.signal.map { case (c, asc) => if (c == i) (if (asc) " ▲" else " ▼") else "" }))
          })),
        tbody(children <-- viewSig.map(_.map(portRowEl)))))
  }
}
