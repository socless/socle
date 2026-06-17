package socless

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
  )
}
