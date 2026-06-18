package socless.ir

/** The synthetic-pin and synthetic-node naming protocol shared across the IR
  * walk (`IrWalk`, `WhenMux`, `FsmDetect`).
  *
  * CHIRRTL identifiers are `[\w$]+`, so `~` never appears in a real signal
  * name; the `~`-suffixed pins below are therefore guaranteed not to collide
  * with user signals. The `<prefix>$...` node ids reuse `$`, which IS legal in
  * CHIRRTL names, but are made unambiguous by sanitizing the sink path.
  *
  * {{{
  *   <node>~q          register Q output pin (the read side of a register)
  *   <node>~in<i>      i-th input pin of a logic-op node
  *   const$<sink>      synthesized constant node feeding <sink>
  *   mux$<sink>        synthesized mux node feeding <sink>
  *   fsm$<mod>$<reg>   collapsed-FSM node for state register <reg> in <mod>
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

  /** The i-th input pin of logic-op node `node`: `<node>~in<i>`. */
  def opInPin(node: String, i: Int): String = s"$node$InMarker$i"

  /** Recover the underlying node name from a net head that may be a register Q
    * pin (`r~q`) or a logic-op input pin (`_T~in<i>`). Strips at most one such
    * suffix; a plain head is returned unchanged. */
  def baseOf(head: String): String =
    if (head.endsWith(QSuffix)) head.dropRight(QSuffix.length)
    else { val i = head.indexOf(InMarker); if (i >= 0) head.substring(0, i) else head }

  /** Strip only a trailing register Q suffix (no `~in` handling). */
  def stripQ(head: String): String =
    if (head.endsWith(QSuffix)) head.dropRight(QSuffix.length) else head

  /** Sanitize a sink path into an identifier-safe token (`.`, `[`, `]` -> `_`). */
  private def sanitize(sink: String): String = sink.replaceAll("[.\\[\\]]", "_")

  /** Synthesized constant-node id feeding `sink`. */
  def constId(sink: String): String = ConstPrefix + sanitize(sink)

  /** Synthesized mux-node id feeding `sink`. */
  def muxId(sink: String): String = MuxPrefix + sanitize(sink)

  /** Collapsed-FSM node id for state register `reg` in module `mod`. */
  def fsmId(mod: String, reg: String): String = s"fsm$$$mod$$$reg"
}
