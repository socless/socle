package axisystem

import socle.SocleExporter
import os._

/** Entrypoint: elaborate AXISystem and write a Socle design.json.
  *
  * Mill driver:  mill example.socle
  * Direct:       mill example.runMain axisystem.Main -- --output design.json
  */
object Main {
  def main(args: Array[String]): Unit = {
    val outIdx = args.indexOf("--output")
    val outPath: Path =
      if (outIdx >= 0 && outIdx + 1 < args.length) Path(args(outIdx + 1), os.pwd)
      else os.pwd / "design.json"

    SocleExporter.export(
      gen        = () => new AXISystem,
      outputPath = outPath,
    )
  }
}
