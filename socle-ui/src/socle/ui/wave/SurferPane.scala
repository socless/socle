package socle.ui.wave

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.js.timers._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}
import socle.wave.{SurferWire, WaveGroup, WaveLayout, WavePlan}

/** The embedded Surfer of the Waveform tab: the vendored WASM build
  * (socle-lsp/vendor/surfer, served at `/surfer/`), driven over its in-page
  * WCP channel.
  *
  * One Surfer per page: its canvas ([[canvasHost]]) is built once and never
  * re-rendered, the module is imported (dynamic `import()`, never the stock
  * index.html) and booted the first time a trace is to be loaded while the
  * canvas is in the document, and later traces are loaded with WCP `load`.
  * The boot follows SOURCE.md: `init` (which starts Surfer on
  * `the_canvas_id` itself), `start_wcp` once, the greeting, then a reader loop
  * on `next_wcp_sc_message`.
  *
  * Commands are pipelined ([[SurferPane.Window]] in flight, replies matched to
  * them first in, first out: Surfer answers each command once, in order). Work
  * runs one item at a time; a new trace, plan or focus replaces one not
  * started yet (latest wins), so a slow exchange never interleaves with the
  * next one, and a running plan stops at the end of its phase. A reply not
  * come after [[ReplyTimeoutMs]] of the canvas being shown (Surfer answers
  * from its frame loop, which a hidden canvas stalls), or a closed channel,
  * fails the pane for the page: a late reply would be taken for the next
  * command's.
  *
  * A plan: `clear`, one `add_items` per group (depth first, a group's items
  * before its children's), `get_item_info` for a group added short (Surfer
  * drops the paths its trace lacks; the ids are matched by name,
  * [[WaveLayout.matchAdded]]), `set_item_color` per id, the labels, then the
  * groups, then the folds. A focus scrolls its row into view
  * (`{"ScrollToItem":7}`, a visible index). Labels and groups are Surfer internal messages
  * (`inject_message`), not WCP. Their JSON, checked in libsurfer/src/message.rs
  * and lib.rs of the pinned v0.7.0 (`enum Message` is serde's default external
  * tagging; `DisplayedItemRef`, `ItemIndex` and `VisibleItemIndex` are
  * newtypes, so bare numbers; a two-field variant is an array):
  * {{{
  *   {"ItemNameChange":[2,"valid"]}
  *   {"GroupNew":{"name":"io.in","before":3,"items":[4,5,6,7]}}
  *   {"GroupFold":12}
  *   {"GroupUnfold":12}
  * }}}
  * `ItemNameChange` names the item at a visible index (`get_item_list`
  * order) with its plan label (`valid` under `io.in`); renaming moves nothing,
  * so the labels go in together, then one `get_item_list` as the barrier.
  * `before` is the item index (hidden items counted) where the group goes, at
  * the level of the item there; `items` move into it. Without `before`, Surfer
  * anchors on the focused item and pulls it in, so it is always given. Groups
  * are made top-down, one at a time: the inject queue is LIFO and runs before
  * WCP each frame, so a `get_item_list` after each inject is the barrier, and
  * its one new id is the group's. Nothing is folded until every group exists,
  * so the visible list (what `get_item_list` returns) is the item index.
  * `focus_item` only reaches visible items: a focus unfolds its groups first.
  *
  * The greeting asks for Surfer's right-click actions (Go to declaration, Add
  * drivers, Add loads: [[SurferWire.Event.Kinds]]); each one Surfer sends goes
  * to [[onEvent]]. [[add]] adds signals below the one given (Surfer inserts
  * after its focused item, so that one is focused first), then focuses the
  * first added.
  */
final class SurferPane extends WavePane {
  import SurferPane._

  /** "idle" (nothing to show), "booting", "loading", "ready" or "error". */
  val statusVar: Var[String] = Var("idle")

