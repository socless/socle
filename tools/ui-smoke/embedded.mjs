// Embedded-mode check (VS Code panel): the UI loaded with ?host=vscode drops the
// chrome the editor already provides and follows the editor's theme; without the
// parameter every piece is still there.
//
// Embedded, the hierarchy and inspector panes are gone (VS Code's side panel shows
// them), and the UI talks to the parent page: it posts `socle-state` (design
// hierarchy only when the design changes, drill path, selection, details) and
// follows `socle-navigate`.
//
// Usage (from the repo root, after `./mill socle-ui.dist`, the elaborated designs
// `./mill example.socleRunGcd` and `./mill example.socleRunDeepSoc`, and the
// captured FSM, GCD and DeepSoc designs `./mill example.socleCapturedFsm`,
// `./mill example.socleCapturedGcd` and `./mill example.socleCapturedDeepSoc`):
//   node tools/ui-smoke/embedded.mjs --design out/example/socleRunGcd.dest/design.json \
//     --deep out/example/socleRunDeepSoc.dest/design.json \
//     --fsm out/example/socleCapturedFsm.dest/design.json \
//     --gcd out/example/socleCapturedGcd.dest/design.json \
//     --deepv out/example/socleCapturedDeepSoc.dest/design.json
//
// The Waveform check (8) stands in for the language server: it serves /ide.json
// (mutable), a ChiselSim GCD trace (tools/ui-smoke/fixtures/gcd-trace.vcd) and its
// names under /wave/<id>/, the vendored Surfer (socle-lsp/vendor/surfer, the
// files the server jar packs) under /surfer/, and records POST /wave/select.
// Check 10 frames the Surfer tab page (`?host=vscode&view=surfer`) and drives it with
// `socle-surfer-cmd` messages, as the extension does, on the same trace. Check 11
// frames the VS Code panel page, which then drives the Surfer tab by messages (the
// check answers as the tab), and check 12 shows the browser page keeps its Surfer.
// Check 8 runs the panel page unframed: with no parent to relay to, it keeps Surfer
// in its Waveform tab, as the browser does.
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
const UI = args.ui || 'out/socle-ui/dist.dest/socle.html';
const DESIGN = args.design || 'out/example/socleRunGcd.dest/design.json';
const DEEP = args.deep || 'out/example/socleRunDeepSoc.dest/design.json'; // a design with instances
const FSM = args.fsm || 'out/example/socleCapturedFsm.dest/design.json'; // an FSM block with a state diagram
const GCD = args.gcd || 'out/example/socleCapturedGcd.dest/design.json'; // a captured design with its signals
const DEEPV = args.deepv || 'out/example/socleCapturedDeepSoc.dest/design.json'; // a captured design with fold groups
const VCD = args.vcd || 'tools/ui-smoke/fixtures/gcd-trace.vcd'; // a ChiselSim (Verilator) trace of that design
const SURFER = args.surfer || 'socle-lsp/vendor/surfer'; // the vendored Surfer WASM build
const SHOTS = args.shots || null; // where to save the Waveform screenshot
for (const f of [UI, DESIGN, DEEP, FSM, GCD, DEEPV, VCD, `${SURFER}/surfer.js`, `${SURFER}/surfer_bg.wasm`])
  if (!existsSync(f)) { console.error(`missing ${f}`); process.exit(2); }

// The stand-in language server state for check 8: /ide.json (404 while null), the
// POST /wave/select and POST /open bodies, and how often each Surfer file was fetched.
const wave = { ide: null, posts: [], opens: [], surferJs: 0, surferWasm: 0, tabFetches: [] };
// Check 13's /ide.json, for the DeepSoc page whose Cluster modules name their Scala class.
const focusIde = { state: null };
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
  // DeepSoc as a capture names it: its two Cluster modules come from deephier.Cluster (check 13).
  '/focus/': ['text/html', readFileSync(UI)],
  '/focus/design.json': ['application/json', (() => {
    const d = JSON.parse(readFileSync(DEEP, 'utf8'));
    for (const m of d.modules) if (m.name === 'Cluster' || m.name === 'Cluster_1') m.scalaClass = 'deephier.Cluster';
    return JSON.stringify(d);
  })()],
  '/fsm/': ['text/html', readFileSync(UI)],
  '/fsm/design.json': ['application/json', readFileSync(FSM)],
  '/gcd/': ['text/html', readFileSync(UI)],
  '/gcd/design.json': ['application/json', readFileSync(GCD)],
  '/deepv/': ['text/html', readFileSync(UI)],
  '/deepv/design.json': ['application/json', readFileSync(DEEPV)],
  '/tab/': ['text/html', readFileSync(UI)], // the Surfer tab page (check 10): no design.json beside it
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
  if (path.startsWith('/tab/') && path !== '/tab/') wave.tabFetches.push(path); // the Surfer tab page fetches nothing of its own
  if (path === '/focus/ide.json') {
    if (!focusIde.state) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(focusIde.state)); return;
  }
  if (path === '/gcd/ide.json') {
    if (!wave.ide) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(wave.ide)); return;
  }
  if (req.method === 'POST' && (path.endsWith('/wave/select') || path.endsWith('/open'))) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      (path.endsWith('/open') ? wave.opens : wave.posts).push({ path, type: req.headers['content-type'], body });
      res.writeHead(204); res.end();
    });
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
// The same for the captured DeepSoc, whose Cluster folds its cores.
files['/parent-deepv.html'] = ['text/html', files['/parent-deep.html'][1].replace('/deep/?', '/deepv/?')];
const PARENT_DEEPV = `http://localhost:${server.address().port}/parent-deepv.html`;
// The VS Code Surfer tab stand-in: frames the Surfer-only page and records what it posts.
files['/parent-tab.html'] = ['text/html', `<!DOCTYPE html><body style="margin:0"><iframe id="f" src="${BASE}/tab/?host=vscode&view=surfer&theme=dark" style="width:1200px;height:700px;border:0"></iframe>
<script>
window.__msgs = [];
const f = document.getElementById('f');
addEventListener('message', (e) => { if (e.source === f.contentWindow) window.__msgs.push({ origin: e.origin, data: e.data }); });
</script></body>`];
const PARENT_TAB = `http://localhost:${server.address().port}/parent-tab.html`;
// The VS Code Socle panel stand-in on the GCD capture (check 11): records what the UI
// posts; the check answers as the extension and the Surfer tab would.
files['/parent-panel.html'] = ['text/html', `<!DOCTYPE html><body style="margin:0"><iframe id="f" src="${BASE}/gcd/?host=vscode&theme=light" style="width:1400px;height:800px;border:0"></iframe>
<script>
window.__msgs = [];
const f = document.getElementById('f');
addEventListener('message', (e) => { if (e.source === f.contentWindow) window.__msgs.push({ origin: e.origin, data: e.data }); });
</script></body>`];
const PARENT_PANEL = `http://localhost:${server.address().port}/parent-panel.html`;

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

// A standalone page's first-time tips are marked seen (they would sit over the
// schematic), unless `fresh` asks for an empty storage (check 1 shows them).
async function open(url, { fresh = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 800 } });
  if (!fresh && !url.includes('host=vscode'))
    await ctx.addInitScript(() => { try { localStorage.setItem('socle-tips', 'done'); } catch {} });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(m.text()); });
  await page.goto(url.startsWith('http') ? url : BASE + url);
  return { ctx, page, errors };
}
const count = (frame, sel) => frame.locator(sel).count();
const theme = (frame) => frame.evaluate(() => document.documentElement.getAttribute('data-theme'));

