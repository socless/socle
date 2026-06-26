# Using the Mill plugin

The Mill plugin is the smoothest Chisel integration: one task elaborates your
design and serves the UI. It is one integration over the same viewer the
[standalone app](usage-standalone.md) drives.

Tested against Chisel 7.x and Mill 1.1.6 (the latest in the 1.1.x line; anything
`>= 1.1, <= 1.1.6` should work).

## One-time: publish to your local Maven cache

```bash
mill socless.publishLocal
mill mill-socless.publishLocal
```

## In your project's `build.mill`

Use the version you published (`mill show mill-socless.publishVersion` prints it):

```scala
//| mill-version: 1.1.6
//| mvnDeps:
//|   - io.socless::mill-socless::0.12.0

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

## Run

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
| `soclessTop: T[String]` | (required) | FQCN of the design to elaborate: a class with a no-arg constructor, a Scala object with a zero-arg `apply()`/`build()`, or a zero-arg factory method (`mypkg.Gens.smallSoC`). For parameterized designs, wrap in a generator object: `object MyTopForSocless { def apply() = new MyTop(defaultParams) }`. |
| `soclessTops: T[Seq[String]]` | `Seq(soclessTop())` | Elaborate several tops from one build (the whole SoC, a core, the NoC, ...) and switch between them in the UI's design picker. The first is `design.json`; the rest are `design-<top>.json`, indexed by a `designs.json`. One broken top is skipped, not fatal. |
| `soclessVersion: T[String]` | this plugin's version | Runtime library version (generated from the build, so it cannot drift from the plugin). |
| `soclessOutputName: T[String]` | `"design.json"` | Filename inside `Task.dest`. |
| `soclessUiPort: T[Int]` | `8765` | Default port for `soclessUI` / `soclessServe`. |
| `soclessRuntimeDeps: T[Seq[Dep]]` | `socless::soclessVersion` | Runtime deps injected into `mvnDeps`. Override to swap to an in-tree `moduleDeps` build. |
| `socless: T[PathRef]` | provided | Elaborate + emit `design.json`. Forks `java -cp <runClasspath> socless.cli.Launcher --top ... --output ...`. |
| `soclessUI(args)` | provided | Elaborate, then serve the UI + `design.json` and open a browser. Accepts `--port <N>` or a bare port. Blocks until Ctrl-C. |
| `soclessServe(args)` | provided | Serve the UI without elaborating first; drag-drop a `.fir` to convert. Same port args. |

## Live dev loop with Mill

Compose a `-w` watch with a static `serve`. The `socless` task writes
`design.json` into its task-dest, so point `serve --dir` there (`mill show
myDesign.socless` prints the exact path):

```bash
mill -w myDesign.socless                                  # terminal 1: re-emits design.json on every edit
cs launch io.socless::socless:0.12.0 -M socless.cli.Launcher -- \
  serve --dir out/myDesign/socless.dest                   # terminal 2: serves it, UI hot-reloads
```

The plugin writes `design.json` atomically, so the UI's poller never reads a
half-written file. (See [usage-standalone.md](usage-standalone.md) for the
`serve --watch` variant.)

## Bundled demos

The `example/` module is wired with `moduleDeps = Seq(socless)` instead of the
plugin, so the demos work without `publishLocal`:

```bash
mill example.soclessRun          # AXISystem (default)
mill example.soclessRunGcd       # GCD
mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
mill example.soclessRunDeepSoc   # deep hierarchy
mill example.soclessRunIoBundles # vec-of-bundle ports
mill example.soclessRunRegHeavy  # register-heavy design
mill show example.soclessRun     # prints the JSON path
```
