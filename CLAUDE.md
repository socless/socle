# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Does

**SoCLess** is a Mill plugin for Chisel that exports elaborated hardware designs to a JSON schema consumed by an interactive single-file web UI. It bridges Chisel elaboration and visualization.

Pipeline: Chisel design → `ChiselStage.emitCHIRRTL` → CHIRRTL text → typed AST → bundle deduplication + wire coalescing → `design.json` → UI.

## Build Commands (Mill 1.1.6)

```bash
# Publish to local Maven cache (prerequisite for user projects)
mill socless.publishLocal        # Scala 2.13 runtime library
mill mill-socless.publishLocal   # Scala 3 Mill plugin

# Run tests
./mill socless.test               # utest suite (LayoutTest, ChirrtlParser, PipelineRobustness, GoldenDesign, ...)
./mill socless.regenerateGoldens  # Regenerate golden JSON snapshots after intentional pipeline changes
# (alternative) SOCLESS_REGENERATE=1 ./mill socless.test socless.GoldenDesignTest
./mill socless-probe.test         # view-mode probe agent (forks ChiselSim tests with the agent; no Verilator)
./mill socless-lsp.test           # language server (in-process LSP, live panel HTTP, stdio smoke test of the assembly)
./mill socless-vscode.test        # VS Code extension logic (Scala.js, runs on Node)
./mill example.test               # the example ChiselSim tests (needs Verilator on PATH)
tools/ci/selective.sh prepare <base-sha>   # PR CI: snapshot the base (checks it out and back: commit first), then
tools/ci/selective.sh run socless.test     # run only if its inputs changed (`selected <sel>` lists what would run)

# Run bundled examples (no publishLocal needed)
mill example.soclessRun          # AXISystem design
mill example.soclessRunGcd       # GCD module
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill example.soclessRunDeepSoc   # Deep hierarchy (DeepSoc)
mill example.soclessRunIoBundles # Vec-of-bundle ports (Interconnect)
mill example.soclessRunRegHeavy  # Register-heavy design (Datapath)
mill example.soclessRunFsm       # TrafficFsm (switch/is FSM)
mill example.soclessRunLayers    # LayeredCounter (verification layers)
mill example.soclessRunScatter   # ScatterDemo (bundle direction split)
mill example.soclessRunBigArray  # BigArray (16x16 stages: the UI smoke's performance guard)
./mill example.soclessCapturedDeepSoc  # committed DeepSoc capture -> design.json (fold groups, no source index)
./mill example.soclessCapturedFsm      # committed TrafficFsm capture -> design.json with its state diagram (FromCapture)
./mill example.soclessCapturedGcd      # committed GCD capture -> design.json with its signals (embedded.mjs Waveform check)
./mill example.soclessView gcd.GcdSpec "GCD computes gcd(48, 18)"  # view a ChiselSim test's design (probe, no simulation)
mill show example.soclessRun     # Print path to emitted design.json

# Chisel IDE: language server jar for editors (java -jar, probe agent inside); setup in docs/ide-neovim.md
./mill socless-lsp.assembly       # -> out/socless-lsp/assembly.dest/out.jar
# VS Code extension (docs/ide-vscode.md): vsix bundles the server jar
./mill socless-vscode.vsix        # -> out/socless-vscode/vsix.dest/socless-<version>.vsix (needs node/npm)
./mill socless-vscode.stage       # extension dir for the e2e
socless-vscode/e2e/run.sh out/socless-vscode/stage.dest  # headless VS Code e2e; NOT a Mill command (it would contend on the out/ lock); never run it during another ./mill

# Inspect baked layout geometry headlessly (no UI)
mill example.soclessDebugLayout          # dump default design's per-module SVG + text
mill myDesign.soclessDebugLayout         # in a user project
./mill socless.runMain socless.cli.Launcher debug-layout design.json --out dump   # any design.json

# In user projects after publishing
mill myDesign.socless             # Elaborate + emit design.json
mill myDesign.soclessUI           # Serve UI locally (default port 8765)
mill myDesign.soclessUI --port 9000
```

