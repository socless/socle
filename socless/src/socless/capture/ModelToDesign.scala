package socless.capture

import scala.collection.mutable

import socless.ir.FsmDetect
import socless.parse.ChirrtlParser
import socless.schema._

/** Adapts a [[CapturedModel]] (the probe's view of an elaborated design) to the
  * design.json schema the UI renders: ports, submodules, and internal logic
  * collapsed into blocks. No layouts: callers bake them (`Layout.withLayouts`).
  *
  * Port types follow exactly how Chisel emits them to CHIRRTL
  * (`chisel3.internal.firrtl.Converter`): the captured `CType.dir` is each
  * node's specified direction, so a port's direction and every channel flip are
  * composed down the tree here, which makes the result line up with what the
  * CHIRRTL pipeline shows for the same design.
  *
  * Logic and wiring come from the CHIRRTL pipeline (`IrWalk`) of the captured
  * circuit: each module's logic nodes are grouped (`LogicGroups`, with the
  * optional source `index`) and collapsed (`LogicBlocks`) into FSM, generator
  * and logic blocks, whose bodies go to `Design.regions`. A module keeps only
  * blocks and the consts that feed a block; a const that only ties off ports
  * or instance pins is dropped with its wires and recorded in `tieOffs`, which
  * the UI draws as a value stub at the tied pin (no layout node).
  * A wire is kept when both ends resolve to a port, an instance pin, a block
  * pin or a const.
  *
  * An FSM block carries its state diagram: the `index`'s, with the block's
  * source moved to the `switch`, else the states from the case literals and a
  * note that the transitions are unknown.
  */
object ModelToDesign {

