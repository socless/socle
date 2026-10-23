# SoCLess for VS Code

Chisel IDE support for Scala files: a "View design" lens above ChiselSim tests opens the elaborated
design in a panel beside the editor, and the schematic follows your cursor.

## Requirements

- Java 21 or newer (set `socless.javaPath` if `java` on your PATH is older).
- A Mill project that contains the Chisel design.
- ChiselSim tests: the lens runs the test and captures the design it elaborates.

## Settings

- `socless.javaPath`: the Java executable used to run the language server.
- `socless.serverJar`: use a custom language server jar instead of the bundled one.

Check the extension's Settings page for the full list and defaults.

## Install the vsix

Build it with `./mill socless-vscode.vsix`, then either run
`code --install-extension out/socless-vscode/vsix.dest/socless-<version>.vsix`
or use "Extensions: Install from VSIX..." in VS Code.
