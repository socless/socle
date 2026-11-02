package socle.layout

import socle.schema._

/** The declared ports of a box (or of the module itself, as edge stubs) as the
  * rows the schematic shows (#332). Pure and shared by the JVM bake and the
  * browser, so both lay out the same pins.
  *
  * Each declared port is expanded with [[BundleTree.portTree]]. A top-level
  * Record port (Chisel's `val io = IO(new Bundle {...})`) is dissolved: its
  * channels are the top rows. A row opens in place when its path is in the
  * module's open set; its children keep its side, so rows never move.
  */
object PortModel {

  /** One shown row. `path` is the full endpoint path ("decode.io.in", "io.in.bits.a"),
    * `label` its name below its parent row ("in", "bits.a", "[0]"), `depth` 0 for a
    * top row. `side` is PinSide.West/East; children of an open row have their
    * parent's side. `dir` is the leaf's direction seen from the node (PortDir.In/Out;
    * None for aggregates). `open` is true for an open aggregate. */
  final case class Row(path: String, label: String, depth: Int, side: String, dir: Option[String],
                       width: Int, bundle: String, expandable: Boolean, open: Boolean)

  /** The rows of instance box `inst` of module type `instType`, in declaration order.
    * `wireEnds` are the module's wire end paths ([[wireEndsOf]]): a Record port a
    * wire ends on as a whole is not dissolved. */
  def instanceRows(inst: String, instType: ModuleDef, design: Design, expanded: Set[String],
                   wireEnds: Set[String] = Set.empty): Seq[Row] =
    topNodes(instType, inst + ".", design, wireEnds).flatMap { case (node, label, side) =>
      emit(node, label, 0, side, expanded)
    }

  /** The module's own ports as edge stubs: (stub id = its top row's path, its rows),
    * in declaration order. Sides are mirrored, so a stub's pins face the design: an
    * input drives in from the left column with its pins on the EAST. */
  def boundaryStubs(module: ModuleDef, design: Design, expanded: Set[String],
                    wireEnds: Set[String] = Set.empty): Seq[(String, Seq[Row])] =
    topNodes(module, "", design, wireEnds).map { case (node, label, side) =>
      node.path -> emit(node, label, 0, mirror(side), expanded)
    }

  /** A Vec whose elements are signals, however deeply nested (`Vec[UInt<8>, 4]`,
    * `Vec[Vec[UInt<1>, 2], 3]`), not of bundles: drawn as stacked tabs (#334). */
  def isVecOfSignals(typeKey: String, design: Design): Boolean = typeKey match {
    case VecKey(inner) =>
      val i = inner.trim
      if (i.startsWith("Vec[")) isVecOfSignals(i, design) else !BundleTree.isAggregateType(i, design)
    case _ => false
  }
  private val VecKey = """^Vec\[(.+),\s*\d+\]$""".r

  /** The rows of a register of bundle type: its D side (`id`, West) and its Q
    * side (`id~q`, East), each opening to its fields. None for a ground register,
    * whose pins stay its wire ends. */
  def regRows(n: LogicNodeDef, design: Design, expanded: Set[String]): Seq[Row] =
    if (n.kind != NodeKind.Reg || !BundleTree.isAggregateType(n.bundle, design)) Nil
    else emit(BundleTree.portTree(n.bundle, Some(PortDir.In), n.id, design), "", 0, PinSide.West, expanded) ++
         emit(BundleTree.portTree(n.bundle, Some(PortDir.Out), n.id + "~q", design), "", 0, PinSide.East, expanded)

  /** Every wire end path of `module`, for `wireEnds`. */
  def wireEndsOf(module: ModuleDef): Set[String] =
    module.wires.iterator.flatMap(w => Iterator(w.from, w.to)).toSet

  /** The path of the deepest shown row containing `endpoint`, or None. */
  def attach(endpoint: String, rows: Seq[Row]): Option[String] =
    rows.iterator
      .filter(r => endpoint == r.path || endpoint.startsWith(r.path + ".") || endpoint.startsWith(r.path + "["))
      .maxByOption(_.path.length)
      .map(_.path)

  private val HiddenTypes = Set("Clock", "Reset", "AsyncReset")

  private def hidden(p: PortDef, design: Design): Boolean =
    HiddenTypes(p.bundle) ||
      ((p.name == "clock" || p.name == "reset") && !BundleTree.isAggregateType(p.bundle, design))

