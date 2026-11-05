package socle.ir

import scala.collection.mutable
import socle.schema.NodeKind
import socle.parse.ChirrtlParser.{Mod, SrcInfo, WhenDecl}

/** One block of the user's code: a `when` chain (its `.elsewhen` arms joined),
  * a `switch` (one arm per `is`), or a `switch` FsmDetect recognises.
  * @param kind      `NodeKind.Block`, or `NodeKind.Fsm` (then `state` is its register).
  * @param label     the call as written: `when(io.enable)`, `switch(state)`.
  * @param src       the marker of the block's first `when`.
  * @param whenIds   the CHIRRTL `when`s of its arms (an `else` keeps its `when`'s id).
  * @param parent    the id of the enclosing block, if any.
  * @param condNodes the arm-condition nodes (and their `asUInt`/`asSInt`
  *                  feeders), which belong to the block even when declared
  *                  outside it (the first arm's compare sits beside the `switch`). */
case class CodeBlock(
  id:        String,
  kind:      String,
  label:     String,
  src:       Option[SrcInfo],
  whenIds:   Set[Int],
  parent:    Option[String],
  state:     String = "",
  condNodes: Set[String] = Set.empty,
)

/** Builds the tree of user-written `when` / `switch` blocks of a module from its
  * CHIRRTL `when`s and the source lines their markers point at. Only a `when`
  * whose marker line has a `when(`, `.elsewhen(` or `switch(` call is a block (a
  * library `when`, `RegEnable` or `Counter.inc`, is not); where the line cannot
  * be read, only the switch chains FsmDetect recognises are (today's FSM boxes).
  * Pure and deterministic. */
object CodeBlocks {

  private val Switch   = "switch"
  private val Elsewhen = "elsewhen"
  private val When     = "when"

  // A `.elsewhen(` call, or a `when(` / `switch(` call not part of a longer name,
  // possibly qualified (`chisel3.util.switch(`); a space may precede the `(`.
  private val CallRe = """\.elsewhen\s*\(|(?<![\w$.])(?:[\w$]+\.)*(?:switch|when)\s*\(""".r

  /** The block call a marker points at, as (keyword, call text). Chisel puts the
    * marker on the `{` after the call, so it is the last call starting left of
    * the column (this also splits a one-line `when(a) {..}.elsewhen(b) {..}`). */
  private[ir] def callAt(line: String, col: Int): Option[(String, String)] = {
    val calls = CallRe.findAllMatchIn(line).toSeq
    val before = calls.filter(_.start < col - 1)
    (if (before.nonEmpty) before.lastOption else calls.headOption).map { mt =>
      // The keyword starts after the last `.` (the elsewhen dot or a qualifier).
      val kwAt: Int = mt.matched.lastIndexOf('.') + 1
      val word: String = mt.matched.substring(kwAt)
      val kw =
        if (word.startsWith(Elsewhen)) Elsewhen
        else if (word.startsWith(Switch)) Switch
        else When
      (kw, balanced(line, mt.start + kwAt, mt.end - 1))
    }
  }

  // The text from `from` through the parenthesis matching the one at `open`;
  // the rest of the line when it does not close there.
  private def balanced(line: String, from: Int, open: Int): String = {
    var depth = 0
    var i     = open
    var end   = line.length
    while (i < line.length && end == line.length) {
      line(i) match {
        case '(' => depth += 1
        case ')' => depth -= 1; if (depth == 0) end = i + 1
        case _   =>
      }
      i += 1
    }
    line.substring(from, end).trim
  }

  // A block while it is built: `idKind` is the id prefix (when / switch / fsm).
  private final class Builder(val root: WhenDecl, val idKind: String, val label: String,
      val state: String, val parent: Option[Builder]) {
    val arms: mutable.ArrayBuffer[WhenDecl] = mutable.ArrayBuffer(root)
  }

