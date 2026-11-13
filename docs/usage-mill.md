# Using the Mill plugin

The Mill plugin is the smoothest Chisel integration: one task elaborates your
design and serves the UI. It is one integration over the same viewer the
[standalone app](usage-standalone.md) drives; [Using the UI](usage-ui.md) shows
how to move around its schematic.

Needs Chisel 7.16 or newer. Tested against Chisel 7.16.0 and Mill 1.1.6, the
Mill the plugin is built against.

## One-time: publish to your local Maven cache

The plugin and its runtime library are not on Maven Central or any other
repository: the release page attaches only the standalone jar, the language
server jar and the single-file UI. Publish both artifacts from a checkout of
this repository, at a release tag (on `main` the version ends in `-SNAPSHOT`):

```bash
git clone https://github.com/socless/socle.git
cd socle
git checkout v<version>          # a tag from the releases page
./mill socle.publishLocal
./mill mill-socle.publishLocal
./mill show mill-socle.publishVersion   # prints the version to use below
```

## In your project's `build.mill`

Use the version you published (`<version>` below):

```scala
//| mill-version: 1.1.6
//| mvnDeps:
//|   - io.socle::mill-socle::<version>

import mill._, scalalib._
import socle.mill.SocleModule

trait ChiselModule extends ScalaModule {
  def scalaVersion = "2.13.16"
  def mvnDeps = super.mvnDeps() ++ Seq(mvn"org.chipsalliance::chisel:7.16.0")
  def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(
    mvn"org.chipsalliance:::chisel-plugin:7.16.0"
  )
}

object myDesign extends ChiselModule with SocleModule {
  def socleTop = "mypkg.MyTop"   // FQCN of your top-level Chisel module
}
```

## Run

```bash
mill myDesign.socleUI            # elaborate + serve the UI, open the browser
mill myDesign.socleUI --port 9000
```

`socleUI` elaborates your design, extracts the single-file UI from the JAR,
drops `design.json` next to it, and serves both on a local port (default 8765,
loopback only). The UI auto-fetches `design.json` on load; the top-bar status
reads "live". While the server is up you can also drag-and-drop a `.fir`
(CHIRRTL) file onto the page to convert and view it on the fly.

Just want the JSON (to wire into your own pipeline)?

```bash
mill myDesign.socle              # elaborate + emit design.json
mill show myDesign.socle         # prints the resulting JSON path
```

To browse an existing `.fir` with no Chisel project at all:

```bash
mill myDesign.socleServe         # serve the UI; drag-drop a .fir onto the page
```

## What the plugin gives you

The `SocleModule` trait adds these (overridable) members to your module:

| Member | Default | Use |
|---|---|---|
| `socleTop: T[String]` | (required) | FQCN of the design to elaborate: a class with a no-arg constructor, a Scala object with a zero-arg `apply()`/`build()`, or a zero-arg factory method (`mypkg.Gens.smallSoC`). For parameterized designs, wrap in a generator object: `object MyTopForSocle { def apply() = new MyTop(defaultParams) }`. |
| `socleTops: T[Seq[String]]` | `Seq(socleTop())` | Elaborate several tops from one build (the whole SoC, a core, the NoC, ...) and switch between them in the UI's design picker. The first is `design.json`; the rest are `design-<top>.json`, indexed by a `designs.json`. One broken top is skipped, not fatal. |
| `socleVersion: T[String]` | this plugin's version | Runtime library version (generated from the build, so it cannot drift from the plugin). |
| `socleOutputName: T[String]` | `"design.json"` | Filename inside `Task.dest`. |
| `socleUiPort: T[Int]` | `8765` | Default port for `socleUI` / `socleServe`. |
| `socleRuntimeDeps: T[Seq[Dep]]` | `socle::socleVersion` | Runtime deps injected into `mvnDeps`. Override to swap to an in-tree `moduleDeps` build. |
| `socle: T[PathRef]` | provided | Elaborate + emit `design.json`. Forks `java -cp <runClasspath> socle.cli.Launcher --top ... --output ...`. |
| `socleUI(args)` | provided | Elaborate, then serve the UI + `design.json` and open a browser. Accepts `--port <N>` or a bare port. Blocks until Ctrl-C. |
| `socleServe(args)` | provided | Serve the UI without elaborating first; drag-drop a `.fir` to convert. Same port args. |

## Live dev loop with Mill

Compose a `-w` watch with a static `serve`. The `socle` task writes
`design.json` into its task-dest, so point `serve --dir` there (`mill show
myDesign.socle` prints the exact path):

```bash
mill -w myDesign.socle                                  # terminal 1: re-emits design.json on every edit
java -jar socle-<version>.jar serve --dir out/myDesign/socle.dest   # terminal 2: serves it, UI hot-reloads
```

`socle-<version>.jar` is the standalone jar of a release (see
[usage-standalone.md](usage-standalone.md#2-convert--serve-with-the-cli)).

The plugin writes `design.json` atomically, so the UI's poller never reads a
half-written file. (See [usage-standalone.md](usage-standalone.md) for the
`serve --watch` variant.)

## Bundled demos

The `example/` module is wired with `moduleDeps = Seq(socle)` instead of the
plugin, so the demos work without `publishLocal`:

```bash
mill example.socleRun          # AXISystem (default)
mill example.socleRunGcd       # GCD
mill example.socleRunPipeline  # 4-stage RISC-V pipeline
mill example.socleRunDeepSoc   # deep hierarchy
mill example.socleRunIoBundles # vec-of-bundle ports
mill example.socleRunRegHeavy  # register-heavy design
mill show example.socleRun     # prints the JSON path
```
