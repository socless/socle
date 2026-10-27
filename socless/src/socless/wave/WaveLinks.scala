package socless.wave

import socless.layout.LayoutCore
import socless.schema._

/** What Surfer's right-click actions need from the design (WCP events
  * `goto_declaration`, `add_drivers`, `add_loads`, which name a signal by its
  * full trace path): the signal behind a trace path ([[locate]]), and the
  * traced signals that drive it or that it feeds ([[drivers]], [[loads]]).
  * Pure and shared by the language server and the Scala.js UI.
  *
  * Drivers and loads walk one graph per module: its wires, plus the wires of
  * every box's region (a logic block or an FSM), whose ports are the box's
  * pins (`blk.p` in the module, `p` inside). A wire is `(from, to)` = (sink,
  * driver). The walk goes through what the trace lacks (a box's pins, an op
  * or a mux, from its output to its inputs) and stops at what it holds: a
  * port of the module, a child instance's pin, a register, a module signal.
  * A constant ends it. An input port's drivers (an output port's loads) are
  * in the parent: the walk also starts there, from the instance's pin. */
object WaveLinks {

  /** A signal of the module at `instPath` (top first): `leaf` is a ground
    * port path (`io.in.bits.a`) or a signal (`x`); `source` where it is
    * declared, else the module's source when unknown (a captured port). */
  final case class Located(instPath: List[String], leaf: String, source: SourceRef)

  private def modules(d: Design): Map[String, ModuleDef] = d.modules.iterator.map(m => m.name -> m).toMap

  private def moduleAt(mods: Map[String, ModuleDef], d: Design, instPath: List[String]): Option[ModuleDef] =
    instPath.drop(1).foldLeft(mods.get(d.top)) { (cur, inst) =>
      cur.flatMap(_.children.find(_.name == inst)).flatMap(c => mods.get(c.`type`))
    }

  private def flat(path: String): String = path.replace('.', '_').replace('[', '_').replace("]", "")

  private def head(p: String): String = LayoutCore.pathHead(p)

  /** The ground port leaves of `m`, in port order. */
  private def portLeaves(d: Design, m: ModuleDef): Seq[(PortDef, String)] =
    m.ports.flatMap(p => WaveLayout.leaves(d, p).map(l => (p, l.path)))

  /** The signal a trace path names (inverse of [[WaveLayout.tracePath]]):
    * after `prefix`, the scopes are instance names from the top, the last
    * segment a flattened port leaf or a signal. None for a path outside the
    * design (a testbench signal) or one it cannot resolve. */
  def locate(d: Design, prefix: String, trace: String): Option[Located] =
    if (!trace.startsWith(prefix + ".")) None
    else {
      val mods = modules(d)
      val segs = trace.substring(prefix.length + 1).split('.').toList
      var path = List(d.top)
      var mod  = mods.get(d.top)
      var rest = segs
      while (rest.size > 1 && mod.exists(_.children.exists(_.name == rest.head))) {
        val inst = rest.head
        mod = mod.flatMap(_.children.find(_.name == inst)).flatMap(c => mods.get(c.`type`))
        path = path :+ inst
        rest = rest.tail
      }
      for {
        m    <- mod
        name <- rest match { case n :: Nil => Some(n); case _ => None }
        found <- portLeaves(d, m).collectFirst { case (p, l) if flat(l) == name =>
                   (l, if (p.source.line > 0) p.source else m.source) }
                   .orElse(m.signals.find(_.name == name).map(s => (s.name, if (s.source.line > 0) s.source else m.source)))
                   .orElse(regNode(d, m, name).map(n => (name, if (n.source.line > 0) n.source else m.source)))
      } yield Located(path, found._1, found._2)
    }

  /** The register node `name` of `m`, at the module's level or in a box's region. */
  private def regNode(d: Design, m: ModuleDef, name: String): Option[LogicNodeDef] = {
    def in(mod: ModuleDef, depth: Int): Option[LogicNodeDef] =
      mod.logic.find(n => n.kind == NodeKind.Reg && n.id == name).orElse(
        if (depth > 8) None
        else mod.logic.iterator.filter(n => NodeKind.isBox(n.kind)).flatMap(n => d.regions.get(n.id)).map(in(_, depth + 1)).collectFirst { case Some(n) => n })
    in(m, 0)
  }

