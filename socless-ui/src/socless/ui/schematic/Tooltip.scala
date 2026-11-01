package socless.ui.schematic

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socless.schema.{NodeKind, PinSide, PortDir, ModuleKind}
import socless.ui.theme.Tokens
import socless.ui.model.ScalaClassLabel
import socless.ui.model.LogicLabels.{OpInRe, stripQ, inRole}
import socless.ui.panels.Inspector
import SchematicGeom.{Geom, opGlyph}

/** The tooltip card of the schematic, and the hovered block's outline.
  *
  * One delegated set of listeners on the viewport (no listener per element):
  *  - `pointerover` outlines the box under the pointer ([[Highlight.enterBox]]);
  *  - `pointermove` re-arms a [[Delay]] timer; when it fires, the element last
  *    under the pointer is classified ([[targetOf]]) and the card shows what it
  *    is and what the mouse does there ([[content]]);
  *  - leaving the viewport, a press (a drag starts with one), the wheel, a pan
  *    or zoom, a navigation and a rebuild hide it; it never arms while a pan
  *    holds the hover.
  *
  * The card is built once, outside the content layer, with
  * `pointer-events:none` (a card under the pointer would steal its events):
  * showing or hiding it changes nothing in the schematic. */
final class Tooltip(hl: Highlight) {
  import Tooltip._

  private val nameEl    = span(dataAttr("tip-name") := "", styleAttr := s"font-family:${Tokens.mono};font-weight:600;color:${Tokens.ink};word-break:break-all")
  private val whatEl    = span(dataAttr("tip-type") := "", styleAttr := s"margin-left:6px;color:${Tokens.ink3};word-break:break-all")
  private val detailEl  = div(dataAttr("tip-detail") := "", styleAttr := s"margin-top:4px;color:${Tokens.ink2};white-space:pre-line;font-family:${Tokens.mono};font-size:11px;word-break:break-all")
  private val actionsEl = div(dataAttr("tip-actions") := "", styleAttr := s"margin-top:5px;color:${Tokens.accent}")

  /** The card, a child of the schematic pane (positioned against it). */
  val el: Div = div(
    dataAttr("tooltip") := "1",
    styleAttr := s"position:absolute;display:none;pointer-events:none;z-index:7;max-width:${MaxW}px;box-sizing:border-box;" +
      s"background:${Tokens.panel};border:1px solid ${Tokens.border};border-radius:7px;padding:7px 10px;" +
      "box-shadow:0 4px 14px rgba(0,0,0,.16);font-family:var(--sans);font-size:12px;line-height:1.4",
    div(nameEl, whatEl), detailEl, actionsEl,
  )

  private var timer = 0          // the pending show (setTimeout handle), 0 = none
  private var shown: Option[Target] = None
  private var last: dom.Element = null
  private var lastX = 0.0
  private var lastY = 0.0

  private def cancelTimer(): Unit = if (timer != 0) { dom.window.clearTimeout(timer); timer = 0 }

  /** Hide the card and drop a pending show. */
  def hide(): Unit = {
    cancelTimer()
    if (shown.isDefined) { shown = None; el.ref.style.display = "none" }
  }

  private def arm(): Unit = {
    cancelTimer()
    timer = dom.window.setTimeout(() => { timer = 0; fire() }, Delay.toDouble)
  }

  private def fire(): Unit =
    if (last != null && last.isConnected && !hl.isHoverSuspended) {
      val g = hl.geom
      for (t <- targetOf(last) if g != null; c <- content(g, t)) { shown = Some(t); show(c) }
    }

  private def show(c: Content): Unit = {
    nameEl.ref.textContent = c.name
    whatEl.ref.textContent = c.what
    detailEl.ref.textContent = c.detail.mkString("\n")
    detailEl.ref.style.display = if (c.detail.isEmpty) "none" else "block"
    actionsEl.ref.textContent = c.actions
    el.ref.setAttribute("data-tip-kind", c.kind)
    // Beside the pointer, flipped to its other side near the pane's right or
    // bottom edge; one rect read per show.
    val pane = el.ref.parentElement.getBoundingClientRect()
    val x = lastX - pane.left; val y = lastY - pane.top
    val s = el.ref.style
    if (x + 16 + MaxW > pane.width) { s.left = ""; s.right = s"${math.max(4.0, pane.width - x + 12)}px" }
    else { s.right = ""; s.left = s"${x + 16}px" }
    if (y > pane.height * 0.6) { s.top = ""; s.bottom = s"${pane.height - y + 12}px" }
    else { s.bottom = ""; s.top = s"${y + 18}px" }
    s.display = "block"
  }

