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
    def clean(id: String) = id.replaceAll("~[^.\\[]*", "")
    // The FSMs of a module: (register, states with values).
    def fsms(m: ModuleDef): Seq[(String, Seq[(BigInt, String)])] = m.logic.flatMap { n =>
      for {
        d   <- n.fsm.toSeq
        reg <- register(m, n).toSeq
        states = d.states.flatMap(s => scala.util.Try(BigInt(s.value)).toOption.map(_ -> s.name))
        if states.nonEmpty
      } yield (reg, states)
    }
    // The FSM box's state register: its region's register that is a signal of
    // the module, else the one its id names (`blk$<module>$fsm$<register>`).
    def register(m: ModuleDef, n: LogicNodeDef): Option[String] =
      design.regions.get(n.id).flatMap { r =>
        val regs = r.logic.filter(_.kind == NodeKind.Reg).map(l => clean(l.id))
        regs.find(id => m.signals.exists(_.name == id)).orElse(regs.headOption)
      }.orElse(Option(n.id.lastIndexOf("$fsm$")).filter(_ >= 0).map(i => n.id.substring(i + 5)))
    val out = Seq.newBuilder[Table]
    def walk(m: ModuleDef, path: List[String], depth: Int): Unit = if (depth < 64) {
      fsms(m).foreach { case (reg, states) => out += Table(WaveLayout.tracePath(prefix, path, reg), reg, states) }
      m.children.foreach(c => mods.get(c.`type`).foreach(cm => walk(cm, path :+ c.name, depth + 1)))
    }
    mods.get(design.top).foreach(walk(_, List(design.top), 0))
    out.result()
  }
}
