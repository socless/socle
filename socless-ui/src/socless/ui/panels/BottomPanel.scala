package socless.ui.panels

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socless.schema.{Design, ModuleDef, PortDir}
import socless.ui.theme._

/** The bottom panel under the schematic (epic #172 Phase 3 follow-up): a tabbed
  * Source / Waveform / Ports view. Behavioral port of the JS `BenchBottomPanel`
  * family (ui/SoCLess.html:4351+).
  *
  *   - Source   : the module's `.scala` source (from `Design.sources`, keyed by
  *                `ModuleDef.source.file`) with line numbers, target-line +
  *                selected-signal highlighting, auto-scroll, and a lightweight
  *                Scala tokenizer whose instance names drill in. Falls back to a
  *                locator notice when no source was embedded.
  *   - Waveform : a no-data placeholder. The typed [[Design]] carries no wave
  *                traces, so there is nothing to draw (the JS demo-only path).
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
  ): Element = {
    val tabVar = Var(if (embedded) "ports" else "source")
    def setSel(p: String): Unit = selectedVar.set(Some(p))

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
        styleAttr <-- tabVar.signal.map(t =>
          s"border:none;background:transparent;cursor:pointer;padding:0 14px;font-size:11.5px;font-family:inherit;" +
            s"color:${if (t == id) Tokens.ink else Tokens.ink3};font-weight:${if (t == id) 600 else 500};" +
            s"border-bottom:2px solid ${if (t == id) Tokens.accent else "transparent"};display:flex;align-items:center;gap:6px"),
        onClick --> { _ => tabVar.set(id) },
        span(styleAttr := s"font-family:${Tokens.mono};color:${Tokens.ink4}", icon), label)

    val infoSig: Signal[com.raquo.laminar.nodes.ChildNode.Base] =
      Signal.combine(tabVar.signal, moduleSig, designSig).map {
        case ("source", Some(mod), Some(d)) => sourcePathChip(mod.source.file, mod.source.line, d)
        case ("wave", _, _)                 => span("no waveform data")
        case ("ports", Some(mod), _)        => span(s"${mod.children.length} instances")
        case _                              => emptyNode
      }

    val bodySig: Signal[com.raquo.laminar.nodes.ChildNode.Base] =
      Signal.combine(tabVar.signal, moduleSig, selectedVar.signal, designSig).map {
        case ("source", Some(mod), sel, Some(d)) => sourceView(mod, d, sel, drillInto)
        case ("ports", Some(mod), _, Some(d))    => portsView(mod, d, setSel)
        case ("wave", _, _, _)                   => waveformPlaceholder
        case _                                   => emptyNode
      }

    div(
      cls := "scl-bottom", dataAttr("bottom-panel") := "1",
      styleAttr <-- heightVar.signal.map(h => s"height:${h}px"),
      div(cls := "scl-bottom-resize", dataAttr("bottom-resize") := "1",
        onMouseDown --> { (e: dom.MouseEvent) => onResizeDown(e) }),
      div(cls := "scl-bottom-tabs",
        Option.when(!embedded)(tabButton("source", "⌥", "Source")),
        Option.when(!embedded)(tabButton("wave", "∿", "Waveform")),
        tabButton("ports", "⊕", "Ports"),
        div(styleAttr := "flex:1"),
        div(styleAttr := s"display:flex;align-items:center;gap:6px;padding:0 12px;font-size:11px;font-family:${Tokens.mono};color:${Tokens.ink4};overflow:hidden;white-space:nowrap;max-width:50%",
          child <-- infoSig),
      ),
      div(cls := "scl-bottom-body", child <-- bodySig),
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
  // Scroll the target line into view only when the module/file changes (not on
  // every selection-driven rebuild).
  private var scrolledFor: String = null

  private def sourceView(mod: ModuleDef, d: Design, selected: Option[String], drillInto: String => Unit): Element = {
    val file       = mod.source.file
    // Jump to the SELECTED component's source line when it lives in this file
    // (#228): look the selection up among instances / logic nodes / wires. Fall
    // back to the module declaration line.
    val selLine: Option[Int] = selected.flatMap { s =>
      val head = s.split('.').head
      mod.children.find(_.name == head).map(_.source)
        .orElse(mod.logic.find(_.id == head).map(_.source))
        .orElse(mod.wires.find(w => w.from == s || w.to == s).map(_.source))
        .filter(r => r.line > 0 && r.file == file).map(_.line)
    }
    val targetLine = selLine.getOrElse(mod.source.line)
    val fileText   = if (file.nonEmpty) d.sources.get(file) else None

    fileText match {
      case None =>
        div(styleAttr := s"padding:20px 22px;font-size:12px;color:${Tokens.ink2};line-height:1.55;max-width:520px",
          div(styleAttr := s"font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink};margin-bottom:6px",
            s"${if (file.nonEmpty) file else "(no source recorded)"}${if (targetLine > 0) s":$targetLine" else ""}"),
          div(styleAttr := s"color:${Tokens.ink3}",
            "Source text was not embedded in design.json. Re-run elaboration with --source-root (or use the Mill plugin) so the exporter includes the files."))
      case Some(text) =>
        val selInst = selected.map(_.split('.').head)
        val selPort = selected.map(_.split('.').toSeq.take(2).mkString("."))
        val childNames = mod.children.map(_.name).toSet
        val lines = text.split("\n", -1).toSeq
        val key = s"$file:$targetLine"
        div(styleAttr := "height:100%;overflow:auto",
          onMountCallback { ctx =>
            val parent = ctx.thisNode.ref
            // Defer one frame: onMount fires before the grid is laid out, and a
            // rebuild (e.g. a selection change) may replace this node before then.
            // Gate inside the frame on the LIVE, connected node so only it scrolls,
            // and only once per file:line (not on every selection-driven rebuild).
            // Scroll on navigating to a new module (its decl line) or selecting a
            // component; not on merely deselecting, which would yank back up.
            val fileChanged  = scrolledFor == null || !scrolledFor.startsWith(s"$file:")
            val shouldScroll = selLine.isDefined || fileChanged
            if (targetLine > 0 && shouldScroll) dom.window.requestAnimationFrame { _ =>
              if (dom.document.contains(parent) && key != scrolledFor) {
                scrolledFor = key
                val el = parent.querySelector(s"""[data-line="$targetLine"]""").asInstanceOf[dom.html.Element]
                if (el != null) {
                  val delta = el.getBoundingClientRect().top - parent.getBoundingClientRect().top
                  parent.scrollTop = math.max(0.0, parent.scrollTop + delta - parent.clientHeight / 4.0)
                }
              }
            }
          },
          div(styleAttr := s"font-family:${Tokens.mono};font-size:11.5px;line-height:1.6;padding:8px 0;display:grid;grid-template-columns:auto 1fr;column-gap:12px",
            lines.zipWithIndex.flatMap { case (line, i) =>
              val lineNo = i + 1
              val matchInst = selInst.exists(s => s.nonEmpty && line.contains(s))
              val matchPort = selPort.exists(s => s.nonEmpty && line.contains(s))
              val isTarget  = lineNo == targetLine
              val hl = matchInst || matchPort || isTarget
              val bg = if (hl) Tokens.accentBg else "transparent"
              Seq[Element](
                div(dataAttr("line") := lineNo.toString,
                  styleAttr := s"text-align:right;padding:0 4px;user-select:none;color:${if (hl) Tokens.accent else Tokens.ink4};background:$bg;font-variant-numeric:tabular-nums;font-weight:${if (isTarget) 600 else 400}", lineNo.toString),
                div(styleAttr := s"padding:0 14px 0 6px;background:$bg;border-left:2px solid ${if (hl) Tokens.accent else "transparent"};white-space:pre;color:${Tokens.ink2}",
                  tokenize(line, selInst, childNames, setSel = (p) => (), drillInto)),
              )
            }))
    }
  }

  private def tokenize(line: String, selInst: Option[String], childNames: Set[String],
                       setSel: String => Unit, drillInto: String => Unit): Seq[Element] =
    TokRe.findAllIn(line).toSeq.map { p =>
      if (p.trim.isEmpty || p.length == 1 && "(){}.,;".contains(p)) span(p)
      else if (Keywords.contains(p)) span(styleAttr := s"color:$KwCol;font-weight:500", p)
      else if (Types.contains(p)) span(styleAttr := s"color:$TyCol", p)
      else if (p == "true" || p == "false" || p.forall(_.isDigit)) span(styleAttr := s"color:$LitCol", p)
      else if (p.startsWith("//")) span(styleAttr := s"color:${Tokens.ink4};font-style:italic", p)
      else if (childNames.contains(p))
        span(title := s"Open $p",
          styleAttr := s"background:${Tokens.linkChildBg};color:${Tokens.linkChildFg};border-radius:2px;padding:0 2px;cursor:pointer;text-decoration:underline dotted;text-underline-offset:2px",
          onClick --> { _ => drillInto(p) }, p)
      else if (selInst.contains(p))
        span(styleAttr := s"background:${Tokens.linkSelBg};border-radius:2px;padding:0 1px;cursor:pointer;color:${Tokens.ink}",
          onClick --> { _ => setSel(p) }, p)
      else span(p)
    }

  // ── Waveform (no traces in the schema) ──────────────────────────────────────
  private def waveformPlaceholder: Element =
    div(styleAttr := s"display:flex;align-items:center;justify-content:center;height:100%;font-size:12px;color:${Tokens.ink4};padding:24px;text-align:center",
      "No waveform data - run a simulation to populate this view.")

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
