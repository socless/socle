package socle.layout

import socle.schema._

/** The design's modules by Scala class, then by parameterization: derived
  * data for the Hierarchy's class view, never written to design.json. Pure and
  * shared with the browser build.
  *
  * Two modules share a parameterization when they have the same class and the
  * same ordered (parameter name, digest) list, so Chisel's uniquified copies
  * of one class (`Core`, `Core_1`) with no parameters, or with equal values,
  * are one row. A parameter with an empty digest (a value not fully known)
  * makes its module's parameterization `unknown`: it is never merged, each
  * such module stands alone. Modules without a `scalaClass` (a design from the
  * CHIRRTL path) are left out, so such a design gives no group. */
object ClassGroups {

  /** One class: `library` for a Chisel library class (`chisel3.`...). */
  final case class ClassGroup(scalaClass: String, library: Boolean, parameterizations: Seq[Parameterization])

  /** One distinct parameterization: `params` are its first module's
    * (declaration order); `unknown` when a digest is empty, and then it holds
    * exactly one module. */
  final case class Parameterization(params: Seq[ParamDef], unknown: Boolean, modules: Seq[ClassModule])

  /** A module and every instance path that reaches it from the top, in
    * hierarchy order. A path is top first, as the UI navigates:
    * `List("DeepSoc", "cluster0", "cores_0")`; the top module's is
    * `List("DeepSoc")`. Empty when the top does not reach it. */
  final case class ClassModule(name: String, paths: Seq[List[String]])

  /** Classes by name, parameterizations by their first module in design order. */
  def of(design: Design): Seq[ClassGroup] = {
    val classed = design.modules.filter(_.scalaClass.nonEmpty)
    if (classed.isEmpty) Nil
    else {
      val paths = pathsOf(design)
      def member(m: ModuleDef) = ClassModule(m.name, paths.getOrElse(m.name, Nil))
      classed.groupBy(_.scalaClass).toSeq.sortBy(_._1).map { case (cls, mods) =>
        // groupBy keeps each group's design order.
        val (unknown, known) = mods.partition(_.parameterDecls.exists(_.digest.isEmpty))
        val keyOf = (m: ModuleDef) => m.parameterDecls.map(p => (p.name, p.digest))
        val merged = known.groupBy(keyOf).values.map(ms => ms.head.name -> Parameterization(ms.head.parameterDecls, unknown = false, ms.map(member)))
        val alone  = unknown.map(m => m.name -> Parameterization(m.parameterDecls, unknown = true, Seq(member(m))))
        val order  = mods.map(_.name).zipWithIndex.toMap
        ClassGroup(cls, cls.startsWith("chisel3."), (merged ++ alone).toSeq.sortBy(p => order(p._1)).map(_._2))
      }
    }
  }

  /** Module name -> instance paths from the top, depth first in child order. */
  private def pathsOf(design: Design): Map[String, Seq[List[String]]] = {
    val byName = design.modules.iterator.map(m => m.name -> m).toMap
    val out    = scala.collection.mutable.LinkedHashMap.empty[String, Vector[List[String]]]
    def walk(name: String, path: List[String]): Unit = byName.get(name).foreach { m =>
      out(name) = out.getOrElse(name, Vector.empty) :+ path
      m.children.foreach(c => walk(c.`type`, path :+ c.name))
    }
    walk(design.top, List(design.top))
    out.toMap
  }
}