  private var mod: js.Dynamic = null
  private var booting = false
  private var booted  = false
  private var failed  = false // Surfer did not start: never retried on this page
  private var boots   = 0
  private var loadedTrace = ""
  private var target      = "" // the trace asked for last (loaded, loading or pending)

  // The WCP replies awaited, in send order (Surfer answers each command once,
  // in order), and the trace load awaited.
  private val waiters    = mutable.Queue.empty[Promise[js.Dynamic]]
  private var loadWaiter = Option.empty[Promise[Unit]]

  // Latest-wins work slots, then queued queries (the debug hook).
  private var pendingLoad  = Option.empty[String]
  private var pendingPlan  = Option.empty[(WavePlan, List[String], String, Option[Int] => Unit)]
  private var pendingFocus = Option.empty[String]
  private var pendingAdds  = Vector.empty[String]
  private var pendingAfter = Option.empty[String]
  private val queries      = mutable.Queue.empty[() => Future[Unit]]
  private var busy         = false

  // The plan shown: trace path <-> Surfer id, each trace's groups (outer
  // first) and the folded groups.
  private var ids       = Map.empty[String, Int]
  private var traceOf   = Map.empty[Int, String]
  private var groupsOf  = Map.empty[String, List[Int]]
  private var folded    = Set.empty[Int]
  private var groupIds  = Map.empty[String, Int] // shown group name -> Surfer id, while a plan is made
  private var shown     = Option.empty[(List[String], String)]
  private var lastFocus = Option.empty[(String, Int, Boolean)]
  private var lastScroll = Option.empty[Int] // the visible index of the last ScrollToItem
  private var focusCount = 0
  private var addCount   = 0
  private var lastAdd    = Seq.empty[String]

  private val canvasEl = canvasTag(idAttr := "the_canvas_id",
    styleAttr := "display:block;width:100%;height:100%;outline:none")

  /** The canvas, mounted once outside any per-selection re-render. */
  val canvasHost: Element = div(
    dataAttr("surfer-pane") := "1",
    dataAttr("surfer-status") <-- statusVar.signal,
    styleAttr := "position:relative;flex:1;min-height:0",
    canvasEl,
    onMountCallback(_ => pump()),
  )

  /** Loads `url` (absolute) unless it is the trace shown or about to be. */
  def load(url: String): Unit =
    if (url != target) {
      target = url
      pendingLoad = Some(url)
      pendingPlan.foreach(_._4(None)) // dropped: never shown
      pendingPlan = None
      pendingFocus = None
      pendingAdds = Vector.empty
      pump()
    }

  /** Shows `plan` (of `path` in `trace`), replacing a plan or focus not
    * started; an empty plan clears Surfer. `done` gets the count of paths
    * Surfer dropped (not in its trace) once it shows the plan, None when it
    * does not (failed, aborted for a newer plan or trace, or dropped by
    * [[load]]); it is never called for a plan replaced before it started. */
  def show(plan: WavePlan, path: List[String], trace: String, done: Option[Int] => Unit = _ => ()): Unit = {
    pendingPlan = Some((plan, path, trace, done))
    pendingFocus = None
    pendingAdds = Vector.empty
    pump()
  }

  /** Focuses the signal `trace` of the plan shown. */
  def focus(trace: String): Unit = { pendingFocus = Some(trace); pump() }

  def add(traces: Seq[String], after: Option[String]): Unit = {
    pendingAdds = (pendingAdds ++ traces).distinct
    pendingAfter = after
    pump()
  }

  private def connected: Boolean = dom.document.body.contains(canvasEl.ref)

