package axisystem

import socless.SoclessExporter
import os._

/** Entrypoint: elaborate AXISystem and write a SoCLess design.json.
  *
  * Mill driver:  mill example.socless
  * Direct:       mill example.runMain axisystem.Main -- --output design.json
  */
object Main {
  def main(args: Array[String]): Unit = {
    val outIdx = args.indexOf("--output")
    val outPath: Path =
      if (outIdx >= 0 && outIdx + 1 < args.length) Path(args(outIdx + 1), os.pwd)
      else os.pwd / "design.json"

    SoclessExporter.export(
      gen        = () => new AXISystem,
      outputPath = outPath,
    )
  }
}
