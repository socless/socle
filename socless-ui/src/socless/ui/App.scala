package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js.annotation._
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.util.{Success, Failure}
import socless.Design

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

  @JSExport
  def main(rootId: String): String = {
    val container = dom.document.getElementById(rootId)
    if (container == null) return "no-root"
    render(container, shell())
    loadDesign()
    "mounted"
  }

  // ── Design loading ───────────────────────────────────────────────────────
  private def loadDesign(): Unit = {
    val fetched: Future[String] =
      dom.fetch("design.json").flatMap { r =>
        if (r.ok) r.text() else Future.failed(new RuntimeException(s"HTTP ${r.status}"))
      }
    fetched.map(txt => upickle.default.read[Design](txt)).onComplete {
      case Success(d) =>
        val top = topOf(d)
        designVar.set(Some(d))
        pathVar.set(if (top.nonEmpty) List(top) else Nil)
        expandedVar.set(if (top.nonEmpty) Set(top) else Set.empty) // top expanded -> shows its children
        errorVar.set(None)
      case Failure(e) =>
        errorVar.set(Some(Option(e.getMessage).getOrElse(e.toString)))
    }
  }

  private def topOf(d: Design): String =
    if (d.top.nonEmpty) d.top else d.modules.headOption.map(_.name).getOrElse("")

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

  /** Tiny glyph for a module kind (mirrors the JS ModuleGlyph). */
  private def glyph(d: Design, tpe: String): String =
    d.modules.find(_.name == tpe) match {
      case None => "·"
      case Some(m) =>
        if (m.children.length >= 3) "⬢"               // hub
        else if (m.metrics.registers > 0) "◆"          // register-bearing
        else if (m.kind == "external" || m.kind == "intrinsic") "■" // blackbox
        else "·"
    }

  private def toggle(id: String): Unit =
    expandedVar.update(s => if (s(id)) s - id else s + id)

  // ── View ───────────────────────────────────────────────────────────────────
  private def shell(): Element =
    div(
      dataAttr("app-shell") := "1",
      cls := "scl-shell",
      topBar(),
      div(
        cls := "scl-panes",
        hierarchyPane(),
        pane("schematic", "SCHEMATIC",
          child.text <-- pathVar.signal.map(p =>
            if (p.isEmpty) "(no module loaded)" else s"viewing ${p.last}  -  ${p.mkString(" > ")}  (Phase 2: boxes + wires)")),
        pane("inspector", "INSPECTOR", "Wire / module details (Phase 3)"),
      ),
    )

  private def topBar(): Element =
    div(
      cls := "scl-topbar", dataAttr("topbar") := "1",
      span(cls := "scl-logo", "SoCLess"),
      span(cls := "scl-sub", "Scala.js + Laminar frontend"),
      span(
        cls := "scl-engine", dataAttr("design-loaded") <-- designVar.signal.map(_.isDefined.toString),
        child.text <-- designVar.signal.map {
          case Some(d) => s"${topOf(d)}  -  ${d.modules.length} modules"
          case None    => "loading design.json…"
        },
      ),
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
      span(cls := "scl-hier-glyph", glyph(d, r.tpe)),
      span(cls := "scl-hier-label", r.label),
      span(cls := "scl-hier-type", r.tpe),
    )
  }

  private def pane(id: String, title: String, body: Modifier[HtmlElement]): Element =
    div(
      cls := s"scl-pane scl-pane-$id", dataAttr("pane") := id,
      div(cls := "scl-pane-title", title),
      div(cls := "scl-pane-body", body),
    )
}