  /** Starts the next work item when nothing runs. */
  def pump(): Unit =
    if (!booted) { if (!booting && !failed && pendingLoad.isDefined && connected) boot() }
    else if (!busy && !failed) {
      val work: Option[() => Future[Unit]] =
        pendingLoad.map { u => pendingLoad = None; () => doLoad(u) }
          .orElse(pendingPlan.filter(_ => loadedTrace.nonEmpty).map { p =>
            pendingPlan = None
            () => doShow(p._1, p._2, p._3).andThen { case r => p._4(r.toOption.flatten) }.map(_ => ())
          })
          .orElse(pendingFocus.filter(_ => loadedTrace.nonEmpty).map { t => pendingFocus = None; () => doFocus(t) })
          .orElse(Option.when(pendingAdds.nonEmpty && loadedTrace.nonEmpty) {
            val (a, at) = (pendingAdds, pendingAfter)
            pendingAdds = Vector.empty
            () => doAdd(a, at)
          })
          .orElse(if (queries.nonEmpty) Some(queries.dequeue()) else None)
      work.foreach { w =>
        busy = true
        val f = try w() catch { case e: Throwable => Future.failed(e) }
        f.onComplete { r =>
          r.failed.foreach(e => dom.console.warn(s"socle: waveform: ${e.getMessage}"))
          busy = false
          pump()
        }
      }
    }

  // ── Boot and the WCP channel ─────────────────────────────────────────────
  private def boot(): Unit = {
    booting = true
    boots += 1
    statusVar.set("booting")
    // The panic hook Surfer calls with its backtrace.
    dom.window.asInstanceOf[js.Dynamic].on_surfer_error = ((msg: String) => {
      statusVar.set("error")
      dom.console.error(s"Surfer: $msg")
    }): js.Function1[String, Unit]
    val imp = new js.Function("u", "return import(u);").asInstanceOf[js.Function1[String, js.Promise[js.Dynamic]]]
    val started = for {
      m <- imp(absolute(SurferJs)).toFuture
      _ <- m.selectDynamic("default")(js.Dynamic.literal(module_or_path = absolute(SurferWasm))).asInstanceOf[js.Promise[js.Any]].toFuture
      _ <- m.start_wcp().asInstanceOf[js.Promise[js.Any]].toFuture
      g <- { mod = m; readLoop(); send(js.Dynamic.literal(`type` = "greeting", version = "0", commands = js.Array(("waveforms_loaded" +: SurferWire.Event.Kinds): _*))) }
    } yield g
    started.onComplete { r =>
      booting = false
      r match {
        case Success(Some(g)) if g.selectDynamic("type").asInstanceOf[Any] == "greeting" =>
          booted = true
          pump()
        case other =>
          failed = true
          statusVar.set("error")
          dom.console.error(s"socle: Surfer did not start: ${other.fold(_.getMessage, g => js.JSON.stringify(g.orNull))}")
      }
    }
  }

  /** Reads every server-to-client message: events, else the reply awaited. */
  private def readLoop(): Unit =
    mod.next_wcp_sc_message().asInstanceOf[js.Promise[js.UndefOr[String]]].toFuture.onComplete {
      case Success(m) if m.isDefined =>
        val msg = js.JSON.parse(m.get)
        if (msg.selectDynamic("type").asInstanceOf[Any] == "event") {
          (msg.selectDynamic("event"): Any, msg.selectDynamic("variable"): Any) match {
            case ("waveforms_loaded", _) => loadWaiter.foreach(_.trySuccess(()))
            case (k: String, v: String) if SurferWire.Event.Kinds.contains(k) =>
              try onEvent(SurferWire.Event(k, v)) catch { case e: Throwable => dom.console.warn(s"socle: Surfer $k: ${e.getMessage}") }
            case _ => ()
          }
        } else if (waiters.nonEmpty) waiters.dequeue().trySuccess(msg)
        readLoop()
      case _ => failPane(new RuntimeException("the Surfer WCP channel closed"))
    }

  /** Fails the pane for the page and every reply awaited: once a reply is
    * missed, the next one would be taken for the wrong command. */
  private def failPane(e: Throwable): Unit = {
    failed = true
    statusVar.set("error")
    val ws = waiters.toList
    waiters.clear()
    ws.foreach(_.tryFailure(e))
  }

