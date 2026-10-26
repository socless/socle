package socless.lsp

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicReference
import socless.schema.Design
import utest._

object LiveHttpTest extends TestSuite {
  private val client = HttpClient.newHttpClient()
  private def get(port: Int, path: String, method: String = "GET") =
    client.send(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
      HttpResponse.BodyHandlers.ofString())
  private def post(port: Int, path: String, body: String) =
    client.send(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
      HttpResponse.BodyHandlers.ofString())
  private def withServer[T](onOpen: (String, Int) => Unit = (_, _) => ())(f: (LiveHttp, Int) => T): T = {
    val s = new LiveHttp(onOpen)
    val port = s.start()
    try f(s, port) finally s.stop()
  }
  /** Raw request: the JDK client forbids overriding Host. Returns the status code. */
  private def raw(port: Int, method: String, path: String, headers: Seq[(String, String)], body: String = ""): Int = {
    val sock = new java.net.Socket("127.0.0.1", port)
    try {
      val h = headers.map { case (k, v) => s"$k: $v\r\n" }.mkString
      val len = body.getBytes("UTF-8").length
      sock.getOutputStream.write(
        s"$method $path HTTP/1.1\r\n${h}Content-Length: $len\r\nConnection: close\r\n\r\n$body".getBytes("UTF-8"))
      sock.getOutputStream.flush()
      val line = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream)).readLine()
      line.split(" ")(1).toInt
    } finally sock.close()
  }
  private def design(top: String) = Design(top, Map.empty, Nil)

  val tests = Tests {
    test("serves ui") {
      withServer() { (_, port) =>
        val r = get(port, "/")
        assert(r.statusCode == 200, r.body.contains("<html"))
        // only the page itself or VS Code's webview may frame the UI
        assert(r.headers.firstValue("Content-Security-Policy").orElse("") == LiveHttp.FrameAncestors)
        assert(LiveHttp.FrameAncestors.startsWith("frame-ancestors 'self' vscode-webview:"))
      }
    }
    test("design") {
      withServer() { (s, port) =>
        assert(get(port, "/design.json").statusCode == 404)
        assert(get(port, "/design.json", "HEAD").statusCode == 404)
        val d = design("A")
        s.publish(d)
        val r1 = get(port, "/design.json")
        assert(r1.statusCode == 200, upickle.default.read[Design](r1.body) == d)
        val lm1 = r1.headers.firstValue("Last-Modified").get
        // back-to-back publish, same wall-clock second: stamp must still change
        s.publish(design("B"))
        val h = get(port, "/design.json", "HEAD")
        assert(h.statusCode == 200, h.headers.firstValue("Last-Modified").get != lm1)
      }
    }
    test("design stamp is monotonic with a frozen clock") {
      val s = new LiveHttp((_, _) => (), () => 5000L)
      val port = s.start()
      try {
        s.publish(design("A"))
        val a = get(port, "/design.json").headers.firstValue("Last-Modified").get
        s.publish(design("B"))
        val b = get(port, "/design.json").headers.firstValue("Last-Modified").get
        assert(a != b)
      } finally s.stop()
    }
    test("ide state") {
      withServer() { (s, port) =>
        val before = upickle.default.read[IdeState](get(port, "/ide.json").body)
        assert(before.status == "idle")
        s.update(_.copy(status = "running", message = "m"))
        val body = get(port, "/ide.json").body
        val st = upickle.default.read[IdeState](body)
        assert(st.status == "running", st.message == "m", st.stamp > 0)
        assert(body.contains("\"focusClass\""))
      }
    }
    test("ide.json carries the wave fields") {
      withServer() { (s, port) =>
        val ws = socless.wave.WaveSettings(followDelayMs = 40)
        s.update(_.copy(waveViewer = "surfer-panel", trace = "/wave/3/trace", traceTest = "GcdSpec#computes", waveSettings = Some(ws),
          suite = "GcdSpec"))
        val j = ujson.read(get(port, "/ide.json").body)
        // the suite too, so the panel compares `<suite>#<test>` with traceTest
        assert(j("suite").str == "GcdSpec")
        assert(j("waveViewer").str == "surfer-panel", j("trace").str == "/wave/3/trace",
          j("traceTest").str == "GcdSpec#computes", j("waveSettings")("followDelayMs").num == 40)
        s.update(_.copy(waveViewer = "", trace = "", traceTest = "", waveSettings = None))
        val j2 = ujson.read(get(port, "/ide.json").body)
        assert(j2("waveViewer").str == "", j2("trace").str == "", j2("traceTest").str == "", j2("waveSettings").isNull)
        // the body reads back as an IdeState (the UI parses it the same way)
        s.update(_.copy(waveSettings = Some(ws)))
        assert(upickle.default.read[IdeState](get(port, "/ide.json").body).waveSettings == Some(ws))
      }
    }
    test("an old IdeState json parses") {
      val st = upickle.default.read[IdeState]("""{"status":"ok","message":"m","focusClass":"","test":"","stamp":1}""")
      assert(st.waveViewer == "", st.trace == "", st.traceTest == "", st.waveSettings.isEmpty)
    }
    test("open") {
      val got = new AtomicReference[(String, Int)]
      withServer((f, l) => got.set((f, l))) { (_, port) =>
        val r = post(port, "/open", """{"file":"/x/A.scala","line":12}""")
        assert(r.statusCode == 204, got.get == (("/x/A.scala", 12)))
      }
    }
    test("open bad json is 400, callback exception is 500 and server survives") {
      withServer((_, _) => throw new RuntimeException("boom")) { (_, port) =>
        assert(post(port, "/open", "not json").statusCode == 400)
        assert(post(port, "/open", """{"file":"/x","line":1}""").statusCode == 500)
        assert(get(port, "/ide.json").statusCode == 200)
      }
    }
    test("wrong Host is 403") {
      withServer() { (s, port) =>
        s.publish(design("A"))
        assert(raw(port, "GET", "/design.json", Seq("Host" -> "evil.example:80")) == 403)
        assert(raw(port, "GET", "/design.json", Seq("Host" -> s"localhost:$port")) == 200)
        assert(raw(port, "GET", "/design.json", Seq("Host" -> s"127.0.0.1:$port")) == 200)
      }
    }
    test("open rejects text/plain, foreign origin, oversize; accepts same origin") {
      val calls = new java.util.concurrent.atomic.AtomicInteger
      withServer((_, _) => { calls.incrementAndGet(); () }) { (_, port) =>
        val b = """{"file":"/x","line":1}"""
        val host = "Host" -> s"127.0.0.1:$port"
        assert(raw(port, "POST", "/open", Seq(host, "Content-Type" -> "text/plain"), b) == 415)
        assert(raw(port, "POST", "/open", Seq(host, "Content-Type" -> "application/json", "Origin" -> "http://evil.example"), b) == 403)
        assert(raw(port, "POST", "/open", Seq(host, "Content-Type" -> "application/json"), "x" * 70000) == 413)
        assert(calls.get == 0)
        assert(raw(port, "POST", "/open", Seq(host, "Content-Type" -> "application/json; charset=utf-8", "Origin" -> s"http://127.0.0.1:$port"), b) == 204)
        assert(calls.get == 1)
      }
    }
    test("wave routes serve only the current trace") {
      withServer() { (s, port) =>
        val dir = os.temp.dir(prefix = "socless-wave-")
        try {
          val a = dir / "a.vcd"
          os.write(a, "$scope module TOP $end\n" * 1000)
          assert(get(port, "/wave/ab12/trace").statusCode == 404) // no trace yet
          s.serveTrace(Some(LiveHttp.Trace("ab12", a, TraceNames("TOP.x", Set("TOP.x.b", "TOP.x.a")))))
          val t = get(port, "/wave/ab12/trace")
          assert(t.statusCode == 200, t.body == os.read(a), t.headers.firstValue("Cache-Control").orElse("") == "no-store")
          assert(get(port, "/wave/ab12/trace", "HEAD").statusCode == 200)
          val n = get(port, "/wave/ab12/names")
          assert(n.statusCode == 200, n.headers.firstValue("Content-Type").orElse("") == "application/json")
          assert(ujson.read(n.body) == ujson.Obj("prefix" -> "TOP.x", "names" -> ujson.Arr("TOP.x.a", "TOP.x.b")))
          // a new run: the old id stops resolving
          val b = dir / "b.vcd"
          os.write(b, "b")
          s.serveTrace(Some(LiveHttp.Trace("cd34", b, TraceNames("TOP.y", Set.empty))))
          assert(get(port, "/wave/ab12/trace").statusCode == 404, get(port, "/wave/ab12/names").statusCode == 404)
          assert(get(port, "/wave/cd34/trace").body == "b")
          assert(ujson.read(get(port, "/wave/cd34/names").body)("names").arr.isEmpty)
          // path-like, other-case, unknown-file and non-GET requests are 404
          val host = "Host" -> s"127.0.0.1:$port"
          for (p <- Seq("/wave/../x/trace", "/wave/..%2Fx/trace", "/wave/cd34/../cd34/trace", "/wave/CD34/trace",
              "/wave/cd34/other", "/wave/cd34/trace/x", "/wave//trace", "/wave/cd34"))
            assert(raw(port, "GET", p, Seq(host)) == 404)
          assert(raw(port, "POST", "/wave/cd34/trace", Seq(host, "Content-Type" -> "application/json"), "{}") == 404)
          // the trace gone from disk (the run dir wiped): 404, not 500
          os.remove(b)
          assert(get(port, "/wave/cd34/trace").statusCode == 404)
          s.serveTrace(None)
          assert(get(port, "/wave/cd34/names").statusCode == 404)
        } finally os.remove.all(dir)
      }
    }
    test("wave select: JSON, same origin, size cap; the request reaches onSelect") {
      val got = new java.util.concurrent.ConcurrentLinkedQueue[SelectRequest]
      val s = new LiveHttp((_, _) => (), onSelect = r => { got.add(r); () })
      val port = s.start()
      try {
        val host = "Host" -> s"127.0.0.1:$port"
        val json = "Content-Type" -> "application/json"
        val b = """{"traceTest":"S#t","instPath":["GCD","sub"],"selection":"io.in"}"""
        assert(raw(port, "POST", "/wave/select", Seq(host, "Content-Type" -> "text/plain"), b) == 415)
        assert(raw(port, "POST", "/wave/select", Seq(host, json, "Origin" -> "http://evil.example"), b) == 403)
        assert(raw(port, "POST", "/wave/select", Seq(host, json), "x" * 70000) == 413)
        assert(raw(port, "POST", "/wave/select", Seq("Host" -> "evil.example:80", json), b) == 403)
        for (bad <- Seq("not json", """{"traceTest":"S#t","instPath":[]}""", """{"traceTest":1,"instPath":["A"],"selection":null}""",
            """{"traceTest":"S#t","instPath":["A",2],"selection":null}""", """{"traceTest":"S#t","instPath":["A"],"selection":3}"""))
          assert(raw(port, "POST", "/wave/select", Seq(host, json), bad) == 400)
        assert(got.isEmpty)
        assert(raw(port, "POST", "/wave/select", Seq(host, json, "Origin" -> s"http://127.0.0.1:$port"), b) == 204)
        assert(post(port, "/wave/select", """{"traceTest":"S#t","instPath":["GCD"],"selection":null}""").statusCode == 204)
        assert(got.toArray.toSeq == Seq(SelectRequest("S#t", List("GCD", "sub"), Some("io.in")),
          SelectRequest("S#t", List("GCD"), None)))
        assert(get(port, "/wave/select").statusCode == 404) // POST only
      } finally s.stop()
    }
    test("surfer serves exactly the vendored module and wasm") {
      withServer() { (_, port) =>
        def bytes(path: String) = client.send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray())
        val js = get(port, "/surfer/surfer.js")
        assert(js.statusCode == 200, js.headers.firstValue("Content-Type").orElse("") == "text/javascript")
        // the WCP and inject entry points the panel imports
        for (name <- Seq("start_wcp", "handle_wcp_cs_message", "next_wcp_sc_message", "inject_message", "WebHandle"))
          assert(js.body.contains(name))
        val wasm = bytes("/surfer/surfer_bg.wasm")
        assert(wasm.statusCode == 200, wasm.headers.firstValue("Content-Type").orElse("") == "application/wasm")
        assert(wasm.body.take(4).toSeq == Seq[Byte](0, 'a', 's', 'm'))
        // revalidated on every load (an updated jar never meets a stale copy), but a
        // 304 lets the browser keep its compiled wasm: the ETag is the vendored build's
        for (r <- Seq(js, wasm)) assert(r.headers.firstValue("Cache-Control").orElse("") == "no-cache")
        val etag = wasm.headers.firstValue("ETag").orElse("")
        assert(etag.matches("\"[0-9a-f]{16,}\""), js.headers.firstValue("ETag").orElse("") == etag)
        def cond(path: String, tag: String) = client.send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).header("If-None-Match", tag).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray())
        val again = cond("/surfer/surfer_bg.wasm", etag)
        assert(again.statusCode == 304, again.body.isEmpty, again.headers.firstValue("ETag").orElse("") == etag)
        assert(cond("/surfer/surfer.js", s"W/$etag, \"other\"").statusCode == 304) // weak and listed tags match too
        assert(cond("/surfer/surfer.js", "\"stale\"").statusCode == 200)
        val head = get(port, "/surfer/surfer_bg.wasm", "HEAD")
        assert(head.statusCode == 200, head.headers.firstValue("Content-Type").orElse("") == "application/wasm")
        // everything else under /surfer/ is 404: the stock page, its service worker, the files shipped
        // beside the build but not served, path tricks, other case, and writes
        val host = "Host" -> s"127.0.0.1:$port"
        for (p <- Seq("/surfer", "/surfer/", "/surfer/index.html", "/surfer/sw.js", "/surfer/integration.js",
            "/surfer/VERSION", "/surfer/SOURCE.md", "/surfer/LICENSE-EUPL-1.2.txt", "/surfer/../surfer/surfer.js",
            "/surfer/%2E%2E/SoCLess.html", "/surfer/Surfer.js", "/surfer/surfer.js/x", "/surfer//surfer.js"))
          assert(raw(port, "GET", p, Seq(host)) == 404)
        assert(raw(port, "POST", "/surfer/surfer.js", Seq(host, "Content-Type" -> "application/json"), "{}") == 404)
        assert(raw(port, "GET", "/surfer/surfer.js", Seq("Host" -> "evil.example:80")) == 403)
      }
    }
    test("unknown is 404") {
      withServer() { (_, port) => assert(get(port, "/nope").statusCode == 404) }
    }
    test("two instances") {
      val a = new LiveHttp((_, _) => ())
      val b = new LiveHttp((_, _) => ())
      val pa = a.start()
      val pb = b.start()
      try {
        assert(pa != pb)
        assert(a.boundAddress.getAddress.isLoopbackAddress, b.boundAddress.getAddress.isLoopbackAddress)
        assert(a.boundAddress.getAddress.getHostAddress == "127.0.0.1")
      } finally { a.stop(); b.stop() }
    }
    test("stop releases the port") {
      val s = new LiveHttp((_, _) => ())
      val p = s.start()
      s.stop()
      val ss = new java.net.ServerSocket(p, 0, java.net.InetAddress.getByName("127.0.0.1"))
      ss.close()
    }
  }
}
