package socless.lsp

import scala.util.Try

/** The ChiselSim tests of a workspace (`socless/tests`): every `.scala` file
  * under `root` that would get lenses (the same files as [[SimBaseIndex]], in
  * a Mill project, the same [[TestDiscovery]] rules with the index's base
  * traits). Parse-only, never Mill.
  *
  * A file is re-parsed only when its mtime or the index's base sets changed.
  * Files are read from disk: the result follows saves, not unsaved edits.
  * Thread-safe (one lock); the server calls it on its parser thread.
  */
final class WorkspaceTests(root: os.Path, index: SimBaseIndex) {
  private type Bases = (Set[String], Set[String])
  private var cache = Map.empty[os.Path, (Long, Bases, Seq[TestCase])] // guarded by this

  /** (suite FQCN, file, its tests in source order), sorted by suite then file;
    * suites without a test are left out. */
  def suites(): Seq[(String, os.Path, Seq[TestCase])] = synchronized {
    val bases = index.both()
    cache = SimBaseIndex.scalaFiles(root).flatMap { f =>
      Try(os.mtime(f)).toOption.map { mtime =>
        f -> cache.get(f).filter(c => c._1 == mtime && c._2 == bases).getOrElse {
          val found =
            if (MillBridge.findRoot(f).isEmpty) Nil
            else Try(TestDiscovery.find(os.read(f), bases._1, bases._2)).getOrElse(Nil)
          (mtime, bases, found)
        }
      }
    }.toMap
    cache.toSeq.flatMap { case (f, (_, _, found)) =>
      found.groupBy(_.suite).toSeq.map { case (suite, cases) => (suite, f, cases.sortBy(_.line)) }
    }.sortBy { case (suite, f, _) => (suite, f.toString) }
  }
}