  /** Whether Surfer can answer now: its canvas shown in a visible page. */
  private def drawn: Boolean =
    connected && canvasEl.ref.clientWidth > 0 && dom.document.visibilityState.asInstanceOf[String] == "visible"

  /** One message and its reply: Some(reply), or None for an `error` reply.
    * Fails, and fails the pane, when no reply comes within [[ReplyTimeoutMs]]
    * of the canvas being drawn. Several may be in flight ([[pipeline]]). */
  private def send(msg: js.Any): Future[Option[js.Dynamic]] = {
    val p = Promise[js.Dynamic]()
    waiters.enqueue(p)
    var waited = 0
    val tick = setInterval(TickMs.toDouble) {
      if (drawn) waited += TickMs
      if (waited >= ReplyTimeoutMs) p.tryFailure(new RuntimeException(s"Surfer did not answer ${js.JSON.stringify(msg).take(80)}"))
    }
    p.future.onComplete { r =>
      clearInterval(tick)
      r.failed.foreach(e => if (waiters.contains(p)) failPane(e))
    }
    mod.handle_wcp_cs_message(js.JSON.stringify(msg)).asInstanceOf[js.Promise[js.Any]].toFuture
      .failed.foreach(e => p.tryFailure(e))
    p.future.map { r =>
      if (r.selectDynamic("type").asInstanceOf[Any] == "error") {
        dom.console.warn(s"socle: Surfer: ${js.JSON.stringify(r)}")
        None
      } else Some(r)
    }
  }

  /** `msgs` and their replies, in order (as [[send]]), with at most [[Window]]
    * in flight: each reply lets the next message go. Surfer drains its whole
    * WCP queue each frame; the window stays well under its channels' bound
    * (100), past which its UI thread, the page's only thread here, would block. */
  private def pipeline(msgs: Seq[js.Any]): Future[Seq[Option[js.Dynamic]]] =
    if (msgs.isEmpty) Future.successful(Nil)
    else {
      val out  = mutable.ArrayBuffer.fill[Option[js.Dynamic]](msgs.size)(None)
      val all  = Promise[Seq[Option[js.Dynamic]]]()
      var next = 0
      var left = msgs.size
      def go(): Unit = {
        val i = next
        next += 1
        send(msgs(i)).onComplete {
          case Success(r) =>
            out(i) = r
            left -= 1
            if (left == 0) all.trySuccess(out.toSeq) else if (next < msgs.size) go()
          case Failure(e) => all.tryFailure(e)
        }
      }
      (0 until math.min(Window, msgs.size)).foreach(_ => go())
      all.future
    }

  private def command(name: String, fields: (String, js.Any)*): js.Any =
    js.Dictionary[js.Any](Seq[(String, js.Any)]("type" -> "command", "command" -> name) ++ fields: _*)

  private def itemList(): Future[Seq[Int]] =
    send(command("get_item_list")).map(_.fold(Seq.empty[Int])(r => r.ids.asInstanceOf[js.Array[Int]].toSeq))

  private def inject(msg: js.Any): Unit = mod.inject_message(js.JSON.stringify(msg))

  // ── Work ─────────────────────────────────────────────────────────────────
  private def doLoad(url: String): Future[Unit] = {
    statusVar.set("loading")
    val loaded = Promise[Unit]()
    loadWaiter = Some(loaded)
    val timer = setTimeout(LoadTimeoutMs.toDouble)(loaded.tryFailure(new RuntimeException(s"the trace did not load in time: $url")))
    val done = for {
      ack <- send(command("load", "source" -> url))
      _   <- if (ack.isDefined) loaded.future else Future.failed(new RuntimeException(s"Surfer refused to load $url"))
    } yield ()
    done.andThen { case r =>
      clearTimeout(timer)
      loadWaiter = None
      clearShown()
      r match {
        case Success(_) => loadedTrace = url; statusVar.set("ready")
        case Failure(_) =>
          loadedTrace = ""
          if (target == url) target = "" // asked again, it is retried
          statusVar.set("error")
      }
    }
  }