  /** The trace paths of the traced signals that drive `trace`, nearest first. */
  def drivers(d: Design, prefix: String, traceNames: Set[String], trace: String): Seq[String] =
    links(d, prefix, traceNames, trace, back = true)

  /** The trace paths of the traced signals `trace` feeds, nearest first. */
  def loads(d: Design, prefix: String, traceNames: Set[String], trace: String): Seq[String] =
    links(d, prefix, traceNames, trace, back = false)

  private val MaxSteps = 10000

  /** One module's graph: its wires and its boxes' regions' wires, with names
    * qualified by region (`blk/x~q`). A region writes its box's pin as the
    * parent does (`blk.p`), so both sides name it alike. */
  private final class Graph(d: Design, m: ModuleDef) {
    private val edges = Seq.newBuilder[(String, String)]
    // scope qualifier ("" for the module) -> the logic nodes there
    val logic = scala.collection.mutable.Map.empty[String, Seq[LogicNodeDef]]
    private def qual(q: String, e: String) = if (q.isEmpty) e else s"$q/$e"
    private def add(q: String, mod: ModuleDef, own: Option[String], outer: String => String, depth: Int): Unit = {
      logic(q) = mod.logic
      // in a box's region, an end on the box's own pin is that pin in the scope around it
      def name(e: String) = if (own.contains(head(e))) outer(e) else qual(q, e)
      mod.wires.iterator.filterNot(LayoutCore.isClockReset).foreach(w => edges += ((name(w.from), name(w.to))))
      if (depth < 8) mod.logic.filter(n => NodeKind.isBox(n.kind)).foreach { n =>
        d.regions.get(n.id).foreach(r => add(qual(q, n.id), r, Some(n.id), name, depth + 1))
      }
    }
    add("", m, None, identity, 0)
    val all: Seq[(String, String)] = edges.result()
    val sinks: Seq[String] = all.map(_._1).distinct

    /** The scope and local name of a qualified end. */
    def split(e: String): (String, String) = {
      val i = e.lastIndexOf('/')
      if (i < 0) ("", e) else (e.substring(0, i), e.substring(i + 1))
    }
    def node(e: String): Option[LogicNodeDef] = {
      val (q, local) = split(e)
      val base = local.takeWhile(_ != '~')
      logic.getOrElse(q, Nil).find(_.id == base)
    }
    /** Whether `e` is a pin of a box (`blk.p`), in its scope. */
    def boxPin(e: String): Boolean = {
      val (q, local) = split(e)
      val h = head(local)
      h.length < local.length && logic.getOrElse(q, Nil).exists(n => n.id == h && NodeKind.isBox(n.kind))
    }
  }

  /** What an end of the graph is: a traced signal (its dotted path at the
    * module's level, or a child pin `c.p`), a dead end, or a step to go through. */
  private sealed trait Kind
  private final case class Traced(path: String) extends Kind
  private case object Dead extends Kind
  private case object Through extends Kind

  private def kind(g: Graph, m: ModuleDef, e: String): Kind = g.node(e) match {
    case Some(n) if n.kind == NodeKind.Reg   => Traced(n.id)
    case Some(n) if n.kind == NodeKind.Const => Dead
    case Some(_)                             => Through // an op, a mux, a memory port
    case None if g.boxPin(e)                 => Through // a box pin (`blk.p`): its region's port
    case None if e.contains('/')             => Dead
    case None =>
      val h = head(e)
      if (m.ports.exists(_.name == h) || m.children.exists(_.name == h) || m.signals.exists(_.name == e)) Traced(e)
      else Dead
  }

  /** `a` and `b` are the same path, or one is under the other; Some(the
    * suffix of `a` under `b`, "" when equal or `b` is under `a`), else None. */
  private def under(a: String, b: String): Option[String] =
    if (a == b) Some("")
    else if (a.length > b.length && a.startsWith(b) && (a.charAt(b.length) == '.' || a.charAt(b.length) == '[')) Some(a.substring(b.length))
    else if (b.length > a.length && b.startsWith(a) && (b.charAt(a.length) == '.' || b.charAt(a.length) == '[')) Some("")
    else None

