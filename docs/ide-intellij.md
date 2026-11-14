# Socle in IntelliJ IDEA

The Socle plugin for IntelliJ IDEA ([socless/socle-intellij](https://github.com/socless/socle-intellij))
is the IntelliJ counterpart of [Socle in VS Code](ide-vscode.md): the same language server, the same
features, and the same settings. A Socle icon in the margin of every ChiselSim test offers
**View design**, which opens the design that test elaborates in a live panel beside the editor, and
**Simulate**, which runs the test with waveforms. The panel follows your cursor and a click in it
jumps to the source line. It is the regular Socle UI inside a JCEF browser tab, served by the
server on `127.0.0.1`.

Requirements: IntelliJ IDEA 2026.2 or newer (the open-source edition is enough) with the JetBrains
Scala plugin and Red Hat's LSP4IJ, Java 21 or newer, and a Mill project with `./mill` at its root.
Your tests and build are never modified, and nothing leaves your machine. To read constructor
arguments, the probe instruments module constructors in memory, at run time, inside the forked
view process only: sources, compiled classes on disk and builds are untouched.

## Install

Install **Socle (Chisel)** from the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35867-socle-chisel-): Settings |
Plugins | Marketplace, search for Socle. Each version is also attached to the
[plugin's releases](https://github.com/socless/socle-intellij/releases) as
`socle-intellij-<version>.zip` (Settings | Plugins | the gear icon | Install Plugin from Disk). To
build it yourself, see the plugin repository's `DEVELOPING.md`.

The plugin bundles the language server jar (`socle-lsp`, with the probe agent and the embedded Surfer
inside), so it is about 70 MB. Each plugin release bundles the server of one Socle release, named
in its release notes; the Server jar setting runs another jar instead. The plugin and the server
talk through LSP (by LSP4IJ) and the `socle/*` methods of [lsp-protocol.md](lsp-protocol.md).

## Use it

1. Open a ChiselSim test, for example `example/test/src/gcd/GcdSpec.scala`. LSP4IJ starts the
   server; once it has listed the project's tests, a Socle icon shows in the margin of each test
   (the same tests as the [VS Code lenses](ide-vscode.md#use-it)).
2. Click the icon and pick **View design**. The panel opens beside the editor. The first run of a
   test compiles it with Mill, which takes a while.
3. Pick **Simulate** to run the test with waveforms (see [Simulate](ide-vscode.md#simulate)).

What the panel shows, the schematic, and the waveforms that follow it are those of VS Code: see
[What the panel shows](ide-vscode.md#what-the-panel-shows),
[The schematic in the IDE view](ide-vscode.md#the-schematic-in-the-ide-view) and
[Waveforms that follow the schematic](ide-vscode.md#waveforms-that-follow-the-schematic).

## The Socle tool window

The Socle tool window (left) has the three views of VS Code's side panel:
- **Tests:** every ChiselSim suite of the project and its tests, with their state (shown,
  running, simulating, failed). View design and Simulate are in the context menu; a double-click
  opens the test. Its toolbar has Refresh, Show Design Panel and Settings.
- **Hierarchy:** the instance tree of the design in the panel. Selecting a row takes the schematic
  there; Open Source is in the context menu.
- **Details:** what is selected in the schematic: its Scala class, parameters and ports.

Hierarchy and Details empty when the panel closes. **Tools | Socle** has Show Design Panel, Show
Waveforms, Refresh Tests and Settings.

## The embedded Surfer

With the wave viewer set to `surfer-panel`, Simulate opens Surfer in an editor tab of its own,
driven by the panel as in [VS Code](ide-vscode.md#the-embedded-surfer). Tools | Socle | Show
Waveforms reopens it on the last Simulate's trace.

## Settings

Settings | Tools | Socle has the settings of the [VS Code table](ide-vscode.md#settings), with the
same meaning and defaults (`socle.waveFlag` there is Wave flag here, and so on). The Java path and
the server jar take effect when the server restarts (LSP4IJ's Language Servers view, or reopen the
project); the others apply at once.

If Java is missing or older than 21, one notification names the setting and the server does not
start. A server that stops is started again at most three times in three minutes. Each project runs
its own server, and nothing starts in an untrusted project.

## Differences from VS Code

- A margin icon on each test instead of code lenses.
- A tool window instead of the activity bar views.
- The Surfer tab opens in a split to the right of the active editor.
- Without JCEF the design opens in your browser, and Hierarchy and Details stay empty.

## Known limits

- The plugin's headless end-to-end check (`e2e/run.sh`, see its `DEVELOPING.md`) covers the
  server start, the test list and a view run, not the panel's content (JCEF may not start under
  Xvfb); the panel, the tool window and the Surfer tab are checked by hand (`e2e/MANUAL.md`).
- Remote development (Gateway) is not supported for the panel, for the reason given in the
  [VS Code limits](ide-vscode.md#known-limits).
