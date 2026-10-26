package socless.ui.wave

import com.raquo.laminar.api.L._
import scala.scalajs.js
import socless.wave.WavePlan

/** What [[WaveFollow]] drives: Surfer on this page ([[SurferPane]]) or in the
  * VS Code Surfer tab ([[RemotePane]]). */
trait WavePane {

  /** Loads `url` (absolute) unless it is the trace shown or about to be. */
  def load(url: String): Unit

  /** Shows `plan` (of `path` in `trace`). `done` gets the count of paths
    * Surfer dropped once it shows the plan, None when it does not; it may
    * never be called for a plan replaced before it started. */
  def show(plan: WavePlan, path: List[String], trace: String, done: Option[Int] => Unit): Unit

  /** Focuses the signal `trace` of the plan shown. */
  def focus(trace: String): Unit

  /** Surfer's state: "idle", "booting", "loading", "ready" or "error", or "closed" when the
    * Surfer tab it drives is closed ([[RemotePane]]). */
  val statusVar: Var[String]

  /** The pane's state, for `window.__soclessWave` (the smoke). */
  def snapshot(): js.Object

  /** Surfer's displayed items, for `window.__soclessWave` (the smoke). */
  def items(): js.Promise[js.Array[js.Object]]
}
