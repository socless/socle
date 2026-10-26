package socless.lsp

import java.io.ByteArrayOutputStream
import java.net.{Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** A fake Surfer for the WCP tests: connects to `port` as `surfer --wcp-initiate
  * <port>` does, records every frame it gets and answers as Surfer does
  * (libsurfer/src/wcp/wcp_handler.rs): its greeting, `add_items` with fresh ids
  * (one per item, from 1), `get_item_list` with the ids shown, `get_item_info`
  * with each id's name (the path's leaf, plus ` [7:0]` as Surfer's variable
  * indices show it), `ack` otherwise.
  * The first `loadingProbes` `get_item_list` get Surfer's "No waveform loaded"
  * error; `delayMs` slows every reply, `greetingDelayMs` the greeting (a window not shown yet)
  * and `slow` the first reply to that command by its delay (a window stalled once).
  * `burst` holds every reply until no frame came for 50 ms, then answers them
  * all at once in order (Surfer drains its whole WCP queue each frame); with
  * it, [[maxOutstanding]] is how many commands the session had in flight.
  * Error paths: `drop` paths are left out of `add_items` (Surfer drops missing
  * paths silently), and `set_item_color` on id `colorError` gets an error. */
final class FakeSurfer(port: Int, loadingProbes: Int = 0, delayMs: Long = 0, greetingDelayMs: Long = 0,
    slow: Option[(String, Long)] = None, drop: Set[String] = Set.empty, colorError: Option[Int] = None,
    burst: Boolean = false) {
  private val sock = new Socket("127.0.0.1", port)
  val received = new ConcurrentLinkedQueue[ujson.Obj]
  @volatile private var nextId = 1
  @volatile private var shown  = Vector.empty[Int]
  @volatile private var names  = Map.empty[Int, String]
  @volatile private var probes = 0
  @volatile private var stalled = false
  @volatile private var outstanding = 0
  /** The most frames read and not answered yet at any time. */
  @volatile var maxOutstanding = 0

  private val reader = new Thread(() => loop(), "fake-surfer")
  reader.setDaemon(true)
  reader.start()

  /** The commands received (`clear`, `add_items`, ...), the greeting as `greeting`. */
  def commands: Seq[String] = frames.map(f => f.value.get("command").map(_.str).getOrElse(f("type").str))
  def frames: Seq[ujson.Obj] = received.asScala.toSeq

  def close(): Unit = sock.close()

  private def send(v: ujson.Value): Unit = {
    if (delayMs > 0) Thread.sleep(delayMs)
    val out = sock.getOutputStream
    out.write(ujson.write(v).getBytes(UTF_8))
    out.write(0)
    out.flush()
    outstanding -= 1
  }

  private def reply(f: ujson.Obj): Unit = f("type").str match {
    case "greeting" => Thread.sleep(greetingDelayMs); send(ujson.Obj("type" -> "greeting", "version" -> "0", "commands" -> ujson.Arr("add_items", "clear")))
    case _ =>
      slow.filter { case (c, _) => c == f("command").str && !stalled }.foreach { case (_, ms) => stalled = true; Thread.sleep(ms) }
      f("command").str match {
      case "add_items" =>
        val added = f("items").arr.map(_.str).filterNot(drop).map { p => val i = nextId; nextId += 1; names += i -> p; i }.toVector
        shown ++= added
        send(ujson.Obj("type" -> "response", "command" -> "add_items", "ids" -> ujson.Arr.from(added)))
      case "get_item_list" if probes < loadingProbes =>
        probes += 1
        send(ujson.Obj("type" -> "error", "error" -> "No waveform loaded", "arguments" -> ujson.Arr(),
          "message" -> "No waveform loaded"))
      case "get_item_list" =>
        send(ujson.Obj("type" -> "response", "command" -> "get_item_list", "ids" -> ujson.Arr.from(shown)))
      case "get_item_info" =>
        val results = f("ids").arr.map(_.num.toInt).map { id =>
          ujson.Obj("name" -> (names(id).split('.').last + " [7:0]"), "type" -> "Variable", "id" -> id)
        }
        send(ujson.Obj("type" -> "response", "command" -> "get_item_info", "results" -> ujson.Arr.from(results)))
      case "clear" => shown = Vector.empty; send(ujson.Obj("type" -> "response", "command" -> "ack"))
      case "set_item_color" if colorError.contains(f("id").num.toInt) =>
        send(ujson.Obj("type" -> "error", "error" -> "set_item_color", "arguments" -> ujson.Arr(),
          "message" -> s"Item ${f("id").num.toInt} not found"))
      case _ => send(ujson.Obj("type" -> "response", "command" -> "ack"))
      }
  }

  private def loop(): Unit =
    try {
      if (burst) sock.setSoTimeout(50)
      val queued = mutable.Queue.empty[ujson.Obj]
      val in  = sock.getInputStream
      val buf = new ByteArrayOutputStream
      var eof = false
      while (!eof) {
        val b = try in.read() catch { case _: SocketTimeoutException => while (queued.nonEmpty) reply(queued.dequeue()); -2 }
        if (b == -1) eof = true
        else if (b == 0) {
          val f = ujson.read(new String(buf.toByteArray, UTF_8)).obj
          buf.reset()
          received.add(f)
          outstanding += 1
          maxOutstanding = math.max(maxOutstanding, outstanding)
          if (burst) queued.enqueue(f) else reply(f)
        } else if (b > 0) buf.write(b)
      }
    } catch { case _: Exception => () }
}