  private def links(d: Design, prefix: String, traceNames: Set[String], trace: String, back: Boolean): Seq[String] = {
    val mods = modules(d)
    val out  = Seq.newBuilder[String]
    locate(d, prefix, trace).foreach { loc =>
      // the module's level, and the parent's for a port (the instance's pin)
      val levels = Seq((loc.instPath, loc.leaf)) ++
        (if (loc.instPath.size > 1 && moduleAt(mods, d, loc.instPath).exists(_.ports.exists(_.name == head(loc.leaf))))
          Seq((loc.instPath.init, s"${loc.instPath.last}.${loc.leaf}"))
        else Nil)
      for {
        (lvl, start) <- levels
        m            <- moduleAt(mods, d, lvl)
        path         <- walk(new Graph(d, m), m, start, back)
        t            <- traces(d, mods, lvl, m, path, prefix)
      } out += t
    }
    out.result().distinct.filter(t => t != trace && (traceNames.isEmpty || traceNames.contains(t)))
  }

  /** The traced ends reached from `start` (a port leaf, a child pin or a
    * signal at the module's level), breadth first. */
  private def walk(g: Graph, m: ModuleDef, start: String, back: Boolean): Seq[String] = {
    val found = Seq.newBuilder[String]
    val seen  = scala.collection.mutable.Set.empty[String]
    val queue = scala.collection.mutable.Queue.empty[String]
    // the starts: the signal itself, or its register's input (back) or output (forward)
    val reg = g.logic.iterator.flatMap { case (q, ns) => ns.find(n => n.kind == NodeKind.Reg && n.id == start).map(_ => q) }.toSeq.headOption
    reg match {
      case Some(q) =>
        val at = if (q.isEmpty) start else s"$q/$start"
        if (back) queue += at else { queue += s"$at~q"; queue += at }
      case None => queue += start
    }
    var steps = 0
    def reach(e: String, suffix: String): Unit = kind(g, m, e) match {
      case Traced(p) => found += p + (if (g.node(e).isDefined) "" else suffix)
      case Dead      => ()
      case Through   =>
        val next = g.node(e) match {
          // an op: back from its output to its inputs, forward from an input to its output
          case Some(n) =>
            val (q, _) = g.split(e)
            val id = if (q.isEmpty) n.id else s"$q/${n.id}"
            if (back) g.sinks.filter(s => s.startsWith(id + "~")) else Seq(id)
          case _ => Seq(e) // a box pin: the region's side of it
        }
        next.foreach(x => if (seen.add(x)) queue += x)
    }
    seen ++= queue
    while (queue.nonEmpty && steps < MaxSteps) {
      steps += 1
      val e = queue.dequeue()
      val edges = if (back) g.all.iterator.flatMap { case (s, dr) => under(e, s).map(sfx => (dr, sfx)) }
                  else g.all.iterator.flatMap { case (s, dr) => under(e, dr).map(sfx => (s, sfx)) }
      edges.foreach { case (other, sfx) => reach(other, sfx) }
    }
    found.result().distinct.filterNot(_ == start)
  }

  /** The trace paths of a traced end at level `lvl`: a port's or child pin's
    * ground leaves under it, a signal itself. */
  private def traces(d: Design, mods: Map[String, ModuleDef], lvl: List[String], m: ModuleDef, path: String, prefix: String): Seq[String] = {
    def within(l: String, p: String) = l == p || l.startsWith(p + ".") || l.startsWith(p + "[")
    val h = head(path)
    m.children.find(_.name == h) match {
      case Some(c) =>
        val pin = path.substring(h.length).dropWhile(_ == '.')
        mods.get(c.`type`).toSeq.flatMap(cm => portLeaves(d, cm).map(_._2).filter(within(_, pin)))
          .map(WaveLayout.tracePath(prefix, lvl :+ h, _))
      case None =>
        val leaves = portLeaves(d, m).map(_._2).filter(within(_, path))
        (if (leaves.nonEmpty) leaves else Seq(path)).map(WaveLayout.tracePath(prefix, lvl, _))
    }
  }
}
