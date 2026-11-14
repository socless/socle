package socle.ui.model

import socle.layout.ClassGroups
import socle.schema._
import utest._

/** The shared class grouping, as the browser build compiles it. */
object ClassGroupsTest extends TestSuite {
  private def mod(name: String, cls: String, digests: Seq[String], children: Seq[(String, String)] = Nil) =
    ModuleDef(name, name, "", SourceRef.unknown, Nil,
      children.map { case (n, t) => InstanceDef(n, t, "", SourceRef.unknown) }, Nil, Metrics(0, 0, 0),
      scalaClass = cls, parameterDecls = digests.map(d => ParamDef("n", "Int", "?", "ctor", d)))

  val tests = Tests {
    test("merge, split, unknown and paths") {
      val d = Design("Top", Map.empty, Seq(
        mod("Top", "x.Top", Nil, Seq("a" -> "Q", "b" -> "Q_1", "c" -> "Q_2")),
        mod("Q", "x.Q", Seq("d4")), mod("Q_1", "x.Q", Seq("")), mod("Q_2", "x.Q", Seq("d4"))))
      val q = ClassGroups.of(d).find(_.scalaClass == "x.Q").get
      assert(q.parameterizations.map(p => (p.modules.map(_.name), p.unknown)) ==
        Seq(Seq("Q", "Q_2") -> false, Seq("Q_1") -> true))
      assert(q.parameterizations.head.modules.flatMap(_.paths) == Seq(List("Top", "a"), List("Top", "c")))
    }
  }
}
