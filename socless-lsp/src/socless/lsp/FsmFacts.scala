package socless.lsp

import scala.collection.mutable.ArrayBuffer
import scala.meta._
import socless.capture.FsmSource
import socless.schema.{FsmDiagram, FsmState, FsmTransition, SourceRef}

/** Chisel `switch(register) { is(...) { ... } }` state machines, syntax
  * only, for [[ScalaSourceIndex.fsm]]. Each template's facts are collected
  * once at parse time, as plain data (names, positions, condition texts); a
  * query only combines them.
  *
  * Labels are the source text of the enclosing `when` chain within the
  * `is` case (or the whole body, outside every case), outermost first:
  * `when(c)` gives `c`, `.elsewhen(d)` after `c` gives `!c && d`,
  * `.otherwise` after `c, d` gives `!c && !d`. A negation is `!x` for a
  * plain name or select, else `!(x)`; a top-level `||` condition is
  * parenthesized when joined to others. A `switch` on another register
  * nested in a case adds no condition.
  *
  * A next-state wire `n` (an assigned local val the register takes
  * unconditionally, `reg := n`, or through `RegNext(n)` / `RegEnable(n, ...)`) counts like the
  * register: `n := sX` is a transition, and `n := reg`, `reg := n` are holds. */
private[lsp] object FsmFacts {
  final case class Pos(line: Int, col: Int)

  /** A state reference, `sIdle` or `S.idle` (`qual` is then `S`). */
  final case class Ref(qual: Option[String], name: String)

  /** An `is(...)` case; `states` is `None` when an argument is computed. */
  final case class Case(states: Option[Seq[Ref]], pos: Pos)

  /** A `switch(subject)`; `subject` is empty unless it is a plain name. */
  final case class Switch(subject: String, pos: Pos, cases: Seq[Case])

  /** A `when` condition's text, and whether it is a top-level `||`. */
  final case class Part(text: String, isOr: Boolean)

  /** An enclosing `is` case: its (switch, case) indices in this template's
    * `switches`, and how many of the assignment's `parts` lie outside it. */
  final case class InCase(switch: Int, cse: Int, depth: Int)

  /** `reg := to`; `to` is `None` when computed. `parts` are all its enclosing
    * `when` conditions in the body, outermost first; `cases` its enclosing
    * `is` cases, innermost first. */
  final case class Assign(reg: String, to: Option[Ref], parts: Seq[Part], cases: List[InCase], pos: Pos)

  /** `reg = RegNext(next[, init])` or `RegEnable(next[, init], en)`. */
  final case class RegNextOf(reg: String, next: String, init: Option[Ref])

  /** One template's facts. `chiselEnum` holds the values of an object that
    * extends `ChiselEnum`; `vals` the names its `val`s define. */
  final case class Facts(
      switches: Seq[Switch], assigns: Seq[Assign], enums: Seq[Seq[String]],
      regInits: Seq[(String, Ref)], chiselEnum: Option[Seq[String]],
      vals: Set[String], regNexts: Seq[RegNextOf])

  def collect(stats: Seq[Stat], isChiselEnum: Boolean): Facts = {
    val switches = ArrayBuffer.empty[(String, Pos, ArrayBuffer[Case])]
    val assigns  = Seq.newBuilder[Assign]
    val enums    = Seq.newBuilder[Seq[String]]
    val regInits = Seq.newBuilder[(String, Ref)]
    val vals     = Set.newBuilder[String]
    val regNexts = Seq.newBuilder[RegNextOf]

    def go(t: Tree, parts: Vector[Part], cases: List[InCase]): Unit = t match {
      case _: Defn.Class | _: Defn.Trait | _: Defn.Object => () // its own template
      case v: Defn.Val =>
        v.pats.foreach { case p: Pat.Var => vals += p.name.value; case _ => }
        (v.pats, v.rhs) match {
          case (List(p), Call("Enum", _)) => consNames(p).foreach(enums += _)
          case (List(p: Pat.Var), Call("RegInit", args)) => args.lastOption.flatMap(ref).foreach(r => regInits += p.name.value -> r)
          case (List(p: Pat.Var), Call("RegNext", Term.Name(n) :: rest)) if rest.size <= 1 =>
            regNexts += RegNextOf(p.name.value, n, rest.headOption.flatMap(ref))
          case (List(p: Pat.Var), Call("RegEnable", Term.Name(n) :: rest)) if rest.size == 1 || rest.size == 2 =>
            regNexts += RegNextOf(p.name.value, n, if (rest.size == 2) ref(rest.head) else None)
          case _ =>
        }
        go(v.rhs, parts, cases)
      case Curried(Term.Name("switch"), List(subject), List(body)) =>
        val idx   = switches.size
        val found = ArrayBuffer.empty[Case]
        switches += ((subject match { case Term.Name(n) => n; case _ => "" }, pos(t), found))
        val stats = body match { case b: Term.Block => b.stats; case s => List(s) }
        stats.foreach {
          case c @ Curried(Term.Name("is"), args, List(block)) =>
            val refs = args.map(ref)
            found += Case(Option.when(refs.forall(_.isDefined))(refs.flatten), pos(c))
            go(block, parts, InCase(idx, found.size - 1, parts.size) :: cases)
          case s => go(s, parts, cases)
        }
      case WhenChain(arms) =>
        arms.indices.foreach { k =>
          val negated = arms.take(k).flatMap(_._1).map(negate)
          go(arms(k)._2, parts ++ negated ++ arms(k)._1.map(part), cases)
        }
      case a: Term.ApplyInfix if a.op.value == ":=" =>
        a.lhs match {
          case Term.Name(reg) =>
            assigns += Assign(reg, a.argClause.values.headOption.flatMap(ref), parts, cases, pos(a))
          case _ =>
        }
        a.argClause.values.foreach(go(_, parts, cases))
      case _ => t.children.foreach(go(_, parts, cases))
    }

    stats.foreach(go(_, Vector.empty, Nil))
    val values = Option.when(isChiselEnum)(stats.collect {
      case v: Defn.Val if isValue(v.rhs) => v.pats.collect { case p: Pat.Var => p.name.value }
    }.flatten)
    Facts(switches.map { case (s, p, c) => Switch(s, p, c.toSeq) }.toSeq, assigns.result(), enums.result(),
      regInits.result(), values, vals.result(), regNexts.result())
  }

  /** The diagram on `register` from the owned bodies' facts (with their
    * files, in order), or `None` when no switch on it is readable.
    * `enumValues` gives the values of each ChiselEnum object of a simple name. */
  def source(bodies: Seq[(String, Facts)], register: String, enumValues: String => Seq[Seq[String]]): Option[FsmSource] = {
    def at(file: String, p: Pos) = SourceRef(file, p.line, p.col)
    val switches = for ((file, f) <- bodies; s <- f.switches if s.subject == register) yield (file, s)
    val cases    = switches.flatMap { case (file, s) => s.cases.map(file -> _) }
    if (switches.isEmpty || cases.exists(_._2.states.isEmpty)) None
    else {
      val args     = cases.flatMap(_._2.states.get)
      val argNames = args.map(_.name).distinct
      val fromEnum =
        if (args.exists(_.qual.isDefined)) None
        else bodies.flatMap(_._2.enums).find(e => argNames.forall(e.contains))
      val fromChiselEnum = args.flatMap(_.qual).distinct match {
        case Seq(obj) if args.forall(_.qual.isDefined) => enumValues(obj).find(v => argNames.forall(v.contains))
        case _                                         => None
      }
      val names    = fromEnum.orElse(fromChiselEnum).getOrElse(argNames)
      val regNexts = bodies.flatMap(_._2.regNexts).filter(_.reg == register)
      val initial = bodies.flatMap(_._2.regInits).find(_._1 == register).map(_._2.name)
        .orElse(regNexts.flatMap(_.init).headOption.map(_.name)).filter(names.contains)
      // Next-state wires: `register = RegNext(n)`, or `register := n` outside
      // every `when` and every `is` case with `n` itself assigned (`n := ...`);
      // `n` is a local val that is not a state.
      val vals     = bodies.flatMap(_._2.vals).toSet
      val assigned = bodies.flatMap(_._2.assigns.map(_.reg)).toSet
      val aliases = (regNexts.map(_.next) ++ (for {
        (_, f) <- bodies
        a      <- f.assigns if a.reg == register && a.parts.isEmpty && a.cases.isEmpty
        to     <- a.to if to.qual.isEmpty && assigned.contains(to.name)
      } yield to.name)).toSet.filter(n => n != register && vals.contains(n) && !names.contains(n))
      val regs = aliases + register
      val states = names.map { n =>
        val first = cases.find(_._2.states.get.exists(_.name == n))
        FsmState(n, initial.contains(n), first.fold(SourceRef.unknown) { case (file, c) => at(file, c.pos) })
      }
      var computed = 0
      val transitions = for {
        (file, f) <- bodies
        a         <- f.assigns if regs.contains(a.reg) && !a.to.exists(t => t.qual.isEmpty && regs.contains(t.name))
        t <- a.to.map(_.name).filter(names.contains) match {
          case None =>
            computed += 1
            Nil
          case Some(to) =>
            // The innermost case of a switch on `register`; its label starts at that case.
            val (from, parts) = a.cases.find(c => f.switches(c.switch).subject == register) match {
              case Some(c) => (f.switches(c.switch).cases(c.cse).states.get.map(_.name), a.parts.drop(c.depth))
              case None    => (Seq("*"), a.parts)
            }
            from.map(FsmTransition(_, to, label(parts), at(file, a.pos)))
        }
      } yield t
      val note =
        if (computed == 0) ""
        else if (computed == 1) "1 transition to a computed state is not shown."
        else s"$computed transitions to computed states are not shown."
      val (file, first) = switches.head
      Some(FsmSource(FsmDiagram(states, transitions, note), at(file, first.pos)))
    }
  }

  private def pos(t: Tree) = Pos(t.pos.startLine + 1, t.pos.startColumn + 1)

  private def text(t: Tree) = t.pos.text.trim.replaceAll("\\s+", " ")

  private def isRef(t: Term): Boolean = t match {
    case _: Term.Name          => true
    case Term.Select(q, _)     => isRef(q)
    case _                     => false
  }

  private def ref(t: Term): Option[Ref] = t match {
    case Term.Name(n)                                  => Some(Ref(None, n))
    case Term.Select(q, Term.Name(n)) if isRef(q)      => Some(Ref(Some(q.syntax.split('.').last), n))
    case _                                             => None
  }

  private def part(c: Term) = Part(text(c), c match {
    case i: Term.ApplyInfix => i.op.value == "||"
    case _                  => false
  })

  private def negate(c: Term) = Part(if (isRef(c)) s"!${text(c)}" else s"!(${text(c)})", isOr = false)

  private def label(parts: Seq[Part]): String = parts match {
    case Seq(p) => p.text
    case _      => parts.map(p => if (p.isOr) s"(${p.text})" else p.text).mkString(" && ")
  }

  /** `a :: b :: ... :: Nil` as its names. */
  private def consNames(p: Pat): Option[List[String]] = p match {
    case x: Pat.ExtractInfix if x.op.value == "::" => (x.lhs, x.argClause.values) match {
      case (v: Pat.Var, List(rest)) => consNames(rest).map(v.name.value :: _)
      case _                        => None
    }
    case n: Term.Name if n.value == "Nil" => Some(Nil)
    case _                                => None
  }

  private def isValue(t: Term): Boolean = t match {
    case Term.Name("Value")                     => true
    case a: Term.Apply                          => a.fun match { case Term.Name("Value") => true; case _ => false }
    case _                                      => false
  }

  /** `f(args)`, `pkg.f(args)`: the simple name and the arguments. */
  private object Call {
    def unapply(t: Term): Option[(String, List[Term])] = t match {
      case a: Term.Apply => a.fun match {
        case Term.Name(n)                 => Some((n, a.argClause.values))
        case Term.Select(_, Term.Name(n)) => Some((n, a.argClause.values))
        case _                            => None
      }
      case _ => None
    }
  }

  /** `f(a1...)(a2...)`. */
  private object Curried {
    def unapply(t: Tree): Option[(Term, List[Term], List[Term])] = t match {
      case outer: Term.Apply => outer.fun match {
        case inner: Term.Apply => Some((inner.fun, inner.argClause.values, outer.argClause.values))
        case _                 => None
      }
      case _ => None
    }
  }

  /** A `when` chain, matched at its outermost call: each arm's condition
    * (`None` for `.otherwise`) and body, in order. */
  private object WhenChain {
    def unapply(t: Tree): Option[List[(Option[Term], Term)]] = t match {
      case Curried(Term.Name("when"), List(c), List(b)) => Some(List((Some(c), b)))
      case Curried(Term.Select(prev, Term.Name("elsewhen")), List(c), List(b)) =>
        unapply(prev).map(_ :+ ((Some(c), b)))
      case o: Term.Apply => (o.fun, o.argClause.values) match {
        case (Term.Select(prev, Term.Name("otherwise")), List(b)) => unapply(prev).map(_ :+ ((None, b)))
        case _                                                    => None
      }
      case _ => None
    }
  }
}
