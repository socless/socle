package socle.ir

import scala.collection.mutable
import socle.schema.NodeKind
import socle.parse.ChirrtlParser.{Mod, NodeDecl, SrcInfo, WhenDecl}

/** One block of the user's code: a `when` chain (its `.elsewhen` arms joined),
  * a `switch` (one arm per `is`), or a `switch` FsmDetect recognises.
  * @param kind      `NodeKind.Block`, or `NodeKind.Fsm` (then `state` is its register).
  * @param label     the call as written: `when(io.enable)`, `switch(state)`.
  * @param src       the marker of the block's first `when`.
  * @param whenIds   the CHIRRTL `when`s of its arms (an `else` keeps its `when`'s id).
  * @param parent    the id of the enclosing block, if any.
  * @param armConds  each `when` / `.elsewhen` arm's condition as written
  *                  (`io.in.fire`, `x > y`), by its `when`'s id; a `switch`
  *                  arm or an arm whose call does not close on its line has none. */
case class CodeBlock(
  id:        String,
  kind:      String,
  label:     String,
  src:       Option[SrcInfo],
  whenIds:   Set[Int],
  parent:    Option[String],
  state:     String = "",
  armConds:  Map[Int, String] = Map.empty,
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
    * the column whose closing parenthesis is followed by nothing but spaces and
    * that `{` through the column, or that spans the column (a condition left
    * open on the line). This splits a one-line `when(a) {..}.elsewhen(b) {..}`,
    * and a library `when` on a user's one-liner (`when(en) { r := RegEnable(d, v) }`,
    * marker on `RegEnable`) finds no call. */
  private[ir] def callAt(line: String, col: Int): Option[(String, String)] = {
    val at: Int = col - 1
    CallRe.findAllMatchIn(line).toSeq.filter { mt =>
      val end: Int = closeOf(line, mt.end - 1)
      mt.start <= at && (end > at || line.substring(end, math.min(at + 1, line.length)).forall(c => c.isWhitespace || c == '{'))
    }.lastOption.map { mt =>
      // The keyword starts after the last `.` (the elsewhen dot or a qualifier).
      val kwAt: Int = mt.matched.lastIndexOf('.') + 1
      val word: String = mt.matched.substring(kwAt)
      val kw =
        if (word.startsWith(Elsewhen)) Elsewhen
        else if (word.startsWith(Switch)) Switch
        else When
      (kw, line.substring(mt.start + kwAt, closeOf(line, mt.end - 1)).trim)
    }
  }

  // The index just past the parenthesis matching the one at `open`; the line's
  // length when it does not close there.
  private def closeOf(line: String, open: Int): Int = {
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
    end
  }

  // A block while it is built: `idKind` is the id prefix (when / switch / fsm).
  private final class Builder(val root: WhenDecl, val idKind: String, val label: String,
      val state: String, val parent: Option[Builder]) {
    val arms: mutable.ArrayBuffer[WhenDecl] = mutable.ArrayBuffer(root)
    val armConds: mutable.Map[Int, String]   = mutable.Map.empty
  }

  // The condition inside a `when(...)` / `.elsewhen(...)` call text, when it closes.
  private def condOf(call: String): Option[String] = {
    val open: Int = call.indexOf('(')
    if (open < 0 || !call.endsWith(")")) None
    else Some(call.substring(open + 1, call.length - 1).trim).filter(_.nonEmpty)
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
      // A `when` / `.elsewhen` arm keeps its condition as written.
      def armCond(call: String): Unit = condOf(call).foreach(c => blockOfWhen(w.id).armConds(w.id) = c)
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
              armCond(label)
            case Some((_, label)) => start(When, label, ""); armCond(label)
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
      CodeBlock(
        id        = ids(b),
        kind      = if (b.idKind == NodeKind.Fsm) NodeKind.Fsm else NodeKind.Block,
        label     = b.label,
        src       = b.root.src,
        whenIds   = b.arms.map(_.id).toSet,
        parent    = b.parent.map(ids),
        state     = b.state,
        armConds  = b.armConds.toMap,
      )
    }
  }

  private val Infix: Map[String, String] = Map(
    "eq" -> "===", "neq" -> "=/=", "lt" -> "<", "leq" -> "<=", "gt" -> ">", "geq" -> ">=",
    "and" -> "&", "or" -> "|", "xor" -> "^", "add" -> "+", "sub" -> "-", "mul" -> "*",
    "div" -> "/", "rem" -> "%", "dshl" -> "<<", "dshr" -> ">>", "cat" -> "##")
  private val Transparent: Set[String] = Set("asUInt", "asSInt", "asBool", "cvt", "pad", "tail", "head", "")
  private val LitRe = """[US]Int<\d*>\((-?)0h([0-9a-fA-F]+)\)""".r
  private val MaxDepth: Int = 4

  /** A CHIRRTL expression shown as Chisel-like text, for a pin that has no
    * source text: a temp node (`_T`, `_GEN_0`) is expanded into its operation
    * (`state === 1`, `busy & (x =/= y)`), a named node or a reference keeps its
    * name, a literal is its value. Never a bare temp: past the depth limit an
    * operation shows as `...`. */
  private[socle] def exprText(nodes: Map[String, NodeDecl], e: String): String = {
    def isLit(t: String): Boolean = LitRe.matches(t.trim)
    def go(e: String, depth: Int): String = {
      val t: String = e.trim
      t match {
        case LitRe(sign, hex) => sign + BigInt(hex, 16).toString
        case _ => nodes.get(t) match {
          case Some(_) if !t.startsWith("_") && depth > 0 => t
          case Some(_) if depth >= MaxDepth              => "..."
          case Some(n) =>
            val args: Seq[String] = n.operands.map(go(_, depth + 1))
            val shown: String = (n.op, args) match {
              case (op, Seq(a, b)) if Infix.contains(op) =>
                // A literal compared with a signal reads signal first: `state === 1`.
                val swap: Boolean = (op == "eq" || op == "neq") && isLit(n.operands.head) && !isLit(n.operands(1))
                if (swap) s"$b ${Infix(op)} $a" else s"$a ${Infix(op)} $b"
              case ("not", Seq(a))                 => s"~$a"
              case (op, a +: _) if Transparent(op) => a
              case ("bits", Seq(a, hi, lo))        => s"$a($hi, $lo)"
              case (op, as)                        => s"$op(${as.mkString(", ")})"
            }
            if (depth > 0 && shown.contains(' ')) s"($shown)" else shown
          case None => t
        }
      }
    }
    go(e, 0)
  }

  /** The innermost block among `whenIds` (outermost first, as on a `Conn`). */
  def blockOf(blocks: Seq[CodeBlock], whenIds: Seq[Int]): Option[String] =
    whenIds.reverseIterator.flatMap(id => blocks.find(_.whenIds.contains(id))).nextOption().map(_.id)
}
