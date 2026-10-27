package socless.ui.model

import socless.schema.{Design, ModuleDef, BundleTree, NodeKind}
import socless.layout.LayoutCore
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
    isGroupHeader: Boolean = false, // a scattered-bundle group header: label only, no pin/chevron
    locked: Boolean = false,        // always-open, non-collapsible (no chevron)
    drawPin: Boolean = false,       // render a pin dot at this (nested) row - a connected scattered leaf
    ownedPins: Seq[String] = Nil,   // laid pins folded under this collapsed row: their wires end here
  )

  /** A box's WEST and EAST rows plus its expansion-aware height. */
  final case class BoxRows(westRows: Seq[Row], eastRows: Seq[Row], boxH: Double) {
    /** Box-relative y of each TOP-LEVEL pin path, used to override wire
      * terminals + feed the engine its grown pin rows. Mirrors the JS `pinY`
      * memo: each side's rows are indexed from 0. */
    def pinY(headerH: Double, portPad: Double, portStep: Double): Map[String, Double] = {
      // With no expansion the baked pin positions are exact and authoritative -
      // crucially they honor an op's EAST output pin laid at the center of its
      // inputs (LayoutCore), which the index-based formula would miss. Only when
      // a bundle is expanded (sub-rows shift the top-level pins) do we fall back
      // to index-based positions.
      val expanded = (westRows ++ eastRows).exists(!_.isTopLevel)
      if (!expanded)
        (westRows ++ eastRows).iterator.filter(_.isTopLevel).map(r => r.path -> r.pinYBaked).toMap
      else {
        // Top-level pins AND connected scattered-leaf rows (`drawPin`) get a
        // position: a scattered field is its own laid pin, so its wire must
        // terminate at the field row, not walk up to the group header (#237).
        // A collapsed row also positions the laid pins folded under it, so their
        // wires (and the engine's pin overrides) end on that row, not on the header.
        def proc(rows: Seq[Row]): Seq[(String, Double)] =
          rows.zipWithIndex.collect {
            case (r, idx) if r.isTopLevel || r.drawPin =>
              val y = headerH + portPad + idx * portStep + portStep / 2
              (r.path +: r.ownedPins).map(_ -> y)
          }.flatten
        (proc(westRows) ++ proc(eastRows)).toMap.toSeq.toMap
      }
    }
  }

  /** Build the per-instance row model for `layout`. Boundary stubs are excluded
    * (their pins are engine-placed and never row-expanded, issue #95). */
  def build(layout: LaidOut, module: ModuleDef, design: Design, expandedPorts: Set[String],
            headerH: Double, portPad: Double, portStep: Double): Map[String, BoxRows] = {
    layout.nodes.iterator.filter(_.kind != "boundary").map { box =>
      val inst      = box.id
      val instType  = module.children.find(_.name == inst).map(_.`type`)
      val ports     = box.ports

      // All laid-port paths on this box, for the scatter test.
      val pinPaths: Set[String] = ports.iterator.map(_.path).toSet
      // Count distinct box pins strictly under `prefix` (prefix + "." or "[").
      def pinsUnder(prefix: String): Int =
        pinPaths.count(p => p != prefix && (p.startsWith(prefix + ".") || p.startsWith(prefix + "[")))
      // A node scatters when >= 2 of the box's pins fall strictly under it.
      def scattered(path: String): Boolean = pinsUnder(path) >= 2

      def buildRows(side: String): Seq[Row] = {
        val rows = scala.collection.mutable.ListBuffer.empty[Row]
        // Dedup by path: a box can carry overlapping pins (e.g. the whole `io`
        // bundle AND a sub-path), so expanding both would emit a duplicate row.
        // First path wins; a scattered-group header and a plain pin may share a
        // path - the header is pushed first by design.
        val seen = scala.collection.mutable.Set.empty[String]
        def pushRow(r: Row): Boolean =
          if (seen.contains(r.path)) false else { seen += r.path; rows += r; true }

        // Backend-precomputed expandable (issue #170), OR a cheap bundle/Vec
        // check for older design.json without the field.
        def expandableOf(bundle: String, flag: Boolean): Boolean =
          flag || BundleTree.isAggregateType(bundle, design)

        val dirWanted = if (side == "WEST") socless.schema.PortDir.In else socless.schema.PortDir.Out

        // Keep only subtree nodes that carry at least one leaf of `dirWanted`.
        def keepsDir(n: BundleTree.PortNode): Boolean =
          if (n.children.isEmpty) n.dir.contains(dirWanted)
          else n.children.exists(keepsDir)

        // Emit a node under a scattered parent, applying the scatter test per node.
        def emitNode(n: BundleTree.PortNode, depth: Int): Unit = {
          if (!keepsDir(n)) return
          val isAgg = n.isExpandable
          if (isAgg && scattered(n.path)) {
            // scattered sub-bundle: locked, always-open header, recurse
            pushRow(Row(
              depth = depth, label = n.name, dir = n.dir, width = 0,
              path = n.path, bundleKey = n.bundleKey, isExpandable = true,
              isTopLevel = false, side = side, pinX = 0, pinYBaked = 0,
              isGroupHeader = true, locked = true,
            ))
            n.children.foreach(c => emitNode(c, depth + 1))
          } else {
            // a normal pin: a leaf, or a non-scattered (wholesale) sub-bundle the user
            // can still expand/collapse on demand via expandedPorts (NOT locked).
            val laid = ports.find(_.path == n.path)
            // A collapsed sub-bundle stands for the laid pins under it (`acc_i` for
            // `acc_i.data`); once expanded, its child rows take them over.
            val owned =
              if (isAgg && !expandedPorts.contains(n.path))
                ports.iterator.filter(p => p.side == side && (p.path.startsWith(n.path + ".") || p.path.startsWith(n.path + "[")))
                  .map(_.path).toSeq.sorted
              else Nil
            val added = pushRow(Row(
              depth = depth, label = n.name, dir = n.dir, width = n.width,
              path = n.path, bundleKey = n.bundleKey, isExpandable = isAgg,
              isTopLevel = false, side = side,
              pinX = laid.map(l => (l.x - box.x).toDouble).getOrElse(0.0),
              pinYBaked = laid.map(l => (l.y - box.y).toDouble).getOrElse(0.0),
              drawPin = laid.isDefined || owned.nonEmpty, // connected -> own pin dot; unconnected -> label-only stub
              ownedPins = owned,
            ))
            if (added && isAgg && expandedPorts.contains(n.path)) {
              val sub = BundleTree.portTree(n.bundleKey, n.dir, n.path, design)
              sub.children.foreach(c => emitNode(c, depth + 1))
            }
          }
        }

        // The owning instance port `<inst>.<port>` of a pin path. A scattered
        // bundle surfaces as its individual field pins (e.g. `p.io.a`, `p.io.b`)
        // with NO `p.io` pin, so the group is detected on this ancestor, not on
        // the leaf, and the scatter count spans BOTH sides (input fields voted
        // WEST, output fields EAST).
        def topPort(path: String): String = {
          val rest = if (path.startsWith(inst + ".")) path.substring(inst.length + 1) else path
          val seg0 = LayoutCore.pathHead(rest)
          inst + "." + seg0
        }
        // Each scattered owning port is emitted as one locked group per side.
        val handledGroups = scala.collection.mutable.Set.empty[String]

        ports.filter(_.side == side).foreach { p =>
          val anc = topPort(p.path)
          if (BundleTree.isAggregateType(p.bundle, design) && scattered(anc)) {
            // Scattered bundle: render ONE locked direction group for the owning
            // port `anc`, once per side. Its field pins under `anc` are emitted by
            // emitNode walking the port's bundle tree filtered to this direction.
            // Build the kept children first and only push the header when this
            // side has at least one field, so a side with no fields of this
            // direction does not leave a dangling empty group header.
            if (!handledGroups.contains(anc)) {
              handledGroups += anc
              val portName = anc.substring(inst.length + 1)
              val baseDir  = instType.flatMap(t => BundleTree.resolvePinDir(t, anc, design))
              val tree     = BundleTree.portTree(p.bundle, baseDir, anc, design)
              val keptChildren = tree.children.filter(keepsDir)
              if (keptChildren.nonEmpty) {
                pushRow(Row(
                  depth = 0,
                  label = portName + (if (side == "WEST") " (in)" else " (out)"),
                  dir = None, width = 0, path = anc, bundleKey = p.bundle, isExpandable = true,
                  isTopLevel = true, side = side, pinX = 0, pinYBaked = 0,
                  isGroupHeader = true, locked = true,
                ))
                keptChildren.foreach(c => emitNode(c, 1))
              }
            }
          } else {
            // Non-scattered bundle: the original on-demand expansion, unchanged from pre-#237.
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
        }
        rows.toSeq
      }

      val westRows = buildRows("WEST")
      val eastRows = buildRows("EAST")
      val numRows  = math.max(1, math.max(westRows.length, eastRows.length))
      // Op/reg logic nodes use LayoutCore's compact geometry (no header band), so
      // their rendered height must match the shorter laid box, not the instance
      // header+rows formula (issue #252). They never expand, so this stays stable.
      val isLogic  = box.kind == NodeKind.Op || box.kind == NodeKind.Reg
      val boxH =
        if (isLogic) LayoutCore.LogicHeaderH + 2 * LayoutCore.LogicPortPad + numRows * LayoutCore.LogicPortStep
        else headerH + 2 * portPad + numRows * portStep
      inst -> BoxRows(westRows, eastRows, boxH)
    }.toMap
  }
}
