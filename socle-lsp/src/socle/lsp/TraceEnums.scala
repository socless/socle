package socle.lsp

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.StandardOpenOption.{CREATE, READ, TRUNCATE_EXISTING, WRITE}
import scala.util.control.NonFatal
import socle.wave.WaveEnums

/** Puts FSM state names in a copy of a trace, so Surfer shows `sGreen`
  * where the trace has `1` ([[WaveEnums]] says which registers and names).
  *
  * VCD: before each state register's `$var`, a GTKWave enum table and its
  * reference (`$attrbegin misc 07 <name> <n> <names...> <values...> <id> $end`,
  * then `$attrbegin misc 07 "" <id> $end`), which Surfer's trace reader
  * (wellen) attaches to that variable; its Enum translator is then the
  * default for it. Values are bit strings of the variable's width. A
  * register of at most [[FillBits]] bits also names every other value by its
  * number, so a value no state has (before the reset) reads as a number,
  * not as Surfer's error. Only the header changes: the value changes are
  * copied as they are.
  *
  * FST: the same tables as hierarchy attributes (252, misc, subtype 7: the
  * table's text `<name> <n> <names...> <values...>` with its id, then an
  * empty one with the id) before each register's var entry. The hierarchy
  * block (gzip, LZ4 or LZ4 twice; Verilator writes LZ4) is read, patched and
  * written back as a gzip block; every other block is copied as it is. A
  * whole-file gzip FST (a ZWRAPPER block) is left alone. */
object TraceEnums {
  val FillBits = 8
  private val MaxHeader = 64 * 1024 * 1024

  /** Writes `in` with the tables of `tables` to `out`. Returns how many
    * variables got one; 0 (and nothing written) when none matched, the
    * format is not VCD, or the header cannot be read. */
  def write(in: os.Path, out: os.Path, tables: Seq[WaveEnums.Table], log: String => Unit = _ => ()): Int =
    if (tables.isEmpty) 0
    else if (in.ext.equalsIgnoreCase("fst")) writeFst(in, out, tables.map(t => t.trace -> t).toMap, log)
    else if (!in.ext.equalsIgnoreCase("vcd")) 0
    else try {
      val byTrace = tables.map(t => t.trace -> t).toMap
      headerEnd(in) match {
        case None => log(s"trace state names: no VCD header end in $in"); 0
        case Some(end) =>
          val header = {
            val ch = FileChannel.open(in.toNIO, READ)
            try { val b = java.nio.ByteBuffer.allocate(end); while (b.hasRemaining && ch.read(b) >= 0) {}; new String(b.array, ISO_8859_1) }
            finally ch.close()
          }
          val (patched, n) = patchHeader(header, byTrace)
          if (n > 0) {
            val src = FileChannel.open(in.toNIO, READ)
            val dst = FileChannel.open(out.toNIO, CREATE, WRITE, TRUNCATE_EXISTING)
            try {
              dst.write(java.nio.ByteBuffer.wrap(patched.getBytes(ISO_8859_1)))
              var pos = end.toLong
              val size = src.size
              while (pos < size) pos += src.transferTo(pos, size - pos, dst)
            } finally { src.close(); dst.close() }
          }
          n
      }
    } catch { case NonFatal(e) => log(s"trace state names: $e"); 0 }

  /** The header with a table before each named `$var`, and how many. Lines
    * are kept as they are; a `$scope`, `$upscope` or `$var` command is read
    * when it starts its line (as Verilator and most writers put them). */
  private[lsp] def patchHeader(header: String, tables: Map[String, WaveEnums.Table]): (String, Int) = {
    val out    = new StringBuilder
    var scopes = List.empty[String]
    var n      = 0
    header.split("(?<=\n)", -1).foreach { line =>
      val tok = line.trim.split("\\s+")
      tok.headOption match {
        case Some("$scope") if tok.length >= 3   => scopes = tok(2) :: scopes
        case Some("$upscope")                    => scopes = scopes.drop(1)
        case Some("$var") if tok.length >= 5 =>
          val path = (tok(4) :: scopes).reverse.mkString(".")
          for {
            t     <- tables.get(path)
            width <- tok(2).toIntOption
            if width > 0 && t.states.forall(_._1 < (BigInt(1) << width))
          } {
            n += 1
            out ++= table(t, width, n) ++= s"""$$attrbegin misc 07 "" $n $$end\n"""
          }
        case _ =>
      }
      out ++= line
    }
    (out.toString, n)
  }

  /** The VCD table line: the table's text, then its id. */
  private def table(t: WaveEnums.Table, width: Int, id: Int): String =
    s"$$attrbegin misc 07 ${tableText(t, width)} $id $$end\n"

