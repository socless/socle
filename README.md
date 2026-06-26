# SoCLess

**An interactive UI for inspecting hardware designs.** SoCLess turns an
elaborated design into a navigable schematic, hierarchy, and inspector so a
hardware designer gets quick visual feedback on what their code actually built:
the module tree, the wires between instances, the intra-module logic (muxes,
adders, registers, memories), bundle structure, widths, and the source behind
each block.

Today it reads **Chisel / CHIRRTL**. The viewer is a single, self-contained HTML
file with no network dependencies, so a design is something you can open, share,
and explore offline.

There are two ways to use it, and **the standalone app and the Mill plugin are
equal front doors** to the same UI:

- **Standalone app** - point the bundled, Python-free server at any `.fir`
  (CHIRRTL) or `design.json`, from any build that can emit CHIRRTL (sbt, mill,
  raw Chisel, ...). See **[docs/usage-standalone.md](docs/usage-standalone.md)**.
- **Mill plugin** - the smoothest Chisel integration: one task elaborates your
  design and serves the UI. See **[docs/usage-mill.md](docs/usage-mill.md)**.

## Features

- **Navigate** the hierarchy tree or the schematic; double-click a box to drill
  into a submodule and breadcrumb back out (pan/zoom preserved per level).
- **Intra-module logic** as first-class nodes: muxes, primops/ALUs, registers,
  constants, FSMs, and memories are drawn and routed, not hidden.
- **Expand bundle ports** in place to see channels and fields; wires stay
  orthogonal and boxes move aside so nothing overlaps.
- **Inspect** any wire or module (types, widths, registers/memories, source
  location) and **jump to the source** in your editor (VS Code / IntelliJ).
- **Command palette** (Ctrl/Cmd-K): fuzzy search over modules, instances, and
  pins, plus actions (theme, panels, optimize); `?` lists keyboard shortcuts.
- **Filter** the schematic with a live match count; a **sortable, filterable
  ports** table; a **Source** tab showing the Chisel behind each module.
- **Themes**: nine cohesive palettes (including an on-brand Blueprint) and a
  density control, in a settings panel.
- **Export**: SVG / PNG of the current schematic, or a self-contained **HTML
  snapshot** with the design embedded - send it to a colleague and they open it
  from `file://` with full interactivity, no repo or build needed.

## Quick start

Browse one of the bundled demos without publishing anything:

```bash
mill example.soclessRun          # AXISystem (default); serves the UI
mill example.soclessRunGcd       # GCD
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill example.soclessRunDeepSoc   # deep hierarchy
mill example.soclessRunIoBundles # vec-of-bundle ports
mill example.soclessRunRegHeavy  # register-heavy design
```

Then point it at your own design via the **[standalone
app](docs/usage-standalone.md)** or the **[Mill plugin](docs/usage-mill.md)**.

## How it works

Chisel design -> `ChiselStage.emitCHIRRTL` -> CHIRRTL text -> typed AST -> bundle
dedup + wire coalescing + logic-node extraction -> `design.json` -> the Scala.js
UI. The exporter only uses Chisel's public CHIRRTL surface (no
`chisel3.internal.*`), so it survives Chisel patch updates. Details in
**[docs/how-it-works.md](docs/how-it-works.md)**; the per-file module map for
contributors is in [CLAUDE.md](CLAUDE.md).

## Roadmap

SoCLess is a hardware-inspection UI first; the Chisel/CHIRRTL exporter and the
Mill plugin are the first integrations. Planned directions:

- **Simulation frontend** - discover and run ChiselSim suites from the UI,
  ingest the waveforms, and cross-probe them against the schematic (epic
  [#127](https://github.com/e-ban/SoCless/issues/127)).
- **CIRCT multi-level IR** - inspect a design across lowering passes, not just
  at the CHIRRTL level.
- **Exports & teamwork** - richer shareable artifacts and integrations for
  presentation and collaboration (the self-contained HTML snapshot is the first
  step).

## Contributing & releases

- Build, test, and dev-loop details: [CONTRIBUTING.md](CONTRIBUTING.md).
- Versioning and the release procedure: [RELEASING.md](RELEASING.md).
- Architecture and design decisions for contributors: [CLAUDE.md](CLAUDE.md).
