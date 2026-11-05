package socle.ir

/** The synthetic-pin and synthetic-node naming protocol shared across the IR
  * walk (`IrWalk`, `WhenMux`, `FsmDetect`).
  *
  * CHIRRTL identifiers are `[\w$]+`, so `~` never appears in a real signal
  * name; the `~`-suffixed pins below are therefore guaranteed not to collide
  * with user signals. The `<prefix>$...` node ids reuse `$`, which IS legal in
  * CHIRRTL names, but are made unambiguous by sanitizing the sink path.
  *
  * {{{
  *   <node>~q          register Q output pin (the read side of a register); an
  *                     index or field follows it (`v~q[0]`, `v~q[0].a`)
  *   <node>~in<i>      i-th input pin of a logic-op node
  *   <mem>~<port>~<r|w|a>   memory read-data / write-data / address pin
  *   const$<sink>      synthesized constant node feeding <sink>
  *   mux$<sink>        synthesized mux node feeding <sink>
  *   asg$<block>$<sink>  assignment terminal: what code block <block> drives <sink> with
  *   cnd$<block>       condition terminal: the arm conditions of code block <block>
  *   <when|switch|fsm>$<mod>$<label>$<k>   a user-written code block (`CodeBlocks`),
  *                     named by its sanitized label and its ordinal among the
  *                     module's blocks of that kind and label (`fsm$` for a
  *                     `switch` FsmDetect recognises)
  * }}}
  *
  * Centralizing the protocol here keeps the producers (pin/id construction) and
  * the consumers (`baseOf`/`stripQ` recovery) reading from one definition, so a
  * change to a marker can never drift between call sites.
  */
object Synthetic {
  val QSuffix     = "~q"
  val InMarker    = "~in"
  val ConstPrefix = "const$"
  val MuxPrefix   = "mux$"
  val MemSep      = "~"
  val AssignPrefix = "asg$"
  val CondPrefix   = "cnd$"

  /** The i-th input pin of logic-op node `node`: `<node>~in<i>`. */
  def opInPin(node: String, i: Int): String = s"$node$InMarker$i"

  /** A memory pin: `<mem>~<port>~<role>`. Roles: `a` address-in, `w` write-data-in,
    * `r` read-data-out (issue #205). */
  def memPin(mem: String, port: String, role: String): String = s"$mem$MemSep$port$MemSep$role"

  /** Decompose a memory pin head into (mem, port, role); None if it is not a
    * three-segment mem pin. */
  def memPinParts(head: String): Option[(String, String, String)] = {
    val ps = head.split('~')
    if (ps.length == 3) Some((ps(0), ps(1), ps(2))) else None
  }

  /** Recover the underlying node name from a net head that may be a register Q
    * pin (`r~q`), a logic-op input pin (`_T~in<i>`), or a memory pin
    * (`<mem>~<port>~<role>`). The mem name precedes its first `~`, so a head with
    * a `~` but no `~q`/`~in` marker reduces to the prefix before the first `~`. A
    * plain head is returned unchanged. */
  def baseOf(head: String): String =
    if (head.endsWith(QSuffix)) head.dropRight(QSuffix.length)
    else {
      val i = head.indexOf(InMarker)
      if (i >= 0) head.substring(0, i)
      else { val t = head.indexOf('~'); if (t >= 0) head.substring(0, t) else head }
    }

  /** Strip only the register Q suffix of a head (no `~in` handling): `r~q` is
    * `r`, and a Vec element's `v~q[0]` is `v[0]` (the `~q` follows the register
    * name, before any index). */
  def stripQ(head: String): String = {
    val i    = head.indexOf('[')
    val name = if (i < 0) head else head.substring(0, i)
    if (name.endsWith(QSuffix)) name.dropRight(QSuffix.length) + head.substring(name.length) else head
  }

  /** Sanitize a sink path into an identifier-safe token (`.`, `[`, `]` -> `_`). */
  private def sanitize(sink: String): String = sink.replaceAll("[.\\[\\]]", "_")

  /** Synthesized constant-node id feeding `sink`. */
  def constId(sink: String): String = ConstPrefix + sanitize(sink)

  /** Synthesized mux-node id feeding `sink`. */
  def muxId(sink: String): String = MuxPrefix + sanitize(sink)

  /** Assignment terminal of code block `block` for `sink`: every connect to
    * `sink` inside the block feeds one of its input pins, its output drives
    * `sink`. A `~` (memory pin) is sanitized too, so the id stays one node. */
  def assignId(block: String, sink: String): String =
    s"$AssignPrefix$block$$${sanitize(sink).replace('~', '_')}"

  /** Condition terminal of code block `block`: one input pin per arm condition. */
  def condId(block: String): String = CondPrefix + block

  /** Is `id` an assignment or condition terminal? */
  def isTerminal(id: String): Boolean = id.startsWith(AssignPrefix) || id.startsWith(CondPrefix)

  /** A code block's label as an id token: every char outside `[A-Za-z0-9_]`
    * becomes `_`, runs of `_` collapse and the ends are trimmed
    * (`when(io.in.fire)` -> `when_io_in_fire`). Readable, not reversible. */
  def labelToken(label: String): String = {
    val t = label.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_").replaceAll("^_+|_+$", "")
    if (t.isEmpty) "block" else t
  }

  /** Code-block id (`CodeBlocks`): `<kind>$<mod>$<token>$<k>`, with `kind` the
    * id prefix `when`, `switch` or `fsm` (`NodeKind.Fsm`; a `when` chain and a
    * non-FSM `switch` are both `NodeKind.Block`, so the prefix is passed apart),
    * `token` the block's [[labelToken]] (suffixed `_2`, `_3`... by `CodeBlocks`
    * when two labels of a module sanitize alike) and `k` the 0-based ordinal of
    * the block among the module's blocks of that kind and label, in source
    * order (nested blocks and generator-loop copies count in the same
    * sequence). Named by what the code says, not where it sits, so an edit
    * above a block does not rename it. Plain strings only: this file is shared
    * with socle-js, which has no parser types. */
  def blockId(kind: String, module: String, token: String, k: Int): String =
    s"$kind$$$module$$$token$$$k"
}