  def design(m: CapturedModel, index: Option[SourceIndex] = None): Design = {
    require(m.status == "ok",
      s"cannot adapt a capture with status '${m.status}'" + (if (m.error.nonEmpty) s": ${m.error}" else ""))
    val reg      = new Registry
    val byName   = m.modules.map(c => c.name -> c).toMap
    // Per module: (port name, schema direction, keyed type), in port order.
    val ports: Map[String, Seq[(String, String, Keyed)]] = m.modules.map { c =>
      c.name -> c.ports.map { p =>
        val user = userDir(p.tpe)
        val dir  = if (user == Unspecified || user == Out) PortDir.Out else PortDir.In
        (p.name, dir, reg.keyed(p.tpe, clear = user == In || user == Out, s"${c.name}.${p.name}"))
      }
    }.toMap
    lazy val circ      = ChirrtlParser.parse(m.chirrtl)
    lazy val ir        = WiresFromChirrtl.design(circ)
    lazy val irModules = ir.modules.map(im => im.name -> im).toMap
    lazy val chirrtlInsts: Map[String, Map[String, ChirrtlParser.SrcInfo]] =
      circ.modules.map { cm =>
        cm.name -> cm.insts.flatMap(i => i.src.map(i.name -> _)).toMap
      }.toMap
    def params(module: String): Map[String, String] =
      byName.get(module).map(_.params.map(p => p.name -> p.value).toMap).getOrElse(Map.empty)

    // Block bodies and the IrWalk FSM bodies they hold, by box id.
    val regions = mutable.LinkedHashMap.empty[String, ModuleDef]
    val modules = m.modules.map { c =>
      val own       = ports(c.name).map { case (n, _, k) => n -> k }.toMap
      val instances = c.instances.map(i => i.name -> i.module).toMap
      // The key of the sub-type `path` names, when its head is a port of this
      // module or a port of one of its instances; None for anything else.
      def resolve(path: String): Option[String] = path.split('.').toList match {
        case h :: p :: rest if instances.contains(stripIdx(h)) =>
          val child = ports.getOrElse(instances(stripIdx(h)), Nil).map { case (n, _, k) => n -> k }.toMap
          child.get(stripIdx(p)).map(k => descend(k, p :: rest).key)
        case h :: rest if own.contains(stripIdx(h)) =>
          Some(descend(own(stripIdx(h)), h :: rest).key)
        case _ => None
      }
      val foldGroups =
        if (m.chirrtl.nonEmpty) foldGroupsOf(c, chirrtlInsts.getOrElse(c.name, Map.empty), byName)
        else Map.empty[String, String]
      // A block pin whose external end is a port or an instance pin is named
      // after that endpoint with every Vec index removed, so the lanes of
      // `io.out.data.bits[0][i]` share `io_out_data_bits`; a generator copy
      // takes its fold group's id (`acc_io_deq_bits`, not `acc_0_0_io_deq_bits`).
      // The wire keeps the real path, so folding merges the copies' wires and
      // unfolding splits them. Any other pin is named after its wire's driver.
      def foldHead(h: String): String = foldGroups.getOrElse(stripIdx(h), h)
      def pinName(driver: String, external: String, boxes: Set[String]): (String, String) =
        if (resolve(external).isDefined)
          LogicBlocks.driverPin(external.replaceAll("""\[[^\]]*\]""", ""), boxes, foldHead)
        else LogicBlocks.driverPin(driver, boxes, foldHead)
      // Logic and wires from IrWalk, collapsed into blocks; probe-supplied
      // wires (no logic) when the capture carries them.
      val irModule = if (c.wires.isEmpty && m.chirrtl.nonEmpty) irModules.get(c.name) else None
      val (collapsed, collapsedWires) = irModule match {
        case Some(im) =>
          val named            = im.copy(scalaClass = c.scalaClass)
          val (folded, bodies) = LogicBlocks.collapse(named, LogicGroups.assign(named, index), pinName)
          regions ++= bodies
          regions ++= im.logic.filter(_.kind == NodeKind.Fsm).flatMap(n => ir.regions.get(n.id).map(n.id -> _))
          lazy val caseLits = circ.modules.find(_.name == c.name).toSeq.flatMap(FsmDetect.detect)
            .map(f => f.stateReg -> f.caseLits).toMap
          val fsmPrefix = s"blk$$${c.name}$$fsm$$"
          val logic = folded.logic.map {
            case n if n.kind == NodeKind.Fsm && n.id.startsWith(fsmPrefix) =>
              val register = n.id.stripPrefix(fsmPrefix)
              val at = circ.modules.find(_.name == c.name).flatMap(_.regs.find(_.name == register))
                .flatMap(_.src).map(s => SourceRef(s.file, s.line, s.col))
              index.flatMap(_.fsm(c.scalaClass, register, at)) match {
                case Some(src) => n.copy(fsm = Some(src.diagram), source = src.switch)
                case None      =>
                  val note = if (index.isEmpty) NoIndexNote else FallbackNote
                  n.copy(fsm = Some(fallbackDiagram(caseLits.getOrElse(register, Nil), note)))
              }
            case n => n
          }
          (logic, folded.wires)
        case None =>
          (Nil, c.wires.map(w => WireDef(w.from, w.to, "", "", SourceRef.unknown)))
      }
      // Constant tie-offs: a const with no wire, or whose every wire ends on a
      // port or an instance pin, is not drawn (TensorGemm ties off hundreds).
      val tieOffs = collapsed.filter(_.kind == NodeKind.Const).map(_.id).filter { id =>
        collapsedWires.filter(w => w.to == id || w.from == id)
          .forall(w => resolve(if (w.to == id) w.from else w.to).isDefined)
      }.toSet
      // A dropped tie-off wire is recorded (sink, value as a number) so the UI
      // marks the tied pin without a layout node, but only when that constant
      // is the pin's sole driver. Every CHIRRTL connect whose target overlaps
      // the sink (an ancestor, the sink or a field under it; a dynamic index
      // overlaps every lane) must be a literal of that value, which also
      // rejects one through a wire (`connect a, w`), and no other IrWalk wire
      // may drive an overlapping endpoint. A connect or wire reading a strict
      // ancestor of the sink (`connect o, i` for `i.r`) may drive it too, through
      // a flipped field, so it rejects the tie. A missed tie only loses a stub.
      val constNum = collapsed.iterator.filter(n => tieOffs(n.id)).map { n =>
        val raw = if (n.label.nonEmpty) n.label else n.id
        n.id -> (raw.indexOf(": ") match { case i if i > 0 => raw.take(i); case _ => raw })
      }.toMap
      lazy val modConns = circ.modules.find(_.name == c.name).toSeq.flatMap(_.conns)
      lazy val connsByRoot: Map[String, Seq[ChirrtlParser.Conn]] = modConns.groupBy(cn => pathSegs(cn.lhs).head)
      lazy val connsByRhsRoot: Map[String, Seq[ChirrtlParser.Conn]] =
        modConns.filter(cn => litText(cn.rhs).isEmpty).groupBy(cn => pathSegs(cn.rhs).head)
      lazy val wiresByRoot: Map[String, Seq[WireDef]]   = collapsedWires.groupBy(w => pathSegs(w.from).head)
      lazy val wiresByToRoot: Map[String, Seq[WireDef]] = collapsedWires.groupBy(w => pathSegs(w.to).head)
      def soleDriver(w: WireDef): Boolean = {
        val sink  = w.from
        val root  = pathSegs(sink).head
        val num   = litNumber(constNum(w.to))
        val conns = connsByRoot.getOrElse(root, Nil).filter(cn => overlaps(cn.lhs, sink))
        num.isDefined && conns.nonEmpty &&
          conns.forall(cn => litText(cn.rhs).flatMap(litNumber) == num) &&
          !connsByRhsRoot.getOrElse(root, Nil).exists(cn => strictAncestor(cn.rhs, sink)) &&
          !wiresByRoot.getOrElse(root, Nil).exists(o => o.to != w.to && overlaps(o.from, sink)) &&
          !wiresByToRoot.getOrElse(root, Nil).exists(o => strictAncestor(o.to, sink))
      }
      val tied = collapsedWires.collect {
        case w if tieOffs(w.to) && soleDriver(w) => TieOff(w.from, tieValue(constNum(w.to)))
      }
      val logic = collapsed.filterNot(n => tieOffs(n.id))
      val raw   = collapsedWires.filterNot(w => tieOffs(w.to) || tieOffs(w.from))
      val boxes  = logic.filter(n => NodeKind.isBox(n.kind)).map(_.id).toSet
      val consts = logic.filter(_.kind == NodeKind.Const).map(_.id).toSet
      def onLogic(e: String): Boolean = consts.contains(stripIdx(e)) || {
        val parts = e.split('.')
        parts.length >= 2 && boxes.contains(stripIdx(parts.head))
      }
      // Port to port: the bundle is the source's sub-type, except for
      // clock/reset: IrWalk labels those wires Clock/Reset/AsyncReset (a
      // top-level reset is a Bool, a child's is Reset), and the layout hides
      // wires by that label. A block pin or const to a port: IrWalk's bundle
      // when this design knows the key, else the port side's.
      val wires = raw.flatMap { w =>
        (resolve(w.from), resolve(w.to)) match {
          case (Some(snk), Some(src)) =>
            Some(w.copy(bundle = Seq(w.bundle, snk, src).find(ClockResetTypes).getOrElse(src)))
          case (snk, src) if (snk.isDefined || onLogic(w.from)) && (src.isDefined || onLogic(w.to)) =>
            val side = snk.orElse(src)
            Some(if (side.isEmpty || known(w.bundle, reg)) w else w.copy(bundle = side.get))
          case _ => None
        }
      }
      ModuleDef(
        name       = c.name,
        `type`     = c.name,
        doc        = "",
        source     = c.source.map(s => SourceRef(s.file, s.line, s.col)).getOrElse(SourceRef.unknown),
        ports      = ports(c.name).map { case (n, d, k) => PortDef(n, d, k.key, "", SourceRef.unknown) },
        children   = c.instances.map(i => InstanceDef(i.name, i.module, "", SourceRef.unknown, params = params(i.module),
          foldGroup = foldGroups.getOrElse(i.name, ""))),
        wires      = wires,
        metrics    = Metrics(0, 0, 0),
        parameters = params(c.name),
        scalaClass = c.scalaClass,
        parameterDecls = c.params.map(p => ParamDef(p.name, p.tpe, p.value)),
        logic      = logic,
        tieOffs    = tied,
        signals    = signalsOf(circ.modules.find(_.name == c.name)),
      )
    }
    Design(
      top      = m.top,
      bundles  = reg.defs.toMap,
      modules  = modules,
      warnings = m.problems.map(p => Warning("probe-problem", 1, p)) ++
        reg.unknown.map { case (cls, n) =>
          Warning("probe-unknown-type", n, s"$n port leaf(s) of type $cls shown by class name: no schema type for it")
        } ++ m.modules.flatMap { c =>
          val cut = cutLeaves(circ.modules.find(_.name == c.name))
          if (cut <= 0) None
          else Some(Warning("signals-capped", math.min(cut, Int.MaxValue.toLong).toInt,
            s"$cut ground leaves of aggregate registers or wires are not in the signal list: each aggregate lists its first $MaxLeaves",
            c.name))
        },
      regions  = regions.toMap,
      // A port no wire reaches (probe-supplied wiring, an unused input) still shows.
      showUnwiredPorts = true,
    )
  }

