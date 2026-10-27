package socless.ui.wave

import com.raquo.laminar.api.L._
import scala.scalajs.js
import socless.wave.SurferWire
import socless.wave.SurferWire.{Add, Cmd, Focus, Hello, Load, Show, Status}
import socless.wave.WavePlan

/** The VS Code Surfer tab, driven from the SoCLess panel: each call is posted
  * (`post`, to the extension, which relays it) as
  * `{type: "socless-surfer-cmd", cmd}`, and the tab's
  * `socless-surfer-status` replies come back through [[onStatus]].
  *
  *  - [[hello]] posts `Hello` when the panel starts: the tab answers with its
  *    status, or the extension with `socless-surfer-closed` when there is no
  *    tab, so a reloaded panel shows the tab's real state.
  *  - `load` posts `Load` for a new url; `show` posts `Show` with an
  *    increasing `seq` (seeded per page, so a tab's late status for an older
  *    page never completes this one's), and only the latest seq's `done` is kept: it completes
  *    on the status that reports that seq (its `dropped`, or None when
  *    `shownSeqFailed`); `focus` posts `Focus`, `add` posts `Add`.
  *  - A status carrying an `event` (a right-click action in the tab's
  *    Surfer) goes to [[onEvent]].
  *  - The tab's `state` is mirrored into [[statusVar]].
  *  - A status whose `loadedTrace` is not the last Load (a fresh or reloaded
  *    tab), the first one after [[onClosed]] (a new tab), or an `error` for
  *    the last Load (retried once per trace and tab), drops the pending
  *    `done` with None and calls [[resync]]: [[WaveFollow]] sends the trace
  *    and the plan again.
  *  - After [[onClosed]] nothing is posted but `socless-surfer-open`, until a
  *    tab reports again. */
final class RemotePane(post: js.Any => Unit) extends WavePane {

  val statusVar: Var[String] = Var("idle")

  /** Set by [[WaveFollow]]: sends the trace and the module's plan again. */
  var resync: () => Unit = () => ()

  private var lastLoad = ""          // the url of the last Load posted ("" for none)
  private var seq      = (js.Date.now() % 1e9).toInt // unique per page, see show
  private var pending  = Option.empty[(Int, Option[Int] => Unit)] // the latest Show not completed
  private var closed   = false
  private var retried  = Set.empty[String] // the traces re-sent after an error
  private var sent     = 0               // commands posted (the smoke)

  /** Asks the extension to open or reveal the Surfer tab for `trace` (absolute) of the test
    * `traceTest` (`<suite>#<test>`, its title): always when `force` (Show Surfer), else only for a
    * trace not opened before in that VS Code window. */
  def open(trace: String, traceTest: String, force: Boolean): Unit =
    post(js.Dynamic.literal(`type` = "socless-surfer-open", trace = trace, traceTest = traceTest, force = force))

  /** Asks for the tab's state (its status, or `socless-surfer-closed`); called once the
    * listeners are installed. */
  def hello(): Unit = send(Hello)

  def load(url: String): Unit =
    if (url != lastLoad) { lastLoad = url; drop(); send(Load(url)) }

  def show(plan: WavePlan, path: List[String], trace: String, done: Option[Int] => Unit): Unit =
    if (closed) done(None)
    else {
      seq += 1
      pending = Some((seq, done)) // a newer Show replaces the older one: its done is never called
      send(Show(seq, plan, path, trace))
    }

  def focus(trace: String): Unit = send(Focus(trace))

  def add(traces: Seq[String], after: Option[String]): Unit = send(Add(traces, after))

  /** A `socless-surfer-status` from the tab. */
  def onStatus(s: Status): Unit = {
    val reopened = closed // the first status of a new tab
    closed = false
    if (statusVar.now() != s.state) statusVar.set(s.state)
    val fresh = reopened || s.loadedTrace != lastLoad
    val retry = !fresh && s.state == "error" && lastLoad.nonEmpty && !retried.contains(lastLoad)
    if (fresh) retried = Set.empty // a new tab may retry again
    if (fresh || retry) {
      if (retry) retried += lastLoad
      drop()
      lastLoad = "" // the trace goes again
      resync()
    } else for {
      shown       <- s.shownSeq
      (n, done)   <- pending
      if n == shown
    } { pending = None; done(if (s.shownSeqFailed) None else Some(s.dropped.getOrElse(0))) }
    s.event.foreach(onEvent)
  }

  /** `socless-surfer-closed`: the tab is gone. */
  def onClosed(): Unit = {
    closed = true
    drop()
    statusVar.set("closed")
  }

  private def drop(): Unit = { pending.foreach(_._2(None)); pending = None }

  private def send(c: Cmd): Unit = if (!closed) {
    sent += 1
    post(js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = js.JSON.parse(SurferWire.writeCmd(c))))
  }

  def snapshot(): js.Object = js.Dynamic.literal(
    status = statusVar.now(), remote = true, loadedTrace = lastLoad, seq = seq, closed = closed, sent = sent,
    pending = pending.fold[js.Any](null)(_._1),
  )

  /** The items are in the tab. */
  def items(): js.Promise[js.Array[js.Object]] = js.Promise.resolve[js.Array[js.Object]](js.Array[js.Object]())
}
