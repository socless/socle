package socle.layout

import socle.capture.{CapturedModel, ModelToDesign}
import socle.schema._
import utest._

object ClassGroupsTest extends TestSuite {
  private def mod(name: String, cls: String, params: Seq[ParamDef] = Nil, children: Seq[(String, String)] = Nil) =
    ModuleDef(name, name, "", SourceRef.unknown, Nil,
      children.map { case (n, t) => InstanceDef(n, t, "", SourceRef.unknown) }, Nil, Metrics(0, 0, 0),
      scalaClass = cls, parameterDecls = params)
  private def p(name: String, digest: String) = ParamDef(name, "Int", "?", "ctor", digest)
  private def design(top: String, mods: ModuleDef*) = Design(top, Map.empty, mods)

  private def deepsoc: Design = {
    val s = getClass.getResourceAsStream("/captured/deepsoc.json")
    try ModelToDesign.design(CapturedModel.fromJson(scala.io.Source.fromInputStream(s, "UTF-8").mkString))
    finally s.close()
  }

  val tests = Tests {
    test("equal digests merge, different digests split") {
      val d = design("Top",
        mod("Top", "x.Top", children = Seq("a" -> "Q", "b" -> "Q_1", "c" -> "Q_2")),
        mod("Q", "x.Q", Seq(p("n", "d4"))), mod("Q_1", "x.Q", Seq(p("n", "d8"))), mod("Q_2", "x.Q", Seq(p("n", "d4"))))
      val q = ClassGroups.of(d).find(_.scalaClass == "x.Q").get
      assert(q.parameterizations.map(_.modules.map(_.name)) == Seq(Seq("Q", "Q_2"), Seq("Q_1")))
      assert(q.parameterizations.forall(!_.unknown), !q.library)
      assert(q.parameterizations.head.modules.map(_.paths) == Seq(Seq(List("Top", "a")), Seq(List("Top", "c"))))
    }

    test("the key is the ordered names and digests") {
      val d = design("A", mod("A", "x.A", Seq(p("a", "1"), p("b", "2"))), mod("A_1", "x.A", Seq(p("b", "2"), p("a", "1"))))
      assert(ClassGroups.of(d).head.parameterizations.size == 2)
    }

    test("an empty digest never merges") {
      val d = design("Q", mod("Q", "x.Q", Seq(p("f", ""))), mod("Q_1", "x.Q", Seq(p("f", ""))), mod("Q_2", "x.Q", Seq(p("f", "1"))))
      val ps = ClassGroups.of(d).head.parameterizations
      assert(ps.map(pz => (pz.modules.map(_.name), pz.unknown)) ==
        Seq(Seq("Q") -> true, Seq("Q_1") -> true, Seq("Q_2") -> false))
    }

    test("a class with no parameters is one parameterization") {
      val d = design("C", mod("C", "x.C"), mod("C_1", "x.C"), mod("C_2", "x.C"))
      val ps = ClassGroups.of(d).head.parameterizations
      assert(ps.size == 1, ps.head.modules.map(_.name) == Seq("C", "C_1", "C_2"), !ps.head.unknown, ps.head.params.isEmpty)
    }

    test("classes sort by name, library classes are flagged") {
      val d = design("Z", mod("Z", "z.Top"), mod("Q", "chisel3.util.Queue"), mod("A", "a.A"))
      val gs = ClassGroups.of(d)
      assert(gs.map(_.scalaClass) == Seq("a.A", "chisel3.util.Queue", "z.Top"))
      assert(gs.map(_.library) == Seq(false, true, false))
    }

    test("a design from the CHIRRTL path gives no group") {
      val d = design("G", mod("G", ""), mod("H", ""))
      assert(ClassGroups.of(d).isEmpty)
    }

    test("deepsoc: classes, parameterizations and instance paths") {
      val gs = ClassGroups.of(deepsoc).map(g => g.scalaClass -> g).toMap
      val cluster = gs("deephier.Cluster").parameterizations
      assert(cluster.size == 1)
      assert(cluster.head.params.map(_.name) == Seq("nCores"), cluster.head.params.head.value == "2")
      assert(cluster.head.modules == Seq(
        ClassGroups.ClassModule("Cluster", Seq(List("DeepSoc", "cluster0"))),
        ClassGroups.ClassModule("Cluster_1", Seq(List("DeepSoc", "cluster1")))))
      val core = gs("deephier.Core").parameterizations
      assert(core.size == 1)
      assert(core.head.modules.map(m => m.name -> m.paths) == Seq(
        "Core"   -> Seq(List("DeepSoc", "cluster0", "cores_0")),
        "Core_1" -> Seq(List("DeepSoc", "cluster0", "cores_1")),
        "Core_2" -> Seq(List("DeepSoc", "cluster1", "cores_0")),
        "Core_3" -> Seq(List("DeepSoc", "cluster1", "cores_1"))))
      assert(gs("deephier.DeepSoc").parameterizations.head.modules.head.paths == Seq(List("DeepSoc")))
      assert(gs("chisel3.util.Queue").library, !gs("deephier.Cluster").library)
      assert(gs("chisel3.util.Queue").parameterizations.map(_.unknown) == Seq(false))
    }
  }
}
