# The language server's protocol

This page is for client authors. Three clients live in their own repositories:
[socless/socle-vscode](https://github.com/socless/socle-vscode),
[socless/socle-intellij](https://github.com/socless/socle-intellij) and
[socless/socle.nvim](https://github.com/socless/socle.nvim). Each pins a server release, so the
surface below is an interface between repositories. A change to it is called out in the
CHANGELOG.

The server (`socle-lsp-<version>.jar`) speaks LSP over stdio. Beyond standard LSP it uses the
code lenses below, two commands, the custom methods below, and a set of settings. The panel is a
web page the server serves on `127.0.0.1`: a client that hosts it in a webview also talks to it
through URL parameters and `postMessage` messages, described at the end.

What a client must do, and what is optional:
- **Required:** run the lens commands (`workspace/executeCommand`) and handle `window/showDocument`
  (the panel's click-to-source). A client that sets `panel: "client"` must also handle
  `socle/openPanel`, or no panel shows.
- **Optional:** `panel: "client"` itself (without it the server opens the panel in the browser),
  `socle/cursor`, `socle/tests`, `socle/status`, `socle/testsChanged`, the settings, file watching,
  and every webview item at the end of this page. A host that frames the UI with `host=vscode`
  must relay the Surfer messages when it offers the `surfer-panel` viewer.

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

`socle/tests` is answered for any client; only the notifications below depend on
`panel: "client"`.

## Server to client

These are sent only to a client that set `panel: "client"`.

| Method | Params | Meaning |
|---|---|---|
| `socle/openPanel` | `{url, test}` | Show the live UI served at `url` (`http://127.0.0.1:<port>/`, no query: the client adds the [URL parameters](#url-parameters) it needs); `test` titles the panel. Sent on every view and Simulate |
| `socle/status` | `{status, suite, test, message}` | The run state changed (`status`: `idle`, `running`, `simulating`, `ok` or `error`, as in `/ide.json`); `suite` is the suite's FQCN, or empty. Not sent for a change of the cursor's focus |
| `socle/testsChanged` | none | The workspace's tests changed after a save or a watched change: ask `socle/tests` again |

Standard LSP messages the server sends:

| Method | When |
|---|---|
| `window/showDocument` `{uri, external: true}` | A client without `panel: "client"`, on the first view only: `uri` is `http://127.0.0.1:<port>/?panel=1`. When the client fails or declines it, the server opens the URL with the desktop's opener (`xdg-open`, `open` or `rundll32`) |
| `window/showDocument` `{uri, takeFocus: true, selection}` | Any client: the panel asked to open a source (`POST /open`, a click on a box), or the Surfer app's Go to declaration. `uri` is a `file:` URI under the Mill project root or a source of the shown design; `selection` is an empty range at the start of the line |
| `window/showMessage` | Any client: a failed view or command, a simulation's outcome (Info on pass, Warning otherwise), a refused or replaced simulation, an invalid `waveFlag` |
| `client/registerCapability` | After `initialized`, when the client advertises `workspace.didChangeWatchedFiles.dynamicRegistration`: a watcher for `**/*.scala` |

With that watcher the server expects `workspace/didChangeWatchedFiles` for changes made outside
the editor. The server runs Mill in the project's root: the `build.mill` at or above the workspace
folder, else the only one at most three levels below it.

## Settings

The server reads its settings from `initializationOptions` (flat) and from the `socle` section of
`workspace/didChangeConfiguration`.

- **Simulation and waveforms:**
  - `waveFlag`, `firtoolOpts`, `waveViewer`, `waveViewerCommand`, `surferCommand`;
  - `wavesFollowSelection`, `wavesShowClockReset`, `wavesFoldBundles`, `wavesShowWires`,
    `wavesColorByDirection`, `wavesFollowDelayMs`.
- **Schematic defaults:**
  - `schematicColorizeComponents`, `schematicColorizeWires`, `schematicDensity`,
    `schematicLabelSize`;
  - `schematicAutoOptimize`, `schematicHintBar`, `schematicShowLayers`, `schematicHide`;
  - `followCursor`.

Values and defaults are those of the VS Code settings of the same names (`socle.<key>`), listed in
[ide-vscode.md](ide-vscode.md). An invalid value keeps the default; an empty `firtoolOpts`
turns its default off.

## The panel's HTTP endpoint

The UI the panel shows is served on `127.0.0.1`, on a new port for each server, at the URL
`socle/openPanel` gives. The UI uses these routes, so clients don't call them directly. Only
requests whose `Host` is `127.0.0.1:<port>` or `localhost:<port>` are served (else 403).

| Route | Gives |
|---|---|
| `GET /` or `/socle.html` | The UI page, with `Content-Security-Policy: frame-ancestors 'self' vscode-webview: vscode-file:` |
| `GET /design.json` | The last design viewed (its `Last-Modified` changes with each one); 404 before the first |
| `GET /ide.json` | The panel state: `status`, `message`, `focusClass`, `test`, `suite`, `stamp`, `waveViewer`, `trace`, `traceTest`, `waveSettings`, `reveal`, `schematicSettings` |
| `POST /open` | `{file, line}` (`line` 1-based): open that source in the editor (`window/showDocument` above); 204 |
| `GET /wave/<id>/trace` | The last simulation's trace; `trace` in `/ide.json` is this path. Any other id is a 404 |
| `GET /wave/<id>/names` | `{prefix, names}`: the trace's signal names, sorted |
| `GET /surfer/surfer.js`, `/surfer/surfer_bg.wasm` | The Surfer WASM build bundled in the server jar, for the embedded viewer |
| `POST /wave/select` | `{traceTest, instPath, selection}` (`selection` a string or null): the panel's selection, which the Surfer app follows with the `surfer` viewer; 204 |

Both POSTs need `Content-Type: application/json` (else 415) and, when an `Origin` is sent, the
page's own origin (else 403); their body is at most 64 KiB.

## The UI page in a client's webview

### URL parameters

| Parameter | Effect |
|---|---|
| `host=vscode` | Embedded mode: the UI drops its logo, Source tab, design picker, theme controls, drop zone, share button, hierarchy and inspector panes, and opens the message channel below. The VS Code extension and the IntelliJ plugin load the panel this way |
| `theme=<kind>` | With `host=vscode`: the starting theme, `light`, `dark` or `hc` (high contrast) |
| `view=surfer` | With `host=vscode`: the page is only Surfer (the Surfer tab), driven by `socle-surfer-cmd` |
| `panel=1` | An editor's browser panel (the server adds it when it opens the browser itself, and a client that opens `socle/openPanel`'s URL in a browser should add it): no first-time tips, and closing the hint bar is not remembered. `host=vscode` implies it |

### Messages

The channel is `window.postMessage` between the UI page and the page that frames it; every message
is an object with a `type`. The UI opens the channel only with `host=vscode` and when the parent's
origin (`location.ancestorOrigins`) is a VS Code webview (`vscode-webview://...`) or a loopback
page (`http(s)://` on `localhost`, `127.0.0.1` or `[::1]`, any port). It posts only to that exact
origin and takes messages only from the parent window with that origin; `socle-theme` is checked
against the parent window only.

From the UI to its host:

| `type` | Fields | When |
|---|---|---|
| `socle-state` | `path`, `selection`, `details`, and `design` and `classes` when the design changed | Once per change of the design, the drill path or the selection |
| `socle-surfer-open` | `trace` (absolute URL), `traceTest` (`<suite>#<test>`), `force` | `surfer-panel` viewer: a new trace (`force: false`: open the Surfer tab unless it was opened for that trace before) or the Show Surfer button (`force: true`) |
| `socle-surfer-cmd` | `cmd` | `surfer-panel` viewer: a command for the Surfer tab, which the host relays unchanged |

- `path` is the drill path: the top module's name, then instance names (or an open block's id).
  `selection` is the selected id, or null.
- `details` (or null) describes the selected instance, else the module in view: `name`, `module`,
  `scalaClass`, `kind`, `params` (`[{name, type, value}]`), `ports` (`[{name, dir, type}]`), and
  `copies` (how many generator copies it stands for) when it is a folded box.
- `design` (or null without a design) is the hierarchy: `top` and `modules`, each
  `{name, scalaClass, kind, source, children}`. `source` is `{file, line}` when the editor can
  open it, else null; each child is `{name, type}`, plus `foldGroup` (its fold group's id) for a
  generator copy.
- `classes` is the class view (the UI's Hierarchy Classes tab), sent with `design` and only then:
  the design's modules by Scala class, then by parameterization, then their instances. It is an
  empty array without a design, or for a design whose modules carry no Scala class (export mode).
  User classes come first, then library classes (`chisel3.`...), each by full class name;
  parameterizations come in design order; a parameterization's instances come module by module
  (design order), each module's instances in hierarchy order (depth first).
  - A class is `{scalaClass, name, library, modules, parameterizations}`: `scalaClass` the full
    name, `name` the short label the UI shows, `library` true for a Chisel library class,
    `modules` how many modules the class built.
  - A parameterization is `{label, unknown, params, instances}`: the modules of the class built
    with the same arguments (say "same arguments", not "same hardware": two modules with the same
    arguments are grouped whatever they elaborate to). `label` is `(nCores = 2)`, or
    `(no parameters)`; `params` is `[{name, tpe, value}]` in declaration order. `unknown` is true
    when some value could not be read (a function, say): it is then not compared, and the
    parameterization holds exactly one module, so two such entries may carry the same `label`. The
    UI marks it with a `?`. Every class has at least one parameterization; when a class has a
    single one without parameters, the UI lists its instances directly under the class.
  - An instance is `{path, label, module}`: `path` its drill path, in the form of `socle-state`'s
    `path` (the top module's name, then instance names); `label` the path below the top
    (`cluster0/cores_0`), or the top module's name for the top; `module` the module it
    instantiates. To show an instance, a host posts `{"type": "socle-navigate", "path": <path>}`;
    a generator copy folded away in the schematic is reached the same way.

  The captured DeepSoc (`./mill example.socleCapturedDeepSoc`), cut to two of its six classes:

  ```json
  "classes": [
    {"scalaClass": "deephier.Cluster", "name": "Cluster", "library": false, "modules": 2,
     "parameterizations": [
       {"label": "(nCores = 2)", "unknown": false,
        "params": [{"name": "nCores", "tpe": "Int", "value": "2"}],
        "instances": [
          {"path": ["DeepSoc", "cluster0"], "label": "cluster0", "module": "Cluster"},
          {"path": ["DeepSoc", "cluster1"], "label": "cluster1", "module": "Cluster_1"}]}]},
    {"scalaClass": "deephier.Core", "name": "Core", "library": false, "modules": 4,
     "parameterizations": [
       {"label": "(no parameters)", "unknown": false, "params": [],
        "instances": [
          {"path": ["DeepSoc", "cluster0", "cores_0"], "label": "cluster0/cores_0", "module": "Core"},
          {"path": ["DeepSoc", "cluster0", "cores_1"], "label": "cluster0/cores_1", "module": "Core_1"},
          {"path": ["DeepSoc", "cluster1", "cores_0"], "label": "cluster1/cores_0", "module": "Core_2"},
          {"path": ["DeepSoc", "cluster1", "cores_1"], "label": "cluster1/cores_1", "module": "Core_3"}]}]}
  ]
  ```
- `cmd` is one of `{"$type": "Load", url}`, `{"$type": "Show", seq, plan, path, trace}`,
  `{"$type": "Focus", trace}`, `{"$type": "Add", traces, after}` and `{"$type": "Hello"}`.

From the host to the UI:

| `type` | Fields | Effect |
|---|---|---|
| `socle-navigate` | `path` (non-empty array of strings), `select` (string, null or absent) | Show `path` and select `select` there: an instance, or a fold group's id (its folded box, or its first copy when the group is unfolded). A copy folded away is unfolded first |
| `socle-theme` | `kind`: `light`, `dark` or `hc` | Switch the theme |
| `socle-surfer-status` | `status` | The Surfer tab's state, relayed unchanged |
| `socle-surfer-closed` | none | There is no Surfer tab (it was closed, or none answered `Hello`) |

The Surfer tab page (`?host=vscode&view=surfer`) takes `socle-surfer-cmd` and `socle-theme`, and
posts `socle-surfer-status` on every change of its state, on `Hello` and when a `Show` completes.
`status` is `{state, loadedTrace, shownSeq, dropped, shownSeqFailed, event}`: `state` is `idle`,
`booting`, `loading`, `ready` or `error`; `shownSeq` is the completed `Show`'s `seq`, with
`dropped` (how many signals Surfer lacked) or `shownSeqFailed`; `event` (`{kind, variable}`,
`kind` being `goto_declaration`, `add_drivers` or `add_loads`) is a right-click action chosen in
that Surfer.
