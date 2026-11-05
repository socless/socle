package socle.layout

import socle.schema._
import socle.ir.Synthetic
import scala.collection.mutable

/** Pure, engine-independent schematic layout logic (issue #167).
  *
  * Everything here is free of the Eclipse Layout Kernel Java API, so it
  * cross-compiles to Scala.js and is shared by both the JVM backend
  * ([[Layout]], which feeds it to ELK) and the in-browser layout
  * (`socle-js`, which feeds it to elkjs). Keeping the wire-endpoint
  * resolution, pin-side voting, node/port geometry and anti-parallel edge
  * collapse in one place means the two engines cannot drift.
  *
  * [[prepare]] is the seam: it turns a module + design into a fully resolved
  * [[Prepared]] graph (nodes with sizes and per-pin coordinates, edges oriented
  * source->sink). The only thing left for a concrete engine to do is build its
  * own node/port/edge objects from this and run orthogonal routing.
  */
object LayoutCore {

  // ── Header / port-row geometry (single source of truth, emitted via consts) ──
  val NodeW: Double    = 160.0
  // Logic-op / register nodes render as a compact centered symbol (issue #203),
  // not a header+label box, so they get a small fixed width and the symbol's
  // stubs reach the WEST/EAST pins closely instead of crossing a wide footprint.
  val LogicNodeW: Double = 80.0
  // Logic nodes (op/reg) render as a centered symbol with NO header label band,
  // so they use a tighter top margin and pin step than instance boxes - this
  // keeps logic-heavy schematics compact instead of stacking module-sized boxes
  // (issue #252). Height and pin Y in `prepare` use these in lockstep, and the
  // UI's RowModel reads them for the op/reg box height so the two agree.
  val LogicHeaderH: Double = 10.0
  val LogicPortPad:  Double = 8.0
  val LogicPortStep: Double = 18.0
  val PortStep: Double = 24.0
  val PortPad: Double  = 18.0
  /** Height of the component header band (name + type). */
  val HeaderH: Double  = 28.0
  /** The header/port-row layout constants, packaged for the schema (issue #38). */
  val consts: LayoutConsts = LayoutConsts(HeaderH, PortStep, PortPad)
  /** The pin model `prepare` lays out, stamped on a bake (`Design.layoutModel`):
    * 1 since pins are the declared port rows (#332); 2 since wires end on the
    * pins' centres, block pins follow their peers and layers sit in a debug band
    * (#334); 3 since instances keep rows for their clk and rst pins and logic
    * blocks are baked as modules to open; 4 since a code-block box (not an FSM)
    * shows its condition pins on WEST, wired or not. Bump it when a bake's pins
    * change meaning, so the UI lays older bakes out again. */
  val LayoutModel: Int = 4
  val BoundaryW: Double = 16.0
  val BoundaryH: Double = 28.0
  val LabelGap: Double  = 24.0   // horizontal gap between widest WEST and EAST label columns
  val MaxNodeW: Double  = 520.0  // cap so layout doesn't explode on very long labels

  def stripIdx(s: String): String = {
    val i = s.indexOf('[')
    if (i < 0) s else s.substring(0, i)
  }

  /** The first segment of a dotted path, before any `.` or `[`: the port of
    * `io.in.bits`, the instance of `cpu.io`, the Vec of `v[3].a`. */
  def pathHead(path: String): String = path.takeWhile(c => c != '.' && c != '[')

  /** The owning node id of a wire-endpoint head (a `path.split('.').head`).
    *
    * A logic node appears in wires under synthetic pin heads - an op input pin
    * `<node>~in<i>` or a register Q pin `<node>~q` - which must reduce to the
    * bare node id (the id its box is keyed by). A constant node id is itself a
    * `const$<sink>` atom whose sanitized sink can legitimately contain `~in`, so
    * it is never reduced (it matches only by its exact id); everything else is
    * returned unchanged. */
  def nodeBaseId(head: String): String =
    if (head.startsWith(Synthetic.ConstPrefix)) head else Synthetic.baseOf(head)

  /** Does a wire-endpoint head belong to a verification-layer node (issue #212)?
    *
    * The UI hides a layer node's box by its node id, then must hide the wires
    * touching it. A wire keeps a synthetic pin head (`<node>~in<i>`, `<node>~q`)
    * that never matches the bare id, so testing the raw head against `layerIds`
    * leaves a layer node's incoming wires visible as dangling stubs into the now
    * hidden box. Resolving the head to its node id via [[nodeBaseId]] first keeps
    * box and wire visibility in lockstep. */
  def isLayerHead(head: String, layerIds: Set[String]): Boolean =
    layerIds.contains(head) || layerIds.contains(nodeBaseId(head))

  /** The grouping path of a wire endpoint under a given expansion set: truncate
    * `path` at the shallowest Vec index `[n]` whose enclosing prefix is NOT in
    * `expanded`. A fully-expanded chain returns the leaf path unchanged; a path
    * with no Vec index is returned unchanged (issue #260). */
  def groupPath(path: String, expanded: Set[String]): String = {
    val idx = """\[\d+\]""".r
    var search = 0
    while (true) {
      idx.findFirstMatchIn(path.substring(search)) match {
        case None => return path
        case Some(m) =>
          val brk    = search + m.start          // position of '[' for this index
          val prefix = path.substring(0, brk)    // path up to but not including '['
          if (!expanded.contains(prefix)) return prefix
          search = search + m.end                // descend past this (expanded) index
      }
    }
    path // unreachable; satisfies the type checker
  }

  /** Label for a collapsed Vec group: `name[a:b]` for a contiguous index set
    * (half-open, Chisel slice style), `name[N×]` for a non-contiguous set, and
    * `name[i]` for a singleton. */
  def vecRangeLabel(name: String, indices: Seq[Int]): String = {
    val xs = indices.distinct.sorted
    if (xs.size == 1) s"$name[${xs.head}]"
    else if (xs.last - xs.head == xs.size - 1) s"$name[${xs.head}:${xs.last + 1}]"
    else s"$name[${xs.size}×]"
  }

  /** A pin sits on the EAST (right/driving) side when its port drives signals
    * rightward in the left-to-right schematic: instance outputs/masters, and
    * this module's own boundary INPUTS (which drive signals into the design).
    * Receiving pins (instance inputs/slaves, boundary outputs) sit on WEST. */
  def sideFor(kind: String, dir: String): String = (kind, dir) match {
    // An FSM box is laid out exactly like an instance: an output pin drives
    // rightward (EAST), everything else receives (WEST) (issue #210). `dir` is the
    // canonical schema PortDir vocabulary (in/out/master/slave/inout).
    case (NodeKind.Inst, PortDir.Out) | (NodeKind.Inst, PortDir.Master) => PinSide.East
    case (k, PortDir.Out | PortDir.Master) if NodeKind.isBox(k)         => PinSide.East
    // A boundary slave comes from `input io` (AXI-aware portRole), which maps to
    // PortDir.In, so by this file's own convention it drives rightward like any
    // boundary input. Without this case AXI slave boundary stubs fell through to
    // WEST: their anti-parallel traffic always ties the side vote and the
    // tie-break uses this declared side (issue #102).
    case (NodeKind.Boundary, PortDir.In) | (NodeKind.Boundary, PortDir.Slave) => PinSide.East
    case _                                                                     => PinSide.West
  }

