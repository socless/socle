# Vendored runtime for the built UI

`elk.bundled.js` - elkjs@0.9.1 (`https://unpkg.com/elkjs@0.9.1/lib/elk.bundled.js`),
the in-browser layout engine (issue #167). Self-contained: bundles its own web
worker, so it runs offline from the single-file dist with no network.

The `socless-ui.dist` Mill task (build.mill) inlines this file and the Scala.js
link output into `socless-ui/html/SoCLess.src.html` to produce the shipped
single-file UI - no committed artifact, no Node (epic #172 / issue #184). Build
it with `./mill socless-ui.dist`.

To upgrade elkjs: download the new `elk.bundled.js` here, bump the version in
the dev CDN tag in `socless-ui/html/SoCLess.src.html`, confirm it has no
`</script` byte sequence, then `./mill socless-ui.dist` and run ui-smoke.
