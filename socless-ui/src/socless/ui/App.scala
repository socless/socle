package socless.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js.annotation._

/** The Scala.js + Laminar frontend (epic #172), Phase 0: the app shell.
  *
  * Renders the overall chrome - top bar and the three-pane layout (hierarchy /
  * schematic / inspector) - with placeholder content. Later phases fill the
  * panes in: state + design loading (Phase 1), the schematic calling the engine
  * in-process (Phase 2), the inspector/tabs (Phase 3), and so on.
  *
  * NoModule + @JSExportTopLevel emits a top-level `let SoclessUI`, so the page
  * bridges it onto window and calls `SoclessUI.main("root")`. */
@JSExportTopLevel("SoclessUI")
object App {

  /** Mount the shell into the element with id `rootId`. Returns a short status
    * string (used by the Phase-0 smoke). */
  @JSExport
  def main(rootId: String): String = {
    val container = dom.document.getElementById(rootId)
    if (container == null) return "no-root"

    // Prove in-process access to the shared backend logic (compiled from the
    // same socless sources as the JVM build): no window.SoclessLayout boundary.
    val engineLive = socless.BundleTree.isAggregateType("Vec[AXI4, 2]", socless.Design("Top", Map.empty, Nil))

    render(container, shell(engineLive))
    "mounted"
  }

  private def shell(engineLive: Boolean): Element =
    div(
      dataAttr("app-shell") := "1",
      cls := "scl-shell",
      topBar(engineLive),
      div(
        cls := "scl-panes",
        pane("hierarchy", "HIERARCHY", "Module tree (Phase 1)"),
        pane("schematic", "SCHEMATIC", "Boxes + wires from the in-process engine (Phase 2)"),
        pane("inspector", "INSPECTOR", "Wire / module details (Phase 3)"),
      ),
    )

  private def topBar(engineLive: Boolean): Element =
    div(
      cls := "scl-topbar", dataAttr("topbar") := "1",
      span(cls := "scl-logo", "SoCLess"),
      span(cls := "scl-sub", "Scala.js + Laminar frontend"),
      span(
        cls := "scl-engine", dataAttr("engine-live") := engineLive.toString,
        if (engineLive) "engine: in-process ✓" else "engine: unavailable",
      ),
    )

  private def pane(id: String, title: String, placeholder: String): Element =
    div(
      cls := s"scl-pane scl-pane-$id", dataAttr("pane") := id,
      div(cls := "scl-pane-title", title),
      div(cls := "scl-pane-body", placeholder),
    )
}
