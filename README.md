# SoCLess — Chisel integration

Two-artifact Mill plugin that turns a Chisel design into a `design.json` the
SoCLess UI consumes. Tested against Chisel 7.x and Mill 1.1.6 (the latest
release in the 1.1.x line; anything `>= 1.1, <= 1.1.6` should work).

```
build.mill                 # Mill 1.1.x — builds + publishes both artifacts
socless/                   # runtime library (Scala 2.13)
  src/socless/
    Schema.scala           # JSON case classes (contract with the UI)
    SoclessExporter.scala  # public entry: SoclessExporter.export(gen, path)
    ChirrtlParser.scala    # CHIRRTL text -> typed AST
    IrWalk.scala           # AST -> Design (UI JSON shape)
    Launcher.scala         # reflection-based main the plugin invokes
  test/src/socless/        # utest suite (./mill socless.test)
mill-socless/              # Mill plugin (Scala 3)
  src/socless/mill/
    SoclessModule.scala    # the trait users mix in
example/                   # bundled demos — moduleDeps, no publishLocal
  src/
    axisystem/             # AXI4 interconnect (AXISystem)
    gcd/                   # simple GCD
    rvpipeline/            # 4-stage RISC-V pipeline
    deephier/              # deep hierarchy (DeepSoc)
    iobundles/             # vec-of-bundle ports (Interconnect)
    regheavy/              # register-heavy design (Datapath)
```

## Seamless Mill plugin (preferred flow)

**One-time: build + publish to your local Maven cache.**

```bash
mill socless.publishLocal
mill mill-socless.publishLocal
```

**In your project's `build.mill`:**

```scala
//| mill-version: 1.1.6
//| mvnDeps:
//|   - io.socless::mill-socless::0.1.0-SNAPSHOT

import mill._, scalalib._
import socless.mill.SoclessModule

trait ChiselModule extends ScalaModule {
  def scalaVersion = "2.13.16"
  def mvnDeps = super.mvnDeps() ++ Seq(mvn"org.chipsalliance::chisel:7.11.0")
  def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(
    mvn"org.chipsalliance:::chisel-plugin:7.11.0"
  )
}

object myDesign extends ChiselModule with SoclessModule {
  def soclessTop = "mypkg.MyTop"   // FQCN of your top-level Chisel module
}
```

**Run:**

```bash
mill myDesign.soclessUI            # elaborate + serve the UI, open the browser
mill myDesign.soclessUI --port 9000
```

`soclessUI` elaborates your design, extracts the single-file UI from the JAR,
drops `design.json` next to it, and serves both on a local port (default 8765,
loopback only). The UI auto-fetches `design.json` on load; the top-bar status
reads "live". While the server is up you can also drag-and-drop a `.fir`
(CHIRRTL) file onto the page to convert and view it on the fly.

Just want the JSON (to wire into your own pipeline)?

```bash
mill myDesign.socless              # elaborate + emit design.json
mill show myDesign.socless         # prints the resulting JSON path
```

To browse an existing `.fir` with no Chisel project at all:

```bash
mill myDesign.soclessServe         # serve the UI; drag-drop a .fir onto the page
```

## What the plugin gives you

The `SoclessModule` trait adds these (overridable) members to your module:

| Member | Default | Use |
|---|---|---|
| `soclessTop: T[String]` | (no default — required) | FQCN of the design to elaborate: a class with a no-arg constructor, a Scala object with a zero-arg `apply()`/`build()`, or a zero-arg factory method (`mypkg.Gens.smallSoC`). For parameterized designs, a generator object is the natural wrapper: `object MyTopForSocless { def apply() = new MyTop(defaultParams) }`. |
| `soclessTops: T[Seq[String]]` | `Seq(soclessTop())` | Elaborate several tops from one build (the whole SoC, a core, the NoC, ...) and switch between them in the UI's design picker. The first is `design.json`; the rest are `design-<top>.json`, indexed by a `designs.json`. One broken top is skipped, not fatal. |
| `soclessVersion: T[String]` | this plugin's version | Runtime library version (generated from the build, so it cannot drift from the plugin). |
| `soclessOutputName: T[String]` | `"design.json"` | Filename inside `Task.dest`. |
| `soclessUiPort: T[Int]` | `8765` | Default port for `soclessUI` / `soclessServe`. |
| `soclessRuntimeDeps: T[Seq[Dep]]` | `socless::soclessVersion` | Runtime deps injected into `mvnDeps`. Override to swap to an in-tree `moduleDeps` build. |
| `socless: T[PathRef]` | provided | Elaborate + emit `design.json`. Forks `java -cp <runClasspath> socless.Launcher --top … --output …`. |
| `soclessUI(args)` | provided | Elaborate, then serve the UI + `design.json` and open a browser. Accepts `--port <N>` or a bare port. Blocks until Ctrl-C. |
| `soclessServe(args)` | provided | Serve the UI without elaborating first; drag-drop a `.fir` to convert. Same port args. |

## How it works

1. The `socless` task forks `java` with your module's `runClasspath` and
   invokes `socless.Launcher`.
2. `Launcher` reflection-resolves your top (class, generator object, or
   factory method) and hands it to `SoclessExporter.export`.
3. `SoclessExporter` calls Chisel's public `circt.stage.ChiselStage.emitCHIRRTL`
   to get the elaborated design as CHIRRTL text — bundles still aggregate
   at this level.
