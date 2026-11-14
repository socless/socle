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
updates. When driven by the Mill plugin, the `socle` task forks `java` with
your module's exact `runClasspath` and invokes `socle.cli.Launcher`, which
reflection-resolves your top and hands it to `SocleExporter.export`.

## The UI

The viewer is a single self-contained HTML file: the **Scala.js + Laminar**
frontend (`socle-ui`), built by `mill socle-ui.dist` with elkjs and the
fonts vendored inline, so it has **no network dependencies** and works offline.
It parses `design.json` into the typed `Design` and runs the schematic layout
in-process (elkjs), off the main thread in a Web Worker so large designs stay
responsive. There is no committed UI artifact and no Node build - the single
file is produced by the `dist` task and embedded in the runtime JAR.

For the per-file module map and the key design decisions (CHIRRTL extraction
surface, bundle dedup, wire coalescing, the two ELK engines, the schema
contract), see [CLAUDE.md](../CLAUDE.md).

## View-mode probe: run contract

`socle-probe` is a Java agent that captures the design an unmodified ChiselSim
scalatest test elaborates, without changing the test or its build. To read
constructor arguments, the probe instruments module constructors in memory, at
run time, inside the forked view process only: sources, compiled classes on
disk and builds are untouched. A caller runs
one test in its own JVM:

```bash
java -javaagent:<socle-probe agent jar> \
  -Dsocle.probe.mode=view -Dsocle.probe.out=<captured.json> \
  -cp <the test classpath> \
  org.scalatest.tools.Runner -s <Suite> -t "<full test name>"
```

- **Status.** `<captured.json>` holds a `CapturedModel` whose `status` is one of:
  - `ok`: a design was captured (`problems` lists anything the probe could not
    read; `modules` may then be empty);
  - `no-design`: the test never elaborated a design (`chiselVersion` still
    names the Chisel on the test classpath, and the editor's message shows it);
  - `elaboration-error`: the user's elaboration threw (`error` has the root
    cause's message).
- **ViewOnly.** In view mode the probe throws `socle.probe.ViewOnly` right
  after the capture, so firtool and Verilator never run. Scalatest therefore
  reports the test as FAILED: trust only `status`, never the test outcome or the
  JVM exit code.
- **No Verilator needed.** View mode works on a machine without Verilator.
  ChiselSim looks Verilator up (`svsim.verilator.Backend$.initializeFromProcessEnvironment`)
  before it elaborates; in view mode only, the probe turns a failed lookup into a
  placeholder backend that is never used. Without view mode the lookup error
  surfaces as usual.
- **First elaboration wins.** The first elaboration of any kind is captured
  (including an `emitCHIRRTL` call or a failure the test intercepts).
- **Missing file.** A missing output file means the probe itself failed. A
  failure of the probe never becomes `elaboration-error` and never fails a run
  that is not in view mode.
- **Wiring.** When every module's `wires` is empty and `chirrtl` is non-empty,
  derive the wiring from the CHIRRTL with `socle.capture.WiresFromChirrtl`
  (what `ModelToDesign` does).
- **Versions.** The agent is compiled against Scala 2.13 and Chisel 7.16 only;
  it uses the test JVM's own Scala and Chisel, so other versions are unsupported.
  `chiselVersion` records the Chisel the test ran; when its major.minor is not
  the tested one (`CapturedModel.TestedChisel`, build.mill's `V.chisel`), the
  adapted design carries an `untested-chisel` warning naming both versions.

`./mill example.socleView <Suite> "<test>"` runs this contract on the bundled
examples, adapts the capture and serves it in the UI. The `socle-lsp` language
server runs the same contract from an editor (a View design lens per test, a
live panel, refresh on save): see [Socle in Neovim](ide-neovim.md),
[Socle in IntelliJ IDEA](ide-intellij.md) and
[Socle in VS Code](ide-vscode.md).

## The IDE view

A capture becomes a `Design` in `socle.capture.ModelToDesign.design`, which
takes an optional `SourceIndex` (the Scala source of the design). Per module:

1. **Fold groups.** Generator copies (`Seq.fill(n)(Module(...))`: instances
   sharing a CHIRRTL source marker, a Scala class and a base name, at least
   two) get a common `InstanceDef.foldGroup`.
2. **Logic grouping.** The module's logic comes from the CHIRRTL pipeline
   (`IrWalk`, via `WiresFromChirrtl`); `LogicGroups.assign` decides which nodes
   form one box: an FSM (its node and state register), a generator construct
   (a `for` loop, or a `foreach` / `map` / `tabulate`-style call, that the
   index maps the node's source position to), the remaining `logic`, and the
   same kinds again for layer nodes (Chisel layer content such as `assert`),
   which never mix with the others.
3. **Collapse.** `LogicBlocks.collapse` turns each group into one box node with
   pins and rewires the module; the group bodies go to `Design.regions`.
4. **FSM diagrams.** An FSM box carries its state diagram from the index's
   `fsm(scalaClass, register)`. Without it, the states come from the CHIRRTL
   case literals (`FsmDetect`) and a note says the transitions are unknown.
5. **Tie-offs.** A constant whose every wire ends on a port or an instance pin
   is dropped with its wires; when it is the pin's only driver it is recorded
   as a `TieOff`, which the UI draws as a value stub (no layout node).

`InstanceFold.fold` (shared with the browser build) then folds each group into
one box when the layout is baked (`Layout`) and in the UI, which keeps the
unfold state. The source index lives in the language server:
`socle.lsp.ScalaSourceIndex` (scalameta, parse-only) maps positions to
generator constructs, and `FsmFacts` reads `switch(register)` state machines,
with `when` conditions as transition labels and next-state wires followed. The
language server's view runs and `socle.lsp.FromCapture` use it;
`Launcher from-capture` (behind `./mill example.socleView`) has none, so it
shows no generator blocks and no transitions.

The classic CHIRRTL view (`IrWalk` from a `.fir` or the Mill plugin) is
unchanged: it never sets a fold group, and its logic stays as individual nodes.

## Compatibility notes

- **Chisel** - the runtime library and the Mill plugin need Chisel 7.16 or
  newer (the library depends on 7.16.0); the IDE's probe is built for 7.16
  only (see **Versions** above). Example elaboration runs against 7.16.0, but
  the parser is
  written for cross-version portability and a committed fixture matrix proves it:
  `VersionMatrixTest` parses the *same* design emitted in three syntax eras
  (SFC/legacy `lhs <= rhs` + `reg ... with`, early-CIRCT `connect` + `regreset`
  without `public module`, and modern `public module` + `layer`) and asserts all
  three yield an identical topology. So the legacy `<=` form, the
  `reg ... with : (reset => ...)` reset, and the `public module` / layer syntax
  are each held by CI. A statement the parser does not know (a newer FIRRTL
  keyword) or cannot read is skipped and counted in an `unrecognised-statement`
  or `unparsed-statement` warning that quotes it, so a gap shows in the
  warnings banner instead of as a silently missing wire. If you hit a parse gap,
  please open an issue with the `.fir` (capture it with `Launcher --chirrtl-out`) -
  it becomes a new row in the matrix.
- **Mill** - tested on 1.1.6 (use `>= 1.1, <= 1.1.6`). If
  `mvn"com.lihaoyi::mill-libs:1.1.6"` doesn't resolve in your environment, swap to
  the specific submodule (e.g. `mill-scalalib`) - the trait only uses
  `ScalaModule` + `Dep`.
- **`gates`** is `None` in the emitted JSON - elaboration alone can't know that.
  Populate it from your synth output (yosys stat JSON, etc.).
