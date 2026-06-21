package socless.layout

import socless.schema._

/** Headless debug renderer for the baked schematic geometry in design.json.
  *
  * Reads only what `Layout` already baked into `Design.layouts`; it never
  * recomputes layout. Produces a per-module debug SVG and a coordinate-rich
  * text dump. Pure - no browser, elkjs, or theme tokens. This is a debugging
  * aid for inspecting Layout output, NOT a second production renderer (the UI's
  * SVG export lives in socless-ui's Export.scala).
  */
object LayoutDebug {

  /** A coordinate-rich, deterministically-ordered text dump of one module's
    * baked layout. Echoes the readable `layout <Module>` style of the
    * structure-only golden (golden/layout/&lt;name&gt;.txt) but adds the x/y/w/h it omits. */
  def textFor(name: String, layout: ModuleLayout): String = {
    val sb = new StringBuilder
    sb.append(s"layout $name  canvas=${layout.w}x${layout.h}  ")
      .append(s"nodes=${layout.nodes.size} edges=${layout.edges.size}\n")
    for (n <- layout.nodes.sortBy(_.id)) {
      sb.append(s"  node ${n.id}  kind=${n.kind}  pos=(${n.x},${n.y}) size=${n.w}x${n.h}\n")
      for (p <- n.ports.sortBy(_.path)) {
        val iface = if (p.interface) " iface" else ""
        sb.append(s"    pin ${p.path}  ${p.side} (${p.x},${p.y})$iface\n")
      }
    }
    for (e <- layout.edges.sortBy(e => (e.from, e.to))) {
      val pts = e.points.map { case (x, y) => s"($x,$y)" }.mkString
      val lbl = if (e.label.nonEmpty) s"  ${e.label}" else ""
      sb.append(s"  edge ${e.from} -> ${e.to}  pts=$pts$lbl\n")
    }
    sb.toString
  }

  // SVG ---------------------------------------------------------------------

  // Fixed high-contrast debug palette (NOT the UI theme tokens), so the SVG is
  // self-contained with no CSS-var resolution.
  private val Palette: Map[String, String] = Map(
    "inst"     -> "#cfe3ff",
    "boundary" -> "#ffe7b3",
    "reg"      -> "#d8f5d0",
    "const"    -> "#eedcff",
    "op"       -> "#ffd6d6",
    "fsm"      -> "#d0eef5",
  )
  private val NodeStroke = "#333333"
  private val WestColor  = "#11aa66"
  private val EastColor  = "#0066cc"
  private val EdgeColor  = "#444444"
  private val GridColor  = "#eeeeee"
  private val TextColor  = "#222222"
  private val HeaderH    = 20

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /** A self-contained debug SVG of one module's baked layout. A `HeaderH`-tall
    * strip at the top carries the title + legend; all baked-coordinate content
    * is drawn in a group translated down by `HeaderH` so it never overlaps. */
  def svgFor(name: String, layout: ModuleLayout): String = {
    val w  = math.max(layout.w, 1)
    val h  = math.max(layout.h, 1)
    val sb = new StringBuilder
    sb.append(s"""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="${h + HeaderH}" """)
      .append(s"""viewBox="0 0 $w ${h + HeaderH}" font-family="monospace" font-size="10">""").append('\n')

    // Header strip: title + legend.
    sb.append(s"""<rect x="0" y="0" width="$w" height="$HeaderH" fill="#fafafa" stroke="#ddd"/>""").append('\n')
    sb.append(s"""<text x="4" y="14" fill="$TextColor">""")
      .append(esc(s"$name  ${layout.w}x${layout.h}  nodes=${layout.nodes.size} edges=${layout.edges.size}"))
      .append("</text>").append('\n')
    var lx = w - 6
    for (k <- Seq("fsm", "op", "const", "reg", "boundary", "inst").iterator) {
      lx -= (k.length * 6 + 16)
      sb.append(s"""<rect x="$lx" y="6" width="9" height="9" fill="${Palette(k)}" stroke="$NodeStroke"/>""").append('\n')
      sb.append(s"""<text x="${lx + 12}" y="14" fill="$TextColor">$k</text>""").append('\n')
    }

    // Content group, shifted below the header strip.
    sb.append(s"""<g transform="translate(0,$HeaderH)">""").append('\n')
    sb.append(s"""<rect x="0" y="0" width="$w" height="$h" fill="#ffffff" stroke="#bbbbbb"/>""").append('\n')

    // Grid every 50px for reading coordinates off the image.
    var gx = 50
    while (gx < w) { sb.append(s"""<line x1="$gx" y1="0" x2="$gx" y2="$h" stroke="$GridColor"/>""").append('\n'); gx += 50 }
    var gy = 50
    while (gy < h) { sb.append(s"""<line x1="0" y1="$gy" x2="$w" y2="$gy" stroke="$GridColor"/>""").append('\n'); gy += 50 }

    // Edges under the boxes: polyline + source dot + sink arrowhead.
    sb.append(s"""<g stroke="$EdgeColor" stroke-width="1.5" fill="none">""").append('\n')
    for (e <- layout.edges if e.points.nonEmpty) {
      val pts = e.points.map { case (x, y) => s"$x,$y" }.mkString(" ")
      sb.append(s"""<polyline points="$pts"/>""").append('\n')
      val (sx, sy) = e.points.head
      sb.append(s"""<circle cx="$sx" cy="$sy" r="2.5" fill="$EdgeColor" stroke="none"/>""").append('\n')
      sb.append(arrowHead(e.points))
    }
    sb.append("</g>").append('\n')

    // Boxes + pins on top.
    for (n <- layout.nodes) {
      val fill = Palette.getOrElse(n.kind, "#eeeeee")
      sb.append(s"""<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" fill="$fill" stroke="$NodeStroke"/>""").append('\n')
      sb.append(s"""<text x="${n.x + 3}" y="${n.y + 11}" fill="$TextColor">""")
        .append(esc(s"${n.id} (${n.x},${n.y} ${n.w}x${n.h})")).append("</text>").append('\n')
      for (p <- n.ports) {
        val col = if (p.side == "WEST") WestColor else EastColor
        val r   = if (p.interface) "4" else "2.5"
        sb.append(s"""<circle cx="${p.x}" cy="${p.y}" r="$r" fill="$col"/>""").append('\n')
        val (tx, anchor) = if (p.side == "WEST") (p.x + 5, "start") else (p.x - 5, "end")
        val lbl = if (p.label.nonEmpty) p.label else p.path.split('.').lastOption.getOrElse(p.path)
        sb.append(s"""<text x="$tx" y="${p.y + 3}" text-anchor="$anchor" fill="$col">""")
          .append(esc(lbl)).append("</text>").append('\n')
      }
    }

    sb.append("</g>").append('\n')   // content group
    sb.append("</svg>").append('\n')
    sb.toString
  }

