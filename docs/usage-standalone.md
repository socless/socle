# Using the standalone app

SoCLess does not need Mill. The runtime library ships a standalone, Python-free
UI server, so any build that can emit CHIRRTL works in a couple of steps. This is
a first-class way to use SoCLess: the same single-file UI, driven by a small CLI.

## 1. Dump CHIRRTL from your build

Anywhere you can run Chisel:

```scala
import circt.stage.ChiselStage
ChiselStage.emitCHIRRTLFile(new mypkg.MyTop, Array("--target-dir", "."))
// writes MyTop.fir
```

(or `sbt "runMain ..."` a tiny main that does the same).

## 2. Convert + serve with the CLI

Build a single self-contained jar once (`socless.cli.Launcher` is its main
class), then run it anywhere - no Mill, no Python, no design checkout:

```bash
mill socless.assembly                       # one fat jar at out/socless/assembly.dest/out.jar
jar=out/socless/assembly.dest/out.jar

# one-off convert:
java -jar "$jar" --fir-input MyTop.fir --output design.json

# or serve the UI and drag-drop the .fir onto the page (in-process /convert):
java -jar "$jar" serve --dir .              # serves design.json from . if present; --port N to override
```

`serve` extracts the bundled single-file UI, serves it on `127.0.0.1` (loopback
only), and converts dropped `.fir` files in-process. Add `--dir <dir>` to also
serve a `design.json` / `designs.json` from that directory so the UI auto-loads
it. [Using the UI](usage-ui.md) shows how to move around the schematic.

Once the artifact is published to a repository you can resolve, coursier runs the
same main without building the jar (publish first with `mill socless.publishLocal`,
or use a published release; substitute the version you published):

```bash
cs launch io.socless::socless:0.12.0 -M socless.cli.Launcher -- serve --dir .
```

## Live dev loop (save and see)

`serve --watch <file.fir>` re-converts a CHIRRTL file in-process every time it
changes and serves the result at `/design.json`; the UI polls that endpoint and
hot-reloads the schematic on its own (an "updated Ns ago" indicator shows the
loop is live). Pair it with a build that re-dumps the `.fir` on every edit:

```bash
# terminal 1 - your build re-emits MyTop.fir on every source change:
sbt "~runMain mypkg.DumpMain"          # DumpMain calls emitCHIRRTLFile (step 1 above)

# terminal 2 - serve + watch that file:
cs launch io.socless::socless:0.12.0 -M socless.cli.Launcher -- serve --watch MyTop.fir
```

Edit your Chisel, sbt re-dumps `MyTop.fir`, and the open UI reloads within ~1s. A
0-module or mid-write parse is ignored (the last good design stays on screen), so
a save caught mid-flush never flickers the view to an error.

## Loading a design by hand

While any `serve` is running you can also **drag-and-drop** a `.fir` or a
`design.json` onto the page to view it on the fly. The drop converts (for `.fir`)
or loads (for `.json`) in-process.
