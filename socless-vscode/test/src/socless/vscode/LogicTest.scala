package socless.vscode

import scala.scalajs.js
import utest._

// Plain Node: only the pure Logic and Debouncer, never the vscode facades.
object LogicTest extends TestSuite {
  val tests = Tests {
    test("javaCommand") {
      test("setting wins") {
        assert(Logic.javaCommand(Some("/opt/jdk/bin/java"), Some("/jh")) == "/opt/jdk/bin/java")
      }
      test("blank setting falls back to JAVA_HOME") {
        assert(Logic.javaCommand(Some("  "), Some("/jh")) == "/jh/bin/java")
      }
      test("no setting, JAVA_HOME") {
        assert(Logic.javaCommand(None, Some("/jh")) == "/jh/bin/java")
      }
      test("blank JAVA_HOME falls back to java") {
        assert(Logic.javaCommand(Some(""), Some(" ")) == "java")
      }
      test("nothing") {
        assert(Logic.javaCommand(None, None) == "java")
      }
      test("null setting (unset in the configuration) is ignored") {
        assert(Logic.javaCommand(Some(null), Some("/jh")) == "/jh/bin/java")
        assert(Logic.javaCommand(Some(null), Some(null)) == "java")
      }
    }

    test("serverJar") {
      test("setting wins") {
        assert(Logic.serverJar(Some("/x/lsp.jar"), "/ext") == "/x/lsp.jar")
      }
      test("blank setting uses the bundled jar") {
        assert(Logic.serverJar(Some(" "), "/ext") == "/ext/server/socless-lsp.jar")
      }
      test("default") {
        assert(Logic.serverJar(None, "/ext") == "/ext/server/socless-lsp.jar")
      }
      test("null setting uses the bundled jar") {
        assert(Logic.serverJar(Some(null), "/ext") == "/ext/server/socless-lsp.jar")
      }
    }

    test("parseJavaMajor") {
      def out(first: String) =
        s"$first\nOpenJDK Runtime Environment (build x)\nOpenJDK 64-Bit Server VM (build x, mixed mode)\n"
      test("8")  { assert(Logic.parseJavaMajor(out("""java version "1.8.0_402"""")) == Some(8)) }
      test("17") { assert(Logic.parseJavaMajor(out("""openjdk version "17.0.9" 2023-10-17 LTS""")) == Some(17)) }
      test("21") { assert(Logic.parseJavaMajor(out("""openjdk version "21.0.12.1" 2026-08-18""")) == Some(21)) }
      test("25") { assert(Logic.parseJavaMajor(out("""openjdk version "25" 2025-09-16""")) == Some(25)) }
      test("picked up line first") {
        val s = "Picked up JAVA_TOOL_OPTIONS: -Dx=1\n" + out("""openjdk version "21.0.12" 2026-08-18""")
        assert(Logic.parseJavaMajor(s) == Some(21))
      }
      test("garbage") {
        assert(Logic.parseJavaMajor("") == None)
        assert(Logic.parseJavaMajor("bash: java: command not found") == None)
      }
    }

    test("cursorParams") {
      val p = Logic.cursorParams("file:///a/B.scala", 12).asInstanceOf[js.Dynamic]
      assert(p.uri.asInstanceOf[String] == "file:///a/B.scala")
      assert(p.line.asInstanceOf[Int] == 12)
      assert(js.Object.keys(p.asInstanceOf[js.Object]).toSeq == Seq("uri", "line"))
    }

    test("serverArgs: an IPv4-only stack, so WSL relays the panel's loopback port to Windows") {
      assert(Logic.serverArgs("/ext/server/socless-lsp.jar") ==
        Seq("-Djava.net.preferIPv4Stack=true", "-jar", "/ext/server/socless-lsp.jar"))
    }

    test("initializationOptions") {
      def keys(o: js.Object) = js.Object.keys(o).toSeq
      test("panel only when the settings are unset or blank") {
        val o = Logic.initializationOptions(None, Some(null))
        assert(keys(o) == Seq("panel"), o.asInstanceOf[js.Dynamic].panel.asInstanceOf[String] == "client")
        assert(keys(Logic.initializationOptions(Some(" "), Some(""))) == Seq("panel"))
      }
      test("non-blank settings are passed, trimmed") {
        val o = Logic.initializationOptions(Some(" emitFst "), Some("/opt/surfer"))
        val d = o.asInstanceOf[js.Dynamic]
        assert(keys(o) == Seq("panel", "waveFlag", "surferCommand"))
        assert(d.waveFlag.asInstanceOf[String] == "emitFst", d.surferCommand.asInstanceOf[String] == "/opt/surfer")
      }
    }

    test("webviewHtml") {
      test("csp and iframe") {
        val h = Logic.webviewHtml("http://127.0.0.1:41234/", "vscode-webview://abc", "N0nce")
        assert(h.startsWith("<!DOCTYPE html>"))
        assert(h.contains("default-src 'none'"))
        assert(h.contains("frame-src http://127.0.0.1:41234;"))
        assert(h.contains("style-src vscode-webview://abc 'unsafe-inline';"))
        assert(h.contains("script-src 'nonce-N0nce';"))
        assert(h.contains("""<iframe src="http://127.0.0.1:41234/""""))
      }
      test("escapes the url") {
        val h = Logic.webviewHtml("""http://127.0.0.1:41234/?a="b"&c<d>""", "vscode-webview://abc", "N0nce")
        assert(h.contains("""src="http://127.0.0.1:41234/?a=&quot;b&quot;&amp;c&lt;d&gt;""""))
        assert(!h.contains(""""b""""))
        assert(h.contains("frame-src http://127.0.0.1:41234;"))
      }
      test("theme script: nonce, body classes, posts to the frame origin only") {
        val h = Logic.webviewHtml("http://127.0.0.1:41234/?host=vscode", "vscode-webview://abc", "N0nce")
        val scripts = "<script".r.findAllMatchIn(h).size
        assert(scripts == 1, h.contains("""<script nonce="N0nce">"""))
        assert(h.contains("MutationObserver"), h.contains("vscode-high-contrast"), h.contains("vscode-dark"))
        assert(h.contains("socless-theme"))
        assert(h.contains("""postMessage({ type: 'socless-theme', kind: k }, "http://127.0.0.1:41234")"""))
      }
      test("side panel relay: state up from the frame, navigate down to it") {
        val h      = Logic.webviewHtml("http://127.0.0.1:41234/?host=vscode", "vscode-webview://abc", "N0nce")
        val body   = h.substring(h.indexOf("""<script nonce="N0nce">""") + 22, h.indexOf("</script>"))
        val toFrame = js.Array[js.Any]() // (message, targetOrigin) the page posts to the iframe
        val toHost  = js.Array[js.Any]() // what the page sends to the extension
        val frameWin = js.Dynamic.literal(postMessage = (m: js.Any, o: js.Any) => { toFrame.push(js.Array(m, o)); () })
        val frame = js.Dynamic.literal(contentWindow = frameWin, addEventListener = (_: js.Any, _: js.Any) => ())
        var onMessage: js.Function1[js.Any, Unit] = null
        val host = js.Dynamic.literal(name = "webview host") // a window of the webview origin, which relays the extension
        val window = js.Dynamic.literal(parent = host, origin = "vscode-webview://abc",
          addEventListener = (t: String, f: js.Function1[js.Any, Unit]) => if (t == "message") onMessage = f)
        val document = js.Dynamic.literal(
          querySelector = (_: String) => frame,
          body = js.Dynamic.literal(classList = js.Dynamic.literal(contains = (_: String) => false)))
        val observer = js.eval("(function () { this.observe = function () {}; })").asInstanceOf[js.Any]
        val vscodeApi = js.Dynamic.literal(postMessage = (m: js.Any) => { toHost.push(m); () })
        val run = js.Dynamic.newInstance(js.Dynamic.global.Function)("window", "document", "MutationObserver", "acquireVsCodeApi", body)
        run(window, document, observer, (() => vscodeApi): js.Function0[js.Any])
        assert(onMessage != null)
        def event(source: js.Any, origin: String, data: js.Any): Unit =
          onMessage(js.Dynamic.literal(source = source, origin = origin, data = data))
        val state = js.Dynamic.literal(`type` = "socless-state", path = js.Array("Top"))
        event(frameWin, "http://127.0.0.1:41234", state)
        assert(toHost.length == 1, toHost(0) eq state.asInstanceOf[js.Any])
        // from the frame but another origin, or not a state: dropped
        event(frameWin, "http://evil.example", state)
        event(frameWin, "http://127.0.0.1:41234", js.Dynamic.literal(`type` = "socless-navigate", path = js.Array("Top")))
        event(frameWin, "http://127.0.0.1:41234", "socless-state")
        assert(toHost.length == 1)
        // from the extension: a navigate goes to the frame origin, rebuilt with only its fields
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-navigate", path = js.Array("Top", "a"), select = "b", extra = 1))
        assert(toFrame.length == 1)
        val sent = toFrame(0).asInstanceOf[js.Array[js.Dynamic]]
        assert(sent(1).asInstanceOf[String] == "http://127.0.0.1:41234")
        assert(js.JSON.stringify(sent(0)) == """{"type":"socless-navigate","path":["Top","a"],"select":"b"}""")
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-navigate", path = js.Array("Top")))
        assert(js.JSON.stringify(toFrame(1).asInstanceOf[js.Array[js.Any]](0)) == """{"type":"socless-navigate","path":["Top"],"select":null}""")
        // anything else from the extension is not relayed
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-theme", kind = "dark"))
        assert(toFrame.length == 2, toHost.length == 1)
        // a navigate from a window of another origin (a third page, or the frame posting as itself),
        // or a synthetic event without a source window: not relayed
        val nav = js.Dynamic.literal(`type` = "socless-navigate", path = js.Array("Top", "x"))
        event(js.Dynamic.literal(name = "third window"), "http://127.0.0.1:41234", nav)
        event(host, "http://evil.example", nav)
        event(null, "vscode-webview://abc", nav)
        event(frameWin, "http://127.0.0.1:41234", nav) // the frame itself
        assert(toFrame.length == 2, toHost.length == 1)
      }
      test("no script without a frame origin") {
        val h = Logic.webviewHtml("not a url", "vscode-webview://abc", "N0nce")
        assert(!h.contains("<script"), h.contains("frame-src 'none';"))
      }
    }

    test("PanelPage") {
      val page = new PanelPage("N0nce", "dark")
      test("same url, identical html (no iframe reload)") {
        assert(page.html("http://127.0.0.1:41234/", "csp") == page.html("http://127.0.0.1:41234/", "csp"))
      }
      test("embedded url with the panel's theme and nonce") {
        val h = page.html("http://127.0.0.1:41234/", "csp")
        assert(h.contains("""src="http://127.0.0.1:41234/?host=vscode&amp;theme=dark""""), h.contains("'nonce-N0nce'"))
      }
      test("a new server url changes the html") {
        assert(page.html("http://127.0.0.1:41234/", "csp") != page.html("http://127.0.0.1:5000/", "csp"))
      }
    }

    test("hex") {
      assert(Logic.hex(Seq(0, 15, 16, 255, -1)) == "000f10ffff")
    }

    test("themeKind") {
      // vscode.ColorThemeKind: 1 Light, 2 Dark, 3 HighContrast, 4 HighContrastLight
      assert(Logic.themeKind(1) == "light", Logic.themeKind(2) == "dark")
      assert(Logic.themeKind(3) == "hc", Logic.themeKind(4) == "light")
      assert(Logic.themeKind(99) == "dark")
    }

    test("panelUrl") {
      test("plain") {
        assert(Logic.panelUrl("http://127.0.0.1:41234/", "dark") == "http://127.0.0.1:41234/?host=vscode&theme=dark")
      }
      test("existing query and fragment") {
        assert(Logic.panelUrl("http://127.0.0.1:1/?a=1#x", "hc") == "http://127.0.0.1:1/?a=1&host=vscode&theme=hc#x")
      }
    }

    test("panelTitle") {
      assert(Logic.panelTitle(None) == "SoCLess design")
      assert(Logic.panelTitle(Some("  ")) == "SoCLess design")
      assert(Logic.panelTitle(Some("GCD computes gcd(48, 18)")) == "SoCLess: GCD computes gcd(48, 18)")
      val long = "a" * 100
      val t = Logic.panelTitle(Some(long))
      assert(t == "SoCLess: " + "a" * 59 + "…")
    }

    test("Debouncer") {
      // Fake clock: scheduled tasks wait in a map until fired by hand.
      var next = 0
      val pending = scala.collection.mutable.LinkedHashMap.empty[Int, (Int, () => Unit)]
      val d = new Debouncer(
        200,
        (ms, f) => { next += 1; pending(next) = (ms, f); next },
        h => pending.remove(h.asInstanceOf[Int]),
      )
      val ran = scala.collection.mutable.ArrayBuffer.empty[Int]
      d(() => ran += 1)
      d(() => ran += 2)
      d(() => ran += 3)
      assert(pending.size == 1)
      assert(pending.values.head._1 == 200)
      assert(ran.isEmpty)
      pending.values.head._2()
      assert(ran.toSeq == Seq(3))

      // A call after the delay schedules afresh.
      pending.clear()
      d(() => ran += 4)
      pending.values.head._2()
      assert(ran.toSeq == Seq(3, 4))
    }

    test("javaProblem") {
      test("found and recent enough is fine") {
        assert(Logic.javaProblem("java", Some(21)).isEmpty)
        assert(Logic.javaProblem("java", Some(25)).isEmpty)
      }
      test("missing names the command and the setting") {
        val m = Logic.javaProblem("/x/java", None).get
        assert(m.contains("/x/java"), m.contains("socless.javaPath"), m.contains("21"))
      }
      test("too old names the version and the setting") {
        val m = Logic.javaProblem("java", Some(17)).get
        assert(m.contains("17"), m.contains("socless.javaPath"), m.contains("21"))
      }
      test("both messages say to reload the window") {
        assert(Logic.javaProblem("java", None).get.endsWith("then reload the window."))
        assert(Logic.javaProblem("java", Some(17)).get.endsWith("then reload the window."))
      }
    }
    test("RestartBudget") {
      test("allows 3 restarts within the window, then refuses") {
        val b = new RestartBudget(3, 180000)
        assert(b.allow(0.0), b.allow(1000.0), b.allow(2000.0))
        assert(!b.allow(3000.0))
      }
      test("restarts older than the window are forgotten") {
        val b = new RestartBudget(3, 180000)
        assert(b.allow(0.0), b.allow(1000.0), b.allow(2000.0))
        assert(b.allow(180001.0))
        assert(!b.allow(180500.0))
        assert(b.allow(182001.0))
      }
      test("a refusal is not counted") {
        val b = new RestartBudget(1, 1000)
        assert(b.allow(0.0), !b.allow(500.0), !b.allow(999.0))
        assert(b.allow(1001.0))
      }
    }
  }
}
