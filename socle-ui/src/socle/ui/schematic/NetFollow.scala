package socle.ui.schematic

import socle.schema.{Design, ModuleDef}
import socle.layout.LayoutCore

/** Follow a net across drill levels, through the ports where its wires meet
  * an instance (pure; [[Highlight]] applies the result as classes).
  *
  * A followed net is the wire clicked, at the level it was clicked
  * ([[Net]]). At any other level its wires are found by walking the drill
  * path from that level:
  *  - up, to a parent: the lit wires' ends on this module's own ports become
  *    the instance's ports in the parent (`inst.<port>`), and the parent's
  *    wires on them light;
  *  - down, into an instance: the lit wires' ends on that instance's ports
  *    become the child's boundary ports (`<port>`), and the child's wires on
  *    them light.
  * The walk goes up from the clicked level to the levels' common ancestor,
  * then down to the level shown, so the result depends only on the clicked
  * wire and the path: drilling in and out, Back and Forward all land on the
  * same wires. A level outside the clicked level's tree (another root, as a
  * module-rooted palette path) lights nothing.
  *
  * Paths match at any granularity (a bundle wire `io.in` and a field wire
  * `io.in.valid` touch), with a boundary-correct prefix test ([[overlaps]]):
  * `cores_1` never matches `cores_10`. Clock and reset wires are not followed,
  * as they are not drawn. */
object NetFollow {

  /** The clicked wire: the drill path it was clicked at and its two ends (as
    * drawn: `from` the sink, `to` the driver). */
  final case class Net(origin: List[String], from: String, to: String)

  /** What lights at one level: the drawn wires (as `(from, to)`) and the wire
    * ends and crossing ports whose pins light. */
  final case class Lit(wires: Set[(String, String)], ends: Set[String])
  object Lit { val empty: Lit = Lit(Set.empty, Set.empty) }

  /** `a` and `b` are the same path, or one is under the other (at a `.` or `[`). */
  def overlaps(a: String, b: String): Boolean = a == b || under(a, b) || under(b, a)
  private def under(p: String, k: String): Boolean =
    p.length > k.length && p.startsWith(k) && (p.charAt(k.length) == '.' || p.charAt(k.length) == '[')

  /** The module at a drill path (its head a module name, then instance names),
    * as App resolves it. */
  def moduleAt(d: Design, path: List[String]): Option[ModuleDef] = moduleAt(byName(d), path)
  // Modules by name, for the last design seen (a design is immutable; the map
  // was most of a resolve's time on a 500-module design).
  private var byNameOf: (Design, Map[String, ModuleDef]) = null
  private def byName(d: Design): Map[String, ModuleDef] = {
    if (byNameOf == null || (byNameOf._1 ne d)) byNameOf = (d, d.modules.iterator.map(m => m.name -> m).toMap)
    byNameOf._2
  }
  private def moduleAt(mods: Map[String, ModuleDef], path: List[String]): Option[ModuleDef] =
    path match {
      case Nil       => None
      case m :: rest => rest.foldLeft(mods.get(m)) { (acc, inst) =>
        for { mod <- acc; c <- mod.children.find(_.name == inst); cm <- mods.get(c.`type`) } yield cm
      }
    }

  private def head(p: String): String = LayoutCore.pathHead(p)

