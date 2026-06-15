package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socless.{Design, ModuleDef, InstanceDef, WireDef, LogicNodeDef, BundleTree}

/** The inspector (right) pane (epic #172 Phase 3): details for whatever is
  * selected (or hovered) on the schematic. Behavioral port of the JS
  * `BenchInspector` family (ui/SoCLess.html:4023+):
  *
  *   - nothing selected -> module metadata (type, counts, metrics)
  *   - an instance       -> clock/reset domains, same-domain peers, parameters
  *   - a wire            -> the bundle field tree (via the shared [[BundleTree]])
  *
  * Reads the `selectedSig` / `hoveredSig` state already owned by [[App]]. The
  * bottom Source / Waveform panel is deferred to a follow-up. */
object Inspector {

  // AXI-ish channel accent colors (ports the JS CHANNEL_COLOR map).
  private val chanColor: Map[String, String] = Map(
    "ar" -> "oklch(0.60 0.16 250)", "aw" -> "oklch(0.55 0.17 290)",
    "w"  -> "oklch(0.62 0.17 170)", "r"  -> "oklch(0.65 0.16 50)",
    "b"  -> "oklch(0.58 0.18 25)",
  )

  private val VecRe    = """^Vec\[(.+),\s*(\d+)\]$""".r
  private val GroundRe = """^(?:UInt|SInt)<(\d+)>$""".r

  /** Total bit width of a bundle/Vec/ground key (ports the JS totalWidth).
    * Shared with the Ports table in [[BottomPanel]]. */
  def totalWidth(key: String, d: Design): Int =
    d.bundles.get(key) match {
      case Some(b) if b.kind == "Bundle"    => b.channels.map(ch => totalWidth(ch.`type`, d)).sum
      case Some(b) if b.kind == "Decoupled" => b.fields.map(_.width).sum + 2 // + valid/ready
      case Some(_)                          => 0
      case None => key match {
        case VecRe(inner, n) => totalWidth(inner.trim, d) * n.toInt
        case GroundRe(w)     => w.toInt
        case _               => 0
      }
    }

  def render(
    designSig: Signal[Option[Design]],
    moduleSig: Signal[Option[ModuleDef]],
    selectedVar: Var[Option[String]],
    hoveredVar: Var[Option[String]],
    expandedBundlesVar: Var[Set[String]],
  ): Element = {

    def setSel(p: String): Unit  = selectedVar.set(Some(p))
    def toggleBundle(k: String): Unit = expandedBundlesVar.update(s => if (s(k)) s - k else s + k)

    val sigSig: Signal[Option[String]] =
      Signal.combine(selectedVar.signal, hoveredVar.signal).map { case (s, h) => s.orElse(h) }

    val bodySig: Signal[com.raquo.laminar.nodes.ChildNode.Base] =
      Signal.combine(moduleSig, sigSig, expandedBundlesVar.signal, designSig).map {
        case (Some(mod), sig, expanded, Some(d)) =>
          val wire = sig.flatMap(s => mod.wires.find(w =>
            w.from == s || w.to == s || s.startsWith(w.from + ".") || s.startsWith(w.to + ".")))
          // A selected logic node (register / constant, issue #188 B1) is keyed by
          // its exact id; resolve it before the wire match so its dedicated panel
          // wins over the incidental net the node terminates.
          val logicNode = sig.flatMap(s => mod.logic.find(_.id == s))
          (sig, logicNode, wire) match {
            case (None, _, _)              => metaView(mod)
            case (Some(_), Some(ln), _)    => logicView(ln, d)
            case (Some(_), None, Some(w))  => bundleView(w, sig.get, expanded, d, setSel, toggleBundle)
            case (Some(s), None, None)     =>
              mod.children.find(_.name == s) match {
                case Some(inst) => instanceView(inst, mod.children, d, setSel)
                case None       => emptyNode
              }
          }
        case _ => emptyNode
      }

    div(
      cls := "scl-pane scl-pane-inspector", dataAttr("pane") := "inspector",
      div(cls := "scl-pane-title",
        span("INSPECTOR"),
        child.maybe <-- sigSig.map {
          case Some(_) => Some(button(cls := "scl-insp-clear", "clear",
            onClick --> { _ => selectedVar.set(None) }))
          case None => None
        }),
      child <-- bodySig,
    )
  }

