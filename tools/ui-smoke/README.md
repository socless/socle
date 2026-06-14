# SoCLess UI smoke test

Headless interactivity check for the single-file Scala.js + Laminar UI
(`ui/SoCLess.scala.html`). It catches regressions that the Scala unit tests
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
`data-inspector-toggle`.

## Run

```bash
# 1. Elaborate the examples, then assemble + serve the per-design dirs:
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