  private def elementOf(e: dom.Event): dom.Element = e.target match {
    case el: dom.Element => el
    case _               => null
  }

  // The box outline follows the box under the pointer (any element inside it).
  private val onOver: js.Function1[dom.PointerEvent, Unit] = (e) => {
    val b = boxOf(elementOf(e))
    if (b == null) hl.leaveBox() else hl.enterBox(b)
  }

  private val onMove: js.Function1[dom.PointerEvent, Unit] = (e) =>
    if (e.buttons != 0 || hl.isHoverSuspended) hide()
    else {
      last = elementOf(e); lastX = e.clientX; lastY = e.clientY
      shown match {
        case None                                => arm()
        case Some(t) if !targetOf(last).contains(t) => hide(); arm()
        case _                                   => () // still on what the card shows
      }
    }

  private val onLeave: js.Function1[dom.PointerEvent, Unit] = (_) => { hl.leaveBox(); hide(); last = null }
  private val onDown: js.Function1[dom.Event, Unit] = (_) => hide()

  /** Add the listeners to the viewport; returns their removal. */
  def install(viewport: dom.html.Element): () => Unit = {
    viewport.addEventListener("pointerover", onOver)
    viewport.addEventListener("pointermove", onMove)
    viewport.addEventListener("pointerleave", onLeave)
    viewport.addEventListener("pointerdown", onDown)
    viewport.addEventListener("wheel", onDown)
    () => {
      hide(); last = null
      viewport.removeEventListener("pointerover", onOver)
      viewport.removeEventListener("pointermove", onMove)
      viewport.removeEventListener("pointerleave", onLeave)
      viewport.removeEventListener("pointerdown", onDown)
      viewport.removeEventListener("wheel", onDown)
    }
  }
}

object Tooltip {

  /** How long the pointer rests before the card shows (ms). */
  val Delay = 600
  private val MaxW = 320

  /** What is under the pointer. */
  sealed trait Target
  object Target {
    /** An instance, a logic node (register, op, FSM, block, memory) or a boundary stub. */
    final case class Box(id: String) extends Target
    /** A wire, by its two ends (`from` is the sink, `to` the driver). */
    final case class Wire(from: String, to: String) extends Target
    /** A pin dot, or an expandable port row, of the box `box`. */
    final case class Pin(path: String, box: String) extends Target
  }

  /** The card: `kind` (box, wire, pin), a name, what it is, detail lines, and
    * what the mouse does there. */
  final case class Content(kind: String, name: String, what: String, detail: Seq[String], actions: String) {
    def text: String = (Seq(name, what) ++ detail :+ actions).mkString("\n")
  }

  private def attr(el: dom.Element, a: String): Option[String] = Option(el.getAttribute(a))

  /** The box id `el` is inside (in the content layer), or null. */
  def boxOf(el: dom.Element): String =
    if (el == null) null
    else {
      val b = el.closest("[data-box-inst], [data-boundary-stub]")
      if (b == null || b.closest("[data-content]") == null) null
      else attr(b, "data-box-inst").orElse(attr(b, "data-boundary-stub")).orNull
    }

  /** What the element under the pointer stands for: a pin dot or an
    * expandable port row, a wire, a box. None on the background, on value
    * stubs, and on the controls that keep their own title (the fold toggle,
    * the open-diagram mark). Pure on the DOM attributes the render sets; the
    * hint bar can read it too. */
  def targetOf(el: dom.Element): Option[Target] =
    if (el == null || el.closest("[data-content]") == null || el.closest("[data-fold-toggle], [data-fsm-open]") != null) None
    // A field's dot is a pin target only when it toggles its row (an
    // expandable field, marked data-pan-block); a plain one is its box's.
    else Option(el.closest("[data-pin], [data-pin-field][data-pan-block], [data-bundle-toggle], path[data-wire], [data-box-inst], [data-boundary-stub]")).flatMap { t =>
      lazy val box = Option(boxOf(t))
      attr(t, "data-pin").orElse(attr(t, "data-pin-field")).orElse(attr(t, "data-bundle-toggle")) match {
        case Some(p) => box.map(Target.Pin(p, _))
        case None =>
          attr(t, "data-wire") match {
            case Some(f) => Some(Target.Wire(f, attr(t, "data-wire-to").getOrElse("")))
            case None    => box.map(Target.Box(_))
          }
      }
    }