  /** `<name> <n> <names...> <values...>`: the names, then their values as
    * `width`-bit strings. */
  private def tableText(t: WaveEnums.Table, width: Int): String = {
    val named = t.states.distinctBy(_._1)
    val others =
      if (width > FillBits) Nil
      else (BigInt(0) until (BigInt(1) << width)).filterNot(v => named.exists(_._1 == v)).map(v => v -> v.toString)
    val all   = named ++ others
    val names = all.map { case (_, s) => s.replaceAll("\\s", "_") }
    val bits  = all.map { case (v, _) => v.toString(2).reverse.padTo(width, '0').reverse }
    s"${t.name.replaceAll("\\s", "_")}_t ${all.size} ${names.mkString(" ")} ${bits.mkString(" ")}"
  }

  // ── FST ────────────────────────────────────────────────────────────────
  private val HierGzip   = 4
  private val HierLz4    = 6
  private val HierLz4Duo = 7
  private val ZWrapper   = 254

  /** The FST's hierarchy block (start, total size) and its entries,
    * decompressed; None when it has no single one, or is a ZWRAPPER. */
  private def hierarchy(src: FileChannel): Option[(Long, Long, Array[Byte])] = {
    // the blocks: (type, start, total size)
    val size   = src.size
    val blocks = Seq.newBuilder[(Int, Long, Long)]
    var pos = 0L
    while (pos + 9 <= size) {
      val h   = read(src, pos, 9)
      val len = java.nio.ByteBuffer.wrap(h, 1, 8).getLong
      if (len < 8) throw new IOException(s"FST block of length $len")
      blocks += ((h(0) & 0xff, pos, 1 + len))
      pos += 1 + len
    }
    val all  = blocks.result()
    val hier = all.filter(b => b._1 == HierGzip || b._1 == HierLz4 || b._1 == HierLz4Duo)
    if (all.exists(_._1 == ZWrapper) || hier.size != 1) None
    else {
      val (tpe, start, total) = hier.head
      val body = read(src, start + 1, total - 1)
      val uncompressed = java.nio.ByteBuffer.wrap(body, 8, 8).getLong.toInt
      val data = body.drop(16)
      val entries = tpe match {
        case HierGzip => inflate(data.drop(10), uncompressed) // past the gzip header
        case HierLz4  => lz4(data, uncompressed)
        case _ =>
          val (lvl1, skip) = varint(data, 0)
          lz4(lz4(data.drop(skip), lvl1.toInt), uncompressed)
      }
      Some((start, total, entries))
    }
  }

  /** An FST's hierarchy entries, decompressed (for the tests). */
  private[lsp] def fstEntries(in: os.Path): Option[Array[Byte]] = {
    val src = FileChannel.open(in.toNIO, READ)
    try hierarchy(src).map(_._3) finally src.close()
  }

  private def writeFst(in: os.Path, out: os.Path, tables: Map[String, WaveEnums.Table], log: String => Unit): Int =
    try {
      val src = FileChannel.open(in.toNIO, READ)
      try hierarchy(src) match {
        case None => log(s"trace state names: no hierarchy block to name in $in"); 0
        case Some((start, total, entries)) =>
          val size = src.size
          val (patched, n) = patchEntries(entries, tables)
          if (n > 0) {
            val gz = new ByteArrayOutputStream
            val z  = new java.util.zip.GZIPOutputStream(gz)
            z.write(patched); z.close()
            val block = java.nio.ByteBuffer.allocate(1 + 16 + gz.size)
            block.put(HierGzip.toByte).putLong(16L + gz.size).putLong(patched.length.toLong).put(gz.toByteArray)
            block.flip()
            val dst = FileChannel.open(out.toNIO, CREATE, WRITE, TRUNCATE_EXISTING)
            try {
              def copy(from: Long, until: Long): Unit = { var p = from; while (p < until) p += src.transferTo(p, until - p, dst) }
              copy(0, start)
              while (block.hasRemaining) dst.write(block)
              copy(start + total, size)
            } finally dst.close()
          }
          n
      } finally src.close()
    } catch { case NonFatal(e) => log(s"trace state names: $e"); 0 }

  private def read(ch: FileChannel, at: Long, n: Long): Array[Byte] = {
    val b = java.nio.ByteBuffer.allocate(n.toInt)
    while (b.hasRemaining && ch.read(b, at + b.position()) >= 0) {}
    if (b.hasRemaining) throw new IOException("FST block past the end of the file")
    b.array
  }

