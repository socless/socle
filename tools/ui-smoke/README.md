# Socle UI smoke test

Headless interactivity check for the single-file Scala.js + Laminar UI
(built by `./mill socle-ui.dist`). It catches regressions that the Scala unit tests
cannot: anything that only breaks when a human clicks around (drill-down
crashes, bundle-expand errors, blank pages).

What it does, per example design served by a local server (one subdir per
design: `<id>/socle.html` + `<id>/design.json`):

1. Loads `<id>/socle.html`.
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

## Waiting: the settle signal, no fixed sleep

The UI exposes `window.__socleSettled()`: true once the schematic view has
settled, that is no engine layout run is awaited, no armed first-view fit or
refit is pending, no flight runs, no fly waits for its module's view, and no
pan or zoom is still to settle into the view history.
`window.__soclePending()` lists what still waits (`layout`, `fit`, `flight`,
`fly`, `history`).

`smoke.mjs` and `embedded.mjs` wait on it with `settled(page)`: the signal
must hold on 3 animation frames in a row (a ResizeObserver report can arm a
refit a frame later), and a timeout (15 s by default) is a failure that names
what still waits. The rule: **no fixed sleep for settle**. After an action
that moves, drills, toggles, fits or flies, call `settled`; after an input
whose effect is synchronous while the view cannot settle (a press still held
down keeps re-arming the history settle), `frames(page)` waits for the next
painted frames; for something recorded on the test's side (a POST to
`/open`), poll it with `until`. A readiness wait never ends in
`.catch(() => {})`: its timeout is reported.

A `waitForTimeout` stays only where nothing can be observed, and carries a
one-line reason: pointer pacing between moves of a drag, an observation window
(a rest that must mutate nothing, a card that must not show within its delay,
a message protocol that must post nothing more), and Surfer redrawing its
canvas at a new size.

## Bundle toggles (#331)

On every design, `smoke.mjs` also opens and closes each bundle toggle of the
instance boxes in the first six modules. A box must not show a port path
twice, and closing a toggle must give back the rows it had, also when one of
its children was opened. An open row must keep a toggle to close it with.
Edge stubs are checked the same way. A known problem would be listed in
`KNOWN_TOGGLE` and printed as `known`, not failed; the port model (#332)
emptied the list. A new problem fails, and so does a known one that no longer
happens, so the list only shrinks.

## timings: first view, drill and toggle times (#331, #335)

```bash
node tools/ui-smoke/timings.mjs http://localhost:8765 axisystem - 3
node tools/ui-smoke/timings.mjs http://localhost:8765 deepsoc DeepSoc/cluster0 3
```

It loads a design of a served site (as for `smoke.mjs`), optionally drills
into a module, opens and closes the first bundle toggle of the box with the
most rows, and prints the medians in ms, measured to the layout-ready flag,
with the view's size (boxes, edge stubs, rows).

## serve-smoke: the design-input layer (#194)

`smoke.mjs` loads a pre-baked `design.json`, so it cannot exercise the
design-INPUT layer: drag-dropping a `.fir` (which the UI POSTs to `/convert`)
or the live poller (#120, which re-fetches `design.json` when `serve --watch`
re-converts a changed `.fir`). `serve-smoke.mjs` runs the real
`socle.cli.Launcher serve` from the assembled jar and checks all of it:

```bash
./mill socle.assembly
node tools/ui-smoke/serve-smoke.mjs \
  --jar out/socle/assembly.dest/out.jar \
  --fixtures socle/test/resources/chirrtl [--shots /tmp/shots]
```

It asserts: a `serve --watch a.fir` page shows `data-source="live"` + a LIVE
badge; overwriting the watched `.fir` hot-reloads the schematic in place;
dropping a `.fir` on a plain `serve` renders it via `/convert`; and dropping a
`design.json` renders it directly.

## embedded: the VS Code panel mode

`embedded.mjs` serves the dist page itself and checks that `?host=vscode`
removes the Source / Waveform tabs, logo, design picker, theme toggle and
gallery, share button and drop zone, starts with the bottom panel closed,
takes its theme from `&theme=light|dark|hc`, and follows `socle-theme`
messages from its parent frame only; and that without the parameter all of it
is still there. Embedded, the hierarchy and inspector panes and their toggles
are gone too (VS Code's side panel shows them): on the DeepSoc design, framed by
a cross-origin loopback page, the UI posts `socle-state` (hierarchy, drill
path, selection, details) on load, drill and select, follows `socle-navigate`
from the parent, and ignores self-posted or malformed messages:

```bash
./mill socle-ui.dist && ./mill 'example.{socleRunGcd,socleRunDeepSoc,socleCapturedFsm}'
node tools/ui-smoke/embedded.mjs --design out/example/socleRunGcd.dest/design.json \
  --deep out/example/socleRunDeepSoc.dest/design.json \
  --fsm out/example/socleCapturedFsm.dest/design.json
```

## Run

```bash
# 1. Build the UI dist, elaborate the examples, then assemble + serve per-design dirs:
./mill socle-ui.dist
for t in socleRun socleRunPipeline socleRunGcd socleRunDeepSoc socleRunIoBundles socleRunRegHeavy socleRunBigArray; do mill example.$t; done
node tools/ui-smoke/assemble.mjs site && (cd site && python3 -m http.server 8765 &)

# 2. Install deps once and run:
cd tools/ui-smoke
npm install
node smoke.mjs --base http://localhost:8765 [--design deepsoc] [--shots /tmp/shots] [--concurrency 4]
```

The `bigarray` design (a 16x16 grid of stages, 256 boxes in one module) gets
the performance guard only: a hover, a rest, a selection, a wheel zoom and a
drag pan must cost the same there as on a small design (a few attribute
changes for a hover, none outside the view's transform for a pan or a zoom,
no rebuild, no long task over 250 ms). It runs after the others, alone.

`--concurrency N` smokes N designs at once, each in its own browser context
(default 1; CI uses 4). Failures are still attributed per design and the
summary lists them in manifest order.

Exit code is non-zero if any problem is found; each is printed as
`[design] where: message`. Uses the system Chromium (`/usr/bin/chromium`);
override with `CHROMIUM=/path/to/chromium`.
