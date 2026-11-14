package socle.ui.model

/** Which lines of a Scala source file use a name, for the Source tab: a name
  * in a comment or a string literal is not a use. */
object SourceUses {

  /** The file's lines with every comment (`//`, nested `/* */`, Scaladoc) and
    * string literal (`"..."`, `"""..."""`) blanked to spaces, so a name there
    * matches nothing and two words around a comment do not join. A small
    * state scan, carried across lines for block comments and triple quotes. */
  def codeOnly(lines: IndexedSeq[String]): IndexedSeq[String] = {
    var depth  = 0     // open block comments (Scala nests them)
    var triple = false // inside a """ string
    lines.map { l =>
      val out = new StringBuilder(l.length)
      val n   = l.length
      def blank(k: Int): Unit = out ++= " " * k
      var i = 0
      while (i < n) {
        if (depth > 0) {
          if (l.startsWith("*/", i)) { depth -= 1; blank(2); i += 2 }
          else if (l.startsWith("/*", i)) { depth += 1; blank(2); i += 2 }
          else { blank(1); i += 1 }
        } else if (triple) {
          if (l.startsWith("\"\"\"", i)) {
            var e = i + 3 // a """ string may end in more quotes: """a""""
            while (e < n && l(e) == '"') e += 1
            triple = false; blank(e - i); i = e
          } else { blank(1); i += 1 }
        } else if (l.startsWith("//", i)) { blank(n - i); i = n }
        else if (l.startsWith("/*", i)) { depth = 1; blank(2); i += 2 }
        else if (l.startsWith("\"\"\"", i)) { triple = true; blank(3); i += 3 }
        else if (l(i) == '"') {
          var e = i + 1
          while (e < n && l(e) != '"') e += (if (l(e) == '\\') 2 else 1)
          e = (e + 1).min(n)
          blank(e - i); i = e
        } else if (l(i) == '\'' && i + 3 < n && l(i + 1) == '\\' && l(i + 3) == '\'') { blank(4); i += 4 } // '\''
        else if (l(i) == '\'' && i + 2 < n && l(i + 2) == '\'') { blank(3); i += 3 } // '"'
        else { out += l(i); i += 1 }
      }
      out.result()
    }
  }

  /** The 1-based numbers of the lines whose code contains one of `names`, as
    * a plain substring (no word boundary). */
  def lines(code: IndexedSeq[String], names: Seq[String]): Seq[Int] =
    code.indices.collect { case i if names.exists(code(i).contains(_)) => i + 1 }
}
