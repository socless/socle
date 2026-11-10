package socle.capture

import scala.collection.mutable

/** Splits generator-copy candidates (instances one source line made, see
  * `ModelToDesign.foldGroupsOf`) into groups whose copies really play one role,
  * from the module's wiring. A folded group is drawn as one box that takes every
  * member's wires, so only two shapes fold:
  *
  *  - parallel copies: no wire between two of them and the same connections
  *    (own port, direction, peer port; Vec indices dropped, a peer copy named by
  *    its group; one copy may have more), e.g. `A0 / A1 / A2` each between
  *    `io.in` and `io.out`;
  *  - a chain: copies wired directly one to the next, nothing in between, e.g.
  *    `A0 => A1 => A2`; the folded box then shows the chain's outer wires.
  *
  * Anything else is split: `A0 => C => A1` (two copies on either side of
  * another instance) is neither, so neither copy folds; a 2D array of copies
  * wired to each other (a systolic grid) is not a chain, so it does not fold;
  * nor do copies linked by a private logic path (`A0 -> reg -> A1`, each node
  * with one driver and one load), which is something in between. Shared logic
  * (an arbiter's register every copy feeds and reads) does not split copies,
  * and wires to logic are not compared otherwise: their ids differ per copy.
  */
object FoldGroups {