Tests live in `socless/test/src/socless/` and run with `./mill socless.test`. The suite includes LayoutTest, ElkSmokeTest, EndpointTest, HarnessTest, LayoutPolishTest, PipelineRobustnessTest, SchemaLayoutTest, WithLayoutsTest, and GoldenDesignTest (utest framework). Additional validation can be done by elaborating examples and inspecting the emitted `design.json` against the reference JSONs in `socless/test/resources/reference/`.

**Golden/snapshot tests**: `GoldenDesignTest` compares the IrWalk-pipeline output (parse+design, no layout) against committed golden files in `socless/test/resources/golden/`. When you intentionally change the emitted schema, regenerate the goldens with `./mill socless.regenerateGoldens`, then `git diff` to review and commit.

## Module Architecture

```
socless/src/socless/
  SoclessExporter.scala  # Public entry point: SoclessExporter.export(gen, path, sourceRoot?), fromCaptured
  schema/                # Schema.scala: JSON case classes, the contract with the UI (Design, ModuleDef, ...);
                         # BundleTree.scala (shared with the UI)
  parse/ChirrtlParser.scala  # CHIRRTL text → typed AST (GroundT, BundleT, VecT, Port, Inst, Conn)
  ir/                    # IrWalk (AST → Design; bundle dedup & wire coalescing), NetTrace, WhenMux,
                         # FsmDetect, MemNodes, Synthetic (logic-node extraction)
  cli/                   # Launcher (reflection-based main, CLI entry invoked by the Mill plugin;
                         # from-capture, debug-layout, serve), Serve (standalone UI server)
  layout/                # LayoutCore (pure, shared with socless-js), Layout (JVM ELK bake),
                         # LayoutDebug (headless dump), InstanceFold (folds generator copies; shared)
  capture/               # View-mode capture: CapturedModel (shared with socless-probe),
                         # ModelToDesign (capture -> Design), WiresFromChirrtl (wiring fallback),
                         # LogicGroups (which logic nodes form one block), LogicBlocks (collapse
                         # groups into boxes with pins), SourceIndex (trait: source position ->
                         # generator construct, FSM diagram; implemented in socless-lsp)

socless-probe/             # Java agent: captures a ChiselSim test's elaborated design (view mode)
  src/socless/probe/     # Agent (premain), CaptureAdvice (Byte Buddy hooks), Capture, ModelWalk
  fixtures/src/probefix/ # NoSimulateSpec, BrokenSpec: compiled fixtures for AgentRunTest

socless-lsp/src/socless/lsp/  # Language server (stdio): View design / Simulate lenses + live panel
  Main.scala             # entry point; stdout is the JSON-RPC channel (prints go to stderr)
  SoclessLanguageServer.scala  # lenses, socless.viewDesign, save refresh, socless/cursor, panel-to-editor
  SoclessClient.scala    # client-side notifications (socless/openPanel, ...)
  TestDiscovery.scala    # scalameta: ChiselSim test cases, enclosing class at a line
  SimBaseIndex.scala     # workspace classes/traits whose extends chain reaches ChiselSim
  WorkspaceTests.scala   # every ChiselSim test of the workspace (socless/tests), parse-only
  MillBridge.scala       # asks ./mill for a source file's test classpath and fork settings
  ViewRunner.scala       # forks the test with the probe agent, adapts the capture to a Design
  SimRunner.scala        # Simulate: runs the test with waves, opens the trace in Surfer
  ProcessTree.scala      # bounded waits on forked processes (kills the whole tree)
  RunQueue.scala         # one view run at a time; newer request replaces a queued one
  LiveHttp.scala         # 127.0.0.1 panel: UI, /design.json, /ide.json, POST /open
  ScalaSourceIndex.scala # scalameta SourceIndex: generator constructs, FSM state diagrams
  FsmFacts.scala         # switch(register) state machines, syntax only (for ScalaSourceIndex.fsm)
  FromCapture.scala      # CLI: capture -> design.json with the source index (soclessCapturedFsm)

socless-vscode/            # VS Code extension (Scala.js, CommonJS; docs/ide-vscode.md)
  src/socless/vscode/    # Extension (activate), Panel (webview), Logic (pure, unit-tested),
                         # Sidebar (Tests/Hierarchy/Details views), SidebarModel (pure, unit-tested),
                         # facade/ (vscode, languageclient, node)
  package.json           # manifest template (@VERSION@ placeholders), rendered by Mill
  e2e/                   # index.js (runs inside VS Code), webview-click.mjs (CDP), run.sh (headless launcher)

mill-socless/src/socless/mill/
  SoclessModule.scala    # Trait users mix into ScalaModule; defines `socless` and `soclessUI` tasks

example/src/
  gcd/                   # Single-module, Decoupled I/O (simplest case)
  rvpipeline/            # Multi-module, mixed bundle types
  axisystem/             # 2-master, 2x2 crossbar, 2-slave AXI4 interconnect (most complex)
  deephier/              # Deep hierarchy: DeepSoc > Cluster > Vec[Core] > (ALU, RegFile)
  iobundles/             # Vec-of-bundle ports and nested/mixed-direction bundles: Interconnect
  regheavy/              # Register-heavy design: Datapath
  fsm/ layers/ scatter/  # TrafficFsm, LayeredCounter (verification layers), ScatterDemo
  bigarray/              # BigArray: 256 boxes in one module (performance guard)

socless-js/src/socless/js/
  BrowserLayout.scala    # Scala.js elkjs facade; runs the schematic layout in-browser (in-process to the UI)

socless-ui/                # Scala.js + Laminar frontend - owns all its UI assets
  src/socless/ui/
    App.scala              # frontend root: design load, hierarchy, state, keyboard, chrome, fold state
    schematic/             # Schematic (center pane: boxes/ports/wires, pan/zoom/drill, expansion,
                           # filter, minimap), SchematicBoxes / SchematicChrome / SchematicGeom /
                           # SchematicColors / LogicSymbols, FsmDiagramView (an FSM block's state
                           # diagram), Export (SVG/PNG + self-contained HTML snapshot)
    panels/                # Inspector (right pane), BottomPanel (Source / Waveform / Ports), Chrome
                           # (command palette, keyboard-help overlay, warnings banner)
    model/                 # RowModel (expansion rows, shared BundleTree), Glyphs, LogicLabels, ScalaClassLabel
    data/                  # DesignLoader (load, live poll, drag-drop, picker), Embedded (?host=vscode), IdeLink
    wave/                  # SurferPane (embedded Surfer WASM over in-page WCP), WaveFollow (the
                           # waveform viewer follows the selection: panel Surfer or POST /wave/select)
    theme/Tokens.scala     # theme tokens
  html/SoCLess.src.html    # template the `dist` task inlines into the single-file UI
  vendor/                  # elk.bundled.js + elk-worker.min.js (vendored elkjs), inlined offline

tools/
  ci/selective.sh          # Mill selective execution for PR CI (ALL_TASKS: keep in sync with ci.yml)
  ui-smoke/                # headless UI smokes: smoke.mjs, serve-smoke.mjs, embedded.mjs (README inside)
  check-chirrtl-fixtures.sh, consume-test/  # CI: fixture drift check; plugin consumption smoke
```