  private val VecKey = """^Vec\[(.+),\s*\d+\]$""".r

  def isInterfaceBundle(bundle: String, design: Design): Boolean =
    design.bundles.get(bundle).exists(b => b.kind == BundleKind.Bundle || b.kind == BundleKind.Decoupled) ||
      // Vec-of-bundle keys ("Vec[AXI4, 2]") are not entries in design.bundles
      // but their pins are interfaces all the same (issue #89).
      (bundle match {
        case VecKey(inner) => isInterfaceBundle(inner.trim, design)
        case _             => false
      })

  def nodeHeight(westN: Int, eastN: Int): Double = {
    val maxRows = math.max(1, math.max(westN, eastN))
    math.max(HeaderH + 2 * PortPad + PortStep, HeaderH + 2 * PortPad + maxRows * PortStep)
  }

  /** Logic block `id` (its body `Design.regions(id)`) as a module to open: its
    * pins are the ports, and the body's wires end on them by name instead of
    * `<id>.<pin>`. A pin named like a member (an out pin after the register that
    * drives it) is renamed `<pin>_pin`, keeping its name as its label. Its type
    * is the block's `label` (`logic`), as the chip and the Inspector show it. */
  def blockAsModule(id: String, body: ModuleDef, label: String = ""): ModuleDef = {
    val members = body.logic.iterator.map(_.id).toSet
    val taken   = scala.collection.mutable.Set.empty[String] ++ members ++ body.ports.map(_.name)
    val renamed = body.ports.iterator.filter(p => members(p.name)).map { p =>
      val n = (s"${p.name}_pin" #:: LazyList.from(2).map(i => s"${p.name}_pin$i")).find(!taken(_)).get
      taken += n
      p.name -> n
    }.toMap
    val prefix = s"$id."
    def end(e: String): String =
      if (!e.startsWith(prefix)) e
      else { val pin = e.drop(prefix.length); renamed.getOrElse(pin, pin) }
    body.copy(
      name  = id,
      `type` = if (label.nonEmpty) label else body.`type`,
      ports = body.ports.map(p => renamed.get(p.name).fold(p)(n => p.copy(name = n, label = if (p.label.nonEmpty) p.label else p.name))),
      wires = body.wires.map(w => w.copy(from = end(w.from), to = end(w.to))))
  }

  /** The logic blocks of `design` that open as modules, by id (not FSMs, which
    * open their state diagram). */
  def openableBlocks(design: Design): Seq[(String, ModuleDef)] = {
    val ids = (design.modules.iterator ++ design.regions.valuesIterator).flatMap(_.logic).filter(_.kind == NodeKind.Block).map(_.id).toSet
    design.regions.toSeq.filter { case (id, _) => ids(id) }.sortBy(_._1).map { case (id, b) => id -> blockAsModule(id, b) }
  }

  /** How many clock and reset pins an instance of `m` shows (0 to 2): a `Clock`
    * port, then a `Reset` or `AsyncReset` one (a ground port named `clock` or
    * `reset` counts too, as a top module's implicit reset is a Bool). */
  def clockRows(m: ModuleDef): Int = (if (hasClockPort(m)) 1 else 0) + (if (hasResetPort(m)) 1 else 0)
  private def bit(p: PortDef) = p.bundle == "UInt<1>" || p.bundle == "UInt"
  def hasClockPort(m: ModuleDef): Boolean = m.ports.exists(p => p.bundle == "Clock")
  def hasResetPort(m: ModuleDef): Boolean =
    m.ports.exists(p => p.bundle == "Reset" || p.bundle == "AsyncReset" || (p.name == "reset" && bit(p)))
  /** The clock domains of `m`, in order of first use: its instances' clocks,
    * then its registers'. Empty when it has fewer than two, as one domain is
    * not worth a colour. */
  def clockDomains(m: ModuleDef): Seq[String] = {
    val ds: Seq[String] =
      (m.children.iterator.map(_.clock) ++ m.logic.iterator.filter(_.kind == NodeKind.Reg).map(_.clock))
        .filter(_.nonEmpty).toSeq.distinct
    if (ds.size < 2) Nil else ds
  }

  /** A domain sink's label: the domain it stands for, after an arrow (`→ fall`). */
  def domainLabel(n: LogicNodeDef): String = s"→ ${n.label}"

  /** Whether `m`'s reset is asynchronous. */
  def asyncReset(m: ModuleDef): Boolean = m.ports.exists(_.bundle == "AsyncReset")

  /** Estimate rendered pixel width of `text` in the UI's small monospace font. */
  def labelPx(text: String): Double = text.length * 6.5 + 14.0

  private val ClockResetBundles = Set("Clock", "Reset", "AsyncReset")
  def isClockReset(w: WireDef): Boolean = ClockResetBundles.contains(w.bundle)

  /** Compute display label for a pin.
    * shortPath = the endpoint path with the leading "<nodeId>." stripped.
    * If the pin has a non-generic bundle, label = "shortPath: bundle";
    * otherwise label = shortPath. */
  def pinLabel(nodeId: String, path: String, bundle: Option[String]): String = {
    val shortPath = if (path.startsWith(nodeId + ".")) path.drop(nodeId.length + 1) else path
    bundle match {
      case Some(b) if b.nonEmpty && b != "Wire" => s"$shortPath: $b"
      case _                                     => shortPath
    }
  }

  /** Display label for a memory pin: "<tag> addr" / "<tag> data" from the node's
    * memInfo (issue #205). Falls back to the raw path if unresolvable. */
  def memPinLabel(module: ModuleDef, memId: String, path: String): String = {
    val head = stripIdx(path.split('.').headOption.getOrElse(""))
    Synthetic.memPinParts(head) match {
      case Some((_, port, role)) =>
        val tag = module.logic.find(_.id == memId).flatMap(_.memInfo)
          .flatMap(_.ports.find(_.name == port)).map(_.tag).getOrElse(port)
        val r = role match { case "a" => "addr"; case "w" | "r" => "data"; case x => x }
        s"$tag $r"
      case None => path
    }
  }

  /** A resolved wire endpoint. `nodeId` is the box that owns the pin (instance
    * name, or the FULL endpoint path for a boundary stub: since issue #68 each
    * external pin is its own per-path boundary node), `kind` is "inst" or
    * "boundary", `path` is the full endpoint path used as the pin id, `side`
    * is "WEST"/"EAST", and `interface` flags aggregate bundles. `bundle` is the
    * pin's own-port bundle key (the instance/boundary port's declared type, or a
    * register's registered type), or empty for bundleless logic nodes
    * (const/op/fsm). */
  case class Endpoint(nodeId: String, kind: String, path: String, side: String,
                      interface: Boolean, bundle: String)

  /** Resolve `path` against `module`. Returns None for internal nets / unknown
    * heads (those endpoints are dropped, as in IrWalk). */
  def classify(path: String, module: ModuleDef, design: Design): Option[Endpoint] =
    classifyWith(path, module, design, design.modules.iterator.map(m => m.name -> m).toMap, new ClassifyIndex(module))

  /** Per-module lookups for [[classifyWith]], built once per module rather than
    * once per endpoint (rebuilding them per call was quadratic on large modules).
    * Maps keep the FIRST element per key, matching the `find` they replace. */
  final class ClassifyIndex(module: ModuleDef) {
    private def firstBy[A](xs: Seq[A])(key: A => String): Map[String, A] = {
      val b = scala.collection.mutable.LinkedHashMap.empty[String, A]
      xs.foreach(x => if (!b.contains(key(x))) b(key(x)) = x)
      b.toMap
    }
    private def ids(p: LogicNodeDef => Boolean): Set[String] = module.logic.iterator.filter(p).map(_.id).toSet
    val children: Map[String, InstanceDef] = firstBy(module.children)(_.name)
    val ports: Map[String, PortDef]        = firstBy(module.ports)(_.name)
    val logicById: Map[String, LogicNodeDef] = firstBy(module.logic)(_.id)
    val regs: Map[String, LogicNodeDef]    = firstBy(module.logic.filter(_.kind == NodeKind.Reg))(_.id)
    val regIds: Set[String]   = regs.keySet
    val constIds: Set[String] = ids(_.kind == NodeKind.Const)
    val opIds: Set[String]    = ids(_.kind == NodeKind.Op)
    val memIds: Set[String]   = ids(_.kind == NodeKind.Mem)
    val domainIds: Set[String] = ids(_.kind == NodeKind.Domain)
    val fsmIds: Set[String]   = ids(n => NodeKind.isBox(n.kind))
  }

  def classifyWith(path: String, module: ModuleDef, design: Design, modulesByName: Map[String, ModuleDef],
                   ix: ClassifyIndex): Option[Endpoint] = {
    val parts = path.split('.').toIndexedSeq
    if (parts.isEmpty) return None
    val head = stripIdx(parts.head)
    val instOf = ix.children.get(head)
    if (instOf.isDefined && parts.length >= 2) {
      val childMod = modulesByName.get(instOf.get.`type`)
      val portName = stripIdx(parts(1))
      val portDef  = childMod.flatMap(_.ports.find(_.name == portName))
      val dir      = portDef.map(_.dir).getOrElse(PortDir.In)
      val bundle   = portDef.map(_.bundle).getOrElse("Wire")
      Some(Endpoint(head, NodeKind.Inst, path, sideFor(NodeKind.Inst, dir), isInterfaceBundle(bundle, design), bundle))
    } else {
      // Intra-module logic nodes: registers (a D input pin `r`/`r.field` on WEST
      // and a Q output pin `r~q`/`r~q.field` on EAST) and constants (single EAST
      // driver pin keyed by the whole `const$...` atom). These resolve before the
      // boundary-port fallthrough so a logic node is never mistaken for a port and
      // is never dropped (issue #188 B1).
      import ix.{regIds, constIds, opIds, memIds}
      // An FSM box has named boundary pins arriving as `<fsmId>.<pin>`, exactly
      // like an instance: the head segment is the box id. Resolve it before the
      // boundary-port fallthrough so the pins are never mistaken for ports or
      // dropped (issue #210).
      val fsmIds   = ix.fsmIds
      def regIface(id: String): Boolean =
        ix.regs.get(id).exists(n => isInterfaceBundle(n.bundle, design))
      def regBundle(id: String): String =
        ix.regs.get(id).map(_.bundle).getOrElse("")
      val rawHead = stripIdx(parts.head)
      if (rawHead.endsWith("~q") && regIds.contains(rawHead.dropRight(2)))
        return Some(Endpoint(rawHead.dropRight(2), NodeKind.Reg, path, PinSide.East, regIface(rawHead.dropRight(2)), regBundle(rawHead.dropRight(2))))
      if (regIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Reg, path, PinSide.West, regIface(rawHead), regBundle(rawHead)))
      if (constIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Const, path, PinSide.East, false, ""))
      // A domain sink: its one pin receives the clock or reset (WEST).
      if (ix.domainIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Domain, path, PinSide.West, false, ""))
      Synthetic.memPinParts(rawHead) match {
        case Some((memId, _, role)) if memIds.contains(memId) =>
          // Memory pins: read-data drives rightward (EAST); address + write-data
          // receive (WEST). Bundleless, like other logic-node pins.
          val side = if (role == "r") PinSide.East else PinSide.West
          return Some(Endpoint(memId, NodeKind.Mem, path, side, false, ""))
        case _ => ()
      }
      val inIdx = rawHead.indexOf("~in")
      if (inIdx > 0 && opIds.contains(rawHead.substring(0, inIdx)))
        return Some(Endpoint(rawHead.substring(0, inIdx), NodeKind.Op, path, PinSide.West, false, ""))
      if (opIds.contains(rawHead))
        return Some(Endpoint(rawHead, NodeKind.Op, path, PinSide.East, false, ""))
      if (fsmIds.contains(rawHead) && parts.length >= 2) {
        // Box pin direction lives in the stored region body's port list. An out
        // pin drives rightward (EAST), an in/inout pin receives (WEST); the
        // dataflow vote in `prepare` refines this, this is just the declared side.
        val pinName = stripIdx(parts(1))
        val dir     = design.regions.get(rawHead).flatMap(_.ports.find(_.name == pinName)).map(_.dir).getOrElse(PortDir.In)
        // FSM box pins carry no bundle: the synthesized region ports store no
        // bundle key, so a pin reflects its own (bundleless) port rather than
        // the wire's aggregate type (#237).
        val boxKind = ix.logicById.get(rawHead).map(_.kind).getOrElse(NodeKind.Fsm)
        return Some(Endpoint(rawHead, boxKind, path, sideFor(boxKind, dir), false, ""))
      }
      ix.ports.get(head).map { p =>
        // Each external pin is its OWN boundary node (keyed by full path), not
        // grouped under the port head. A single shared node would force all of
        // a module's I/O into one layer, so inputs could not sit on the left
        // and outputs on the right; wires then route backward or span the whole
        // canvas (issue #68). Per-pin stubs let ELK rank input-sources left and
        // output-sinks right with forward-flowing wires.
        Endpoint(path, NodeKind.Boundary, path, sideFor(NodeKind.Boundary, p.dir), isInterfaceBundle(p.bundle, design), p.bundle)
      }
    }
  }

  // ── Prepared graph: the engine-agnostic layout input ────────────────────────

  /** One pin on a [[PreparedNode]]. For instance nodes `x`/`y` are the explicit
    * FIXED_POS coordinates (relative to the node box). For boundary stubs the
    * engine places the pin from `side`, so `x`/`y` are unused (0). `label` and
    * `bundle` carry through to the laid-out port. */
  case class PreparedPin(path: String, side: String, interface: Boolean, bundle: String,
                         label: String, x: Double, y: Double, expandable: Boolean)

  /** One box: an instance component (`kind == NodeKind.Inst`, ports at fixed rows)
    * or a boundary stub (`kind == NodeKind.Boundary`, ports placed on their side).
    *
    * `layerConstraint` pins boundary stubs into a dedicated first/last ELK layer
    * so all external inputs align into one leftmost column and all outputs into
    * one rightmost column, neither sharing a column with an instance (issue #163).
    * The value is an ELK `LayerConstraint` name ("FIRST_SEPARATE" /
    * "LAST_SEPARATE"), applied by each engine. The SEPARATE variants (not plain
    * FIRST/LAST) are required: a bidirectional boundary pin (e.g. a Decoupled
    * output carrying `ready` back) has a "wrong-direction" edge that plain
    * FIRST/LAST reject; SEPARATE tolerates it. None = unconstrained (instances
    * rank by dataflow). */
  case class PreparedNode(id: String, kind: String, width: Double, height: Double,
                          pins: Seq[PreparedPin], layerConstraint: Option[String] = None)

  /** One edge oriented source->sink. `toPath` is the SOURCE pin (drives), and
    * `fromPath` is the SINK pin, matching [[WireDef]] semantics (from = sink,
    * to = source). `label` is the merged label of its anti-parallel group.
    * `reversed`: each engine hands this edge to the layered algorithm sink->source
    * and maps its route back, because it runs against its pins' sides: out of a
    * WEST pin or into an EAST pin (a Decoupled `ready`, an output tag's flipped
    * field). Drawn, such a wire comes back leftwards, so its source belongs on
    * the right: layered forward, it forms cycles with the data it answers, and
    * ELK may break them on the data edge (a queue placed after the decoder it
    * feeds). ELK also rejects it on a FIRST/LAST_SEPARATE tag. Set by [[prepare]]. */
  case class PreparedEdge(fromPath: String, toPath: String, label: String, reversed: Boolean = false)

  case class Prepared(nodes: Seq[PreparedNode], edges: Seq[PreparedEdge])

  /** The shown port rows of a module's instance boxes and edge stubs under an
    * expansion set ([[PortModel]], #332), and the row each wire end lands on. */
  private final class PortRows(module: ModuleDef, design: Design,
                               modulesByName: Map[String, ModuleDef], expanded: Set[String]) {
    private val ends = PortModel.wireEndsOf(module)
    val inst: Map[String, Seq[PortModel.Row]] = module.children.iterator.flatMap { c =>
      modulesByName.get(c.`type`).map(m => c.name -> PortModel.instanceRows(c.name, m, design, expanded, ends))
    }.toMap
    val stubs: Seq[(String, Seq[PortModel.Row])] = PortModel.boundaryStubs(module, design, expanded, ends)
    /** A bundle register's rows, by the register's id (#334 follow-up). */
    val regs: Map[String, Seq[PortModel.Row]] = module.logic.iterator
      .map(n => n.id -> PortModel.regRows(n, design, expanded)).filter(_._2.nonEmpty).toMap
    private val stubRows = stubs.flatMap(_._2)
    /** The stub id of each stub row's path. */
    val stubOf: Map[String, String] = stubs.iterator.flatMap { case (id, rs) => rs.map(_.path -> id) }.toMap
    private val portNames = module.ports.iterator.map(_.name).toSet

    /** The deepest shown row containing `path`; a path no row contains (an
      * instance with no module, an undeclared or hidden port, a logic node)
      * keeps its Vec group path, so no wire is ever dropped. */
    def group(path: String): String = {
      val head = pathHead(path)
      val rows = inst.get(head).orElse(regs.get(head.stripSuffix("~q")))
        .getOrElse(if (portNames(head)) stubRows else Nil)
      PortModel.attach(path, rows).getOrElse(groupPath(path, expanded))
    }
  }

  /** A row's pin label: indented under its parent, with its type as [[pinLabel]] does. */
  private def rowLabel(r: PortModel.Row, design: Design): String =
    ("  " * r.depth) + pinLabel("", r.label, Some(BundleTree.displayName(r.bundle, design)))

  /** Resolve a module into an engine-agnostic graph: nodes with sizes + per-pin
    * coordinates, and anti-parallel-collapsed edges. This is the entire pure
    * pipeline that used to live inline in [[Layout.layout]] before the ELK
    * graph was built; both the JVM (ELK) and browser (elkjs) engines consume
    * it identically. */
  def prepare(module: ModuleDef, design: Design, expanded: Set[String] = Set.empty): Prepared = {
    val modulesByName = design.modules.iterator.map(m => m.name -> m).toMap

    // 1. Resolve every wire endpoint; keep only wires whose BOTH ends resolve.
    //    Drop clock/reset wires before resolution so their boundary stubs are
    //    also excluded from the layout.
    case class E(w: WireDef, from: Endpoint, to: Endpoint)
    val ix = new ClassifyIndex(module)
    val resolved: Seq[E] = module.wires.filterNot(isClockReset).flatMap { w =>
      for { f <- classifyWith(w.from, module, design, modulesByName, ix); t <- classifyWith(w.to, module, design, modulesByName, ix) }
        yield E(w, f, t)
    }

    // Vec grouping (issue #260): collapse each endpoint to its group path under
    // the current expansion set, so sibling Vec-index wires to the same node
    // dedup into one pin + one edge. The original leaf path stays in `e.w` for
    // range labelling below.
    // A boundary node's identity is its full leaf path (issue #68: each external
    // pin is its own per-path boundary node), so collapsing only `.path` would
    // leave each Vec-sibling leaf as a distinct node. Regroup BOUNDARY `nodeId`
    // to the group path too, so all siblings dedup into one node. Instance
    // endpoints already share a node id (the instance name), so leave theirs.
    // Instance and boundary ends land on the deepest shown declared row (#332):
    // a stub row's node is its stub, not the path.
    val portRows = new PortRows(module, design, modulesByName, expanded)
    def regroup(ep: Endpoint): Endpoint = {
      val gp = portRows.group(ep.path)
      if (ep.kind == NodeKind.Boundary) ep.copy(path = gp, nodeId = portRows.stubOf.getOrElse(gp, gp))
      else ep.copy(path = gp)
    }
    val grouped: Seq[E] = resolved.map(e => e.copy(from = regroup(e.from), to = regroup(e.to)))

    // 2. Group pins per node (distinct path), preserving first-seen order. Pin
    //    side is determined by dataflow role: SOURCE (e.to) votes EAST, SINK
    //    (e.from) votes WEST; a pin voted both ways resolves EAST on >= tie.
    case class PinSpec(path: String, side: String, interface: Boolean, bundle: String)
    type PinMap = mutable.LinkedHashMap[String, PinSpec]

    val eastVotes  = mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val westVotes  = mutable.Map.empty[(String, String, String), Int].withDefaultValue(0)
    val ifaceOf    = mutable.Map.empty[(String, String, String), Boolean].withDefaultValue(false)
    val bundleOf   = mutable.Map.empty[(String, String, String), String]
    val declaredOf = mutable.Map.empty[(String, String, String), String]
    val pinOrder   = mutable.LinkedHashMap.empty[(String, String), mutable.LinkedHashSet[String]]

    def vote(ep: Endpoint, isSource: Boolean): Unit = {
      val k = (ep.nodeId, ep.kind, ep.path)
      if (isSource) eastVotes(k) += 1 else westVotes(k) += 1
      ifaceOf(k) = ifaceOf(k) || ep.interface
      declaredOf(k) = ep.side
      if (!bundleOf.contains(k) && ep.bundle.nonEmpty && ep.bundle != "Wire")
        bundleOf(k) = ep.bundle
      pinOrder.getOrElseUpdate((ep.nodeId, ep.kind), mutable.LinkedHashSet.empty) += ep.path
    }
    // Every shown declared row is a pin, in declaration order, on its own side,
    // wired or not (#332). Wire ends no row contains are added after, by vote.
    val rowOf = mutable.Map.empty[(String, String, String), PortModel.Row]
    def seed(nodeId: String, kind: String, rows: Seq[PortModel.Row]): Unit = {
      val order = pinOrder.getOrElseUpdate((nodeId, kind), mutable.LinkedHashSet.empty)
      rows.foreach { r => order += r.path; rowOf((nodeId, kind, r.path)) = r }
    }
    portRows.stubs.foreach { case (id, rows) => seed(id, NodeKind.Boundary, rows) }
    module.children.foreach(c => portRows.inst.get(c.name).foreach(seed(c.name, NodeKind.Inst, _)))
    portRows.regs.foreach { case (id, rows) => seed(id, NodeKind.Reg, rows) }
    // A code-block box's condition pins come first on its WEST side, wired or
    // not: an expression computed inside the box drives an out pin nothing
    // outside reads, and the closed box still shows its condition. Not an FSM
    // box: it opens its state diagram, and its arm compares (`state === 1`) are
    // CHIRRTL encodings, not the `is(...)` as written.
    val condPins = mutable.Set.empty[(String, String, String)]
    module.logic.foreach { n =>
      if (n.kind == NodeKind.Block) design.regions.get(n.id).foreach { body =>
        body.ports.filter(_.cond).foreach { p =>
          val path = s"${n.id}.${p.name}"
          pinOrder.getOrElseUpdate((n.id, n.kind), mutable.LinkedHashSet.empty) += path
          condPins += ((n.id, n.kind, path))
        }
      }
    }
    // e.to is SOURCE; e.from is SINK.
    grouped.foreach { e =>
      vote(e.to,   isSource = true)
      vote(e.from, isSource = false)
    }
    // Ensure instances with no resolved wires still appear as empty boxes.
    module.children.foreach { c =>
      pinOrder.getOrElseUpdate((c.name, NodeKind.Inst), mutable.LinkedHashSet.empty)
    }

    // Build nodePins from the vote tallies.
    val nodePins = mutable.LinkedHashMap.empty[(String, String), PinMap]
    pinOrder.foreach { case ((nodeId, kind), paths) =>
      val pins: PinMap = mutable.LinkedHashMap.empty
      paths.foreach { path =>
        val k = (nodeId, kind, path)
        val side =
          if (condPins(k)) PinSide.West
          else if (rowOf.contains(k)) rowOf(k).side
          else if (eastVotes(k) > westVotes(k)) PinSide.East
          else if (westVotes(k) > eastVotes(k)) PinSide.West
          else if (kind == NodeKind.Boundary) declaredOf.getOrElse(k, PinSide.East)
          // An unwired instance stub (showUnwiredPorts) has no vote at all and
          // takes its declared side; a wired pin always carries a vote.
          else if (eastVotes(k) == 0 && westVotes(k) == 0) declaredOf.getOrElse(k, PinSide.East)
          else PinSide.East
        rowOf.get(k) match {
          case Some(r) => pins(path) = PinSpec(path, side, isInterfaceBundle(r.bundle, design), r.bundle)
          case None    => pins(path) = PinSpec(path, side, ifaceOf(k), bundleOf.getOrElse(k, ""))
        }
      }
      nodePins((nodeId, kind)) = pins
    }

    // 3. Collapse anti-parallel wire pairs (same unordered port pair, opposite
    //    direction). Keep the edge whose source drives EAST by vote; merge the
    //    group's labels so a dropped partner's count does not vanish (issue #102).
    //    Done before node geometry because the per-boundary layer constraint
    //    below depends on the FINAL (collapsed) edge directions.
    def antiParallelKey(e: E): (String, String) = {
      val a = e.to.path   // source
      val b = e.from.path // sink
      if (a <= b) (a, b) else (b, a)
    }
    // The leaf index elided to reach the group path `gp` from an original leaf
    // `leaf` (e.g. leaf "s.v[2]" vs gp "s.v" -> Some(2)); None when not a Vec
    // group member.
    def elidedIndex(leaf: String, gp: String): Option[Int] =
      if (leaf == gp || !leaf.startsWith(gp)) None
      else """^\[(\d+)\]""".r.findPrefixMatchOf(leaf.substring(gp.length)).map(_.group(1).toInt)

    val edges: Seq[PreparedEdge] = grouped
      .groupBy(antiParallelKey)
      .values
      .map { grp =>
        val kept =
          if (grp.size == 1) grp.head
          else {
            def isEastByVote(ep: Endpoint): Boolean = {
              val k = (ep.nodeId, ep.kind, ep.path)
              eastVotes(k) >= westVotes(k)
            }
            grp.find(e => isEastByVote(e.to))
              .orElse(grp.sortBy(_.to.path).headOption)
              .getOrElse(grp.head)
          }
        val base = grp.map(_.w.label).filter(_.nonEmpty).distinct.mkString(" / ")
        val idxs = grp.flatMap(m => elidedIndex(m.w.to, kept.to.path)).distinct
        val vecName = { val gp = kept.to.path; gp.substring(gp.lastIndexOf('.') + 1) }
        val label =
          if (idxs.size >= 2) {
            val r = vecRangeLabel(vecName, idxs)
            if (base.nonEmpty) s"$r  $base" else r
          } else base
        PreparedEdge(kept.from.path, kept.to.path, label)
      }
      .toSeq

    // A declared row is expandable when it has children; any other pin by its type.
    def expandableOf(id: String, kind: String, p: PinSpec): Boolean =
      rowOf.get((id, kind, p.path)).fold(BundleTree.isAggregateType(p.bundle, design))(_.expandable)

    // 4. Compute node geometry + per-pin coordinates (pure; no engine objects).
    val nodes: Seq[PreparedNode] = nodePins.map { case ((id, kind), pins) =>
      // A logic block's or FSM's pin by its source path (`io.in.bits.a`), not its lowered id (#333).
      def boxPinLabel(p: PinSpec): Option[String] =
        if (!NodeKind.isBox(kind)) None
        else design.regions.get(id).flatMap(_.ports.find(q => p.path == s"$id.${q.name}")).map(_.label).filter(_.nonEmpty)
      def lblOf(p: PinSpec): String =
        if (kind == NodeKind.Mem) memPinLabel(module, id, p.path)
        else if (boxPinLabel(p).isDefined) boxPinLabel(p).get
        // A stub's top row keeps its full path ("io.in: Decoupled"), as before #332.
        else if (rowOf.get((id, kind, p.path)).exists(r => kind == NodeKind.Inst || (kind == NodeKind.Boundary && r.depth > 0)))
          rowLabel(rowOf((id, kind, p.path)), design)
        // The type at Chisel's level (#333): a class or a known shape, never a hash.
        else pinLabel(id, p.path, Some(BundleTree.displayName(p.bundle, design)))
      // Const nodes share the boundary stub's compact geometry (a single small
      // driver), but unlike boundaries they are NOT layer-pinned: a const ranks
      // naturally by dataflow next to its sink (issue #188 B1). Reg nodes fall
      // into the instance branch below (header + D/Q port rows).
      // A domain sink is a stub too, sized to its `→ fall` label and, like a
      // const, ranked by dataflow (next to its source), not column-pinned.
      val compact = kind == NodeKind.Boundary || kind == NodeKind.Const || kind == NodeKind.Domain
      if (compact) {
        // Boundary/const: compact, but at least wide enough for the label (if any).
        // A const shows ONLY its literal value (the logic node's `label`, e.g.
        // `0x0`), never the synthetic `const$...` pin id or its bundle suffix,
        // so size it to that value rather than the pin label (issue #188 follow-up).
        val constLabel =
          if (kind == NodeKind.Const) module.logic.find(_.id == id).map(_.label).filter(_.nonEmpty)
          else if (kind == NodeKind.Domain) module.logic.find(_.id == id).map(domainLabel)
          else None
        val maxLabelPx = constLabel match {
          case Some(v) => labelPx(v)
          case None    => pins.values.map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        }
        val bw = math.max(BoundaryW, maxLabelPx)
        // A stub with one pin lets the engine place it on its side; an open stub
        // stacks its rows like a box does, at fixed positions (#332).
        val stacked = pins.size > 1
        val laidPins = pins.values.toSeq.zipWithIndex.map { case (p, i) =>
          val (x, y) =
            // A domain sink's one pin: the middle of its left edge, fixed, as
            // both engines keep a non-boundary node's pins where they are put.
            if (!stacked && kind == NodeKind.Domain) (0.0, BoundaryH / 2)
            else if (!stacked) (0.0, 0.0)
            else (if (p.side == PinSide.East) bw else 0.0, PortPad + i * PortStep + PortStep / 2)
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), x, y,
            expandableOf(id, kind, p))
        }
        // An EAST boundary pin drives into the design (a source): pin it to the
        // first column. A WEST one receives (a sink): pin it to the last column.
        // Every tag is pinned, also one carrying a wire the other way (a
        // Decoupled `ready`): that wire is marked `reversed` below, otherwise
        // the tag would rank among the logic, mid-schematic.
        val side        = laidPins.headOption.map(_.side).getOrElse(PinSide.East)
        // Only true boundary stubs get column-pinned (issue #163); const nodes
        // share the compact box but rank by dataflow, so they stay unconstrained.
        val lc =
          if (kind != NodeKind.Boundary)                  None
          else if (side == PinSide.East) Some("FIRST_SEPARATE")
          else if (side == PinSide.West) Some("LAST_SEPARATE")
          else                                     None
        val bh = if (stacked) 2 * PortPad + pins.size * PortStep else BoundaryH
        PreparedNode(id, kind, bw, bh, laidPins, lc)
      } else {
        // Instance component: reserve HeaderH at top, ports in rows below.
        val west = pins.values.count(_.side == PinSide.West)
        val east = pins.values.count(_.side == PinSide.East)
        val westMaxPx = pins.values.filter(_.side == PinSide.West).map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        val eastMaxPx = pins.values.filter(_.side == PinSide.East).map(p => labelPx(lblOf(p))).maxOption.getOrElse(0.0)
        // Also ensure header text (instance id) fits: id.length * 6.5 + 24 (glyph + padding)
        // A folded box shows `<id> × <n>` and a copy its group's toggle (about
        // 30px with its gap); both must fit. No fold field is set on a CHIRRTL design.
        val fold         = module.children.find(_.name == id)
        val headerText   = fold.filter(_.foldCount > 0).fold(id)(c => s"$id × ${c.foldCount}")
        val toggleW      = if (fold.exists(c => c.foldCount > 0 || c.foldGroup.nonEmpty)) 30.0 else 0.0
        // The header's right group too: the type label (capped at 80px by the UI)
        // and the open mark, so the name keeps its room (#334).
        // The name is drawn at about 8px a character; padding and gaps take about 50px.
        val typeW        = if (kind == NodeKind.Inst) math.min(80.0, fold.fold(0)(_.`type`.length) * 6.5) + 24.0 else 0.0
        val headerTextPx =
          if (kind == NodeKind.Inst) headerText.length * 8.0 + 50.0 + toggleW + typeW
          else headerText.length * 6.5 + 24.0 + toggleW
        // Op/reg logic nodes render as a compact symbol (no header, short or no
        // pin labels), so they use a small fixed width; real instances size to
        // their header + label columns (issue #203).
        val isLogic = kind == NodeKind.Op || kind == NodeKind.Reg
        // Logic symbols carry no header band, so they use the tighter logic
        // geometry; height and rowY below must use the SAME consts so every pin
        // stays inside the shorter box (issue #252). Instances are unchanged.
        val (hdr, pad, step) = if (isLogic) (LogicHeaderH, LogicPortPad, LogicPortStep) else (HeaderH, PortPad, PortStep)
        val w = if (isLogic) LogicNodeW
                else math.min(MaxNodeW, math.max(NodeW, math.max(headerTextPx, westMaxPx + LabelGap + eastMaxPx)))
        // An instance keeps rows at the bottom of its West side for its clk and
        // rst pins (they carry no wire).
        val clk = if (kind == NodeKind.Inst) fold.flatMap(c => modulesByName.get(c.`type`)).fold(0)(clockRows) else 0
        val h = if (isLogic) hdr + 2 * pad + math.max(1, math.max(west, east)) * step
                else nodeHeight(west + clk, east)
        // West and east pins are laid from their own row 0 via separate index sequences
        // (westPins.zipWithIndex / eastPins.zipWithIndex), so each side starts at row 0.
        def rowY(r: Double): Double = hdr + pad + r * step + step / 2
        // An op renders as a symbol whose single output leaves from the vertical
        // center of its DATA inputs; place its EAST pin there so the output is a
        // straight horizontal line and the inputs sit equidistant about it
        // (#230). A mux select (in0) renders on top, so it is excluded from the
        // centering.
        val opName  = if (kind == NodeKind.Op) module.logic.find(_.id == id).map(_.op).getOrElse("") else ""
        // For op nodes, lay west pins in a deterministic order driven by the
        // `~in<i>` index so the symbol's selector/operand assumptions hold
        // regardless of `pins.values` iteration order (issue #246). Instances
        // keep their existing order (only kind == Op is reordered).
        val OpIn = """.*~in(\d+)$""".r
        val isOp = kind == NodeKind.Op
        val isMem = kind == NodeKind.Mem
        def memSortKey(p: PinSpec): (Int, Int) = {
          val head = stripIdx(p.path.split('.').head)
          Synthetic.memPinParts(head) match {
            case Some((_, port, role)) =>
              val ports = module.logic.find(_.id == id).flatMap(_.memInfo).map(_.ports).getOrElse(Nil)
              val pi = ports.indexWhere(_.name == port)
              val ri = role match { case "a" => 0; case "w" => 1; case _ => 0 }
              ((if (pi < 0) Int.MaxValue else pi), ri)
            case None => (Int.MaxValue, 0)
          }
        }
        val westPins = {
          val ws = pins.values.filter(_.side == PinSide.West).toSeq
          if (isOp) ws.sortBy(p => p.path match { case OpIn(i) => (i.toInt, p.path); case _ => (Int.MaxValue, p.path) })
          else if (isMem) ws.sortBy(memSortKey)
          else ws
        }
        val eastPins = {
          val es = pins.values.filter(_.side == PinSide.East).toSeq
          if (isMem) es.sortBy(memSortKey) else es
        }
        // Output centers on the DATA inputs (every west pin except a mux select
        // `~in0`, which renders on top), now over the deterministic order.
        val dataRows = westPins.zipWithIndex.collect {
          case (p, i) if !(opName == "mux" && p.path.endsWith("~in0")) => i.toDouble
        }
        val opOutY  = if (dataRows.nonEmpty) rowY(dataRows.sum / dataRows.size) else rowY(0)
        def lay(p: PinSpec, rowIdx: Int): PreparedPin = {
          val portY =
            if (isOp && p.side == PinSide.East) opOutY
            else rowY(rowIdx.toDouble)
          val portX = if (p.side == PinSide.East) w else 0.0
          PreparedPin(p.path, p.side, p.interface, p.bundle, lblOf(p), portX, portY,
            expandableOf(id, kind, p))
        }
        val laidPins =
          westPins.zipWithIndex.map { case (p, i) => lay(p, i) } ++
          eastPins.zipWithIndex.map { case (p, i) => lay(p, i) }
        PreparedNode(id, kind, w, h, laidPins)
      }
    }.toSeq

    // A wire between two tags of the same column (an output passed straight to
    // another output) fits that column in neither direction: the tag at its
    // wrong end (the driving output, the receiving input) stays unpinned.
    val nodeOfPin: Map[String, PreparedNode] = nodes.iterator.flatMap(n => n.pins.iterator.map(_.path -> n)).toMap
    val unpin: Set[String] = edges.iterator.flatMap { e =>
      (nodeOfPin.get(e.toPath), nodeOfPin.get(e.fromPath)) match {
        case (Some(s), Some(t)) if s.id != t.id && s.layerConstraint.isDefined && s.layerConstraint == t.layerConstraint =>
          if (s.layerConstraint.contains("LAST_SEPARATE")) Some(s.id) else Some(t.id)
        case _ => None
      }
    }.toSet
    val pinned = nodes.map(n => if (unpin(n.id)) n.copy(layerConstraint = None) else n)

    // An edge leaving a WEST pin or entering an EAST pin runs against its pins'
    // sides (a `ready` back, a tag's flipped field): it goes to the layered
    // algorithm reversed. This also keeps every edge on a column-pinned tag
    // pointing the way its column needs (tag pins are EAST for inputs, WEST
    // for outputs).
    val sideOf: Map[String, String] = pinned.iterator.flatMap(_.pins.iterator.map(p => p.path -> p.side)).toMap
    val oriented = edges.map { e =>
      val rev = sideOf.get(e.toPath).contains(PinSide.West) || sideOf.get(e.fromPath).contains(PinSide.East)
      if (rev) e.copy(reversed = true) else e
    }
    Prepared(pinned, oriented)
  }

  /** Where a bundle wire carries its type label (#334): the middle of the
    * longest horizontal segment of its drawn route, or None when that segment
    * is shorter than the label (`labelW`) plus a margin. */
  def wireLabelAt(points: Seq[(Double, Double)], labelW: Double): Option[(Double, Double)] =
    points.sliding(2).collect { case Seq(a, b) if a._2 == b._2 => (a, b) }
      .maxByOption { case (a, b) => math.abs(b._1 - a._1) }
      .filter { case (a, b) => math.abs(b._1 - a._1) >= labelW + 8 }
      .map { case (a, b) => ((a._1 + b._1) / 2, a._2) }

  // ── Self-loops (routed here, not by ELK) ─────────────────────────────────────

  /** `prep`'s edges split into self-loops (both pins on one box: a register
    * holding its value, a block wired back to itself) and the rest. ELK routes
    * self-loops from identity-hashed sets, so its routes, and the room it left
    * around the box, changed with whatever the JVM had hashed first: the same
    * design laid out differently from one run (or editor session) to the next.
    * Both engines hand ELK only the rest and route self-loops with
    * [[selfLoopRoute]]. */
  def splitSelfLoops(prep: Prepared): (Seq[PreparedEdge], Seq[PreparedEdge]) = {
    val owner = prep.nodes.iterator.flatMap(n => n.pins.iterator.map(_.path -> n.id)).toMap
    prep.edges.partition(e => owner.get(e.fromPath).exists(o => owner.get(e.toPath).contains(o)))
  }

  /** Above this many boxes, both engines place nodes with ELK's Brandes-Koepf
    * instead of network simplex. Network simplex gives straighter wires, but its
    * cost grows fast with module size: on VTA's TensorLoadWgt (2229 boxes) it
    * took 7 s of a 12.6 s export, against 0.3 s for Brandes-Koepf. On mid-size
    * modules (VME, 313 boxes) Brandes-Koepf draws staircases and is not worth
    * the half second it saves, hence a high threshold. */
  val FastPlacementAbove: Int = 1000

  def fastPlacement(prep: Prepared): Boolean = prep.nodes.length > FastPlacementAbove

  /** How far a self-loop runs from its box, and how far apart loops on one box
    * stack: inside the 10px ELK keeps between a box and the wires it routes
    * (`spacing.edgeNode`), so a loop never runs along another wire. */
  val SelfLoopGap: Double  = 5.0
  val SelfLoopStep: Double = 3.0

  /** A self-loop's route: out of the source pin `src` (x, y, side), under the
    * box `box` (x, y, w, h), back into the sink pin. `slot` (0, 1, ...) is the
    * loop's index among its box's loops, so they stack. Points run source -> sink. */
  def selfLoopRoute(src: (Double, Double, String), sink: (Double, Double, String),
                    box: (Double, Double, Double, Double), slot: Int): Seq[(Double, Double)] = {
    val (bx, by, bw, bh) = box
    val d     = SelfLoopGap + slot * SelfLoopStep
    def out(side: String): Double = if (side == PinSide.West) bx - d else bx + bw + d
    val below = by + bh + d
    val pts   = Seq((src._1, src._2), (out(src._3), src._2), (out(src._3), below),
                    (out(sink._3), below), (out(sink._3), sink._2), (sink._1, sink._2))
    pts.foldLeft(Vector.empty[(Double, Double)])((acc, p) => if (acc.lastOption.contains(p)) acc else acc :+ p)
  }

  // ── Verification layers apart (a debug band below the design) ───────────────

  /** The space between the design and the debug band, the channel the wires
    * into the band run in at its middle. */
  val DebugGap: Double = 56.0

  /** `prep` split into the design's own graph and its verification-layer
    * nodes' (`layerIds`), and the edges between the two. */
  def splitLayers(prep: Prepared, layerIds: Set[String]): (Prepared, Prepared, Seq[PreparedEdge]) = {
    val (dbg, main) = prep.nodes.partition(n => layerIds(n.id))
    val inDbg = dbg.iterator.flatMap(_.pins.map(_.path)).toSet
    val (within, cross) = prep.edges.partition(e => inDbg(e.fromPath) == inDbg(e.toPath))
    val (dbgE, mainE) = within.partition(e => inDbg(e.fromPath))
    (Prepared(main, mainE), Prepared(dbg, dbgE), cross)
  }

  /** Routes for the wires between the design and the band: out of the source
    * pin, along the channel at `channelY`, into the sink pin, each wire on its
    * own track. `pin` gives each pin's absolute position and side; an edge with
    * an unknown pin gets no route. Points run source (`toPath`) to sink. */
  def debugRoutes(cross: Seq[PreparedEdge], pin: Map[String, (Double, Double, String)],
                  channelY: Double): Seq[(PreparedEdge, Seq[(Double, Double)])] =
    cross.zipWithIndex.flatMap { case (e, i) =>
      for { (sx, sy, sSide) <- pin.get(e.toPath); (tx, ty, tSide) <- pin.get(e.fromPath) } yield {
        val track = channelY + (i % 6 - 2.5) * 5
        val out   = if (sSide == PinSide.West) sx - 10 - 3 * (i % 4) else sx + 10 + 3 * (i % 4)
        val in    = if (tSide == PinSide.East) tx + 10 + 3 * (i % 4) else tx - 10 - 3 * (i % 4)
        e -> Seq((sx, sy), (out, sy), (out, track), (in, track), (in, ty), (tx, ty))
      }
    }

  // ── Logic block pin order (#334) ─────────────────────────────────────────────

  /** For every pin of a logic or FSM block, the mean y (in `portY`, absolute)
    * of the other ends of its edges; a pin with no laid peer is absent. */
  def peerYOf(prep: Prepared, portY: Map[String, Double]): Map[String, Double] = {
    val boxPins = prep.nodes.iterator.filter(n => NodeKind.isBox(n.kind)).flatMap(_.pins.map(_.path)).toSet
    val ys = mutable.Map.empty[String, mutable.ArrayBuffer[Double]]
    prep.edges.foreach { e =>
      if (boxPins(e.fromPath)) portY.get(e.toPath).foreach(y => ys.getOrElseUpdate(e.fromPath, mutable.ArrayBuffer.empty) += y)
      if (boxPins(e.toPath))   portY.get(e.fromPath).foreach(y => ys.getOrElseUpdate(e.toPath, mutable.ArrayBuffer.empty) += y)
    }
    ys.iterator.map { case (p, v) => p -> v.sum / v.size }.toMap
  }

  /** A logic or FSM block's pins reordered within their side by `peerY`, on the
    * side's own y slots: each pin sits across from the box it connects to, inputs
    * still West and outputs East (#334). A pin with no peer keeps its slot.
    * Other nodes are returned unchanged. */
  def reorderBoxPins(prep: Prepared, peerY: Map[String, Double]): Prepared =
    prep.copy(nodes = prep.nodes.map { n =>
      if (!NodeKind.isBox(n.kind)) n
      else {
        // Only pins with a peer move, onto the slots the peered pins hold: a pin
        // with none keeps its slot (peer ys are absolute, pin ys box-relative).
        val moved = Seq(PinSide.West, PinSide.East).flatMap { side =>
          val peered = n.pins.filter(p => p.side == side && peerY.contains(p.path))
          val slots  = peered.map(_.y).sorted
          peered.sortBy(p => peerY(p.path)).zip(slots).map { case (p, y) => p.path -> y }
        }.toMap
        n.copy(pins = n.pins.map(p => moved.get(p.path).fold(p)(y => p.copy(y = y))))
      }
    })

  // ── Display wires: the schematic's drawable connection list ─────────────────

  /** One drawable schematic connection. `from`/`to` are the SINK/SOURCE pin
    * paths of the kept representative; `bidir` is set when the same pin pair was
    * connected in both directions (a folded anti-parallel pair); `count` and
    * `label` carry the merged ground-connect tally for the inspector. */
  case class DisplayWire(from: String, to: String, bundle: String, label: String,
                         bidir: Boolean, count: Int)

  private val GroundCountRe = """(\d+)\s*×""".r

  /** Leading "<n>×" count baked into a coalesced ground-connect label (IrWalk),
    * or 1 when absent. */
  def groundCount(label: String): Int =
    GroundCountRe.findFirstMatchIn(if (label == null) "" else label).map(_.group(1).toInt).getOrElse(1)

  /** Fold a module's wires into the schematic's drawable connections.
    *
    * Anti-parallel pairs (A->B and B->A on the SAME two pins) collapse into one
    * bidirectional wire, exactly the way [[prepare]] collapses layout edges
    * (`antiParallelKey`), so the rendered wire set matches the routed edge set
    * pin-for-pin and no real connection silently disappears.
    *
    * The fold key is the unordered pair of FULL pin paths, NOT the node heads.
    * Heads are wrong because each external pin is its own per-path boundary node
    * (issue #68): a head key lumps every `io.*` pin under one `io` node, so all
    * but the first connection per direction gets absorbed into a bidirectional
    * wire on another pin and never renders (issue #258).
    *
    * Clock/reset wires and const-driver wires are excluded: they have no laid
    * pin to attach to (clock/reset distribution is implicit; a const renders as
    * a small value stub at its sink, issue #203). */
  def displayWires(module: ModuleDef, design: Design, expanded: Set[String] = Set.empty): Seq[DisplayWire] = {
    val portRows = new PortRows(module, design, design.modules.iterator.map(m => m.name -> m).toMap, expanded)
    val seen = mutable.LinkedHashMap.empty[(String, String), Int] // pin-pair -> index in out
    val out  = mutable.ArrayBuffer.empty[DisplayWire]
    def pairKey(a: String, b: String): (String, String) = if (a <= b) (a, b) else (b, a)
    module.wires.iterator
      .filterNot(isClockReset)
      .filterNot(_.to.startsWith("const$"))
      .foreach { w =>
        // Each end lands on its deepest shown declared row (#332), or its Vec
        // group path (#260), exactly as `prepare` collapses the layout edges.
        // Clock/reset and const filtering above still key on the ORIGINAL `w`.
        val gf = portRows.group(w.from)
        val gt = portRows.group(w.to)
        val a = gf.split('.').headOption.getOrElse("")
        val b = gt.split('.').headOption.getOrElse("")
        if (a.isEmpty || b.isEmpty || a == b)
          out += DisplayWire(gf, gt, w.bundle, w.label, bidir = false, groundCount(w.label))
        else {
          val k = pairKey(gf, gt)
          seen.get(k) match {
            case Some(i) =>
              val d       = out(i)
              val reverse = d.from == gt && d.to == gf
              val bidir   = d.bidir || reverse
              val count   = d.count + groundCount(w.label)
              val bundle  = if (d.bundle == "Wire" && w.bundle.nonEmpty && w.bundle != "Wire") w.bundle else d.bundle
              val label   = if (bidir) (if (count > 1) s"$count× ground (bidir)" else "bidirectional") else d.label
              out(i) = d.copy(bundle = bundle, label = label, bidir = bidir, count = count)
            case None =>
              seen(k) = out.length
              out += DisplayWire(gf, gt, w.bundle, w.label, bidir = false, groundCount(w.label))
          }
        }
      }
    out.toSeq
  }
}
