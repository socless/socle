# Vendored runtime for the built UI

Production UMD builds inlined into `ui/SoCLess.dist.html` by `build.mjs`:

- `react.production.min.js` - react@18.3.1 (`https://unpkg.com/react@18.3.1/umd/react.production.min.js`)
- `react-dom.production.min.js` - react-dom@18.3.1 (`https://unpkg.com/react-dom@18.3.1/umd/react-dom.production.min.js`)

To upgrade React: download the new production UMDs here, bump the version in
the dev CDN tags of `ui/SoCLess.html` (kept for the no-build dev loop), verify
neither file contains the byte sequence `</script` (it would terminate the
inline script tag early), then rebuild and run the ui-smoke suite.
