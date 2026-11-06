package socle.ui.wave

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.js.timers._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Success
import socle.schema.{Design, ModuleDef, NodeKind}
import socle.ui.data.{Embedded, IdeState}
import socle.wave.{SurferWire, WaveLayout, WaveLinks, WaveSettings}

/** Makes the waveform viewer follow the panel's selection and drill path.
  *
  * The viewer is the language server's `waveViewer` (`/ide.json`):
  *  - `surfer-panel`: `pane`, loaded with each new trace and driven here:
  *    the embedded [[SurferPane]], or in VS Code the [[RemotePane]] of the
  *    Surfer tab, which a new trace also opens (`socle-surfer-open`) in
  *    place of the Waveform tab;
  *  - `surfer`: the Surfer app, held by the language server: only
  *    `POST /wave/select` (`{"traceTest","instPath","selection"}`), the server
  *    plans (without a second debounce). Once the trace's names are read (a
  *    new trace, or the page loaded) the module in view is posted with no
  *    selection, so the app shows it whatever it showed before;
  *  - `custom` (or none): nothing.
  *
  * Each change of the path or the selection (re)starts a `followDelayMs`
  * debounce; when it fires:
  *  - a module not shown yet (another path, trace, design or plan setting)
  *    is planned ([[WaveLayout.plan]]);
  *  - a selection inside it is mapped to a signal ([[signalOf]]) and focused
  *    ([[WaveLayout.focus]]); nothing happens when it is none.
  *
  * Nothing is followed while there is no trace, while following is off
  * (`wavesFollowSelection`), while the drill path does not start at the top,
  * or while the panel shows another test than the trace's (paused, see
  * [[WaveFollow.paused]]). A new trace still shows the module in view once (Simulate
  * shows the test's design), whatever the follow setting.
  *
  * Surfer's right-click actions ([[SurferWire.Event]]) act on the trace's
  * design: Add drivers and Add loads add the signals [[WaveLinks]] finds;
  * Go to declaration calls `onReveal` (show the signal's module, select it,
  * open its source), as does a `reveal` in the state (the Surfer app's, the
  * language server opened the source). The selection a reveal makes is not
  * followed: Surfer keeps what it shows until the next selection.
  */
