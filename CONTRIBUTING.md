# Contributing to SoCLess

SoCLess is a Mill plugin for Chisel that exports elaborated hardware designs to
`design.json` for a single-file interactive web UI.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 11 or 17 (tested) |
| Mill | 1.1.6 (pinned in `build.mill`) |

The included `./mill` wrapper script downloads Mill automatically on first run.
Scala versions (2.13.16 for the runtime library, 3.8.2 for the Mill plugin) are
resolved by Mill; no manual Scala install is needed.

## Getting started

```bash
git clone https://github.com/e-ban/SoCless.git
cd SoCless
./mill socless.compile         # compile the runtime library
./mill mill-socless.compile    # compile the Mill plugin
```

To publish both artifacts to your local Maven cache (needed to use the plugin in
a separate project):

```bash
./mill socless.publishLocal
./mill mill-socless.publishLocal
```

## Running the test suite

```bash
./mill socless.test
```

The suite (utest) covers `LayoutTest`, `ElkSmokeTest`, `EndpointTest`,
`HarnessTest`, `LayoutPolishTest`, `PipelineRobustnessTest`, `SchemaLayoutTest`,
and `WithLayoutsTest`. All tests must pass before a PR is merged.

## Running the bundled examples

The `example/` module uses `moduleDeps` (not the published plugin), so no
`publishLocal` is needed:

```bash
./mill example.soclessRun          # AXISystem (default)
./mill example.soclessRunGcd       # GCD module
./mill example.soclessRunPipeline  # 4-stage RISC-V pipeline
./mill example.soclessRunDeepSoc   # deep hierarchy (DeepSoc)
./mill example.soclessRunIoBundles # vec-of-bundle ports (Interconnect)
./mill example.soclessRunRegHeavy  # register-heavy design (Datapath)
./mill show example.soclessRun     # print the path to the emitted design.json
```

## Viewing the UI

The UI needs HTTP (the Laminar app fetches `design.json`), so serve it rather
than opening from `file://`. The simplest path is the `soclessUI` task in a user
project (it serves the UI embedded in the JAR and drops `design.json` next to it):

```bash
./mill myDesign.soclessUI           # serves the UI at http://localhost:8765
./mill myDesign.soclessUI --port 9000
```

To build just the single-file UI for manual serving:

```bash
./mill socless-ui.dist              # writes out/socless-ui/dist.dest/SoCLess.html
```

## Project layout

```
socless/src/socless/    # runtime exporter library (Scala 2.13 + Chisel)
socless-js/             # Scala.js layout engine (shares pure sources with the backend)
socless-ui/             # Scala.js + Laminar frontend (the shipped UI)
mill-socless/           # Mill plugin trait (Scala 3)
example/src/            # bundled demo designs
```

See `CLAUDE.md` for a detailed breakdown of every source file and the key
design decisions (CHIRRTL extraction surface, bundle deduplication, wire
coalescing, etc.).

## Contribution workflow

1. Fork the repo and create a branch off `main` with a descriptive name:
   `feat/my-feature`, `fix/short-description`, `chore/infra-thing`, etc.
2. Keep PRs focused on one concern. Split unrelated changes into separate PRs.
3. Run `./mill socless.test` and confirm it passes before pushing.
4. Reference the issue in your PR description with `Closes #N`.
5. Open the PR against `main`.

## Commit style

This repo uses conventional commits with a scope in parentheses:

```
feat(ui): add dark-mode toggle
fix(backend): handle empty bundle in CHIRRTL parser
chore: update .gitignore
test(examples): add deep-hierarchy fixture
docs: update CONTRIBUTING prerequisites
```

Common prefixes: `feat`, `fix`, `refactor`, `test`, `chore`, `docs`, `style`,
`perf`.

## Code style

- **Backend (Scala)**: standard `scalafmt` defaults; no `chisel3.internal.*`
  imports (use only public Chisel APIs).
- **UI (`socless-ui/`)**: Scala.js + Laminar. The shipped single-file UI is
  produced by `./mill socless-ui.dist` (the Scala.js link output + vendored
  elkjs inlined into `socless-ui/html/SoCLess.src.html`, offline) - there is no
  committed artifact and no Node build. Smoke interactivity changes with the
  ui-smoke harness (`tools/ui-smoke/`).
- **Prose and comments**: no em dashes. Use a hyphen, comma, colon, or
  parentheses instead.

## Schema contract

`Schema.scala` case classes are the JSON contract with the `socless-ui`
frontend. Adding fields is fine (use a trailing default so older `design.json`
still parses). Never remove or rename existing fields without updating the UI
in the same PR.
