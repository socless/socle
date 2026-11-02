package socle.lsp

import scala.meta._
import scala.util.Try

/** The classes and traits under `root` whose extends chain reaches
  * `ChiselSim`, by simple and fully-qualified name, so that a suite extending
  * a project base trait (`class S extends SimBase`, `trait SimBase extends
  * AnyFlatSpec with ChiselSim`, in any module of the workspace) gets lenses.
  *
  * Parse-only, like [[TestDiscovery]]. A parent is matched by its simple
  * name: with two same-named types in different packages the lens is given
  * rather than hidden (a wrong guess is reported honestly by the view run).
  * Skips `out`, `node_modules`, `target` and dot-directories below `root`.
  *
  * `subjectBases()` names the classes and traits whose own body, or an
  * ancestor's, sets a FlatSpec subject (`behavior of`, `"S" should ...`): a
  * suite extending one inherits that subject, unknown to its own source.
  *
  * Both sets are cached: `invalidate()` (on save, never blocking) makes the
  * next query re-stat the tree, re-parsing only files whose mtime changed and
  * dropping deleted ones. Thread-safe: queries serialize on one lock.
  * `onRebuild` is a test hook, run under the lock at the start of a rebuild.
  */
final class SimBaseIndex(root: os.Path, onRebuild: () => Unit = () => ()) {
  import SimBaseIndex._

  private val lock   = new Object
  private val dirty  = new java.util.concurrent.atomic.AtomicBoolean(true)
  private var files  = Map.empty[os.Path, (Long, Seq[Def])]          // guarded by lock
  private var cached = (Set.empty[String], Set.empty[String])         // guarded by lock: (sim, subject)
  private var parses = 0                                               // guarded by lock

  /** (`bases()`, `subjectBases()`) from one consistent build. */
  def both(): (Set[String], Set[String]) = lock.synchronized {
    if (dirty.getAndSet(false)) cached = rebuild() // an invalidate during the rebuild sets it again
    cached
  }

  def bases(): Set[String] = both()._1

  def subjectBases(): Set[String] = both()._2

  /** Lock-free: safe on the LSP reader thread while a rebuild runs. */
  def invalidate(): Unit = dirty.set(true)

  /** Files parsed so far (cache misses), for tests. */
  private[lsp] def parseCount: Int = lock.synchronized(parses)

  private def rebuild(): (Set[String], Set[String]) = {
    onRebuild()
    files = scalaFiles(root).flatMap(f => Try(f -> os.mtime(f)).toOption).map { case (f, mtime) =>
      f -> files.get(f).filter(_._1 == mtime).getOrElse {
        parses += 1
        (mtime, Try(defsIn(os.read(f))).getOrElse(Nil))
      }
    }.toMap
    val all = files.valuesIterator.flatMap(_._2).toSeq
    (Seeds ++ closure(all, Set.empty, Seeds.map(TestDiscovery.simpleName)),
     closure(all, all.filter(_.setsSubject).toSet, Set.empty))
  }
}

object SimBaseIndex {
  private val SkipDirs = Set("out", "node_modules", "target")
  private val Seeds    = Set("ChiselSim", "chisel3.simulator.scalatest.ChiselSim")

  /** A class or trait: simple name, dotted full name, parents as written,
    * whether its body sets a FlatSpec subject. */
  private final case class Def(simple: String, fq: String, parents: Seq[String], setsSubject: Boolean)

  private[lsp] def scalaFiles(dir: os.Path): Seq[os.Path] =
    Try(os.list(dir)).getOrElse(Nil).flatMap { p =>
      if (os.isDir(p, followLinks = false)) {
        if (SkipDirs(p.last) || p.last.startsWith(".")) Nil else scalaFiles(p)
      } else if (p.ext == "scala" && os.isFile(p)) Seq(p)
      else Nil
    }

  private def defsIn(source: String): Seq[Def] =
    TestDiscovery.parse(source).toSeq.flatMap(defs(_, Nil))

  private def defs(tree: Tree, prefix: Seq[String]): Seq[Def] = {
    def inside(name: String, templ: Template) = templ.body.stats.flatMap(defs(_, prefix :+ name))
    def define(name: String, templ: Template) =
      Def(name, (prefix :+ name).mkString("."), TestDiscovery.parents(templ), TestDiscovery.setsSubject(templ)) +:
        inside(name, templ)
    tree match {
      case s: Source      => s.stats.flatMap(defs(_, prefix))
      case p: Pkg         => p.body.stats.flatMap(defs(_, prefix :+ p.ref.syntax))
      case o: Pkg.Object  => inside(o.name.value, o.templ)
      case o: Defn.Object => inside(o.name.value, o.templ)
      case c: Defn.Class  => define(c.name.value, c.templ)
      case t: Defn.Trait  => define(t.name.value, t.templ)
      case _              => Nil
    }
  }

  /** `start` plus every definition with a parent (by simple name) in it or
    * in `names`, transitively; their simple and full names. */
  private def closure(all: Seq[Def], start: Set[Def], names0: Set[String]): Set[String] = {
    var found = start
    var names = names0 ++ start.map(_.simple)
    var more  = true
    while (more) {
      val next = all.filter(d => !found(d) && d.parents.exists(p => names(TestDiscovery.simpleName(p))))
      found ++= next
      names ++= next.map(_.simple)
      more = next.nonEmpty
    }
    found.flatMap(d => Set(d.simple, d.fq))
  }
}
