package socless.vscode

import scala.scalajs.js

/** The extension's pure decisions. No `vscode` import, so it runs under plain Node in tests. */
object Logic {

  /** A setting's value, or None when it is unset: absent, `null` (what `get` can return), or blank. */
  private def nonBlank(s: Option[String]): Option[String] = s.flatMap(Option(_)).map(_.trim).filter(_.nonEmpty)

  /** The java to launch: the setting if non-blank, else `$JAVA_HOME/bin/java`, else `java` on the PATH. */
  def javaCommand(setting: Option[String], javaHome: Option[String]): String =
    nonBlank(setting).orElse(nonBlank(javaHome).map(_ + "/bin/java")).getOrElse("java")

  /** The language server jar: the setting if non-blank, else the one bundled in the extension. */
  def serverJar(setting: Option[String], extensionDir: String): String =
    nonBlank(setting).getOrElse(extensionDir + "/server/socless-lsp.jar")

  private val VersionRe = """version "([0-9][^"]*)"""".r

  /** The major Java version from `java -version` output: `"1.8.0_402"` is 8, `"21.0.12"` is 21. */
  def parseJavaMajor(versionOutput: String): Option[Int] =
    VersionRe.findFirstMatchIn(versionOutput).flatMap { m =>
      val parts = m.group(1).split("[^0-9]+")
      val major = if (parts.length > 1 && parts(0) == "1") parts(1) else parts(0)
      major.toIntOption
    }

  /** The minimum Java the language server runs on. */
  val MinJava = 21

  /** Why `javaCmd` cannot run the server (`major` is None when it did not run or did not parse), or None if it can. */
  def javaProblem(javaCmd: String, major: Option[Int]): Option[String] = major match {
    case Some(m) if m >= MinJava => None
    case Some(m) =>
      Some(s"SoCLess needs Java $MinJava or newer, but '$javaCmd' is Java $m. Set the socless.javaPath setting to a Java $MinJava+ executable, then reload the window.")
    case None =>
      Some(s"SoCLess needs Java $MinJava or newer, but '$javaCmd' could not be run. Set the socless.javaPath setting to a Java $MinJava+ executable, then reload the window.")
  }

  /** The language server's JVM arguments. An IPv4-only stack: by default Java binds 127.0.0.1 as an
    * IPv4-mapped address on an IPv6 socket, which WSL's localhost relay forwards to `::1` in WSL,
    * where nothing listens, so a Windows VS Code could not load the WSL server's panel (an empty
    * panel). An IPv4 socket is relayed to 127.0.0.1.
    */
  def serverArgs(jar: String): Seq[String] = Seq("-Djava.net.preferIPv4Stack=true", "-jar", jar)

  /** The language server's `initializationOptions`: the client-hosted panel, plus the Simulate
    * settings `waveFlag` and `surferCommand` when set (else the server's defaults apply).
    */
  def initializationOptions(waveFlag: Option[String], surferCommand: Option[String]): js.Object = {
    val o = js.Dynamic.literal(panel = "client")
    nonBlank(waveFlag).foreach(o.updateDynamic("waveFlag")(_))
    nonBlank(surferCommand).foreach(o.updateDynamic("surferCommand")(_))
    o
  }

  /** The `socless/cursor` notification params. */
  def cursorParams(uri: String, line: Int): js.Object =
    js.Dynamic.literal(uri = uri, line = line)

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

  private val OriginRe = """^([A-Za-z][A-Za-z0-9+.-]*://[^/?#\s"'<>;]+)""".r

  /** The UI's theme kind for a `vscode.ColorThemeKind` (1 Light, 2 Dark, 3 HighContrast,
    * 4 HighContrastLight): `light`, `dark` or `hc`. High contrast light falls back to `light`.
    */
  def themeKind(kind: Int): String = kind match {
    case 1 | 4 => "light"
    case 3     => "hc"
    case _     => "dark"
  }

  /** The server's UI URL with the embedded-mode query: `host=vscode` and the initial theme kind. */
  def panelUrl(url: String, theme: String): String = withQuery(url, s"host=vscode&theme=$theme")

  /** The server's UI URL for the Surfer tab page: `host=vscode&view=surfer` and the initial theme kind. */
  def surferUrl(serverUrl: String, themeKind: String): String =
    withQuery(serverUrl, s"host=vscode&view=surfer&theme=$themeKind")

