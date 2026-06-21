package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import chisel3.RawModule
import _root_.circt.stage.ChiselStage
import upickle.default._
import os.Path
import scala.collection.mutable

/** Elaborate a Chisel design and emit a SoCLess-format `design.json`.
  *
  * Implementation: we go through Chisel's public CHIRRTL emission path
  * (`ChiselStage.emitCHIRRTL`) rather than reaching into
  * `chisel3.internal.*` - those internals are `private[chisel3]` since
  * Chisel 7 and shift between patch versions. CHIRRTL text preserves Bundle
  * types intact (lowering happens later, in firtool), so we still get the
  * bundle-aware view we want, with stable API surface.
  *
  * Usage:
  * {{{
  *   SoclessExporter.export(
  *     gen        = () => new AXISystem,
  *     outputPath = os.pwd / "design.json",
  *     sourceRoot = Some(os.pwd),    // for embedding source text
  *   )
  * }}}
  */
object SoclessExporter {

  def export[T <: RawModule](
    gen:        () => T,
    outputPath: Path,
    sourceRoot: Option[Path] = None,
    indent:     Int          = 2,
  ): Design = {
    val chirrtl   = elaborate(gen)
    val parsed    = ChirrtlParser.parse(chirrtl)
    val core      = IrWalk.design(parsed)
    val sources   = sourceRoot.map(root => collectSources(core, root)).getOrElse(Map.empty[String, String])
    val design    = Layout.withLayouts(core.copy(
      sources    = sources,
      // Absolute root for open-in-editor links (issue #123).
      sourceRoot = sourceRoot.map(_.toString).getOrElse(""),
    ))
    val json      = upickle.default.write(design, indent = indent)
    writeAtomic(outputPath, json)
    val sourceTxt = if (sources.isEmpty) "" else s", ${sources.size} sources"
    val warnTxt   = if (design.warnings.isEmpty) "" else s", ${design.warnings.map(_.count).sum} warnings"
    Console.err.println(
      s"[socless] wrote $outputPath - " +
      s"${design.modules.size} modules, ${design.bundles.size} bundles, ${design.layouts.size} layouts$sourceTxt$warnTxt"
    )
    logWarnings(design)
    design
  }

  /** Echo each structured warning to stderr at conversion time. This is the
    * single place warnings are printed: the parser/IR-walk only accumulate them
    * into `Design.warnings` (which also flows to design.json and the UI banner),
    * so every channel agrees and nothing is printed twice. */
  private def logWarnings(design: Design): Unit =
    design.warnings.foreach(w =>
      Console.err.println(s"[socless] [${w.kind}] ${w.message}${if (w.module.nonEmpty) s" (${w.module})" else ""}")
    )

  /** Convert a CHIRRTL text string to a SoCLess `design.json` file.
    *
    * Unlike [[export]], this path does not require Chisel or a user class on
    * the classpath: it feeds the CHIRRTL text directly into the parser /
    * IrWalk / Layout pipeline.  Useful for processing `.fir` files that were
    * captured separately (e.g. via `--chirrtl-out` or from third-party tools).
    *
    * {{{
    *   SoclessExporter.fromChirrtl(
    *     chirrtl    = os.read(os.Path("/path/to/design.fir")),
    *     outputPath = os.pwd / "design.json",
    *   )
    * }}}
    */
  /** Parse CHIRRTL text into a laid-out [[Design]] with no file I/O. Used by
    * [[fromChirrtl]] and by the standalone server's in-process `/convert`
    * (issue #115).
    *
    * When `sourceRoot` is given, the `@[file …]` markers in the CHIRRTL are
    * resolved against it and the source text is embedded, exactly like the
    * Chisel [[export]] path - so a design served from a `.fir` can show source
    * code too (issue #154). Absolute marker paths are read directly; relative
    * ones resolve under `sourceRoot`. */
  def designFromChirrtl(chirrtl: String, sourceRoot: Option[Path] = None): Design = {
    val core    = IrWalk.design(ChirrtlParser.parse(chirrtl))
    val sources = sourceRoot.map(root => collectSources(core, root)).getOrElse(Map.empty[String, String])
    Layout.withLayouts(core.copy(
      sources    = sources,
      sourceRoot = sourceRoot.map(_.toString).getOrElse(""),
    ))
  }

  /** Convert CHIRRTL text to a `design.json` string (no file I/O), for the
    * standalone server (issue #115). `sourceRoot` embeds source text (#154). */
  def designJson(chirrtl: String, sourceRoot: Option[Path] = None, indent: Int = 2): String =
    upickle.default.write(designFromChirrtl(chirrtl, sourceRoot), indent = indent)

  def fromChirrtl(
    chirrtl:    String,
    outputPath: Path,
    sourceRoot: Option[Path] = None,
    indent:     Int          = 2,
  ): Design = {
    val design = designFromChirrtl(chirrtl, sourceRoot)
    val json   = upickle.default.write(design, indent = indent)
    writeAtomic(outputPath, json)
    val sourceTxt = if (design.sources.isEmpty) "" else s", ${design.sources.size} sources"
    val warnTxt   = if (design.warnings.isEmpty) "" else s", ${design.warnings.map(_.count).sum} warnings"
    Console.err.println(
      s"[socless] wrote $outputPath - " +
      s"${design.modules.size} modules, ${design.bundles.size} bundles, ${design.layouts.size} layouts$sourceTxt$warnTxt"
    )
    logWarnings(design)
    design
  }

  /** Gather all unique source.file paths referenced anywhere in the design
    * and read them from the filesystem (relative to `sourceRoot`). The
    * resulting Map is the UI's lookup table - it slices out the relevant
    * lines per module on display.
    *
    * Unreadable / missing files are skipped silently with a stderr note;
    * we never abort the export because of a stale source path.
    */
  /** Write JSON atomically: a same-directory temp file then an atomic rename,
    * so a consumer reading the file mid-write (the UI's live watch polling
    * design.json, #120) never sees a truncated document. */
  private def writeAtomic(path: Path, json: String): Unit = {
    os.makeDir.all(path / os.up)
    val tmp = path / os.up / s".${path.last}.tmp"
    os.write.over(tmp, json)
    os.move.over(tmp, path) // rename is atomic on the same filesystem
  }

  private def collectSources(d: Design, sourceRoot: Path): Map[String, String] = {
    val files = mutable.LinkedHashSet.empty[String]
    def add(s: SourceRef): Unit =
      if (s.file != null && s.file.nonEmpty) files += s.file

    for (m <- d.modules) {
      add(m.source)
      m.ports.foreach(p => add(p.source))
      m.children.foreach(c => add(c.source))
      m.wires.foreach(w => add(w.source))
    }

    val out = mutable.LinkedHashMap.empty[String, String]
    var missing = 0
    for (rel <- files) {
      // Source paths may be absolute (Chisel sometimes attributes to absolute
      // paths) or relative to the build root. Try both.
      val candidates = Seq(
        if (rel.startsWith("/")) Some(Path(rel)) else None,
        if (!rel.startsWith("/")) Some(sourceRoot / os.RelPath(rel)) else None,
      ).flatten
      val read = candidates.iterator.flatMap { p =>
        try if (os.exists(p) && os.isFile(p)) Some(os.read(p)) else None
        catch { case _: Throwable => None }
      }.toSeq.headOption
      read match {
        case Some(text) => out(rel) = text
        case None       => missing += 1
      }
    }
    if (missing > 0)
      Console.err.println(s"[socless] $missing source file(s) could not be read from $sourceRoot")
    out.toMap
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
