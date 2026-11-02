# Contributing to Socle

Socle is a Mill plugin for Chisel that exports elaborated hardware designs to
`design.json` for a single-file interactive web UI.

## Licence and contributor agreement

Socle is licensed under the AGPL-3.0 and also offered under commercial licences
([LICENSING.md](LICENSING.md)). To keep that possible, contributions are accepted under the
[Contributor License Agreement](CLA.md): opening a pull request means you accept it.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK | 21 (what CI uses; the language server needs 21 or newer) |
| Mill | 1.1.6 (pinned in `build.mill`) |

The included `./mill` wrapper script downloads Mill automatically on first run.
Scala versions (2.13.16 for the runtime library, 3.8.2 for the Mill plugin) are
resolved by Mill; no manual Scala install is needed.

## Getting started

```bash
git clone https://github.com/socless/socle.git
cd socle
./mill socle.compile         # compile the runtime library
./mill mill-socle.compile    # compile the Mill plugin
```

To publish both artifacts to your local Maven cache (needed to use the plugin in
a separate project):

```bash
./mill socle.publishLocal
./mill mill-socle.publishLocal
```

## Running the test suite

```bash
./mill socle.test
```

The suite (utest) covers `LayoutTest`, `ElkSmokeTest`, `EndpointTest`,
`HarnessTest`, `LayoutPolishTest`, `PipelineRobustnessTest`, `SchemaLayoutTest`,
and `WithLayoutsTest`. All tests must pass before a PR is merged.

## Running the bundled examples

The `example/` module uses `moduleDeps` (not the published plugin), so no
`publishLocal` is needed:

```bash
./mill example.socleRun          # AXISystem (default)
./mill example.socleRunGcd       # GCD module
./mill example.socleRunPipeline  # 4-stage RISC-V pipeline
./mill example.socleRunDeepSoc   # deep hierarchy (DeepSoc)
./mill example.socleRunIoBundles # vec-of-bundle ports (Interconnect)
./mill example.socleRunRegHeavy  # register-heavy design (Datapath)
./mill show example.socleRun     # print the path to the emitted design.json
```

## Viewing the UI

The UI needs HTTP (the Laminar app fetches `design.json`), so serve it rather
than opening from `file://`. The simplest path is the `socleUI` task in a user
project (it serves the UI embedded in the JAR and drops `design.json` next to it):

```bash
./mill myDesign.socleUI           # serves the UI at http://localhost:8765
./mill myDesign.socleUI --port 9000
```

To build just the single-file UI for manual serving:

```bash
./mill socle-ui.dist              # writes out/socle-ui/dist.dest/socle.html
```

## Project layout

```
socle/src/socle/    # runtime exporter library (Scala 2.13 + Chisel)
socle-js/             # Scala.js layout engine (shares pure sources with the backend)
socle-ui/             # Scala.js + Laminar frontend (the shipped UI)
mill-socle/           # Mill plugin trait (Scala 3)
socle-probe/          # Java agent: captures a ChiselSim test's design (IDE view mode)
socle-lsp/            # language server for the Chisel IDE (lenses, live panel, Simulate)
example/src/            # bundled demo designs
tools/                  # CI helpers (ci/selective.sh) and the UI smokes (ui-smoke/)
```

See `CLAUDE.md` for a detailed breakdown of every source file and the key
design decisions (CHIRRTL extraction surface, bundle deduplication, wire
coalescing, etc.).

## CI

CI (`.github/workflows/ci.yml`) runs five jobs in parallel, each compiling only
what its own tasks need:

- **Artifacts** (`build-artifacts.yml`): the jars and the single-file
  UI, uploaded as the `socle-dist` workflow artifact.
- **Unit tests**: the `socle` and `socle-probe` test suites, the CHIRRTL fixture sync check and the reference JSON check.
- **Language server tests**: the `socle-lsp` suite, on its own because
  `MillBridgeTest` drives a real Mill and takes about 3 minutes in CI.
- **UI smokes and examples**: the UI dist, the example elaborations, the
  standalone server smoke, and the browser smokes (`smoke.mjs`, four designs at
  once, and `serve-smoke.mjs`).
- **Packaging (publish + plugin consumption)**: `publishLocal` of both
  artifacts and the plugin consumption smoke (`tools/consume-test`).

Each Mill job caches its `out/` directory (keyed on the job, `build.mill` and
the sources it builds), so a module whose inputs did not change is not
recompiled; Playwright's Chromium is cached too. Pull requests read the caches
that pushes to `main` save.

On a pull request, the unit, language server and UI jobs use Mill selective
execution through `tools/ci/selective.sh`: they snapshot the base commit, then
the test jobs run only the suites whose inputs changed, and the UI job runs the
browser smokes only when one of the Mill tasks they drive (the UI dist, the
assembly, an example design) is selected, or when a file under
`tools/ui-smoke`, `tools/ci`, `socle/test/resources/chirrtl` or
`.github/workflows` changed. Pushes to `main` run everything.

A change to CI itself tests itself: when a pull request changes
`.github/workflows/**` or `tools/ci/**`, `selective.sh prepare` takes no
snapshot, so every job runs everything, as on a push to `main`.

When you add a test module or a task that drives a smoke, add it to
`ALL_TASKS` in `tools/ci/selective.sh` (the snapshot only covers those tasks)
and, for a smoke input, to the selector of the "Decide whether the UI smokes
run" step in `ci.yml`.

To run the UI smokes locally, see `tools/ui-smoke/README.md`: `smoke.mjs`
(every example design), `serve-smoke.mjs` (the standalone server) and
`embedded.mjs` (the VS Code panel mode, not run in CI). They use the system
Chromium (`/usr/bin/chromium`); set `CHROMIUM=/path/to/chromium` to use
another.

```bash
./mill socle-ui.dist && ./mill 'example.{socleRunGcd,socleRunDeepSoc,socleCapturedFsm}'
node tools/ui-smoke/embedded.mjs --design out/example/socleRunGcd.dest/design.json \
  --deep out/example/socleRunDeepSoc.dest/design.json \
  --fsm out/example/socleCapturedFsm.dest/design.json
```

## Contribution workflow

1. Fork the repo and create a branch off `main` with a descriptive name:
   `feat/my-feature`, `fix/short-description`, `chore/infra-thing`, etc.
2. Keep PRs focused on one concern. Split unrelated changes into separate PRs.
3. Run `./mill socle.test` and confirm it passes before pushing.
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
- **UI (`socle-ui/`)**: Scala.js + Laminar. The shipped single-file UI is
  produced by `./mill socle-ui.dist` (the Scala.js link output + vendored
  elkjs inlined into `socle-ui/html/socle.src.html`, offline) - there is no
  committed artifact and no Node build. Smoke interactivity changes with the
  ui-smoke harness (`tools/ui-smoke/`).
- **Prose and comments**: no em dashes. Use a hyphen, comma, colon, or
  parentheses instead.

## Schema contract

`Schema.scala` case classes are the JSON contract with the `socle-ui`
frontend. Adding fields is fine (use a trailing default so older `design.json`
still parses). Never remove or rename existing fields without updating the UI
in the same PR.
