package socless.lsp

import scala.meta._
import scala.util.Try

/** A ChiselSim scalatest case: `suite` is the FQCN, `name` the full scalatest
  * test name, `line` the 0-based line of the test's call site.
  */
case class TestCase(suite: String, name: String, line: Int)

/** Syntactic (parse-only) discovery of ChiselSim test cases. */
object TestDiscovery {

  private def parse(source: String): Option[Source] =
    Try(dialects.Scala213(source).parse[Source]).toOption.flatMap {
      case Parsed.Success(t: Source) => Some(t)
      case _                         => None
    }

  private def isChiselSim(t: Template): Boolean =
    t.inits.exists(_.tpe match {
      case Type.Name("ChiselSim")                => true
      case Type.Select(_, Type.Name("ChiselSim")) => true
      case _                                     => false
    })

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

  def find(source: String): Seq[TestCase] =
    parse(source).toSeq.flatMap { src =>
      // Only top-level, concrete classes are runnable by `Runner -s <FQCN>`.
      topLevelClasses(src, Nil).collect {
        case (fq, templ) if isChiselSim(templ) => cases(fq, templ)
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

  private def cases(suite: String, templ: Template): Seq[TestCase] = {
    val out = Seq.newBuilder[TestCase]
    var subject: Option[String] = None // FlatSpec subject, reused by `it`/`they`

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
      // "A" should "x" in { ... } / it should "y" in { ... }
      case Term.ApplyInfix.After_4_6_0(
            Term.ApplyInfix.After_4_6_0(lhs, Term.Name(verb), _, Term.ArgClause(List(Lit.String(x)), _)),
            Term.Name("in"), _, _) if Verbs(verb) =>
        val subj = lhs match {
          case Lit.String(a)                 => subject = Some(a); Some(a)
          case Term.Name("it" | "they")      => subject
          case _                             => None
        }
        subj.foreach(a => add(scope, s"$a $verb $x", tree))
      // computed names live in loops: never report them
      case _: Term.For | _: Term.ForYield | _: Term.While | _: Term.Function | _: Term.PartialFunction => ()
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