// The waveform colours chip next to Surfer's status: hovering it opens its legend.
// Returns the legend's rows (`<signals> <colour>`, from their data attributes, each
// checked against the text shown) and its text, or null when there is no chip.
async function legendRead(frame) {
  const chip = frame.locator('[data-wave-legend]').first();
  if (!(await chip.isVisible().catch(() => false))) return null;
  await chip.hover();
  await frame.locator('[data-wave-legend-card]').first().waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
  return frame.evaluate(() => {
    const c = document.querySelector('[data-wave-legend-card]');
    if (!c || c.offsetParent === null && getComputedStyle(c).position !== 'fixed') return null;
    const rows = Array.from(c.querySelectorAll('[data-wave-legend-row]')).map((e) => {
      const r = `${e.getAttribute('data-wave-legend-row')} ${e.getAttribute('data-wave-color')}`;
      return e.innerText.replace(/\s+/g, ' ').trim() === r ? r : `shown as ${JSON.stringify(e.innerText)}`;
    });
    return { rows, text: c.innerText.replace(/\s+/g, ' ') };
  });
}
const LEGEND = ['Inputs Blue', 'Outputs Green', 'Registers Orange', 'Wires Gray'];
const LEGEND_OFF = ['Ports Blue', 'Registers Orange', 'Wires Gray'];
const GROUPS = /per port.*bundles nested.*Registers.*Wires/;

async function waitLoaded(frame) {
  await frame.waitForSelector('[data-design-loaded="true"]', { timeout: 30000 });
  await frame.waitForSelector('[data-design-picker], [data-pane="schematic"]', { timeout: 30000 });
  await frame.waitForTimeout(500); // the designs.json index lands asynchronously
}