  // ── Module metadata (nothing selected) ──────────────────────────────────────
  private def metaView(mod: ModuleDef): Element = {
    val m = mod.metrics
    val items: Seq[(String, String)] =
      Seq("Type" -> mod.`type`, "Instances" -> mod.children.length.toString, "Connects" -> mod.wires.length.toString) ++
        m.gates.map(g => Seq("Gates" -> g.toString)).getOrElse(Nil) ++
        Seq("Registers" -> m.registers.toString, "Memories" -> m.memories.toString, "LOC" -> m.lines.toString)
    div(styleAttr := s"padding:24px 16px;color:${Tokens.ink3};font-size:12px;line-height:1.55",
      div(styleAttr := s"margin-bottom:12px;font-weight:500;color:${Tokens.ink2}", mod.`type`),
      if (mod.doc.nonEmpty) div(styleAttr := "margin-bottom:16px;font-size:11.5px", mod.doc) else emptyNode,
      div(styleAttr := s"display:grid;grid-template-columns:90px 1fr;row-gap:5px;column-gap:8px;font-family:${Tokens.mono};font-size:11px",
        items.flatMap { case (k, v) => Seq(
          span(styleAttr := s"color:${Tokens.ink4}", k),
          span(styleAttr := s"color:${Tokens.ink2}", v),
        ) }),
      div(styleAttr := s"margin-top:14px;color:${Tokens.ink4};font-size:11px", "Click a wire or module to inspect."),
    )
  }

  // ── Instance detail (clock/reset domains + peers + params) ──────────────────
  private def instanceView(inst: InstanceDef, siblings: Seq[InstanceDef], d: Design, setSel: String => Unit): Element = {
    val params     = d.modules.find(_.name == inst.`type`).map(_.parameters).getOrElse(Map.empty)
    val clockPeers = if (inst.clock.nonEmpty) siblings.filter(c => c.name != inst.name && c.clock == inst.clock) else Nil
    def labelRow(k: String, v: Element): Seq[Element] =
      Seq(span(styleAttr := s"color:${Tokens.ink4}", k), v)
    div(dataAttr("inst-inspect") := inst.name, styleAttr := "padding:12px 14px;font-size:12px;overflow:auto",
      div(styleAttr := s"font-family:${Tokens.mono};font-size:13px;font-weight:600;color:${Tokens.ink};margin-bottom:2px", inst.name),
      div(styleAttr := s"font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink3};margin-bottom:12px", inst.`type`),
      sectionTitle("Domains"),
      div(styleAttr := s"display:grid;grid-template-columns:64px 1fr;row-gap:5px;column-gap:8px;font-size:11px;margin-bottom:12px;font-family:${Tokens.mono}",
        labelRow("clock", span(dataAttr("inst-clock") := inst.clock, if (inst.clock.nonEmpty) inst.clock else "—")) ++
          labelRow("reset", span(if (inst.reset.nonEmpty) inst.reset else "—"))),
      if (inst.clock.nonEmpty && clockPeers.nonEmpty)
        div(dataAttr("clock-domain") := inst.clock, styleAttr := "margin-bottom:12px",
          sectionTitle(s"Same clock domain (${clockPeers.length})"),
          div(styleAttr := "display:flex;flex-wrap:wrap;gap:4px",
            clockPeers.map(c => button(dataAttr("clock-peer") := c.name,
              title := s"Select ${c.name} (clock: ${c.clock})",
              styleAttr := s"font-family:${Tokens.mono};font-size:10.5px;color:${Tokens.ink2};background:${Tokens.panelAlt};border:1px solid ${Tokens.borderSoft};border-radius:4px;padding:2px 6px;cursor:pointer",
              onClick --> { _ => setSel(c.name) }, c.name))))
      else emptyNode,
      if (params.nonEmpty)
        div(sectionTitle("Parameters"),
          div(styleAttr := s"display:grid;grid-template-columns:auto 1fr;row-gap:5px;column-gap:8px;font-size:11px;font-family:${Tokens.mono}",
            params.toSeq.flatMap { case (k, v) => Seq(
              span(styleAttr := s"color:${Tokens.ink4}", k),
              span(styleAttr := s"color:${Tokens.ink2};word-break:break-all", v),
            ) }))
      else emptyNode,
    )
  }

