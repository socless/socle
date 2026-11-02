# Vendored Surfer (WASM build)

The Waveform tab of the Socle panel embeds [Surfer](https://surfer-project.org),
the waveform viewer. These files are Surfer's web build, unmodified. They are
packed into the language server jar under `surfer/`, and `LiveHttp` serves
`/surfer/surfer.js` and `/surfer/surfer_bg.wasm`, nothing else.

Surfer is licensed under the EUPL-1.2 (`LICENSE-EUPL-1.2.txt`). It is a separate
component; Socle itself is MIT.

## Pin

- Upstream: <https://gitlab.com/surfer-project/surfer>
- Tag `v0.7.0`, commit `bd749b1f786c1c62cd67893ca71346cbe6983915` (also in `VERSION`)
- Source: <https://gitlab.com/surfer-project/surfer/-/tree/v0.7.0>, submodules
  `f128` at `e60de395f0d6454c1dc546b87f06a6358425b6b6` and
  `instruction-decoder` at `02bdcbfd5663a72e4a0b1b9d54519412d417fab1`

| File | Bytes | sha256 |
|---|---|---|
| `surfer.js` | 95027 | `c86f8a0e34ad8420ef62fb5f5d62cd5efbf6c45fcdbd45b755f322140fb4cb0a` |
| `surfer_bg.wasm` | 12871342 | `20e7d2fd28ca7ef97fa0bb624e5e430400e1954de1c15399737bd2cbbc21e723` |

## Why it is built, not downloaded

Upstream publishes no tagged web build:

- the v0.7.0 release and its package registry entry hold only the native zips
  (linux, win, macos-aarch64);
- the `pages` CI job deploys app.surfer-project.org from `main` only, so the
  hosted app is not a tagged version;
- the `pages_build` job's `surfer_wasm` artifact expires after one hour.

So the files come from the tag, built with upstream's own `pages_build` command.

## How it was built

Toolchain: rustc 1.99.1 (58cf8f9c3 2026-10-13), cargo 1.99.1, the
`wasm32-unknown-unknown` target, trunk 0.21.14 (the version upstream's CI uses).
Trunk downloaded wasm-bindgen 0.2.118 (matching `Cargo.lock`) and wasm-opt
`version_123` (binaryen, for `data-wasm-opt="2"` in `surfer/index.html`).

```sh
git clone --branch v0.7.0 --recurse-submodules https://gitlab.com/surfer-project/surfer.git
cd surfer
rustup target add wasm32-unknown-unknown
RUSTFLAGS="--cfg=web_sys_unstable_apis" \
  trunk build --config=surfer/Trunk.toml index.html --release --public-url /dist --features accesskit
cp surfer/dist/surfer.js surfer/dist/surfer_bg.wasm LICENSE-EUPL-1.2.txt <socle>/socle-lsp/vendor/surfer/
```

Features: Surfer's defaults (`performance_plot`, `https`, `wasm_plugins`) plus
`accesskit`, exactly as upstream's pages build. The `vscode` feature is **off**:
this build reads `?load_url=` and `?startup_commands=` from the page URL and
has none of the VS Code host bridges (`surfer_notify_host`,
`vscode_show_open_dialog`).

Only `surfer.js` and `surfer_bg.wasm` are taken from `surfer/dist`. The rest
(`index.html` with its analytics script, `sw.js` service worker,
`integration.js`, `manifest.json`, `surfer.d.ts`) is left out on purpose. The
module has no `snippets/` imports, so the two files are self-contained.

## How the page boots it

`surfer.js` is an ES module (wasm-bindgen `web` target). Its default export is
the init function, and init runs the crate's `main` (`__wbindgen_start`), which
**starts Surfer by itself** on the canvas whose id is `the_canvas_id`
(`surfer/src/main.rs`, wasm `main_impl`). This is how Surfer's own `index.html`
boots it: it only calls init. So:

1. Before init, the document must contain `<canvas id="the_canvas_id">` (main
   panics with "Failed to find the_canvas_id" otherwise), and should define
   `window.on_surfer_error = (msg) => ...`, which the panic hook calls with the
   backtrace (the stock page shows it in an error box).
2. `import init, { start_wcp, handle_wcp_cs_message, next_wcp_sc_message, inject_message } from '/surfer/surfer.js'`
3. `await init({ module_or_path: '/surfer/surfer_bg.wasm' })` (object form; the
   positional form is deprecated). Surfer is now running on the canvas.
4. `await start_wcp()`, once per page. It queues `SetupChannelWCP`, handled on
   the next frame; the channel can be claimed only once.
5. Send WCP client-to-server messages with `await handle_wcp_cs_message(json)`
   (the first one is the `greeting`), and read replies and events in a loop
   with `await next_wcp_sc_message()` (a JSON string, or `undefined` when the
   channel closes).
6. `inject_message(json)` queues a raw internal `Message` (for example
   `GroupNew`). These are not a stable API: re-test them on every update.

Checked on 2026-10-26 in headless Chromium (Brave) with exactly these steps:
init booted Surfer on the canvas; after `start_wcp` and a `greeting`
(`commands: ["waveforms_loaded"]`), Surfer answered with its own greeting, then
`load` of a ChiselSim GCD VCD by URL gave `{"type":"response","command":"ack"}`
and the `waveforms_loaded` event.

Do **not** also call `new WebHandle().start(canvas)`: `main` already did, and a
second runner would share Surfer's global state. `WebHandle.start` takes an
`HTMLCanvasElement`, not an id, if a page ever boots without `main`.

## How to update

1. Pick the new tag; check `libsurfer/src/wasm_api.rs` still exports
   `start_wcp`, `handle_wcp_cs_message`, `next_wcp_sc_message`,
   `inject_message` and `WebHandle`, and that `surfer/src/main.rs` still boots
   on `the_canvas_id`.
2. Rebuild with the steps above (use the trunk version upstream's
   `.gitlab-ci.yml` `pages_build` job uses), copy the two files and the
   licence, and update `VERSION` and this file (pin, toolchain, sizes, hashes).
3. Run `./mill socle-lsp.test` and the panel's Waveform tests, and re-check
   every injected `Message` shape the panel sends.
