// Embedded-mode check (VS Code panel): the UI loaded with ?host=vscode drops the
// chrome the editor already provides and follows the editor's theme; without the
// parameter every piece is still there.
//
// Embedded, the hierarchy and inspector panes are gone (VS Code's side panel shows
// them), and the UI talks to the parent page: it posts `socless-state` (design
// hierarchy only when the design changes, drill path, selection, details) and
// follows `socless-navigate`.
//
// Usage (from the repo root, after `./mill socless-ui.dist`, the elaborated designs
// `./mill example.soclessRunGcd` and `./mill example.soclessRunDeepSoc`, and the
// captured FSM and GCD designs `./mill example.soclessCapturedFsm` and
// `./mill example.soclessCapturedGcd`):
//   node tools/ui-smoke/embedded.mjs --design out/example/soclessRunGcd.dest/design.json \
//     --deep out/example/soclessRunDeepSoc.dest/design.json \
//     --fsm out/example/soclessCapturedFsm.dest/design.json \
//     --gcd out/example/soclessCapturedGcd.dest/design.json
//
// The Waveform check (8) stands in for the language server: it serves /ide.json
// (mutable), a ChiselSim GCD trace (tools/ui-smoke/fixtures/gcd-trace.vcd) and its
// names under /wave/<id>/, the vendored Surfer (socless-lsp/vendor/surfer, the
// files the server jar packs) under /surfer/, and records POST /wave/select.
//
// Serves the dist page itself (loopback, free port) with a two-entry designs.json so
// the multi-top picker shows in normal mode. Uses CHROMIUM=/path like smoke.mjs.

import { chromium } from 'playwright-core';
import { existsSync, readFileSync } from 'node:fs';
import http from 'node:http';

const args = Object.fromEntries(
  process.argv.slice(2).join(' ').split('--').filter(Boolean).map((s) => {
    const [k, ...v] = s.trim().split(/\s+/);
    return [k, v.join(' ') || true];
  }),
);
const UI = args.ui || 'out/socless-ui/dist.dest/SoCLess.html';
const DESIGN = args.design || 'out/example/soclessRunGcd.dest/design.json';
const DEEP = args.deep || 'out/example/soclessRunDeepSoc.dest/design.json'; // a design with instances
const FSM = args.fsm || 'out/example/soclessCapturedFsm.dest/design.json'; // an FSM block with a state diagram
const GCD = args.gcd || 'out/example/soclessCapturedGcd.dest/design.json'; // a captured design with its signals
const VCD = args.vcd || 'tools/ui-smoke/fixtures/gcd-trace.vcd'; // a ChiselSim (Verilator) trace of that design
const SURFER = args.surfer || 'socless-lsp/vendor/surfer'; // the vendored Surfer WASM build
const SHOTS = args.shots || null; // where to save the Waveform screenshot
for (const f of [UI, DESIGN, DEEP, FSM, GCD, VCD, `${SURFER}/surfer.js`, `${SURFER}/surfer_bg.wasm`])
  if (!existsSync(f)) { console.error(`missing ${f}`); process.exit(2); }

// The stand-in language server state for check 8: /ide.json (404 while null), the
// POST /wave/select bodies, and how often each Surfer file was fetched.
const wave = { ide: null, posts: [], surferJs: 0, surferWasm: 0 };
// The trace's full dotted var paths, from its VCD header (as the server's TraceNames).
const vcdNames = (() => {
  const scopes = []; const names = [];
  for (const line of readFileSync(VCD, 'utf8').split('\n')) {
    const t = line.trim().split(/\s+/);
    if (t[0] === '$scope') scopes.push(t[2]);
    else if (t[0] === '$upscope') scopes.pop();
    else if (t[0] === '$var') names.push([...scopes, t[4]].join('.'));
    else if (t[0] === '$enddefinitions') break;
  }
  return names;
})();
const PREFIX = 'TOP.svsimTestbench.dut';
const names = (drop) => JSON.stringify({ prefix: PREFIX, names: vcdNames.filter((n) => !drop.includes(n)) });

