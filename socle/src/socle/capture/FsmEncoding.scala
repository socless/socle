package socle.capture

import socle.parse.ChirrtlParser.Mod
import socle.schema.FsmDiagram

/** The encoding of each state of an FSM diagram (its names come from the
  * Scala source) in the state register (its values from the CHIRRTL), so the
  * waveform viewer can show names instead of numbers. Three pairings, which
  * must agree:
  *  - an assignment: the CHIRRTL connects of a literal on the source line of
  *    a transition (`state := sGreen`), the transitions into states on that
  *    line taken in column order; a line whose counts differ (another literal
  *    assigned there) pairs nothing;
  *  - the reset: the register's `regreset` literal is the initial state's;
  *  - the comparison chain: [[socle.ir.FsmDetect]]'s case literals, in
  *    chain order, are the `is(...)` cases' states in source order (each
  *    state's source is its first case); only when every state has a case of
  *    its own.
  * A state no pairing reaches has no value. When two pairings disagree (a
  * name with two values, a value with two names), no state gets one: a wrong
  * name in the waves is worse than a number. */
object FsmEncoding {

  def values(m: Mod, register: String, diagram: FsmDiagram, caseLits: Seq[String]): Map[String, BigInt] = {
    val names = diagram.states.map(_.name).toSet
    val pairs = Seq.newBuilder[(String, BigInt)]
    def sameFile(a: String, b: String) = a == b || a.endsWith("/" + b) || b.endsWith("/" + a)

    // Assignments: the literal connects on each transition's line.
    val literals = m.conns.flatMap(c => for { s <- c.src; v <- ModelToDesign.literal(c.rhs) } yield (s, v))
    diagram.transitions.filter(t => t.source.line > 0 && names(t.to)).map(t => (t.to, t.source)).distinct
      .groupBy { case (_, s) => (s.file, s.line) }.foreach { case ((file, line), ts) =>
        val here = literals.filter { case (s, _) => s.line == line && sameFile(s.file, file) }.sortBy(_._1.col)
        if (here.size == ts.size) ts.sortBy(_._2.col).map(_._1).zip(here.map(_._2)).foreach(pairs += _)
      }

    // The reset value is the initial state's.
    for {
      r <- m.regs.find(_.name == register)
      v <- r.init.flatMap(ModelToDesign.literal)
      s <- diagram.states.find(_.initial)
    } pairs += s.name -> v

    // The comparison chain, in the cases' order.
    val ordered = diagram.states.sortBy(s => (s.source.line, s.source.col))
    val chain   = caseLits.flatMap(ModelToDesign.literal)
    if (ordered.forall(_.source.line > 0) && ordered.map(_.source).distinct.size == ordered.size && chain.size == ordered.size)
      ordered.map(_.name).zip(chain).foreach(pairs += _)

    val all = pairs.result().distinct
    val consistent = all.groupBy(_._1).values.forall(_.size == 1) && all.groupBy(_._2).values.forall(_.size == 1)
    if (consistent) all.toMap else Map.empty
  }
}