  private val Pan = "Drag: pan"
  private def acts(xs: String*): String = (xs :+ Pan).mkString(" · ")
  private def plural(n: Int, one: String): String = s"$n $one${if (n == 1) "" else "s"}"
  /** A type key and its width in bits (`known` when the row has it), or what is known of them.
    * `Wire` is the backend's key for an untyped net: no type. */
  private def typed(g: Geom, key0: String, known: Int = 0): String = {
    val key = if (key0 == "Wire") "" else key0
    val n = if (known > 0) known else if (key.isEmpty) 0 else Inspector.totalWidth(key, g.design)
    (Seq(if (key.isEmpty) "" else Inspector.typeName(key, g.design)) ++ Option.when(n > 0)(if (n == 1) "1 bit" else s"$n bits")).filter(_.nonEmpty).mkString(", ")
  }

  /** The card for a target of the view `g` (None when it is not in it). */
  def content(g: Geom, t: Target): Option[Content] = t match {
    case Target.Box(id)        => box(g, id)
    case Target.Wire(from, to) => g.wires.find(w => w.fromPath == from && (to.isEmpty || w.toPath == to)).map(wire(g, _))
    case Target.Pin(path, b)   => pin(g, path, b)
  }

  private val WireActs = acts("Click: select and follow the net")

  /** The hint bar's (name, actions) for a target: the card's, read from the
    * view's maps. A wire's are fixed, so no scan of the wires per pointer move. */
  def hintOf(g: Geom, t: Target): Option[(String, String)] = t match {
    case Target.Wire(_, _) => Some(("Wire", WireActs))
    case _                 => content(g, t).map(c => (c.name, c.actions))
  }

  private def box(g: Geom, id: String): Option[Content] = g.boxById.get(id).map { n =>
    val ln    = g.logicById.get(id)
    val label = ln.map(_.label).filter(_.nonEmpty).getOrElse(id)
    val width = ln.map(l => typed(g, l.bundle)).filter(_.nonEmpty).toSeq
    n.kind match {
      case NodeKind.Reg => Content("box", id, "register", width, acts("Click: inspect"))
      case NodeKind.Op =>
        val op   = ln.map(_.op).getOrElse("")
        val what = if (op == "mux") "multiplexer" else if (op.isEmpty) "operator" else s"operator $op (${opGlyph(op)})"
        Content("box", id, what, width, acts("Click: inspect"))
      case NodeKind.Fsm =>
        val d = ln.flatMap(_.fsm)
        Content("box", label, "state machine",
          d.map(f => s"${plural(f.states.size, "state")}, ${plural(f.transitions.size, "transition")}").toSeq,
          if (d.isDefined) acts("Double-click: state diagram", "Click title: inspect") else acts("Click title: inspect"))
      case NodeKind.Block =>
        Content("box", label, "logic block", Seq("Logic of this module, grouped into one block"), acts("Click title: inspect"))
      case NodeKind.Mem =>
        val tag = ln.flatMap(_.memInfo).map(mi => s"${if (mi.sync) "SRAM" else "RAM"} ${mi.depth}x${mi.elem}")
        Content("box", id, "memory", tag.toSeq, acts("Click title: inspect"))
      case _ =>
        g.childByName.get(id).filter(_ => n.kind != NodeKind.Boundary) match {
          case Some(ch) =>
            val info = g.instInfo(ch)
            val sig  = info.mod.filter(_.scalaClass.nonEmpty).map(ScalaClassLabel.signature).getOrElse("")
            val counts = info.mod.map { m =>
              if (info.blackbox) plural(m.ports.size, "port")
              else Seq(plural(m.ports.size, "port"), plural(m.children.size, "instance"), plural(m.wires.size, "connection")).mkString(" · ")
            }
            val detail = Seq(
              Option.when(info.folded)(s"Generator group: ${ch.foldCount} copies"),
              Option.when(info.blackbox)(if (info.mod.exists(_.kind == ModuleKind.Intrinsic)) "Intrinsic module" else "External module"),
              counts,
            ).flatten
            val actions =
              if (info.folded) acts("Double-click: unfold", "Click title: inspect")
              else if (info.drillable) acts("Double-click: open", "Click title: inspect")
              else acts("Click title: inspect")
            Content("box", if (info.folded) s"$id × ${ch.foldCount}" else id, if (sig.nonEmpty) sig else ch.`type`, detail, actions)
          case None =>
            Content("box", id, if (n.kind == NodeKind.Boundary) "port of this module" else n.kind, Nil, acts("Click: inspect"))
        }
    }
  }

