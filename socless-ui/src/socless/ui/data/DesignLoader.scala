package socless.ui.data

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.js.timers._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.util.{Success, Failure}
import socless.schema.Design

/** Design-loading service extracted from [[socless.ui.App]] (#219): owns the
  * design + source state, the live-poll daemon (#120), file drag-drop (#194),
  * the multi-top index/picker (#197), and transient toasts.
  *
  * App keeps the navigation/view state and injects, via the constructor, how to
  * reset navigation when a design lands: [[onFresh]] for a fresh load (reset to
  * the new top) and [[onLive]] for a live reload (keep the drill path when it
  * still resolves). The loader exposes its state as read-only [[Signal]]s the
  * view binds to. */
final class DesignLoader(
  topOf:   Design => String,
  onFresh: Design => Unit,
  onLive:  Design => Unit,
) {

  // ── State ──────────────────────────────────────────────────────────────────
  private val designVar        = Var[Option[Design]](None)
  // Where the active design came from (#194): "live" (fetched design.json),
  // "snapshot" (embedded), "file" (dropped .json), "fir" (dropped .fir),
  // "multi" (a non-primary top). None until the first design loads.
  private val sourceVar        = Var[Option[(String, String)]](None)
  private val errorVar         = Var[Option[String]](None)
  private val loadingVar       = Var(false)                          // a design fetch/parse is in flight
  // design.json answered 404 at load (a language server that has not
  // published yet): the initial load is retried until the first design.
  private val waitingVar       = Var(false)
  private val liveUpdatedAtVar = Var[Double](0d)                     // ms of the last live auto-reload; 0 = never
  private val dragActiveVar    = Var(false)                          // drop-zone overlay visibility
  private val toastVar         = Var[Option[(String, String)]](None) // (message, kind: info|ok|err)
  // Multi-top design picker (#197): (id, label, path) entries from designs.json.
  // Empty for a single-top build (no index) -> the picker is hidden.
  private val designsVar       = Var[List[(String, String, String)]](Nil)
  private val activeDesignVar  = Var[String]("design.json")          // path of the selected entry
  private var toastTimer       = Option.empty[SetTimeoutHandle]
  private var liveWatchStarted = false
  // Last-Modified of the live design currently shown. The poller compares the
  // server's stamp against this, so a change that lands between the initial
  // load and the first poll is still caught (no baseline race). None = unknown.
  private var liveBaseline     = Option.empty[String]

  // ── Public signals (read by App's view) ─────────────────────────────────────
  val designSig:        Signal[Option[Design]]                   = designVar.signal
  val sourceSig:        Signal[Option[(String, String)]]         = sourceVar.signal
  val errorSig:         Signal[Option[String]]                   = errorVar.signal
  val loadingSig:       Signal[Boolean]                          = loadingVar.signal
  val waitingSig:       Signal[Boolean]                          = waitingVar.signal
  val liveUpdatedAtSig: Signal[Double]                           = liveUpdatedAtVar.signal
  val dragActiveSig:    Signal[Boolean]                          = dragActiveVar.signal
  val toastSig:         Signal[Option[(String, String)]]         = toastVar.signal
  val designsSig:       Signal[List[(String, String, String)]]   = designsVar.signal
  val activeDesignSig:  Signal[String]                           = activeDesignVar.signal

  def currentDesign: Option[Design] = designVar.now()

  /** Raw JSON + top of the active design, for the snapshot/share button. */
  def snapshot(): Option[(String, String)] =
    designVar.now().map(d => (upickle.default.write(d), topOf(d)))

  /** Kick off the initial design load + multi-top index fetch (call from main). */
  def start(): Unit = { loadDesign(); loadDesignsIndex() }

  // ── Design loading ──────────────────────────────────────────────────────────
  private def msgOf(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  /** Install a freshly loaded design, resetting navigation to its top (via
    * [[onFresh]]). `source` is (kind, label) recording where it came from. */
  // Smoke test hook: every design load, fresh or live, with its time.
  private val loads = js.Array[js.Dynamic]()
  dom.window.asInstanceOf[js.Dynamic].updateDynamic("__soclessLoads")(loads)
  private def logLoad(kind: String): Unit = { loads.push(js.Dynamic.literal(kind = kind, t = dom.window.performance.now())); () }

  private def setDesign(d: Design, source: (String, String)): Unit = {
    logLoad("fresh")
    designVar.set(Some(d))
    onFresh(d)
    sourceVar.set(Some(source))
    errorVar.set(None)
    waitingVar.set(false)
  }

  /** Live reload (#120): swap in a re-converted design but keep the user's
    * drill path/expansion when it still resolves (via [[onLive]]), so
    * re-elaboration does not throw away navigation. Stamps the "updated Ns ago"
    * clock. */
  private def reloadLiveDesign(d: Design): Unit = {
    logLoad("live")
    designVar.set(Some(d))
    onLive(d)
    sourceVar.set(Some(("live", "live (design.json)")))
    liveUpdatedAtVar.set(js.Date.now())
    errorVar.set(None)
  }

  private def loadDesign(): Unit = {
    // A self-contained snapshot (the share button) inlines the design as an
    // embedded block; prefer it so the page opens from file:// with no fetch.
    val embedded = Option(dom.document.getElementById("socless-embedded-design"))
      .map(_.textContent).filter(_.trim.nonEmpty)
    embedded match {
      case Some(txt) =>
        try { val d = upickle.default.read[Design](txt); setDesign(d, ("snapshot", s"${topOf(d)} (snapshot)")) }
        catch { case e: Throwable => errorVar.set(Some(msgOf(e))) }
      case None => fetchInitialDesign()
    }
  }

  private object NoDesignYet extends RuntimeException("no design yet")

  /** The initial live load. A 404 means the server has no design yet (the
    * language server before its first successful run): show a calm waiting
    * state and retry every 1500 ms while the tab is visible, until the first
    * design lands or another source (a dropped file) takes over. */
  private def fetchInitialDesign(): Unit = {
    loadingVar.set(true)
    dom.fetch("design.json").flatMap { r =>
      if (r.ok) { liveBaseline = headerStamp(r); r.text() }
      else if (r.status == 404) Future.failed(NoDesignYet)
      else Future.failed(new RuntimeException(s"HTTP ${r.status}"))
    }.map(txt => upickle.default.read[Design](txt)).onComplete { res =>
      loadingVar.set(false)
      if (sourceVar.now().isDefined) waitingVar.set(false) // a dropped file took over
      else res match {
        case Success(d) =>
          waitingVar.set(false); setDesign(d, ("live", "live (design.json)")); startLiveWatch()
        case Failure(NoDesignYet) => waitingVar.set(true); retryInitialDesign()
        case Failure(e)           => waitingVar.set(false); errorVar.set(Some(msgOf(e)))
      }
    }
  }

  private def retryInitialDesign(): Unit = {
    setTimeout(1500d) {
      if (sourceVar.now().isDefined) waitingVar.set(false)
      else if (dom.document.visibilityState.asInstanceOf[String] == "visible") fetchInitialDesign()
      else retryInitialDesign()
    }
    ()
  }

  /** Best-effort fetch of the multi-top index (#197). Absent (404) or
    * unparseable -> no picker, which is the single-top / embedded case. */
  private def loadDesignsIndex(): Unit =
    dom.fetch("designs.json").flatMap(r => if (r.ok) r.text() else Future.failed(new RuntimeException("no index")))
      .map { txt =>
        ujson.read(txt).obj("designs").arr.iterator
          .map(e => (e.obj("id").str, e.obj("label").str, e.obj("path").str)).toList
      }
      .onComplete {
        case Success(list) if list.nonEmpty => designsVar.set(list)
        case _                              => () // no index -> single-top, picker stays hidden
      }

  /** Switch the active design in place (#197). The primary entry
    * (`design.json`) restores the live source + poller; other tops load
    * statically. Navigation resets to the new design's top via [[setDesign]]. */
  def selectDesign(path: String, label: String): Unit = {
    if (path == activeDesignVar.now()) return
    activeDesignVar.set(path)
    loadingVar.set(true)
    val primary = path == "design.json"
    dom.fetch(path).flatMap { r =>
      if (r.ok) { if (primary) liveBaseline = headerStamp(r); r.text() }
      else Future.failed(new RuntimeException(s"HTTP ${r.status}"))
    }.map(txt => upickle.default.read[Design](txt)).onComplete {
      case Success(d) =>
        loadingVar.set(false)
        if (primary) { setDesign(d, ("live", "live (design.json)")); startLiveWatch() }
        else setDesign(d, ("multi", label))
      case Failure(e) => loadingVar.set(false); errorVar.set(Some(msgOf(e)))
    }
  }

  /** The freshness stamp of a response: Last-Modified, else ETag, else None. */
  private def headerStamp(r: dom.Response): Option[String] =
    Option(r.headers.get("Last-Modified")).filter(_.nonEmpty)
      .orElse(Option(r.headers.get("ETag")).filter(_.nonEmpty))

  // ── Design input: drag-drop a .json / .fir (#194) ──────────────────────────
  private def hasFiles(e: dom.DragEvent): Boolean = {
    val dt = e.dataTransfer
    dt != null && dt.types != null && dt.types.exists(_ == "Files")
  }

  /** Window-level drag-drop: a dropped `.json` loads directly; a dropped `.fir`
    * is POSTed to the standalone server's `/convert`. Ref-counts enter/leave so
    * the overlay does not flicker across nested children (mirrors the JS UI). */
  def installDragDrop(): Unit = {
    var dragDepth = 0
    dom.window.addEventListener("dragenter", (e: dom.DragEvent) =>
      if (hasFiles(e)) { e.preventDefault(); dragDepth += 1; dragActiveVar.set(true) })
    dom.window.addEventListener("dragover", (e: dom.DragEvent) =>
      if (hasFiles(e)) { e.preventDefault(); if (e.dataTransfer != null) e.dataTransfer.dropEffect = dom.DataTransferDropEffectKind.copy })
    dom.window.addEventListener("dragleave", (e: dom.DragEvent) =>
      if (hasFiles(e)) { dragDepth = math.max(0, dragDepth - 1); if (dragDepth == 0) dragActiveVar.set(false) })
    dom.window.addEventListener("drop", (e: dom.DragEvent) => {
      val dt = e.dataTransfer
      if (dt != null && dt.files.length > 0) {
        e.preventDefault(); dragDepth = 0; dragActiveVar.set(false)
        val f    = dt.files(0)
        val name = if (f.name.nonEmpty) f.name else "file"
        if (name.toLowerCase.endsWith(".fir")) loadFirFromFile(f, name)
        else if (name.toLowerCase.endsWith(".json")) loadJsonFromFile(f, name)
        else toast(s"expected a .json or .fir file, got $name", "err")
      }
    })
  }

  private def loadJsonFromFile(f: dom.File, name: String): Unit =
    f.text().onComplete {
      case Success(text) =>
        try setDesign(upickle.default.read[Design](text), ("file", name))
        catch { case e: Throwable => toast(s"couldn't read $name: ${msgOf(e)}", "err") }
      case Failure(e) => toast(s"couldn't read $name: ${msgOf(e)}", "err")
    }

  /** Convert a dropped CHIRRTL `.fir` via `POST /convert` (served by the
    * standalone server). A network failure means no server is running. */
  private def loadFirFromFile(f: dom.File, name: String): Unit = {
    toast(s"converting $name…", "info")
    f.text().onComplete {
      case Failure(e) => toast(s"couldn't read $name: ${msgOf(e)}", "err")
      case Success(text) =>
        val init = js.Dynamic.literal(
          method  = "POST",
          headers = js.Dynamic.literal("Content-Type" -> "text/plain; charset=utf-8"),
          body    = text,
        ).asInstanceOf[dom.RequestInit]
        dom.fetch("/convert", init).onComplete {
          case Failure(_) =>
            toast("POST /convert failed: no SoCLess server running. Start one with: mill example.soclessServe", "err")
          case Success(resp) if resp.status == 200 =>
            resp.text().onComplete {
              case Success(json) =>
                try setDesign(upickle.default.read[Design](json), ("fir", name))
                catch { case e: Throwable => toast(s"couldn't load $name: ${msgOf(e)}", "err") }
              case Failure(e) => toast(s"couldn't load $name: ${msgOf(e)}", "err")
            }
          case Success(resp) =>
            resp.text().recover { case _ => resp.statusText }.foreach { m =>
              toast(s"conversion failed (${resp.status}): ${m.take(120)}", "err")
            }
        }
    }
  }

  private def toast(msg: String, kind: String): Unit = {
    toastVar.set(Some((msg, kind)))
    toastTimer.foreach(clearTimeout)
    toastTimer = Some(setTimeout(if (kind == "err") 6000d else 3000d) { toastVar.set(None) })
  }

  /** Live watch (#120): while showing the live design and the tab is visible,
    * poll design.json's Last-Modified and reload in place when it changes, so
    * re-running elaboration (`serve --watch`, `soclessUI -w`) refreshes the
    * schematic with no manual reload. Started once, after the first live load. */
  private def startLiveWatch(): Unit = {
    if (liveWatchStarted) return
    liveWatchStarted = true
    def poll(): Unit = {
      if (!sourceVar.now().exists(_._1 == "live")) return
      if (dom.document.visibilityState.asInstanceOf[String] != "visible") return
      val init = js.Dynamic.literal(method = "HEAD", cache = "no-store").asInstanceOf[dom.RequestInit]
      dom.fetch("design.json", init).foreach { r =>
        if (r.ok) headerStamp(r).foreach { stamp =>
          liveBaseline match {
            case None              => liveBaseline = Some(stamp)     // no stamp from load; baseline now
            case Some(s) if s != stamp =>
              liveBaseline = Some(stamp)                             // changed: reload in place
              dom.fetch("design.json").flatMap(rr => rr.text())
                .map(t => upickle.default.read[Design](t))
                .foreach(d => reloadLiveDesign(d))
            case _ => ()                                            // unchanged
          }
        }
      }
    }
    setInterval(1500d) { poll() }
  }
}
