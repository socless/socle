package socless.ui.schematic

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** Schematic export (epic #172 Phase 5): serialize the schematic content into a
  * standalone SVG, rasterize it to PNG, and snapshot the whole page into a
  * self-contained HTML file. Ports the JS `buildExportSvg` / `exportPng` /
  * `exportSnapshot`. */
object Export {

  private val VarRe = """var\((--[\w-]+)""".r

  private def blob(parts: String, mime: String): dom.Blob =
    new dom.Blob(js.Array[dom.BlobPart](parts), js.Dynamic.literal(`type` = mime).asInstanceOf[dom.BlobPropertyBag])

  private def downloadBlob(filename: String, b: dom.Blob): Unit = {
    val a = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    val url = dom.URL.createObjectURL(b)
    a.href = url; a.setAttribute("download", filename); a.click()
    dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 2000)
  }

  /** Serialize `node` into a standalone SVG string sized `w` x `h`. The UI styles
    * everything inline, so a foreignObject wrapper preserves appearance; every
    * `var(--x)` referenced is resolved from the live theme and re-declared on the
    * wrapper (a detached SVG cannot see :root). */
  private def buildSvg(node: dom.html.Element, w: Int, h: Int): String = {
    val clone = node.cloneNode(true).asInstanceOf[dom.html.Element]
    clone.style.transform = "none"
    clone.style.transition = "none"
    clone.setAttribute("xmlns", "http://www.w3.org/1999/xhtml")
    val xhtml = new dom.XMLSerializer().serializeToString(clone)
    val rootStyle = dom.window.getComputedStyle(dom.document.documentElement)
    val varDecls = VarRe.findAllMatchIn(xhtml).map(_.group(1)).toSet.toSeq
      .map((v: String) => s"$v: ${rootStyle.getPropertyValue(v).trim};").mkString(" ")
    val bg = { val b = rootStyle.getPropertyValue("--bg").trim; if (b.nonEmpty) b else "#fbfbfa" }
    s"""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""" +
      s"""<rect width="100%" height="100%" fill="$bg"/>""" +
      s"""<foreignObject width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml" style="$varDecls position: relative; width: ${w}px; height: ${h}px; font-family: ui-monospace, monospace;">""" +
      xhtml +
      "</div></foreignObject></svg>"
  }

  def exportSvg(node: dom.html.Element, w: Int, h: Int, name: String): Unit =
    downloadBlob(s"socless-$name.svg", blob(buildSvg(node, w, h), "image/svg+xml"))

  /** Rasterize the SVG to a 2x PNG. Falls back to SVG if the browser cannot
    * rasterize the foreignObject (e.g. WebKit produces a blank canvas). */
  def exportPng(node: dom.html.Element, w: Int, h: Int, name: String): Unit = {
    val svg = buildSvg(node, w, h)
    val img = dom.document.createElement("img").asInstanceOf[dom.html.Image]
    img.onload = (_: dom.Event) => {
      try {
        val scale = 2
        val c = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
        c.width = w * scale; c.height = h * scale
        val ctx = c.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
        ctx.scale(scale, scale)
        ctx.drawImage(img, 0, 0)
        c.asInstanceOf[js.Dynamic].toBlob((b: dom.Blob) =>
          if (b != null) downloadBlob(s"socless-$name.png", b)
          else exportSvg(node, w, h, name), "image/png")
      } catch { case _: Throwable => exportSvg(node, w, h, name) }
    }
    img.asInstanceOf[js.Dynamic].onerror = ((_: dom.Event) => exportSvg(node, w, h, name)): js.Function1[dom.Event, Unit]
    img.src = "data:image/svg+xml;charset=utf-8," + js.URIUtils.encodeURIComponent(svg)
  }

  /** Save a self-contained HTML snapshot: refetch this page and inline the active
    * design as an embedded-design block the boot loader recognizes, so it opens
    * from file:// with full interactivity. */
  def snapshot(rawJson: String, top: String): Unit = {
    // Tag needles are split so the page's own code does not match itself.
    val OPEN  = "<scr" + "ipt type=\"application/json\" id=\"socless-embedded-design\">"
    val CLOSE = "<" + "/script>"
    dom.fetch(dom.window.location.pathname, js.Dynamic.literal(cache = "no-store").asInstanceOf[dom.RequestInit])
      .flatMap(r => r.text())
      .foreach { html0 =>
        var html = html0
        val at0 = html.indexOf(OPEN)
        if (at0 >= 0) { val end0 = html.indexOf(CLOSE, at0); if (end0 >= 0) html = html.substring(0, at0) + html.substring(end0 + CLOSE.length) }
        val json  = rawJson.replace("</script", "<\\/script")
        val block = OPEN + json + CLOSE + "\n"
        val at = html.indexOf("<script")
        if (at >= 0) {
          val out = html.substring(0, at) + block + html.substring(at)
          downloadBlob(s"${if (top.nonEmpty) top else "design"}-snapshot.html", blob(out, "text/html"))
        }
      }
  }
}
