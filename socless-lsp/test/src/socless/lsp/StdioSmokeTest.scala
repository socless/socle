package socless.lsp

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import utest._

/** The packaged server as an editor runs it: `java -jar <assembly>` speaking
  * raw JSON-RPC over stdin/stdout. stdout must carry nothing but well-formed
  * frames, even when something prints after startup (the
  * `SOCLESS_LSP_STRAY_OUTPUT` hook in Main): those bytes belong on stderr. */
object StdioSmokeTest extends TestSuite {
  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private val root    = os.Path(prop("socless.root"))
  private val jar     = os.Path(prop("lsp.jar"))
  private val javaBin = (os.Path(sys.props("java.home")) / "bin" / "java").toString
  private val gcdSpec = root / "example" / "test" / "src" / "gcd" / "GcdSpec.scala"
  private def uri(p: os.Path) = p.toNIO.toUri.toString

  /** One running server; stdout is parsed strictly as LSP frames on a reader thread. */
  final class Server(env: Map[String, String] = Map.empty) {
    private val pb = new ProcessBuilder(javaBin, "-jar", jar.toString)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val proc = pb.start()
    val frames = new LinkedBlockingQueue[ujson.Value]
    @volatile var protocolError = Option.empty[String]
    private val stderrBuf = new ByteArrayOutputStream
    def stderr: String = stderrBuf.synchronized(stderrBuf.toString(UTF_8))

    private def daemon(name: String)(body: => Unit): Thread = {
      val t = new Thread(() => body, name); t.setDaemon(true); t.start(); t
    }
    private val reader = daemon("stdout-frames") {
      try readFrames(proc.getInputStream)
      catch { case e: Throwable => protocolError = Some(s"$e") }
    }
    private val drain = daemon("stderr-drain") {
      val in  = proc.getErrorStream
      val buf = new Array[Byte](4096)
      var n   = in.read(buf)
      while (n >= 0) { stderrBuf.synchronized(stderrBuf.write(buf, 0, n)); n = in.read(buf) }
    }

    /** Header lines (`Content-Length` required, nothing but known headers), a blank line, a JSON body; until EOF. */
    private def readFrames(in: InputStream): Unit = {
      def line(): Option[String] = {
        val b = new ByteArrayOutputStream
        var c = in.read()
        if (c < 0) return None
        while (c >= 0 && c != '\n') { b.write(c); c = in.read() }
        val s = b.toString(UTF_8)
        if (!s.endsWith("\r")) sys.error(s"not a CRLF header line on stdout: ${s.take(200)}")
        Some(s.stripSuffix("\r"))
      }
      var more = true
      while (more) line() match {
        case None => more = false
        case Some(first) =>
          var headers = Map.empty[String, String]
          var h = first
          while (h.nonEmpty) {
            val (k, v) = h.span(_ != ':')
            if (v.isEmpty || !Set("content-length", "content-type")(k.toLowerCase))
              sys.error(s"not an LSP header on stdout: ${h.take(200)}")
            headers += k.toLowerCase -> v.drop(1).trim
            h = line().getOrElse(sys.error("EOF inside headers"))
          }
          val len  = headers.getOrElse("content-length", sys.error("frame without Content-Length")).toInt
          val body = in.readNBytes(len)
          if (body.length != len) sys.error("EOF inside a frame body")
          val json = ujson.read(new String(body, UTF_8))
          if (json.obj.get("jsonrpc").flatMap(_.strOpt) != Some("2.0"))
            sys.error(s"not a JSON-RPC 2.0 message on stdout: ${ujson.write(json).take(200)}")
          frames.put(json)
      }
    }

    private def send(msg: ujson.Obj): Unit = {
      val body = ujson.write(msg).getBytes(UTF_8)
      val out  = proc.getOutputStream
      out.write(s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8))
      out.write(body)
      out.flush()
    }
    def request(id: Int, method: String, params: ujson.Value): Unit =
      send(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))
    def notify(method: String, params: ujson.Value = ujson.Null): Unit =
      send(ujson.Obj("jsonrpc" -> "2.0", "method" -> method, "params" -> params))

    /** The response to request `id` (other frames are skipped). */
    def response(id: Int, timeoutS: Long = 30): ujson.Value = {
      val end = System.currentTimeMillis() + timeoutS * 1000
      var r   = Option.empty[ujson.Value]
      while (r.isEmpty) {
        val f = Option(frames.poll(math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS))
          .getOrElse(sys.error(s"no response to $id; protocol error: $protocolError; stderr:\n$stderr"))
        if (f.obj.get("id").exists(_.numOpt.contains(id.toDouble))) r = Some(f)
      }
      r.get
    }

    def initialize(): Unit = {
      request(1, "initialize", ujson.Obj("processId" -> ujson.Null, "rootUri" -> uri(root), "capabilities" -> ujson.Obj()))
      val caps = response(1)("result")("capabilities")
      assert(caps("executeCommandProvider")("commands").arr.map(_.str) == Seq("socless.viewDesign"))
      notify("initialized", ujson.Obj())
    }

    /** Exit code, waiting at most 10 s; then both streams are read to EOF. */
    def exitCode(): Int = {
      if (!proc.waitFor(10, TimeUnit.SECONDS)) { kill(); sys.error(s"server did not exit; stderr:\n$stderr") }
      reader.join(5000)
      drain.join(5000)
      proc.exitValue()
    }

    def kill(): Unit = { proc.destroyForcibly(); proc.waitFor(10, TimeUnit.SECONDS); () }
  }

  private val strayConsole = "socless-stray-console-out"
  private val straySystem  = "socless-stray-system-out"

  val tests = Tests {
    test("stdio smoke") {
      val s = new Server(Map("SOCLESS_LSP_STRAY_OUTPUT" -> "1"))
      try {
        s.initialize()
        s.notify("textDocument/didOpen", ujson.Obj("textDocument" -> ujson.Obj(
          "uri" -> uri(gcdSpec), "languageId" -> "scala", "version" -> 1, "text" -> os.read(gcdSpec))))
        s.request(2, "textDocument/codeLens", ujson.Obj("textDocument" -> ujson.Obj("uri" -> uri(gcdSpec))))
        val lenses = s.response(2)("result").arr
        assert(lenses.size == 1)
        assert(lenses.head("command")("title").str == "View design")
        assert(lenses.head("command")("command").str == "socless.viewDesign")

        s.request(3, "shutdown", ujson.Null)
        s.response(3)
        s.notify("exit")
        val code = s.exitCode()
        assert(code == 0)
        assert(s.protocolError.isEmpty)
        // the stray prints happened, and went to stderr
        val err = s.stderr
        assert(err.contains(strayConsole), err.contains(straySystem))
      } finally s.kill()
    }
    test("exit without shutdown is 1") {
      val s = new Server()
      try {
        s.initialize()
        s.notify("exit")
        assert(s.exitCode() == 1)
        assert(s.protocolError.isEmpty)
      } finally s.kill()
    }
  }
}