  /** The top nodes of a module's ports, with their labels and box sides. */
  private def topNodes(m: ModuleDef, prefix: String, design: Design,
                       wireEnds: Set[String]): Seq[(BundleTree.PortNode, String, String)] =
    m.ports.filterNot(hidden(_, design)).flatMap { p =>
      val base = if (p.dir == PortDir.Out || p.dir == PortDir.Master) PortDir.Out else PortDir.In
      val role = Some(p.dir).filter(d => d == PortDir.Master || d == PortDir.Slave)
      val tree = BundleTree.portTree(p.bundle, Some(base), prefix + p.name, design)
      // A port a wire ends on as a whole (`a.io <> b.io`, an AXI4 master to the
      // crossbar) keeps its row: that wire has nowhere else to land.
      // Chisel's `io` wrapper is dissolved even when its fields all go one way.
      val wrapper = isRecord(tree, design) ||
        (p.name == "io" && design.bundles.get(p.bundle).exists(_.kind == BundleKind.Bundle))
      if (wrapper && !wireEnds(tree.path))
        tree.children.map(c => (c, c.name, sideOf(c, None, design)))
      else Seq((tree, p.name, sideOf(tree, role, design)))
    }

  private def emit(n: BundleTree.PortNode, label: String, depth: Int, side: String,
                   expanded: Set[String]): Seq[Row] = {
    val expandable = n.isExpandable && n.children.nonEmpty
    val open       = expandable && expanded(n.path)
    val row = Row(n.path, label, depth, side, if (expandable) None else n.dir, n.width, n.bundleKey, expandable, open)
    if (!open) Seq(row)
    else row +: n.children.flatMap(c => emit(c, c.name, depth + 1, side, expanded))
  }

  private def mirror(side: String) = if (side == PinSide.West) PinSide.East else PinSide.West

  private def isDecoupled(n: BundleTree.PortNode, design: Design) =
    design.bundles.get(n.bundleKey).exists(_.kind == BundleKind.Decoupled)

  private def isVec(n: BundleTree.PortNode) = n.bundleKey.startsWith("Vec[")

  private def leafDirs(n: BundleTree.PortNode): Seq[Option[String]] =
    if (n.children.isEmpty) Seq(n.dir) else n.children.flatMap(leafDirs)

  /** A Bundle whose leaves go both ways (or that has none), other than a Decoupled. */
  private def isRecord(n: BundleTree.PortNode, design: Design): Boolean =
    design.bundles.get(n.bundleKey).exists(_.kind == BundleKind.Bundle) &&
      (n.children.isEmpty || leafDirs(n).distinct.size > 1)

  /** The Decoupled channels anywhere under `n`, as their `valid` directions. */
  private def decoupledDirs(n: BundleTree.PortNode, design: Design): Seq[Option[String]] =
    if (isDecoupled(n, design)) Seq(n.dir) else n.children.flatMap(decoupledDirs(_, design))

  /** The way a node's data flows (PortDir.Out or In), from the spec's rules. */
  private def flow(n: BundleTree.PortNode, role: Option[String], design: Design): Option[String] =
    if (n.children.isEmpty || isDecoupled(n, design)) n.dir
    else if (isVec(n)) flow(n.children.head, role, design)
    else {
      val leaves = leafDirs(n)
      if (leaves.distinct.size == 1) leaves.head
      else {
        val dec = decoupledDirs(n, design)
        if (dec.nonEmpty) majority(dec, dec.head)
        else role match {
          case Some(PortDir.Master) => Some(PortDir.Out)
          case Some(PortDir.Slave)  => Some(PortDir.In)
          case _                    => majority(leaves, n.dir)
        }
      }
    }

  private def majority(dirs: Seq[Option[String]], tie: Option[String]): Option[String] = {
    val out = dirs.count(_.contains(PortDir.Out))
    val in  = dirs.count(_.contains(PortDir.In))
    if (out > in) Some(PortDir.Out) else if (in > out) Some(PortDir.In) else tie
  }

  private def sideOf(n: BundleTree.PortNode, role: Option[String], design: Design): String =
    if (flow(n, role, design).contains(PortDir.Out)) PinSide.East else PinSide.West
}
