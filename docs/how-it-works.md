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

## View-mode probe: run contract

`socless-probe` is a Java agent that captures the design an unmodified ChiselSim
scalatest test elaborates, without changing the test or its build. A caller runs
one test in its own JVM:

```bash
java -javaagent:<socless-probe agent jar> \
  -Dsocless.probe.mode=view -Dsocless.probe.out=<captured.json> \
  -cp <the test classpath> \
  org.scalatest.tools.Runner -s <Suite> -t "<full test name>"
```

- **Status.** `<captured.json>` holds a `CapturedModel` whose `status` is one of:
  - `ok`: a design was captured (`problems` lists anything the probe could not
    read; `modules` may then be empty);
  - `no-design`: the test never elaborated a design;
  - `elaboration-error`: the user's elaboration threw (`error` has the root
    cause's message).
- **ViewOnly.** In view mode the probe throws `socless.probe.ViewOnly` right
  after the capture, so firtool and Verilator never run. Scalatest therefore
  reports the test as FAILED: trust only `status`, never the test outcome or the
  JVM exit code.
- **First elaboration wins.** The first elaboration of any kind is captured
  (including an `emitCHIRRTL` call or a failure the test intercepts).
- **Missing file.** A missing output file means the probe itself failed. A
  failure of the probe never becomes `elaboration-error` and never fails a run
  that is not in view mode.
- **Wiring.** When every module's `wires` is empty and `chirrtl` is non-empty,
  derive the wiring from the CHIRRTL with `socless.capture.WiresFromChirrtl`
  (what `ModelToDesign` does).
- **Versions.** The agent is compiled against Scala 2.13 and Chisel 7.16 only;
  it uses the test JVM's own Scala and Chisel, so other versions are unsupported.
  `chiselVersion` records the Chisel the test ran.

`./mill example.soclessView <Suite> "<test>"` runs this contract on the bundled
examples, adapts the capture and serves it in the UI. The `socless-lsp` language
server runs the same contract from an editor (a View design lens per test, a
live panel, refresh on save): see [SoCLess in Neovim](ide-neovim.md).

## Compatibility notes

- **Chisel** - example elaboration runs against 7.16.0, but the parser is
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