The frontend is **Scala.js + Laminar** (`socless-ui`). The shipped single-file
UI is produced by the `socless-ui.dist` Mill task (link output + vendored elkjs
inlined into the template, offline) and embedded in the socless JAR as
`SoCLess.html` (epic #172). There is **no committed UI artifact and no Node
build** - just `./mill socless-ui.dist`. It parses `design.json` into the typed
`Design` and calls `socless-js` (`BrowserLayout.runTyped`) and
`socless.BundleTree` in-process.

## Key Design Decisions

**CHIRRTL as the extraction surface** — only `chisel3.stage.ChiselStage.emitCHIRRTL` is used; no `chisel3.internal.*` imports. CHIRRTL preserves bundle types before lowering, making it the right level to extract topology.

**Regex-based CHIRRTL parser** (`ChirrtlParser.scala`) — handles both legacy (`lhs <= rhs`) and modern (`connect lhs, rhs`) syntax for cross-version portability. Source markers (`@[file line:col]`) are parsed for optional source embedding.

**Bundle deduplication by structural hash** (`IrWalk.scala`) — bundles with identical field names/widths/directions collapse to one `BundleDef`. `Decoupled[T]` and AXI4 shapes are recognized by field-name sets and given friendly names.

**Wire coalescing** — ground-level connects sharing a `<inst>.<port>` prefix are grouped; the count becomes the wire label in the UI.

**Plugin task forks a JVM** (`SoclessModule.scala`) — `socless` task runs `Launcher` in a separate JVM with the user's exact run classpath, so the plugin doesn't need to share a classloader with user code.

**Launcher requires no-arg constructors** — parameterized Chisel modules need a thin wrapper class:
```scala
class MyTopForSocless extends MyTop(defaultParams)
```

**Two ELK engines, one shared core (deliberate, not duplication).** Layout has a
single pure, engine-independent core (`layout/LayoutCore.scala`, plus `Schema`,
`BundleTree`, `Synthetic`) that `build.mill` cross-compiles into BOTH the JVM
`socless` module and the Scala.js `socless-js` module (`sharedSources`), so the
graph prep cannot drift. Only the ELK runtime *binding* is duplicated: `Layout.scala`
(JVM, wraps Eclipse ELK Java) bakes per-module layouts into `design.json` at export
time, and `BrowserLayout.scala` (JS, wraps elkjs) re-lays-out in the browser. Both
exist on purpose: the baked JVM layout gives instant first paint, deterministic
golden snapshots (`LayoutStructureGoldenTest`), and headless `soclessDebugLayout`
(no browser); elkjs gives interactive re-layout for expansions / drill-in / Optimize
that the backend cannot precompute (issue #167). ELK versions are pinned together
(`V.elk` and the vendored `elk.bundled.js`). Keep the two engine bindings behaviorally
aligned when you touch either; consolidating to elkjs-only is possible but would trade
away first-paint and the JVM-side layout tests (see the perf plan, not yet scheduled).

## Schema Contract

`Schema.scala` case classes are the JSON contract with the `socless-ui` frontend, which reads `design.json` into the typed `Design` via the shared upickle ReadWriters. Adding fields must be backward-compatible (use a trailing default so older `design.json` still parses). Never remove or rename existing fields without updating the UI.

## Dependencies

| Module | Scala | Key deps |
|--------|-------|----------|
| `socless` | 2.13.16 | Chisel 7.16.0, upickle 4.0.2, os-lib 0.11.4 |
| `socless-js` | 2.13.16 (Scala.js 1.19) | upickle; elkjs (`window.ELK`) at runtime |
| `socless-ui` | 2.13.16 (Scala.js 1.19) | Laminar 17.2.0; depends on `socless-js` |
| `mill-socless` | 3.8.2 | Mill 1.1.6 APIs |
| `socless-lsp` | 2.13.16 | lsp4j 1.0.0, scalameta 4.17.4; depends on `socless` |
| `socless-probe` | 2.13.16 | Chisel 7.16.0 compile-only (the test JVM provides it); Byte Buddy 1.18.15 + upickle shaded under `socless.shaded` in the agent jar |
| `socless-vscode` | 2.13.16 (Scala.js 1.19) | vscode-languageclient 9.0.1 (npm, only into the vsix); vsce 3.2.1 |
