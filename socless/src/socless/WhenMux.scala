package socless

import scala.collection.mutable
import ChirrtlParser.{Conn, Guard}

/** Slice 1 of issue #210: reconstruct SIMPLE (depth-1) conditional assignments
  * as muxes. A sink (connect lhs) is eligible iff every guarded connect on it is
  * at nesting depth exactly 1 (a single enclosing `when`, optionally its `else`)
  * AND it has an unconditional default OR is a register (whose held value is the
  * implicit default). Eligible sinks are folded, in source order with
  * last-connect-wins, into a priority-mux chain; the chain output drives the sink.
  *
  * Anything nested (depth >= 2: elsewhen / FSM / nested when) is left untouched
  * (no nodes, nothing consumed) and falls back to the existing fan-in rendering.
  *
  * Pure and side-effect free, like [[NetTrace]]: it emits mux LogicNodeDefs and
  * NetTrace (sink, source) edges; literal inputs and reg `~q` suffixing are
  * handled downstream by IrWalk's existing const-lifting / reg machinery.
  */
object WhenMux {

  /** @param nodes    synthesized mux nodes (kind="op", op="mux").
    * @param edges    NetTrace (sink, source) edges for mux pins + mux->sink.
    * @param consumed indices into the input `conns` that were folded (and so must
    *                 be removed from the connect stream before NetTrace runs).
    */
  final case class Result(
    nodes:    Seq[LogicNodeDef],
    edges:    Seq[(String, String)],
    consumed: Set[Int],
  )

  // The false/else side of a mux can nest another mux; the true side is always
  // a plain source path in Slice 1.
  private sealed trait Arg
  private final case class Ref(path: String)                                extends Arg
  private final case class MuxArg(sel: String, t: Arg, f: Arg)              extends Arg

  // Splits on '.' only, so a vec-indexed reg element like `r[0]` is treated as
  // ineligible (falls back to fan-in) rather than recognized as a register.
  // Acceptable for Slice 1 (whole-signal).
  private def headOf(path: String): String = path.split('.').head

  def synthesize(conns: Seq[Conn], isReg: String => Boolean): Result = {
    val nodes    = mutable.ArrayBuffer.empty[LogicNodeDef]
    val edges    = mutable.ArrayBuffer.empty[(String, String)]
    val consumed = mutable.SortedSet.empty[Int]

    // Group connect indices by sink (lhs), preserving source order.
    val bySink = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Int]]
    conns.iterator.zipWithIndex.foreach { case (c, i) =>
      bySink.getOrElseUpdate(c.lhs, mutable.ArrayBuffer.empty) += i
    }

    val usedIds = mutable.HashSet.empty[String]

    bySink.foreach { case (sink, idxs) =>
      val group     = idxs.map(i => (i, conns(i)))
      val guarded   = group.filter(_._2.guard.nonEmpty)
      val hasGuarded = guarded.nonEmpty
      val allDepth1 = guarded.forall(_._2.guard.length == 1)
      val hasDefault = group.exists(_._2.guard.isEmpty)
      val sinkIsReg = isReg(headOf(sink))

      if (hasGuarded && allDepth1 && (hasDefault || sinkIsReg)) {
        // Order conditions by their LAST event's source index so the
        // last-assigned condition becomes the outermost (highest-priority) mux,
        // matching FIRRTL last-connect-wins even if conditions interleave.
        // (`guarded` is in ascending source order, so the final write per cond
        // is its last event.)
        val lastIdxByCond = mutable.LinkedHashMap.empty[String, Int]
        guarded.foreach { case (i, c) => lastIdxByCond(c.guard.head.cond) = i }
        val condOrder = lastIdxByCond.toSeq.sortBy(_._2).map(_._1)
        // Per cond, the last true-branch rhs and last else-branch rhs.
        def lastRhs(p: Conn => Boolean): Option[String] =
          group.filter { case (_, c) => p(c) }.lastOption.map(_._2.rhs)
        val defaultArg: Arg =
          lastRhs(_.guard.isEmpty).map(Ref(_)).getOrElse(Ref(sink)) // reg-hold = the sink's own path

        // Fold events in source order; the LAST event becomes the outermost mux.
        var acc: Arg = defaultArg
        condOrder.foreach { cond =>
          val trueV = lastRhs(c => c.guard == Seq(Guard(cond, negated = false)))
          val elseV = lastRhs(c => c.guard == Seq(Guard(cond, negated = true)))
          trueV.foreach { tv =>
            acc = MuxArg(cond, Ref(tv), elseV.map(Ref(_)).getOrElse(acc))
          }
        }

        acc match {
          case MuxArg(_, _, _) =>
            val base = "mux$" + sink.replaceAll("[.\\[\\]]", "_")
            var counter = 0
            def nextId(): String = {
              val cand0 = if (counter == 0) base else s"$base$$$counter"
              counter += 1
              // Guarantee global uniqueness: two distinct sinks can flatten to
              // the same base (`a.b` and `a_b` both -> mux$a_b), and a chain id
              // could clash with another sink's base. Suffix until unused.
              var cand = cand0; var k = 0
              while (usedIds.contains(cand)) { k += 1; cand = s"${cand0}_$k" }
              usedIds += cand
              cand
            }
            // Pre-order id assignment so the outermost mux gets the bare base id.
            def materialize(a: Arg): String = a match {
              case Ref(p)            => p
              case MuxArg(sel, t, f) =>
                val id = nextId()
                nodes += LogicNodeDef(id, "op", "mux", "", SourceRef.unknown, "mux")
                edges += ((s"$id~in0", sel))
                edges += ((s"$id~in1", materialize(t)))
                edges += ((s"$id~in2", materialize(f)))
                id
            }
            val outId = materialize(acc)
            edges += ((sink, outId))         // mux output drives the sink
            consumed ++= idxs                // all of this sink's connects are folded
          case _ => () // no true-branch ever matched (e.g. only else): leave as-is
        }
      }
    }

    Result(nodes.toSeq, edges.toSeq, consumed.toSet)
  }
}
