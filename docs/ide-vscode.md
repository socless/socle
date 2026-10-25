# SoCLess in VS Code

The `socless-vscode` extension wraps the same language server as the
[Neovim guide](ide-neovim.md): a **View design** code lens above every
ChiselSim scalatest test (and a [Simulate](#simulate) lens that opens its
waveforms in Surfer), a live design panel that opens beside the editor, a
refresh on save, a panel that follows your cursor, and a click in the panel that
jumps to the source line. The panel is the regular SoCLess UI inside a VS Code
webview, served by the server on `127.0.0.1`.

Requirements: VS Code 1.90 or newer, Java 21 or newer, and a Mill project with
`./mill` at its root. Your tests and build are never modified, and nothing
leaves your machine.

## Install

The extension is not on the Marketplace yet; build the vsix from this repo.

```bash
./mill socless-vscode.vsix
code --install-extension out/socless-vscode/vsix.dest/socless-*.vsix
```

The glob matches the one vsix there, whose file name carries the current
version (`./mill show socless-vscode.vsix` prints its exact path). The vsix is
about 60 MB because it bundles the language server jar (`socless-lsp`, probe
agent inside). Building it needs Node and npm (for `npm install
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

## What the panel shows

The panel is the SoCLess UI in embedded mode (the extension loads it with
`?host=vscode`), trimmed to what VS Code does not already do:

- the tab is titled `SoCLess: <test name>`, so the UI has no logo of its own;
- no Source tab (the editor shows the code, and a click on a box opens it
  there) and no Waveform tab (`Simulate` opens the trace in Surfer). The bottom
  panel keeps Ports and starts closed; its toolbar button opens it;
- no theme toggle or theme gallery: the panel follows the VS Code theme, light
  to the light theme, dark to the dark one, high contrast to the high-contrast
  one and high contrast light to the light one, and switches live when you change the VS Code theme. This is not saved
  as your theme in the browser UI;
- no multi-top design picker, no drop zone for `design.json` / `.fir` files and
  no `share` (HTML snapshot) button: the design always comes from the test you
  viewed. The `svg` and `png` exports stay.

Viewing a test again, or another one, or running `SoCLess: Show design panel`
does not reload the panel: the new design loads in place, and the open panels
and the drill path (while it exists in the new design) are kept.

Breadcrumbs, search, the run status, the panel toggles, the view settings and
the keyboard help work as in the browser. Opened in a browser or from Neovim,
the UI is unchanged.

## Simulate

Next to each `View design` lens, a `Simulate` lens runs the test for real with
waveforms on and opens the trace in [Surfer](https://surfer-project.org/).
View design needs no simulator; Simulate needs Verilator (on the `PATH` the
test sees, as for `./mill <module>.test`) and Surfer.

- The test runs with the module's fork settings, without the probe, with
  `-D<socless.waveFlag>=1` passed to ScalaTest (ChiselSim reads its options
  there). The default `emitVcd` comes with ChiselSim; set `emitFst` or another
  option your project defines. A suite that does not know the option fails
  with "illegal ChiselSim ScalaTest option".
- Waves are kept in `out/socless/sim/<suite>/<test>/` under the workspace root
  (ChiselSim's `build/chiselsim` goes there), emptied at the start of each run
  of that test, with `sim.log` (the test's output) and `surfer.log`.
  `./mill clean` (which empties `out/`) removes them too.
- When the test ends, pass or fail, the newest `*.vcd` or `*.fst` there opens
  in Surfer, and VS Code shows `Simulation passed: <suite> <test>. Waves:
  <path>` (a warning when it failed), or `No waveform found` with the flag to
  check. A test name that matches no test is a failure, and a build or Mill
  problem is reported as `Simulation not run`.
- One simulation at a time: a second one is refused with a message and the
  first goes on; View design keeps working. A simulation is stopped after 30
  minutes. The panel shows `simulating…` while one runs, unless it shows a
  view run or a view error: a simulation never hides those.

See [Simulate in the Neovim guide](ide-neovim.md#simulate) for the exact
command line.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `socless.javaPath` | empty | Java 21+ executable that runs the server. Empty: `$JAVA_HOME/bin/java`, else `java` on the `PATH`. |
| `socless.serverJar` | empty | Server jar to use instead of the bundled one. |
| `socless.waveFlag` | `emitVcd` | ChiselSim option that turns waves on for [Simulate](#simulate), passed as `-D<flag>=1`. |
| `socless.surferCommand` | `surfer` | Viewer Simulate starts, as `<command> <trace>` (an executable). |

`socless.waveFlag` and `socless.surferCommand` apply to the next simulation
without a reload; the other two need one.

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

1. the `View design` lens appears on `GcdSpec.scala`;
2. running it opens a SoCLess webview tab titled after the test and
   `/design.json` serves the GCD design;
3. the cursor in `GCD.scala` makes `/ide.json` report `gcd.GCD`;
4. `POST /open` (what a click in the panel sends) moves the editor to
   `GCD.scala:24`;
5. closing the panel and running the lens again opens exactly one new panel;
6. inside the webview, the UI loads `DeepSoc` from the server, and a real mouse
   click on the `cluster0` box makes the UI send `POST /open` (with the
   iframe's `Origin`, accepted by the server), which moves the editor to the
   `Cluster` class in `DeepSoc.scala`. Before the click it checks the UI is in
   embedded mode (no Source tab, logo or theme toggle, bottom panel closed)
   with the data theme of the VS Code theme;
7. after drilling into `cluster0` and opening the bottom panel, viewing the
   design again and running `SoCLess: Show design panel` do not reload the UI
   (both are kept);
8. after switching the VS Code theme (dark to light), the UI in the webview
   follows it.

The last three checks (steps 7 to 9 in the log, which also counts the webview
tab check) drive the webview over the Chrome DevTools Protocol: `run.sh` starts VS Code
with a free `--remote-debugging-port` and runs
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
- The vsix and the end-to-end test are not run in CI.
- The end-to-end test does not run Simulate; the server's tests cover it (a
  real Verilator run of the GCD test when `verilator` is on the `PATH`).
- Remote workspaces (Remote-SSH, WSL, Codespaces) are not supported for the
  panel: port forwarding changes the `Host` (its port) that reaches the
  server, and the loopback server only accepts `127.0.0.1` or `localhost` on
  its own port. A setup that blocks `127.0.0.1` will not show it either.
