package socle.lsp

import java.util.concurrent.{Executors, RejectedExecutionException, ScheduledFuture, TimeUnit}
import scala.util.control.NonFatal
import socle.schema.Design
import socle.wave.{WaveLayout, WaveSettings}

/** A panel selection (`POST /wave/select`): the trace it was made for
  * (`<suite>#<test>`), the module's instance path (top first) and the selected
  * item inside it, None for the module itself. */
final case class SelectRequest(traceTest: String, instPath: List[String], selection: Option[String])

object SelectRequest {
  /** `{"traceTest": string, "instPath": [string, ...] (non-empty), "selection": string | null}`;
    * a missing `selection` is null. None for anything else. */
  def parse(body: String): Option[SelectRequest] =
    try {
      val o = ujson.read(body).obj
      val inst = o("instPath").arr.map(_.str).toList
      val sel = o.get("selection") match {
        case None | Some(ujson.Null) => None
        case Some(v) => Some(v.str)
      }
      if (inst.isEmpty) None else Some(SelectRequest(o("traceTest").str, inst, sel))
    } catch { case NonFatal(_) => None }
}

/** Makes the viewer follow the panel's selection.
  *
  * `context` gives, when following can act at all, the viewer and what a plan
  * needs: the trace's test, the design of that test, the trace's prefix and
  * names, and the settings of the moment; None otherwise (no simulation, no
  * session, the panel on another test). It is read at each step, so a setting
  * changed meanwhile applies to the next selection.
  *
  * [[select]] ignores a request when following is off or the request is for
  * another trace than the current one, and otherwise keeps it as the only
  * pending one and (re)starts the `followDelayMs` debounce: in a burst only the
  * last selection runs. A request the panel already debounced (`debounce`
  * false: every `POST /wave/select`) runs at once, still replacing one not run
  * yet. Running it:
  *  - no selection: the plan of the module at `instPath`, unless that module
  *    is shown with the same plan settings and the same design (the panel
  *    posts its path again when it reloads);
  *  - a selection: the module's plan first unless it is shown so, then
  *    the focus on its signal ([[WaveLayout.focus]]); nothing when it is not one.
  *
  * A module path that does not resolve leaves the viewer as it is. Nothing
  * here throws.
  */
final class WaveFollower(context: () => Option[WaveFollower.Context],
    log: String => Unit = m => Console.err.println(s"[socle-lsp] $m")) {
  import WaveFollower._

  private val timer = Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
    val t = new Thread(r, "socle-wave-follow"); t.setDaemon(true); t
  }
  private val lock    = new Object
  private var pending = Option.empty[(SelectRequest, ScheduledFuture[_])] // guarded by lock
  // The viewer and the module it shows; timer thread only (showTop hands its work to it).
  private var shown = Option.empty[Shown]

  def select(req: SelectRequest, debounce: Boolean = true): Unit =
    try context().filter(c => c.settings.followSelection && c.traceTest == req.traceTest).foreach { c =>
      lock.synchronized {
        pending.foreach(_._2.cancel(false))
        val delay = if (debounce) c.settings.followDelayMs.toLong else 0L
        val f = timer.schedule((() => fire()): Runnable, delay, TimeUnit.MILLISECONDS)
        pending = Some((req, f))
      }
    } catch {
      case _: RejectedExecutionException => () // closed
      case NonFatal(e) => log(s"wave follow: $e")
    }

  /** Shows the top module's plan now (Simulate's first view), whatever the follow setting. */
  def showTop(): Unit =
    try timer.submit((() => context().foreach(c => show(c, List(c.design.top)))): Runnable).get()
    catch {
      case _: RejectedExecutionException => ()
      case NonFatal(e) => log(s"wave follow: $e")
    }

  def close(): Unit = timer.shutdownNow()

  private def fire(): Unit = {
    val req = lock.synchronized { val r = pending.map(_._1); pending = None; r }
    try for {
      r <- req
      c <- context()
      if c.settings.followSelection && c.traceTest == r.traceTest
    } r.selection match {
      case None => if (!isShown(c, r.instPath)) show(c, r.instPath)
      case Some(sel) =>
        if (!isShown(c, r.instPath)) show(c, r.instPath)
        if (isShown(c, r.instPath))
          WaveLayout.focus(c.design, r.instPath, c.prefix, c.names, sel).foreach(c.target.focus)
    } catch { case NonFatal(e) => log(s"wave follow: $e") }
  }

  /** Whether the viewer shows the plan `c` would give for `instPath` (the same
    * viewer, module, plan settings and design instance: a new capture re-plans). */
  private def isShown(c: Context, instPath: List[String]): Boolean = shown.exists { s =>
    (s.target eq c.target) && s.instPath == instPath && s.settings == planSettings(c.settings) && (s.design eq c.design)
  }

  /** The module's plan, unless its path does not resolve (an empty plan with nothing missing). */
  private def show(c: Context, instPath: List[String]): Unit = {
    val plan = WaveLayout.plan(c.design, instPath, c.prefix, c.names, c.settings)
    if (plan.groups.nonEmpty || plan.missing > 0) {
      c.target.apply(plan)
      shown = Some(Shown(c.target, instPath, planSettings(c.settings), c.design))
    }
  }
}

object WaveFollower {
  /** What the viewer shows: the plan of `instPath` in `design` with `settings`. */
  private final case class Shown(target: WaveTarget, instPath: List[String], settings: WaveSettings, design: Design)

  /** The settings a plan depends on (not the follow switch nor its delay). */
  private def planSettings(s: WaveSettings): WaveSettings = s.copy(followSelection = true, followDelayMs = 0)

  /** What following needs, read at each step. `names` empty means unknown (nothing dropped). */
  final case class Context(target: WaveTarget, traceTest: String, design: Design, prefix: String,
      names: Set[String], settings: WaveSettings)
}
