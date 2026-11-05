package socle.ir

import scala.collection.mutable
import scala.util.Try

/** The user's Scala source lines a CHIRRTL marker `@[file line:col]` points at.
  * `CodeBlocks` reads them to tell a `when` the user wrote from one a library
  * made, and to label the block as written. `line` is 1-based; an unreadable
  * file or an out-of-range line gives `None`. */
trait SourceLines {
  def line(file: String, line: Int): Option[String]
}

object SourceLines {

  /** No source at all (export mode without a readable source tree). */
  val none: SourceLines = (_: String, _: Int) => None

  /** Files under `root`; a marker path is relative to `root` unless absolute.
    * Each file is read once (a missing one is remembered as missing). */
  def fromRoot(root: os.Path): SourceLines = new SourceLines {
    private val cache = mutable.Map.empty[String, Option[IndexedSeq[String]]]
    private def read(file: String): Option[IndexedSeq[String]] =
      cache.getOrElseUpdate(file, Try {
        val p = if (file.startsWith("/")) os.Path(file) else root / os.RelPath(file)
        os.read.lines(p).toIndexedSeq
      }.toOption)
    def line(file: String, line: Int): Option[String] =
      read(file).flatMap(ls => ls.lift(line - 1))
  }

  /** In-memory files, path -> text (for tests). */
  def of(files: Map[String, String]): SourceLines = {
    val split: Map[String, IndexedSeq[String]] =
      files.map { case (f, text) => f -> text.split("\n", -1).toIndexedSeq }
    (file: String, line: Int) => split.get(file).flatMap(_.lift(line - 1))
  }
}