  def of(m: Mod, lines: SourceLines): Seq[CodeBlock] = {
    val fsmByRoot: Map[String, FsmDetect.Fsm] = FsmDetect.detect(m).map(f => f.outerCond -> f).toMap
    val nodeByName: Map[String, socle.parse.ChirrtlParser.NodeDecl] = m.nodes.map(n => n.name -> n).toMap

    def isCast(name: String): Boolean =
      nodeByName.get(name).exists(n => (n.op == "asUInt" || n.op == "asSInt") && n.operands.size == 1)

    // Does the condition node `cond` compare register `reg` (directly or through a cast)?
    def comparesReg(cond: String, reg: String): Boolean =
      nodeByName.get(cond).exists { n =>
        n.op == "eq" && n.operands.map(_.trim).exists { op =>
          op == reg || (isCast(op) && nodeByName(op).operands.head.trim == reg)
        }
      }

    val built: mutable.ArrayBuffer[Builder]   = mutable.ArrayBuffer.empty
    val blockOfWhen: mutable.Map[Int, Builder] = mutable.Map.empty

    for (w <- m.whens) {
      // The block whose last arm holds `w` in its `else`, if any.
      val elseOf: Option[Builder] = w.parents.lastOption.collect { case (pid, true) => pid }
        .flatMap(pid => blockOfWhen.get(pid).filter(_.arms.last.id == pid))
      def joinsSwitch(b: Builder): Boolean =
        b.idKind != When && b.arms.last.src == w.src &&
          (b.idKind != NodeKind.Fsm || comparesReg(w.cond, b.state))
      val enclosing: Option[Builder] = w.parents.reverseIterator.flatMap(p => blockOfWhen.get(p._1)).nextOption()

      def join(b: Builder): Unit = { b.arms += w; blockOfWhen(w.id) = b }
      def start(idKind: String, label: String, state: String): Unit = {
        val b = new Builder(w, idKind, label, state, enclosing)
        built += b; blockOfWhen(w.id) = b
      }
      def startSwitch(label: String): Unit = fsmByRoot.get(w.cond) match {
        case Some(f) => start(NodeKind.Fsm, label, f.stateReg)
        case None    => start(Switch, label, "")
      }

      // Without a call to read: only the switch chains FsmDetect recognises.
      def fsmOnly(): Unit =
        elseOf.filter(b => b.idKind == NodeKind.Fsm && joinsSwitch(b)) match {
          case Some(b) => join(b)
          case None    => fsmByRoot.get(w.cond).foreach(f => start(NodeKind.Fsm, s"switch(${f.stateReg})", f.stateReg))
        }

      val line: Option[String] = w.src.flatMap(s => lines.line(s.file, s.line))
      line match {
        case Some(text) =>
          callAt(text, w.src.get.col) match {
            case Some((Switch, label)) =>
              elseOf.filter(joinsSwitch) match {
                case Some(b) => join(b)
                case None    => startSwitch(label)
              }
            case Some((Elsewhen, label)) =>
              elseOf.filter(_.idKind == When) match {
                case Some(b) => join(b)
                case None    => start(When, label, "")
              }
            case Some((_, label)) => start(When, label, "")
            // No call on the line: not a block (its items belong to the enclosing
            // one), unless FsmDetect recognises a switch chain there.
            case None             => fsmOnly()
          }
        case None => fsmOnly() // no source
      }
    }

    // Ids: named by the root marker; later blocks on the same marker get a copy suffix.
    val copies: mutable.Map[String, Int] = mutable.Map.empty
    val ids: Map[Builder, String] = built.map { b =>
      val marker: Option[(Int, Int)] = b.root.src.map(s => (s.line, s.col))
      val base = Synthetic.blockId(b.idKind, m.name, marker, b.root.id, 0)
      val n: Int = copies.getOrElse(base, 0)
      copies(base) = n + 1
      b -> Synthetic.blockId(b.idKind, m.name, marker, b.root.id, n)
    }.toMap

    built.toSeq.map { b =>
      val condNodes: Set[String] = b.arms.iterator.flatMap { a =>
        nodeByName.get(a.cond).toSeq.flatMap(n => n.name +: n.operands.map(_.trim).filter(isCast))
      }.toSet
      CodeBlock(
        id        = ids(b),
        kind      = if (b.idKind == NodeKind.Fsm) NodeKind.Fsm else NodeKind.Block,
        label     = b.label,
        src       = b.root.src,
        whenIds   = b.arms.map(_.id).toSet,
        parent    = b.parent.map(ids),
        state     = b.state,
        condNodes = condNodes,
      )
    }
  }

  /** The innermost block among `whenIds` (outermost first, as on a `Conn`). */
  def blockOf(blocks: Seq[CodeBlock], whenIds: Seq[Int]): Option[String] =
    whenIds.reverseIterator.flatMap(id => blocks.find(_.whenIds.contains(id))).nextOption().map(_.id)
}