  /** An aggregate register or wire expands to at most this many ground leaves
    * (the first ones, in order): a huge Vec would otherwise flood the signal list.
    * The leaves left out are counted in a `signals-capped` warning of the module. */
  private val MaxLeaves = 256

  /** The module's named internal signals: registers, then wires and named nodes
    * in declaration order (a node has no declared type, so its width is 0).
    * Compiler temporaries (`_T`, `_GEN_0`) and layer content are dropped;
    * aggregates expand to ground leaves (`a.b`, `v[0]`). */
  private def signalsOf(mod: Option[ChirrtlParser.Mod]): Seq[SignalDef] = mod.toSeq.flatMap { cm =>
    def src(s: Option[ChirrtlParser.SrcInfo]) = s.map(i => SourceRef(i.file, i.line, i.col)).getOrElse(SourceRef.unknown)
    def leaves(path: String, t: ChirrtlParser.Type): Seq[(String, Int)] = t match {
      case ChirrtlParser.GroundT(_, w) => Seq(path -> w.getOrElse(0))
      case ChirrtlParser.BundleT(fs)   => fs.view.flatMap(f => leaves(s"$path.${f.name}", f.tpe)).take(MaxLeaves).toSeq
      case ChirrtlParser.VecT(e, n)    => (0 until math.min(n, MaxLeaves)).view.flatMap(i => leaves(s"$path[$i]", e)).take(MaxLeaves).toSeq
    }
    def named(n: String) = !n.startsWith("_")
    def expand(name: String, t: ChirrtlParser.Type, kind: String, at: Option[ChirrtlParser.SrcInfo]) =
      leaves(name, t).take(MaxLeaves).map { case (n, w) => SignalDef(n, kind, w, src(at)) }
    val regs = cm.regs.filter(r => named(r.name) && !r.inLayer).flatMap(r => expand(r.name, r.tpe, "reg", r.src))
    val rest: Seq[(Int, Seq[SignalDef])] =
      cm.wires.filter(w => named(w.name) && !w.inLayer).map(w => w.idx -> expand(w.name, w.tpe, "wire", w.src)) ++
        cm.nodes.filter(n => named(n.name) && !n.inLayer).map(n => n.idx -> Seq(SignalDef(n.name, "wire", 0, src(n.src))))
    regs ++ rest.sortBy(_._1).flatMap(_._2)
  }

