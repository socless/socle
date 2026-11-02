package socless.schema

/** The scaladoc of a module and of its port fields, read from the sources a
  * design embeds (`Design.sources`). Pure and shared with the UI, which shows
  * it in the Inspector and on the pin cards. Best effort: anything it cannot
  * find is "", never an error. */
object Scaladoc {

  /** The module's doc: its declared `doc`, else the `/** ... */` above its class. */
  def ofModule(m: ModuleDef, design: Design): String =
    if (m.doc.nonEmpty) m.doc
    else linesOf(m.source.file, design).fold("")(ls => above(ls, m.source.line))

  /** The doc of a field of module `m`'s ports, by its path below the module:
    * `Seq("io", "in")`, or deeper through bundles (`Seq("io", "in", "bits", "addr")`).
    * A Decoupled's `bits` and Vec indexes are walked through. */
  def ofField(m: ModuleDef, path: Seq[String], design: Design): String = {
    val segs = path.map(stripIdx).filter(s => s.nonEmpty && s != "bits")
    val res = for {
      port  <- segs.headOption.flatMap(p => m.ports.find(_.name == p))
      file   = if (port.source.file.nonEmpty) port.source.file else m.source.file
      lines <- linesOf(file, design)
      start <- if (port.source.file.nonEmpty && port.source.line > 0) Some(port.source.line)
               else findVal(lines, port.name, m.source.line, stopAtClass = true)
      decl  <- segs.tail.foldLeft(Option(start)) { (cur, s) => cur.flatMap(c => fieldIn(lines, c, s)) }
    } yield above(lines, decl)
    res.getOrElse("")
  }

  private def linesOf(file: String, design: Design): Option[IndexedSeq[String]] =
    if (file.isEmpty) None else design.sources.get(file).map(_.linesIterator.toIndexedSeq)

  private def stripIdx(s: String): String = s.takeWhile(_ != '[')

  private val NewClass = """\bnew\s+([A-Za-z_][A-Za-z0-9_]*)""".r

  /** The declaration line (1-based) of field `name` of the bundle the line
    * `decl` declares: an anonymous `new Bundle { ... }` that opens there, or a
    * named bundle class (`new Req`) found in the same file. */
  private def fieldIn(lines: IndexedSeq[String], decl: Int, name: String): Option[Int] = {
    val text = lines.lift(decl - 1).getOrElse("")
    if (text.contains("new Bundle")) findVal(lines, name, decl + 1, stopAtClass = true)
    else NewClass.findAllMatchIn(text).map(_.group(1)).toSeq.lastOption.flatMap { cls =>
      val classRe = ("""^\s*(?:final\s+|case\s+|abstract\s+)*class\s+""" + cls + """\b""").r
      val at = lines.indexWhere(l => classRe.findFirstIn(l).isDefined)
      if (at < 0) None else findVal(lines, name, at + 2, stopAtClass = true)
    }
  }

  /** The first line (1-based), from `from` on, that declares `val <name>`; with
    * `stopAtClass`, not past the next top-level class. */
  private def findVal(lines: IndexedSeq[String], name: String, from: Int, stopAtClass: Boolean): Option[Int] = {
    val valRe = ("""\bval\s+""" + java.util.regex.Pattern.quote(name) + """\b""").r
    var i = math.max(0, from - 1)
    while (i < lines.length) {
      val l = lines(i)
      if (valRe.findFirstIn(l).isDefined) return Some(i + 1)
      if (stopAtClass && i > from - 1 && (l.startsWith("class ") || l.startsWith("object ") || l.startsWith("trait "))) return None
      i += 1
    }
    None
  }

  /** The `/** ... */` that ends just above line `line` (1-based), past any
    * annotations, cleaned: paragraphs kept, lines of a paragraph joined. */
  private def above(lines: IndexedSeq[String], line: Int): String = {
    var i = line - 2
    while (i >= 0 && lines(i).trim.startsWith("@")) i -= 1
    if (i < 0 || !lines(i).trim.endsWith("*/")) return ""
    var j = i
    while (j >= 0 && !lines(j).contains("/*")) j -= 1
    if (j < 0 || !lines(j).contains("/**")) return ""
    val body = lines.slice(j, i + 1).mkString("\n")
    val inner = body.substring(body.indexOf("/**") + 3, body.lastIndexOf("*/"))
    val cleaned = inner.linesIterator.map(_.trim.stripPrefix("*").trim).toSeq
    cleaned.foldLeft(Vector.empty[Vector[String]]) { (paras, l) =>
      if (l.isEmpty) (if (paras.lastOption.exists(_.nonEmpty)) paras :+ Vector.empty else paras)
      else if (paras.isEmpty) Vector(Vector(l))
      else paras.init :+ (paras.last :+ l)
    }.filter(_.nonEmpty).map(_.mkString(" ")).mkString("\n\n")
  }
}
