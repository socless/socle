# How it works

## The pipeline

1. The exporter elaborates your design with Chisel's public
   `circt.stage.ChiselStage.emitCHIRRTL` to get the design as **CHIRRTL** text -
   bundles still aggregate at this level, which is what makes it the right
   surface to extract topology from.
2. `ChirrtlParser` turns the text into a typed AST.
3. `IrWalk` translates the AST into the `Design` schema: it dedups bundles by
   structural shape, coalesces ground-typed `connect` statements back into
   bundle-level wires, and extracts intra-module logic (muxes, primops,
   registers, constants, memories) as first-class nodes.
4. The `Design` is written as `design.json`.
5. The UI loads `design.json` and renders it.

No `chisel3.internal.*` imports anywhere, so the exporter survives Chisel patch
updates. When driven by the Mill plugin, the `socless` task forks `java` with
your module's exact `runClasspath` and invokes `socless.cli.Launcher`, which
reflection-resolves your top and hands it to `SoclessExporter.export`.

## The UI

The viewer is a single self-contained HTML file: the **Scala.js + Laminar**
frontend (`socless-ui`), built by `mill socless-ui.dist` with elkjs and the
fonts vendored inline, so it has **no network dependencies** and works offline.
It parses `design.json` into the typed `Design` and runs the schematic layout
in-process (elkjs), off the main thread in a Web Worker so large designs stay
responsive. There is no committed UI artifact and no Node build - the single
file is produced by the `dist` task and embedded in the runtime JAR.

For the per-file module map and the key design decisions (CHIRRTL extraction
surface, bundle dedup, wire coalescing, the two ELK engines, the schema
contract), see [CLAUDE.md](../CLAUDE.md).

## Compatibility notes

- **Chisel** - example elaboration runs against 7.11.0, but the parser is
  written for cross-version portability and a committed fixture matrix proves it:
  `VersionMatrixTest` parses the *same* design emitted in three syntax eras
  (SFC/legacy `lhs <= rhs` + `reg ... with`, early-CIRCT `connect` + `regreset`
  without `public module`, and modern `public module` + `layer`) and asserts all
  three yield an identical topology. So the legacy `<=` form, the
  `reg ... with : (reset => ...)` reset, and the `public module` / layer syntax
  are each held by CI. If you use an older Chisel and hit a parse gap, please open
  an issue with the `.fir` (capture it with `Launcher --chirrtl-out`) - it becomes
  a new row in the matrix.
- **Mill** - tested on 1.1.6 (use `>= 1.1, <= 1.1.6`). If
  `mvn"com.lihaoyi::mill-libs:1.1.6"` doesn't resolve in your environment, swap to
  the specific submodule (e.g. `mill-scalalib`) - the trait only uses
  `ScalaModule` + `Dep`.
- **`gates`** is `None` in the emitted JSON - elaboration alone can't know that.
  Populate it from your synth output (yosys stat JSON, etc.).