  private def withQuery(url: String, query: String): String = {
    val (base, frag) = url.indexOf('#') match {
      case -1 => (url, "")
      case i  => (url.substring(0, i), url.substring(i))
    }
    val sep = if (base.contains("?")) "&" else "?"
    s"$base$sep$query$frag"
  }

  private val TitleMax = 60

  /** The panel's tab title: `SoCLess: <test>`, the test name cut to 60 characters (the last one "…"). */
  def panelTitle(test: Option[String]): String =
    nonBlank(test).fold("SoCLess design")(t => s"SoCLess: ${shortTest(t)}")

  /** The Surfer tab's title: `Surfer: <test>`, cut as in [[panelTitle]], or `Surfer`. */
  def surferTitle(test: Option[String]): String =
    nonBlank(test).fold("Surfer")(t => s"Surfer: ${shortTest(t)}")

  private def shortTest(t: String): String =
    if (t.length <= TitleMax) t
    else {
      val cut = t.substring(0, TitleMax - 1)
      (if (Character.isHighSurrogate(cut.last)) cut.init else cut) + "…"
    }

  /** `vscode.ViewColumn.Beside`: the column beside the active one. */
  val ViewColumnBeside = -2

  /** The column a new Surfer tab opens in: the one after the SoCLess panel's (`panel`), so it never
    * covers the schematic; beside the active editor when there is no panel. */
  def surferColumn(panel: Option[Int]): Int = panel.fold(ViewColumnBeside)(_ + 1)

  /** The column `Panel.show` reveals the SoCLess panel in: its own (`existing`), so it never moves
    * (Beside is relative to the active editor, which may be the Surfer tab); beside the active editor
    * for a fresh panel. */
  def panelRevealColumn(existing: Option[Int]): Int = existing.getOrElse(ViewColumnBeside)

  /** Why `socless.showWaves` has nothing to show: the viewer is not `surfer-panel` (`viewer`, None
    * when unset, whose default is `surfer`), or no Simulate has given the panel a trace yet
    * (`hasTrace`, see [[SurferOpens.traced]]; a View design alone has none). None when it can. */
  def showWavesProblem(viewer: Option[String], hasTrace: Boolean): Option[String] =
    if (!nonBlank(viewer).contains("surfer-panel"))
      Some("SoCLess: the Surfer tab is for the surfer-panel viewer. Set the socless.waveViewer setting to surfer-panel, then Simulate.")
    else if (!hasTrace) Some("SoCLess: Run Simulate first.")
    else None

  /** The test the Surfer tab is titled after: the test part of the trace's `traceTest`
    * (`<suite>#<test>`, cut at the first `#`; the whole of it when the server sends no suite), so
    * the title follows the trace, not the panel's view; `fallback` (the panel's test) when it is
    * absent or blank. */
  def surferTest(traceTest: Option[String], fallback: Option[String]): Option[String] =
    nonBlank(traceTest).map(t => t.indexOf('#') match {
      case -1 => t
      case i  => t.substring(i + 1)
    }).flatMap(t => nonBlank(Some(t))).orElse(fallback)

  /** A `socless-surfer-cmd`'s command kind (`cmd.$type`), "" when it has none. */
  def cmdKind(msg: js.Any): String =
    if (msg == null || js.typeOf(msg) != "object") ""
    else {
      val cmd = msg.asInstanceOf[js.Dynamic].cmd
      if (cmd == null || js.typeOf(cmd) != "object") "" else (cmd.selectDynamic("$type"): Any) match {
        case t: String => t
        case _         => ""
      }
    }

  /** Whether the extension answers the panel's command `msg` itself with `socless-surfer-closed`:
    * a `Hello` while there is no Surfer tab (`tabOpen`), so a reloaded panel shows the tab closed.
    * With a tab, ready or not, the Hello goes to it (see [[SurferQueue]]). */
  def answersClosed(msg: js.Any, tabOpen: Boolean): Boolean = !tabOpen && cmdKind(msg) == "Hello"

  /** Where a webview's Surfer message goes: a `socless-surfer-cmd` from the SoCLess panel
    * (`from = "panel"`) to the Surfer tab (`"surfer"`), a `socless-surfer-status` from the tab to
    * the panel. None for anything else, a wrong direction included (`socless-surfer-open` is the
    * extension's own business, see [[SurferOpens]]).
    */
  def route(from: String, msg: js.Any): Option[(String, js.Any)] = (from, msgType(msg)) match {
    case ("panel", Some("socless-surfer-cmd"))     => Some(("surfer", msg))
    case ("surfer", Some("socless-surfer-status")) => Some(("panel", msg))
    case _                                         => None
  }

