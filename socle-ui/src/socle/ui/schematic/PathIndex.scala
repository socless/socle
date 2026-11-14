package socle.ui.schematic

/** Paths to elements, answering the two highlight questions without a scan:
  * the paths `k` or under it (a binary-searched range of the sorted paths per
  * separator, O(log n + hits)), and the paths `k` is under (a map lookup per
  * separator of `k`, O(|k|)). A path is under another on a separator, never
  * where it only shares its text (`c.io.ab` is not under `c.io.a`). */
final class PathIndex[A](entries: Iterable[(String, A)]) {
  import PathIndex.{Seps, isSep}
  private val sorted: Array[(String, A)] = entries.toArray.sortBy(_._1)
  private val byPath: Map[String, Seq[A]] = entries.groupMap(_._1)(_._2).map { case (k, v) => k -> v.toSeq }
  /** The elements whose path starts with `k`, as text. */
  def startingWith(k: String): Iterator[A] = {
    var lo = 0; var hi = sorted.length
    while (lo < hi) { val mid = (lo + hi) >>> 1; if (sorted(mid)._1.compareTo(k) < 0) lo = mid + 1 else hi = mid }
    sorted.iterator.drop(lo).takeWhile(_._1.startsWith(k)).map(_._2)
  }
  /** The elements whose path is `k` or under it. */
  def under(k: String): Iterator[A] =
    exactly(k).iterator ++ Seps.iterator.flatMap(c => startingWith(k + c))
  /** The elements whose path is `k` or over it (`k` is under the path). */
  def over(k: String): Iterator[A] =
    exactly(k).iterator ++ k.indices.iterator.filter(i => isSep(k.charAt(i))).flatMap(i => exactly(k.substring(0, i)))
  def exactly(k: String): Seq[A] = byPath.getOrElse(k, Nil)
}

object PathIndex {
  /** What separates a path's levels: a field (`.`), a Vec element (`[`), a
    * logic node's pin (`r~q`, `x~in0`, `mem~port~role`). */
  val Seps: Seq[Char] = Seq('.', '[', '~')
  def isSep(c: Char): Boolean = Seps.contains(c)

  /** `p` is `k` or under it (a field, an element or a pin of `k`). */
  def within(p: String, k: String): Boolean =
    p.startsWith(k) && (p.length == k.length || isSep(p.charAt(k.length)))
}