  /** The wires lit at `path` for `net`. `shown` are the wires drawn at `path`
    * (as `(from, to)`): the result names those, so a Vec group or a folded
    * box lights as drawn. Other levels use the module's own wires. */
  def resolve(d: Design, net: Net, path: List[String], shown: Seq[(String, String)]): Lit = {
    val common = net.origin.iterator.zip(path.iterator).takeWhile { case (a, b) => a == b }.size
    if (common == 0) return Lit.empty
    val mods = byName(d)
    def wiresAt(lvl: List[String]): Seq[(String, String)] =
      if (lvl == path) shown
      else moduleAt(mods, lvl).fold(Seq.empty[(String, String)])(
        _.wires.iterator.filterNot(LayoutCore.isClockReset).map(w => (w.from, w.to)).toSeq)
    def touching(ws: Seq[(String, String)], ports: Set[String]): Set[(String, String)] =
      if (ports.isEmpty) Set.empty
      else ws.iterator.filter { case (f, t) => ports.exists(p => overlaps(f, p) || overlaps(t, p)) }.toSet
    def endsOf(ws: Set[(String, String)]): Set[String] = ws.flatMap { case (f, t) => Seq(f, t) }

    var lvl = net.origin
    var lit = wiresAt(lvl).iterator.filter { case (f, t) =>
      (overlaps(f, net.from) && overlaps(t, net.to)) || (overlaps(f, net.to) && overlaps(t, net.from))
    }.toSet
    var cross = Set.empty[String]
    // Up: the ends on this module's ports cross into the parent as `inst.<port>`.
    while (lvl.length > common) {
      val ports = moduleAt(mods, lvl).map { m =>
        val names = m.ports.iterator.map(_.name).toSet
        val inner = m.children.iterator.map(_.name).toSet
        endsOf(lit).filter { e => val h = head(e); names(h) && !inner(h) }
      }.getOrElse(Set.empty)
      cross = ports.map(lvl.last + "." + _)
      lvl = lvl.init
      lit = touching(wiresAt(lvl), cross)
    }
    // Down: the ends on the next instance's ports cross into it as `<port>`.
    while (lvl.length < path.length) {
      val inst = path(lvl.length)
      cross = endsOf(lit).collect { case e if e.length > inst.length + 1 && e.startsWith(inst) && e.charAt(inst.length) == '.' => e.substring(inst.length + 1) }
      lvl = lvl :+ inst
      lit = touching(wiresAt(lvl), cross)
    }
    Lit(lit, endsOf(lit) ++ cross)
  }

  /** Test hook (smoke): for every drawn wire with an end on an instance that
    * has a module, follow it from `path` into that instance and back up
    * through one of the child's lit wires; the original must light again,
    * and every child wire must touch the port it entered by. Returns (wires
    * checked, failures). */
  def selfCheck(d: Design, path: List[String], shown: Seq[(String, String)]): (Int, Seq[String]) = {
    val mods = byName(d)
    val bad = Seq.newBuilder[String]
    var n = 0
    for {
      mod <- moduleAt(mods, path).toSeq
      (f, t) <- shown
      end <- Seq(f, t).distinct
      inst = head(end)
      if end.length > inst.length && end.charAt(inst.length) == '.'
      if mod.children.exists(_.name == inst) && moduleAt(mods, path :+ inst).isDefined
    } {
      n += 1
      val net   = Net(path, f, t)
      val here  = resolve(d, net, path, shown)
      // The ports it enters by (both ends, for a wire looping on one instance).
      val ports = Seq(f, t).filter(e => head(e) == inst && e.length > inst.length).map(_.substring(inst.length + 1))
      val child = resolve(d, net, path :+ inst, wiresOfChild(mods, path :+ inst))
      if (!here.wires.contains((f, t))) bad += s"$f <= $t: not lit where clicked"
      child.wires.find { case (a, b) => !ports.exists(p => overlaps(a, p) || overlaps(b, p)) }
        .foreach { case (a, b) => bad += s"$f <= $t: in $inst, $a <= $b is off ${ports.mkString(", ")}" }
      child.wires.headOption.foreach { case (a, b) =>
        val back = resolve(d, Net(path :+ inst, a, b), path, shown)
        if (!back.wires.contains((f, t))) bad += s"$f <= $t: following $a <= $b in $inst back up missed it"
      }
    }
    (n, bad.result())
  }
  private def wiresOfChild(mods: Map[String, ModuleDef], p: List[String]): Seq[(String, String)] =
    moduleAt(mods, p).fold(Seq.empty[(String, String)])(_.wires.iterator.filterNot(LayoutCore.isClockReset).map(w => (w.from, w.to)).toSeq)
}
