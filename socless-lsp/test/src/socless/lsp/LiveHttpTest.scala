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
