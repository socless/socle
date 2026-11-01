package socless.ui.model

import socless.schema.{Design, ModuleDef}
import socless.layout.PortModel
import socless.js.BrowserLayout.LaidOut

/** The port rows each box of a laid-out module renders (#332).
  *
  * The layout lays out every shown row as a pin ([[PortModel]] through
  * `LayoutCore.prepare`, with the module's open paths), so a box's rows are its
  * laid pins in order on each side, each where the layout placed it. The
  * declared-port metadata (depth, name, direction, open state) comes from the
  * same [[PortModel]] rows; any other pin (a logic node's, or a wire end no
  * declared row contains) is a plain top-level row. */
object RowModel {

  /** One rendered port row, at its laid pin (`pinX`/`pinYBaked`, box-relative). */
  final case class Row(
    depth: Int, label: String, dir: Option[String], width: Int,
    path: String, bundleKey: String, isExpandable: Boolean, isTopLevel: Boolean,
    side: String, pinX: Double, pinYBaked: Double,
    open: Boolean = false,
  )

  /** A box's WEST and EAST rows plus its height. */
  final case class BoxRows(westRows: Seq[Row], eastRows: Seq[Row], boxH: Double) {
    /** Box-relative y of each row's pin: where the layout placed it. */
    def pinY(headerH: Double, portPad: Double, portStep: Double): Map[String, Double] =
      (westRows ++ eastRows).iterator.map(r => r.path -> r.pinYBaked).toMap
  }

  /** The rows of every box and edge stub of `layout`. */
  def build(layout: LaidOut, module: ModuleDef, design: Design, expandedPorts: Set[String],
            headerH: Double, portPad: Double, portStep: Double): Map[String, BoxRows] = {
    val modules = design.modules.iterator.map(m => m.name -> m).toMap
    val ends = PortModel.wireEndsOf(module)
    val declared: Map[String, PortModel.Row] =
      (module.children.iterator.flatMap(c =>
        modules.get(c.`type`).iterator.flatMap(m => PortModel.instanceRows(c.name, m, design, expandedPorts, ends))) ++
        PortModel.boundaryStubs(module, design, expandedPorts, ends).iterator.flatMap(_._2))
        .map(r => r.path -> r).toMap
    layout.nodes.iterator.map { box =>
      def rowsOf(side: String): Seq[Row] =
        box.ports.filter(_.side == side).sortBy(_.y).map { p =>
          val px = (p.x - box.x).toDouble
          val py = (p.y - box.y).toDouble
          declared.get(p.path) match {
            case Some(r) =>
              // A top row keeps the layout's `name: type` label; a field its own name.
              val label = if (r.depth == 0 && p.label.nonEmpty) p.label else r.label
              Row(r.depth, label, r.dir, r.width, r.path, r.bundle, r.expandable, r.depth == 0,
                side, px, py, r.open)
            case None =>
              Row(0, if (p.label.nonEmpty) p.label else p.path, None, 0, p.path, p.bundle,
                isExpandable = false, isTopLevel = true, side, px, py)
          }
        }
      box.id -> BoxRows(rowsOf("WEST"), rowsOf("EAST"), box.h.toDouble)
    }.toMap
  }
}