4. `ChirrtlParser` turns the text into a typed AST.
5. `IrWalk` translates the AST into the `Design` schema and coalesces
   ground-typed `connect` statements back into bundle-level wires.
6. JSON gets written to `Task.dest/design.json`. Mill's `show` task prints
   that path on stdout.

No `chisel3.internal.*` imports anywhere — survives Chisel patch updates.

## The UI

The viewer is a single self-contained HTML file (`ui/SoCLess.html` is the dev
source; `ui/SoCLess.dist.html` is the shipped artifact with React vendored and
the JSX precompiled, so it has **no network dependencies** and works offline).
`soclessUI` serves it for you; you can also open the file directly and drop a
`design.json` onto it.

What you can do in it:

- **Navigate** the hierarchy tree or the schematic; double-click a box to drill
  into a submodule, breadcrumb back out (zoom/pan is preserved per level).
- **Expand bundle ports** in place to see channels and fields; wires stay
  orthogonal and boxes move aside so nothing overlaps.
- **Inspect** any wire or module (types, widths, registers/memories, source
  location) and **jump to the source** in your editor (VS Code / IntelliJ link,
  right-click the path to switch).
- **Command palette** (Ctrl/Cmd-K) with fuzzy search over modules, instances
  and pins; `?` lists the keyboard shortcuts.
- **Dark mode** (persists), and a **Source** tab that shows the Chisel behind
  each module when the design was exported with a source root.
- **Export**: SVG / PNG of the current schematic, or a self-contained
  **HTML snapshot** with the design embedded - send it to a colleague and they
  open it from `file://` with full interactivity, no repo or build needed.

## Trying the bundled demos

```bash
mill example.soclessRun          # AXISystem (default)
mill example.soclessRunGcd       # GCD
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill example.soclessRunDeepSoc   # deep hierarchy
mill example.soclessRunIoBundles # vec-of-bundle ports
mill example.soclessRunRegHeavy  # register-heavy design
mill show example.soclessRun     # prints the JSON path
```

The `example/` module is wired with `moduleDeps = Seq(socless)` instead of
the plugin, so it works without `publishLocal`.

## Using SoCLess with sbt (or any build)

The Mill plugin is the smoothest path, but SoCLess does not need Mill. The
runtime library ships a standalone, Python-free UI server, so any build that
can emit CHIRRTL works in two steps.

**1. Dump CHIRRTL from your own build.** Anywhere you can run Chisel:

```scala
import circt.stage.ChiselStage
ChiselStage.emitCHIRRTLFile(new mypkg.MyTop, Array("--target-dir", "."))
// writes MyTop.fir
```

(or `sbt "runMain ..."` a tiny main that does the same).

**2. Convert + serve with the standalone CLI.** No checkout needed - launch the
published artifact straight from coursier:

```bash
# one-off convert:
cs launch io.socless::socless:<version> -M socless.Launcher -- \
  --fir-input MyTop.fir --output design.json

# or serve the UI and drag-drop the .fir onto the page (in-process /convert):
cs launch io.socless::socless:<version> -M socless.Launcher -- \
  serve --dir .        # serves design.json from . if present; --port N to override
```

`serve` extracts the bundled single-file UI, serves it on `127.0.0.1` (loopback
only), and converts dropped `.fir` files in-process - no Mill, no Python. Add
`--dir <dir>` to also serve a `design.json` / `designs.json` from that
directory so the UI auto-loads it.

### Live dev loop (save and see)

`serve --watch <file.fir>` re-converts a CHIRRTL file in-process every time it
changes and serves the result at `/design.json`; the UI polls that endpoint and
hot-reloads the schematic on its own (an "updated Ns ago" indicator shows the
loop is live). Pair it with a build that re-dumps the `.fir` on every edit for a
save-and-see workflow:

```bash
# terminal 1 - your build re-emits MyTop.fir on every source change:
sbt "~runMain mypkg.DumpMain"          # DumpMain calls emitCHIRRTLFile (step 1 above)

# terminal 2 - serve + watch that file:
cs launch io.socless::socless:<version> -M socless.Launcher -- \
  serve --watch MyTop.fir
```

Edit your Chisel, sbt re-dumps `MyTop.fir`, and the open UI reloads within ~1s.
A 0-module or mid-write parse is ignored (the last good design stays on screen),
so a save caught mid-flush never flickers the view to an error.

With Mill, compose the plugin's `-w` watch with a static `serve` instead:

```bash
mill -w myDesign.socless        # terminal 1: re-emits design.json on every edit
cs launch ... -- serve --dir .  # terminal 2: serves that design.json, UI hot-reloads
```

## Compatibility notes

- **Chisel** — CI tests against 7.11.0 only. 5.x / 6.x are not yet covered by
  CI, but the parser is written for cross-version portability: it accepts both
  the legacy `lhs <= rhs` and the modern `connect lhs, rhs` connect forms, plus
  `public module` / layer syntax. If you use an older Chisel and hit a parse
  gap, please open an issue with the `.fir` (capture it with
  `Launcher --chirrtl-out`).
- **Mill** — tested on 1.1.6 (use `>= 1.1, <= 1.1.6`). If
  `mvn"com.lihaoyi::mill-libs:1.1.6"` doesn't resolve in your environment,
  swap to the specific submodule (e.g. `mill-scalalib`) — the trait only
  uses `ScalaModule` + `Dep`.
- `gates` is `None` in the emitted JSON — elaboration alone can't know
  that. Populate it from your synth output (yosys stat JSON, etc.).
