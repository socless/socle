package socle.lsp

import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.net.{InetAddress, ServerSocket, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal
import socle.wave.{SurferWire, WaveGroup, WaveLayout, WavePlan}

/** Where a [[WavePlan]] and a focus go: the external Surfer ([[WcpSession]]). */
trait WaveTarget {
  def apply(plan: WavePlan): Unit
  def focus(trace: String): Unit
}

/** A WCP session with one external Surfer (the Waveform Control Protocol,
  * libsurfer/src/wcp/ and surfer-wcp/src/proto.rs of Surfer 0.7.0-dev).
  *
  * The language server listens on 127.0.0.1:0 and Surfer, started with
  * `--wcp-initiate <port>`, connects to it once: Surfer is the TCP client but
  * plays the WCP server. Frames are JSON objects, each followed by a null byte.
  * The session sends `{"type":"greeting","version":"0","commands":["waveforms_loaded",
  * "goto_declaration","add_drivers","add_loads"]}` first (Surfer rejects every
  * command before it, and shows its right-click actions only for the events
  * asked for), waits for Surfer's greeting,
  * then for the trace to load: `get_item_list` errors ("No waveform loaded")
  * until it has, and `waveforms_loaded` only comes when loading ends after the
  * greeting, so both count.
  *
  * Commands are pipelined ([[WcpSession.Window]] in flight, replies matched
  * first in, first out, events skipped) and run on the session's own thread,
  * so one plan's exchange never interleaves with the next. [[apply]] and
  * [[focus]] only leave work for that thread and return: a new plan replaces a
  * plan or focus not started yet, so only the latest one runs, and it aborts a
  * running plan at the end of its current phase.
  *
  * A plan is shown flat (WCP has no group command): `clear`, one `add_items`
  * per group, depth first with a group's items before its children's, then
  * `set_item_color` for each returned id. Surfer drops the paths its trace
  * lacks silently: a group added short has its ids named with `get_item_info`
  * and matched to its paths in order ([[WaveLayout.matchAdded]]), the dropped
  * ones logged. [[focus]] sends `focus_item` with the id of a path of the
  * latest plan. WCP cannot scroll Surfer's list: a focused signal below the
  * visible rows stays out of view.
  *
  * A right-click action (`goto_declaration`, `add_drivers`, `add_loads`) goes
  * to `onEvent(kind, variable)`, on the session's thread: read while a reply
  * is awaited, and every [[WcpSession.EventPollMs]] while idle. [[add]] adds
  * signals after those shown (one `add_items`, the paths shown skipped); a
  * new plan drops the adds not run yet.
  *
  * A slow reply is waited for (Surfer answers from its UI loop, which stalls
  * while its window is hidden); the next plan simply waits behind it. When
  * Surfer never connects, exits or closes the socket, or the greeting and load
  * miss the load deadline, the session is dead ([[alive]] false) and every
  * later call does nothing; nothing here throws after [[WcpSession.start]].
  */
final class WcpSession private (
    server: ServerSocket,
    process: Option[Process],
    log: String => Unit,
    acceptTimeoutMs: Long,
    replyTimeoutMs: Int,
    loadTimeoutMs: Long,
    onEarlyExit: () => Unit,
    onEvent: (String, String) => Unit,
) extends WaveTarget {
  import WcpSession._

  /** The port Surfer connects to. */
  val port: Int = server.getLocalPort

  private val lock         = new Object
  private var pendingPlan  = Option.empty[WavePlan] // guarded by lock
  private var pendingFocus = Option.empty[String]   // guarded by lock
  private var pendingAdds  = Vector.empty[String]   // guarded by lock
  private var pendingAfter = Option.empty[String]   // guarded by lock
  private var closed       = false                  // guarded by lock
  @volatile private var dead = false
  @volatile private var socket = Option.empty[Socket]
  private var ids = Map.empty[String, Int] // trace path -> Surfer id of the latest plan; worker thread only

  private val worker = new Thread(() => run(), "socle-wcp")
  worker.setDaemon(true)
  worker.start()

  /** False once Surfer is gone (or never came) or the session is closed. */
  def alive: Boolean = !dead && !lock.synchronized(closed)

  def apply(plan: WavePlan): Unit = lock.synchronized {
    pendingPlan = Some(plan)
    pendingFocus = None
    pendingAdds = Vector.empty
    lock.notifyAll()
  }

  /** Adds `traces` not shown, below the signal `after` when it is shown. */
  def add(traces: Seq[String], after: Option[String] = None): Unit = lock.synchronized {
    pendingAdds = (pendingAdds ++ traces).distinct
    pendingAfter = after
    lock.notifyAll()
  }

  def focus(trace: String): Unit = lock.synchronized {
    pendingFocus = Some(trace)
    lock.notifyAll()
  }

  /** Ends the session and kills the Surfer it started. */
  def close(): Unit = {
    lock.synchronized { closed = true; lock.notifyAll() }
    quietly(server.close())
    socket.foreach(s => quietly(s.close()))
    process.foreach(p => ProcessTree.kill(p.toHandle))
  }

  private def isClosed = lock.synchronized(closed)

  private def run(): Unit = {
    try {
      accept().foreach { s =>
        socket = Some(s)
        if (!isClosed) { // else closed while accepting: the finally closes it
          s.setSoTimeout(replyTimeoutMs)
          val io = new Frames(new BufferedInputStream(s.getInputStream), new BufferedOutputStream(s.getOutputStream), event)
          if (handshake(io)) serve(io)
        }
      }
    } catch {
      case NonFatal(e) => if (!isClosed) log(s"wcp: Surfer session ended: $e")
    } finally {
      dead = true
      quietly(server.close())
      socket.foreach(s => quietly(s.close()))
    }
  }

  /** Waits for Surfer's connection; None when it exits first, the session is
    * closed or [[acceptTimeoutMs]] passes. */
  private def accept(): Option[Socket] = {
    val end = System.currentTimeMillis() + acceptTimeoutMs
    server.setSoTimeout(200)
    var got = Option.empty[Socket]
    while (got.isEmpty && !isClosed && System.currentTimeMillis() < end && process.forall(_.isAlive)) {
      try got = Some(server.accept())
      catch { case _: SocketTimeoutException => () }
    }
    if (got.isEmpty && !isClosed) {
      if (process.exists(!_.isAlive)) {
        log("wcp: Surfer exited before connecting (it may be too old for --wcp-initiate)")
        try onEarlyExit() catch { case NonFatal(e) => log(s"wcp: early exit handler failed: $e") }
      } else log("wcp: Surfer did not connect; the waves will not follow the panel")
    }
    got
  }

  /** Greeting both ways, then the trace loaded. */
  private def handshake(io: Frames): Boolean = {
    io.send(ujson.Obj("type" -> "greeting", "version" -> "0",
      "commands" -> ujson.Arr.from(("waveforms_loaded" +: SurferWire.Event.Kinds).map(ujson.Str(_)))))
    // Surfer answers from its UI loop, which may only run once its window is
    // shown: its greeting gets the load deadline, not one reply's timeout
    val end = System.currentTimeMillis() + loadTimeoutMs
    val greeting = awaitReply(io, Some(end))
    if (greeting.forall(_("type").str != "greeting")) {
      log(s"wcp: Surfer refused the greeting: ${greeting.map(ujson.write(_)).getOrElse("closed")}")
      false
    } else {
      var loaded = false
      while (!loaded && !isClosed && System.currentTimeMillis() < end) {
        io.send(command("get_item_list"))
        awaitReply(io, Some(end)) match {
          case None => throw new IOException("closed")
          case Some(r) if r("type").str == "response" => loaded = true
          case Some(_) => loaded = io.loaded; if (!loaded) Thread.sleep(LoadProbeMs)
        }
      }
      if (!loaded && !isClosed) log("wcp: the trace did not load in Surfer in time")
      loaded
    }
  }

  /** A right-click action from Surfer, handed to `onEvent`; nothing it throws ends the session. */
  private def event(f: ujson.Obj): Unit =
    (f.value.get("event").map(_.str), f.value.get("variable").map(_.str)) match {
      case (Some(k), Some(v)) if SurferWire.Event.Kinds.contains(k) =>
        try onEvent(k, v) catch { case NonFatal(e) => log(s"wcp: $k on $v failed: $e") }
      case _ => ()
    }

  /** Runs the pending plan, focus and adds until the session closes or dies.
    * While idle, Surfer's events are read every [[EventPollMs]]. */
  private def serve(io: Frames): Unit =
    while (!isClosed) {
      io.drain()
      val next = lock.synchronized {
        if (!closed && pendingPlan.isEmpty && pendingFocus.isEmpty && pendingAdds.isEmpty) lock.wait(EventPollMs)
        val n = (pendingPlan, pendingFocus, pendingAdds, pendingAfter)
        pendingPlan = None
        pendingFocus = None
        pendingAdds = Vector.empty
        n
      }
      // a plan aborted for a newer one drops the focus that came with it
      if (!isClosed && next._1.forall(show(io, _))) {
        next._2.foreach { t =>
          ids.get(t) match {
            case Some(id) => step(io, command("focus_item", "id" -> ujson.Num(id)))
            case None => log(s"wcp: no focus on $t: it has no Surfer id (not in the plan shown, or not in the trace)")
          }
        }
        if (next._3.nonEmpty) addItems(io, next._3, next._4)
      }
    }

  /** Adds the paths of `traces` not shown yet with one `add_items`, names the
    * ids of a short reply as [[show]] does, then focuses the first one added.
    * Surfer inserts after its focused item: `after`, when shown, is focused first. */
  private def addItems(io: Frames, traces: Seq[String], after: Option[String]): Unit = {
    val fresh = traces.filterNot(ids.contains)
    if (fresh.nonEmpty) after.flatMap(ids.get).foreach(id => step(io, command("focus_item", "id" -> ujson.Num(id))))
    if (fresh.nonEmpty) step(io, command("add_items", "items" -> ujson.Arr.from(fresh), "recursive" -> ujson.False)).foreach { r =>
      val got = r.value.get("ids").fold(Seq.empty[Int])(_.arr.map(_.num.toInt).toSeq)
      val named =
        if (got.size == fresh.size) fresh.zip(got)
        else if (got.isEmpty) Nil
        else {
          val names = step(io, command("get_item_info", "ids" -> ujson.Arr.from(got.map(ujson.Num(_)))))
            .flatMap(_.value.get("results")).fold(Seq.empty[String])(_.arr.map(_("name").str).toSeq)
          WaveLayout.matchAdded(fresh, names).zip(got).collect { case (Some(t), id) => (t, id) }
        }
      ids ++= named
      if (named.size < fresh.size) log(s"wcp: ${fresh.size - named.size} signal(s) to add not in the trace (Surfer did not add them)")
      named.headOption.foreach { case (_, id) => step(io, command("focus_item", "id" -> ujson.Num(id))) }
    }
  }

  /** Whether a newer plan waits (or the session closed): the running one stops
    * at the end of its phase. */
  private def superseded: Boolean = lock.synchronized(closed || pendingPlan.isDefined)

  /** Shows `plan` in three pipelined phases: `clear` and the adds, then the
    * names of the ids of groups Surfer added short (a path it lacks), then the
    * colours. False when a newer plan aborted it between phases. */
  private def show(io: Frames, plan: WavePlan): Boolean = {
    ids = Map.empty
    val groups  = flatten(plan.groups).filter(_.nonEmpty)
    val replies = pipeline(io, command("clear") +: groups.map { g =>
      command("add_items", "items" -> ujson.Arr.from(g.map(_.trace)), "recursive" -> ujson.False)
    }).tail
    val added = groups.zip(replies).map { case (g, r) => (g, r.flatMap(_.value.get("ids")).map(_.arr.map(_.num.toInt).toSeq)) }
    if (superseded) return false
    // the groups Surfer added short: name their ids
    val short = added.collect { case (g, Some(got)) if got.size != g.size && got.nonEmpty => (g, got) }
    val infos = pipeline(io, short.map { case (_, got) => command("get_item_info", "ids" -> ujson.Arr.from(got.map(ujson.Num(_)))) })
    val infoOf = short.map(_._1).zip(infos).toMap
    var dropped = 0
    val shown = added.flatMap {
      case (g, Some(got)) if got.size == g.size => g.zip(got)
      case (g, Some(got)) =>
        val names = infoOf.get(g).flatten.flatMap(_.value.get("results")).fold(Seq.empty[String])(_.arr.map(_("name").str).toSeq)
        val byTrace = g.map(i => i.trace -> i).toMap
        val matched = WaveLayout.matchAdded(g.map(_.trace), names).zip(got).collect { case (Some(t), id) => (byTrace(t), id) }
        dropped += g.size - matched.size
        matched
      case (g, None) => dropped += g.size; Nil
    }
    ids = shown.map { case (i, id) => i.trace -> id }.toMap
    if (dropped > 0) log(s"wcp: $dropped signal(s) of the plan not in the trace (Surfer did not add them)")
    if (superseded) return false
    pipeline(io, shown.map { case (item, id) => command("set_item_color", "id" -> ujson.Num(id), "color" -> ujson.Str(item.color)) })
    true
  }

  /** The next reply, waiting through read timeouts while the session is open
    * (Surfer answers from its UI loop, which stalls while its window is not
    * drawn: minimised, or on another workspace) and, when given, `deadline`
    * is not passed. The read timeout only bounds each wait; [[close]] closes
    * the socket, which ends it at once. None at EOF. */
  private def awaitReply(io: Frames, deadline: Option[Long]): Option[ujson.Obj] = {
    var r = Option.empty[Option[ujson.Obj]]
    var slow = false
    while (r.isEmpty)
      try r = Some(io.reply())
      catch {
        case _: SocketTimeoutException if !isClosed && deadline.forall(System.currentTimeMillis() < _) =>
          if (!slow) log("wcp: Surfer is slow to answer (its window may be hidden); still waiting")
          slow = true
      }
    r.get
  }

  /** One command and its reply: the `response`, or None for an `error` (logged).
    * Throws when Surfer is gone, which ends the session. */
  private def step(io: Frames, cmd: ujson.Obj): Option[ujson.Obj] = pipeline(io, Seq(cmd)).head

  /** `cmds` and their replies, in order: each `response`, or None for an
    * `error` (logged). At most [[Window]] commands are in flight; each reply
    * read lets the next command go. Surfer drains its whole WCP queue each
    * frame and answers every command exactly once, in order, so replies match
    * commands first in, first out. The window stays well under Surfer's
    * channels (bounded at 100, filled by a UI thread that blocks on them), and
    * replies are read as they come, so neither side's writes fill up. Every
    * reply is read before it returns. Throws when Surfer is gone. */
  private def pipeline(io: Frames, cmds: Seq[ujson.Obj]): Seq[Option[ujson.Obj]] = {
    val out      = Vector.newBuilder[Option[ujson.Obj]]
    val inFlight = scala.collection.mutable.Queue.empty[ujson.Obj]
    def receive(): Unit = {
      val cmd = inFlight.dequeue()
      out += (awaitReply(io, None) match {
        case None => throw new IOException("Surfer closed the WCP connection")
        case Some(r) if r("type").str == "response" => Some(r)
        case Some(r) => log(s"wcp: ${cmd("command").str}: ${ujson.write(r)}"); None
      })
    }
    cmds.foreach { c =>
      if (inFlight.size >= Window) receive()
      io.send(c)
      inFlight.enqueue(c)
    }
    while (inFlight.nonEmpty) receive()
    out.result()
  }
}

object WcpSession {
  val DefaultAcceptTimeoutMs = 60L * 1000
  val DefaultReplyTimeoutMs  = 30 * 1000
  val DefaultLoadTimeoutMs   = 5L * 60 * 1000
  /** At most this many commands in flight. */
  val Window = 32
  /** How often an idle session reads Surfer's events. */
  val EventPollMs = 50L
  private val LoadProbeMs    = 100L
  private val MaxFrame       = 16 * 1024 * 1024

  /** Listens on 127.0.0.1:0 and starts `<command> --wcp-initiate <port> <trace>`
    * detached (empty stdin, output to `logFile` or discarded, never our
    * stdout). Throws when the command cannot be started. `onEarlyExit` runs
    * once, on the session's thread, when that process exits before connecting
    * (a Surfer without `--wcp-initiate`), unless the session was closed. */
  def start(command: String, trace: os.Path, logFile: Option[os.Path] = None,
      log: String => Unit = m => Console.err.println(s"[socle-lsp] $m"), onEarlyExit: () => Unit = () => (),
      onEvent: (String, String) => Unit = (_, _) => ()): WcpSession = {
    val server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val pb = new ProcessBuilder(command, "--wcp-initiate", server.getLocalPort.toString, trace.toString)
      .directory(trace.toNIO.getParent.toFile).redirectErrorStream(true)
    logFile.fold(pb.redirectOutput(ProcessBuilder.Redirect.DISCARD))(f => pb.redirectOutput(f.toIO))
    if (preferX11(sys.env)) pb.environment().remove("WAYLAND_DISPLAY")
    val p =
      try pb.start()
      catch { case NonFatal(e) => quietly(server.close()); throw e }
    p.getOutputStream.close()
    log(s"wcp: started $command --wcp-initiate ${server.getLocalPort} $trace")
    new WcpSession(server, Some(p), log, DefaultAcceptTimeoutMs, DefaultReplyTimeoutMs, DefaultLoadTimeoutMs, onEarlyExit, onEvent)
  }

  /** Start Surfer under XWayland when both displays exist: a native-Wayland
    * Surfer 0.7.0 connects but never answers WCP (seen on Hyprland, even with
    * its window visible), while under XWayland it answers at once. */
  private[lsp] def preferX11(env: Map[String, String]): Boolean =
    env.get("WAYLAND_DISPLAY").exists(_.nonEmpty) && env.get("DISPLAY").exists(_.nonEmpty)

  /** A session with no process: the test's fake Surfer connects to [[WcpSession.port]]. */
  private[lsp] def listen(log: String => Unit, acceptTimeoutMs: Long = 10000, replyTimeoutMs: Int = 5000,
      loadTimeoutMs: Long = 10000, onEvent: (String, String) => Unit = (_, _) => ()): WcpSession =
    new WcpSession(new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")), None, log,
      acceptTimeoutMs, replyTimeoutMs, loadTimeoutMs, () => (), onEvent)

  private def command(name: String, fields: (String, ujson.Value)*): ujson.Obj =
    ujson.Obj.from(Seq("type" -> ujson.Str("command"), "command" -> ujson.Str(name)) ++ fields)

  /** A plan's groups as one list of items each, depth first, items before children. */
  private def flatten(groups: Seq[WaveGroup]): Seq[Seq[socle.wave.WaveItem]] =
    groups.flatMap(g => g.items +: flatten(g.children))

  private def quietly(f: => Unit): Unit = try f catch { case NonFatal(_) => () }

  /** Null-delimited JSON frames over the socket; events other than
    * `waveforms_loaded` go to `onEvent`. */
  private final class Frames(in: InputStream, out: OutputStream, onEvent: ujson.Obj => Unit) {
    /** Whether a `waveforms_loaded` event went by. */
    var loaded = false

    def send(v: ujson.Value): Unit = {
      out.write(ujson.write(v).getBytes(UTF_8))
      out.write(0)
      out.flush()
    }

    /** The next `response`, `error` or `greeting`, events skipped; None at EOF. */
    def reply(): Option[ujson.Obj] = {
      var r = Option.empty[ujson.Obj]
      var eof = false
      while (r.isEmpty && !eof) frame() match {
        case None => eof = true
        case Some(f) if f.value.get("type").exists(_.str == "event") => seen(f)
        case Some(f) => r = Some(f)
      }
      r
    }

    private def seen(event: ujson.Obj): Unit =
      if (event.value.get("event").exists(_.str == "waveforms_loaded")) loaded = true else onEvent(event)

    /** Reads the frames already arrived while no command is in flight: its
      * events (a stray reply, which nothing awaits, is dropped). Throws at
      * EOF, which ends the session; a frame cut by the read timeout is kept. */
    def drain(): Unit =
      try while (in.available() > 0) frame() match {
        case None => throw new IOException("Surfer closed the WCP connection")
        case Some(f) if f.value.get("type").exists(_.str == "event") => seen(f)
        case Some(_) => ()
      } catch { case _: SocketTimeoutException => () }

    // the frame read so far: kept across a read timeout
    private val buf = new ByteArrayOutputStream

    /** The next non-empty frame; None at EOF. */
    private def frame(): Option[ujson.Obj] = {
      var b = in.read()
      while (b == 0 && buf.size == 0) b = in.read() // empty frames are skipped
      while (b > 0) {
        if (buf.size >= MaxFrame) throw new IOException("WCP frame too large")
        buf.write(b)
        b = in.read()
      }
      if (b < 0) None
      else try Some(ujson.read(new String(buf.toByteArray, UTF_8)).obj) finally buf.reset()
    }
  }
}