  /** A message's `type`, when it is an object with a string `type`. */
  def msgType(msg: js.Any): Option[String] =
    if (msg == null || js.typeOf(msg) != "object") None
    else (msg.asInstanceOf[js.Dynamic].selectDynamic("type"): Any) match {
      case t: String => Some(t)
      case _         => None
    }

  /** The hex form of random bytes, for a CSP nonce. */
  def hex(bytes: Seq[Int]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** The webview page: a CSP allowing only `frameUrl`'s origin as a frame and only the `nonce` script,
    * one full-size iframe on it, and a script that forwards VS Code's theme kind (the `vscode-*` body
    * classes) to the iframe as `{type: "socless-theme", kind}`, on load and on every theme change.
    * The script also relays the side panel channel: a `socless-state` from the iframe (its window
    * and origin) to the extension, and a `socless-navigate` from the extension to the iframe origin.
    * Both the SoCLess panel and the Surfer tab use it, so it relays the Surfer channel too:
    * `socless-surfer-open`, `-cmd` and `-status` up from the iframe, `-cmd`, `-status` and
    * `-closed` down to it. Each tab's page posts its own `socless-theme`.
    * Without a frame origin there is no script.
    */
  def webviewHtml(frameUrl: String, cspSource: String, nonce: String): String = {
    val origin = OriginRe.findFirstMatchIn(frameUrl).map(_.group(1))
    val csp = s"default-src 'none'; frame-src ${origin.getOrElse("'none'")}; style-src $cspSource 'unsafe-inline';" +
      origin.fold("")(_ => s" script-src 'nonce-$nonce';")
    // OriginRe excludes quotes, backslashes and angle brackets, so the origin is a safe JS string literal.
    val script = origin.fold("") { o =>
      s"""<script nonce="${escapeHtml(nonce)}">
         |(function () {
         |  var frame = document.querySelector('iframe');
         |  function kind() {
         |    var c = document.body.classList;
         |    if (c.contains('vscode-high-contrast-light')) return 'light';
         |    if (c.contains('vscode-high-contrast')) return 'hc';
         |    if (c.contains('vscode-dark')) return 'dark';
         |    return 'light';
         |  }
         |  var last = null;
         |  function post(force) {
         |    var k = kind();
         |    if (!force && k === last) return;
         |    last = k;
         |    if (frame.contentWindow) frame.contentWindow.postMessage({ type: 'socless-theme', kind: k }, "$o");
         |  }
         |  new MutationObserver(function () { post(false); }).observe(document.body, { attributes: true, attributeFilter: ['class'] });
         |  frame.addEventListener('load', function () { post(true); });
         |  // The side panel channel: the UI's socless-state goes up to the extension, the
         |  // extension's socless-navigate goes down to the UI (its origin only). The Surfer
         |  // channel, between the SoCLess panel and the Surfer tab through the extension:
         |  // socless-surfer-open, -cmd and -status go up; -cmd, -status and -closed go down.
         |  var up = ['socless-state', 'socless-surfer-open', 'socless-surfer-cmd', 'socless-surfer-status'];
         |  var host = acquireVsCodeApi();
         |  window.addEventListener('message', function (e) {
         |    var d = e.data;
         |    if (!d || typeof d !== 'object') return;
         |    if (e.source === frame.contentWindow) {
         |      if (e.origin === "$o" && up.indexOf(d.type) >= 0) host.postMessage(d);
         |    } else if (e.source && e.origin === window.origin && frame.contentWindow) {
         |      // From VS Code's webview host, which delivers the extension's messages: a window of this
         |      // page's own origin (the webview's). Not window.parent: VS Code 1.143 posts from a window
         |      // that is neither the parent, the top nor one of the parent's frames (seen in the e2e).
         |      // Each is rebuilt with only its own fields.
         |      var m = null;
         |      if (d.type === 'socless-navigate')
         |        m = { type: d.type, path: d.path, select: d.select === undefined ? null : d.select };
         |      else if (d.type === 'socless-surfer-cmd') m = { type: d.type, cmd: d.cmd };
         |      else if (d.type === 'socless-surfer-status') m = { type: d.type, status: d.status };
         |      else if (d.type === 'socless-surfer-closed') m = { type: d.type };
         |      if (m) frame.contentWindow.postMessage(m, "$o");
         |    }
         |  });
         |})();
         |</script>
         |""".stripMargin
    }
    s"""<!DOCTYPE html>
       |<html>
       |<head>
       |<meta charset="UTF-8">
       |<meta http-equiv="Content-Security-Policy" content="${escapeHtml(csp)}">
       |<style>html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; } iframe { border: 0; width: 100%; height: 100%; display: block; }</style>
       |</head>
       |<body>
       |<iframe src="${escapeHtml(frameUrl)}"></iframe>
       |$script</body>
       |</html>
       |""".stripMargin
  }
}

