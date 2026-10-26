package socless.wave

import socless.schema._
import upickle.default.{ReadWriter, macroRW}

/** One signal of the waveform list: its full dotted trace path, the label shown
  * in the viewer, a Surfer theme colour name (Green, Red, Yellow, Blue, Pink,
  * Orange, Gray, Violet) and its width in bits (0 when unknown, as for a named
  * node). */
case class WaveItem(trace: String, label: String, color: String, width: Int = 0)
object WaveItem { implicit val rw: ReadWriter[WaveItem] = macroRW }

/** A named, possibly nested, group of signals. `folded` collapses it in the viewer. */
case class WaveGroup(name: String, items: Seq[WaveItem], children: Seq[WaveGroup], folded: Boolean)
object WaveGroup { implicit val rw: ReadWriter[WaveGroup] = macroRW }

/** The signal list of one module. `missing` counts the signals dropped because
  * the trace does not hold them (optimised away). */
case class WavePlan(groups: Seq[WaveGroup], missing: Int)
object WavePlan { implicit val rw: ReadWriter[WavePlan] = macroRW }

/** The pure plan of a module's signals for the waveform viewer, shared by the
  * language server and the Scala.js UI, so it stays free of JVM-only code.
  *
  * The order is the Chisel declaration order: clock and reset (when asked),
  * one group per port, then `Registers`, then `Wires`. */
object WaveLayout {

  private val Segment = """[^.\[\]]+|\[\d+\]""".r

  /** A ground leaf of a port: its full dotted path (`io.in.bits.a`), its width and its
    * direction seen from the module (`in`, `out`, None when unknown). */
  private case class Leaf(path: String, dir: Option[String], width: Int)

  /** The trace path of `leaf` (a dotted path inside the module) for the instance
    * at `instPath`. The first element of `instPath` is the top instance, which is
    * the prefix itself; each later one adds a scope. Verilator flattens
    * `a.b` to `a_b` and `v[i]` to `v_i`. */
  def tracePath(prefix: String, instPath: List[String], leaf: String): String = {
    val flat = leaf.replace('.', '_').replace('[', '_').replace("]", "")
    (prefix +: (instPath.drop(1) :+ flat)).mkString(".")
  }

  /** The module at `instPath` (top first), None when a step does not resolve. */
  private def module(design: Design, instPath: List[String]): Option[ModuleDef] = {
    def find(name: String) = design.modules.find(_.name == name)
    instPath.drop(1).foldLeft(find(design.top)) { (cur, inst) =>
      cur.flatMap(_.children.find(_.name == inst)).flatMap(c => find(c.`type`))
    }
  }

  /** The ground leaves of a port in bundle order, with directions seen from the
    * module. A Decoupled's valid/ready follow the channel's own direction (the
    * producer drives valid, the consumer ready), whatever side the port is on. */
  private def leaves(design: Design, p: PortDef): Seq[Leaf] = {
    val base = if (p.dir == PortDir.Out || p.dir == PortDir.Master) PortDir.Out else PortDir.In
    def flip(d: Option[String]) = d.map(x => if (x == PortDir.Out) PortDir.In else PortDir.Out)
    def walk(n: BundleTree.PortNode): Seq[Leaf] = {
      val decoupled = design.bundles.get(n.bundleKey).exists(_.kind == BundleKind.Decoupled)
      if (n.children.isEmpty) Seq(Leaf(n.path, n.dir, n.width))
      else n.children.flatMap { c =>
        if (decoupled && c.name == "valid") Seq(Leaf(c.path, n.dir, c.width))
        else if (decoupled && c.name == "ready") Seq(Leaf(c.path, flip(n.dir), c.width))
        else walk(c)
      }
    }
    walk(BundleTree.portTree(p.bundle, Some(base), p.name, design))
  }

  private def isClockReset(p: PortDef) = p.name == "clock" || p.name == "reset"

  /** Signals of a leaf path below its port name: `io.in.bits.a` of port `io`
    * gives `in`, `bits`, `a`. */
  private def segments(port: String, path: String): List[String] =
    Segment.findAllIn(path.stripPrefix(port)).toList

