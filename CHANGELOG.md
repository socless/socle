# Changelog

Notable changes to SoCLess, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. SoCLess uses
[Semantic Versioning](https://semver.org/). Releases before 0.16.0 are listed on
the [GitHub releases](https://github.com/e-ban/SoCless/releases) and
[tags](https://github.com/e-ban/SoCless/tags) pages.

## [Unreleased]

### Added

- Waveforms that follow the schematic: after **Simulate**, the module selected
  in the panel decides which signals Surfer shows (its ports grouped by port,
  then its registers and wires, coloured by direction), and a smaller
  selection moves Surfer's focus. `socless.waveViewer` picks the Surfer app
  (driven over WCP, the default), Surfer embedded in the panel's Waveform tab
  (`surfer-panel`), or a `custom` command (`socless.waveViewerCommand`); six
  `socless.waves*` settings tune it. On Wayland the Surfer app is started
  under XWayland when available, so it keeps following while hidden.
- VS Code: with `surfer-panel`, Surfer has its own editor tab (`Surfer: <test>`)
  in the column after the SoCLess panel's, which drives it, so it never covers
  the schematic. A new trace opens it; it can be
  dragged or hidden without reloading, and a closed tab stays closed until the
  next new trace, the Waveform tab's **Show Surfer** button or the new command
  `SoCLess: Show waveforms`. Neovim and the browser keep Surfer in the
  Waveform tab.
- Simulate first views the test, so the panel shows the design whose waves it
  opens; the trace of a failing test is published too.
- The language server ships Surfer v0.7.0 as WebAssembly (EUPL-1.2, with its
  licence and source pointer) for the embedded viewer.

### Fixed

- An FSM built in a helper the module calls (for example an implicit class's
  method, as in std-vta-dev's `AxiLike` handlers) now gets its state diagram,
  found from its register's source position, instead of "could not be read".
- Simulate warns when the waveform viewer cannot start (for example `surfer`
  not on the editor's `PATH`): the outcome is a Warning even when the test
  passed, and the problem is logged, instead of an easily missed Info.
- The VS Code panel is no longer empty when VS Code runs on Windows with a WSL
  workspace: the server now binds an IPv4 socket, which WSL relays to Windows
  (Java's default IPv6 socket was relayed to `::1`, where nothing listened).

## [0.16.0] - 2026-10-26

### Added

- Chisel IDE: a **View design** code lens on every ChiselSim test shows the
  design that test elaborates in a live panel (no simulator needed), refreshed
  on save, following your cursor, with a click on a box opening its source. It
  comes from a view-mode probe agent and the `socless-lsp` language server,
  usable from Neovim or any LSP editor (#294).
- VS Code extension around the language server, with the design panel beside
  the editor (#296).
- View design works on real projects: suites that inherit `ChiselSim` from a
  project base class or trait, `behavior of` + `it should` tests, and the test
  module's `forkArgs`, `forkEnv` and working directory (#298).
- **Simulate** lens: runs a ChiselSim test with waveforms on and opens the
  trace in Surfer (#299).
- The VS Code design panel is embedded: it follows the VS Code theme and drops
  what the editor already does (Source and Waveform tabs, theme controls, file
  drop) (#300).
- SoCLess side panel in VS Code with Tests, Hierarchy and Details views (#301).
- Generator copies (`Seq.fill(n)(Module(...))`) fold into one `<name> × <n>`
  box in the IDE view; `+` or a double-click unfolds them, `−` folds them back
  (#303).
- In the IDE view, a module's logic is shown as FSM, generator-loop and `logic`
  blocks (Chisel layer blocks hidden until `layers` is on); selecting a block
  opens its code (#305).
- An FSM block opens into its state diagram, with transitions labelled by their
  Scala condition, `fit` / `1:1` and Escape to go back (#306).
- Ports tied to a constant show the value as a small stub on the pin instead of
  a constant node, in the IDE view (#308).
- FSM diagrams follow a next-state wire (`state := next`, `RegNext(next)`,
  `RegEnable(next, en)`) (#309).

### Changed

- The minimum Chisel version is now **7.16** (was 7.11) for the runtime
  library and the Mill plugin; the IDE's probe supports Chisel 7.16.
- Infrastructure: pull request CI runs only the test suites and UI smokes whose
  inputs changed (#304). CI builds downloadable artifacts, and releases publish
  the jars, the VS Code vsix and the single-file UI.

### Fixed

- Width labels sit beside their port, and wires into a folded bundle land on
  the folded row (#302).
- The first schematic view, on load and after a drill, is the fitted view
  (#307).
