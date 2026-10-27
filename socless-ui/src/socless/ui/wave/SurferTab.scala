package socless.ui.wave

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socless.ui.data.Embedded
import socless.wave.SurferWire._

/** The message loop of the VS Code Surfer tab page (`?host=vscode&view=surfer`):
  * the parent's `socless-surfer-cmd` messages drive the page's one
  * [[SurferPane]], and the page answers with `socless-surfer-status`: on every
  * change of the pane's state, on `Hello`, and when a `Show` completes (its
  * `seq`, with the paths Surfer dropped, or failed). `loadedTrace` is the
  * trace the tab was last asked to load. `byDirection` takes how the last
  * plan shown was coloured (the colours legend: the page has no settings). */
object SurferTab {

  /** Starts the loop for `pane`. The returned binder, bound to the page,
    * posts the state changes. */
  def run(pane: SurferPane, byDirection: Var[Boolean]): Binder[HtmlElement] = {
    var trace = ""
    def status(shown: Option[Int] = None, dropped: Option[Int] = None, failed: Boolean = false) =
      Embedded.postSurferStatus(Status(pane.statusVar.now(), trace, shown, dropped, failed))

    Embedded.onSurferCmd {
      case Load(url) => trace = url; pane.load(url)
      case Show(seq, plan, path, t) =>
        byDirection.set(plan.colorByDirection)
        pane.show(plan, path, t, r => status(shown = Some(seq), dropped = r, failed = r.isEmpty))
      case Focus(t) => pane.focus(t)
      case Hello    => status()
    }

    // `window.__soclessWave`, as in the panel (for the smoke): the pane's state and items.
    dom.window.asInstanceOf[js.Dynamic].__soclessWave = js.Dynamic.literal(
      snapshot = (() => pane.snapshot()): js.Function0[js.Object],
      items = (() => pane.items()): js.Function0[js.Promise[js.Array[js.Object]]],
    )

    pane.statusVar.signal.distinct --> { _ => status() }
  }
}
