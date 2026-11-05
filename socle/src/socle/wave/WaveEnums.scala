package socle.wave

import socle.schema._

/** The state names a trace can carry for the waveform viewer: for every
  * instance of a module with an FSM whose states have values
  * ([[FsmState.value]]), its state register's trace path and the values'
  * names. A writer puts them in the trace's header (a VCD enum table), which
  * Surfer shows instead of the numbers. */
object WaveEnums {

  /** One state register: its full trace path, the table's name (the
    * register's) and its states, value to name, in the diagram's order. */
  final case class Table(trace: String, name: String, states: Seq[(BigInt, String)])

  def tables(design: Design, prefix: String): Seq[Table] = {
    val mods = design.modules.iterator.map(m => m.name -> m).toMap
    // The FSMs of a module, at its top level or inside its code-block boxes
    // (an FSM under a `when`): (register, states with values).
    def fsms(m: ModuleDef): Seq[(String, Seq[(BigInt, String)])] = {
      val seen = scala.collection.mutable.Set.empty[String]
      def nodes(logic: Seq[LogicNodeDef]): Seq[LogicNodeDef] = logic.flatMap { n =>
        val inside =
          if (NodeKind.isBox(n.kind) && seen.add(n.id)) design.regions.get(n.id).toSeq.flatMap(r => nodes(r.logic))
          else Nil
        n +: inside
      }
      nodes(m.logic).flatMap { n =>
        for {
          d <- n.fsm.toSeq
          if n.state.nonEmpty
          states = d.states.flatMap(s => scala.util.Try(BigInt(s.value)).toOption.map(_ -> s.name))
          if states.nonEmpty
        } yield (n.state, states)
      }
    }
    val out = Seq.newBuilder[Table]
    def walk(m: ModuleDef, path: List[String], depth: Int): Unit = if (depth < 64) {
      fsms(m).foreach { case (reg, states) => out += Table(WaveLayout.tracePath(prefix, path, reg), reg, states) }
      m.children.foreach(c => mods.get(c.`type`).foreach(cm => walk(cm, path :+ c.name, depth + 1)))
    }
    mods.get(design.top).foreach(walk(_, List(design.top), 0))
    out.result()
  }
}