  /** How many ground leaves [[signalsOf]] leaves out of the module's aggregates
    * ([[MaxLeaves]] each, the first ones in order, so a cap anywhere inside
    * keeps exactly the first [[MaxLeaves]] overall). */
  private def cutLeaves(mod: Option[ChirrtlParser.Mod]): Long = mod.toSeq.flatMap { cm =>
    def count(t: ChirrtlParser.Type): Long = t match {
      case ChirrtlParser.GroundT(_, _) => 1L
      case ChirrtlParser.BundleT(fs)   => fs.map(f => count(f.tpe)).sum
      case ChirrtlParser.VecT(e, n)    => n.toLong * count(e)
    }
    def named(n: String) = !n.startsWith("_")
    cm.regs.filter(r => named(r.name) && !r.inLayer).map(r => count(r.tpe)) ++
      cm.wires.filter(w => named(w.name) && !w.inLayer).map(w => count(w.tpe))
  }.map(n => math.max(0L, n - MaxLeaves)).sum

  private val FallbackNote ="Transitions are not available: the state machine's Scala source could not be read."
  private val NoIndexNote  = "Transitions are not shown: this view was built without the Scala source index."
  private val LitValue     = """^(?:UInt|SInt)(?:<\d+>)?\((.*)\)$""".r

