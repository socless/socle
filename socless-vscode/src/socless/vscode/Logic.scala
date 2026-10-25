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

  /** The webview page: a CSP allowing only `frameUrl`'s origin as a frame, and one full-size iframe on it. */
  def webviewHtml(frameUrl: String, cspSource: String): String = {
    val origin = OriginRe.findFirstMatchIn(frameUrl).map(_.group(1)).getOrElse("'none'")
    val csp    = s"default-src 'none'; frame-src $origin; style-src $cspSource 'unsafe-inline';"
    s"""<!DOCTYPE html>
       |<html>
       |<head>
       |<meta charset="UTF-8">
       |<meta http-equiv="Content-Security-Policy" content="${escapeHtml(csp)}">
       |<style>html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; } iframe { border: 0; width: 100%; height: 100%; display: block; }</style>
       |</head>
       |<body>
       |<iframe src="${escapeHtml(frameUrl)}"></iframe>
       |</body>
       |</html>
       |""".stripMargin
  }
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
