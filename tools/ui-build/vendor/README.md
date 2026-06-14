# Vendored runtime for the built UI

Production UMD builds inlined into `ui/SoCLess.dist.html` by `build.mjs`:

- `react.production.min.js` - react@18.3.1 (`https://unpkg.com/react@18.3.1/umd/react.production.min.js`)
- `react-dom.production.min.js` - react-dom@18.3.1 (`https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js`)
- `elk.bundled.js` - elkjs@0.9.3 (`https://unpkg.com/elkjs@0.9.3/lib/elk.bundled.js`), the in-browser
  layout engine (issue #167). Self-contained: bundles its own web worker, so it
  runs offline from the single-file dist with no network.

To upgrade React: download the new production UMDs here, bump the version in
the dev CDN tags of `ui/SoCLess.html` (kept for the no-build dev loop), verify
neither file contains the byte sequence `</script` (it would terminate the
inline script tag early), then rebuild and run the ui-smoke suite.

The dist also inlines `ui/socless-layout.js` (the Scala.js layout engine,
issue #167) which is NOT vendored here: it is a local build artifact committed
next to the dev page. Rebuild it with `tools/refresh-socless-js.sh` after
editing `socless-js/` or the shared `Schema.scala` / `LayoutCore.scala`, then
re-run `node build.mjs`. CI re-links it from source and fails if it is stale.

To upgrade elkjs: download the new `elk.bundled.js` here, bump the version in
its dev CDN tag in `ui/SoCLess.html`, recompute the SHA-256 (`sha256sum
elk.bundled.js`) and update `ELK_SHA256` in `build.mjs` (the bundle does not
embed its version, so it is pinned by content hash), confirm it has no
`</script` byte sequence, then rebuild and run ui-smoke.
