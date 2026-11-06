package socle.ui.model

/** Shared labelling helpers for combinational op / register logic nodes
  * (issue #188 B2). Both the schematic ([[Schematic]]) and the inspector
  * ([[Inspector]]) must agree on how an op's input pins are named and how the
  * encoding artifacts (`~q`, `~in<i>`) are cleaned out of user-visible paths, so
  * the role table, the `~in<N>` regex, and the cleanup live here once.
  *
  * Note: the backend `IrWalk.baseOf` has its own `~in` stripping; that is a
  * separate compile unit (backend Scala, not Scala.js) and intentionally does
  * not share with this object. */
object LogicLabels {

  /** Matches an op input pin `<id>~in<i>` so the head can be cleaned to `in<i>`
    * and the index recovered for role labelling. */
  val OpInRe = """^(.*)~in(\d+)$""".r

  /** Clean a pin label/path's HEAD segment of the encoding artifacts that must
    * never reach the user (issue #188): a register's Q output pin `r~q` reads
    * `r` (and `r~q.bits` reads `r.bits`), and a combinational op's input pin
    * `sum~in0` reads `in0` (B2). Only the head segment is touched, never a
    * literal mid-string occurrence; a trailing `.field` is preserved. */
  def stripQ(label: String): String = {
    if (label == null || label.isEmpty) return label
    val dot  = label.indexOf('.')
    val head = if (dot < 0) label else label.substring(0, dot)
    val rest = if (dot < 0) "" else label.substring(dot)
    // A Vec register element's Q pin `v~q[0]` reads `v[0]`: the `~q` precedes the index.
    val name = head.takeWhile(_ != '[')
    if (name.endsWith("~q")) name.dropRight(2) + head.substring(name.length) + rest
    else head match {
      case OpInRe(_, i) => "in" + i + rest
      case _            => label
    }
  }

  /** The role a combinational op's i-th WEST input plays, by op + index. Used to
    * label op input pins meaningfully (`sel`/`a`/`b`/`amt`) instead of `in0`.
    *
    * Backend invariant: FIRRTL `mux(sel, a, b)` = `sel ? a : b`, so operand 1
    * (a) is the value when sel is TRUE and operand 2 (b) the value when sel is
    * FALSE; the digit reflects the sel value that selects that input.
    *
    * Defensive: any out-of-range or unmapped index falls back to its number. */
  def inRole(op: String, i: Int): String = {
    val roles: Seq[String] = op match {
      case "mux"                                  => Seq("sel", "1", "0")
      case "sub" | "lt" | "leq" | "gt" | "geq"
         | "eq" | "neq" | "div" | "rem"           => Seq("a", "b")
      case "add" | "mul" | "and" | "or" | "xor"   => Seq("a", "b")
      case "shl" | "shr" | "dshl" | "dshr"        => Seq("in", "amt")
      case "neg" | "not" | "andr" | "orr" | "xorr" => Seq("in")
      case _                                       => Seq.empty
    }
    roles.lift(i).getOrElse(i.toString)
  }
}