  private def clearShown(): Unit = {
    ids = Map.empty; traceOf = Map.empty; groupsOf = Map.empty; folded = Set.empty; groupIds = Map.empty; shown = None
  }

  /** Whether a newer plan or trace waits: the running plan stops at the end of its phase. */
  private def superseded: Boolean = pendingPlan.isDefined || pendingLoad.isDefined

  /** Runs `next` unless superseded; None (not shown) otherwise. */
  private def phase(next: => Future[Option[Int]]): Future[Option[Int]] =
    if (superseded) Future.successful(None) else next

  /** Shows `plan` in phases, each pipelined or batched: `clear` and the adds,
    * the names of the ids of groups Surfer added short (a path it lacks), the
    * colours, the labels, the groups (one at a time) and the folds; a newer
    * plan or trace aborts it between phases. Some(the paths Surfer dropped)
    * once shown, None when aborted. */
  private def doShow(plan: WavePlan, path: List[String], trace: String): Future[Option[Int]] = {
    clearShown()
    val adds = flatten(plan.groups).filter(_.nonEmpty)
    pipeline(command("clear") +: adds.map(g => command("add_items", "items" -> js.Array(g.map(_.trace): _*), "recursive" -> false)))
      .flatMap { replies =>
        val added = adds.zip(replies.tail).map { case (g, r) => (g, r.map(_.ids.asInstanceOf[js.Array[Int]].toSeq)) }
        phase {
          val short = added.collect { case (g, Some(got)) if got.size != g.size && got.nonEmpty => got }
          pipeline(short.map(got => command("get_item_info", "ids" -> js.Array(got: _*)))).flatMap { infos =>
            val names = short.zip(infos).toMap
            var dropped = 0
            added.foreach {
              case (g, Some(got)) =>
                val matched =
                  if (got.size == g.size) g.zip(got)
                  else {
                    val shownNames = names.get(got).flatten.fold(Seq.empty[String])(
                      _.results.asInstanceOf[js.Array[js.Dynamic]].toSeq.map(_.name.asInstanceOf[String]))
                    val byTrace = g.map(i => i.trace -> i).toMap
                    WaveLayout.matchAdded(g.map(_.trace), shownNames).zip(got).collect { case (Some(t), id) => (byTrace(t), id) }
                  }
                dropped += g.size - matched.size
                matched.foreach { case (i, id) => ids += i.trace -> id; traceOf += id -> i.trace }
              case (g, None) => dropped += g.size
            }
            val items   = adds.flatten
            val colours = items.flatMap(i => ids.get(i.trace).map(id => command("set_item_color", "id" -> id, "color" -> i.color)))
            phase(pipeline(colours).flatMap { _ =>
              phase(label(items).flatMap { _ =>
                phase(sequence(nodes(plan.groups, None)) { case (name, g) => group(name, g) }
                  .flatMap(_ => fold(plan.groups))
                  .map { _ => shown = Some((path, trace)); Some(dropped) })
              })
            })
          }
        }
      }
  }

  /** Names each added signal with its plan label, before any group or fold
    * (the visible index is then the add order). A multi-bit signal keeps its
    * width as Surfer shows it (`x [15:0]`); width 0 is unknown, never shown. */
  private def label(items: Seq[socle.wave.WaveItem]): Future[Unit] = itemList().flatMap { list =>
    def text(i: socle.wave.WaveItem) = if (i.width > 1) s"${i.label} [${i.width - 1}:0]" else i.label
    val named = items.flatMap(i => ids.get(i.trace).map(id => (list.indexOf(id), text(i)))).filter(_._1 >= 0)
    named.foreach { case (vidx, l) => inject(js.Dynamic.literal(ItemNameChange = js.Array[js.Any](vidx, l))) }
    if (named.isEmpty) Future.unit else itemList().map(_ => ()) // the barrier
  }

