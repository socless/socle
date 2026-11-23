package socle.ui.model

import socle.schema._

/** The wires one drawn wire stands for, grouped by the connection they make.
  * A capture keeps a whole-bundle connect (`io.mem.rd(1) <> dma.io.rd(0)`)
  * as one wire per field; the field they share at both ends (`.cmd.bits`) is cut
  * off, and what is left, the two bundles (`io.mem.rd[1]`, `dma.io.rd[0]`),
  * is the connection the Scala code makes. */
object WireGroups {

  /** A connection: its end under the selection, its far end, its field wires. */
  final case class Group(end: String, far: String, wires: Seq[WireDef])

  private def under(p: String, root: String) = p.startsWith(root + ".") || p.startsWith(root + "[")

  /** The two bundles a field wire `a` (under `sel`) to `b` connects: the longest
    * field both end with, cut off where `a` stays at or below `sel`. */
  def roots(a: String, b: String, sel: String): (String, String) =
    a.indices.iterator
      .filter(i => i >= sel.length && (a(i) == '.' || a(i) == '['))
      .find { i => val f = a.drop(i); b.length > f.length && b.endsWith(f) }
      .fold((a, b))(i => (a.take(i), b.dropRight(a.length - i)))

  /** The wires under `sel` (only those to `far` when given), as connections,
    * in the order of their first wire. */
  def of(ws: Seq[WireDef], sel: String, far: Option[String] = None): Seq[Group] = {
    val mine = ws.flatMap { w =>
      val (a, b) = if (under(w.from, sel)) (w.from, w.to) else (w.to, w.from)
      if (far.forall(f => b == f || under(b, f))) Some(roots(a, b, sel) -> w) else None
    }
    mine.groupBy(_._1).toSeq.sortBy { case (_, xs) => mine.indexWhere(_._2 eq xs.head._2) }
      .map { case ((e, f), xs) => Group(e, f, xs.map(_._2)) }
  }

  /** The declared type at `path` in module `mod`: under one of its ports, or
    * under a port of one of its instances. Empty when not found. */
  def typeAt(mod: ModuleDef, d: Design, path: String): String = {
    def head(p: String) = p.takeWhile(c => c != '.' && c != '[')
    val h = head(path)
    val port: Option[(PortDef, String)] =
      mod.ports.find(_.name == h).map(_ -> h).orElse {
        mod.children.find(_.name == h).flatMap(c => d.modules.find(_.name == c.`type`)).flatMap { cm =>
          val ph = head(path.drop(h.length + 1))
          cm.ports.find(_.name == ph).map(_ -> s"$h.$ph")
        }
      }
    def find(n: BundleTree.PortNode): Option[String] =
      if (n.path == path) Some(n.bundleKey) else n.children.iterator.flatMap(find).nextOption()
    port.flatMap { case (p, pre) => find(BundleTree.portTree(p.bundle, None, pre, d)) }.getOrElse("")
  }
}
