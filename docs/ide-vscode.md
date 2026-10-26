# SoCLess in VS Code

The `socless-vscode` extension wraps the same language server as the
[Neovim guide](ide-neovim.md): a **View design** code lens above every
ChiselSim scalatest test (and a [Simulate](#simulate) lens whose waveforms
[follow the schematic](#waveforms-that-follow-the-schematic) in Surfer), a
live design panel that opens beside the editor, a
refresh on save, a panel that follows your cursor, and a click in the panel that
jumps to the source line. The panel is the regular SoCLess UI inside a VS Code
webview, served by the server on `127.0.0.1`.

Requirements: VS Code 1.90 or newer, Java 21 or newer, and a Mill project with
`./mill` at its root. Your tests and build are never modified, and nothing
leaves your machine.

## Install

The extension is not on the Marketplace yet. Download `socless-<version>.vsix`
from the [GitHub releases](https://github.com/e-ban/SoCless/releases) page and
install it with `code --install-extension socless-<version>.vsix`, or build it
from this repo:

```bash
./mill socless-vscode.vsix
code --install-extension out/socless-vscode/vsix.dest/socless-*.vsix
```

The glob matches the one vsix there, whose file name carries the current
version (`./mill show socless-vscode.vsix` prints its exact path). The vsix is
about 70 MB because it bundles the language server jar (`socless-lsp`, with
the probe agent and the [embedded Surfer](#the-embedded-surfer) inside). Building it needs Node and npm (for `npm install
vscode-languageclient` and `npx @vscode/vsce package`), and network access the
first time, to fetch those packages; nothing else of the extension is built
with Node.

## Use it

1. Open a ChiselSim test, for example `example/test/src/gcd/GcdSpec.scala`. A
   `View design` lens appears on each test (the same rules as in
   [Neovim](ide-neovim.md#use-it), including suites that inherit `ChiselSim`
   from a project base trait and `behavior of` + `it should` tests). Runs are
   forked with the test module's `forkArgs`, `forkEnv` and
   `MILL_WORKSPACE_ROOT`, as `./mill <module>.test` does (see
   [How a view run is forked](ide-neovim.md#how-a-view-run-is-forked)).
2. Click the lens. The panel opens beside the editor. The first run of a test
   module compiles it with Mill, so it can take a while; the panel shows
   "waiting for the first design" until it succeeds.
3. Save a file to refresh the panel, move the cursor to focus the class under it
   (after a 300 ms pause), click a box to open its source.
4. Close the panel and run the lens again to get it back, or run the command
   `SoCLess: Show design panel` to reveal the current design.

## The SoCLess side panel

The SoCLess icon in the activity bar opens a side panel with three native
views. The webview beside the editor keeps only the schematic.

- **Tests**: every ChiselSim suite of the workspace (the simple class name,
  with its file as the description) and its tests, found with the same rules
  as the lenses (no Mill call). Clicking a test opens its source line. Each
  test has two inline actions: `View design` (eye) and `Simulate` (play),
  the same commands as the lenses. The test whose design the panel shows is
  marked `shown` (eye icon); a test being viewed reads `running…`, one being
  simulated `simulating…`, except while a view runs or after a view failed
  (as in the panel's run status); a test whose last view failed shows an
  error icon until a view of it succeeds. The list follows your saves: adding,
  renaming or removing a test updates it.
- **Hierarchy**: the instance tree of the design in the panel (instance name,
  then its module, or the simple name of its Scala class for a captured
  design). Clicking a module drills the schematic into it; clicking a leaf (or
  a blackbox) shows its parent with the leaf selected. Either way its source
  opens beside, and the focus stays in the tree. The drill path of the
  schematic is selected in the tree as you navigate.
- **Details**: what is selected in the schematic, or the module in view when
  nothing is: the instance and its module, the full Scala class, the
  parameters (`name: type = value`, in declaration order) and the ports
  (name, direction, type).

The Tests title bar has three buttons: refresh the tests, show the design
panel (`SoCLess: Show design panel`) and open the settings filtered to
SoCLess. Hierarchy and Details are empty until a design is shown, and are
cleared when the panel closes (no test is marked `shown` then).

When the language server cannot start (Java missing or too old) or has
stopped, the Tests view says so instead of staying blank, and its inline
actions report it instead of marking a test running. When the server
restarts (after a crash), the run states are reset and the tests are fetched
again.

The views come with the extension's activation: opening a Scala file or the
SoCLess side panel starts it (VS Code activates an extension on its views by
itself since 1.74, so no `onView` event is declared).

## What the panel shows

The panel is the SoCLess UI in embedded mode (the extension loads it with
`?host=vscode`), trimmed to what VS Code does not already do:

- the tab is titled `SoCLess: <test name>`, so the UI has no logo of its own;
- no Source tab (the editor shows the code, and a click on a box opens it
  there). The Waveform tab is there only when `socless.waveViewer` is
  `surfer-panel` (see [Waveforms that follow the
  schematic](#waveforms-that-follow-the-schematic)). The bottom panel keeps
  Ports and starts closed; its toolbar button opens it, and so does a new
  trace in `surfer-panel` mode, on the Waveform tab;
- no theme toggle or theme gallery: the panel follows the VS Code theme, light
  to the light theme, dark to the dark one, high contrast to the high-contrast
  one and high contrast light to the light one, and switches live when you
  change the VS Code theme. This is not saved as your theme in the browser UI;
- no multi-top design picker, no drop zone for `design.json` / `.fir` files and
  no `share` (HTML snapshot) button: the design always comes from the test you
  viewed. The `svg` and `png` exports stay;
- no hierarchy pane and no inspector pane, nor their toggle buttons and
  commands: the [side panel](#the-socless-side-panel) shows them, and the
  schematic takes the whole width.

Breadcrumbs, search, the run status, the bottom panel toggle, the view settings
and the keyboard help work as in the browser. Opened in a browser or from
Neovim, the UI is unchanged.

Viewing a test again, or another one, or running `SoCLess: Show design panel`
does not reload the panel: the new design loads in place, and the open panels
and the drill path (while it exists in the new design) are kept.

The UI and the side panel talk through the webview page: the UI posts its drill
path, selection and details (`socless-state`, once per change), with the design
hierarchy only when the design changed, to the page that frames it, only when
that page is a VS Code webview or a loopback page and only to its exact origin.
The page forwards it (from the UI's window and origin only) to the extension,
and forwards the extension's `socless-navigate` (from a window of the webview's
own origin only) to the UI's origin. The server sends the UI page with
`Content-Security-Policy: frame-ancestors 'self' vscode-webview: vscode-file:`,
so no other site can frame it (`vscode-file:` is the workbench, which the
check covers as an ancestor too). The language server adds the `socless/tests`
request and the `socless/testsChanged` and `socless/status` notifications, sent
only to a client that hosts the panel (VS Code), so Neovim sees no change.

## The schematic in the IDE view

A design viewed from a test is drawn with a few extras over the classic
CHIRRTL view. They are the same in the Neovim browser panel.

**Folded copies.** Generator copies (`Seq.fill(n)(Module(...))`) show folded:
one box titled `<name> × <n>` stands for the n copies, and the wires into and
out of them merge with their counts. The `+` on the box, or a double-click on
it, unfolds the copies; the `−` on any copy folds them back. The choice is kept
while you navigate. The side panel's Hierarchy tree still lists every copy;
selecting a copy there unfolds its group.

**Logic blocks.** Inside a module, the logic that is not an instance collapses
into blocks: an `FSM: <reg>` box for each state machine, a box labelled with
its loop head for each generator loop (`for (b <- 0 until batch)`), and a
`logic` box for the rest. A block's pins are the signals that cross its edge. A
pin wired to a port or an instance is named after that signal without its Vec
indices, so the lanes of `io.out.data.bits` share one pin, and a folded copy's
pin takes the group's name (`acc_io_deq_bits`); a pin between two blocks is
named after the signal that drives it. Selecting a block opens its code: the
loop, the module, or for an FSM its `switch` line. Blocks of Chisel layer
content (`assert` and other verification) are kept apart (the leftover one is
labelled `debug logic`) and stay hidden until you turn on `layers`.

**Tied ports.** A port or an instance pin tied to a constant (`io.op := 0.U`)
is not drawn as a constant node: the pin carries a short `|-` stub with the
value (`0`; `0x2a` from 10 up). When several tie-offs land on one pin (a folded
bundle row, a Vec group) with different values, the stub reads `<n> tied` and
its tooltip lists `signal = value` (the first 10). A constant that is only a
`when` default next to another driver is not a tie and gets no stub.

**State diagrams.** Double-click an `FSM: <reg>` box (or click the `↵` at the
right of its header, or focus it and press Enter) to open its state diagram:
one node per state, the initial state marked by a dot, and an arrow per
transition read from the `switch` in your source. An arrow's label is the Scala
condition text of the `when` / `.elsewhen` that guards the assignment
(`io.start`, `cond && !(inflight =/= 0.U)`); it is empty when the state moves
on unconditionally, and a branch after earlier ones carries their negation
(`!cond`). Click a state or an arrow to open its line. `fit` shows the whole
diagram and `1:1` its natural size, scrolled; Escape returns to the schematic.
A transition whose target is computed rather than a state name is not drawn,
and the diagram says how many were skipped.

A separate next-state wire is followed: a local `val next` (`Wire`,
`WireDefault(state)`, `WireInit`, ...) counts like the register itself when it
is assigned (`next := ...`) and the register takes it unconditionally
(`state := next` outside every `when` and every `is` case), or when the
register is defined as `RegNext(next)`, `RegNext(next, sIdle)`,
`RegEnable(next, en)` or `RegEnable(next, sIdle, en)` (the init then marks the
initial state). Its `next := sRun` assignments are transitions, and holds such
as `next := state` are ignored. A `state := next` under a `when` is not
followed: `next`'s assignments are not shown, and the `state := next` counts as
one computed transition.

**Without the source index.** `./mill example.soclessView` (and the
`Launcher from-capture` path behind it) has no Scala source index: it shows the
FSM and `logic` blocks, but no generator blocks; their logic stays in the
`logic` block. Its FSM diagrams list the states without transitions, under the
note "Transitions are not shown: this view was built without the Scala source
index." In the editor, a state machine whose source cannot be read shows its
states the same way, with "Transitions are not available: the state machine's
Scala source could not be read."

## Simulate

Next to each `View design` lens, a `Simulate` lens runs the test for real with
waveforms on and shows the trace in [Surfer](https://surfer-project.org/).
View design needs no simulator; Simulate needs Verilator (on the `PATH` the
test sees, as for `./mill <module>.test`), and the Surfer app unless
`socless.waveViewer` is `surfer-panel`.

- Simulate first views the same test, as `View design` does: the panel opens
  (or refreshes) on that test's design. Then the simulation runs.
- The test runs with the module's fork settings, without the probe, with
  `-D<socless.waveFlag>=1` passed to ScalaTest (ChiselSim reads its options
  there). The default `emitVcd` comes with ChiselSim; set `emitFst` or another
  option your project defines. A suite that does not know the option fails
  with "illegal ChiselSim ScalaTest option".
- Waves are kept in `out/socless/sim/<suite>/<test>/` under the workspace root
  (ChiselSim's `build/chiselsim` goes there), emptied at the start of each run
  of that test, with `sim.log` (the test's output) and `surfer.log` (the Surfer
  app's). `./mill clean` (which empties `out/`) removes them too.
- When the test ends, pass or fail, the newest `*.vcd` or `*.fst` there is the
  trace. VS Code shows `Simulation passed: <suite> <test>. Waves: <path>` (a
  warning when it failed), or `No waveform found` with the flag to check. A
  test name that matches no test is a failure, and a build or Mill problem is
  reported as `Simulation not run`.
- The trace goes to the viewer `socless.waveViewer` names, which follows the
  panel's selection (see the next section).
- One simulation at a time: a second one is refused with a message and the
  first goes on; View design keeps working. A simulation is stopped after 30
  minutes. The panel shows `simulating…` while one runs, unless it shows a
  view run or a view error: a simulation never hides those.

See [Simulate in the Neovim guide](ide-neovim.md#simulate) for the exact
command line.

## Waveforms that follow the schematic

After a simulation, the module you look at in the panel decides which signals
the waveform viewer shows. `socless.waveViewer` picks the viewer:

- `surfer` (the default): Simulate starts the Surfer app on the trace, as
  `<socless.surferCommand> --wcp-initiate <port> <trace>`, and drives it over
  WCP, Surfer's control protocol. Its list is flat (full signal names, no
  groups, since WCP has no group command) and coloured. Each Simulate replaces
  the previous Surfer window. This needs a Surfer with `--wcp-initiate`
  (tested with Surfer 0.7.0). One that exits without connecting (too old for
  that option) gets a warning pointing at `surfer.log`, and is started again
  as `<socless.surferCommand> <trace>`, so the waves still show, without
  following the panel. That fallback window is not replaced by the next
  Simulate (like a `custom` viewer); close it yourself. A `surferCommand`
  wrapper must `exec` Surfer: one that forks and exits looks like an old
  Surfer.
- `surfer-panel`: Surfer runs inside the panel's Waveform tab. The list is
  grouped: one group per port, with its bundles nested, then `Registers`, then
  `Wires`. Signals are coloured and carry short labels (`valid`, `bits`,
  `data [31:0]` for a multi-bit one) under their group's name.
- `custom`: `socless.waveViewerCommand` runs on the trace, with `{file}`
  replaced by its path. It is split on whitespace, with no quoting and no
  shell, so a path with spaces cannot be passed. Without `{file}`, the path is
  appended; with an empty command, `socless.surferCommand` is used. Nothing
  follows the selection.

With the two Surfer viewers:

- Selecting a module (drilling into it in the schematic or the Hierarchy
  view) replaces the signal list with that module's. The list holds clock and
  reset when `socless.wavesShowClockReset` is on, then its ports in
  declaration order, its registers, and its wires and named nodes.
- Inputs are blue, outputs green, registers orange and wires gray. With
  `socless.wavesColorByDirection` off, all ports are blue.
- A smaller selection moves Surfer's focus to one signal:
  - a port or bundle pin: its first field;
  - a wire: its end in the module;
  - a logic block: its first pin;
  - an FSM block: its state register.
- Surfer is updated `socless.wavesFollowDelayMs` (150 ms) after the last
  change, so clicking through the hierarchy sends only the last selection.
- The trace is stamped with its test (suite and name). While the panel shows
  another test, following pauses until you simulate that one.
- Signals the module declares but the trace lacks (optimised away by firtool
  or Verilator) are left out, and the Waveform tab says `N signals not in the
  trace`. For an FST trace the server cannot read the names, so Surfer drops
  them itself; the panel's Surfer counts those too, the app's are only logged.
- An aggregate register or wire lists its first 256 ground leaves; a design
  warning counts the others.
- In the panel's Surfer, a focus scrolls the focused signal to the top of
  the list, as far as the list allows (when the list is longer than the pane,
  this happens on every focus, even for a signal already in view).
- With `socless.wavesFollowSelection` off, Surfer shows the module in view
  when the trace arrives, and stays there.

Known limits:

- The panel's Surfer only answers while its canvas is drawn: on another tab
  or with the panel closed, the latest selection applies once it shows again.
- On a Wayland desktop with XWayland (`DISPLAY` set), the Surfer app is
  started under XWayland: a native Wayland window gets no frames while it is
  hidden (another workspace, a locked screen), and Surfer only answers WCP
  from its frames. Without XWayland it runs natively and follows only while
  its window is visible. The app must connect within a minute of starting, or
  it does not follow for that simulation.
- The Surfer app handles WCP commands from its frame loop (every queued
  command each frame), so the server sends up to 32 at a time instead of
  waiting for each reply: a module of hundreds of signals takes a few frames,
  and a focus waits until it is done. Selecting another module (or changing a
  wave setting) stops a plan that is still being sent.
- WCP cannot scroll Surfer's list: in the Surfer app, a focused signal below
  the visible rows stays out of view until you scroll to it.

### The embedded Surfer

The `surfer-panel` viewer is [Surfer](https://surfer-project.org/) v0.7.0,
built to WebAssembly from its release tag and shipped inside the language
server jar. It is a separate component under its own licence, the EUPL-1.2,
which ships beside it. `socless-lsp/vendor/surfer/SOURCE.md` says where it
comes from and how to rebuild it.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `socless.javaPath` | empty | Java 21+ executable that runs the server. Empty: `$JAVA_HOME/bin/java`, else `java` on the `PATH`. |
| `socless.serverJar` | empty | Server jar to use instead of the bundled one. |
| `socless.waveFlag` | `emitVcd` | ChiselSim option that turns waves on for [Simulate](#simulate), passed as `-D<flag>=1`. |
| `socless.surferCommand` | `surfer` | The Surfer app (an executable): started as `<command> --wcp-initiate <port> <trace>` for `surfer`, and as `<command> <trace>` for an empty `custom` command. |
| `socless.waveViewer` | `surfer` | `surfer` (external app), `surfer-panel` (embedded in the panel) or `custom`. |
| `socless.waveViewerCommand` | empty | Command for `custom`, split on whitespace (no quoting); `{file}` is the trace path, e.g. `gtkwave {file}`. |
| `socless.wavesFollowSelection` | `true` | Surfer viewers only: the selected module decides which signals Surfer shows. |
| `socless.wavesShowClockReset` | `false` | Surfer viewers only: clock and reset lead the list. |
| `socless.wavesFoldBundles` | `false` | `surfer-panel` only (the app's list has no groups): bundle sub-groups start folded; a focus unfolds its groups. |
| `socless.wavesShowWires` | `true` | Surfer viewers only: include the Wires group. |
| `socless.wavesColorByDirection` | `true` | Surfer viewers only: inputs blue, outputs green (else all ports blue). |
| `socless.wavesFollowDelayMs` | `150` | Surfer viewers only: delay before Surfer is updated after a selection change. |

`socless.javaPath` and `socless.serverJar` need a reload. The others apply
without one; the `socless.waves*` settings are read on the next selection.

If Java is missing or older than 21, the extension shows one error message
naming the setting and does not start the server. If the server crashes it is
restarted up to three times in three minutes, then a message says so.

Each VS Code window runs its own language server, with its own loopback port.
The extension needs a trusted workspace: it runs your build (Mill) and tests,
so Restricted Mode disables it.

The extension contributes the `scala` language id itself (stock VS Code has none,
and the server only attaches to Scala files), so it works without Metals.

## Verified by the end-to-end test

```bash
./mill socless-vscode.stage
socless-vscode/e2e/run.sh out/socless-vscode/stage.dest
```

This is a script, not a Mill command: it runs the server, which calls
`./mill --no-daemon` on this repo, and a Mill command would contend with those
calls on the `out/` lock. For the same reason, do not run it while another
`./mill` command runs. It starts a headless VS Code (xvfb, a throwaway profile
and extensions dir) on this repository with the staged extension and checks:

1. the SoCLess view container exists and its Tests view lists `GcdSpec`
   with `GCD computes gcd(48, 18)`;
2. the `View design` lens appears on `GcdSpec.scala`;
3. `View design` on that Tests view item opens a SoCLess webview tab titled
   after the test, `/design.json` serves the GCD design, and the test is
   marked `shown`;
4. the cursor in `GCD.scala` makes `/ide.json` report `gcd.GCD`;
5. `POST /open` (what a click in the panel sends) moves the editor to
   `GCD.scala:24`;
6. closing the panel and running the lens again opens exactly one new panel;
7. viewing `DeepSoc` fills the Hierarchy view (`DeepSoc` > `cluster0`, ...);
   inside the webview, the UI loads `DeepSoc` from the server, and a real mouse
   click on the `cluster0` box makes the UI send `POST /open` (with the
   iframe's `Origin`, accepted by the server), which moves the editor to the
   `Cluster` class in `DeepSoc.scala`, and the Details view shows `cluster0`
   (module, ports). Before the click it checks the UI is in embedded mode (no
   Source tab, logo, theme toggle, hierarchy or inspector pane, bottom panel
   closed) with the data theme of the VS Code theme;
8. clicking `cluster0` in the Hierarchy view drills the schematic into it;
9. with the bottom panel open, viewing the design again and running
   `SoCLess: Show design panel` do not reload the UI (drill path and panel
   are kept);
10. after switching the VS Code theme (dark to light), the UI in the webview
    follows it.

The webview checks (from step 7 on) drive the webview over the Chrome DevTools
Protocol: `run.sh` starts VS Code with a free `--remote-debugging-port` and runs
`socless-vscode/e2e/webview-click.mjs` (plain Node, no npm package) next to it.
The screenshot it takes before the click and its result are kept in
`$SOCLESS_E2E_ARTIFACTS` (default `${TMPDIR:-/tmp}/socless-e2e-artifacts`).

Needs `xvfb-run` and a Linux VS Code. It finds the Electron binary next to the
`code` on your `PATH`; set `SOCLESS_E2E_ELECTRON` to override. A cold first run
(Mill compiling the example tests) can take a few minutes; a warm run takes
about 18 seconds. The pure logic is covered by `./mill socless-vscode.test`
(Scala.js unit tests, run on Node), which CI runs.

## Known limits

- **Metals coexistence is expected, not verified.** The extension only adds
  lenses, its command, save handling and cursor forwarding, and the `scala`
  language contribution merges with Metals' own. The end-to-end test runs with
  `--disable-extensions`, so no automated check has Metals installed.
- CI builds the vsix (a downloadable artifact of every run), but the headless
  end-to-end test is not run in CI.
- The end-to-end test does not run Simulate; the server's tests cover it (a
  real Verilator run of the GCD test when `verilator` is on the `PATH`).
- Remote workspaces (Remote-SSH, WSL, Codespaces) are not supported for the
  panel: port forwarding changes the `Host` (its port) that reaches the
  server, and the loopback server only accepts `127.0.0.1` or `localhost` on
  its own port. A setup that blocks `127.0.0.1` will not show it either.
