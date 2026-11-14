package socle.cli

import utest._

/** The standalone server's request guards: a Host allowlist on every route
  * (DNS-rebinding guard), a same-origin `Origin` on `POST /convert` (CSRF
  * guard), and a body cap on the bytes `/convert` actually reads.
  */
object ServeHttpTest extends TestSuite {
  private lazy val fir = Serve.readResource("/chirrtl/gcd.fir").getOrElse(sys.error("missing /chirrtl/gcd.fir"))
  // a small cap, a few KB of body past it: the whole request fits the socket buffers
  private lazy val Cap = fir.getBytes("UTF-8").length + 1

  private def withServer[T](bindHost: String = "127.0.0.1")(f: Int => T): T = {
    val s = Serve.start(bindHost, 0, "<html>ui</html>", None, maxBody = Cap)
    try f(s.getAddress.getPort) finally s.stop(0)
  }

  /** Raw request, written whole before the status line is read (the JDK client
    * forbids overriding Host). `head` is sent verbatim, so the caller frames the
    * body (Content-Length or chunked). Returns the status code. */
  private def raw(port: Int, method: String, path: String, head: Seq[(String, String)], body: String = "",
      to: String = "127.0.0.1"): Int = {
    val sock = new java.net.Socket(to, port)
    try {
      val h = head.map { case (k, v) => s"$k: $v\r\n" }.mkString
      sock.getOutputStream.write(s"$method $path HTTP/1.1\r\n${h}Connection: close\r\n\r\n$body".getBytes("UTF-8"))
      sock.getOutputStream.flush()
      val line = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream)).readLine()
      line.split(" ")(1).toInt
    } finally sock.close()
  }
  private def len(body: String) = "Content-Length" -> body.getBytes("UTF-8").length.toString
  private def chunked(body: String) = s"${Integer.toHexString(body.getBytes("UTF-8").length)}\r\n$body\r\n0\r\n\r\n"
  private val text = "Content-Type" -> "text/plain; charset=utf-8"

  val tests = Tests {
    test("a foreign Host is refused on every route") {
      withServer() { port =>
        for (h <- Seq(s"evil.example:$port", "evil.example", s"evil.localhost:$port", s"localhost.evil.example:$port",
            s"localhost:x$port")) {
          assert(raw(port, "GET", "/socle.html", Seq("Host" -> h)) == 403)
          assert(raw(port, "GET", "/design.json", Seq("Host" -> h)) == 403)
          assert(raw(port, "POST", "/convert", Seq("Host" -> h, text, len(fir)), fir) == 403)
        }
        assert(raw(port, "GET", "/socle.html", Seq()) == 403) // no Host at all
        for (h <- Seq(s"127.0.0.1:$port", s"localhost:$port", s"LOCALHOST:$port"))
          assert(raw(port, "GET", "/socle.html", Seq("Host" -> h)) == 200)
      }
    }
    test("convert: no Origin and same origin are served, a foreign origin is refused") {
      withServer() { port =>
        val host = "Host" -> s"127.0.0.1:$port"
        assert(raw(port, "POST", "/convert", Seq(host, text, len(fir)), fir) == 200)
        assert(raw(port, "POST", "/convert", Seq(host, text, len(fir), "Origin" -> s"http://127.0.0.1:$port"), fir) == 200)
        assert(raw(port, "POST", "/convert", Seq(host, text, len(fir), "Origin" -> s"HTTP://127.0.0.1:$port"), fir) == 200)
        // same origin is the request's own Host: another name or local port is another origin
        for (o <- Seq("http://evil.example", s"http://evil.example:$port", s"https://127.0.0.1:$port", "null",
            s"http://localhost:$port", s"http://127.0.0.1:${port + 1}"))
          assert(raw(port, "POST", "/convert", Seq(host, text, len(fir), "Origin" -> o), fir) == 403)
      }
    }
    test("an ssh -L tunnel on another local port is served") {
      withServer() { port =>
        val local = port + 1 // the tunnel's local port: the browser names it in Host and Origin
        for (h <- Seq(s"localhost:$local", s"127.0.0.1:$local", s"[::1]:$local", "localhost"))
          assert(raw(port, "GET", "/socle.html", Seq("Host" -> h)) == 200)
        val host = "Host" -> s"localhost:$local"
        assert(raw(port, "POST", "/convert", Seq(host, text, len(fir), "Origin" -> s"http://localhost:$local"), fir) == 200)
        assert(raw(port, "POST", "/convert", Seq(host, text, len(fir), "Origin" -> s"http://localhost:${local + 1}"), fir) == 403)
      }
    }
    test("convert: the cap applies to the bytes read, declared or not") {
      withServer() { port =>
        val host = "Host" -> s"127.0.0.1:$port"
        val big = "x" * (4 * Cap)
        // chunked: no Content-Length to trust
        assert(raw(port, "POST", "/convert", Seq(host, text, "Transfer-Encoding" -> "chunked"), chunked(big)) == 413)
        // declared over the cap: refused before reading
        assert(raw(port, "POST", "/convert", Seq(host, text, "Content-Length" -> (Cap + 1).toString), "x") == 413)
        // a declared length past Int.MaxValue is not ignored
        assert(raw(port, "POST", "/convert", Seq(host, text, "Content-Length" -> "4294967296"), "x") == 413)
        // just under the cap is read and converted (an invalid body: 200 or 500, not 413)
        val small = "x" * Cap
        assert(raw(port, "POST", "/convert", Seq(host, text, "Transfer-Encoding" -> "chunked"), chunked(small)) != 413)
      }
    }
    test("allowed hosts follow the bound address") {
      assert(Serve.allowedHosts("127.0.0.1", 8765) == Set("127.0.0.1:8765", "localhost:8765", "[::1]:8765"))
      assert(Serve.allowedHosts("192.168.1.5", 9000).contains("192.168.1.5:9000"))
      assert(Serve.allowedHosts("myhost.lan", 9000).contains("myhost.lan:9000"))
      assert(Serve.allowedHosts("::1", 9000).contains("[::1]:9000"))
      // a wildcard bind names no host of its own: the connection's local address does
      assert(!Serve.allowedHosts("0.0.0.0", 9000).exists(_.startsWith("0.0.0.0")))
      // port 80: browsers omit it
      assert(Serve.allowedHosts("127.0.0.1", 80).contains("localhost"))
    }
    test("a wildcard bind serves its interface address, not a foreign name") {
      withServer("0.0.0.0") { port =>
        assert(raw(port, "GET", "/socle.html", Seq("Host" -> s"127.0.0.1:$port")) == 200)
        assert(raw(port, "GET", "/socle.html", Seq("Host" -> s"localhost:$port")) == 200)
        assert(raw(port, "GET", "/socle.html", Seq("Host" -> s"evil.example:$port")) == 403)
        assert(raw(port, "POST", "/convert", Seq("Host" -> s"127.0.0.1:$port", text, len(fir),
          "Origin" -> s"http://127.0.0.1:$port"), fir) == 200)
        // reached on a LAN / VM address: that address, as an IP literal, is the Host
        import scala.jdk.CollectionConverters._
        val lan = java.net.NetworkInterface.networkInterfaces.iterator.asScala.filter(_.isUp)
          .flatMap(_.inetAddresses.iterator.asScala)
          .collectFirst { case a: java.net.Inet4Address if !a.isLoopbackAddress => a.getHostAddress }
        lan.foreach { ip =>
          assert(raw(port, "GET", "/socle.html", Seq("Host" -> s"$ip:$port"), to = ip) == 200)
          assert(raw(port, "GET", "/socle.html", Seq("Host" -> s"evil.example:$port"), to = ip) == 403)
        }
      }
    }
  }
}