  /** The hierarchy entries with a table and its reference before each named
    * var, and how many; the entries themselves are copied byte for byte. */
  private[lsp] def patchEntries(in: Array[Byte], tables: Map[String, WaveEnums.Table]): (Array[Byte], Int) = {
    val out    = new ByteArrayOutputStream(in.length + 256)
    var scopes = List.empty[String]
    var n      = 0
    var p      = 0
    def cstr(): String = {
      val s = p
      while (in(p) != 0) p += 1
      p += 1
      new String(in, s, p - 1 - s, java.nio.charset.StandardCharsets.UTF_8)
    }
    def vint(): Long = { val (v, k) = varint(in, p); p += k; v }
    while (p < in.length) {
      val start = p
      val tpe   = in(p) & 0xff
      p += 1
      tpe match {
        case 254 => p += 1; val name = cstr(); cstr(); scopes = name :: scopes // scope: type, name, component
        case 255 => scopes = scopes.drop(1)
        case 252 => // attribute begin: type, subtype, a name (or a value and a 0 for a source stem), a value
          val attr = in(p) & 0xff
          val sub  = in(p + 1) & 0xff
          p += 2
          if (attr == 0 && (sub == 4 || sub == 5)) { vint(); p += 1 } else cstr()
          vint()
        case 253 => ()
        case v if v <= 29 => // var: direction, name, length, alias
          p += 1
          val name  = cstr()
          val raw   = vint().toInt
          vint()
          val width = if (v == 18) (raw - 2) / 3 else raw // a port's length counts its delimiters
          val path  = (name.takeWhile(_ != ' ') :: scopes).reverse.mkString(".")
          for {
            t <- tables.get(path)
            if width > 0 && t.states.forall(_._1 < (BigInt(1) << width))
          } {
            n += 1
            out.write(attribute(tableText(t, width), n))
            out.write(attribute("", n))
          }
        case other => throw new IOException(s"unknown FST hierarchy entry $other")
      }
      out.write(in, start, p - start)
    }
    (out.toByteArray, n)
  }

  /** A misc enum-table attribute: 252, 0, 7, the text, 0, the id. */
  private def attribute(text: String, id: Int): Array[Byte] = {
    val b = new ByteArrayOutputStream
    b.write(252); b.write(0); b.write(7)
    b.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); b.write(0)
    var v = id.toLong
    var more = true
    while (more) {
      val byte = (v & 0x7f).toInt
      v >>>= 7
      more = v != 0
      b.write(if (more) byte | 0x80 else byte)
    }
    b.toByteArray
  }

  /** An LEB128 value at `at`, and its size in bytes. */
  private def varint(b: Array[Byte], at: Int): (Long, Int) = {
    var res  = 0L
    var i    = 0
    var more = true
    while (more) {
      val x = b(at + i) & 0xff
      res |= (x & 0x7fL) << (7 * i)
      more = (x & 0x80) != 0
      i += 1
    }
    (res, i)
  }

  private def inflate(deflate: Array[Byte], size: Int): Array[Byte] = {
    val inf = new java.util.zip.Inflater(true)
    inf.setInput(deflate)
    val out = new Array[Byte](size)
    var n = 0
    try {
      while (n < size && !inf.finished()) {
        val k = inf.inflate(out, n, size - n)
        if (k == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IOException("truncated FST hierarchy")
        n += k
      }
    } finally inf.end()
    if (n != size) throw new IOException("short FST hierarchy")
    out
  }

  /** An LZ4 block (no frame, no size prefix) of `size` bytes, as lz4_flex and fstapi write it. */
  private def lz4(src: Array[Byte], size: Int): Array[Byte] = {
    val dst = new Array[Byte](size)
    var s = 0
    var d = 0
    def more(n0: Int): Int = { var n = n0; var b = 255; while (b == 255) { b = src(s) & 0xff; s += 1; n += b }; n }
    while (s < src.length) {
      val token = src(s) & 0xff
      s += 1
      val lit = if ((token >>> 4) == 15) more(15) else token >>> 4
      System.arraycopy(src, s, dst, d, lit)
      s += lit; d += lit
      if (s < src.length) {
        val offset = (src(s) & 0xff) | ((src(s + 1) & 0xff) << 8)
        s += 2
        val ml = (if ((token & 15) == 15) more(15) else token & 15) + 4
        var m = d - offset
        if (offset <= 0 || m < 0) throw new IOException("bad LZ4 offset")
        var k = 0
        while (k < ml) { dst(d) = dst(m); d += 1; m += 1; k += 1 }
      }
    }
    if (d != size) throw new IOException(s"LZ4 block of $d bytes, $size expected")
    dst
  }

  /** The byte offset just past the line of `$enddefinitions ... $end`. */
  private def headerEnd(in: os.Path): Option[Int] = {
    val marker = "$enddefinitions".getBytes(ISO_8859_1)
    val is  = os.read.inputStream(in)
    val buf = new ByteArrayOutputStream
    try {
      val chunk = new Array[Byte](1 << 16)
      var found = Option.empty[Int]
      var r = 0
      while (found.isEmpty && buf.size < MaxHeader && { r = is.read(chunk); r > 0 }) {
        buf.write(chunk, 0, r)
        val s = new String(buf.toByteArray, ISO_8859_1)
        val i = s.indexOf("$enddefinitions")
        if (i >= 0) {
          val e = s.indexOf("$end", i + marker.length)
          if (e >= 0) {
            val nl = s.indexOf('\n', e)
            found = Some(if (nl >= 0) nl + 1 else e + 4)
          }
        }
      }
      found
    } finally is.close()
  }
}
