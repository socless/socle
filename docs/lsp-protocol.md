# The language server's protocol

This page is for client authors. Three clients live in their own repositories:
[socless/socle-vscode](https://github.com/socless/socle-vscode),
[socless/socle-intellij](https://github.com/socless/socle-intellij) and
[socless/socle.nvim](https://github.com/socless/socle.nvim). Each pins a server release, so the
surface below is an interface between repositories. A change to it is called out in the
CHANGELOG.

The server (`socle-lsp-<version>.jar`) speaks LSP over stdio. Beyond standard LSP it uses the
code lenses below, two commands, the custom methods below, and a set of settings.

## Code lenses and commands

Each ChiselSim test gets two lenses, **View design** and **Simulate**. Their commands run through
`workspace/executeCommand` with the arguments `[uri, suite, test]`:

| Command | Does |
|---|---|
| `socle.viewDesign` | Elaborates the test's design (no simulation) and shows it in the panel |
| `socle.simulate` | Runs the test with waveforms, then opens the trace in the wave viewer |

## Initialization options

`initializationOptions` is a flat object:
- **`panel: "client"`:** the client hosts the panel (VS Code's webview, IntelliJ's JCEF tab). The server then sends
  `socle/openPanel` and the other notifications below instead of opening a browser.
- **Settings:** any of the keys listed under "Settings".

## Client to server

| Method | Kind | Params | Result |
|---|---|---|---|
| `socle/cursor` | notification | `{uri, line}` (0-based) | The class around the cursor becomes the panel's focus |
| `socle/tests` | request | none | `{suites: [{suite, file (uri), tests: [{name, line}]}]}`, `line` 0-based |

## Server to client

These are sent only to a client that set `panel: "client"`.

| Method | Params | Meaning |
|---|---|---|
| `socle/openPanel` | `{url, test}` | Show the live UI served at `url` (loopback); `test` titles the panel |
| `socle/status` | `{status, suite, test, message}` | The run state changed (`status`: as in `/ide.json`); `suite` is the suite's FQCN, or empty |
| `socle/testsChanged` | none | The workspace's tests changed after a save: ask `socle/tests` again |

## Settings

The server reads its settings from `initializationOptions` (flat) and from the `socle` section of
`workspace/didChangeConfiguration`.

- **Simulation and waveforms:**
  - `waveFlag`, `waveViewer`, `waveViewerCommand`, `surferCommand`;
  - `wavesFollowSelection`, `wavesShowClockReset`, `wavesFoldBundles`, `wavesShowWires`,
    `wavesColorByDirection`, `wavesFollowDelayMs`.
- **Schematic defaults:**
  - `schematicColorizeComponents`, `schematicColorizeWires`, `schematicDensity`,
    `schematicLabelSize`;
  - `schematicAutoOptimize`, `schematicHintBar`, `schematicShowLayers`, `schematicHide`;
  - `followCursor`.

Values and defaults are those of the VS Code settings of the same names (`socle.<key>`), listed in
[ide-vscode.md](ide-vscode.md). An invalid value keeps the default.

## The panel's HTTP endpoint

The UI the panel shows is served on `127.0.0.1`, at the URL `socle/openPanel` gives. Its JSON
endpoints are `/design.json`, `/ide.json` and `POST /open`. The UI uses them, so clients don't
call them directly.
