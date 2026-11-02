package socle.lsp

import scala.meta._
import scala.util.Try

/** A ChiselSim scalatest case: `suite` is the FQCN, `name` the full scalatest
  * test name, `line` the 0-based line of the test's call site.
  */
case class TestCase(suite: String, name: String, line: Int)

/** Syntactic (parse-only) discovery of ChiselSim test cases. */
object TestDiscovery {

  private[lsp] def parse(source: String): Option[Source] =
    Try(dialects.Scala213(source).parse[Source]).toOption.flatMap {
      case Parsed.Success(t: Source) => Some(t)
      case _                         => None
    }

  /** The parents of a template as written (`a.b.Base`, `Base` for `Base[T](x)`). */
  private[lsp] def parents(t: Template): Seq[String] = {
    def name(tpe: Type): Option[String] = tpe match {
      case n: Type.Name   => Some(n.value)
      case s: Type.Select => Some(s.syntax)
      case a: Type.Apply  => name(a.tpe)
      case _              => None
    }
    t.inits.flatMap(i => name(i.tpe))
  }

  private[lsp] def simpleName(parent: String): String = parent.split('.').last

  /** `ChiselSim` or a base known to reach it, written simple or qualified. */
  private def extendsSim(t: Template, simBases: Set[String]): Boolean =
    parents(t).exists(p => simpleName(p) == "ChiselSim" || simBases(p) || simBases(simpleName(p)))

  private def isSimulate(fun: Term): Boolean = fun match {
    case Term.Name("simulate" | "simulateRaw")               => true
    case Term.Select(_, Term.Name("simulate" | "simulateRaw")) => true
    case Term.ApplyType.After_4_6_0(f, _)                    => isSimulate(f) // simulate[T](...)
    case _                                                   => false
  }

  /** Fallback when the base is unknown: the body calls `simulate(`/`simulateRaw(`. */
  private def callsSimulate(t: Template): Boolean =
    t.body.stats.exists(_.collect {
      case Term.Apply.After_4_6_0(fun, _) if isSimulate(fun) => ()
    }.nonEmpty)

