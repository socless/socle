// Embedded-mode check (VS Code panel): the UI loaded with ?host=vscode drops the
// chrome the editor already provides and follows the editor's theme; without the
// parameter every piece is still there.
//
// Usage (from the repo root, after `./mill socless-ui.dist` and an elaborated design):
//   node tools/ui-smoke/embedded.mjs --design out/example/soclessRunGcd.dest/design.json
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
for (const f of [UI, DESIGN]) if (!existsSync(f)) { console.error(`missing ${f}`); process.exit(2); }

const files = {
  '/': ['text/html', readFileSync(UI)],
  '/design.json': ['application/json', readFileSync(DESIGN)],
  '/designs.json': ['application/json', JSON.stringify({ designs: [
    { id: 'a', label: 'A', path: 'design.json' }, { id: 'b', label: 'B', path: 'design.json' }] })],
};
const server = http.createServer((req, res) => {
  const f = files[req.url.split('?')[0]];
  if (!f) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': f[0] }); res.end(f[1]);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;
// A stand-in for the VS Code webview page, cross-origin to the UI like the real one
// (localhost vs 127.0.0.1): hosts the UI in an iframe.
files['/parent.html'] = ['text/html', `<!DOCTYPE html><body style="margin:0"><iframe id="f" src="${BASE}/?host=vscode&theme=light" style="width:1400px;height:800px;border:0"></iframe></body>`];
const PARENT = `http://localhost:${server.address().port}/parent.html`;

const EXEC = process.env.CHROMIUM
  || ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable'].find((p) => existsSync(p));
const browser = await chromium.launch(EXEC ? { executablePath: EXEC, args: ['--no-sandbox'] } : { args: ['--no-sandbox'] });

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
  check('embedded', !/waveform/.test(cmds), 'bottom panel command says ports only');
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

await browser.close();
server.close();
console.log(failures.length ? `FAILED: ${failures.length} check(s)` : 'embedded: all checks passed');
process.exit(failures.length ? 1 : 0);
