# Using the standalone app

Socle does not need Mill. The runtime library ships a standalone, Python-free
UI server, so any build that can emit CHIRRTL works in a couple of steps. This is
a first-class way to use Socle: the same single-file UI, driven by a small CLI.

## 1. Dump CHIRRTL from your build

Anywhere you can run Chisel:

```scala
import circt.stage.ChiselStage
ChiselStage.emitCHIRRTLFile(new mypkg.MyTop, Array("--target-dir", "."))
// writes MyTop.fir
```

(or `sbt "runMain ..."` a tiny main that does the same).

## 2. Convert + serve with the CLI

Download the self-contained jar, `socle-<version>.jar`, from the
[releases page](https://github.com/socless/socle/releases) (check it against
the release's `SHA256SUMS`), then run it anywhere with Java - no Mill, no
Python, no design checkout:

```bash
v=<version>                               # a release, for example the latest
curl -fLO https://github.com/socless/socle/releases/download/v$v/socle-$v.jar
jar=socle-$v.jar

# one-off convert:
java -jar "$jar" --fir-input MyTop.fir --output design.json

# or serve the UI and drag-drop the .fir onto the page (in-process /convert):
java -jar "$jar" serve --dir .              # serves design.json from . if present; --port N to override
```

`serve` extracts the bundled single-file UI, serves it on `127.0.0.1` (loopback
only), and converts dropped `.fir` files in-process. Add `--dir <dir>` to also
serve a `design.json` / `designs.json` from that directory so the UI auto-loads
it. [Using the UI](usage-ui.md) shows how to move around the schematic.

The server answers only requests addressed to it (a guard against DNS
rebinding): `localhost` or `127.0.0.1` on any port, so an `ssh -L
9000:localhost:8765` tunnel works, or the `--host` you gave (bound to
`0.0.0.0`: the machine's IP) with the port it listens on. Reaching it under
another host name, such as a DNS name, gets `403 forbidden host`.

To build the same jar from source instead (`socle.cli.Launcher` is its main
class), in a checkout of this repository:

```bash
./mill socle.assembly                     # one fat jar at out/socle/assembly.dest/out.jar
jar=out/socle/assembly.dest/out.jar
```

The runtime library is not published to Maven Central, so coursier can run the
same main only after `./mill socle.publishLocal` in that checkout (use the
version it published):

```bash
cs launch io.socle::socle:<version> -M socle.cli.Launcher -- serve --dir .
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
java -jar socle-<version>.jar serve --watch MyTop.fir
```

Edit your Chisel, sbt re-dumps `MyTop.fir`, and the open UI reloads within ~1s. A
0-module or mid-write parse is ignored (the last good design stays on screen), so
a save caught mid-flush never flickers the view to an error.

## Loading a design by hand

While any `serve` is running you can also **drag-and-drop** a `.fir` or a
`design.json` onto the page to view it on the fly. The drop converts (for `.fir`)
or loads (for `.json`) in-process.
