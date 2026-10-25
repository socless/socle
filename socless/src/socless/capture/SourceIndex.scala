package socless.capture

import socless.schema.{FsmDiagram, SourceRef}

/** A state machine read from the Scala source: its diagram, and `switch`, the
  * position of the first `switch(register)` on it. */
case class FsmSource(diagram: FsmDiagram, switch: SourceRef)

/** A Scala source construct that gives generated hardware a shape: a `when`
  * chain, a `for` loop, a `switch`. `id` is stable per construct; `label` is a
  * one-line description (a loop head, a condition). */
case class Construct(id: String, label: String, source: SourceRef)

/** Maps a hardware node's source position to the construct that contains it.
  * `None` when the position lies in no known construct. `scalaClass` is the
  * generator class of the module (`ModuleDef.scalaClass`); empty means the
  * class is unknown, and an implementation may then return `None`. */
trait SourceIndex {
  def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct]

  /** The state machine `scalaClass` builds on `register` with `switch`, or
    * `None` when the source does not give one. */
  def fsm(scalaClass: String, register: String): Option[FsmSource] = None
}
