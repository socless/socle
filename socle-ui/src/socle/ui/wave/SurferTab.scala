package socle.ui.wave

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import socle.ui.data.Embedded
import socle.wave.SurferWire._

/** The message loop of the VS Code Surfer tab page (`?host=vscode&view=surfer`):
  * the parent's `socle-surfer-cmd` messages drive the page's one
  * [[SurferPane]], and the page answers with `socle-surfer-status`: on every
  * change of the pane's state, on `Hello`, and when a `Show` completes (its
  * `seq`, with the paths Surfer dropped, or failed). `loadedTrace` is the
  * trace the tab was last asked to load. `byDirection` takes how the last
  * plan shown was coloured (the colours legend: the page has no settings).
  * A right-click action in its Surfer goes up as a status with its `event`
  * (the panel acts on it: the design is there), and `Add` comes back down. */
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
      case Add(ts, at) => pane.add(ts, at)
      case Hello    => status()
    }

    pane.onEvent = e => Embedded.postSurferStatus(Status(pane.statusVar.now(), trace, None, None, event = Some(e)))

    // `window.__socleWave`, as in the panel (for the smoke): the pane's state
    // and items, and Surfer's right-click menu.
    dom.window.asInstanceOf[js.Dynamic].__socleWave = js.Dynamic.literal(
      snapshot = (() => pane.snapshot()): js.Function0[js.Object],
      items = (() => pane.items()): js.Function0[js.Promise[js.Array[js.Object]]],
      event = ((k: String, v: String) => pane.onEvent(Event(k, v))): js.Function2[String, String, Unit],
    )

    pane.statusVar.signal.distinct --> { _ => status() }
  }
}
