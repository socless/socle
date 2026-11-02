package socle.layout

import socle.schema._

/** Folds generator copies (children sharing a `foldGroup`) into one synthetic
  * instance and retargets the module's wires onto it. Pure and shared with the
  * browser build. The classic CHIRRTL view never sets a fold group, so [[fold]]
  * is the identity there. */
object InstanceFold {

  private val CountRe = """^\s*\d+\s*×\s*""".r

  /** Group id -> member names, in child order. */
  def groups(module: ModuleDef): Seq[(String, Seq[String])] = {
    val grouped = module.children.filter(_.foldGroup.nonEmpty)
    grouped.map(_.foldGroup).distinct.map(g => g -> grouped.filter(_.foldGroup == g).map(_.name))
  }

  /** Fold every group not in `unfolded` (unknown ids are ignored). */
  def fold(module: ModuleDef, unfolded: Set[String]): ModuleDef = {
    val members: Map[String, Seq[InstanceDef]] =
      module.children.filter(c => c.foldGroup.nonEmpty && !unfolded(c.foldGroup)).groupBy(_.foldGroup)
    if (members.isEmpty) module
    else {
      val groupOf = members.flatMap { case (g, ms) => ms.map(_.name -> g) }
      val children = module.children.flatMap { c =>
        if (!groupOf.contains(c.name)) Some(c)
        else {
          val ms = members(c.foldGroup)
          if (ms.head.name == c.name) Some(c.copy(name = c.foldGroup, foldGroup = "", foldCount = ms.size))
          else None
        }
      }
      def head(path: String): String = LayoutCore.stripIdx(path.takeWhile(_ != '.'))
      def retarget(path: String): String = groupOf.get(head(path)) match {
        case Some(g) => g + path.dropWhile(_ != '.')
        case None    => path
      }
      val kept = module.wires
        .filterNot { w =>
          val f = groupOf.get(head(w.from))
          f.isDefined && f == groupOf.get(head(w.to))
        }
        .map(w => w.copy(from = retarget(w.from), to = retarget(w.to)))
      val byEnds = kept.groupBy(w => (w.from, w.to))
      val wires = kept.filter(w => byEnds((w.from, w.to)).head eq w).map { w =>
        val same = byEnds((w.from, w.to))
        if (same.size == 1) w
        else {
          val sum = same.map(x => LayoutCore.groundCount(x.label)).sum
          w.copy(label = (s"$sum× " + CountRe.replaceFirstIn(w.label, "")).trim)
        }
      }
      // A tie on a folded copy's pin lands on the group's pin; the copies'
      // identical ties merge.
      val tieOffs = module.tieOffs.map(t => t.copy(sink = retarget(t.sink))).distinct
      module.copy(children = children, wires = wires, tieOffs = tieOffs)
    }
  }
}
