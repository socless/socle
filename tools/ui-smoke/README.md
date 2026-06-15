# SoCLess UI smoke test

Headless interactivity check for the single-file Scala.js + Laminar UI
(built by `./mill socless-ui.dist`). It catches regressions that the Scala unit tests
cannot: anything that only breaks when a human clicks around (drill-down
crashes, bundle-expand errors, blank pages).

What it does, per example design served by a local server (one subdir per
design: `<id>/SoCLess.html` + `<id>/design.json`):

1. Loads `<id>/SoCLess.html`.
2. Fully expands the hierarchy tree.
3. Drills into every module (including leaves) and asserts the app stays mounted.
4. Back on the top module, expands every bundle port.
5. Exercises the command palette (open/search/navigate), the `?` keyboard-help
   overlay, wire hover+select, and the inspector bundle tree.
6. Fails on any uncaught page exception or `console.error`.

It relies on test hooks baked into the UI: `data-hier-path`, `data-hier-toggle`,
`data-box-inst`, `data-bundle-toggle`, `data-wire`, `data-command-palette` /
`data-cmd-input` / `data-cmd-result`, `data-help-overlay`,
`data-inspector-toggle`, `data-source` / `data-top` / `data-live` (the SOURCE
indicator + LIVE badge), `data-dropzone` / `data-toast` (drag-drop chrome),
`data-theme-toggle` (the light/dark theme button), and `data-open-editor` /
`data-editor` (the open-in-editor preset chip).

## serve-smoke: the design-input layer (#194)

`smoke.mjs` loads a pre-baked `design.json`, so it cannot exercise the
design-INPUT layer: drag-dropping a `.fir` (which the UI POSTs to `/convert`)
or the live poller (#120, which re-fetches `design.json` when `serve --watch`
re-converts a changed `.fir`). `serve-smoke.mjs` runs the real
`socless.Launcher serve` from the assembled jar and checks all of it:

```bash
./mill socless.assembly
node tools/ui-smoke/serve-smoke.mjs \
  --jar out/socless/assembly.dest/out.jar \
  --fixtures socless/test/resources/chirrtl [--shots /tmp/shots]
```

It asserts: a `serve --watch a.fir` page shows `data-source="live"` + a LIVE
badge; overwriting the watched `.fir` hot-reloads the schematic in place;
dropping a `.fir` on a plain `serve` renders it via `/convert`; and dropping a
`design.json` renders it directly.

## Run

```bash
# 1. Build the UI dist, elaborate the examples, then assemble + serve per-design dirs:
./mill socless-ui.dist
for t in soclessRun soclessRunPipeline soclessRunGcd soclessRunDeepSoc soclessRunIoBundles soclessRunRegHeavy; do mill example.$t; done
node tools/ui-smoke/assemble.mjs site && (cd site && python3 -m http.server 8765 &)

# 2. Install deps once and run:
cd tools/ui-smoke
npm install
node smoke.mjs --base http://localhost:8765 [--design deepsoc] [--shots /tmp/shots]
```

Exit code is non-zero if any problem is found; each is printed as
`[design] where: message`. Uses the system Chromium (`/usr/bin/chromium`);
override with `CHROMIUM=/path/to/chromium`.