  /** A path as the user reads it: a register's Q pin `r~q` is `r`, an op's
    * input pin `x~in0` is `x.in0` (the raw encoding never reaches the card). */
  private def pretty(p: String): String = {
    val dot  = p.indexOf('.')
    val head = if (dot < 0) p else p.substring(0, dot)
    val rest = if (dot < 0) "" else p.substring(dot)
    (head match {
      case OpInRe(n, i)              => s"$n.in$i"
      case _ if head.endsWith("~q") => head.dropRight(2)
      case _                         => head
    }) + rest
  }

  private def wire(g: Geom, w: SchematicGeom.Wire): Content = {
    // A wire runs from its driver (`toPath`) to its sink (`fromPath`).
    val ends =
      if (w.bidir) Seq(s"Between: ${pretty(w.toPath)}", s"and: ${pretty(w.fromPath)}")
      else Seq(s"From: ${pretty(w.toPath)}", s"To: ${pretty(w.fromPath)}")
    Content("wire", if (w.dangling) "Wire (no route)" else "Wire", typed(g, w.bundle),
      ends ++ Option.when(w.dangling)("The layout found no route: drawn straight"), WireActs)
  }

  private def pin(g: Geom, path: String, boxId: String): Option[Content] =
    g.rows.get(boxId).flatMap(br => (br.westRows ++ br.eastRows).find(_.path == path)).map { r =>
      // An op's input pins show their role (`sel`, `a`, `b`), as the inspector does.
      val op = g.boxById.get(boxId).filter(_.kind == NodeKind.Op).flatMap(_ => g.logicById.get(boxId)).map(_.op)
      val plain = pretty(path)
      // A row label may carry its type ("io: AXI4"); the type has its own place.
      val name = (path, op) match {
        case (OpInRe(_, i), Some(o)) => inRole(o, i.toInt)
        case _                       => Option(stripQ(r.label)).map(_.split(": ").head).filter(_.nonEmpty).getOrElse(plain)
      }
      val dir = r.dir match {
        case Some(PortDir.In)     => "input"
        case Some(PortDir.Out)    => "output"
        case Some(PortDir.Inout)  => "inout"
        case Some(PortDir.Master) => "master"
        case Some(PortDir.Slave)  => "slave"
        case _                    => if (r.side == PinSide.West) "input" else "output"
      }
      val what = (Seq(dir) :+ typed(g, r.bundleKey, r.width)).filter(_.nonEmpty).mkString(", ")
      Content("pin", name, what, Option.when(name != plain)(plain).toSeq,
        if (r.isExpandable) acts("Click: expand or collapse") else acts())
    }

  private val Dash = "[\u2013\u2014]".r

  /** Test hook (smoke): the card of every box, wire and pin of the view; the
    * targets with no card, an empty name or action, or a dash. */
  def selfCheck(g: Geom): (Int, Seq[String]) = if (g == null) (0, Nil) else {
    val targets: Seq[(String, Target)] =
      g.layout.nodes.filter(_.kind != NodeKind.Const).map(n => s"box ${n.id}" -> Target.Box(n.id)) ++
      g.wires.map(w => s"wire ${w.fromPath}" -> Target.Wire(w.fromPath, w.toPath)) ++
      g.rows.toSeq.flatMap { case (b, br) =>
        (br.westRows ++ br.eastRows).filter(r => r.isTopLevel || r.isExpandable)
          .map(r => s"pin ${r.path}" -> Target.Pin(r.path, b))
      }
    val bad = targets.collect {
      case (k, t) if content(g, t).forall(c => c.name.isEmpty || c.actions.isEmpty || Dash.findFirstIn(c.text).isDefined) => k
    }
    (targets.size, bad)
  }
}
