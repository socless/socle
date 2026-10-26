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
      test("the Surfer relay carries the four Surfer channel types") {
        val h = Logic.webviewHtml("http://127.0.0.1:41234/?host=vscode", "vscode-webview://abc", "N0nce")
        for (t <- Seq("socless-surfer-open", "socless-surfer-cmd", "socless-surfer-status", "socless-surfer-closed"))
          assert(h.contains(t))
      }
      test("Surfer relay: open, cmd and status up from the frame; cmd, status and closed down to it") {
        val h      = Logic.webviewHtml("http://127.0.0.1:41234/?host=vscode", "vscode-webview://abc", "N0nce")
        val body   = h.substring(h.indexOf("""<script nonce="N0nce">""") + 22, h.indexOf("</script>"))
        val toFrame = js.Array[js.Any]()
        val toHost  = js.Array[js.Any]()
        val frameWin = js.Dynamic.literal(postMessage = (m: js.Any, o: js.Any) => { toFrame.push(js.Array(m, o)); () })
        val frame = js.Dynamic.literal(contentWindow = frameWin, addEventListener = (_: js.Any, _: js.Any) => ())
        var onMessage: js.Function1[js.Any, Unit] = null
        val host = js.Dynamic.literal(name = "webview host")
        val window = js.Dynamic.literal(parent = host, origin = "vscode-webview://abc",
          addEventListener = (t: String, f: js.Function1[js.Any, Unit]) => if (t == "message") onMessage = f)
        val document = js.Dynamic.literal(
          querySelector = (_: String) => frame,
          body = js.Dynamic.literal(classList = js.Dynamic.literal(contains = (_: String) => false)))
        val observer = js.eval("(function () { this.observe = function () {}; })").asInstanceOf[js.Any]
        val vscodeApi = js.Dynamic.literal(postMessage = (m: js.Any) => { toHost.push(m); () })
        val run = js.Dynamic.newInstance(js.Dynamic.global.Function)("window", "document", "MutationObserver", "acquireVsCodeApi", body)
        run(window, document, observer, (() => vscodeApi): js.Function0[js.Any])
        def event(source: js.Any, origin: String, data: js.Any): Unit =
          onMessage(js.Dynamic.literal(source = source, origin = origin, data = data))
        val cmd    = js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = js.Dynamic.literal(`$type` = "Load", url = "u"))
        val status = js.Dynamic.literal(`type` = "socless-surfer-status", status = js.Dynamic.literal(state = "ready"))
        val open   = js.Dynamic.literal(`type` = "socless-surfer-open", trace = "u", traceTest = "s.S#t", force = true)
        // up, from the frame's origin only, whole (the open keeps its traceTest)
        for (m <- Seq(open, cmd, status)) event(frameWin, "http://127.0.0.1:41234", m)
        assert(toHost.length == 3)
        assert(js.JSON.stringify(toHost(0)) == """{"type":"socless-surfer-open","trace":"u","traceTest":"s.S#t","force":true}""")
        event(frameWin, "http://evil.example", cmd)
        event(frameWin, "http://127.0.0.1:41234", js.Dynamic.literal(`type` = "socless-surfer-closed"))
        assert(toHost.length == 3)
        // down, from the webview host, to the frame origin, rebuilt with only their fields
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = js.Dynamic.literal(`$type` = "Hello"), extra = 1))
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-surfer-status", status = js.Dynamic.literal(state = "idle")))
        event(host, "vscode-webview://abc", js.Dynamic.literal(`type` = "socless-surfer-closed", extra = 1))
        event(host, "vscode-webview://abc", open) // not a downward type
        event(host, "http://evil.example", cmd)
        assert(toFrame.length == 3)
        def sent(i: Int) = toFrame(i).asInstanceOf[js.Array[js.Any]]
        assert(js.JSON.stringify(sent(0)(0)) == """{"type":"socless-surfer-cmd","cmd":{"$type":"Hello"}}""")
        assert(js.JSON.stringify(sent(1)(0)) == """{"type":"socless-surfer-status","status":{"state":"idle"}}""")
        assert(js.JSON.stringify(sent(2)(0)) == """{"type":"socless-surfer-closed"}""")
        assert((0 until 3).forall(i => sent(i)(1).asInstanceOf[String] == "http://127.0.0.1:41234"))
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

    test("surferUrl") {
      assert(Logic.surferUrl("http://127.0.0.1:5/", "dark") == "http://127.0.0.1:5/?host=vscode&view=surfer&theme=dark")
      assert(Logic.surferUrl("http://127.0.0.1:5/?a=1#x", "hc") == "http://127.0.0.1:5/?a=1&host=vscode&view=surfer&theme=hc#x")
    }

    test("surferTitle") {
      assert(Logic.surferTitle(Some("t")) == "Surfer: t")
      assert(Logic.surferTitle(None) == "Surfer", Logic.surferTitle(Some(" ")) == "Surfer")
      assert(Logic.surferTitle(Some("a" * 100)) == "Surfer: " + "a" * 59 + "…")
    }

    test("surferColumn") {
      // the column after the SoCLess panel's, so the tab never covers it; beside the editor without a panel
      assert(Logic.surferColumn(Some(1)) == 2, Logic.surferColumn(Some(2)) == 3)
      assert(Logic.surferColumn(None) == -2) // vscode.ViewColumn.Beside
    }

    test("panelRevealColumn") {
      // an existing panel stays in its own column, even when the Surfer tab is the active editor
      assert(Logic.panelRevealColumn(Some(1)) == 1, Logic.panelRevealColumn(Some(3)) == 3)
      assert(Logic.panelRevealColumn(None) == -2) // a fresh panel: beside the active editor
    }

    test("showWavesProblem") {
      assert(Logic.showWavesProblem(Some("surfer-panel"), hasTrace = true).isEmpty)
      val m = Logic.showWavesProblem(Some("surfer"), hasTrace = true).get
      assert(m.contains("socless.waveViewer"), m.contains("surfer-panel"))
      assert(Logic.showWavesProblem(None, hasTrace = true).get.contains("socless.waveViewer")) // the default is surfer
      // a View design without a Simulate has no trace
      assert(Logic.showWavesProblem(Some("surfer-panel"), hasTrace = false).contains("SoCLess: Run Simulate first."))
    }

    test("surferTest") {
      // the test part of the trace's "<suite>#<test>", for the Surfer tab's title
      assert(Logic.surferTest(Some("gcd.GcdSpec#GCD computes"), Some("other")) == Some("GCD computes"))
      assert(Logic.surferTest(Some("a.B#case #1"), None) == Some("case #1")) // the first # ends the suite
      assert(Logic.surferTest(Some("no suite"), None) == Some("no suite"))   // a server that sends no suite
      // absent or blank: the fallback (the panel's test)
      assert(Logic.surferTest(None, Some("panel")) == Some("panel"))
      assert(Logic.surferTest(Some(""), Some("panel")) == Some("panel"))
      assert(Logic.surferTest(Some("a.B#"), Some("panel")) == Some("panel"))
      assert(Logic.surferTest(None, None).isEmpty)
    }

    test("answersClosed") {
      def cmd(t: String): js.Any =
        js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = js.Dynamic.literal(`$type` = t))
      // a Hello with no Surfer tab: the extension answers socless-surfer-closed
      assert(Logic.answersClosed(cmd("Hello"), tabOpen = false))
      // with a tab (ready or not), it goes to the tab; other commands always do
      assert(!Logic.answersClosed(cmd("Hello"), tabOpen = true))
      assert(!Logic.answersClosed(cmd("Load"), tabOpen = false))
      assert(!Logic.answersClosed(js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = "Hello"), tabOpen = false))
      assert(!Logic.answersClosed(null, tabOpen = false))
    }

    test("route") {
      def msg(t: String): js.Any = js.Dynamic.literal(`type` = t)
      val cmd = msg("socless-surfer-cmd")
      val st  = msg("socless-surfer-status")
      assert(Logic.route("panel", cmd).exists { case (to, m) => to == "surfer" && (m eq cmd) })
      assert(Logic.route("surfer", st).exists { case (to, m) => to == "panel" && (m eq st) })
      // wrong directions
      assert(Logic.route("surfer", cmd).isEmpty, Logic.route("panel", st).isEmpty)
      // unknown types, the open (handled by the extension), not an object
      assert(Logic.route("panel", msg("socless-state")).isEmpty, Logic.route("panel", msg("socless-surfer-open")).isEmpty)
      assert(Logic.route("panel", "socless-surfer-cmd").isEmpty, Logic.route("panel", null).isEmpty)
      assert(Logic.route("elsewhere", cmd).isEmpty)
    }

    test("SurferOpens") {
      val o = new SurferOpens
      test("a new trace opens once; again only when forced") {
        assert(o.admit("t1", force = false))
        assert(!o.admit("t1", force = false))
        assert(o.admit("t1", force = true))
        assert(!o.admit("t1", force = false))
        assert(o.admit("t2", force = false))
      }
      test("a forced open counts as opened") {
        assert(o.admit("t1", force = true))
        assert(!o.admit("t1", force = false))
      }
      test("no trace: only forced") {
        assert(!o.admit("", force = false), o.admit("", force = true))
      }
      test("traced: once any open carried a trace") {
        assert(!o.traced)
        o.admit("", force = true)
        assert(!o.traced)
        o.admit("t1", force = false)
        assert(o.traced)
      }
    }

    test("SurferQueue") {
      def cmd(t: String, n: Int): js.Any =
        js.Dynamic.literal(`type` = "socless-surfer-cmd", cmd = js.Dynamic.literal(`$type` = t, n = n))
      def tags(xs: Seq[js.Any]): Seq[String] =
        xs.map { m => val c = m.asInstanceOf[js.Dynamic].cmd; s"${c.selectDynamic("$type")}${c.n}" }
      val sent = scala.collection.mutable.ArrayBuffer.empty[js.Any]
      val q = new SurferQueue(m => sent += m)
      test("latest of each kind, drained Load, Show, Focus") {
        q.offer(cmd("Show", 1)); q.offer(cmd("Focus", 1)); q.offer(cmd("Show", 2))
        q.offer(cmd("Load", 1)); q.offer(cmd("Focus", 2)); q.offer(cmd("Show", 3))
        assert(sent.isEmpty)
        val out = q.ready()
        assert(tags(out) == Seq("Load1", "Show3", "Focus2"), tags(sent.toSeq) == tags(out))
      }
      test("a Show before any Load never drops the Load") {
        q.offer(cmd("Show", 1)); q.offer(cmd("Load", 1)); q.offer(cmd("Show", 2))
        assert(tags(q.ready()) == Seq("Load1", "Show2"))
      }
      test("a new Load clears the queued Show and Focus of the old trace") {
        q.offer(cmd("Load", 1)); q.offer(cmd("Show", 1)); q.offer(cmd("Focus", 1)); q.offer(cmd("Load", 2))
        assert(tags(q.ready()) == Seq("Load2"))
      }
      test("a Show after its Load is kept") {
        q.offer(cmd("Load", 1)); q.offer(cmd("Show", 1)); q.offer(cmd("Load", 2)); q.offer(cmd("Show", 2))
        assert(tags(q.ready()) == Seq("Load2", "Show2"))
      }
      test("a Hello is queued as its own kind, drained after Focus") {
        q.offer(cmd("Hello", 1)); q.offer(cmd("Load", 1)); q.offer(cmd("Focus", 1)); q.offer(cmd("Show", 1))
        assert(sent.isEmpty)
        assert(tags(q.ready()) == Seq("Load1", "Show1", "Focus1", "Hello1"))
      }
      test("a Load keeps the queued Hello") {
        q.offer(cmd("Hello", 1)); q.offer(cmd("Load", 1))
        assert(tags(q.ready()) == Seq("Load1", "Hello1"))
      }
      test("once ready, a Hello passes through") {
        q.ready()
        q.offer(cmd("Hello", 2))
        assert(tags(sent.toSeq) == Seq("Hello2"))
      }
      test("the latest Load wins") {
        q.offer(cmd("Load", 1)); q.offer(cmd("Load", 2))
        assert(tags(q.ready()) == Seq("Load2"))
      }
      test("once ready, offers pass through") {
        q.ready()
        q.offer(cmd("Show", 1)); q.offer(cmd("Show", 2))
        assert(tags(sent.toSeq) == Seq("Show1", "Show2"))
        assert(q.ready().isEmpty)
      }
      test("reset queues again and keeps the queued Load") {
        // the panel's Load arrives before the tab is made, whose creation resets the queue
        q.offer(cmd("Load", 1))
        q.reset()
        q.offer(cmd("Show", 1))
        assert(sent.isEmpty, tags(q.ready()) == Seq("Load1", "Show1"))
        q.reset()
        q.offer(cmd("Show", 5))
        assert(tags(sent.toSeq) == Seq("Load1", "Show1"), tags(q.ready()) == Seq("Show5"))
      }
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
