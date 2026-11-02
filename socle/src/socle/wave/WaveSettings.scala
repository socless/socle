package socle.wave

import upickle.default.{ReadWriter, macroRW}

/** The wave viewer settings that shape the signal list and its following
  * (`socle.waves*`). Shared by the language server and the Scala.js UI (the
  * UI reads them from `/ide.json`), so it stays free of JVM-only code. */
case class WaveSettings(
    followSelection: Boolean = true,
    showClockReset: Boolean = false,
    foldBundles: Boolean = false,
    showWires: Boolean = true,
    colorByDirection: Boolean = true,
    followDelayMs: Int = 150
)
object WaveSettings { implicit val rw: ReadWriter[WaveSettings] = macroRW }