/** One panel's webview page. The nonce and the initial theme kind are fixed when the panel is made, so
  * the html changes only with the server URL: setting the same html again does not reload the iframe,
  * and the UI keeps its state (drill path, zoom, open panels). The page script posts the current theme
  * kind on every iframe load anyway.
  */
final class PanelPage(nonce: String, themeKind: String) {
  def html(serverUrl: String, cspSource: String): String =
    Logic.webviewHtml(Logic.panelUrl(serverUrl, themeKind), cspSource, nonce)
}

/** Runs only the last `apply` of a burst, `delayMs` after it. `schedule`/`cancel` are
  * injected (production: `js.timers.setTimeout`/`clearTimeout`; tests: a fake clock).
  */
final class Debouncer(delayMs: Int, schedule: (Int, () => Unit) => Any, cancel: Any => Unit) {
  private var pending: Option[Any] = None

  def apply(f: () => Unit): Unit = {
    pending.foreach(cancel)
    pending = Some(schedule(delayMs, () => { pending = None; f() }))
  }
}

/** The `socless-surfer-cmd` messages for a Surfer tab that has not reported yet. Until [[ready]],
  * [[offer]] keeps only the latest message of each command kind (`cmd.$type`), and a Load clears
  * the queued Show and Focus (they were of the previous trace); [[ready]] sends them
  * (`send`) in the order Load, Show, Focus, Hello (then any other kind) and returns them; after it, [[offer]]
  * sends at once. [[reset]] (a new or closed tab) queues again; it keeps what is queued, since the
  * panel's commands can arrive before the tab is made (a Load is never dropped).
  */
final class SurferQueue(send: js.Any => Unit) {
  private var isReady = false
  private var queued  = Map.empty[String, js.Any]

  private def rank(kind: String): Int = kind match {
    case "Load"  => 0
    case "Show"  => 1
    case "Focus" => 2
    case "Hello" => 3 // after the rest: the status it asks for reports them
    case _       => 4
  }

  def offer(cmd: js.Any): Unit =
    if (isReady) send(cmd)
    else {
      val k = Logic.cmdKind(cmd)
      // a new trace: the queued Show and Focus were of the old one
      if (k == "Load") queued = queued -- Seq("Show", "Focus")
      queued += k -> cmd
    }

  def ready(): Seq[js.Any] = {
    val out = queued.toSeq.sortBy { case (k, _) => (rank(k), k) }.map(_._2)
    queued = Map.empty
    isReady = true
    out.foreach(send)
    out
  }

  def reset(): Unit = isReady = false
}

/** Which `socless-surfer-open` requests open or reveal the Surfer tab: a forced one (the Show
  * Surfer button, `socless.showWaves`) always; an unforced one (a new trace in the panel) only for a
  * trace not opened before in this window, so a reloaded or reopened panel does not bring back a
  * tab the user closed.
  */
final class SurferOpens {
  private var seen = Set.empty[String]

  /** Whether any request so far carried a trace (a Simulate gave the panel one). */
  def traced: Boolean = seen.nonEmpty

  def admit(trace: String, force: Boolean): Boolean = {
    val ok = force || (trace.nonEmpty && !seen.contains(trace))
    if (trace.nonEmpty) seen += trace
    ok
  }
}

/** At most `max` restarts in any `windowMs` window; a refused attempt is not counted. */
final class RestartBudget(max: Int, windowMs: Double) {
  private var stamps = Vector.empty[Double]

  def allow(nowMs: Double): Boolean = {
    stamps = stamps.filter(t => nowMs - t < windowMs)
    val ok = stamps.size < max
    if (ok) stamps :+= nowMs
    ok
  }
}