  // File dump ---------------------------------------------------------------

  /** Resolve each module to its (possibly shared) baked layout and write
    * per-module files into `outDir`. `format` is "svg", "text", or "both".
    * `moduleFilter`, when set, limits output to that one module name. Modules
    * with no baked layout are skipped (and counted to stderr). Returns the
    * paths written, in module order. */
  def dump(design: Design, outDir: os.Path,
           moduleFilter: Option[String], format: String): Seq[os.Path] = {
    os.makeDir.all(outDir)
    val wantSvg  = format == "svg"  || format == "both"
    val wantText = format == "text" || format == "both"
    val mods     = design.modules.filter(m => moduleFilter.forall(_ == m.name))
    val written  = scala.collection.mutable.ArrayBuffer.empty[os.Path]
    var skipped  = 0
    for (m <- mods) {
      val key = design.layoutOf.getOrElse(m.name, m.name)
      design.layouts.get(key) match {
        case Some(layout) =>
          if (wantSvg)  { val p = outDir / s"${safe(m.name)}.svg"; os.write.over(p, svgFor(m.name, layout));  written += p }
          if (wantText) { val p = outDir / s"${safe(m.name)}.txt"; os.write.over(p, textFor(m.name, layout)); written += p }
        case None => skipped += 1
      }
    }
    if (skipped > 0)
      Console.err.println(s"[socless] debug-layout: skipped $skipped module(s) with no baked layout")
    written.toSeq
  }

  /** Make a module name safe to use as a filename (dots, brackets, etc to _). */
  private def safe(name: String): String = name.replaceAll("[^A-Za-z0-9._-]", "_")

  /** A small filled arrowhead at the last point, oriented along the final
    * segment so wire direction is visible. Empty string for a zero-length tail. */
  private def arrowHead(pts: Seq[(Int, Int)]): String = {
    if (pts.size < 2) return ""
    val (tx, ty) = pts.last
    val (px, py) = pts(pts.size - 2)
    val dx = (tx - px).toDouble; val dy = (ty - py).toDouble
    val len = math.hypot(dx, dy)
    if (len < 0.001) return ""
    val ux = dx / len; val uy = dy / len           // unit direction
    val nx = -uy; val ny = ux                       // unit normal
    val size = 6.0
    val bx = tx - ux * size; val by = ty - uy * size
    // Round to integer pixels: sub-pixel precision is meaningless for a 6px
    // glyph, and it keeps formatting locale-independent (a `%.1f` here emits a
    // comma decimal under fr_FR, which corrupts the SVG points attribute).
    val x1 = math.round(bx + nx * size / 2); val y1 = math.round(by + ny * size / 2)
    val x2 = math.round(bx - nx * size / 2); val y2 = math.round(by - ny * size / 2)
    s"""<polygon points="$tx,$ty $x1,$y1 $x2,$y2" fill="$EdgeColor" stroke="none"/>""" + "\n"
  }
}
