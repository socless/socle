package socle.ui.model

import socle.layout.ClassGroups
import socle.schema._
import utest._

/** The Classes tab's rows, on a design shaped like the captured DeepSoc. */
object ClassRowsTest extends TestSuite {
  private def mod(name: String, cls: String, params: Seq[(String, String, String)] = Nil,
                  children: Seq[(String, String)] = Nil) =
    ModuleDef(name, name, "", SourceRef.unknown, Nil,
      children.map { case (n, t) => InstanceDef(n, t, "", SourceRef.unknown) }, Nil, Metrics(0, 0, 0),
      scalaClass = cls, parameterDecls = params.map { case (n, v, d) => ParamDef(n, "Int", v, "ctor", d) })

  private val nCores = Seq(("nCores", "2", "c6"))
  private val deepSoc = Design("DeepSoc", Map.empty, Seq(
    mod("DeepSoc", "deephier.DeepSoc", children = Seq("cluster0" -> "Cluster", "cluster1" -> "Cluster_1", "pipe" -> "Queue4")),
    mod("Cluster", "deephier.Cluster", nCores, Seq("cores_0" -> "Core", "cores_1" -> "Core_1")),
    mod("Core", "deephier.Core"), mod("Core_1", "deephier.Core"),
    mod("Cluster_1", "deephier.Cluster", nCores, Seq("cores_0" -> "Core_2", "cores_1" -> "Core_3")),
    mod("Core_2", "deephier.Core"), mod("Core_3", "deephier.Core"),
    mod("Queue4", "chisel3.util.Queue", Seq(("entries", "4", "3e"), ("pipe", "false", "34")))))

  private def show(rows: Seq[ClassRows.Row]) =
    rows.map(r => ("  " * r.depth) + r.label + (if (r.count > 0) s" ${r.count}" else "") + (if (r.unknown) " ?" else ""))

  /** Every class of `d` opened. */
  private def allOpen(d: Design) = ClassGroups.of(d).map(g => s"class:${g.scalaClass}").toSet

  val tests = Tests {
    test("every class closed by default, user classes before the library's") {
      assert(show(ClassRows.build(ClassGroups.of(deepSoc), Set.empty)) ==
        Seq("Cluster 2", "Core 4", "DeepSoc 1", "Queue 1"))
      assert(ClassRows.build(ClassGroups.of(deepSoc), Set.empty).forall(r => r.expandable && !r.expanded))
    }
    test("opened classes: parameterizations open, single no-parameter level skipped") {
      assert(show(ClassRows.build(ClassGroups.of(deepSoc), allOpen(deepSoc))) == Seq(
        "Cluster 2",
        "  (nCores = 2) 2",
        "    cluster0",
        "    cluster1",
        "Core 4",
        "  cluster0/cores_0",
        "  cluster0/cores_1",
        "  cluster1/cores_0",
        "  cluster1/cores_1",
        "DeepSoc 1",
        "  DeepSoc",
        "Queue 1",
        "  (entries = 4, pipe = false) 1",
        "    pipe"))
    }
    test("toggling a parameterization closes it") {
      val rows = ClassRows.build(ClassGroups.of(deepSoc), Set("class:deephier.Cluster", "class:deephier.Cluster#0"))
      assert(show(rows).take(3) == Seq("Cluster 2", "  (nCores = 2) 2", "Core 4"))
      assert(!rows(1).expanded && rows(1).expandable)
    }
    test("instance rows navigate to their path and name their module") {
      val core = ClassRows.build(ClassGroups.of(deepSoc), allOpen(deepSoc)).filter(_.kind == "instance")
      assert(core.find(_.label == "cluster1/cores_0").map(r => (r.path, r.module, r.id)) ==
        Some((List("DeepSoc", "cluster1", "cores_0"), "Core_2", "DeepSoc/cluster1/cores_0")))
      assert(core.find(_.label == "DeepSoc").map(_.path) == Some(List("DeepSoc")))
    }
    test("an unknown value: its module stands alone, marked") {
      val d = Design("Top", Map.empty, Seq(
        mod("Top", "x.Top", children = Seq("a" -> "Q", "b" -> "Q_1")),
        mod("Q", "x.Q", Seq(("f", "<function>", ""))), mod("Q_1", "x.Q", Seq(("f", "<function>", "")))))
      val rows = ClassRows.build(ClassGroups.of(d), allOpen(d)).filter(_.kind == "params")
      assert(show(rows) == Seq("  (f = <function>) 1 ?", "  (f = <function>) 1 ?"))
      assert(rows.forall(_.title.endsWith(ClassRows.Unknown)))
    }
    test("no parameters, several parameterizations: the level stays") {
      val d = Design("Top", Map.empty, Seq(
        mod("Top", "x.Top", children = Seq("a" -> "Q", "b" -> "Q_1")),
        mod("Q", "x.Q"), mod("Q_1", "x.Q", Seq(("n", "1", "d1")))))
      assert(show(ClassRows.build(ClassGroups.of(d), allOpen(d))).take(5) ==
        Seq("Q 2", "  (no parameters) 1", "    a", "  (n = 1) 1", "    b"))
    }
    test("an export-mode design gives no row") {
      val d = Design("Top", Map.empty, Seq(mod("Top", "")))
      assert(ClassRows.build(ClassGroups.of(d), Set.empty).isEmpty)
    }
  }
}
