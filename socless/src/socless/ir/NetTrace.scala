package socless.ir

import socless.schema._
import socless.parse._
import scala.collection.mutable

/** Pure net resolver (issue #182 Part A).
  *
  * CHIRRTL emits one ground `connect lhs, rhs` per signal (rhs drives lhs).
  * Bundle fields can reach their real destination through internal nets: alias
  * wires, plain wire/node references, and registers (all expressed as connects).
  * This contracts those internal hops and returns the resolved port-field ->
  * port-field edges. Primop expressions (`node _T = add(a, b)`) are NOT connects,
  * so a path running into a primop node terminates here - rendering that logic is
  * Part B (#188). Output is sorted for deterministic golden snapshots.
  */
object NetTrace {

  /** @param edges      ground connects as (sink, source), i.e. CHIRRTL (lhs, rhs).
    * @param isExternal true if a ground path is a port-field terminal (head is an
    *                   instance name or a module-own port), false for internal.
    * @return resolved (source, sink) pairs, both external, internal hops removed.
    */
  def resolve(edges: Seq[(String, String)], isExternal: String => Boolean): Seq[(String, String)] = {
    val fwd = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
    edges.foreach { case (sink, source) =>
      fwd.getOrElseUpdate(source, mutable.LinkedHashSet.empty) += sink
    }
    val results = mutable.LinkedHashSet.empty[(String, String)]
    val externalSources = edges.iterator.map(_._2).filter(isExternal).toSeq.distinct.sorted
    externalSources.foreach { src =>
      val visited = mutable.HashSet.empty[String]
      visited += src
      def dfs(node: String): Unit =
        fwd.getOrElse(node, mutable.LinkedHashSet.empty).foreach { succ =>
          if (isExternal(succ)) results += ((src, succ)) // external sink: record, don't trace through
          else if (visited.add(succ)) dfs(succ)          // internal: contract, guard cycles
        }
      dfs(src)
    }
    results.toSeq.sorted
  }
}