  /** Whether the body sets a FlatSpec subject anywhere (`behavior of`,
    * `"S" <verb> ...`): a subclass's leading `it` tests inherit it. */
  private[lsp] def setsSubject(t: Template): Boolean =
    t.body.stats.exists(_.collect {
      case Term.ApplyInfix.After_4_6_0(Term.Name("behavior"), Term.Name("of"), _, _)          => ()
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("behavior"), Term.Name("of")), _)      => ()
      case Term.ApplyInfix.After_4_6_0(Lit.String(_), Term.Name(verb), _, _) if Verbs(verb) => ()
    }.nonEmpty)

  /** Every class/object/trait with its JVM binary name: package segments
    * joined by `.`, type nesting by `$`, and an object's own class with a
    * trailing `$` (as `getClass.getName` reports them). */
  private def definitions(tree: Tree, pkg: Seq[String], outer: Seq[String]): Seq[(Tree, String, Template)] = {
    def binary(name: String) = (pkg :+ (outer :+ name).mkString("$")).mkString(".")
    def nested(t: Tree, name: String, binName: String, templ: Template) =
      (t, binName, templ) +: templ.stats.flatMap(definitions(_, pkg, outer :+ name))
    tree match {
      case p: Pkg         => p.stats.flatMap(definitions(_, pkg :+ p.ref.syntax, outer))
      case c: Defn.Class  => nested(c, c.name.value, binary(c.name.value), c.templ)
      case o: Defn.Object => nested(o, o.name.value, binary(o.name.value) + "$", o.templ)
      case t: Defn.Trait  => nested(t, t.name.value, binary(t.name.value), t.templ)
      case s: Source      => s.stats.flatMap(definitions(_, pkg, outer))
      case _              => Nil
    }
  }

  /** ChiselSim test cases in `source`. A suite is a top-level concrete class
    * whose extends list names `ChiselSim` or one of `simBases` (the names a
    * [[SimBaseIndex]] found to reach it), or whose body calls `simulate(`.
    * A suite extending one of `subjectBases` inherits an unknown FlatSpec
    * subject: its `it` tests before its own subject get no lens. */
  def find(source: String, simBases: Set[String] = Set.empty, subjectBases: Set[String] = Set.empty): Seq[TestCase] =
    parse(source).toSeq.flatMap { src =>
      // Only top-level, concrete classes are runnable by `Runner -s <FQCN>`.
      topLevelClasses(src, Nil).collect {
        case (fq, templ) if extendsSim(templ, simBases) || callsSimulate(templ) =>
          cases(fq, templ, inheritsSubject = parents(templ).exists(p => subjectBases(p) || subjectBases(simpleName(p))))
      }.flatten
    }

  private def topLevelClasses(tree: Tree, prefix: Seq[String]): Seq[(String, Template)] =
    tree match {
      case s: Source => s.stats.flatMap(topLevelClasses(_, prefix))
      case p: Pkg    => p.stats.flatMap(topLevelClasses(_, prefix :+ p.ref.syntax))
      case c: Defn.Class if !c.mods.exists(_.is[Mod.Abstract]) =>
        Seq(((prefix :+ c.name.value).mkString("."), c.templ))
      case _ => Nil
    }

  private val Verbs = Set("should", "must", "can")

  private def literal(t: Term): Option[String] = t match {
    case Lit.String(s) => Some(s)
    case _             => None
  }

  /** `x taggedAs (T1) taggedAs (T2, T3)` is `x`: tags do not change the name. */
  @annotation.tailrec
  private def untagged(t: Term): Term = t match {
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("taggedAs"), _, _) => untagged(lhs)
    case _                                                            => t
  }

  private def cases(suite: String, templ: Template, inheritsSubject: Boolean): Seq[TestCase] = {
    val out = Seq.newBuilder[TestCase]
    // FlatSpec subject, reused by `it`/`they`: None when unknown (computed or
    // inherited), "" before any (scalatest then names the test "<verb> <text>")
    var subject: Option[String] = if (inheritsSubject) None else Some("")

    def add(scope: List[String], name: String, tree: Tree): Unit =
      out += TestCase(suite, (scope :+ name).mkString(" "), tree.pos.startLine)

    def walk(tree: Tree, scope: List[String]): Unit = tree match {
      // describe("X") { ... } nests
      case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("describe"), Term.ArgClause(List(Lit.String(s)), _)), body) =>
        body.values.foreach(walk(_, scope :+ s))
      // describe(<computed>) { ... }: the scope name is unknown, skip the subtree
      case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("describe"), _), _) => ()
      // it("x") { ... } / test("x") { ... }
      case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("it" | "test"), Term.ArgClause(Lit.String(s) :: _, _)), _) =>
        add(scope, s, tree)
      // behavior of "S" / behavior.of("S"): the subject of the next `it`/`they`
      case Term.ApplyInfix.After_4_6_0(Term.Name("behavior"), Term.Name("of"), _, Term.ArgClause(List(arg), _)) =>
        subject = literal(arg)
      case Term.Apply.After_4_6_0(Term.Select(Term.Name("behavior"), Term.Name("of")), Term.ArgClause(List(arg), _)) =>
        subject = literal(arg)
      // "A" should "x" in { ... } / it should "y" taggedAs (T) in { ... } / ignore should ...
      case Term.ApplyInfix.After_4_6_0(registered, Term.Name(op @ ("in" | "ignore")), _, _) =>
        untagged(registered) match {
          case Term.ApplyInfix.After_4_6_0(lhs, Term.Name(verb), _, Term.ArgClause(List(Lit.String(x)), _))
              if Verbs(verb) =>
            val subj = lhs match {
              case Lit.String(a)            => subject = Some(a); Some(a)
              case Term.Name("it" | "they") => subject
              case _                        => None // `ignore` and computed subjects
            }
            if (op == "in") subj.foreach(a => add(scope, (if (a.isEmpty) "" else s"$a ") + s"$verb $x", tree))
          case _ => tree.children.foreach(walk(_, scope))
        }
      // computed names live in loops: never report them; a def's tests
      // register at its call sites, under the subject current there
      case _: Term.For | _: Term.ForYield | _: Term.While | _: Term.Function | _: Term.PartialFunction | _: Defn.Def => ()
      case _ => tree.children.foreach(walk(_, scope))
    }

    templ.stats.foreach(walk(_, Nil))
    out.result()
  }

  /** Binary name of the innermost class/object/trait whose body spans `line`
    * (0-based), e.g. `a.b.Units$Adder` for `class Adder` inside `object Units`. */
  def enclosingClass(source: String, line: Int): Option[String] =
    parse(source).flatMap { src =>
      definitions(src, Nil, Nil)
        .filter { case (t, _, _) => t.pos.startLine <= line && line <= t.pos.endLine }
        .lastOption // definitions are in pre-order, so the last match is the innermost
        .map(_._2)
    }
}