  /** Makes group `g` (shown as `name`) of the signals added under it. A port of
    * one ground signal is left as that signal. */
  private def group(name: String, g: WaveGroup): Future[Unit] = {
    val leaves = traces(g).flatMap(ids.get)
    val ground = g.children.isEmpty && g.items.size == 1 && g.items.head.label == g.name
    if (leaves.isEmpty || ground) Future.unit
    else for {
      before <- itemList()
      _ = inject(js.Dynamic.literal(GroupNew = js.Dynamic.literal(
        name = name, before = before.indexOf(leaves.head), items = js.Array(leaves: _*))))
      after <- itemList() // the barrier: the inject ran before this WCP command
    } yield after.find(id => !before.contains(id)).foreach { gid =>
      traces(g).foreach(t => groupsOf += t -> (groupsOf.getOrElse(t, Nil) :+ gid))
      groupIds += name -> gid
    }
  }

  /** Folds the groups the plan folds, once every group exists. */
  private def fold(groups: Seq[WaveGroup]): Future[Unit] = {
    val toFold = nodes(groups, None).collect { case (name, g) if g.folded => groupIds.get(name) }.flatten
    toFold.foreach { gid => inject(js.Dynamic.literal(GroupFold = gid)); folded += gid }
    groupIds = Map.empty
    if (toFold.isEmpty) Future.unit else itemList().map(_ => ())
  }

  private def doFocus(trace: String): Future[Unit] = ids.get(trace) match {
    case None =>
      lastFocus = Some((trace, -1, false)); focusCount += 1
      Future.unit
    case Some(id) =>
      val unfold = groupsOf.getOrElse(trace, Nil).filter(folded)
      unfold.foreach { gid => inject(js.Dynamic.literal(GroupUnfold = gid)); folded -= gid }
      for {
        _ <- if (unfold.isEmpty) Future.unit else itemList()
        r <- send(command("focus_item", "id" -> id))
        // focus_item does not scroll: ScrollToItem at its visible index. It reads
        // the rows of the last drawn frame, so a get_item_list (the index, and a
        // frame barrier after a fresh plan) comes first, another one after.
        list <- itemList()
        idx = list.indexOf(id)
        _ <- if (idx < 0) Future.unit else { inject(js.Dynamic.literal(ScrollToItem = idx)); lastScroll = Some(idx); itemList() }
      } yield { lastFocus = Some((trace, id, r.isDefined)); focusCount += 1 }
  }

  /** Adds `traces` not shown yet with one `add_items` (Surfer drops the paths
    * its trace lacks: a short reply is named as a plan's group is), below
    * `after` when shown, then focuses the first one added. */
  private def doAdd(traces: Seq[String], after: Option[String]): Future[Unit] = {
    val fresh = traces.filterNot(ids.contains)
    val below = after.flatMap(ids.get).fold(Future.unit)(id => send(command("focus_item", "id" -> id)).map(_ => ()))
    if (fresh.isEmpty) Future.unit
    else below.flatMap(_ => send(command("add_items", "items" -> js.Array(fresh: _*), "recursive" -> false))).flatMap {
      case None => Future.unit
      case Some(r) =>
        val got = r.ids.asInstanceOf[js.Array[Int]].toSeq
        val named: Future[Seq[(String, Int)]] =
          if (got.size == fresh.size) Future.successful(fresh.zip(got))
          else if (got.isEmpty) Future.successful(Nil)
          else send(command("get_item_info", "ids" -> js.Array(got: _*))).map { info =>
            val names = info.fold(Seq.empty[String])(_.results.asInstanceOf[js.Array[js.Dynamic]].toSeq.map(_.name.asInstanceOf[String]))
            WaveLayout.matchAdded(fresh, names).zip(got).collect { case (Some(t), id) => (t, id) }
          }
        named.flatMap { ps =>
          ps.foreach { case (t, id) => ids += t -> id; traceOf += id -> t }
          addCount += 1
          lastAdd = ps.map(_._1)
          ps.headOption.fold(Future.unit)(p => doFocus(p._1))
        }
    }
  }