const files = {
  '/': ['text/html', readFileSync(UI)],
  '/design.json': ['application/json', readFileSync(DESIGN)],
  '/deep/': ['text/html', readFileSync(UI)],
  '/deep/design.json': ['application/json', readFileSync(DEEP)],
  '/fsm/': ['text/html', readFileSync(UI)],
  '/fsm/design.json': ['application/json', readFileSync(FSM)],
  '/gcd/': ['text/html', readFileSync(UI)],
  '/gcd/design.json': ['application/json', readFileSync(GCD)],
  '/wave/t1/trace': ['application/octet-stream', readFileSync(VCD)],
  '/wave/t1/names': ['application/json', names([])],
  // the second trace's names lack one register: the panel counts it missing
  '/wave/t2/trace': ['application/octet-stream', readFileSync(VCD)],
  '/wave/t2/names': ['application/json', names([`${PREFIX}.y`])],
  // the third trace lacks `y` (renamed in its header, so its value changes stay
  // valid) and its names are unknown, as an FST's: Surfer itself drops `y`
  '/wave/t3/trace': ['application/octet-stream', readFileSync(VCD, 'utf8').replace("$var wire 16 ' y [15:0] $end", "$var wire 16 ' yy [15:0] $end")],
  '/wave/t3/names': ['application/json', JSON.stringify({ prefix: PREFIX, names: [] })],
  '/designs.json': ['application/json', JSON.stringify({ designs: [
    { id: 'a', label: 'A', path: 'design.json' }, { id: 'b', label: 'B', path: 'design.json' }] })],
};
const server = http.createServer((req, res) => {
  const path = req.url.split('?')[0];
  if (path === '/gcd/ide.json') {
    if (!wave.ide) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(wave.ide)); return;
  }
  if (req.method === 'POST' && path.endsWith('/wave/select')) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => { wave.posts.push({ path, type: req.headers['content-type'], body }); res.writeHead(204); res.end(); });
    return;
  }
  if (path === '/surfer/surfer.js' || path === '/surfer/surfer_bg.wasm') {
    const js = path.endsWith('.js');
    if (js) wave.surferJs++; else wave.surferWasm++;
    res.writeHead(200, { 'content-type': js ? 'text/javascript' : 'application/wasm' });
    res.end(readFileSync(`${SURFER}/${path.slice('/surfer/'.length)}`)); return;
  }
  const f = files[path];
  if (!f) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': f[0] }); res.end(f[1]);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;
// A stand-in for the VS Code webview page, cross-origin to the UI like the real one
// (localhost vs 127.0.0.1): hosts the UI in an iframe.
files['/parent.html'] = ['text/html', `<!DOCTYPE html><body style="margin:0"><iframe id="f" src="${BASE}/?host=vscode&theme=light" style="width:1400px;height:800px;border:0"></iframe></body>`];
const PARENT = `http://localhost:${server.address().port}/parent.html`;
// The same, on the DeepSoc design, recording every message the UI posts to it.
files['/parent-deep.html'] = ['text/html', `<!DOCTYPE html><body style="margin:0"><iframe id="f" src="${BASE}/deep/?host=vscode&theme=light" style="width:1400px;height:800px;border:0"></iframe>
<script>
window.__msgs = [];
const f = document.getElementById('f');
addEventListener('message', (e) => { if (e.source === f.contentWindow) window.__msgs.push({ origin: e.origin, data: e.data }); });
</script></body>`];
const PARENT_DEEP = `http://localhost:${server.address().port}/parent-deep.html`;

const EXEC = process.env.CHROMIUM
  || ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable'].find((p) => existsSync(p));
// Software WebGL: Surfer (egui) draws with WebGL, which headless Chromium has only through SwiftShader.
const LAUNCH_ARGS = ['--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader'];
const browser = await chromium.launch(EXEC ? { executablePath: EXEC, args: LAUNCH_ARGS } : { args: LAUNCH_ARGS });

const failures = [];
const check = (where, ok, msg) => { console.log(`  ${ok ? 'ok  ' : 'FAIL'} [${where}] ${msg}`); if (!ok) failures.push(where); };

// The hooks of everything embedded mode removes.
const REMOVED = {
  logo: '.scl-logo',
  picker: '[data-design-picker]',
  themeToggle: '[data-theme-toggle]',
  sourceTab: '[data-bottom-tab="source"]',
  waveTab: '[data-bottom-tab="wave"]',
  share: '[data-export-snapshot]',
  dropzone: '[data-dropzone]',
  hierarchyPane: '[data-pane="hierarchy"]',
  inspectorPane: '[data-pane="inspector"]',
  hierarchyToggle: '[data-toggle-pane="hierarchy"]',
  inspectorToggle: '[data-toggle-pane="inspector"]',
};

async function open(url) {
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 800 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(m.text()); });
  await page.goto(url.startsWith('http') ? url : BASE + url);
  return { ctx, page, errors };
}
const count = (frame, sel) => frame.locator(sel).count();
const theme = (frame) => frame.evaluate(() => document.documentElement.getAttribute('data-theme'));

async function waitLoaded(frame) {
  await frame.waitForSelector('[data-design-loaded="true"]', { timeout: 30000 });
  await frame.waitForSelector('[data-design-picker], [data-pane="schematic"]', { timeout: 30000 });
  await frame.waitForTimeout(500); // the designs.json index lands asynchronously
}