  // ── Logic node detail: register / constant (issue #188 B1) ──────────────────
  private def logicView(ln: LogicNodeDef, d: Design): Element = {
    val isReg = ln.kind == "reg"
    def sourceRow: Element =
      if (ln.source.file.nonEmpty)
        div(styleAttr := s"background:${Tokens.panelAlt};padding:6px 10px;border-radius:5px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink2}",
          span(title := ln.source.file, s"${ln.source.file.split('/').lastOption.getOrElse(ln.source.file)}:${ln.source.line}"))
      else div(styleAttr := s"font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink4}", "—")
    div(dataAttr("logic-inspect") := ln.id, styleAttr := "padding:12px 14px;font-size:12px;overflow:auto",
      div(styleAttr := s"font-family:${Tokens.mono};font-size:13px;font-weight:600;color:${Tokens.ink};margin-bottom:2px", ln.label),
      div(styleAttr := s"font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink3};margin-bottom:12px",
        if (isReg) "register" else "constant"),
      if (isReg) {
        div(sectionTitle("Registered type"),
          div(styleAttr := s"background:${Tokens.panelAlt};padding:8px 10px;border-radius:5px;margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;font-family:${Tokens.mono};font-size:11px",
            span(styleAttr := s"color:${Tokens.ink3}", if (ln.bundle.nonEmpty) ln.bundle else "—"),
            if (ln.bundle.nonEmpty) span(styleAttr := s"color:${Tokens.ink2}", s"${totalWidth(ln.bundle, d)} bits") else emptyNode))
      } else {
        div(sectionTitle("Value"),
          div(styleAttr := s"background:${Tokens.panelAlt};padding:8px 10px;border-radius:5px;margin-bottom:12px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink2};word-break:break-all", ln.label))
      },
      sectionTitle("Source"),
      sourceRow,
    )
  }

  // ── Wire bundle tree ─────────────────────────────────────────────────────────
  private def bundleView(wire: WireDef, sig: String, expanded: Set[String], d: Design,
                         setSel: String => Unit, toggleBundle: String => Unit): Element = {
    val tree = BundleTree.portTree(wire.bundle, None, wire.from, d)
    div(styleAttr := "padding:12px 14px;font-size:12px;overflow:auto",
      div(styleAttr := "margin-bottom:10px",
        div(styleAttr := s"font-family:${Tokens.mono};font-size:12px;font-weight:600;color:${Tokens.ink};margin-bottom:2px;display:flex;align-items:center;gap:4px",
          span(title := wire.from, styleAttr := "flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap", wire.from),
          copyBtn(wire.from)),
        div(styleAttr := s"font-family:${Tokens.mono};font-size:10.5px;color:${Tokens.ink3};display:flex;align-items:center;gap:6px",
          span("↓"), span(title := wire.to, styleAttr := "flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap", wire.to),
          copyBtn(wire.to))),
      div(styleAttr := s"background:${Tokens.panelAlt};padding:8px 10px;border-radius:5px;margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;font-family:${Tokens.mono};font-size:11px",
        span(styleAttr := s"color:${Tokens.ink3}", wire.bundle),
        span(styleAttr := s"color:${Tokens.ink2}", s"${totalWidth(wire.bundle, d)} bits")),
      sectionTitle("Fields"),
      div(tree.children.map(c => treeNode(c, 0, sig, expanded, setSel, toggleBundle))),
      div(styleAttr := "margin-top:14px", sectionTitle("Source")),
      div(styleAttr := s"background:${Tokens.panelAlt};padding:6px 10px;border-radius:5px;font-family:${Tokens.mono};font-size:11px;color:${Tokens.ink2}",
        span(if (wire.label.nonEmpty) wire.label else wire.bundle),
        if (wire.source.file.nonEmpty)
          span(title := wire.source.file, styleAttr := s"color:${Tokens.ink4}",
            s" · ${wire.source.file.split('/').lastOption.getOrElse(wire.source.file)}:${wire.source.line}")
        else emptyNode),
    )
  }

