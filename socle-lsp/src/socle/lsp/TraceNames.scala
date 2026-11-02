package socle.lsp

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.ISO_8859_1
import scala.collection.mutable
import scala.util.control.NonFatal

/** What a trace holds: the full dotted name of every var (`TOP.svsimTestbench.dut.x`)
  * and the scope the design sits under. Empty `names` means unknown (an FST):
  * nothing is dropped downstream. */
final case class TraceNames(prefix: String, names: Set[String])

/** Reads a VCD header (`$scope` / `$var` / `$upscope`) up to `$enddefinitions`
  * (or the first `#` timestamp), never the value changes, so a huge trace costs
  * only its header. Never throws: a truncated or odd file gives what was read,
  * a missing one nothing.
  *
  * The prefix is a scope whose vars and child scopes match the top module's
  * port names `ports` (a port `io` matches a var `io` or `io_...`, Verilator's
  * flattening, or a child scope `io`): [[TraceNames.DefaultPrefix]], where
  * ChiselSim and Verilator put the design, when it matches (its testbench
  * mirrors the ports, and most submodules have `clock`, `reset` and `io` too);
  * else the shallowest matching scope, the first in file order. With no port
  * names, no match or an FST, it is [[TraceNames.DefaultPrefix]]. */
object TraceNames {
  val DefaultPrefix = "TOP.svsimTestbench.dut"

  private val MaxChars = 64L * 1024 * 1024 // a header past this is not a header
  private val MaxToken = 4096

  private final class Scope(val path: List[String]) {
    val vars     = mutable.Set.empty[String]
    val children = mutable.Set.empty[String]
  }

  def read(trace: os.Path, ports: Set[String] = Set.empty): TraceNames =
    if (trace.ext.equalsIgnoreCase("fst")) TraceNames(DefaultPrefix, Set.empty)
    else {
      val scopes = mutable.ArrayBuffer.empty[Scope]
      val names  = Set.newBuilder[String]
      try {
        val in = new BufferedReader(new InputStreamReader(os.read.inputStream(trace), ISO_8859_1))
        try parse(new Tokens(in), scopes, names) finally in.close()
      } catch { case NonFatal(_) => () }
      TraceNames(prefix(scopes.toSeq, ports), names.result())
    }

  private def prefix(scopes: Seq[Scope], ports: Set[String]): String =
    if (ports.isEmpty) DefaultPrefix
    else {
      def matches(s: Scope) = ports.forall(p => s.vars(p) || s.children(p) || s.vars.exists(_.startsWith(p + "_")))
      def name(s: Scope) = s.path.reverse.mkString(".")
      val hits = scopes.filter(matches)
      if (hits.isEmpty || hits.exists(name(_) == DefaultPrefix)) DefaultPrefix
      else name(hits.minBy(_.path.size)) // the first of the shallowest
    }

  private def parse(t: Tokens, scopes: mutable.ArrayBuffer[Scope], names: mutable.Builder[String, Set[String]]): Unit = {
    var stack = List.empty[Scope] // innermost first
    // the next `n` tokens of a declaration, None when `$end` (or EOF) comes first
    def fields(n: Int): Option[Seq[String]] = {
      val got = mutable.ArrayBuffer.empty[String]
      var tok = t.next()
      while (got.size < n && tok.exists(_ != "$end")) { got += tok.get; if (got.size < n) tok = t.next() }
      if (got.size == n && t.skipToEnd()) Some(got.toSeq) else None // complete declarations only
    }
    var done = false
    while (!done) t.next() match {
      case None => done = true
      case Some("$enddefinitions") => done = true
      case Some(tok) if tok.startsWith("#") => done = true
      case Some("$scope") =>
        fields(2).foreach { f =>
          val name = f(1)
          stack.headOption.foreach(_.children += name)
          val s = new Scope(name :: stack.headOption.fold(List.empty[String])(_.path))
          scopes += s
          stack = s :: stack
        }
      case Some("$upscope") =>
        if (stack.nonEmpty) stack = stack.tail
        t.skipToEnd()
      case Some("$var") =>
        fields(4).foreach { f =>
          val ref = f(3)
          stack.headOption.foreach { s =>
            s.vars += ref
            names += (ref :: s.path).reverse.mkString(".")
          }
        }
      case Some(tok) if tok.startsWith("$") && tok != "$end" => t.skipToEnd() // $date, $comment, $version, ...
      case Some(_) => ()
    }
  }

  /** Whitespace-separated tokens, at most [[MaxChars]] read, each cut at [[MaxToken]]. */
  private final class Tokens(in: BufferedReader) {
    private var read = 0L
    def next(): Option[String] = {
      var c = nextChar()
      while (c >= 0 && Character.isWhitespace(c)) c = nextChar()
      if (c < 0) None
      else {
        val sb = new java.lang.StringBuilder
        while (c >= 0 && !Character.isWhitespace(c)) {
          if (sb.length < MaxToken) sb.append(c.toChar)
          c = nextChar()
        }
        Some(sb.toString)
      }
    }
    /** Skips past the next `$end`; false at EOF. */
    def skipToEnd(): Boolean = {
      var tok = next()
      while (tok.exists(_ != "$end")) tok = next()
      tok.isDefined
    }
    private def nextChar(): Int =
      if (read >= MaxChars) -1 else { read += 1; in.read() }
  }
}
