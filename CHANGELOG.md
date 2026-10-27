# Changelog

Notable changes to SoCLess, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. SoCLess uses
[Semantic Versioning](https://semver.org/). Releases before 0.16.0 are listed on
the [GitHub releases](https://github.com/e-ban/SoCless/releases) and
[tags](https://github.com/e-ban/SoCless/tags) pages.

## [Unreleased]

## [0.18.0] - 2026-10-27

### Added

- The editor focus (the class under the cursor lights its boxes and dims the
  rest) shows a **focus** chip naming the class; its × stops the focus until
  the cursor is on another class. It can be turned off in the settings popover
  (Follow the editor's cursor) or with `socless.followCursor`. docs/usage-ui.md
  says what dims the schematic.
- Schematic defaults in the editor's settings: `socless.schematicColorizeComponents`,
  `schematicColorizeWires`, `schematicDensity`, `schematicLabelSize`,
  `schematicAutoOptimize`, `schematicHintBar`, `schematicShowLayers` and
  `schematicHide`. The panel applies them when it opens and when one changes.
  In the browser, the colorize, Show and verification-layers toggles are now
  remembered too.
- Waveforms show FSM state names instead of numbers (VCD and FST traces):
  SoCLess pairs the state diagram's names with their encodings in the CHIRRTL
  and writes them into a copy of the trace as an enum table, which Surfer
  applies by itself.
- Surfer's right-click menu: Go to declaration shows the signal in the
  schematic and opens its declaration in the editor; Add drivers and Add loads
  add the signals that drive it or that it feeds, found in the design through
  operators, muxes, memories and logic blocks, and across a port into the
  parent. In the Surfer app and the panel's Surfer (docs/ide-vscode.md).
- Neovim: the [socless.nvim](https://github.com/e-ban/socless.nvim) plugin
  installs SoCLess from lazy.nvim / LazyVim, `vim.pack`, mini.deps, packer or
  vim-plug (it fetches the server jar of a pinned release; configurable);
  docs/ide-neovim.md leads with it and keeps the manual setup.
- Schematic navigation: a drag pans from anywhere (a block, a wire, a pin or
  the background; a press that barely moves is still a click), as does a
  middle-button drag. The wheel zooms at the pointer, Shift + wheel and
  Ctrl + wheel scroll, and a trackpad swipe pans and a pinch zooms. The arrow
  keys pan, `+` and `-` zoom, `0` or `f` fits and `z` zooms to the selection.
  The new docs/usage-ui.md lists every gesture and key.
- Hover feedback: a hovered block gets a coloured outline and its wires are
  drawn thicker; resting on a block, a wire or a pin shows a card with what it
  is and what a click does there, and the cursor shows what a press will do.
- Help in the UI: a hint bar under the schematic says what the mouse does on
  what is under it (Settings > Hint bar), the "?" card (now "Mouse and
  keyboard") lists the mouse actions per element next to the keys, and four
  first-time tips greet a first visit in the browser.
- Back and forward through the views, like a browser: Alt + ← / Alt + →, the
  mouse's side buttons, or the palette's **Back** and **Forward**. Choosing an
  instance in the search flies to it (a short animation), the hierarchy's ◎
  shows an instance in its parent, and `z` (or **Zoom to selection**) frames the
  selection.
- Follow a net across levels: clicking a wire keeps its net lit through
  instance ports while drilling in and out; Esc or a click on the background
  clears it.
- Waveform colours: a **● colours** chip beside Surfer's status (the Waveform
  tab, and VS Code's Surfer tab) says what each colour means (inputs Blue,
  outputs Green, registers Orange, wires Gray; ports all Blue with
  `socless.wavesColorByDirection` off) and how the signal groups are built.

### Changed

- The schematic no longer rebuilds on hover, selection, the filter or
  colourization, only on a new layout, an expansion or a fold, and the Source
  tab renders a file once and moves its highlight. On a large design
  (TensorGemm, 4x CPU slowdown) resting on a wire for a second went from 19
  frames and 40 280 rebuilt nodes to 60 frames and none, and hovering wires
  from 1 963 ms of work to 368 ms.

### Fixed

- Opening a design whose top is the module already shown (a picked or dropped
  design) left Back and Forward empty until the next move; its fit is now
  the first entry.
- A design file dropped (or another top picked) while a live reload was on
  its way could be replaced by that reload when it landed; a live reload now
  applies only while the live design is the one shown.
- The "laying out..." spinner no longer stays on after expanding a bundle in a
  drilled module and returning to the top: each module now keeps its own
  expanded bundles, and returning to a module shows it as it was left.

## [0.17.0] - 2026-10-26

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
- VS Code: `socless.waveViewer` and `socless.waveFlag` are ordinary user
  settings, so a WSL window lists them in the User tab; the executable paths
  stay per machine (the Remote tab).

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
