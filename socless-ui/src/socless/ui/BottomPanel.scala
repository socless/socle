package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import socless.{Design, ModuleDef}

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

  // Scala token colors (port the JS tokenizeScala palette).
  private val KwCol   = "oklch(0.5 0.15 290)"
  private val TyCol   = "oklch(0.5 0.13 200)"
  private val LitCol  = "oklch(0.55 0.15 30)"
  private val Keywords = Set("class","extends","val","def","new","for","until","object","trait","case","match","if","else","when","otherwise")
  private val Types    = Set("Module","IO","Bundle","Vec","Flipped","Decoupled","Queue","RRArbiter","UInt","SInt","Bool","Clock","Reset","Reg","RegInit","Mem","DontCare")
  private val TokRe    = """[^\s(){}.,;]+|\s+|[(){}.,;]""".r

  def render(
    designSig: Signal[Option[Design]],
    moduleSig: Signal[Option[ModuleDef]],
    selectedVar: Var[Option[String]],
    hoveredVar: Var[Option[String]],
    drillInto: String => Unit,
  ): Element = {
    val tabVar = Var("source")
    def setSel(p: String): Unit = selectedVar.set(Some(p))

    def tabButton(id: String, icon: String, label: String): Element =
      button(
        dataAttr("bottom-tab") := id,
        styleAttr <-- tabVar.signal.map(t =>
          s"border:none;background:transparent;cursor:pointer;padding:0 14px;font-size:11.5px;font-family:inherit;" +
            s"color:${if (t == id) Tokens.ink else Tokens.ink3};font-weight:${if (t == id) 600 else 500};" +
            s"border-bottom:2px solid ${if (t == id) Tokens.accent else "transparent"};display:flex;align-items:center;gap:6px"),
        onClick --> { _ => tabVar.set(id) },
        span(styleAttr := s"font-family:${Tokens.mono};color:${Tokens.ink4}", icon), label)

    val infoSig = Signal.combine(tabVar.signal, moduleSig).map {
      case ("source", Some(mod)) => mod.source.file
      case ("wave", _)           => "no waveform data"
      case ("ports", Some(mod))  => s"${mod.children.length} instances"
      case _                     => ""
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
      div(cls := "scl-bottom-tabs",
        tabButton("source", "⌥", "Source"),
        tabButton("wave", "∿", "Waveform"),
        tabButton("ports", "⊕", "Ports"),
        div(styleAttr := "flex:1"),
        div(styleAttr := s"display:flex;align-items:center;padding:0 12px;font-size:11px;font-family:${Tokens.mono};color:${Tokens.ink4};overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:50%",
          child.text <-- infoSig),
      ),
      div(cls := "scl-bottom-body", child <-- bodySig),
    )
  }

  // ── Source ──────────────────────────────────────────────────────────────────
  // Scroll the target line into view only when the module/file changes (not on
  // every selection-driven rebuild).
  private var scrolledFor: String = null

  private def sourceView(mod: ModuleDef, d: Design, selected: Option[String], drillInto: String => Unit): Element = {
    val file       = mod.source.file
    val targetLine = mod.source.line
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
            if (targetLine > 0) dom.window.requestAnimationFrame { _ =>
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
          styleAttr := "background:oklch(0.95 0.08 200);color:oklch(0.4 0.15 250);border-radius:2px;padding:0 2px;cursor:pointer;text-decoration:underline dotted;text-underline-offset:2px",
          onClick --> { _ => drillInto(p) }, p)
      else if (selInst.contains(p))
        span(styleAttr := s"background:oklch(0.95 0.1 90);border-radius:2px;padding:0 1px;cursor:pointer;color:${Tokens.ink}",
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
    val thStyle = s"text-align:left;padding:7px 12px;font-size:10px;color:${Tokens.ink3};font-weight:600;text-transform:uppercase;letter-spacing:.6px;border-bottom:1px solid ${Tokens.borderSoft}"
    table(styleAttr := s"width:100%;border-collapse:collapse;font-family:${Tokens.mono};font-size:11px",
      thead(tr(styleAttr := s"background:${Tokens.panelAlt}",
        Seq("Instance","Type","Port","Dir","Bundle","Width","Notes").map(h => th(styleAttr := thStyle, h)))),
      tbody(rows.toSeq.map { r =>
        val dirBg  = if (r.dir == "master") "oklch(0.96 0.04 250)" else "oklch(0.96 0.04 50)"
        val dirCol = if (r.dir == "master") "oklch(0.4 0.15 250)" else "oklch(0.4 0.15 50)"
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
              styleAttr := s"padding:1px 6px;border-radius:2px;background:oklch(0.95 0.06 30);color:oklch(0.45 0.16 30);font-family:${Tokens.mono};font-size:9.5px;margin-right:6px", "unconnected") else emptyNode,
            r.doc),
        )
      }))
  }
}