final class WaveFollow(
    stateSig: Signal[Option[IdeState]],
    designSig: Signal[Option[Design]],
    pathSig: Signal[List[String]],
    selectedVar: Var[Option[String]],
    moduleSig: Signal[Option[ModuleDef]],
    onTrace: () => Unit,
    val pane: WavePane,
    onReveal: (WaveLinks.Located, Boolean) => Option[String] = (_, _) => None,
) {
  import WaveFollow._

  private var state  = Option.empty[IdeState]
  private var design = Option.empty[Design]
  private var module = Option.empty[ModuleDef]
  private var path   = List.empty[String]
  private var sel    = Option.empty[String]

  /** The trace's names, for the trace path they were read for. */
  private var names = Option.empty[Names]
  private var traceSeen = ""
  private var opened = ("", "") // the viewer and trace the Waveform tab was last opened for
  private var projection: Any = null
  private var timer = Option.empty[SetTimeoutHandle]
  /** What the viewer shows: trace, path, plan settings and design (by identity). */
  private var shownKey = Option.empty[(String, List[String], WaveSettings, Design)]
  /** The plan handed to the pane and not shown yet. */
  private var showingKey = Option.empty[(String, List[String], WaveSettings, Design)]
  private val viewVar = Var(View("", "", None, 0))
  /** The drill path and selection a reveal made: not followed (see [[fire]]). */
  private var hold = Option.empty[(List[String], Option[String])]
  /** The last state's reveal seq; None before the first state (a reveal
    * already there when the page loads is not applied). */
  private var revealSeq = Option.empty[Int]

  /** What the Waveform tab says. */
  val viewSig: Signal[View] = viewVar.signal

  /** Whether the plans colour the ports by direction (the colours legend). */
  val colorByDirectionSig: Signal[Boolean] =
    stateSig.map(_.flatMap(_.waveSettings).getOrElse(WaveSettings()).colorByDirection).distinct

  /** The subscriptions, bound to the app shell. */
  val binders: Seq[Modifier[HtmlElement]] = Seq(
    designSig --> { d => design = d },
    moduleSig --> { m => module = m },
    stateSig --> { s => state = s; onState() },
    Signal.combine(pathSig, selectedVar.signal) --> { case (p, s) => path = p; sel = s; schedule() },
  )

  pane match {
    case r: RemotePane => r.resync = () => resync()
    case _             => ()
  }
  pane.onEvent = onSurferEvent

  /** A right-click action chosen in the panel's Surfer (or the Surfer tab's). */
  private def onSurferEvent(e: SurferWire.Event): Unit =
    try for {
      s <- state
      if s.waveViewer == Panel && s.trace.nonEmpty
      d <- design
      n <- names
      if n.trace == s.trace
    } {
      if (paused(s)) dom.console.info(s"socle: Surfer ${e.kind}: the panel shows another test than the trace's")
      else e.kind match {
        case "goto_declaration" =>
          WaveLinks.locate(d, n.prefix, e.variable) match {
            case Some(loc) => reveal(loc, open = true)
            case None      => dom.console.info(s"socle: Go to declaration: ${e.variable} is not a signal of the design")
          }
        case k =>
          val found = if (k == "add_drivers") WaveLinks.drivers(d, n.prefix, n.names, e.variable)
                      else WaveLinks.loads(d, n.prefix, n.names, e.variable)
          if (found.isEmpty) dom.console.info(s"socle: Surfer $k: nothing traced found for ${e.variable}")
          else pane.add(found, Some(e.variable))
      }
    } catch { case ex: Throwable => dom.console.warn(s"socle: Surfer ${e.kind}: ${ex.getMessage}") }

  private def reveal(loc: WaveLinks.Located, open: Boolean): Unit = {
    val sel = onReveal(loc, open)
    hold = Some((loc.instPath, sel))
  }

  if (Embedded.on) installHook()

  /** Shows the trace and the module in view again, as for a new viewer: the
    * Surfer tab came back (a new or reloaded tab, or an error). */
  def resync(): Unit = if (state.exists(_.waveViewer == Panel)) {
    shownKey = None
    showingKey = None
    onState()
    fire(initial = true)
  }

  private def settings: WaveSettings = state.flatMap(_.waveSettings).getOrElse(WaveSettings())

  /** The trace's test (`<suite>#<test>`, "" for none), for the Surfer tab's title. */
  def traceTest: String = state.fold("")(_.traceTest)

  private def following(s: IdeState): Boolean = s.waveViewer == Panel || s.waveViewer == App

  private def onState(): Unit = state.foreach { s =>
    val seq = s.reveal.fold(0)(_.seq)
    if (revealSeq.exists(_ != seq)) s.reveal.foreach(r => reveal(WaveLinks.Located(r.instPath, r.leaf, socle.schema.SourceRef.unknown), open = false))
    revealSeq = Some(seq)
    if (s.trace != traceSeen) {
      traceSeen = s.trace
      names = None
      shownKey = None
      showingKey = None
      if (s.trace.nonEmpty && following(s)) fetchNames(s.trace)
    }
    if (s.waveViewer == Panel && s.trace.nonEmpty) {
      // A new trace (or the panel viewer chosen) shows the Waveform tab, or opens the Surfer tab.
      if (opened != ((s.waveViewer, s.trace))) {
        opened = (s.waveViewer, s.trace)
        pane match { case r: RemotePane => r.open(SurferPane.absolute(s.trace), s.traceTest, force = false); case _ => onTrace() }
      }
      installHook()
      pane.load(SurferPane.absolute(s.trace))
    }
    updateView()
    // A resume, a setting or a viewer change applies to the module in view.
    val p = (s.waveViewer, s.trace, s.traceTest, s.test, settings)
    if (p != projection) { projection = p; schedule() }
  }

  private def updateView(): Unit = state.foreach { s =>
    val v = View(s.waveViewer, s.trace, if (s.trace.nonEmpty && paused(s)) Some(s.traceTest) else None,
      if (shownKey.exists(_._1 == s.trace)) viewVar.now().missing else 0)
    if (v != viewVar.now()) viewVar.set(v)
  }

  private def fetchNames(trace: String): Unit = {
    val url  = SurferPane.absolute(trace.stripSuffix("/trace") + "/names")
    val init = js.Dynamic.literal(cache = "no-store").asInstanceOf[dom.RequestInit]
    dom.fetch(url, init).flatMap(r => if (r.ok) r.text().toFuture else scala.concurrent.Future.failed(new RuntimeException(s"HTTP ${r.status}")))
      .onComplete {
        case Success(txt) if traceSeen == trace =>
          try {
            val o = ujson.read(txt).obj
            names = Some(Names(trace, o("prefix").str, o("names").arr.map(_.str).toSet))
            fire(initial = true)
          } catch { case e: Throwable => dom.console.warn(s"socle: waveform names: ${e.getMessage}") }
        case Success(_) => ()
        case scala.util.Failure(e) => dom.console.warn(s"socle: waveform names: ${e.getMessage}")
      }
  }

  private def schedule(): Unit = {
    timer.foreach(clearTimeout)
    timer = Some(setTimeout(math.max(0, settings.followDelayMs).toDouble) { timer = None; fire(initial = false) })
  }

  private def fire(initial: Boolean): Unit = if (initial || !held()) {
    try for {
      s <- state
      if following(s) && s.trace.nonEmpty && !paused(s)
      if initial || settings.followSelection
      d <- design
      n <- names
      if n.trace == s.trace && path.headOption.contains(d.top)
    } {
      val st   = settings
      val key  = (s.trace, path, planSettings(st), d)
      val plan = WaveLayout.plan(d, path, n.prefix, n.names, st)
      val signal = sel.flatMap(x => module.flatMap(m => signalOf(m, d, x)))
      val focus  = signal.flatMap(x => WaveLayout.focus(d, path, n.prefix, n.names, x))
      if (s.waveViewer == Panel) {
        // The module's plan, unless shown or on its way; an empty one clears
        // Surfer. It counts as shown once Surfer has it.
        if (!shownKey.contains(key) && !showingKey.contains(key)) {
          showingKey = Some(key)
          pane.show(plan, path, SurferPane.absolute(s.trace), shown => if (showingKey.contains(key)) {
            showingKey = None
            // missing: what the trace's names lack, plus what Surfer dropped (names unknown, as for FST)
            shown.foreach { dropped => shownKey = Some(key); viewVar.update(_.copy(missing = plan.missing + dropped)) }
          })
        }
        focus.foreach(pane.focus)
      } else {
        // The server plans (an empty plan leaves its Surfer as it is). A page
        // (re)load posts the module in view whatever the app shows: the server
        // re-plans only when it shows another one.
        val fresh = !shownKey.contains(key) && (plan.groups.nonEmpty || plan.missing > 0)
        if (fresh) { shownKey = Some(key); viewVar.update(_.copy(missing = plan.missing)) }
        if (focus.isDefined) post(s.traceTest, path, signal)
        else if (fresh || initial) post(s.traceTest, path, None)
      }
    } catch { case e: Throwable => dom.console.warn(s"socle: waveform follow: ${e.getMessage}") }
  }

  /** Whether the path and selection are the ones a reveal made (once). */
  private def held(): Boolean = {
    val h = hold
    hold = None
    h.contains((path, sel))
  }

  /** `POST /wave/select`: same-origin, JSON, relative like `/open`. */
  private def post(traceTest: String, instPath: List[String], selection: Option[String]): Unit = {
    val body = ujson.Obj("traceTest" -> traceTest, "instPath" -> instPath,
      "selection" -> selection.fold[ujson.Value](ujson.Null)(ujson.Str(_)))
    val init = js.Dynamic.literal(
      method = "POST", headers = js.Dynamic.literal("Content-Type" -> "application/json"), body = ujson.write(body),
    ).asInstanceOf[dom.RequestInit]
    dom.fetch("wave/select", init).toFuture.onComplete(_ => ())
  }

  private var hooked = false

  /** `window.__socleWave`: the pane's state and items, and a way to select
    * anything (a bundle like `io.out` has no node of its own to click), to act
    * as Surfer's right-click menu (`event`) and to read the drill path and the
    * selection (`view`). For the smoke: installed once, in embedded mode or
    * when the pane gets a trace. */
  private def installHook(): Unit = if (!hooked) {
    hooked = true
    dom.window.asInstanceOf[js.Dynamic].__socleWave = js.Dynamic.literal(
      snapshot = (() => pane.snapshot()): js.Function0[js.Object],
      items = (() => pane.items()): js.Function0[js.Promise[js.Array[js.Object]]],
      select = ((s: String) => selectedVar.set(Option(s))): js.Function1[String, Unit],
      event = ((k: String, v: String) => onSurferEvent(SurferWire.Event(k, v))): js.Function2[String, String, Unit],
      view = (() => js.Dynamic.literal(path = js.JSON.stringify(js.Array(path: _*)), selection = sel.fold[js.Any](null)(s => s))): js.Function0[js.Dynamic],
    )
  }
}