  /** A diagram with only the states, from FsmDetect's case literals. */
  private def fallbackDiagram(caseLits: Seq[String], note: String): FsmDiagram =
    FsmDiagram(caseLits.map(l => FsmState(litValue(l))), Nil, note)

  /** A CHIRRTL literal's value in decimal: `UInt<2>(0h1)` gives `1`. */
  private def litValue(lit: String): String = lit.trim match {
    case LitValue(v) => litNumber(v).map(_.toString).getOrElse(v)
    case other => other
  }

  /** The digits of a CHIRRTL literal (`UInt<8>(0h2A)` gives `0h2A`), if it is one. */
  private def litText(expr: String): Option[String] = expr.trim match {
    case LitValue(v) => Some(v)
    case _           => None
  }

  private val Seg = """[^.\[\]]+|\[[^\]]*\]""".r
  /** A path's segments: its root, then `.field` names and `[index]` selectors. */
  private def pathSegs(path: String): Vector[String] = {
    val v = Seg.findAllIn(path.trim).toVector
    if (v.isEmpty) Vector(path) else v
  }

  /** Whether two paths may name a common leaf: one is an ancestor of (or
    * equal to) the other, an index that is not a number matching any lane. */
  private def overlaps(a: String, b: String): Boolean =
    pathSegs(a).zip(pathSegs(b)).forall { case (x, y) =>
      x == y || (x.startsWith("[") && y.startsWith("[") && !(isLane(x) && isLane(y)))
    }
  /** Whether `a` overlaps `b` as a strict ancestor (fewer segments). */
  private def strictAncestor(a: String, b: String): Boolean =
    pathSegs(a).length < pathSegs(b).length && overlaps(a, b)
  private def isLane(sel: String): Boolean = sel.length > 2 && sel.drop(1).dropRight(1).forall(_.isDigit)

  /** The number a literal's digits spell (`0h2A`, `-0b101`, `0o7`, `12`), if any. */
  private def litNumber(v: String): Option[BigInt] = {
    val digits = v.trim.stripPrefix("\"").stripSuffix("\"")
    val neg    = digits.startsWith("-")
    val body   = digits.stripPrefix("-").stripPrefix("0")
    val radix  = body.headOption match {
      case Some('h') => 16
      case Some('b') => 2
      case Some('o') => 8
      case _         => 10
    }
    val num = if (radix == 10) digits.stripPrefix("-") else body.drop(1)
    scala.util.Try(BigInt(num, radix)).toOption.map(b => if (neg) -b else b)
  }

  /** A tie-off's value as shown: decimal below 10, else lowercase `0x` hex
    * (`0h0` gives `0`, `0h2A` gives `0x2a`); the text itself when it is no number. */
  private def tieValue(v: String): String = litNumber(v) match {
    case Some(n) if n.abs < 10 => n.toString
    case Some(n)               => (if (n < 0) "-" else "") + "0x" + n.abs.toString(16)
    case None                  => v
  }

