# SoCLess in Neovim

`socless-lsp` is a small language server for Chisel projects built with Mill. It
adds a **View design** code lens above every ChiselSim scalatest test. Running
the lens elaborates that test's design in view mode (the
[probe run contract](how-it-works.md#view-mode-probe-run-contract)) and shows it
in a live panel in your browser. Saving a `.scala` file refreshes the panel, the
panel follows your cursor, and clicking a box in the panel jumps to its source.

It does not replace Metals: it offers no diagnostics, completion, hover or
go-to-definition, so run both side by side (see [With Metals](#with-metals)).

Using VS Code? See [SoCLess in VS Code](ide-vscode.md).

Requirements: Neovim 0.12, Java 21 or newer on `PATH`, and a Mill project with
`./mill` at its root (the server asks Mill for the test classpath and fork
settings of the file the lens is in). Your tests and build are never modified.

## Build the server

```bash
./mill socless-lsp.assembly
cp out/socless-lsp/assembly.dest/out.jar ~/.local/share/socless/socless-lsp.jar
```

The jar is self-contained (`java -jar`, the probe agent inside). Copy it out of
`out/`, which Mill rewrites on every build.

## Configure Neovim

In your `init.lua`:

```lua
vim.lsp.config('socless', {
  cmd = { 'java', '-jar', vim.fn.expand('~/.local/share/socless/socless-lsp.jar') },
  filetypes = { 'scala' },
  root_markers = { 'build.mill' },
})
vim.lsp.enable('socless')

-- Show the "View design" lenses (Neovim 0.12 refreshes them by itself).
vim.api.nvim_create_autocmd('LspAttach', {
  callback = function(args)
    local client = vim.lsp.get_client_by_id(args.data.client_id)
    if client and client.name == 'socless' then
      vim.lsp.codelens.enable(true, { bufnr = args.buf, client_id = client.id })
    end
  end,
})

-- The panel focuses the class under the cursor (lines are 0-based in LSP).
vim.api.nvim_create_autocmd('CursorHold', {
  pattern = '*.scala',
  callback = function(args)
    for _, client in ipairs(vim.lsp.get_clients({ bufnr = args.buf, name = 'socless' })) do
      client:notify('socless/cursor', {
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
   `it should "y" in`; not on `ignore` tests) of a suite that mixes in
   `ChiselSim`, directly or through a base class or trait of the workspace,
   or whose body calls `simulate(`, only in a Mill project (a `build.mill`
   above the file).
2. Put the cursor on the lens line and run it with `grx` (the 0.12 default
   mapping) or `:lua vim.lsp.codelens.run()`.
3. The first view opens the panel at `http://127.0.0.1:<port>/` through
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

## How a view run is forked

The server forks the test JVM the way `./mill <module>.test` does, with the
settings of the test module that owns the file, read in one Mill call
(`show <m>.{runClasspath,forkArgs,allForkEnv,forkWorkingDir,testSandboxWorkingDir}`):

- the module's run classpath and its `forkArgs` (so `-D` config properties
  reach your design);
- the server's environment plus Mill's `allForkEnv` for the module: its
  `forkEnv`, `MILL_WORKSPACE_ROOT` (so config read from
  `$MILL_WORKSPACE_ROOT/config/...` resolves), `MILL_TEST_RESOURCE_DIR` and a
  `PATH` with the module's JDK first, exactly as Mill passes them to tests;
- a fresh temporary working directory, as Mill's default test sandbox; with
  `testSandboxWorkingDir = false` the module's `forkWorkingDir` instead.

The probe's own options come after `forkArgs` on the command line, so they win
over any conflicting `-D` there. A module without these tasks (a custom
module, an older Mill) is run with its classpath and `MILL_WORKSPACE_ROOT`
only, and the server logs it.

## With Metals

Not yet tested together with nvim-metals. Both clients are expected to attach
to the same Scala buffers side by side: SoCLess offers only its lenses, its
command, save handling and `socless/cursor`, no diagnostics, completion, hover
or definition.
