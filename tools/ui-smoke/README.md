# SoCLess UI smoke test

Headless interactivity check for the single-file UI (`ui/SoCLess.html`). It
catches regressions that the Scala unit tests cannot: anything that only breaks
when a human clicks around (drill-down crashes, bundle-expand errors, blank
pages from React errors).

What it does, per example design served by a local server:

1. Loads `SoCLess.html?design=<id>`.
2. Fully expands the hierarchy tree.
3. Drills into every module (including leaves) and asserts the app stays mounted.
4. Expands every bundle port in the last view.
5. Fails on any uncaught page exception or `console.error`.

It relies on test hooks baked into the UI: `data-hier-path`, `data-hier-toggle`,
`data-box-inst`, `data-bundle-toggle`.

## Run

```bash
# 1. Serve the UI + example designs (one option):
mill example.soclessUI            # serves AXISystem on :8765
# or assemble a multi-design dir with an examples/manifest.json and
# `python3 -m http.server 8765` from it.

# 2. Install deps once and run:
cd tools/ui-smoke
npm install
node smoke.mjs --base http://localhost:8765 [--design deepsoc] [--shots /tmp/shots]
```

Exit code is non-zero if any problem is found; each is printed as
`[design] where: message`. Uses the system Chromium (`/usr/bin/chromium`);
override with `CHROMIUM=/path/to/chromium`.
