package socless.capture

import socless.ir.IrWalk
import socless.parse.ChirrtlParser
import socless.schema.{Design, WireDef}

/** Consumer-side wiring for a [[CapturedModel]]: the probe leaves
  * `CModule.wires` empty (Chisel's public `Select.connectionsTo` throws on
  * literal-driven connects and cannot see nets traced through temporaries or
  * read by logic), so the wiring is derived here from the captured CHIRRTL by
  * the same pipeline that builds `design.json` (no layout). Not a shared source:
  * it stays out of the probe agent jar.
  */
object WiresFromChirrtl {

  /** Module name -> its full wire definitions, as the CHIRRTL pipeline emits them. */
  def wireDefs(chirrtl: String): Map[String, Seq[WireDef]] =
    design(ChirrtlParser.parse(chirrtl)).modules.map(m => m.name -> m.wires).toMap

  /** The full CHIRRTL-pipeline design (logic, wires, FSM regions) of an
    * already parsed circuit, so a caller parses the CHIRRTL once. */
  def design(circ: ChirrtlParser.Circ): Design = IrWalk.design(circ)

  /** Module name -> its wires, endpoints verbatim from `WireDef` (from = sink, to = source). */
  def wires(chirrtl: String): Map[String, Seq[CWire]] =
    wireDefs(chirrtl).map { case (name, ws) => name -> ws.map(w => CWire(w.from, w.to)) }
}