  /** `candidates`: instance -> candidate group id; `children`: the module's
    * instances in order; `links`: its wires as (sink, source) endpoint paths;
    * `ports`: its own port names. Returns instance -> group id for the groups
    * that fold (at least two members each). A candidate split into several
    * groups keeps its id for the first and takes `_g2`, `_g3`... for the next,
    * unique against the other instances and the other ids. */
  def refine(candidates: Map[String, String], children: Seq[String],
             links: Seq[(String, String)], ports: Set[String]): Map[String, String] = {
    if (candidates.isEmpty) return Map.empty
    val instances = children.toSet
    val order     = children.zipWithIndex.toMap

    def head(path: String): String = stripIdx(path.takeWhile(_ != '.'))
    def tail(path: String): String = noIdx(path.dropWhile(_ != '.'))
    // A logic node by its endpoint head: a register's read `r~q` is `r`.
    def node(path: String): String = head(path).takeWhile(_ != '~')
    def isLogic(n: String): Boolean = !instances(n) && !ports(n)

    // Each logic node's peers that are not the module's ports (a clock, an
    // enable from `io` do not count): what drives it, what it drives.
    val drivers = mutable.Map.empty[String, mutable.Set[String]]
    val loads   = mutable.Map.empty[String, mutable.Set[String]]
    for ((snk, src) <- links) {
      val (s, r) = (node(snk), node(src))
      if (s != r) {
        if (isLogic(s) && !ports(r)) drivers.getOrElseUpdate(s, mutable.Set.empty) += r
        if (isLogic(r) && !ports(s)) loads.getOrElseUpdate(r, mutable.Set.empty) += s
      }
    }
    /** The copy `m` reaches through a private logic path from one of its
      * outputs: logic nodes with one driver and one load each (a `RegNext`, a
      * cast), at most [[MaxPrivateHops]] of them. Shared logic (an arbiter's
      * register read by every copy) is not private. */
    def privateReach(m: String): Set[String] =
      links.iterator.collect { case (snk, src) if head(src) == m && isLogic(node(snk)) => node(snk) }.toSet[String].flatMap { (first: String) =>
        var (prev, cur, hops) = (m, first, 0)
        var found: Option[String] = None
        var go = true
        while (go && hops < MaxPrivateHops) {
          val ds = drivers.getOrElse(cur, Set.empty); val ls = loads.getOrElse(cur, Set.empty)
          if (ds != Set(prev) || ls.size != 1) go = false
          else {
            val next = ls.head
            if (instances(next)) { if (next != m) found = Some(next); go = false }
            else { prev = cur; cur = next; hops += 1 }
          }
        }
        found
      }

    // Every wire end on an instance, once per instance.
    val byInst = mutable.Map.empty[String, mutable.ArrayBuffer[(String, String, String)]] // (dir, own, peer path)
    for ((snk, src) <- links) {
      val (hs, hr) = (head(snk), head(src))
      if (hs != hr) {
        if (instances(hs)) byInst.getOrElseUpdate(hs, mutable.ArrayBuffer.empty) += (("in", tail(snk), src))
        if (instances(hr)) byInst.getOrElseUpdate(hr, mutable.ArrayBuffer.empty) += (("out", tail(src), snk))
      }
    }
    def ends(i: String) = byInst.getOrElse(i, mutable.ArrayBuffer.empty)

    val groups = mutable.ArrayBuffer.empty[(String, Seq[String])] // (candidate id, members)
    candidates.groupBy(_._2).toSeq.sortBy { case (_, ms) => ms.keys.map(order).min }.foreach { case (id, ms) =>
      // Copies linked by a private logic path (`a_0 -> reg -> a_1`) have
      // something in between: neither parallel nor a chain, so neither folds.
      val all     = ms.keys.toSet
      val linked  = all.flatMap(m => privateReach(m).filter(all).flatMap(n => Set(m, n)))
      val members = (all -- linked).toSeq.sortBy(order)
      val inGroup = members.toSet
      // Direct wires between members, as undirected pairs.
      val adj = members.map(m => m -> mutable.Set.empty[String]).toMap
      for (m <- members; (_, _, peer) <- ends(m); p = head(peer) if inGroup(p) && p != m) {
        adj(m) += p; adj(p) += m
      }
      // Connected copies: a component that is a simple path is a chain; any
      // other shape (a grid, a ring, a tree) does not fold.
      val seen = mutable.Set.empty[String]
      val isolated = mutable.ArrayBuffer.empty[String]
      for (m <- members if !seen(m)) {
        if (adj(m).isEmpty) { seen += m; isolated += m }
        else {
          val comp = mutable.ArrayBuffer(m); seen += m
          var i = 0
          while (i < comp.size) {
            adj(comp(i)).toSeq.sortBy(order).foreach(n => if (!seen(n)) { seen += n; comp += n })
            i += 1
          }
          val edges = comp.map(adj(_).size).sum / 2
          if (edges == comp.size - 1 && comp.forall(adj(_).size <= 2)) groups += (id -> comp.toSeq.sortBy(order))
        }
      }
      // Copies with no wire to one another: parallel when their connections match.
      def peerKey(peer: String): Option[String] = {
        val h = head(peer)
        if (instances(h)) Some(candidates.getOrElse(h, h) + tail(peer))
        else if (ports(h)) Some(noIdx(peer))
        else None // a logic node: its id is the copy's own
      }
      def signature(m: String): Set[(String, String, String)] =
        ends(m).iterator.flatMap { case (dir, own, peer) => peerKey(peer).map(k => (dir, own, k)) }.toSet
      // A copy may have a few connections more than the others (the head of a
      // `Seq` driving a status port): it joins a class when, with every copy
      // already in it, one signature holds the other. Smallest signatures first,
      // so a class starts from its common part.
      val sig = isolated.map(m => m -> signature(m)).toMap
      def fits(a: String, b: String) = sig(a).subsetOf(sig(b)) || sig(b).subsetOf(sig(a))
      val classes = mutable.ArrayBuffer.empty[mutable.ArrayBuffer[String]]
      isolated.sortBy(m => (sig(m).size, order(m))).foreach { m =>
        classes.find(_.forall(fits(_, m))) match {
          case Some(ms) => ms += m
          case None     => classes += mutable.ArrayBuffer(m)
        }
      }
      classes.map(_.toSeq.sortBy(order)).sortBy(c => order(c.head)).foreach(c => groups += (id -> c))
    }

    // Ids: the first group of a candidate keeps it, the next ones are suffixed.
    val kept  = groups.filter(_._2.size >= 2).sortBy(g => order(g._2.head))
    val taken = mutable.Set.empty[String]
    kept.flatMap { case (id, members) =>
      val others = instances -- members
      val gid = (id #:: LazyList.from(2).map(n => s"${id}_g$n")).find(c => !others(c) && !taken(c)).get
      taken += gid
      members.map(_ -> gid)
    }.toMap
  }

  /** A bound on the walk along a private logic path (a delay line of
    * registers is a few nodes; a longer path is not followed). */
  private val MaxPrivateHops = 16

  private def stripIdx(s: String): String = s.takeWhile(_ != '[')
  private def noIdx(s: String): String = s.replaceAll("""\[[^\]]*\]""", "")
}
