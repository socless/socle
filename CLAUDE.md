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

# Run bundled examples (no publishLocal needed)
mill example.soclessRun          # AXISystem design
mill example.soclessRunGcd       # GCD module
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill show example.soclessRun     # Print path to emitted design.json

# In user projects after publishing
mill myDesign.socless             # Elaborate + emit design.json
mill myDesign.soclessUI           # Serve UI locally (default port 8765)
mill myDesign.soclessUI --port 9000
```

There is no dedicated test suite. Validation is done by elaborating examples and inspecting the emitted `design.json` against `ui/examples/` reference JSONs.

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
  axisystem/             # 2-master, 2×2 crossbar, 2-slave AXI4 interconnect (most complex)

ui/
  SoCLess.html           # Single-file bundled web UI; embedded in socless JAR at build time
  examples/              # Reference JSONs: axi.json, gcd.json, pipeline.json, vta.json
```

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

`Schema.scala` case classes are the JSON contract with `ui/SoCLess.html`. Adding fields must be backward-compatible (UI must tolerate `null`/missing for new fields). Never remove or rename existing fields without updating the UI.

## Dependencies

| Module | Scala | Key deps |
|--------|-------|----------|
| `socless` | 2.13.16 | Chisel 7.11.0, upickle 4.0.2, os-lib 0.11.4 |
| `mill-socless` | 3.8.2 | Mill 1.1.6 APIs |