  def plan(design: Design, instPath: List[String], prefix: String, traceNames: Set[String], s: WaveSettings): WavePlan =
    module(design, instPath) match {
      case None => WavePlan(Nil, 0)
      case Some(m) =>
        var missing = 0
        def keep(trace: String): Boolean = {
          val ok = traceNames.isEmpty || traceNames.contains(trace)
          if (!ok) missing += 1
          ok
        }
        def item(path: String, label: String, color: String, width: Int): Option[WaveItem] = {
          val t = tracePath(prefix, instPath, path)
          if (keep(t)) Some(WaveItem(t, label, color, width)) else None
        }
        // A group from leaves keyed by their remaining segments: one-segment
        // leaves are its items, the others nest by their first segment.
        def build(name: String, ls: Seq[(List[String], Leaf)], port: PortDef, nested: Boolean): WaveGroup = {
          val here = ls.collect { case (segs, l) if segs.size <= 1 => l }
          val deeper = ls.filter(_._1.size > 1)
          val order = deeper.map(_._1.head).distinct
          val items = here.flatMap { l =>
            val label = segments(port.name, l.path).lastOption.getOrElse(port.name)
            item(l.path, label, if (s.colorByDirection && l.dir.contains(PortDir.Out)) "Green" else "Blue", l.width)
          }
          val kids = order.map(o => build(o, deeper.filter(_._1.head == o).map { case (segs, l) => (segs.tail, l) }, port, nested = true))
          WaveGroup(name, items, kids.filter(g => g.items.nonEmpty || g.children.nonEmpty), folded = nested && s.foldBundles)
        }
        def portGroup(p: PortDef): WaveGroup =
          build(p.name, leaves(design, p).map(l => (segments(p.name, l.path), l)), p, nested = false)
        def signalGroup(name: String, kind: String, color: String): WaveGroup =
          WaveGroup(name, m.signals.filter(_.kind == kind).flatMap(sig => item(sig.name, sig.name, color, sig.width)), Nil, folded = false)

        val (clockReset, others) = m.ports.partition(isClockReset)
        val portGroups  = (if (s.showClockReset) clockReset else Nil) ++ others
        val groups =
          portGroups.map(portGroup) ++ Seq(signalGroup("Registers", "reg", "Orange")) ++
            (if (s.showWires) Seq(signalGroup("Wires", "wire", "Gray")) else Nil)
        WavePlan(groups.filter(g => g.items.nonEmpty || g.children.nonEmpty), missing)
    }

  /** The paths of `traces` that Surfer added, matched to the names it shows for
    * the ids it returned (`get_item_info`, in order). Surfer drops the paths
    * its trace lacks silently and keeps the order of the others, so the match
    * walks both in order. A name is the path's leaf or a dotted tail of it (its
    * unique name), maybe with a ` [msb:lsb]` index suffix. None for a name no
    * later path matches. */
  def matchAdded(traces: Seq[String], names: Seq[String]): Seq[Option[String]] = {
    var rest = traces.toList
    names.map { shown =>
      val n = shown.replaceAll(" \\[[^\\]]*\\]$", "")
      rest.dropWhile(t => t != n && !t.endsWith("." + n)) match {
        case t :: tail => rest = tail; Some(t)
        case Nil       => None
      }
    }
  }

  /** The trace path to focus for a schematic selection inside the module: the
    * first ground field of a port or bundle pin, or a register or wire itself.
    * None when the selection is not a signal of this module or the trace lacks it. */
  def focus(design: Design, instPath: List[String], prefix: String, traceNames: Set[String], selection: String): Option[String] =
    module(design, instPath).flatMap { m =>
      def within(path: String) = path == selection || path.startsWith(selection + ".") || path.startsWith(selection + "[")
      val fromPort = m.ports.view.flatMap(p => leaves(design, p)).map(_.path).find(within)
      val path = fromPort.orElse(m.signals.view.map(_.name).find(within))
      path.map(tracePath(prefix, instPath, _)).filter(t => traceNames.isEmpty || traceNames.contains(t))
    }
}
