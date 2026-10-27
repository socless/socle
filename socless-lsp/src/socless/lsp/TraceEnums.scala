package socless.lsp

import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.StandardOpenOption.{CREATE, READ, TRUNCATE_EXISTING, WRITE}
import scala.util.control.NonFatal
import socless.wave.WaveEnums

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
  * copied as they are. */
object TraceEnums {
  val FillBits = 8
  private val MaxHeader = 64 * 1024 * 1024

  /** Writes `in` with the tables of `tables` to `out`. Returns how many
    * variables got one; 0 (and nothing written) when none matched, the
    * format is not VCD, or the header cannot be read. */
  def write(in: os.Path, out: os.Path, tables: Seq[WaveEnums.Table], log: String => Unit = _ => ()): Int =
    if (tables.isEmpty || !in.ext.equalsIgnoreCase("vcd")) 0
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

  /** The table line: names, then their values as `width`-bit strings, then its id. */
  private def table(t: WaveEnums.Table, width: Int, id: Int): String = {
    val named = t.states.distinctBy(_._1)
    val others =
      if (width > FillBits) Nil
      else (BigInt(0) until (BigInt(1) << width)).filterNot(v => named.exists(_._1 == v)).map(v => v -> v.toString)
    val all   = named ++ others
    val names = all.map { case (_, s) => s.replaceAll("\\s", "_") }
    val bits  = all.map { case (v, _) => v.toString(2).reverse.padTo(width, '0').reverse }
    s"$$attrbegin misc 07 ${t.name.replaceAll("\\s", "_")}_t ${all.size} ${names.mkString(" ")} ${bits.mkString(" ")} $id $$end\n"
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
