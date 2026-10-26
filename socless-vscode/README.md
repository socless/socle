# SoCLess for VS Code

Chisel IDE support for Scala files: a "View design" lens above ChiselSim tests opens the elaborated
design in a panel beside the editor, and the schematic follows your cursor. The SoCLess side panel
(activity bar) lists the workspace's tests (View design and Simulate on each), the design's instance
hierarchy and the details of the selection.

## Requirements

- Java 21 or newer (set `socless.javaPath` if `java` on your PATH is older).
- A Mill project that contains the Chisel design.
- ChiselSim tests: the lens runs the test and captures the design it elaborates.

## Settings

- `socless.javaPath`: the Java executable used to run the language server.
- `socless.serverJar`: use a custom language server jar instead of the bundled one.

Check the extension's Settings page for the full list and defaults.

## Third-party software

The bundled language server ships [Surfer](https://surfer-project.org/) v0.7.0,
built to WebAssembly, for the panel's Waveform tab. Surfer is a separate work
licensed under the EUPL-1.2; its licence text and source pointer ship beside it
in the server jar (`surfer/LICENSE-EUPL-1.2.txt`, `surfer/SOURCE.md`).

## Install the vsix

Build it with `./mill socless-vscode.vsix`, then either run
`code --install-extension out/socless-vscode/vsix.dest/socless-<version>.vsix`
or use "Extensions: Install from VSIX..." in VS Code.
