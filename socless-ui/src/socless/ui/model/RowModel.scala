package socless.ui.model

import socless.schema.{Design, ModuleDef, BundleTree}
import socless.js.BrowserLayout.LaidOut

/** Expansion row model for the schematic (issue #170 Phase B/C).
  *
  * The Scala home of the JS UI's `buildRows` / `boxRowsMap` / `pinY`
  * (ui/SoCLess.html:3353-3436): given a laid-out module, the set of expanded
  * port paths, and the design's bundle definitions, compute the ordered WEST/EAST
  * row lists each instance box renders (top-level pins plus the spliced-in
  * sub-fields of any expanded bundle) and the resulting grown box height. Reuses
  * the shared [[BundleTree]] walk so the schematic, the layout, and the parser
  * agree on bundle structure - no JS/Scala drift. */
object RowModel {

  /** One rendered port row. `dir`/`width` are populated for sub-rows; top-level
    * rows carry the pin's box-relative baked position (`pinX`/`pinYBaked`). */
  final case class Row(
    depth: Int, label: String, dir: Option[String], width: Int,
    path: String, bundleKey: String, isExpandable: Boolean, isTopLevel: Boolean,
    side: String, pinX: Double, pinYBaked: Double,
  )

  /** A box's WEST and EAST rows plus its expansion-aware height. */
  final case class BoxRows(westRows: Seq[Row], eastRows: Seq[Row], boxH: Double) {
    /** Box-relative y of each TOP-LEVEL pin path, used to override wire
      * terminals + feed the engine its grown pin rows. Mirrors the JS `pinY`
      * memo: each side's rows are indexed from 0. */
    def pinY(headerH: Double, portPad: Double, portStep: Double): Map[String, Double] = {
      def proc(rows: Seq[Row]): Seq[(String, Double)] =
        rows.zipWithIndex.collect {
          case (r, idx) if r.isTopLevel => r.path -> (headerH + portPad + idx * portStep + portStep / 2)
        }
      (proc(westRows) ++ proc(eastRows)).toMap.toSeq
    }.toMap
  }

  /** Build the per-instance row model for `layout`. Boundary stubs are excluded
    * (their pins are engine-placed and never row-expanded, issue #95). */
  def build(layout: LaidOut, module: ModuleDef, design: Design, expandedPorts: Set[String],
            headerH: Double, portPad: Double, portStep: Double): Map[String, BoxRows] = {
    layout.nodes.iterator.filter(_.kind != "boundary").map { box =>
      val inst      = box.id
      val instType  = module.children.find(_.name == inst).map(_.`type`)
      val ports     = box.ports

      def buildRows(side: String): Seq[Row] = {
        val rows = scala.collection.mutable.ListBuffer.empty[Row]
        // Dedup by path: a box can carry overlapping pins (e.g. the whole `io`
        // bundle AND a sub-path), so expanding both would emit a duplicate row.
        // First path wins.
        val seen = scala.collection.mutable.Set.empty[String]
        def pushRow(r: Row): Boolean =
          if (seen.contains(r.path)) false else { seen += r.path; rows += r; true }

        // Backend-precomputed expandable (issue #170), OR a cheap bundle/Vec
        // check for older design.json without the field.
        def expandableOf(bundle: String, flag: Boolean): Boolean =
          flag || BundleTree.isAggregateType(bundle, design)

        ports.filter(_.side == side).foreach { p =>
          val added = pushRow(Row(
            depth = 0, label = if (p.label.nonEmpty) p.label else p.path, dir = None, width = 0,
            path = p.path, bundleKey = p.bundle, isExpandable = expandableOf(p.bundle, p.expandable),
            isTopLevel = true, side = side, pinX = p.x - box.x, pinYBaked = p.y - box.y,
          ))
          if (added && p.bundle.nonEmpty && expandedPorts.contains(p.path)) {
            // The pin's true direction comes from the instance type's port
            // declaration (issue #85: null here rendered wrong/missing arrows).
            val baseDir = instType.flatMap(t => BundleTree.resolvePinDir(t, p.path, design))
            val tree    = BundleTree.portTree(p.bundle, baseDir, p.path, design)
            def addChildren(children: Seq[BundleTree.PortNode], depth: Int): Unit =
              children.foreach { child =>
                val ok = pushRow(Row(
                  depth = depth, label = child.name, dir = child.dir, width = child.width,
                  path = child.path, bundleKey = child.bundleKey, isExpandable = child.isExpandable,
                  isTopLevel = false, side = side, pinX = 0, pinYBaked = 0,
                ))
                if (ok && child.isExpandable && expandedPorts.contains(child.path)) {
                  val subTree = BundleTree.portTree(child.bundleKey, child.dir, child.path, design)
                  addChildren(subTree.children, depth + 1)
                }
              }
            addChildren(tree.children, 1)
          }
        }
        rows.toSeq
      }

      val westRows = buildRows("WEST")
      val eastRows = buildRows("EAST")
      val numRows  = math.max(1, math.max(westRows.length, eastRows.length))
      val boxH     = headerH + 2 * portPad + numRows * portStep
      inst -> BoxRows(westRows, eastRows, boxH)
    }.toMap
  }
}