  // ── Debug hook (window.__socleWave, for the smoke) ─────────────────────
  /** The pane's state: status, trace, the plan shown, the last focus sent. */
  def snapshot(): js.Object = js.Dynamic.literal(
    status = statusVar.now(), boots = boots, loadedTrace = loadedTrace,
    idle = !busy && pendingLoad.isEmpty && pendingPlan.isEmpty && pendingFocus.isEmpty && pendingAdds.isEmpty && queries.isEmpty,
    shown = shown.fold[js.Any](null) { case (p, t) => js.Dynamic.literal(path = js.JSON.stringify(js.Array(p: _*)), trace = t) },
    lastFocus = lastFocus.fold[js.Any](null) { case (t, id, ok) => js.Dynamic.literal(trace = t, id = id, ok = ok) },
    lastScroll = lastScroll.fold[js.Any](null)(i => js.Dynamic.literal(index = i)),
    focusCount = focusCount,
    addCount = addCount, lastAdd = js.Array(lastAdd: _*),
  )

  /** Surfer's displayed items in order (WCP `get_item_list` / `get_item_info`),
    * each with the trace path of the plan it was added for. */
  def items(): js.Promise[js.Array[js.Object]] = {
    val p = Promise[js.Array[js.Object]]()
    if (!booted) p.success(js.Array()) // no Surfer (yet): nothing shown
    else {
      queries.enqueue { () =>
        val f = for {
          list <- itemList()
          info <- if (list.isEmpty) Future.successful(None) else send(command("get_item_info", "ids" -> js.Array(list: _*)))
        } yield info.fold(js.Array[js.Object]()) { r =>
          r.results.asInstanceOf[js.Array[js.Dynamic]].map { i =>
            val id = i.id.asInstanceOf[Int]
            js.Dynamic.literal(id = id, name = i.name, `type` = i.selectDynamic("type"),
              trace = traceOf.get(id).fold[js.Any](null)(t => t)).asInstanceOf[js.Object]
          }
        }
        f.onComplete(p.tryComplete)
        f.map(_ => ())
      }
      pump()
    }
    import scala.scalajs.js.JSConverters._
    p.future.toJSPromise
  }
}

object SurferPane {
  val SurferJs   = "/surfer/surfer.js"
  val SurferWasm = "/surfer/surfer_bg.wasm"
  private val LoadTimeoutMs = 120000
  /** At most this many WCP commands in flight. */
  val Window = 32
  /** How long a WCP reply may take while the canvas is drawn. */
  val ReplyTimeoutMs = 60000
  private val TickMs = 1000

  def absolute(path: String): String = new dom.URL(path, dom.window.location.href).href

  /** A plan's groups as one list of items each, depth first, items before children. */
  private def flatten(groups: Seq[WaveGroup]): Seq[Seq[socle.wave.WaveItem]] =
    groups.flatMap(g => g.items +: flatten(g.children))

  /** Every group, preorder, with its shown name: a nested group is named after
    * its path (`io.in.bits`). */
  private def nodes(groups: Seq[WaveGroup], parent: Option[String]): Seq[(String, WaveGroup)] =
    groups.flatMap { g =>
      val name = parent.fold(g.name)(p => s"$p.${g.name}")
      (name, g) +: nodes(g.children, Some(name))
    }

  /** The trace paths under a group, in order. */
  private def traces(g: WaveGroup): Seq[String] = g.items.map(_.trace) ++ g.children.flatMap(traces)

  /** Runs `f` on each of `xs` in order, each after the previous one completed. */
  private def sequence[A](xs: Seq[A])(f: A => Future[Unit]): Future[Unit] =
    xs.foldLeft(Future.unit)((acc, x) => acc.flatMap(_ => f(x)))
}
