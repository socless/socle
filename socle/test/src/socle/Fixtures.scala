package socle

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

/** The committed example fixtures, shared by GoldenDesignTest,
  * LayoutStructureGoldenTest and GoldenWriter so adding an example design only
  * needs one edit instead of three (issue #135). Each entry is
  * (CHIRRTL resource filename, golden basename).
  */
object Fixtures {
  val all: Seq[(String, String)] = Seq(
    "axisystem.fir"    -> "axisystem",
    "gcd.fir"          -> "gcd",
    "rvpipeline.fir"   -> "rvpipeline",
    "deepsoc.fir"      -> "deepsoc",
    "interconnect.fir" -> "interconnect",
    "regheavy.fir"     -> "regheavy",
    "fsm.fir"          -> "fsm",
    "layers.fir"       -> "layers",
  )

  /** The repo root: `socle/test/resources` (holding /chirrtl) is three levels below it.
    * Fixture markers (`example/src/...`) resolve against it. */
  def repoRoot: os.Path = {
    val url = getClass.getResource("/chirrtl")
    require(url != null, "classpath resource /chirrtl not found")
    os.Path(new java.io.File(url.toURI)) / os.up / os.up / os.up / os.up
  }

  /** Source lines of the example sources the fixtures' markers point at. */
  def lines: SourceLines = SourceLines.fromRoot(repoRoot)
}