  private val GroundKey = """(UInt|SInt)(<\d+>)?|Clock|Reset|AsyncReset|Analog""".r
  private val VecKey    = """Vec\[(.+), (\d+)\]""".r

  /** A type key this design resolves: a bundle it defines, a ground type, or a Vec of one. */
  private def known(key: String, reg: Registry): Boolean = key match {
    case VecKey(inner, _)            => known(inner, reg)
    case _ if GroundKey.matches(key) => true
    case _                           => reg.defs.contains(key)
  }

  private val CopySuffix = """(_\d+)+$""".r

  /** Instance name -> fold group id for the generator copies of `c`
    * (Seq.fill(n)(Module(...))): instances sharing a source marker, a Scala
    * class and a name without trailing indices, at least two of them. Copies
    * do not share a module name, so the marker plus the class is the key; the
    * base name keeps apart distinct instances a helper makes on one line
    * (`inQ`, `outQ`). The id is that base name, made unique against the
    * module's other children (the group's own members aside) and the earlier
    * ids with `_g2`... */
  private def foldGroupsOf(c: CModule, markers: Map[String, ChirrtlParser.SrcInfo],
                           byName: Map[String, CModule]): Map[String, String] = {
    val keyed = c.instances.flatMap(i => markers.get(i.name).map(src =>
      (src, byName.get(i.module).map(_.scalaClass), CopySuffix.replaceFirstIn(i.name, "")) -> i.name))
    val groups = keyed.map(_._1).distinct.map(k => k._3 -> keyed.filter(_._1 == k).map(_._2)).filter(_._2.size >= 2)
    val taken  = mutable.Set.empty[String] ++ c.instances.map(_.name)
    groups.flatMap { case (base, members) =>
      val others = taken -- members
      val id     = (base #:: LazyList.from(2).map(n => s"${base}_g$n")).find(!others(_)).get
      taken += id
      members.map(_ -> id)
    }.toMap
  }

  private val ClockResetTypes = Set("Clock", "Reset", "AsyncReset")

  // ── Directions (chisel3 SpecifiedDirection, as captured) ──────────────────
  private val Unspecified = "unspecified"
  private val Out         = "out"
  private val In          = "in"
  private val Flip        = "flip"

  private def flip(d: String): String = d match {
    case Unspecified => Flip
    case Flip        => Unspecified
    case Out         => In
    case In          => Out
    case other       => other
  }

  /** SpecifiedDirection.fromParent. */
  private def fromParent(parent: String, child: String): String = parent match {
    case Out | In => parent
    case Flip     => flip(child)
    case _        => child
  }

  /** Converter.firrtlUserDirOf: a Vec takes its element's direction. */
  private def userDir(t: CType): String =
    if (t.vecLen >= 0 && t.fields.nonEmpty) fromParent(t.dir, userDir(t.fields.head._2)) else t.dir

  // ── Types ─────────────────────────────────────────────────────────────────
  /** A type with its schema key, kept as a tree so a wire endpoint path
    * resolves to the key of the sub-type it names. */
  private case class Keyed(key: String, fields: Map[String, Keyed] = Map.empty, elem: Option[Keyed] = None)

  private val DecoupledClasses = Set("chisel3.util.DecoupledIO", "chisel3.util.IrrevocableIO")

  private def stripIdx(s: String): String = s.takeWhile(_ != '[')

  /** Walk `segs` (the first one names `k` itself, with any Vec indices)
    * down fields and Vec elements, stopping at the deepest node reached. */
  private def descend(k: Keyed, segs: List[String]): Keyed = segs match {
    case Nil => k
    case seg :: rest =>
      val at = (1 to seg.count(_ == '[')).foldLeft(k)((n, _) => n.elem.getOrElse(n))
      rest match {
        case next :: _ => at.fields.get(stripIdx(next)).fold(at)(descend(_, rest))
        case Nil       => at
      }
  }

  /** The schema name of a ground type, or None for a class this adapter does
    * not know (a ChiselEnum, a Property, ...). */
  private def ground(t: CType): Option[String] = {
    def sized(base: String) = if (t.width >= 0) s"$base<${t.width}>" else base
    t.scalaClass match {
      case "chisel3.Clock"               => Some("Clock")
      case "chisel3.ResetType"           => Some("Reset")
      case "chisel3.AsyncReset"          => Some("AsyncReset")
      case "chisel3.experimental.Analog" => Some("Analog")
      case "chisel3.SInt"                => Some(sized("SInt"))
      case "chisel3.UInt" | "chisel3.Bool" => Some(sized("UInt"))
      case _                             => None
    }
  }

  /** Ground leaves of a Decoupled payload, as IrWalk flattens `bits`. */
  private def flatten(t: CType, prefix: String): Seq[FieldDef] =
    if (t.vecLen >= 0) t.fields.headOption.toSeq.flatMap { case (_, e) =>
      (0 until t.vecLen).flatMap(i => flatten(e, s"$prefix[$i]"))
    }
    else if (t.fields.isEmpty) Seq(FieldDef(prefix, t.width.max(0), PortDir.Out))
    else t.fields.flatMap { case (n, f) => flatten(f, if (prefix.isEmpty) n else s"$prefix.$n") }

  /** Bundle definitions, keyed by Chisel class (or `<module>.<path>` for an
    * anonymous bundle); a class seen with a different structure (a generic
    * like DecoupledIO[T]) gets a structural hash suffix. */
  private final class Registry {
    val defs = mutable.LinkedHashMap.empty[String, BundleDef]
    /** Ground classes with no schema name, with how many leaves use each. */
    val unknown = mutable.LinkedHashMap.empty[String, Int]

    /** `clear`: an Input/Output above this node, so no flips below it. */
    def keyed(t: CType, clear: Boolean, path: String): Keyed = {
      val childClear = clear || t.dir == In || t.dir == Out
      if (t.vecLen >= 0) {
        val elem = t.fields.headOption.map { case (_, e) => keyed(e, childClear, s"$path[]") }
        Keyed(s"Vec[${elem.map(_.key).getOrElse("UInt")}, ${t.vecLen}]", elem = elem)
      } else if (t.fields.isEmpty) Keyed(ground(t).getOrElse {
        // Never invent a type: show the class's simple name and say so.
        unknown(t.scalaClass) = unknown.getOrElse(t.scalaClass, 0) + 1
        t.scalaClass.substring(t.scalaClass.lastIndexOf('.') + 1)
      })
      else {
        val kids = t.fields.map { case (n, f) => (n, keyed(f, childClear, s"$path.$n"), !childClear && (userDir(f) == Flip || userDir(f) == In)) }
        val body =
          if (DecoupledClasses(t.scalaClass))
            BundleDef("", BundleKind.Decoupled, fields = t.fields.find(_._1 == "bits").toSeq.flatMap(b => flatten(b._2, "")))
          else
            BundleDef("", BundleKind.Bundle, channels = kids.map { case (n, k, fl) => ChannelDef(n, k.key, fl) })
        val base = if (t.scalaClass.isEmpty || t.scalaClass.contains("$anon")) path else t.scalaClass
        Keyed(intern(base, body.copy(scalaClass = t.scalaClass)), kids.map { case (n, k, _) => n -> k }.toMap)
      }
    }

    private def intern(base: String, body: BundleDef): String = {
      val hex = java.security.MessageDigest.getInstance("SHA-1")
        .digest(body.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
      val candidates = base +: (6 to hex.length).map(n => s"${base}_${hex.take(n)}")
      val key = candidates.find(k => defs.get(k).forall(_.copy(name = "") == body))
        .getOrElse(sys.error(s"bundle key clash for $base"))
      if (!defs.contains(key)) defs(key) = body.copy(name = key)
      key
    }
  }
}
