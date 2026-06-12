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

The easiest way is to open `ui/SoCLess.html` directly in a browser and place a
`design.json` next to it. The UI fetches `design.json` on load; the top-bar
status switches from "demo" to "live" when it succeeds.

Alternatively, use the `soclessUI` task in a user project:

```bash
./mill myDesign.soclessUI           # serves the UI at http://localhost:8765
./mill myDesign.soclessUI --port 9000
```

## Project layout

```
socless/src/socless/    # runtime library (Scala 2.13 + Chisel)
mill-socless/           # Mill plugin trait (Scala 3)
example/src/            # bundled demo designs
ui/                     # single-file web UI (SoCLess.html) + reference JSONs
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
- **UI (`ui/SoCLess.html`)**: single-file React, JSX compiled in the browser
  (dev CDN builds) so you can hack on it with zero tooling. The SHIPPED
  artifact is `ui/SoCLess.dist.html` (JSX precompiled, production React
  inlined, no network dependencies): after editing the source, regenerate it
  and commit both files (CI fails if they drift):

  ```bash
  cd tools/ui-build && npm ci && node build.mjs
  ```
- **Prose and comments**: no em dashes. Use a hyphen, comma, colon, or
  parentheses instead.

## Schema contract

`Schema.scala` case classes are the JSON contract with the UI. Adding fields is
fine (the UI tolerates null/missing). Never remove or rename existing fields
without updating `ui/SoCLess.html` in the same PR.
