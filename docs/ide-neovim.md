# Socle in Neovim

`socle-lsp` is a small language server for Chisel projects built with Mill. It
adds a **View design** code lens above every ChiselSim scalatest test. Running
the lens elaborates that test's design in view mode (the
[probe run contract](how-it-works.md#view-mode-probe-run-contract)) and shows it
in a live panel in your browser. Saving a `.scala` file refreshes the panel, the
panel follows your cursor, and clicking a box in the panel jumps to its source.

It does not replace Metals: it offers no diagnostics, completion, hover or
go-to-definition, so run both side by side (see [With Metals](#with-metals)).

Using VS Code? See [Socle in VS Code](ide-vscode.md). IntelliJ IDEA? See [Socle in IntelliJ IDEA](ide-intellij.md).

Requirements: Neovim 0.12, Java 21 or newer on `PATH`, and a Mill project with
`./mill` at its root (the server asks Mill for the test classpath and fork
settings of the file the lens is in); [Simulate](#simulate) also needs
Verilator, and the Surfer app unless `waveViewer` is `surfer-panel`. Your
tests and build are never modified.

## Install the plugin

[socle.nvim](https://github.com/socless/socle.nvim) sets all of this up from
your package manager: it downloads the server jar of a pinned Socle release
(checked against the release's `SHA256SUMS`), starts it on Scala files of Mill
projects, turns the lenses on, follows the cursor, opens the panel and adds
`:Socle view|simulate|panel|update|log|health` and `:checkhealth socle`.
With lazy.nvim / LazyVim:

```lua
{
  "socless/socle.nvim",
  ft = "scala",
  opts = {},
}
```

Its README has the snippets for `vim.pack`, mini.deps, packer and vim-plug and
every option (the Java executable, a local `server_jar` or another `version`,
how the panel opens, and the wave settings below in snake_case). The rest of
this page sets the server up by hand, without the plugin.

## Build the server

```bash
./mill socle-lsp.assembly
cp out/socle-lsp/assembly.dest/out.jar ~/.local/share/socle/socle-lsp.jar
```

The jar is self-contained (`java -jar`, the probe agent inside). Copy it out of
`out/`, which Mill rewrites on every build.

## Configure Neovim

In your `init.lua`:

```lua
vim.lsp.config('socle', {
  cmd = { 'java', '-jar', vim.fn.expand('~/.local/share/socle/socle-lsp.jar') },
  filetypes = { 'scala' },
  root_markers = { 'build.mill' },
})
vim.lsp.enable('socle')

-- Show the "View design" lenses (Neovim 0.12 refreshes them by itself).
vim.api.nvim_create_autocmd('LspAttach', {
  callback = function(args)
    local client = vim.lsp.get_client_by_id(args.data.client_id)
    if client and client.name == 'socle' then
      vim.lsp.codelens.enable(true, { bufnr = args.buf, client_id = client.id })
    end
  end,
})

-- The panel focuses the class under the cursor (lines are 0-based in LSP).
vim.api.nvim_create_autocmd('CursorHold', {
  pattern = '*.scala',
  callback = function(args)
    for _, client in ipairs(vim.lsp.get_clients({ bufnr = args.buf, name = 'socle' })) do
      client:notify('socle/cursor', {
        uri = vim.uri_from_bufnr(args.buf),
        line = vim.api.nvim_win_get_cursor(0)[1] - 1,
      })
    end
  end,
})
```

`vim.lsp.codelens.refresh()` from older guides is deprecated in 0.12;
`vim.lsp.codelens.enable(true)` replaces it.

## Use it

1. Open a ChiselSim test, for example `example/test/src/gcd/GcdSpec.scala` in
   this repo. A `View design` lens appears on each test (`it("...")`,
   `test("...")`, `"X" should "y" in`, `behavior of "X"` then
   `it should "y" in`; not on `ignore` tests, tests named in a loop, or tests
   registered inside a `def`, except FunSuite's `test("...")`, whose name
   does not depend on where the `def` is called) of a suite that mixes in
   `ChiselSim`, directly or through a base class or trait of the workspace,
   or whose body calls `simulate(`, only in a Mill project (a `build.mill`
   above the file).
2. Put the cursor on the lens line and run it with `grx` (the 0.12 default
   mapping) or `:lua vim.lsp.codelens.run()`.
3. The first view opens the panel at `http://127.0.0.1:<port>/?panel=1` through
   `vim.ui.open` (your desktop browser). The first run of a test module also
   compiles it with Mill, so it can take a while; later views take seconds.
   Until that first run succeeds the panel shows "waiting for the first design…".
4. Edit and `:w`: the last viewed test runs again and the panel refreshes. If the
   build or the elaboration fails, the panel should show the error and keep the
   last good design (covered by the server's tests, not yet exercised in this
   Neovim setup).
5. Move the cursor in a Chisel source: after `updatetime` the panel focuses the
   class under it.
6. Click a box in the panel: Neovim opens its source file at that line (only
   files under the workspace root, or sources of the shown design).

The panel listens on `127.0.0.1` only, on a new port for each server. Quitting
Neovim stops the server and the panel.

The browser panel draws the same schematic as the VS Code panel: logic grouped
into FSM, generator-loop and `logic` blocks, an FSM block's state diagram,
folded generator copies and tied-port stubs. See
[The schematic in the IDE view](ide-vscode.md#the-schematic-in-the-ide-view)
in the VS Code guide, and [Navigating the
schematic](usage-ui.md#navigating-the-schematic) for the mouse and the keys.

## Simulate

Each test with a `View design` lens also gets a `Simulate` lens. It runs that
test for real, with waveforms on, and shows the trace in
[Surfer](https://surfer-project.org/), following the panel's selection (see
[Waveforms that follow the schematic](#waveforms-that-follow-the-schematic)).
View design needs no simulator; Simulate needs Verilator on the `PATH` the
test sees (the server's, with Mill's `allForkEnv` `PATH` on top), as
`./mill <module>.test` does.

- Simulate first views the same test, as the `View design` lens does: the
  panel shows that test's design (the first view opens it in your browser).
  Then the simulation runs.
- The test JVM is forked with the same settings as a view run (see
  [How a view run is forked](#how-a-view-run-is-forked)), without the probe,
  plus the wave option as a ScalaTest config-map entry: `-D<waveFlag>=1` after
  `org.scalatest.tools.Runner`, which is where ChiselSim reads its options.
  `emitVcd` (the default) comes with ChiselSim; a project can define its own,
  such as `emitFst`. A suite that does not know the option fails with
  "illegal ChiselSim ScalaTest option".
- The run's working directory, and ChiselSim's `build/chiselsim` (through
  `-Dchisel.project.root`, which wins over the same option in `forkArgs`), is
  `out/socle/sim/<suite>/<test>/` under the workspace root. It is emptied at
  the start of each run, so the last run of each test is kept; `sim.log` there
  holds the test's output, `surfer.log` the viewer's. `./mill clean` (which
  empties `out/`) removes the kept waves too.
- When the test ends, pass or fail, the newest `*.vcd` or `*.fst` there goes
  to the viewer `waveViewer` names: by default the Surfer app, started as
  `<surferCommand> --wcp-initiate <port> <trace>`. Neovim then shows
  `Simulation passed: <suite> <test>. Waves: <path>` (a warning when it failed),
  or `No waveform found` with the flag to check. A test name that matches no
  test is a failure (`No test named "<test>" ran in <suite>.`), and a build or
  Mill problem is reported as `Simulation not run`.
- One simulation at a time: a second `Simulate` while one runs is refused with
  a message, and the first goes on. View design keeps working meanwhile. A
  simulation is stopped after 30 minutes. The panel shows `simulating…` while
  one runs, unless it shows a view run or a view error: a simulation never
  hides those.
- An invalid `waveFlag` (not letters, digits, `_`, `.` or `-`) is reported once
  as a warning, and `emitVcd` is used.

The settings go in `init_options` (defaults: `waveFlag = "emitVcd"`,
`surferCommand = "surfer"`, the Surfer app's executable). The wave viewer keys
use the same names as VS Code without the `socle.` prefix: `waveViewer`
(`surfer`, `surfer-panel`, `custom`), `waveViewerCommand`,
`wavesFollowSelection`, `wavesShowClockReset`, `wavesFoldBundles`,
`wavesShowWires`, `wavesColorByDirection` and `wavesFollowDelayMs`. They have
the same defaults and meanings as in VS Code (see its
[settings table](ide-vscode.md#settings)).

```lua
vim.lsp.config('socle', {
  cmd = { 'java', '-jar', vim.fn.expand('~/.local/share/socle/socle-lsp.jar') },
  filetypes = { 'scala' },
  root_markers = { 'build.mill' },
  init_options = { waveFlag = "emitFst", waveViewer = "surfer-panel" },
})
```

## Waveforms that follow the schematic

After a simulation, the module you drill into in the browser panel decides
which signals Surfer shows: its ports in declaration order (grouped by port in
the embedded viewer), then its registers and wires, coloured by direction. A smaller selection (a port, a wire, a logic or
FSM block) moves Surfer's focus. With `waveViewer = "surfer"` that is the
Surfer app; with `"surfer-panel"` it is Surfer embedded in the panel's
Waveform tab. With `"custom"`, `waveViewerCommand` runs on the trace (`{file}`
is its path; the command is split on whitespace, with no quoting) and nothing
follows. The Surfer app needs `--wcp-initiate` (tested with Surfer 0.7.0); one
that exits without connecting gets a warning pointing at `surfer.log` and is
started again as `<surferCommand> <trace>`, without following. The behaviour
and the known limits (Surfer answers only while it is drawn, so on Wayland the
app is started under XWayland when available;
the app cannot scroll to a focused signal) are described in the
[VS Code guide](ide-vscode.md#waveforms-that-follow-the-schematic).

The embedded viewer is Surfer v0.7.0, shipped in the server jar under its own
licence, the EUPL-1.2; `socle-lsp/vendor/surfer/SOURCE.md` says where it
comes from and how to rebuild it.

In the browser panel the Waveform tab is always there. In `surfer` mode it
says the waveforms are in the Surfer app, and how many of the module's
signals the trace lacks. With `surfer-panel`, the **● colours** chip beside
Surfer's status says what each colour means
([Waveform colours](usage-ui.md#waveform-colours)).

## How a view run is forked

The server forks the test JVM the way `./mill <module>.test` does, with the
settings of the test module that owns the file, read in one Mill call
(`show <m>.{runClasspath,forkArgs,allForkEnv,forkWorkingDir,testSandboxWorkingDir,javaHome}`):

- the module's JDK when it pins one (`javaHome`, for example from `jvmId`),
  else the server's own Java;
- the module's run classpath and its `forkArgs` (so `-D` config properties
  reach your design);
- the server's environment plus Mill's `allForkEnv` for the module: its
  `forkEnv`, `MILL_WORKSPACE_ROOT` (so config read from
  `$MILL_WORKSPACE_ROOT/config/...` resolves), `MILL_TEST_RESOURCE_DIR` and a
  `PATH` with the module's JDK first, exactly as Mill passes them to tests;
- a fresh temporary working directory, as Mill's default test sandbox; with
  `testSandboxWorkingDir = false` the module's `forkWorkingDir` instead.

The probe's own options come after `forkArgs` on the command line, so they win
over any conflicting `-D` there. A module without `testSandboxWorkingDir` (a
`test` module that is not a `TestModule`) runs in a fresh directory, and one
without `javaHome` on the server's Java; both keep the rest of their settings.
A module without the other tasks (a custom module, an older Mill) is run with
its classpath and `MILL_WORKSPACE_ROOT` only, and the server logs it.

The server runs Mill in the project's root: the `build.mill` at or above the
editor's workspace folder, else the only one at most three levels below it
(one workspace, one Mill build). Lenses on suites that extend a project base
trait come from an index of the project's Scala files, which leaves out `out`,
`target`, `node_modules`, dot-directories and the test output at the root
(`build`, `test_run_dir`). The index follows saves, and changes made outside
the editor (switching branches) when the client can watch files for the
server.

## With Metals

Not yet tested together with nvim-metals. Both clients are expected to attach
to the same Scala buffers side by side: Socle offers only its lenses, its
command, save handling and `socle/cursor`, no diagnostics, completion, hover
or definition.
