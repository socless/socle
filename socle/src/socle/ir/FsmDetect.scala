package socle.ir

import socle.schema._
import socle.parse._
import socle.parse.ChirrtlParser.{Mod, NodeDecl}

/** Pure recognizer for `switch(state)`-style FSMs in a parsed module.
  *
  * A `switch(state)` lowers to an outermost `when`/`else` chain whose case
  * conditions are all `eq(R, lit)` comparisons on the SAME register `R`. The
  * first case condition node is declared where the `switch` is (module scope,
  * or inside an enclosing `when`); each inner case condition is declared
  * inside the preceding `else` branch (so the first case condition is among
  * its guards, after the enclosing `when`s' conditions).
  *
  * This pass identifies such chains and reports, per recognized FSM, the
  * register being switched on, the chain's root condition and its case
  * literals (in source order). `CodeBlocks` makes such a `switch` an FSM box
  * (named by its source marker, `fsm$<module>$<line>_<col>`). It is pure and
  * deterministic: no side effects, results sorted by register.
  */
object FsmDetect {

  /** One recognized FSM within a module.
    * @param stateReg   the register R the chain compares.
    * @param outerCond  the outermost case-condition node name (e.g. "_T_1").
    * @param caseLits   the literals compared, in chain order.
    * @param condRefs   the `when` conditions the FSM reads besides its case
    *                   compares, in order: the enclosing `when`s' (around the
    *                   `switch`), then those inside it (refs or node names). */
  final case class Fsm(
    stateReg:        String,
    outerCond:       String,
    caseLits:        Seq[String],
    condRefs:        Seq[String] = Nil,
  )

  // Literal shape: UInt<2>(0h0), SInt<3>(...), or bare-width forms.
  private val LitRe = """^(?:UInt|SInt)(?:<\d+>)?\(.*\)$""".r
  private def isLit(s: String): Boolean = LitRe.findFirstIn(s.trim).isDefined

  def detect(m: Mod): Seq[Fsm] = {
    val regNames   = m.regs.map(_.name).toSet
    val nodeByName = m.nodes.map(n => n.name -> n).toMap

    // Does `ref` resolve to a register, possibly through one asUInt/asSInt node?
    // Returns the register name if so.
    def resolveReg(ref: String): Option[String] =
      if (regNames.contains(ref)) Some(ref)
      else
        nodeByName.get(ref).flatMap { n =>
          if ((n.op == "asUInt" || n.op == "asSInt") && n.operands.size == 1
              && regNames.contains(n.operands.head.trim))
            Some(n.operands.head.trim)
          else None
        }

    /** If `nodeName` is a state-compare (`eq(reg, lit)` in either order, reg
      * possibly reached through asUInt/asSInt), return (reg, lit). */
    def comparedReg(nodeName: String): Option[(String, String)] =
      nodeByName.get(nodeName).flatMap { n =>
        if (n.op != "eq" || n.operands.size != 2) None
        else {
          val a = n.operands(0).trim
          val b = n.operands(1).trim
          if (isLit(a) && !isLit(b)) resolveReg(b).map(r => (r, a))
          else if (isLit(b) && !isLit(a)) resolveReg(a).map(r => (r, b))
          else None
        }
      }

    // Whether a node or conn with guards `g` is inside the `when` on `cond`:
    // anywhere in the chain, as enclosing `when`s come first.
    def under(g: Seq[ChirrtlParser.Guard], cond: String): Boolean =
      g.exists(_.cond == cond)

    // For each candidate root node C (a state-compare not itself inside a
    // compare on the same register, which makes it an inner case), the chain
    // consists of C plus every state-compare on the SAME register guarded by C.
    val roots = m.nodes.iterator.flatMap { c =>
      comparedReg(c.name).collect {
        case (reg, lit) if !c.guard.exists(g => comparedReg(g.cond).exists(_._1 == reg)) => (c.name, reg, lit)
      }
    }.toSeq

    val fsms = roots.flatMap { case (rootName, reg, rootLit) =>
      // Items (nodes or conns) lexically inside the construct rooted at C.
      val guardedNodes = m.nodes.filter(n => under(n.guard, rootName))
      val guardedConns = m.conns.filter(c => under(c.guard, rootName))
      val anyGuarded   = guardedNodes.nonEmpty || guardedConns.nonEmpty

      // Same-R state-compares inside the else chain.
      val chainCompares = guardedNodes.flatMap { n =>
        comparedReg(n.name).collect { case (r, lit) if r == reg => (n.name, lit) }
      }

      if (!anyGuarded || chainCompares.isEmpty) None
      else {
        // caseLits in source order: root first, then guarded compares in the
        // order they appear in m.nodes.
        val orderedChainLits =
          guardedNodes.iterator
            .flatMap(n => comparedReg(n.name).collect { case (r, l) if r == reg => l })
            .toSeq
        val caseLits = rootLit +: orderedChainLits

        // Require at least 2 distinct case literals on the same R.
        if (caseLits.distinct.size < 2) None
        else {
          // All same-R state-compare node names in the chain (root + guarded).
          val compareNodeNames: Seq[String] =
            rootName +: guardedNodes.iterator
              .filter(n => comparedReg(n.name).exists(_._1 == reg))
              .map(_.name)
              .toSeq

          val condRefs: Seq[String] =
            (nodeByName.get(rootName).toSeq.flatMap(_.guard) ++ guardedNodes.flatMap(_.guard) ++
              guardedConns.flatMap(_.guard)).map(_.cond.trim).distinct
              .filterNot(compareNodeNames.toSet)

          Some(Fsm(
            stateReg        = reg,
            outerCond       = rootName,
            caseLits        = caseLits,
            condRefs        = condRefs,
          ))
        }
      }
    }

    // One Fsm per matched root; sort by register for determinism. If two roots
    // map to the same register (shouldn't normally happen), keep the first in
    // source order.
    fsms.groupBy(_.stateReg).toSeq.map(_._2.head).sortBy(_.stateReg)
  }
}