  private def treeNode(node: BundleTree.PortNode, depth: Int, sig: String, expanded: Set[String],
                       setSel: String => Unit, toggleBundle: String => Unit): Element = {
    val isOpen = expanded.contains(node.path)
    val isSel  = sig == node.path
    val indent = depth * 14
    val color  = chanColor.get(node.name)
    if (node.isExpandable) {
      div(styleAttr := s"margin-bottom:3px;border:1px solid ${Tokens.borderSoft};border-radius:5px;overflow:hidden",
        div(dataAttr("inspector-toggle") := node.path,
          styleAttr := s"display:flex;align-items:center;gap:8px;padding:4px 8px 4px ${8 + indent}px;background:${Tokens.panelAlt};cursor:pointer;font-family:${Tokens.mono};font-size:11px",
          onClick --> { _ => toggleBundle(node.path) },
          color.map(c => span(styleAttr := s"width:7px;height:7px;border-radius:4px;background:$c;flex-shrink:0")).getOrElse(emptyNode),
          span(styleAttr := s"font-weight:600;color:${if (isSel) Tokens.accent else Tokens.ink2}", node.name),
          span(styleAttr := s"color:${Tokens.ink4};font-size:9.5px", node.bundleKey),
          node.dir.map(dd => span(styleAttr := s"color:${Tokens.ink4};font-size:9px", if (dd == "out") "→" else "←")).getOrElse(emptyNode),
          span(styleAttr := s"margin-left:auto;color:${Tokens.ink4};font-size:10px", s"${node.children.length} fields"),
          span(styleAttr := s"color:${Tokens.ink4};font-size:9px;display:inline-block;transition:transform .12s;transform:${if (isOpen) "rotate(90deg)" else "none"}", "▶")),
        if (isOpen) div(node.children.map(c => treeNode(c, depth + 1, sig, expanded, setSel, toggleBundle))) else emptyNode,
      )
    } else {
      val bg = if (isSel) s"background:${Tokens.accentBg};" else ""
      div(cls := "scl-insp-leaf",
        styleAttr := s"display:flex;align-items:center;gap:8px;padding:3px 8px 3px ${8 + 14 + indent}px;font-family:${Tokens.mono};font-size:10.5px;cursor:pointer;position:relative;${bg}color:${if (isSel) Tokens.accent else Tokens.ink2}",
        onClick --> { _ => setSel(node.path) },
        span(styleAttr := s"width:12px;color:${Tokens.ink4};font-size:9px;flex-shrink:0", node.dir match { case Some("out") => "→"; case Some(_) => "←"; case None => "" }),
        span(node.name),
        span(styleAttr := s"margin-left:auto;color:${Tokens.ink4}", s"${node.width}b"),
        span(cls := "copy-btn", copyBtn(node.path)),
      )
    }
  }

  // ── Copy-path button ─────────────────────────────────────────────────────────
  private def copyBtn(path: String): Element = {
    val copied = Var(false)
    button(
      title := s"Copy path: $path",
      styleAttr <-- copied.signal.map(c =>
        s"border:none;background:transparent;cursor:pointer;padding:1px 3px;border-radius:3px;line-height:1;font-size:10px;flex-shrink:0;color:${if (c) Tokens.ok else Tokens.ink4}"),
      onClick --> { (e: dom.MouseEvent) =>
        e.stopPropagation()
        val clip = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
        if (!js.isUndefined(clip) && clip != null) {
          clip.writeText(path)
          copied.set(true)
          dom.window.setTimeout(() => copied.set(false), 1400)
        }
      },
      child.text <-- copied.signal.map(c => if (c) "✓" else "📋"),
    )
  }

  private def sectionTitle(text: String): Element =
    div(styleAttr := s"font-size:10.5px;font-weight:600;color:${Tokens.ink3};letter-spacing:.6px;text-transform:uppercase;margin-bottom:6px", text)
}