// 1. Normal mode (regression): everything present, bottom panel open.
{
  const { ctx, page, errors } = await open('/');
  await waitLoaded(page);
  for (const [k, sel] of Object.entries(REMOVED)) check('normal', (await count(page, sel)) > 0, `${k} present`);
  check('normal', (await count(page, '[data-bottom-panel]')) === 1, 'bottom panel open');
  await page.click('[data-settings-toggle]');
  check('normal', (await count(page, '[data-theme-option]')) > 0, 'theme gallery in settings');
  check('normal', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 2. Embedded, dark: removed chrome absent, bottom panel closed then Ports only, theme from the URL, not persisted.
{
  const { ctx, page, errors } = await open('/?host=vscode&theme=dark');
  await waitLoaded(page);
  for (const [k, sel] of Object.entries(REMOVED)) check('embedded', (await count(page, sel)) === 0, `${k} absent`);
  check('embedded', (await count(page, '[data-bottom-panel]')) === 0, 'bottom panel closed');
  const dropCancelled = await page.evaluate(() => ['dragover', 'drop'].map((t) => {
    const e = new DragEvent(t, { cancelable: true, bubbles: true });
    document.body.dispatchEvent(e);
    return e.defaultPrevented;
  }));
  check('embedded', dropCancelled.every(Boolean), `dragover/drop cancelled (no navigation) ${dropCancelled}`);
  check('embedded', (await theme(page)) === 'dark', 'data-theme=dark from ?theme=dark');
  check('embedded', (await page.evaluate(() => localStorage.getItem('socless-theme'))) === null, 'theme not persisted');
  for (const sel of ['[data-search-trigger]', '[data-help-trigger]', '[data-toggle-pane="bottom"]', '[data-settings-toggle]'])
    check('embedded', (await count(page, sel)) === 1, `${sel} kept`);
  await page.click('[data-toggle-pane="bottom"]');
  const tabs = await page.$$eval('[data-bottom-tab]', (els) => els.map((e) => e.getAttribute('data-bottom-tab')));
  check('embedded', JSON.stringify(tabs) === '["ports"]', `bottom tabs ${JSON.stringify(tabs)}`);
  check('embedded', (await page.locator('[data-bottom-panel]').innerText()).includes('instances'), 'ports tab selected');
  await page.click('[data-settings-toggle]');
  check('embedded', (await count(page, '[data-theme-option]')) === 0, 'no theme gallery in settings');
  await page.keyboard.press('Escape');
  await page.keyboard.press('Control+k');
  const cmds = await page.locator('[data-command-palette]').innerText();
  check('embedded', !/Toggle theme/.test(cmds), 'no "Toggle theme" command');
  check('embedded', /ports \/ waveform/.test(cmds) && !/source \/ waveform/.test(cmds), 'bottom panel command says ports / waveform, no source');
  check('embedded', !/Toggle hierarchy panel|Toggle inspector panel/.test(cmds), 'no hierarchy / inspector toggle commands');
  await page.keyboard.press('Escape');
  // A window posting to itself is not the parent: ignored.
  await page.evaluate(() => window.postMessage({ type: 'socless-theme', kind: 'light' }, '*'));
  await page.waitForTimeout(200);
  check('embedded', (await theme(page)) === 'dark', 'self-posted theme message ignored');
  check('embedded', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 3. Embedded hc and light kinds.
for (const [kind, want] of [['hc', 'contrast'], ['light', null]]) {
  const { ctx, page } = await open(`/?host=vscode&theme=${kind}`);
  await waitLoaded(page);
  check(`theme=${kind}`, (await theme(page)) === want, `data-theme=${want}`);
  await ctx.close();
}

// 4. Live switch: the parent page posts socless-theme to the iframe.
{
  const { ctx, page, errors } = await open(PARENT);
  const frame = page.frameLocator('#f');
  await frame.locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  check('live', (await theme(f)) === null, 'starts light');
  for (const [kind, want] of [['dark', 'dark'], ['bogus', 'dark'], ['hc', 'contrast'], ['light', null]]) {
    await page.evaluate((k) => document.getElementById('f').contentWindow.postMessage({ type: 'socless-theme', kind: k }, '*'), kind);
    await page.waitForTimeout(200);
    check('live', (await theme(f)) === want, `parent posts ${kind} -> data-theme=${want}`);
  }
  await f.evaluate(() => window.postMessage({ type: 'socless-theme', kind: 'dark' }, '*'));
  await page.waitForTimeout(200);
  check('live', (await theme(f)) === null, 'message from the frame itself ignored');
  check('live', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 5. The side panel channel (DeepSoc): socless-state to the parent on load, drill and select;
// socless-navigate from the parent drills and selects; forged or malformed messages are ignored.
{
  const { ctx, page, errors } = await open(PARENT_DEEP);
  const frame = page.frameLocator('#f');
  await frame.locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  const states = () => page.evaluate(() => window.__msgs.filter((m) => m.data && m.data.type === 'socless-state'));
  const last = async () => (await states()).at(-1);
  const crumb = () => f.evaluate(() => { const a = document.querySelector('.scl-crumb.active'); return a && a.textContent; });
  const navigate = (msg) => page.evaluate((m) => document.getElementById('f').contentWindow.postMessage(m, '*'), msg);
  const waitFor = async (what, pred) => {
    for (let i = 0; i < 50; i++) { const s = await last(); if (s && pred(s.data)) return s; await page.waitForTimeout(100); }
    check('side', false, `timed out waiting for ${what}: last ${JSON.stringify((await last())?.data)?.slice(0, 300)}`);
    return null;
  };

  const s0 = await waitFor('the loaded state', (d) => d.design && d.design.top === 'DeepSoc');
  if (s0) {
    const d = s0.data;
    check('side', s0.origin === BASE, `posted from the UI origin (${s0.origin})`);
    check('side', JSON.stringify(d.path) === '["DeepSoc"]' && d.selection === null, `path ${JSON.stringify(d.path)}, no selection`);
    const top = d.design.modules.find((m) => m.name === 'DeepSoc');
    check('side', !!top && JSON.stringify(top.children.map((c) => c.name)) === '["cluster0","cluster1","pipe"]', `top children ${JSON.stringify(top && top.children)}`);
    const q = d.design.modules.find((m) => m.name === 'Queue4_Token');
    check('side', !!top.source && top.source.line === 162 && q && q.source === null, 'module source only when openable');
    check('side', d.details && d.details.module === 'DeepSoc' && d.details.ports.length === 3, `details of the current module ${JSON.stringify(d.details).slice(0, 200)}`);
  }

  const n0 = (await states()).length;
  check('side', (await states()).filter((m) => 'design' in m.data && m.data.design).length === 1, 'the hierarchy is posted once');
  await navigate({ type: 'socless-navigate', path: ['DeepSoc', 'cluster0'], select: null });
  const drilled = await waitFor('the drilled state', (d) => JSON.stringify(d.path) === '["DeepSoc","cluster0"]');
  check('side', (await crumb()) === 'cluster0', 'navigate drills (breadcrumb cluster0)');
  await page.waitForTimeout(300);
  check('side', (await states()).length === n0 + 1, `one state per navigate (${(await states()).length - n0})`);
  check('side', !!drilled && !('design' in drilled.data) && drilled.data.details.module === 'Cluster',
    'a drill posts path and details, not the hierarchy again');

  const n1 = (await states()).length;
  await navigate({ type: 'socless-navigate', path: ['DeepSoc'], select: 'pipe' });
  const sel = await waitFor('the selected state', (d) => d.selection === 'pipe');
  await page.waitForTimeout(300);
  check('side', (await states()).length === n1 + 1, `one state for a drill-and-select navigate (${(await states()).length - n1})`);
  check('side', (await crumb()) === 'DeepSoc', 'navigate back to the top');
  check('side', !!sel && sel.data.details.name === 'pipe' && sel.data.details.module === 'Queue4_Token', `details of the selected instance ${JSON.stringify(sel && sel.data.details).slice(0, 200)}`);

  // The box header (its centre can be a port row, which expands instead of selecting).
  await f.locator('[data-box-inst="cluster1"]').first().click({ position: { x: 20, y: 3 } });
  const clicked = await waitFor('the clicked state', (d) => d.selection === 'cluster1');
  check('side', !!clicked && clicked.data.details.module === 'Cluster_1', 'a box click posts its selection and details');

  const n = (await states()).length;
  await f.evaluate(() => window.postMessage({ type: 'socless-navigate', path: ['DeepSoc', 'cluster0'], select: null }, '*'));
  for (const bad of [{ type: 'socless-navigate', path: 'DeepSoc' }, { type: 'socless-navigate', path: ['DeepSoc', 'nope'] },
    { type: 'socless-navigate', path: ['DeepSoc', 7] }, { type: 'socless-navigate', path: ['DeepSoc'], select: 3 }]) await navigate(bad);
  await page.waitForTimeout(400);
  check('side', (await crumb()) === 'DeepSoc', 'self-posted and malformed navigate messages ignored');
  check('side', (await states()).length === n, 'no state posted for ignored messages');
  check('side', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 6. Not framed: an embedded page at the top level posts nothing and does not break.
{
  const { ctx, page, errors } = await open('/deep/?host=vscode');
  await waitLoaded(page);
  check('top-level', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 7. The FSM state diagram, embedded: a double-click opens it, the breadcrumb
// names it, Escape closes it.
{
  const { ctx, page, errors } = await open('/fsm/?host=vscode&theme=dark');
  await waitLoaded(page);
  const id = 'blk$TrafficFsm$fsm$state';
  await page.locator(`[data-fsm="${id}"]`).first().dblclick({ position: { x: 30, y: 8 } }).catch(() => {});
  const shown = await page.waitForSelector(`[data-fsm-diagram="${id}"][data-fsm-ready="1"]`, { timeout: 10000 }).then(() => true).catch(() => false);
  check('fsm', shown, 'a double-click opens the state diagram');
  const crumbs = await page.$$eval('.scl-crumb', (els) => els.map((e) => e.textContent.trim()));
  check('fsm', crumbs.at(-1) === 'FSM: state', `breadcrumb ${JSON.stringify(crumbs)}`);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  check('fsm', (await count(page, '[data-fsm-diagram]')) === 0 && (await count(page, `[data-fsm="${id}"]`)) === 1, 'Escape closes it');
  check('fsm', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 8. The Waveform tab follows the selection. The page plays the VS Code panel of
// the language server after Simulate of `GcdSpec / GCD computes gcd(48, 18)`.
// Embedded Surfer (`surfer-panel`): no trace yet, the tab says how to get one; a
// trace arrives, Surfer boots once and shows GCD's plan, grouped by port; smaller
// selections focus their signal, a focus below the visible rows scrolls into view;
// another test pauses following; a new trace loads in place and counts a missing
// signal, as does a path Surfer drops itself (names unknown, as for FST); following
// off does nothing. Then `surfer` (the app, driven by the server) POSTs the
// selection, a page load POSTs the module in view, and `custom` sends nothing.
{
  const TEST = 'GCD computes gcd(48, 18)';
  const TRACE_TEST = `gcd.GcdSpec#${TEST}`; // as the server stamps the trace: <suite>#<test>
  const SETTINGS = { followSelection: true, showClockReset: false, foldBundles: false, showWires: true, colorByDirection: true, followDelayMs: 150 };
  // /ide.json as LiveHttp writes it: `test` is the bare test name, `suite` its suite.
  wave.ide = { status: 'ok', message: '', focusClass: '', test: TEST, suite: 'gcd.GcdSpec', stamp: 1, waveViewer: 'surfer-panel', trace: '', traceTest: '', waveSettings: SETTINGS };
  const setIde = async (patch) => { wave.ide = { ...wave.ide, ...patch, stamp: wave.ide.stamp + 1 }; await new Promise((r) => setTimeout(r, 2000)); }; // > one 1500 ms poll
  const { ctx, page, errors } = await open('/gcd/?host=vscode&theme=light');
  await waitLoaded(page);
  const snap = () => page.evaluate(() => window.__soclessWave ? window.__soclessWave.snapshot() : null);
  const waitSnap = async (what, pred, ms = 20000) => {
    for (let t = 0; t < ms; t += 200) { const s = await snap(); if (s && pred(s)) return s; await page.waitForTimeout(200); }
    check('wave', false, `timed out waiting for ${what}: ${JSON.stringify(await snap())}`);
    return null;
  };
  const P = (leaf) => `${PREFIX}.${leaf}`;
  const stub = (id) => page.locator(`[data-boundary-stub="${id}"]`).first().click();

  // No trace yet: the Waveform tab is there and explains how to get one; selecting is harmless.
  await page.click('[data-toggle-pane="bottom"]'); // embedded, the bottom panel starts closed
  const tab = await page.waitForSelector('[data-bottom-tab="wave"]', { timeout: 10000 }).then(() => true).catch(() => false);
  check('wave', tab, 'Waveform tab shown in embedded mode for surfer-panel');
  await page.click('[data-bottom-tab="wave"]').catch(() => {});
  const hint = await page.locator('[data-wave-hint]').innerText().catch(() => '');
  check('wave', /Simulate/.test(hint), `no trace: the tab says how to get one (${JSON.stringify(hint)})`);
  await stub('io.in.valid');
  await page.waitForTimeout(400);
  check('wave', wave.surferJs === 0, 'no trace: Surfer not loaded');

  // A trace arrives: Surfer boots once and shows GCD's plan in order, grouped by port.
  await setIde({ trace: '/wave/t1/trace', traceTest: TRACE_TEST });
  const ready = await page.waitForSelector('[data-surfer-status="ready"]', { timeout: 120000 }).then(() => true).catch(() => false);
  check('wave', ready, 'Surfer reports loaded');
  const shown = await waitSnap('the top plan', (s) => s.idle && s.shown && s.shown.path === '["GCD"]' && s.loadedTrace.endsWith('/wave/t1/trace'), 30000);
  const items = await page.evaluate(() => window.__soclessWave.items()).catch(() => []);
  const got = items.map((i) => (i.type === 'Group' ? `group ${i.name}` : i.trace || `? ${i.name}`));
  // GCD's plan (WaveLayout): one group per port, its bundles nested (a Decoupled
  // reads valid, ready, bits, BundleTree's order), then the registers.
  const want = ['group io', 'group io.in', P('io_in_valid'), P('io_in_ready'), 'group io.in.bits', P('io_in_bits_a'), P('io_in_bits_b'),
    'group io.out', P('io_out_valid'), P('io_out_ready'), 'group io.out.bits', P('io_out_bits_result'),
    'group Registers', P('x'), P('y'), P('busy')];
  check('wave', JSON.stringify(got) === JSON.stringify(want), `Surfer shows GCD's plan in order\n      got  ${JSON.stringify(got)}\n      want ${JSON.stringify(want)}`);
  check('wave', (await count(page, '[data-wave-missing]')) === 0, 'nothing missing from the first trace');
  // Each signal shows its short label (ItemNameChange), its group says the rest; a
  // multi-bit one keeps its width as Surfer showed it.
  const label = (t) => (items.find((i) => i.trace === t) || {}).name;
  check('wave', label(P('io_in_valid')) === 'valid' && label(P('io_out_bits_result')) === 'result [15:0]' && label(P('x')) === 'x [15:0]',
    `short labels (${label(P('io_in_valid'))}, ${label(P('io_out_bits_result'))}, ${label(P('x'))})`);
  if (SHOTS) {
    // a taller panel, so every signal shows
    const h = await page.locator('[data-bottom-resize]').boundingBox();
    await page.mouse.move(h.x + 100, h.y + 3);
    await page.mouse.down();
    await page.mouse.move(h.x + 100, h.y - 260, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(1000); // Surfer redraws at the new size
    await page.locator('[data-bottom-panel]').screenshot({ path: `${SHOTS}/wave-gcd.png` });
    console.log(`  shot ${SHOTS}/wave-gcd.png`);
  }

  // Smaller selections focus their signal: a port, a block (its first pin), a wire, a bundle (its first field).
  const focusAfter = async (what, act, trace) => {
    const before = (await snap()).focusCount;
    await act();
    const s = await waitSnap(`the focus of ${what}`, (x) => x.idle && x.focusCount > before && x.lastFocus);
    check('wave', !!s && s.lastFocus.trace === trace && s.lastFocus.ok, `${what} focuses ${trace} (${JSON.stringify(s && s.lastFocus)})`);
  };
  await focusAfter('port io.out.bits.result', () => stub('io.out.bits.result'), P('io_out_bits_result'));
  await focusAfter('block blk$GCD$logic', () => page.locator('[data-block="blk$GCD$logic"]').first().click({ position: { x: 20, y: 5 } }), P('io_in_ready'));
  await focusAfter('wire from blk$GCD$logic.io_in_bits_a', () => page.locator('[data-wire="blk$GCD$logic.io_in_bits_a"]').first().dispatchEvent('click'), P('io_in_bits_a'));
  await focusAfter('bundle io.out', () => page.evaluate(() => window.__soclessWave.select('io.out')), P('io_out_valid'));
  // A focus below the visible rows scrolls it into view (ScrollToItem, at its visible
  // index): a short panel, then the last signal.
  {
    const h = await page.locator('[data-bottom-resize]').boundingBox();
    await page.mouse.move(h.x + 100, h.y + 3);
    await page.mouse.down();
    await page.mouse.move(h.x + 100, h.y + 150, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(1000); // Surfer redraws at the new size
    const rows = (await page.evaluate(() => window.__soclessWave.items()).catch(() => [])).map((i) => i.trace);
    await focusAfter('register busy (the last row)', () => page.evaluate(() => window.__soclessWave.select('busy')), P('busy'));
    const s = await snap();
    check('wave', !!s.lastScroll && s.lastScroll.index === rows.indexOf(P('busy')) && s.lastScroll.index > 0,
      `the focus scrolled its row into view (ScrollToItem ${JSON.stringify(s.lastScroll)}, row ${rows.indexOf(P('busy'))})`);
    if (SHOTS) { await page.locator('[data-bottom-panel]').screenshot({ path: `${SHOTS}/wave-scroll.png` }); console.log(`  shot ${SHOTS}/wave-scroll.png`); }
    const h2 = await page.locator('[data-bottom-resize]').boundingBox();
    await page.mouse.move(h2.x + 100, h2.y + 3);
    await page.mouse.down();
    await page.mouse.move(h2.x + 100, h2.y - 150, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(500);
  }
  // The canvas survives the panel closing and reopening and other tabs: Surfer still answers.
  await page.click('[data-toggle-pane="bottom"]');
  await page.waitForTimeout(300);
  await page.click('[data-toggle-pane="bottom"]');
  await page.click('[data-bottom-tab="ports"]');
  await page.click('[data-bottom-tab="wave"]');
  await focusAfter('port io.in.bits.a after closing the panel', () => stub('io.in.bits.a'), P('io_in_bits_a'));

  // Folded bundles (wavesFoldBundles): the plan is redone with its bundle groups
  // folded (GroupFold); a focus inside unfolds them first (GroupUnfold).
  const visible = async () => (await page.evaluate(() => window.__soclessWave.items()).catch(() => []))
    .map((i) => (i.type === 'Group' ? `group ${i.name}` : i.trace));
  await page.evaluate(() => window.__soclessWave.select(null)); // no selection: no focus unfolds a group
  await setIde({ waveSettings: { ...SETTINGS, foldBundles: true } });
  await waitSnap('the folded plan', (s) => s.idle);
  const foldedItems = await visible();
  const wantFolded = ['group io', 'group io.in', 'group io.out', 'group Registers', P('x'), P('y'), P('busy')];
  check('wave', JSON.stringify(foldedItems) === JSON.stringify(wantFolded), `bundle groups folded ${JSON.stringify(foldedItems)}`);
  await focusAfter('port io.in.bits.a in a folded bundle', () => stub('io.in.bits.b').then(() => stub('io.in.bits.a')), P('io_in_bits_a'));
  const unfolded = await visible();
  check('wave', unfolded.includes(P('io_in_bits_a')) && unfolded.includes(P('io_in_bits_b')) && !unfolded.includes(P('io_out_valid')),
    `the focus unfolded its groups only ${JSON.stringify(unfolded)}`);
  await setIde({ waveSettings: SETTINGS });
  await waitSnap('the unfolded plan', (s) => s.idle);

  // A burst of selections: only the last one reaches Surfer (debounced, one exchange at a time).
  const burst0 = (await snap()).focusCount;
  await page.evaluate(() => ['io.in.valid', 'io.in.ready', 'io.out.valid', 'io.in.bits.b'].forEach((x) => window.__soclessWave.select(x)));
  const burst = await waitSnap('the burst', (x) => x.idle && x.focusCount > burst0);
  await page.waitForTimeout(500);
  const burst1 = await snap();
  check('wave', !!burst && burst1.focusCount === burst0 + 1 && burst1.lastFocus.trace === P('io_in_bits_b'),
    `a burst of 4 selections focuses once, the last (${burst1.focusCount - burst0}, ${JSON.stringify(burst1.lastFocus)})`);

  // The keyboard still drives the panel once Surfer runs (its canvas is not focused).
  await stub('io.out.valid');
  await page.keyboard.press('Control+k');
  check('wave', (await count(page, '[data-command-palette]')) === 1, 'Ctrl+K opens the palette with Surfer running');
  await page.keyboard.press('Escape');
  await page.keyboard.press('?');
  check('wave', (await count(page, '[data-help-overlay]')) === 1, '? opens the help with Surfer running');
  await page.keyboard.press('Escape');

  // Another test in the panel: following pauses, and the tab says why.
  await setIde({ test: 'GCD something else' });
  const paused = await page.locator('[data-wave-paused]').innerText().catch(() => '');
  check('wave', /paused/i.test(paused), `another test pauses following (${JSON.stringify(paused)})`);
  const f0 = (await snap()).focusCount;
  await stub('io.in.valid');
  await page.waitForTimeout(800);
  check('wave', (await snap()).focusCount === f0, 'paused: a selection does not reach Surfer');
  await setIde({ test: TEST });
  // The same test name in another suite is another test: still paused.
  await setIde({ suite: 'other.OtherSpec' });
  const paused2 = await page.locator('[data-wave-paused]').innerText().catch(() => '');
  check('wave', /paused/i.test(paused2), `the same test name in another suite pauses following (${JSON.stringify(paused2)})`);
  const f2 = (await snap()).focusCount;
  await stub('io.in.ready');
  await page.waitForTimeout(800);
  check('wave', (await snap()).focusCount === f2, 'another suite: a selection does not reach Surfer');
  await setIde({ suite: 'gcd.GcdSpec' });

  // A new Simulate: the new trace loads in place (no second boot) and a missing signal is counted.
  await setIde({ trace: '/wave/t2/trace' });
  await waitSnap('the second trace', (s) => s.idle && s.loadedTrace.endsWith('/wave/t2/trace') && s.shown && s.shown.trace.endsWith('/wave/t2/trace'), 30000);
  const missing = await page.locator('[data-wave-missing]').innerText().catch(() => '');
  check('wave', missing.trim() === '1 signal not in the trace', `missing count shown (${JSON.stringify(missing)})`);
  const items2 = await page.evaluate(() => window.__soclessWave.items()).catch(() => []);
  check('wave', !items2.some((i) => i.trace === P('y')) && items2.some((i) => i.trace === P('x')), 'the missing register is not shown, the others are');
  check('wave', wave.surferJs === 1 && wave.surferWasm === 1 && (await snap()).boots === 1, `Surfer booted once (js ${wave.surferJs}, wasm ${wave.surferWasm})`);

  // A trace whose names are unknown (as an FST's): the plan keeps every signal and
  // Surfer drops the one it lacks; the rest of its group is still matched by name.
  await setIde({ trace: '/wave/t3/trace' });
  await waitSnap('the third trace', (s) => s.idle && s.shown && s.shown.trace.endsWith('/wave/t3/trace'), 30000);
  const missing3 = await page.locator('[data-wave-missing]').innerText().catch(() => '');
  check('wave', missing3.trim() === '1 signal not in the trace', `a path Surfer dropped is counted missing (${JSON.stringify(missing3)})`);
  const items3 = await page.evaluate(() => window.__soclessWave.items()).catch(() => []);
  const regs3 = items3.filter((i) => i.type !== 'Group').map((i) => i.trace);
  check('wave', regs3.includes(P('x')) && regs3.includes(P('busy')) && !regs3.includes(P('y')) && !regs3.includes(null),
    `the dropped register's group keeps its other signals, each matched ${JSON.stringify(regs3.slice(-3))}`);
  await focusAfter('register busy beside a dropped one', () => page.evaluate(() => window.__soclessWave.select('busy')), P('busy'));

  // Following off: selections do nothing.
  await setIde({ waveSettings: { ...SETTINGS, followSelection: false } });
  const f1 = (await snap()).focusCount;
  await stub('io.out.bits.result');
  await page.waitForTimeout(800);
  check('wave', (await snap()).focusCount === f1, 'wavesFollowSelection=false: a selection does not reach Surfer');
  await setIde({ waveSettings: SETTINGS });

  // `surfer` (the app): the UI only POSTs the selection, with the trace's test and the module path.
  await setIde({ waveViewer: 'surfer' });
  wave.posts = [];
  await stub('io.in.bits.b');
  await page.waitForTimeout(800);
  const want2 = { traceTest: TRACE_TEST, instPath: ['GCD'], selection: 'io.in.bits.b' };
  const bodies = wave.posts.map((x) => JSON.parse(x.body));
  check('wave', wave.posts.length === 1 && JSON.stringify(bodies[0]) === JSON.stringify(want2) && /application\/json/.test(wave.posts[0].type),
    `surfer mode POSTs /wave/select ${JSON.stringify(wave.posts)}`);
  check('wave', (await count(page, '[data-bottom-tab="wave"]')) === 0, 'surfer mode: no Waveform tab embedded');

  // `custom`: nothing is sent.
  await setIde({ waveViewer: 'custom' });
  wave.posts = [];
  await stub('io.out.valid');
  await page.waitForTimeout(800);
  check('wave', wave.posts.length === 0, `custom mode sends nothing ${JSON.stringify(wave.posts)}`);
  check('wave', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();

  // `surfer`, the panel (re)loaded on a trace: it POSTs the module in view, with no
  // selection, so the app shows it whatever it showed before.
  wave.ide = { ...wave.ide, waveViewer: 'surfer', trace: '/wave/t1/trace', traceTest: TRACE_TEST, waveSettings: SETTINGS, stamp: wave.ide.stamp + 1 };
  wave.posts = [];
  const reload = await open('/gcd/?host=vscode&theme=light');
  await waitLoaded(reload.page);
  for (let t = 0; t < 5000 && wave.posts.length === 0; t += 200) await reload.page.waitForTimeout(200);
  await reload.page.waitForTimeout(500);
  const loadBodies = wave.posts.map((x) => JSON.parse(x.body));
  check('wave', loadBodies.length === 1 && JSON.stringify(loadBodies[0]) === JSON.stringify({ traceTest: TRACE_TEST, instPath: ['GCD'], selection: null }),
    `surfer mode: a page load POSTs the current path ${JSON.stringify(loadBodies)}`);
  check('wave', reload.errors.length === 0, `no page errors ${reload.errors.join(' | ')}`);
  await reload.ctx.close();
  wave.ide = null;
}

// 9. The debug hook is installed only where the smoke needs it (embedded, or once
// the panel has a trace to show): a normal page without a trace has no
// window.__soclessWave.
{
  const { ctx, page } = await open('/');
  await waitLoaded(page);
  check('hook', (await page.evaluate(() => typeof window.__soclessWave)) === 'undefined', 'no __soclessWave on a normal page');
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures.length ? `FAILED: ${failures.length} check(s)` : 'embedded: all checks passed');
process.exit(failures.length ? 1 : 0);
