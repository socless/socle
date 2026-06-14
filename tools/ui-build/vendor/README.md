# Vendored runtime for the built UI

Inlined into `ui/SoCLess.scala.html` by `build-scala.mjs`:

- `elk.bundled.js` - elkjs@0.9.1 (`https://unpkg.com/elkjs@0.9.1/lib/elk.bundled.js`), the in-browser
  layout engine (issue #167). Self-contained: bundles its own web worker, so it
  runs offline from the single-file dist with no network.

`build-scala.mjs` also inlines `ui/socless-ui.js` (the Scala.js + Laminar
frontend, epic #172), a local build artifact committed next to the template.
Rebuild it with `tools/refresh-socless-ui.sh` after editing `socless-ui/` (or
the shared `socless` sources), then re-run `node build-scala.mjs`. CI re-links
it from source and fails if it is stale.

To upgrade elkjs: download the new `elk.bundled.js` here, bump the version in
the dev CDN tag in `ui/SoCLess.scala.src.html`, confirm it has no `</script`
byte sequence, then rebuild (`node build-scala.mjs`) and run ui-smoke.
