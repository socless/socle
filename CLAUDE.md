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

# Run bundled examples (no publishLocal needed)
mill example.soclessRun          # AXISystem design
mill example.soclessRunGcd       # GCD module
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill example.soclessRunDeepSoc   # Deep hierarchy (DeepSoc)
mill example.soclessRunIoBundles # Vec-of-bundle ports (Interconnect)
mill example.soclessRunRegHeavy  # Register-heavy design (Datapath)
mill show example.soclessRun     # Print path to emitted design.json

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
  Schema.scala           # JSON case classes — the contract with the UI (Design, ModuleDef, ...)
  SoclessExporter.scala  # Public entry point: SoclessExporter.export(gen, path, sourceRoot?)
  ChirrtlParser.scala    # CHIRRTL text → typed AST (GroundT, BundleT, VecT, Port, Inst, Conn)
  IrWalk.scala           # AST → Design; bundle deduplication & wire coalescing
  Launcher.scala         # Reflection-based main; CLI entry invoked by the Mill plugin

mill-socless/src/socless/mill/
  SoclessModule.scala    # Trait users mix into ScalaModule; defines `socless` and `soclessUI` tasks

example/src/
  gcd/                   # Single-module, Decoupled I/O (simplest case)
  rvpipeline/            # Multi-module, mixed bundle types
  axisystem/             # 2-master, 2x2 crossbar, 2-slave AXI4 interconnect (most complex)
  deephier/              # Deep hierarchy: DeepSoc > Cluster > Vec[Core] > (ALU, RegFile)
  iobundles/             # Vec-of-bundle ports and nested/mixed-direction bundles: Interconnect
  regheavy/              # Register-heavy design: Datapath

socless-js/src/socless/js/
  BrowserLayout.scala    # Scala.js elkjs facade; runs the schematic layout in-browser (in-process to the UI)

socless-ui/                # Scala.js + Laminar frontend - owns all its UI assets
  src/socless/ui/
    App.scala              # frontend root: design load, hierarchy, state, keyboard, chrome
    Schematic.scala        # center pane: boxes/ports/wires, pan/zoom/drill, expansion, filter, minimap, export
    Inspector.scala        # right pane: module/instance/wire-bundle detail
    BottomPanel.scala      # Source / Waveform / Ports tabs
    Chrome.scala           # command palette, keyboard-help overlay, warnings banner
    Export.scala           # SVG/PNG export + self-contained HTML snapshot
    Tokens.scala / RowModel.scala  # theme tokens; expansion row model (shared BundleTree)
  html/SoCLess.src.html    # template the `dist` task inlines into the single-file UI
  vendor/elk.bundled.js    # vendored elkjs runtime, inlined offline
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

## Schema Contract

`Schema.scala` case classes are the JSON contract with the `socless-ui` frontend, which reads `design.json` into the typed `Design` via the shared upickle ReadWriters. Adding fields must be backward-compatible (use a trailing default so older `design.json` still parses). Never remove or rename existing fields without updating the UI.

## Dependencies

| Module | Scala | Key deps |
|--------|-------|----------|
| `socless` | 2.13.16 | Chisel 7.11.0, upickle 4.0.2, os-lib 0.11.4 |
| `socless-js` | 2.13.16 (Scala.js 1.19) | upickle; elkjs (`window.ELK`) at runtime |
| `socless-ui` | 2.13.16 (Scala.js 1.19) | Laminar 17.2.0; depends on `socless-js` |
| `mill-socless` | 3.8.2 | Mill 1.1.6 APIs |
