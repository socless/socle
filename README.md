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
  def mvnDeps = super.mvnDeps() ++ Seq(mvn"org.chipsalliance::chisel:7.0.0-RC2")
  def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(
    mvn"org.chipsalliance:::chisel-plugin:7.0.0-RC2"
  )
}

object myDesign extends ChiselModule with SoclessModule {
  def soclessTop = "mypkg.MyTop"   // FQCN of your top-level Chisel module
}
```

**Run:**

```bash
mill myDesign.socless              # elaborate + emit design.json
mill show myDesign.socless         # prints the resulting JSON path
# Drop it next to SoCLess.html:
cp $(mill show myDesign.socless | tr -d '"') /path/to/SoCLess.html.dir/design.json
```

The UI auto-fetches `design.json` on load. Top-bar status flips from "demo"
to "live".

## What the plugin gives you

The `SoclessModule` trait adds these (overridable) members to your module:

| Member | Default | Use |
|---|---|---|
| `soclessTop: T[String]` | (no default — required) | FQCN of the top Chisel class. Must have a no-arg constructor; wrap parameterized modules in a thin `extends YourTop(defaults)` stub. |
| `soclessVersion: T[String]` | `"0.1.0-SNAPSHOT"` | Runtime library version. |
| `soclessOutputName: T[String]` | `"design.json"` | Filename inside `Task.dest`. |
| `soclessRuntimeDeps: T[Seq[Dep]]` | `socless::soclessVersion` | Runtime deps injected into `mvnDeps`. Override to swap to an in-tree `moduleDeps` build. |
| `socless: T[PathRef]` | provided | The task. Forks `java -cp <runClasspath> socless.Launcher --top … --output …`. |

## How it works

1. The `socless` task forks `java` with your module's `runClasspath` and
   invokes `socless.Launcher`.
2. `Launcher` reflection-instantiates your top class, hands it to
   `SoclessExporter.export`.
3. `SoclessExporter` calls Chisel's public `circt.stage.ChiselStage.emitCHIRRTL`
   to get the elaborated design as CHIRRTL text — bundles still aggregate
   at this level.
4. `ChirrtlParser` turns the text into a typed AST.
5. `IrWalk` translates the AST into the `Design` schema and coalesces
   ground-typed `connect` statements back into bundle-level wires.
6. JSON gets written to `Task.dest/design.json`. Mill's `show` task prints
   that path on stdout.

No `chisel3.internal.*` imports anywhere — survives Chisel patch updates.

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

## Compatibility notes

- **Chisel** — tested on 7.x. Earlier (5.x / 6.x) should work too; if your
  CHIRRTL emit uses the legacy `lhs <= rhs` form instead of `connect lhs, rhs`,
  the parser handles both.
- **Mill** — tested on 1.1.6 (use `>= 1.1, <= 1.1.6`). If
  `mvn"com.lihaoyi::mill-libs:1.1.6"` doesn't resolve in your environment,
  swap to the specific submodule (e.g. `mill-scalalib`) — the trait only
  uses `ScalaModule` + `Dep`.
- `gates` is `None` in the emitted JSON — elaboration alone can't know
  that. Populate it from your synth output (yosys stat JSON, etc.).
