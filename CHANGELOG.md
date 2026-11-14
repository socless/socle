# Changelog

Notable changes to Socle, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. Socle uses
[Semantic Versioning](https://semver.org/). Releases up to 0.21.0 were named
SoCLess; their sections below keep the names they shipped with. Releases before
0.16.0 are listed on the [GitHub releases](https://github.com/socless/socle/releases)
and [tags](https://github.com/socless/socle/tags) pages.

## [Unreleased]

## [0.29.0] - 2026-11-14

### Added

- View design reads every module constructor argument, not only `val` parameters:
  `Cluster(nCores = 2)` instead of `Cluster(nCores = <unreadable>)`. The probe records the
  arguments as each module is built (in memory, inside the forked view process: sources, class
  files on disk and builds are untouched) and renders them after elaboration: case classes as
  `Name(field = value)`, collections up to 8 elements, Chisel types by their label, functions,
  modules and objects without a `toString` of their own as placeholders. Each captured parameter
  also says where its value was read (`origin`: `ctor` or `val`) and carries a digest of the
  whole value, empty when the value is not fully known.
- design.json carries each constructor parameter's `origin` and `digest` (`ParamDef`; omitted when
  empty, so older files still load), and `ClassGroups` groups a view's modules by Scala class, then
  by parameterization (equal parameter digests), with the instance paths of each module: the data
  for a class view of the hierarchy. A parameter whose value is not fully known keeps its module
  apart. Designs from CHIRRTL (no Scala class) give no group.
- The Hierarchy pane has a Classes tab beside the tree (Instances) for a design opened from a
  test: one row per Scala class, then one per set of constructor arguments (`(nCores = 2)`, a `?`
  when a value could not be read and was not compared), then one per instance path; a click on an
  instance opens it as the tree does. Classes start closed, library classes after the user's.
  Designs from CHIRRTL keep the tree alone, with no tab bar.

### Changed

- View design gives each instance the source position where it is created (its `Module(...)`
  call), as the CHIRRTL path already does: selecting an instance in the schematic now highlights
  that line in the Source tab instead of the module declaration, and the instantiation files are
  embedded with the other sources.

### Fixed

- The Source tab no longer lights a line where the selected instance's name appears only in a
  comment (`//`, `/* */`, Scaladoc) or a string literal.

### Security

- The standalone server (`serve`) answers only requests whose `Host` names the address it serves
  on (`localhost`, `127.0.0.1` or `[::1]` on any port, so an `ssh -L` tunnel works; the `--host`
  given, or under `0.0.0.0` the IP the connection reached, with the port): another site can no
  longer read a design or convert through it by DNS rebinding. `POST /convert` also refuses a
  cross-origin `Origin` (a request without one, as curl sends, still works), and its 128 MB cap
  now counts the bytes read, so a chunked body cannot pass it.

## [0.28.0] - 2026-11-14

### Added

- A CHIRRTL statement the parser does not know (an `unrecognised-statement` warning naming its
  keywords) or cannot read (an `unparsed-statement` warning quoting the first line) is counted
  in the design's warnings instead of being dropped silently. View design carries these parser
  warnings too.
- View design warns (`untested-chisel`) when the capture comes from a Chisel major.minor other
  than the tested one (7.16), and a "no design" result names the Chisel found on the test
  classpath.

### Fixed

- A wire the layout could not route (drawn as a straight segment) to a field of a collapsed
  bundle or Vec pin starts from that pin again, not from a sibling pin whose name is a prefix
  of it (`io.a` for `io.ab.bits`, `in[1]` for `in[10].x`), which could sit on the other side
  of the box.
- Selecting or hovering a wire, pin or box no longer lights the wires and pins of a sibling
  whose name merely starts with the same text: `io.a` lit `io.ab`, box `core1` lit `core10`'s
  wires, op node `_T` lit `_T_1`'s. A selection still lights the fields, elements and logic
  pins (`r~q`) under it and the bundle pins over it.

### Documentation

- Install steps that match the releases: the standalone guide and the Neovim guide download
  `socle-<version>.jar` and `socle-lsp-<version>.jar` from the releases page, and the Mill guide
  says the plugin is published nowhere, so it is published locally from a release tag. The
  `io.socle::...:0.12.0` coordinates, which never existed, are gone.
- `docs/lsp-protocol.md` lists the whole client contract: the standard LSP messages the server
  sends (`window/showDocument`, `window/showMessage`), the panel's HTTP routes, the UI's URL
  parameters and its webview messages (`socle-state`, `socle-navigate`, `socle-theme`, the
  Surfer tab's), and which items are optional for a client.

## [0.27.0] - 2026-11-11

### Added

- The Hierarchy pane shows generator copies as the schematic folds them: one `<name> × <n>`
  row, which opens their module with the folded box selected and lists the copies under it.
  The Inspector of a folded box gives its copy count. For the editors' side panels, the
  hierarchy names each copy's `foldGroup`, a folded box's details give its `copies`, and
  `socle-navigate` selects a fold group by its id (#15).

### Fixed

- View design folds generator copies only when they play one role: copies in parallel (no wire
  between them, the same connections) or a chain with nothing in between (`A0 => A1 => A2`).
  Copies on either side of another instance (`A0 => C => A1`, e.g. layers a `match` makes on
  one line) no longer fold into one box, nor do copies with different ports or a 2D array of
  copies wired to each other (#15).
- View design no longer folds copies linked by a private logic path, such as pipeline stages
  with a `RegNext` between each (`A0 -> reg -> A1`): the register is something in between.
  Copies that share logic (an arbiter every copy feeds and reads) still fold (#15).
- The browser tab shows the Socle icon: the page's icon link was cut short by its own quotes.

## [0.26.0] - 2026-11-10

### Added

- IDE: Simulate compiles the design with the `firtoolOpts` setting (`socle.firtoolOpts` in
  VS Code), by default `-preserve-values=named`: the ports and instances firtool would remove
  are now in the trace, so the Waveform tab finds them. ChiselSim adds these options to the
  test's own; an empty value compiles as `./mill <module>.test` does (#17).

## [0.25.2] - 2026-11-10

### Fixed

- Waveforms of a design whose top module has an empty `io` bundle (`IO(new Bundle {})`)
  follow the schematic: the trace was anchored one instance too deep, so no signal
  was found. In the IDE view such a port is now an empty bundle, as in export
  mode (#7).
- After a pan, the first click made with the keyboard (Enter on a focused control in
  the schematic) is no longer swallowed (#14).
- IDE: View design and Simulate run on the test module's JDK when it pins one
  (`javaHome`), not the server's. The probe agent now loads on Java 8 and newer
  (run on 8, 11, 17 and 21): part of it was Java 21 bytecode (#2).
- IDE: a `test` module that is not a `TestModule` (no `testSandboxWorkingDir`) keeps
  its `forkArgs` and environment instead of falling back to its classpath alone; only
  a missing task of the test's own module triggers that fallback (#2).
- IDE: FunSuite `test("...")` calls inside a `def` get their lens (#2).
- IDE: with an editor workspace above or below the Mill project, the server runs Mill
  in the project's root and indexes only its Scala files, leaving out ChiselSim's
  `build` and chiseltest's `test_run_dir`. Files changed outside the editor (switching
  branches) update the lenses when the client can watch files (#2).

### Documentation

- How to keep the ports and instances firtool would remove in a simulation's trace
  (`-DfirtoolOpts=-preserve-values=named`), with its cost (#7).

## [0.25.1] - 2026-11-10

### Fixed

- The UI fits a narrow editor panel (an IntelliJ or VS Code panel about 600 px wide):
  the top bar's design label and search button shrink and the schematic toolbar wraps,
  so the page no longer shows scrollbars (#12).

## [0.25.0] - 2026-11-06

### Added

- The schematic shows the Chisel code's own structure: every `when` and `switch` you
  wrote is a box labelled as written (`when(io.enable)`) and nested as in the code,
  with its condition on a pin. The registers it assigns stay at module level, fed from
  the box, and a `switch` state machine's box opens its state diagram. Export mode
  needs the source root (the Mill plugin passes it); view mode reads the sources
  through the IDE.
- Clock domains on registers: each register is coloured with its clock domain, and
  logic that derives a clock or a reset (`io.sclk` to a rising-edge clock) shows as a
  small coloured stub. New example: `SpiSlave` (`mill example.socleRunDomains`).
- Previous / Next module buttons at the top left of the schematic and of the state
  diagram. On the diagram, Previous closes it and Next reopens it. Alt+Left / Alt+Right,
  the mouse's side buttons and the palette still step one view at a time.
- The hierarchy tree follows the module you open: it expands down to it, highlights
  it and scrolls it into view.

### Fixed

- A state machine inside a `when` (`when(io.enable) { switch(state) { ... } }`) is
  detected, and the conditions it reads are wired into it.
- A double-click opens any module with registers, logic or wires, not only modules
  with sub-instances.
- A read of one element of a `Vec` register comes from the register's output.

## [0.24.0] - 2026-11-04

### Added

- The zoomed-out overview keeps the I/O tags' names: each tag shows its name at a fixed
  screen size, on a chip that grows away from its pin side so it never covers a box.

### Changed

- Faster exports of large designs: three per-module lookups are now linear (an 8.8 MB
  CHIRRTL design went from 44 s to 18 s, with an identical `design.json`), and modules
  above 1000 boxes are placed with Brandes-Koepf instead of network simplex (a 2229-box
  module from 7.1 s to 0.3 s). Mid-size modules keep network simplex.

### Fixed

- Every I/O tag stays in its column, including a tag carrying a wire the other way
  (a Decoupled `ready`), and a wire answering data no longer flips the order of the
  boxes it connects (a queue placed after the decoder it feeds).
- Layouts are now the same from one JVM to the next: register hold wires (self-loops)
  are routed by Socle in both engines instead of by ELK, whose route depended on the
  JVM's identity hashes.

## [0.23.0] - 2026-11-02

### Changed

- Socle is now licensed under the **GNU AGPL-3.0** (was MIT), with an additional permission
  for its EPL libraries (ELK, elkjs, EMF, LSP4J); see LICENSING.md. A commercial licence is
  available for closed-source tools, and contributions go through a contributor agreement
  (CLA.md). The jars and the single-file UI now carry the third-party notices and licence
  texts (THIRD_PARTY_NOTICES.md, `legal/`).
- The VS Code extension moved to its own repository,
  [socless/socle-vscode](https://github.com/socless/socle-vscode), with its own
  releases (from 0.23.0, each bundling a pinned Socle language server). Socle's
  releases no longer carry a vsix. `docs/lsp-protocol.md` lists the server's
  protocol for its clients.
- The embedded Surfer is rebuilt from the same v0.7.0 sources without the builder's local
  paths. The language server jars and vsix files of 0.17.0 to 0.22.0, which carried them,
  are removed from those releases: use 0.23.0 or later.

## [0.22.0] - 2026-11-02

The first release named Socle.

### Added

- The Socle brand (`brand/`): the logo in the UI's top bar, a favicon, the
  VS Code tile and activity bar mark, and the charter's stone, slate and brass
  in the default light and dark themes (the accent is unchanged).

### Changed

- SoCLess is now **Socle**, everywhere, with no alias for the old names:

  | Before | After |
  |---|---|
  | Scala packages `socless.*` | `socle.*` |
  | Maven `io.socless::socless`, `::mill-socless` | `io.socle::socle`, `::mill-socle` |
  | Mill trait `SoclessModule`, tasks `socless`, `soclessUI` | `SocleModule`, `socle`, `socleUI` |
  | `SoclessExporter` | `SocleExporter` |
  | VS Code extension `socless`, settings and commands `socless.*` | `socle`, `socle.*` |
  | LSP methods `socless/*` | `socle/*` |
  | Environment `SOCLESS_*`, properties `-Dsocless.*` | `SOCLE_*`, `-Dsocle.*` |
  | Release files `socless-X.jar`, `socless-lsp-X.jar`, `socless-X.vsix`, `SoCLess-X.html` | `socle-X.jar`, `socle-lsp-X.jar`, `socle-X.vsix`, `socle-X.html` |

  The browser's stored view settings (`socless-*` keys) start over once.
  `design.json` files are unchanged and still load.

## [0.21.0] - 2026-11-02

### Added

- A click on a coloured `clk` pin or a legend row lights that clock domain:
  the other instances fade.
- A logic block opens like a module: double-click it (or its `↵`) to see its
  registers, operators and wires, with its pins as ports. The breadcrumb names
  it (`logic`) and takes you back.
- Clock domains: when a module's instances run on two or more clocks, each
  domain has a colour (its `clk` pin and a stripe beside the clock and reset
  pins) and a legend.

### Changed

- A box header no longer shows the module kind glyph (`·`, `◆`, `⬢`, `■`); the
  hierarchy tree keeps it.
- An open logic block reads as a block: the chip says `block` and counts its
  registers and operators, and the Inspector lists its pins, registers,
  operators and memories instead of module metrics.
- Clock and reset are pins: `clk` (the flip-flop chevron) and `rst` (a ring,
  `(async)` for an AsyncReset) at the bottom of a box's left side, in place of
  the two small marks in its header.
- A selection opens the editor at its own line: a register, an operator or a
  memory at its declaration, a wire or a block pin at its connection, a port at
  its declaration. An instance still opens its module's class.
- A bake now holds a layout for each logic block, and boxes keep rows for their
  clock and reset pins (layout model 3): older files are laid out again.

## [0.20.0] - 2026-11-02

### Added

- The Inspector shows a module's scaladoc, and a pin's card the scaladoc of
  its field (read from the embedded sources: the CHIRRTL export and the IDE
  view both carry them).
- A state diagram zooms and pans like the schematic: the wheel zooms at the
  pointer, a drag pans, `+` / `-` zoom, `f` fits; its buttons are fit and 100%.

### Changed

- Verification layers are laid out apart from the design. Hidden (the
  default), the schematic is the design alone, with no gap where they were;
  shown, they sit in a band titled "debug" below it.
- CI runs on pull requests and by hand, no longer on each push to main.
- Designs exported before this version are laid out again when they open.

### Fixed

- A register of bundle type opens in place again (lost in 0.19.0).
- A signal row shows its width once (`8b`), not also its type.
- In the overview, bundle wire types are readable and edge stub labels fade
  with the pin text.

## [0.19.0] - 2026-11-02

### Changed

- Blocks and the module's own ports show the ports Chisel declares, as trees:
  one row per port, in declaration order, wired or not (a port with no wire is
  faint), every bundle opening in place on its own side with its fields' own
  directions. A module's `io` is shown as its fields; a Decoupled port sits on
  the side of its `valid`, and a mixed bundle on the side of most of its
  Decoupled channels. The module's own ports are one stub per port instead of
  one per signal. docs/usage-ui.md has the rules. An older `design.json` is
  laid out again when it opens (its baked pins follow the old model).
- Types are shown by their Chisel-level name: a captured design's bundle class
  (`DecoupledIO`, `GCDInput`), else a shape SoCLess recognises (`AXI4`,
  `Decoupled`), never a structural hash such as `Io_284312`; an anonymous
  bundle shows no type next to its port's name (`Bundle` in the type columns).
  A logic block's pins are labelled by their source path (`io.in.bits.a`), not
  the lowered name (`io_in_bits_a`).
- Schematic visual pass (#334). Pins are glyphs: a triangle pointing the way a
  signal flows, a tab for a bundle (its chevron opens it). Boxes lose their
  footer (clock and reset are marks in the header) and their `+`/`−` marks;
  open fields hang from a guide line, and a Decoupled's `bits` is one row until
  opened. Bundle wires are thick and carry their type once; signal wires are
  thin. Zoomed out past readable pin text, the schematic shows an overview with
  box names at a readable size. A logic block's pins follow the boxes they
  connect to (routes up to 9% shorter on large captures). The fan-out and
  fan-in dots are gone: they sat on the pins and read as port marks.
- Faster schematic (#335). The export bakes its layouts with the optimize
  pass the UI applies by default, so a first view is final at once instead of
  being laid out again in the browser (a large design's top module: about 560
  to 290 ms; BigArray: 650 to 300 ms). Opening or closing a bundle lays out
  again from the layout shown, keeping its columns and order (a large design's
  top module: 290 to 160 ms), and the layout engine starts while the first
  view is idle.

### Fixed

- A bundle could show the same fields on both sides of a block, or open and
  then have no way to close; every bundle now has one row and closes again.
- The direction arrows of a consumer's Decoupled `valid` and `ready` were
  swapped (#313).
- The IDE view's Inspector showed 0 registers, connects and lines for every
  module; it now has the CHIRRTL walk's counts.
- Wires bent into a small bump just before each pin: the layout ended them
  3px off the pin. They now end straight on it.
- A first view could stay a few pixels off its fit when a bar appeared under
  the schematic while it loaded; it now refits while it is still the automatic fit.

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
- Neovim: the [socless.nvim](https://github.com/socless/socless.nvim) plugin
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
  tab renders a file once and moves its highlight. On a large design (4x CPU
  slowdown) resting on a wire for a second went from 19 frames and 40 280
  rebuilt nodes to 60 frames and none, and hovering wires from 1 963 ms of
  work to 368 ms.

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
  method, as in AXI-style read and write handlers) now gets its state diagram,
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
- View design handles more test setups: suites that inherit `ChiselSim` from a
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