// 1. Normal mode (regression): everything present, bottom panel open.
{
  const { ctx, page, errors } = await open('/', { fresh: true });
  await waitLoaded(page);
  for (const [k, sel] of Object.entries(REMOVED)) check('normal', (await count(page, sel)) > 0, `${k} present`);
  check('normal', (await count(page, '[data-bottom-panel]')) === 1, 'bottom panel open');
  // First visit: the tips show one at a time; the last one's button closes them for good.
  check('normal', (await count(page, '[data-first-tips]')) === 1, 'first-time tips on the first visit');
  const seen = [];
  for (let i = 0; i < 8 && (await count(page, '[data-first-tips]')); i++) {
    seen.push(await page.locator('[data-first-tips-text]').textContent());
    await page.click('[data-first-tips-next]');
  }
  check('normal', seen.length === 4 && new Set(seen).size === 4, `four tips, then closed (${JSON.stringify(seen)})`);
  check('normal', (await page.evaluate(() => localStorage.getItem('socle-tips'))) === 'done', 'tips remembered as seen');
  await page.reload();
  await waitLoaded(page);
  check('normal', (await count(page, '[data-first-tips]')) === 0, 'no tips after a reload');
  await page.click('[data-settings-toggle]');
  check('normal', (await count(page, '[data-theme-option]')) > 0, 'theme gallery in settings');
  check('normal', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 1b. The language server's browser panel (`?panel=1`, Neovim and other
// editors): the standalone page, all its chrome kept, but its origin changes
// with each server port, so the tips never show and closing the hint bar lasts
// for the page's life only (nothing stored), as in the VS Code panel.
{
  const { ctx, page, errors } = await open('/?panel=1', { fresh: true });
  await waitLoaded(page);
  for (const [k, sel] of Object.entries(REMOVED)) check('editor panel', (await count(page, sel)) > 0, `${k} present`);
  check('editor panel', (await count(page, '[data-bottom-panel]')) === 1, 'bottom panel open');
  check('editor panel', (await count(page, '[data-nav-back]')) === 1 && (await count(page, '[data-nav-forward]')) === 1, 'Back / Forward buttons on the schematic');
  check('editor panel', (await count(page, '[data-first-tips]')) === 0, 'no first-time tips on a first visit');
  check('editor panel', await page.locator('[data-hint-bar]').isVisible(), 'hint bar shown');
  await page.click('[data-hint-close]', { timeout: 3000 }).catch(() => {});
  check('editor panel', !(await page.locator('[data-hint-bar]').isVisible()), 'hint bar closed');
  check('editor panel', (await page.evaluate(() => [localStorage.getItem('socle-hints'), localStorage.getItem('socle-tips')])).every((v) => v === null),
    'nothing stored for the hint bar or the tips');
  await page.reload();
  await waitLoaded(page);
  check('editor panel', await page.locator('[data-hint-bar]').isVisible(), 'hint bar back after a reload');
  check('editor panel', (await count(page, '[data-first-tips]')) === 0, 'no first-time tips after a reload');
  check('editor panel', errors.length === 0, `no page errors ${errors.join(' | ')}`);
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
  check('embedded', (await page.evaluate(() => localStorage.getItem('socle-theme'))) === null, 'theme not persisted');
  // Help: the hint bar shows, the first-time tips never do; closing the bar
  // lasts for the page's life only.
  check('embedded', await page.locator('[data-hint-bar]').isVisible(), 'hint bar shown');
  check('embedded', (await count(page, '[data-first-tips]')) === 0, 'no first-time tips');
  await page.click('[data-hint-close]', { timeout: 3000 }).catch(() => {});
  check('embedded', !(await page.locator('[data-hint-bar]').isVisible()), 'hint bar closed');
  check('embedded', (await page.evaluate(() => [localStorage.getItem('socle-hints'), localStorage.getItem('socle-tips')])).every((v) => v === null),
    'nothing stored for the hint bar or the tips');
  await page.reload();
  await waitLoaded(page);
  check('embedded', await page.locator('[data-hint-bar]').isVisible(), 'hint bar back after a reload');
  check('embedded', (await count(page, '[data-first-tips]')) === 0, 'no first-time tips after a reload');
  for (const sel of ['[data-search-trigger]', '[data-help-trigger]', '[data-toggle-pane="bottom"]', '[data-settings-toggle]', '[data-nav-back]', '[data-nav-forward]'])
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
  await page.evaluate(() => window.postMessage({ type: 'socle-theme', kind: 'light' }, '*'));
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

// 3b. A narrow editor panel (#12): the top bar and the schematic's toolbar fit its width
// (their labels shrink, the toolbar wraps), so the page shows no scrollbar.
for (const width of [620, 360]) {
  const { ctx, page } = await open('/?host=vscode&theme=dark');
  await page.setViewportSize({ width, height: 700 });
  await waitLoaded(page);
  const over = await page.evaluate(() => {
    const out = [];
    const bar = document.querySelector('.scl-topbar');
    const chips = document.querySelector('[data-module-chip]')?.parentElement;
    for (const e of [...(bar?.children ?? []), ...(chips?.children ?? [])]) {
      const r = e.getBoundingClientRect();
      if (r.width > 0 && r.right > innerWidth + 0.5) out.push(`${e.className || e.tagName} ends at ${Math.round(r.right)}`);
    }
    return { bar: !!bar, chips: !!chips, out };
  });
  check(`narrow ${width}`, over.bar && over.chips && over.out.length === 0,
    `top bar and toolbar within ${width} px ${JSON.stringify(over)}`);
  await ctx.close();
}

// 4. Live switch: the parent page posts socle-theme to the iframe.
{
  const { ctx, page, errors } = await open(PARENT);
  const frame = page.frameLocator('#f');
  await frame.locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  check('live', (await theme(f)) === null, 'starts light');
  for (const [kind, want] of [['dark', 'dark'], ['bogus', 'dark'], ['hc', 'contrast'], ['light', null]]) {
    await page.evaluate((k) => document.getElementById('f').contentWindow.postMessage({ type: 'socle-theme', kind: k }, '*'), kind);
    await page.waitForTimeout(200);
    check('live', (await theme(f)) === want, `parent posts ${kind} -> data-theme=${want}`);
  }
  await f.evaluate(() => window.postMessage({ type: 'socle-theme', kind: 'dark' }, '*'));
  await page.waitForTimeout(200);
  check('live', (await theme(f)) === null, 'message from the frame itself ignored');
  check('live', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 5. The side panel channel (DeepSoc): socle-state to the parent on load, drill and select;
// socle-navigate from the parent drills and selects; forged or malformed messages are ignored.
{
  const { ctx, page, errors } = await open(PARENT_DEEP);
  const frame = page.frameLocator('#f');
  await frame.locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  const states = () => page.evaluate(() => window.__msgs.filter((m) => m.data && m.data.type === 'socle-state'));
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
  await navigate({ type: 'socle-navigate', path: ['DeepSoc', 'cluster0'], select: null });
  const drilled = await waitFor('the drilled state', (d) => JSON.stringify(d.path) === '["DeepSoc","cluster0"]');
  check('side', (await crumb()) === 'cluster0', 'navigate drills (breadcrumb cluster0)');
  await page.waitForTimeout(300);
  check('side', (await states()).length === n0 + 1, `one state per navigate (${(await states()).length - n0})`);
  check('side', !!drilled && !('design' in drilled.data) && drilled.data.details.module === 'Cluster',
    'a drill posts path and details, not the hierarchy again');

  const n1 = (await states()).length;
  await navigate({ type: 'socle-navigate', path: ['DeepSoc'], select: 'pipe' });
  const sel = await waitFor('the selected state', (d) => d.selection === 'pipe');
  await page.waitForTimeout(300);
  check('side', (await states()).length === n1 + 1, `one state for a drill-and-select navigate (${(await states()).length - n1})`);
  check('side', (await crumb()) === 'DeepSoc', 'navigate back to the top');
  check('side', !!sel && sel.data.details.name === 'pipe' && sel.data.details.module === 'Queue4_Token', `details of the selected instance ${JSON.stringify(sel && sel.data.details).slice(0, 200)}`);

  // Hover feedback, embedded: resting on a box outlines it and, after 600 ms,
  // shows its tooltip card; neither posts a state to the side panel.
  {
    const nh = (await states()).length;
    // The view settled first: the navigate's fit (or a relayout landing) moves the
    // box, and a box that moves under a still pointer gets no hover.
    await f.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
    await f.waitForFunction(() => !(window.__socleFlying && window.__socleFlying()), null, { timeout: 10000 }).catch(() => {});
    const rect = () => f.locator('[data-box-inst="cluster1"]').first().boundingBox();
    let bb = await rect();
    for (let i = 0; i < 25; i++) {
      await page.waitForTimeout(150);
      const nb = await rect();
      if (JSON.stringify(nb) === JSON.stringify(bb)) break;
      bb = nb;
    }
    await page.mouse.move(bb.x + 20, bb.y + 3);
    await page.waitForTimeout(900);
    const h = await f.evaluate(() => {
      const t = document.querySelector('[data-tooltip]');
      return { outlined: document.querySelector('[data-box-inst="cluster1"]').classList.contains('ohov'),
               tip: t && getComputedStyle(t).display !== 'none' ? t.textContent : null };
    });
    // What the pointer was on, should it miss: the box then and now, and the element there.
    const missed = async () => {
      const now = await rect();
      const fb = await page.locator('#f').boundingBox();
      const under = await f.evaluate(([x, y]) => {
        const e = document.elementFromPoint(x, y);
        return e ? `${e.tagName}.${e.className && e.className.baseVal !== undefined ? e.className.baseVal : e.className}` : null;
      }, [bb.x + 20 - fb.x, bb.y + 3 - fb.y]);
      return JSON.stringify({ moved: bb, now, under });
    };
    check('side', h.outlined, `resting on a box outlines it${h.outlined ? '' : ' ' + (await missed())}`);
    check('side', !!h.tip && h.tip.startsWith('cluster1') && /Double-click: open/.test(h.tip), `resting on a box shows its card (${h.tip})`);
    await page.mouse.move(2, 2);
    await page.waitForTimeout(150);
    check('side', (await states()).length === nh, 'hovering posts no state');
  }

  // The box header (its centre can be a port row, which expands instead of selecting).
  await f.locator('[data-box-inst="cluster1"]').first().click({ position: { x: 20, y: 3 } });
  const clicked = await waitFor('the clicked state', (d) => d.selection === 'cluster1');
  check('side', !!clicked && clicked.data.details.module === 'Cluster_1', 'a box click posts its selection and details');

  const n = (await states()).length;
  await f.evaluate(() => window.postMessage({ type: 'socle-navigate', path: ['DeepSoc', 'cluster0'], select: null }, '*'));
  for (const bad of [{ type: 'socle-navigate', path: 'DeepSoc' }, { type: 'socle-navigate', path: ['DeepSoc', 'nope'] },
    { type: 'socle-navigate', path: ['DeepSoc', 7] }, { type: 'socle-navigate', path: ['DeepSoc'], select: 3 }]) await navigate(bad);
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
  const id = 'fsm$TrafficFsm$switch_state$0';
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
  const snap = () => page.evaluate(() => window.__socleWave ? window.__socleWave.snapshot() : null);
  const waitSnap = async (what, pred, ms = 20000) => {
    for (let t = 0; t < ms; t += 200) { const s = await snap(); if (s && pred(s)) return s; await page.waitForTimeout(200); }
    check('wave', false, `timed out waiting for ${what}: ${JSON.stringify(await snap())}`);
    return null;
  };
  const P = (leaf) => `${PREFIX}.${leaf}`;
  // GCD's edge stubs are its ports `io.in` and `io.out` (#332): a click on a
  // field's port selects that port. A focus may have flown the view so that
  // the stub lies outside the canvas (a transform, nothing to scroll): fit first.
  const stub = async (id) => {
    const loc = page.locator(`[data-boundary-stub="${id.split('.').slice(0, 2).join('.')}"]`).first();
    const shown = await loc.evaluate((e) => {
      const r = e.getBoundingClientRect();
      const v = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
      return r.top >= v.top && r.bottom <= v.bottom && r.left >= v.left && r.right <= v.right;
    }).catch(() => true);
    if (!shown) { await page.locator('button[title="Fit to view"]').first().click(); await page.waitForTimeout(400); }
    await loc.click();
  };

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
  const items = await page.evaluate(() => window.__socleWave.items()).catch(() => []);
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
  // The colours chip beside Surfer's status: hovering it says what each colour
  // means and how the groups are built; the keyboard reaches it too.
  {
    const leg = await legendRead(page);
    check('wave', !!leg && JSON.stringify(leg.rows) === JSON.stringify(LEGEND) && GROUPS.test(leg.text),
      `the colours chip's legend ${JSON.stringify(leg)}`);
    await page.mouse.move(5, 5);
    await page.waitForTimeout(300);
    check('wave', !(await page.locator('[data-wave-legend-card]').first().isVisible().catch(() => false)), 'the legend closes when the pointer leaves');
    await page.locator('[data-wave-legend]').first().focus().catch(() => {});
    await page.keyboard.press('Enter');
    const byKey = await page.locator('[data-wave-legend-card]').first().isVisible().catch(() => false);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    const closed = !(await page.locator('[data-wave-legend-card]').first().isVisible().catch(() => false));
    check('wave', byKey && closed, `the chip opens by keyboard and Esc closes it (open ${byKey}, closed ${closed})`);
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
  }
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
  // A click on the io.out stub focuses the port's first field; a field (a row
  // of the stub, no node of its own) through the hook.
  await focusAfter('port io.out', () => stub('io.out'), P('io_out_valid'));
  await focusAfter('port io.out.bits.result', () => page.evaluate(() => window.__socleWave.select('io.out.bits.result')), P('io_out_bits_result'));
  await focusAfter('block blk$GCD$logic', () => page.locator('[data-block="blk$GCD$logic"]').first().click({ position: { x: 20, y: 5 } }), P('io_in_ready'));
  await focusAfter('wire from when$GCD$when_io_in_fire$0.io_in_bits_a', () => page.locator('[data-wire="when$GCD$when_io_in_fire$0.io_in_bits_a"]').first().dispatchEvent('click'), P('io_in_bits_a'));
  await focusAfter('bundle io.out', () => page.evaluate(() => window.__socleWave.select('io.out')), P('io_out_valid'));
  // A focus below the visible rows scrolls it into view (ScrollToItem, at its visible
  // index): a short panel, then the last signal.
  {
    const h = await page.locator('[data-bottom-resize]').boundingBox();
    await page.mouse.move(h.x + 100, h.y + 3);
    await page.mouse.down();
    await page.mouse.move(h.x + 100, h.y + 150, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(1000); // Surfer redraws at the new size
    const rows = (await page.evaluate(() => window.__socleWave.items()).catch(() => [])).map((i) => i.trace);
    await focusAfter('register busy (the last row)', () => page.evaluate(() => window.__socleWave.select('busy')), P('busy'));
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
  await focusAfter('port io.in.bits.a after closing the panel', () => page.evaluate(() => window.__socleWave.select('io.in.bits.a')), P('io_in_bits_a'));

  // Surfer's right-click actions (WCP events; the hook stands in for its menu):
  // Go to declaration of a register selects its logic block and opens its source,
  // and Surfer is left as it is (the reveal is not followed); Add drivers of a
  // signal whose drivers are shown adds nothing.
  {
    wave.opens = [];
    const before = await snap();
    await page.evaluate((v) => window.__socleWave.event('goto_declaration', v), P('x'));
    await page.waitForTimeout(800);
    const v = await page.evaluate(() => window.__socleWave.view());
    check('wave', v.path === '["GCD"]' && v.selection === 'x', `Go to declaration of x selects the register ${JSON.stringify(v)}`);
    const opened = wave.opens.map((o) => JSON.parse(o.body));
    check('wave', opened.length === 1 && /GCD\.scala$/.test(opened[0].file) && opened[0].line === 30, `and opens x's declaration ${JSON.stringify(opened)}`);
    const after = await snap();
    check('wave', after.idle && after.focusCount === before.focusCount, `the reveal's selection is not followed (focus ${before.focusCount} -> ${after.focusCount})`);
    await page.evaluate((v) => window.__socleWave.event('add_drivers', v), P('io_out_bits_result'));
    await page.waitForTimeout(800);
    const added = await snap();
    check('wave', added.idle && added.addCount === before.addCount, `Add drivers of a signal whose drivers are shown adds nothing (${added.addCount})`);
    check('wave', wave.opens.length === 1, 'the selection the reveal made opened nothing more');
  }

  // Folded bundles (wavesFoldBundles): the plan is redone with its bundle groups
  // folded (GroupFold); a focus inside unfolds them first (GroupUnfold).
  const visible = async () => (await page.evaluate(() => window.__socleWave.items()).catch(() => []))
    .map((i) => (i.type === 'Group' ? `group ${i.name}` : i.trace));
  await page.evaluate(() => window.__socleWave.select(null)); // no selection: no focus unfolds a group
  await setIde({ waveSettings: { ...SETTINGS, foldBundles: true } });
  await waitSnap('the folded plan', (s) => s.idle);
  const foldedItems = await visible();
  const wantFolded = ['group io', 'group io.in', 'group io.out', 'group Registers', P('x'), P('y'), P('busy')];
  check('wave', JSON.stringify(foldedItems) === JSON.stringify(wantFolded), `bundle groups folded ${JSON.stringify(foldedItems)}`);
  await focusAfter('port io.in.bits.a in a folded bundle', () => page.evaluate(() => window.__socleWave.select('io.in.bits.a')), P('io_in_bits_a'));
  const unfolded = await visible();
  check('wave', unfolded.includes(P('io_in_bits_a')) && unfolded.includes(P('io_in_bits_b')) && !unfolded.includes(P('io_out_valid')),
    `the focus unfolded its groups only ${JSON.stringify(unfolded)}`);
  await setIde({ waveSettings: SETTINGS });
  await waitSnap('the unfolded plan', (s) => s.idle);

  // Colour by direction off: the legend says the ports are all one colour.
  await setIde({ waveSettings: { ...SETTINGS, colorByDirection: false } });
  await waitSnap('the plan in one port colour', (s) => s.idle);
  const legOff = await legendRead(page);
  check('wave', !!legOff && JSON.stringify(legOff.rows) === JSON.stringify(LEGEND_OFF) && /ports are all Blue/.test(legOff.text),
    `colour by direction off: the legend says ports are all Blue ${JSON.stringify(legOff)}`);
  await page.mouse.move(5, 5);
  await setIde({ waveSettings: SETTINGS });
  await waitSnap('the plan coloured by direction', (s) => s.idle);

  // A burst of selections: only the last one reaches Surfer (debounced, one exchange at a time).
  const burst0 = (await snap()).focusCount;
  await page.evaluate(() => ['io.in.valid', 'io.in.ready', 'io.out.valid', 'io.in.bits.b'].forEach((x) => window.__socleWave.select(x)));
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
  const items2 = await page.evaluate(() => window.__socleWave.items()).catch(() => []);
  check('wave', !items2.some((i) => i.trace === P('y')) && items2.some((i) => i.trace === P('x')), 'the missing register is not shown, the others are');
  check('wave', wave.surferJs === 1 && wave.surferWasm === 1 && (await snap()).boots === 1, `Surfer booted once (js ${wave.surferJs}, wasm ${wave.surferWasm})`);

  // A trace whose names are unknown (as an FST's): the plan keeps every signal and
  // Surfer drops the one it lacks; the rest of its group is still matched by name.
  await setIde({ trace: '/wave/t3/trace' });
  await waitSnap('the third trace', (s) => s.idle && s.shown && s.shown.trace.endsWith('/wave/t3/trace'), 30000);
  const missing3 = await page.locator('[data-wave-missing]').innerText().catch(() => '');
  check('wave', missing3.trim() === '1 signal not in the trace', `a path Surfer dropped is counted missing (${JSON.stringify(missing3)})`);
  const items3 = await page.evaluate(() => window.__socleWave.items()).catch(() => []);
  const regs3 = items3.filter((i) => i.type !== 'Group').map((i) => i.trace);
  check('wave', regs3.includes(P('x')) && regs3.includes(P('busy')) && !regs3.includes(P('y')) && !regs3.includes(null),
    `the dropped register's group keeps its other signals, each matched ${JSON.stringify(regs3.slice(-3))}`);
  await focusAfter('register busy beside a dropped one', () => page.evaluate(() => window.__socleWave.select('busy')), P('busy'));

  // The schematic's navigation keys never act from the Surfer canvas, and do
  // with the focus on the body (Surfer loaded, so its own key handling is live).
  {
    const view = () => page.evaluate(() => document.querySelector('[data-content]')?.style.transform || '');
    const keys = async () => { for (const k of ['ArrowRight', 'ArrowDown', '-', 'f']) await page.keyboard.press(k); await page.waitForTimeout(100); };
    const focused = await page.evaluate(() => {
      const c = document.querySelector('[data-surfer-pane] canvas');
      if (!c) return 'no canvas';
      c.focus();
      return document.activeElement === c ? 'canvas' : `not focusable (${document.activeElement && document.activeElement.tagName})`;
    });
    if (focused !== 'canvas') {
      // Not focusable by script: a click in it is how a user gives it the focus.
      const box = await page.locator('[data-surfer-pane] canvas').first().boundingBox().catch(() => null);
      if (box) await page.mouse.click(box.x + box.width - 20, box.y + box.height - 20);
    }
    const inCanvas = await page.evaluate(() => !!document.activeElement && !!document.activeElement.closest('[data-surfer-pane]'));
    check('wave', inCanvas, `the Surfer canvas takes the focus (${focused})`);
    const a = await view();
    await keys();
    const b = await view();
    check('wave', a !== '' && a === b, `keys in the Surfer canvas leave the schematic alone (${a} -> ${b})`);
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(100);
    const c = await view();
    check('wave', c !== b, `ArrowRight with the focus on the body pans the schematic (${b} -> ${c})`);
    await page.keyboard.press('ArrowLeft');
    await page.waitForTimeout(100);
  }

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
  // A field has no node of its own to click (it is a row of the io.in stub).
  await page.evaluate(() => window.__socleWave.select('io.in.bits.b'));
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
// window.__socleWave.
{
  const { ctx, page } = await open('/');
  await waitLoaded(page);
  check('hook', (await page.evaluate(() => typeof window.__socleWave)) === 'undefined', 'no __socleWave on a normal page');
  await ctx.close();
}

// 10. The Surfer tab page (`?host=vscode&view=surfer`): only Surfer, driven by its
// parent's `socle-surfer-cmd` messages, answering `socle-surfer-status`. It
// fetches no design and no /ide.json; a Load then a Show of GCD's top plan shows
// its 16 rows; Hello reports the state; Focus reaches the pane; self-posted and
// malformed commands are ignored.
{
  const TRACE = `${BASE}/wave/t1/trace`;
  const P = (leaf) => `${PREFIX}.${leaf}`;
  const it = (leaf, label, color, width) => ({ trace: P(leaf), label, color, width });
  const g = (name, items, children = []) => ({ name, items, children, folded: false });
  // GCD's top plan as WaveLayout makes it (the default settings).
  const plan = { missing: 0, groups: [
    g('io', [], [
      g('in', [it('io_in_valid', 'valid', 'Blue', 1), it('io_in_ready', 'ready', 'Green', 1)],
        [g('bits', [it('io_in_bits_a', 'a', 'Blue', 16), it('io_in_bits_b', 'b', 'Blue', 16)])]),
      g('out', [it('io_out_valid', 'valid', 'Green', 1), it('io_out_ready', 'ready', 'Blue', 1)],
        [g('bits', [it('io_out_bits_result', 'result', 'Green', 16)])]),
    ]),
    g('Registers', [it('x', 'x', 'Orange', 16), it('y', 'y', 'Orange', 16), it('busy', 'busy', 'Orange', 1)]),
  ] };
  const want = ['group io', 'group io.in', P('io_in_valid'), P('io_in_ready'), 'group io.in.bits', P('io_in_bits_a'), P('io_in_bits_b'),
    'group io.out', P('io_out_valid'), P('io_out_ready'), 'group io.out.bits', P('io_out_bits_result'),
    'group Registers', P('x'), P('y'), P('busy')];

  wave.tabFetches = [];
  const js0 = wave.surferJs;
  const { ctx, page, errors } = await open(PARENT_TAB);
  const mounted = await page.frameLocator('#f').locator('[data-surfer-pane]').waitFor({ timeout: 30000 }).then(() => true).catch(() => false);
  check('tab', mounted, 'the Surfer tab page mounts its pane');
  const f = page.frames().find((x) => x !== page.mainFrame());
  const statuses = () => page.evaluate(() => window.__msgs.filter((m) => m.data && m.data.type === 'socle-surfer-status'));
  const waitStatus = async (what, pred, ms = 30000) => {
    for (let t = 0; t < ms; t += 200) {
      const s = (await statuses()).find((m) => pred(m.data.status));
      if (s) return s;
      await page.waitForTimeout(200);
    }
    check('tab', false, `timed out waiting for ${what}: ${JSON.stringify((await statuses()).map((m) => m.data.status)).slice(-400)}`);
    return null;
  };
  const cmd = (c) => page.evaluate((x) => document.getElementById('f').contentWindow.postMessage({ type: 'socle-surfer-cmd', cmd: x }, '*'), c);

  const first = await waitStatus('the first status', () => true, 10000);
  check('tab', !!first && first.origin === BASE && first.data.status.state === 'idle', `the page reports idle at start ${JSON.stringify(first)}`);
  check('tab', (await count(f, '[data-surfer-pane]')) === 1 && (await count(f, 'canvas')) === 1, 'one Surfer canvas');
  for (const sel of ['[data-pane="schematic"]', '[data-bottom-panel]', '[data-search-trigger]', '[data-settings-toggle]', '[data-design-loaded]'])
    check('tab', (await count(f, sel)) === 0, `no ${sel}`);
  check('tab', (await theme(f)) === 'dark', 'theme from ?theme=dark');

  // Ignored: posted by the frame to itself, or malformed.
  await f.evaluate((u) => window.postMessage({ type: 'socle-surfer-cmd', cmd: { $type: 'Load', url: u } }, '*'), TRACE);
  for (const bad of [{}, 'Load', { $type: 'Nope' }, { $type: 'Load' }, null]) await cmd(bad);
  await page.evaluate(() => document.getElementById('f').contentWindow.postMessage({ type: 'socle-surfer-cmd' }, '*'));
  await page.waitForTimeout(600);
  check('tab', wave.surferJs === js0 && (await statuses()).every((m) => m.data.status.state === 'idle'),
    `self-posted and malformed commands ignored (surfer.js fetched ${wave.surferJs - js0}x)`);

  // Load, then Show: Surfer boots, loads the trace and shows the plan.
  await cmd({ $type: 'Load', url: TRACE });
  await cmd({ $type: 'Show', seq: 1, plan, path: ['GCD'], trace: TRACE });
  const done = await waitStatus('the shown seq 1', (s) => s.shownSeq === 1, 120000);
  check('tab', !!done && done.data.status.state === 'ready' && done.data.status.loadedTrace === TRACE && done.data.status.dropped === 0 && !done.data.status.shownSeqFailed,
    `seq 1 shown, ready, nothing dropped ${JSON.stringify(done && done.data.status)}`);
  const items = await f.evaluate(() => window.__socleWave.items()).catch(() => []);
  const got = items.map((i) => (i.type === 'Group' ? `group ${i.name}` : i.trace || `? ${i.name}`));
  check('tab', items.length === 16 && JSON.stringify(got) === JSON.stringify(want),
    `Surfer shows GCD's 16 rows\n      got  ${JSON.stringify(got)}\n      want ${JSON.stringify(want)}`);

  // Hello: the page reports its state again.
  const n0 = (await statuses()).length;
  await cmd({ $type: 'Hello' });
  await page.waitForTimeout(400);
  const hello = (await statuses()).slice(n0);
  check('tab', hello.length === 1 && hello[0].data.status.state === 'ready' && hello[0].data.status.loadedTrace === TRACE,
    `Hello answers one status ${JSON.stringify(hello.map((m) => m.data.status))}`);

  // Focus reaches the pane.
  await cmd({ $type: 'Focus', trace: P('busy') });
  let focus = null;
  for (let t = 0; t < 20000 && !focus; t += 200) {
    const s = await f.evaluate(() => window.__socleWave.snapshot());
    if (s.idle && s.lastFocus) focus = s.lastFocus; else await page.waitForTimeout(200);
  }
  check('tab', !!focus && focus.trace === P('busy') && focus.ok, `Focus reaches Surfer ${JSON.stringify(focus)}`);

  // The colours chip in the status line: the legend of the plan shown, which says
  // how it was coloured (the page has no settings of its own).
  const leg = await legendRead(f);
  check('tab', !!leg && JSON.stringify(leg.rows) === JSON.stringify(LEGEND) && GROUPS.test(leg.text), `the colours chip's legend ${JSON.stringify(leg)}`);
  await page.mouse.move(5, 5);
  const blue = (gr) => ({ ...gr, items: gr.items.map((i) => ({ ...i, color: 'Blue' })), children: gr.children.map(blue) });
  await cmd({ $type: 'Show', seq: 2, plan: { ...plan, groups: [blue(plan.groups[0]), plan.groups[1]], colorByDirection: false }, path: ['GCD'], trace: TRACE });
  await waitStatus('the shown seq 2', (s) => s.shownSeq === 2, 30000);
  const legOff = await legendRead(f);
  check('tab', !!legOff && JSON.stringify(legOff.rows) === JSON.stringify(LEGEND_OFF) && /ports are all Blue/.test(legOff.text),
    `a plan coloured without direction: ports are all Blue ${JSON.stringify(legOff)}`);
  await page.mouse.move(5, 5);
  // Add (Surfer's Add drivers or Add loads, sent by the panel): the signals not
  // shown go in below the one right-clicked, the first one focused; one shown
  // already is skipped.
  {
    const small = { missing: 0, groups: [g('Registers', [it('x', 'x', 'Orange', 16)])] };
    await cmd({ $type: 'Show', seq: 3, plan: small, path: ['GCD'], trace: TRACE });
    await waitStatus('the shown seq 3', (s) => s.shownSeq === 3, 30000);
    await cmd({ $type: 'Add', traces: [P('x'), P('busy'), P('io_out_bits_result')], after: P('x') });
    let s = null;
    for (let t = 0; t < 20000 && !(s && s.idle && s.addCount > 0); t += 200) {
      s = await f.evaluate(() => window.__socleWave.snapshot());
      if (!(s.idle && s.addCount > 0)) await page.waitForTimeout(200);
    }
    const rows = (await f.evaluate(() => window.__socleWave.items()).catch(() => [])).filter((i) => i.type !== 'Group').map((i) => i.trace);
    check('tab', JSON.stringify(rows) === JSON.stringify([P('x'), P('busy'), P('io_out_bits_result')]) &&
      JSON.stringify(s.lastAdd) === JSON.stringify([P('busy'), P('io_out_bits_result')]) && s.lastFocus && s.lastFocus.trace === P('busy'),
      `Add shows the signals not shown, the first focused ${JSON.stringify({ rows, lastAdd: s && s.lastAdd, focus: s && s.lastFocus })}`);
    // A right-click action in the tab's Surfer goes up as a status carrying it.
    await f.evaluate((v) => window.__socleWave.event('add_loads', v), P('x'));
    const ev = await waitStatus('the status with the event', (st) => !!st.event, 5000);
    check('tab', !!ev && ev.data.status.event.kind === 'add_loads' && ev.data.status.event.variable === P('x') && ev.data.status.loadedTrace === TRACE,
      `an action in the tab's Surfer posts a status with its event ${JSON.stringify(ev && ev.data.status)}`);
  }
  check('tab', wave.tabFetches.length === 0, `no design or /ide.json fetched ${JSON.stringify(wave.tabFetches)}`);
  // Surfer (egui) focuses its hidden text input; Chromium refuses that in a cross-origin
  // frame (as the VS Code webview is) and logs it as an error: not a page fault.
  const pageErrors = errors.filter((e) => !/^Blocked autofocusing on a <input> element in a cross-origin subframe/.test(e));
  check('tab', pageErrors.length === 0, `no page errors ${pageErrors.join(' | ')}`);
  await ctx.close();
}

// 11. The VS Code panel (framed, `surfer-panel`) drives Surfer in another tab: on load
// it posts a `Hello` (a tagged object) to learn the tab's state; a new trace posts
// `socle-surfer-open` (with the trace and its test, unforced), then the `Load` and the
// `Show` of the top plan (`socle-surfer-cmd`, its seq seeded per page); the new tab's
// first status (no trace yet) gets them once more; the Waveform tab mounts no Surfer, says what the tab
// reports and has a Show Surfer button (a forced open); a stale status changes nothing;
// a selection posts `Focus`; after `socle-surfer-closed` nothing is sent until a
// fresh tab reports, which gets the trace and the plan again; an error is retried once
// per tab. 11b: a reloaded panel whose Hello is answered `socle-surfer-closed` (no tab)
// says "Surfer: closed", and its seq starts elsewhere.
let seq1 = -1;
{
  const TEST = 'GCD computes gcd(48, 18)';
  const TRACE_TEST = `gcd.GcdSpec#${TEST}`;
  const SETTINGS = { followSelection: true, showClockReset: false, foldBundles: false, showWires: true, colorByDirection: true, followDelayMs: 150 };
  wave.ide = { status: 'ok', message: '', focusClass: '', test: TEST, suite: 'gcd.GcdSpec', stamp: 100, waveViewer: 'surfer-panel', trace: '', traceTest: '', waveSettings: SETTINGS };
  const setIde = async (patch) => { wave.ide = { ...wave.ide, ...patch, stamp: wave.ide.stamp + 1 }; await new Promise((r) => setTimeout(r, 2000)); };
  const P = (leaf) => `${PREFIX}.${leaf}`;
  const js0 = wave.surferJs;
  const { ctx, page, errors } = await open(PARENT_PANEL);
  await page.frameLocator('#f').locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  const msgs = () => page.evaluate(() => window.__msgs.map((m) => m.data)
    .filter((d) => d && typeof d.type === 'string' && d.type.startsWith('socle-surfer')));
  const kind = (m) => (m.type === 'socle-surfer-cmd' ? (m.cmd && m.cmd.$type) : m.type);
  const after = async (n) => (await msgs()).slice(n);
  const waitMsgs = async (what, n, pred, ms = 20000) => {
    for (let t = 0; t < ms; t += 200) { const xs = await after(n); if (pred(xs)) return xs; await page.waitForTimeout(200); }
    check('remote', false, `timed out waiting for ${what}: ${JSON.stringify((await after(n)).map(kind))}`);
    return null;
  };
  const toUi = (m) => page.evaluate((x) => document.getElementById('f').contentWindow.postMessage(x, '*'), m);
  const status = (s) => toUi({ type: 'socle-surfer-status', status: s });
  const stub = (id) => f.locator(`[data-boundary-stub="${id.split('.').slice(0, 2).join('.')}"]`).first().click();

  await f.waitForTimeout(500);
  const hello = await waitMsgs('the Hello', 0, (xs) => xs.length > 0, 5000);
  await f.waitForTimeout(300);
  check('remote', !!hello && JSON.stringify(await msgs()) === '[{"type":"socle-surfer-cmd","cmd":{"$type":"Hello"}}]',
    `no trace: only a Hello posted, as a tagged object ${JSON.stringify(await msgs())}`);
  const base = (await msgs()).length;
  await f.click('[data-toggle-pane="bottom"]');
  await f.click('[data-bottom-tab="wave"]').catch(() => {});
  const hint = await f.locator('[data-wave-hint]').innerText().catch(() => '');
  check('remote', /Simulate/.test(hint) && /Surfer tab/.test(hint) && !/here/.test(hint), `no trace: the hint points at the Surfer tab (${JSON.stringify(hint)})`);
  await f.click('[data-toggle-pane="bottom"]'); // closed again, as the rest expects

  // A new trace: open the tab, then Load, then the top plan's Show (seq 1).
  await setIde({ trace: '/wave/t1/trace', traceTest: TRACE_TEST });
  const first = await waitMsgs('open, Load and Show', base, (xs) => xs.some((m) => kind(m) === 'Show'));
  await page.waitForTimeout(500);
  const xs = await after(base);
  check('remote', JSON.stringify(xs.map(kind)) === '["socle-surfer-open","Load","Show"]', `a new trace posts open, Load, Show ${JSON.stringify(xs.map(kind))}`);
  const load = xs.find((m) => kind(m) === 'Load');
  const show = xs.find((m) => kind(m) === 'Show');
  const url = load && load.cmd.url;
  check('remote', !!first && !!url && url === `${BASE}/wave/t1/trace`, `Load of the absolute trace (${url})`);
  const open0 = xs.find((m) => m.type === 'socle-surfer-open');
  check('remote', !!open0 && open0.trace === url && open0.traceTest === TRACE_TEST && open0.force === false,
    `the new trace's open names it and its test, unforced ${JSON.stringify(open0)}`);
  seq1 = show ? show.cmd.seq : -1;
  check('remote', !!show && seq1 > 2 && JSON.stringify(show.cmd.path) === '["GCD"]' && show.cmd.trace === url &&
    JSON.stringify(show.cmd.plan.groups.map((g) => g.name)) === '["io","Registers"]',
    `Show (seq seeded per page, not 1) of GCD's top plan ${JSON.stringify(show && { seq: show.cmd.seq, path: show.cmd.path, groups: show.cmd.plan.groups.map((g) => g.name) })}`);
  check('remote', wave.surferJs === js0, 'no Surfer loaded in the panel');

  // The new tab's first status (no closed before it: the real first open) reports no trace
  // yet: the panel sends the trace and the plan again, exactly once.
  const nf = (await msgs()).length;
  await status({ state: 'idle', loadedTrace: '', shownSeq: null, dropped: null });
  const fr = await waitMsgs('the fresh tab resync', nf, (ys) => ys.some((m) => kind(m) === 'Show'));
  await page.waitForTimeout(1000);
  const frs = await after(nf);
  const show2 = frs.find((m) => kind(m) === 'Show');
  check('remote', !!fr && JSON.stringify(frs.map(kind)) === '["Load","Show"]' && frs[0].cmd.url === url && show2.cmd.seq === seq1 + 1,
    `a fresh tab's first status gets one Load and one Show ${JSON.stringify(frs.map((m) => [kind(m), m.cmd && (m.cmd.url || m.cmd.seq)]))}`);
  const seq2 = show2 ? show2.cmd.seq : -1;

  // The Waveform tab: no Surfer canvas, a Show Surfer button that asks for the tab.
  await f.click('[data-toggle-pane="bottom"]');
  await f.click('[data-bottom-tab="wave"]').catch(() => {});
  check('remote', (await f.locator('[data-wave-pane]').isVisible().catch(() => false)) && (await count(f, '[data-surfer-pane]')) === 0,
    'the Waveform tab shows and mounts no Surfer');
  const btn = f.locator('[data-show-surfer]');
  check('remote', (await btn.count()) === 1 && /Show Surfer/.test(await btn.innerText().catch(() => '')), 'a Show Surfer button');
  const n0 = (await msgs()).length;
  await btn.click().catch(() => {});
  const opened = await waitMsgs('the open of Show Surfer', n0, (ys) => ys.length > 0, 5000);
  check('remote', !!opened && JSON.stringify(opened.map(kind)) === '["socle-surfer-open"]' &&
    opened[0].trace === url && opened[0].traceTest === TRACE_TEST && opened[0].force === true, `Show Surfer posts a forced open ${JSON.stringify(opened)}`);

  // A stale status (seq 1, replaced by seq 2) changes nothing.
  const missingText = () => f.locator('[data-wave-missing]').innerText().catch(() => '');
  const before = await missingText();
  await status({ state: 'ready', loadedTrace: url, shownSeq: seq2 - 1, dropped: 3 });
  await page.waitForTimeout(800);
  const afterStale = await missingText();
  check('remote', afterStale === before, `a stale shownSeq leaves the missing note unchanged (${JSON.stringify(before)} -> ${JSON.stringify(afterStale)})`);

  // The tab shows seq 2, dropping one signal: the Waveform tab counts it, with the tab's state.
  await status({ state: 'ready', loadedTrace: url, shownSeq: seq2, dropped: 1 });
  let missing = '';
  for (let t = 0; t < 5000 && !missing; t += 200) { missing = await f.locator('[data-wave-missing]').innerText().catch(() => ''); if (!missing) await page.waitForTimeout(200); }
  check('remote', missing.trim() === '1 signal not in the trace', `the dropped signal is counted (${JSON.stringify(missing)})`);
  const info = await f.locator('[data-bottom-panel]').innerText().catch(() => '');
  check('remote', /Surfer: ready/.test(info), `the tab's state shown (${JSON.stringify(info.slice(0, 120))})`);

  // A selection: Focus with its trace, no new plan.
  const n1 = (await msgs()).length;
  await f.evaluate(() => window.__socleWave.select('io.out.bits.result'));
  const foc = await waitMsgs('the Focus', n1, (ys) => ys.some((m) => kind(m) === 'Focus'));
  await page.waitForTimeout(400);
  const ys = await after(n1);
  check('remote', !!foc && JSON.stringify(ys.map(kind)) === '["Focus"]' && ys[0].cmd.trace === P('io_out_bits_result'),
    `a port click posts Focus ${JSON.stringify(ys.map((m) => m.cmd))}`);

  // A right-click action in the tab's Surfer (a status carrying its event): Add
  // drivers posts the drivers the design gives (Add); Go to declaration selects
  // in the panel, and that selection posts no Focus.
  const n4 = (await msgs()).length;
  await status({ state: 'ready', loadedTrace: url, shownSeq: null, dropped: null, event: { kind: 'add_drivers', variable: P('io_out_bits_result') } });
  const addMsgs = await waitMsgs('the Add', n4, (ws) => ws.some((m) => kind(m) === 'Add'));
  const addCmd = addMsgs && addMsgs.find((m) => kind(m) === 'Add');
  check('remote', !!addCmd && JSON.stringify(addCmd.cmd.traces) === JSON.stringify([P('x')]), `Add drivers posts Add of x ${JSON.stringify(addCmd)}`);
  check('remote', !!addCmd && addCmd.cmd.after === P('io_out_bits_result'), `below the signal right-clicked ${JSON.stringify(addCmd && addCmd.cmd.after)}`);
  const n5 = (await msgs()).length;
  await status({ state: 'ready', loadedTrace: url, shownSeq: null, dropped: null, event: { kind: 'goto_declaration', variable: P('busy') } });
  await page.waitForTimeout(800);
  const rv = await f.evaluate(() => window.__socleWave.view());
  check('remote', rv.selection === 'busy' && (await after(n5)).length === 0,
    `Go to declaration selects the register busy, posting nothing ${JSON.stringify({ rv, posted: (await after(n5)).map(kind) })}`);

  // The tab closed: nothing is sent; a fresh tab's first status gets Load and Show again.
  await toUi({ type: 'socle-surfer-closed' });
  await page.waitForTimeout(200);
  const n2 = (await msgs()).length;
  await stub('io.in.valid');
  await page.waitForTimeout(800);
  check('remote', (await msgs()).length === n2, `closed: a selection posts nothing ${JSON.stringify((await after(n2)).map(kind))}`);
  await status({ state: 'idle', loadedTrace: '', shownSeq: null, dropped: null });
  const re = await waitMsgs('the resync', n2, (zs) => zs.some((m) => kind(m) === 'Show'));
  await page.waitForTimeout(500);
  const zs = await after(n2);
  const reLoad = zs.find((m) => kind(m) === 'Load');
  const reShow = zs.find((m) => kind(m) === 'Show');
  check('remote', !!re && !!reLoad && reLoad.cmd.url === url && !!reShow && reShow.cmd.seq > seq1 + 1 && JSON.stringify(reShow.cmd.path) === '["GCD"]' &&
    zs.map(kind).indexOf('Load') < zs.map(kind).indexOf('Show'),
    `a fresh tab gets Load then Show again ${JSON.stringify(zs.map((m) => [kind(m), m.cmd && (m.cmd.url || m.cmd.seq)]))}`);
  // It reports the plan shown: no further resend.
  await status({ state: 'ready', loadedTrace: url, shownSeq: reShow ? reShow.cmd.seq : -1, dropped: 0 });
  const n3 = (await msgs()).length;
  await page.waitForTimeout(800);
  check('remote', (await msgs()).length === n3, `settled: nothing more posted ${JSON.stringify((await after(n3)).map(kind))}`);

  // An error for the trace is retried once: two errors, one resync.
  const errStatus = { state: 'error', loadedTrace: url, shownSeq: null, dropped: null };
  const ne = (await msgs()).length;
  await status(errStatus);
  await page.waitForTimeout(1000);
  await status(errStatus);
  await page.waitForTimeout(1500);
  // One resync: the trace and the plan, then the selection's Focus.
  const resent = (ms) => JSON.stringify(ms.map(kind).filter((k) => k === 'Load' || k === 'Show'));
  const es = await after(ne);
  check('remote', resent(es) === '["Load","Show"]', `an error twice resyncs once ${JSON.stringify(es.map(kind))}`);
  // A new tab may retry again.
  await toUi({ type: 'socle-surfer-closed' });
  await page.waitForTimeout(200);
  await status({ state: 'idle', loadedTrace: '', shownSeq: null, dropped: null });
  await page.waitForTimeout(1500);
  const ne2 = (await msgs()).length;
  await status(errStatus);
  await page.waitForTimeout(1500);
  const es2 = await after(ne2);
  check('remote', resent(es2) === '["Load","Show"]', `a new tab's error is retried ${JSON.stringify(es2.map(kind))}`);
  check('remote', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
  wave.ide = null;
}
// 11b. A reloaded panel (after a Simulate) with no Surfer tab: its Hello is answered closed.
{
  const TEST = 'GCD computes gcd(48, 18)';
  wave.ide = { status: 'ok', message: '', focusClass: '', test: TEST, suite: 'gcd.GcdSpec', stamp: 300, waveViewer: 'surfer-panel',
    trace: '/wave/t1/trace', traceTest: `gcd.GcdSpec#${TEST}` };
  const { ctx, page, errors } = await open(PARENT_PANEL);
  await page.frameLocator('#f').locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const f = page.frames().find((x) => x !== page.mainFrame());
  const surferMsgs = () => page.evaluate(() => window.__msgs.map((m) => m.data)
    .filter((d) => d && typeof d.type === 'string' && d.type.startsWith('socle-surfer')));
  let ms = [];
  for (let t = 0; t < 5000 && ms.length === 0; t += 200) { ms = await surferMsgs(); if (!ms.length) await page.waitForTimeout(200); }
  check('remote-reload', ms.length > 0 && ms[0].type === 'socle-surfer-cmd' && ms[0].cmd && ms[0].cmd.$type === 'Hello',
    `the reloaded panel's first message is a Hello ${JSON.stringify(ms[0])}`);
  // The extension's answer with no Surfer tab.
  await page.evaluate(() => document.getElementById('f').contentWindow.postMessage({ type: 'socle-surfer-closed' }, '*'));
  await f.click('[data-toggle-pane="bottom"]');
  await f.click('[data-bottom-tab="wave"]').catch(() => {});
  let info = '';
  for (let t = 0; t < 10000 && !/Surfer: closed/.test(info); t += 200) {
    info = await f.locator('[data-bottom-panel]').innerText().catch(() => '');
    if (!/Surfer: closed/.test(info)) await page.waitForTimeout(200);
  }
  check('remote-reload', /Surfer: closed/.test(info), `the answer shows the tab closed, not idle (${JSON.stringify(info.slice(0, 120))})`);
  const seqB = await f.evaluate(() => window.__socleWave && window.__socleWave.snapshot().seq);
  check('remote-reload', typeof seqB === 'number' && seqB > 2 && Math.abs(seqB - seq1) > 2,
    `a new page's seq starts elsewhere (${seq1} then ${seqB})`);
  check('remote-reload', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
  wave.ide = null;
}

// 12. The browser (no host), `surfer-panel` with a trace: Surfer stays in the Waveform tab.
{
  wave.ide = { status: 'ok', message: '', focusClass: '', test: 'GCD computes gcd(48, 18)', suite: 'gcd.GcdSpec', stamp: 200, waveViewer: 'surfer-panel',
    trace: '/wave/t1/trace', traceTest: 'gcd.GcdSpec#GCD computes gcd(48, 18)' };
  const { ctx, page, errors } = await open('/gcd/');
  await waitLoaded(page);
  const pane = await page.waitForSelector('[data-surfer-pane]', { state: 'attached', timeout: 10000 }).then(() => true).catch(() => false);
  check('browser', pane, 'the Waveform tab mounts Surfer');
  check('browser', (await count(page, '[data-show-surfer]')) === 0, 'no Show Surfer button');
  const chip = await page.waitForSelector('[data-wave-legend]', { timeout: 10000 }).then(() => true).catch(() => false);
  check('browser', chip, 'the colours chip shows beside the Surfer status');
  check('browser', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
  wave.ide = null;
}

// 13. The editor's schematic settings and the editor focus, in the VS Code panel: the
// settings in /ide.json set the view's toggles when they come and when they change;
// the class under the editor's cursor dims the rest and shows a Focus chip, whose ×
// stops it until the cursor is on another class; followCursor off stops it too.
{
  const poll = () => new Promise((r) => setTimeout(r, 2000)); // > one 1500 ms poll
  const set = async (patch) => { focusIde.state = { ...focusIde.state, ...patch, stamp: focusIde.state.stamp + 1 }; await poll(); };
  focusIde.state = { status: 'ok', message: '', focusClass: 'deephier.Cluster', test: '', suite: '', stamp: 1, waveViewer: '',
    trace: '', traceTest: '',
    schematicSettings: { colorizeComponents: true, colorizeWires: true, density: 'compact', labelSize: 'large', hide: ['const'], showLayers: true } };
  const { ctx, page, errors } = await open('/focus/?host=vscode&theme=light');
  await waitLoaded(page);
  await poll();
  const view = () => page.evaluate(() => {
    const pane = document.querySelector('[data-pane="schematic"]');
    const cls = (sel) => !!pane.querySelector(sel);
    return {
      cb: cls('.hl-cb'), cw: cls('.hl-cw'),
      density: document.documentElement.getAttribute('data-density'), labels: document.documentElement.getAttribute('data-labels'),
      chip: document.querySelector('[data-focus-chip]')?.getAttribute('data-focus-chip') || null,
      off: pane.querySelectorAll('.scl-b.off').length, lit: pane.querySelectorAll('[data-box-inst]:not(.off)').length,
    };
  });
  let v = await view();
  check('settings', v.cb && v.cw && v.density === 'compact' && v.labels === 'large', `the editor's settings set the view ${JSON.stringify(v)}`);
  await page.click('[data-settings-toggle]');
  const toggle = (hook) => page.locator(`[${hook}]`).first().getAttribute('data-on');
  const hidden = await page.locator('[data-declutter-kind="const"]').first().getAttribute('data-on');
  const layers = await toggle('data-toggle-layers');
  const follow = await toggle('data-follow-cursor');
  check('settings', hidden === '0' && layers === '1' && follow === '1', `the popover shows them (const hidden ${hidden}, layers ${layers}, follow ${follow})`);
  await page.click('[data-settings-toggle]');
  check('focus', v.chip === 'deephier.Cluster' && v.off > 0 && v.lit >= 2, `the cursor's class dims the rest and shows its chip ${JSON.stringify(v)}`);
  // The chip's ×: the dim goes, until the cursor is on another class.
  await page.click('[data-focus-off]');
  await page.waitForTimeout(300);
  v = await view();
  check('focus', !v.chip && v.off === 0, `× stops the focus ${JSON.stringify(v)}`);
  await poll(); // the same class on the next poll stays off
  v = await view();
  check('focus', !v.chip && v.off === 0, `it stays off while the cursor stays in the class ${JSON.stringify(v)}`);
  await set({ focusClass: '' });
  await set({ focusClass: 'deephier.Cluster' });
  v = await view();
  check('focus', v.chip === 'deephier.Cluster' && v.off > 0, `the cursor back on the class brings it back ${JSON.stringify(v)}`);
  // followCursor off in the editor: no focus at all.
  await set({ schematicSettings: { ...focusIde.state.schematicSettings, followCursor: false, colorizeComponents: false } });
  v = await view();
  check('settings', !v.chip && v.off === 0 && !v.cb && v.cw, `a changed setting applies at once (followCursor off, components not coloured) ${JSON.stringify(v)}`);
  check('settings', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

// 14. A folded design in the side panel channel (the captured DeepSoc, whose
// clusters fold their cores): the hierarchy names each copy's fold group, and a
// socle-navigate selecting the group id selects the folded box, whose details
// give its copy count.
{
  const { ctx, page, errors } = await open(PARENT_DEEPV);
  await page.frameLocator('#f').locator('[data-design-loaded="true"]').waitFor({ timeout: 30000 });
  const states = () => page.evaluate(() => window.__msgs.filter((m) => m.data && m.data.type === 'socle-state'));
  const last = async () => (await states()).at(-1);
  const navigate = (msg) => page.evaluate((m) => document.getElementById('f').contentWindow.postMessage(m, '*'), msg);
  const waitFor = async (what, pred) => {
    for (let i = 0; i < 50; i++) { const s = await last(); if (s && pred(s.data)) return s; await page.waitForTimeout(100); }
    check('fold', false, `timed out waiting for ${what}: last ${JSON.stringify((await last())?.data)?.slice(0, 300)}`);
    return null;
  };
  const s0 = await waitFor('the loaded state', (d) => d.design && d.design.top);
  if (s0) {
    const top = s0.data.design.top;
    const cl = s0.data.design.modules.find((m) => m.children.some((c) => c.name === 'cores_0'));
    const tagged = cl ? cl.children.filter((c) => c.foldGroup).map((c) => `${c.name}:${c.foldGroup}`) : [];
    check('fold', JSON.stringify(tagged) === '["cores_0:cores","cores_1:cores"]', `each copy names its fold group ${JSON.stringify(tagged)}`);
    const parent = s0.data.design.modules.find((m) => cl && m.children.some((c) => c.type === cl.name));
    const inst = parent && parent.children.find((c) => c.type === cl.name).name;
    await navigate({ type: 'socle-navigate', path: [top, inst], select: 'cores' });
    const sel = await waitFor('the folded group selected', (d) => d.selection === 'cores');
    check('fold', !!sel && sel.data.details.copies === 2 && sel.data.details.name === 'cores',
      `the folded group's details give its copies ${JSON.stringify(sel && sel.data.details)?.slice(0, 200)}`);
  }
  check('fold', errors.length === 0, `no page errors ${errors.join(' | ')}`);
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures.length ? `FAILED: ${failures.length} check(s)` : 'embedded: all checks passed');
process.exit(failures.length ? 1 : 0);