object WaveFollow {
  val Panel = "surfer-panel"
  val App   = "surfer"

  /** What the Waveform tab shows: the viewer, the trace, the trace's test when
    * following is paused (the panel shows another test) and the signals of the
    * module shown that the trace lacks. */
  final case class View(viewer: String, trace: String, pausedFor: Option[String], missing: Int)

  private final case class Names(trace: String, prefix: String, names: Set[String])

  /** Whether the panel shows another test than the trace's: its
    * `<suite>#<test>` is not `traceTest`. A server that sends no suite is
    * matched on the test name alone. */
  def paused(s: IdeState): Boolean =
    if (s.suite.nonEmpty) s.traceTest != s"${s.suite}#${s.test}"
    else s.traceTest != s.test && !s.traceTest.endsWith("#" + s.test)

  /** The settings a plan depends on (not the follow switch nor its delay). */
  private def planSettings(s: WaveSettings): WaveSettings = s.copy(followSelection = true, followDelayMs = 0)

  /** The signal of `mod` a schematic selection stands for, for
    * [[WaveLayout.focus]]:
    *  - an FSM: its state register;
    *  - a block: the first of its pins whose wire's other end is a port or
    *    signal of the module;
    *  - a register node: itself;
    *  - a wire (selected by its `from`): its end that is a port or signal of
    *    the module (a child instance's pin is not in the list);
    *  - another logic node: none;
    *  - anything else (a port, a bundle pin, an instance) as it is: the focus
    *    resolves it or not. */
  def signalOf(mod: ModuleDef, d: Design, sel: String): Option[String] = {
    def clean(end: String) = end.replaceAll("~[^.\\[]*", "")
    def own(end: String): Boolean = {
      val head = socle.layout.LayoutCore.pathHead(clean(end))
      mod.ports.exists(_.name == head) || mod.signals.exists(_.name == head)
    }
    mod.logic.find(_.id == sel) match {
      case Some(n) if n.kind == NodeKind.Fsm =>
        // Its register (`state`); an older design keeps it in the box's region.
        Some(n.state).filter(_.nonEmpty).orElse(d.regions.get(n.id).flatMap { r =>
          val regs = r.logic.filter(_.kind == NodeKind.Reg).map(l => clean(l.id))
          regs.find(id => mod.signals.exists(_.name == id)).orElse(regs.headOption)
        })
      case Some(n) if n.kind == NodeKind.Block =>
        d.regions.get(n.id).toSeq.flatMap(_.ports).view.flatMap { p =>
          val pin = s"${n.id}.${p.name}"
          mod.wires.find(w => w.from == pin || w.to == pin).map(w => if (w.from == pin) w.to else w.from)
        }.find(own).map(clean)
      case Some(n) if n.kind == NodeKind.Reg => Some(clean(n.id))
      case Some(_) => None
      case None => mod.wires.find(_.from == sel) match {
        case Some(w) => Seq(w.from, w.to).find(own).map(clean)
        case None    => Some(sel)
      }
    }
  }
}
