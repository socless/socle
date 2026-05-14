package socless

import chisel3.RawModule
import _root_.circt.stage.ChiselStage
import upickle.default._
import os.Path

/** Elaborate a Chisel design and emit a SoCLess-format `design.json`.
  *
  * Implementation: we go through Chisel's public CHIRRTL emission path
  * (`ChiselStage.emitCHIRRTL`) rather than reaching into
  * `chisel3.internal.*` — those internals are `private[chisel3]` since
  * Chisel 7 and shift between patch versions. CHIRRTL text preserves Bundle
  * types intact (lowering happens later, in firtool), so we still get the
  * bundle-aware view we want, with stable API surface.
  *
  * Usage:
  * {{{
  *   SoclessExporter.export(
  *     gen        = () => new AXISystem,
  *     outputPath = os.pwd / "design.json",
  *   )
  * }}}
  */
object SoclessExporter {

  def export[T <: RawModule](
    gen:        () => T,
    outputPath: Path,
    indent:     Int = 2,
  ): Design = {
    val chirrtl = elaborate(gen)
    val parsed  = ChirrtlParser.parse(chirrtl)
    val design  = IrWalk.design(parsed)
    val json    = upickle.default.write(design, indent = indent)
    os.write.over(outputPath, json, createFolders = true)
    Console.err.println(
      s"[socless] wrote $outputPath — " +
      s"${design.modules.size} modules, ${design.bundles.size} bundles"
    )
    design
  }

  /** Emit CHIRRTL text. The `--target` flag selects the format that
    * preserves bundle types (vs `--target chirrtl-lowered` or `firrtl`).
    *
    * `ChiselStage.emitCHIRRTL(gen())` is the supported one-shot helper; it
    * runs Chisel elaboration and returns the textual CHIRRTL without
    * invoking firtool / writing files.
    */
  private def elaborate[T <: RawModule](gen: () => T): String = {
    ChiselStage.emitCHIRRTL(gen(), args = Array("--chisel-output-file", (os.temp.dir() / "example.fir").toString))
  }
}
