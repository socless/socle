// SoCLess UI interactivity smoke test.
//
// Drives the single-file UI in a headless browser and exercises the
// interactive surface that unit tests cannot reach: each design on its own page
// (out/<id>/SoCLess.html), drilling into every module in the hierarchy
// (including leaves), and expanding every bundle port. It fails on any uncaught
// page exception or console error, so regressions like "drilling into a leaf
// module blanks the page" are caught automatically.
//
// Usage:
//   node smoke.mjs --base http://localhost:8765 [--design <id>] [--shots <dir>]
//
// Requires a running server (see README) and a system Chromium. Override the
// browser with CHROMIUM=/path/to/chromium.

import { chromium } from 'playwright-core';
import { existsSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';

const args = Object.fromEntries(
  process.argv.slice(2).join(' ').split('--').filter(Boolean).map((s) => {
    const [k, ...v] = s.trim().split(/\s+/);
    return [k, v.join(' ') || true];
  }),
);

const BASE = args.base || 'http://localhost:8765';
const ONLY = args.design || null;            // restrict to one design id
const SHOTS = args.shots || null;            // screenshot dir (optional)
// Browser: an explicit CHROMIUM path or a system chromium if present; otherwise
// fall back to Playwright's managed chromium (e.g. `npx playwright install
// chromium` in CI), which chromium.launch() uses when executablePath is unset.
const EXEC = process.env.CHROMIUM
  || ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable']
       .find((p) => existsSync(p));
if (SHOTS) await mkdir(SHOTS, { recursive: true });

const failures = [];
const note = (design, where, msg) => {
  failures.push({ design, where, msg });
  console.log(`  FAIL [${design}] ${where}: ${msg}`);
};

// Union of every data-symbol value seen across all designs/views (#203).
const seenSymbolKinds = new Set();

const launchOpts = { args: ['--no-sandbox'] };
if (EXEC) launchOpts.executablePath = EXEC;
const browser = await chromium.launch(launchOpts);

// Discover designs from the manifest.
const ctx0 = await browser.newContext();
const p0 = await ctx0.newPage();
await p0.goto(`${BASE}/manifest.json`);
const manifest = await p0.evaluate(() => JSON.parse(document.body.innerText));
await ctx0.close();
let designs = (manifest.designs || []).map((d) => d.id);
if (ONLY) designs = designs.filter((d) => d === ONLY);
console.log(`Designs: ${designs.join(', ')}`);

for (const design of designs) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const t = m.text();
    // The generic "Failed to load resource" console line has no URL; real
    // resource 404s are reported precisely by the response handler below.
    if (/Failed to load resource/.test(t)) return;
    errors.push('console.error: ' + t);
  });
  page.on('response', (r) => {
    // designs.json is the optional plugin multi-top index (#117): a 404 just
    // means single-top / non-plugin serve, which is the smoke's case.
    const optional = ['/favicon.ico', '/designs.json'];
    if (r.status() === 404 && !optional.some((s) => r.url().endsWith(s))) {
      errors.push('http 404: ' + r.url());
    }
  });

  const drain = (where) => { while (errors.length) note(design, where, errors.shift()); };

  await page.goto(`${BASE}/${design}/SoCLess.html`, { waitUntil: 'networkidle' });
  // A design that renders zero hierarchy nodes is a failure, not a skip:
  // every fixture has at least a top module (issue #103 - the blank-canvas
  // regression class must not pass silently).
  const loaded = await page.waitForSelector('[data-hier-path]', { timeout: 15000 })
    .then(() => true).catch(() => false);
  drain('initial load');
  if (!loaded) {
    note(design, 'initial load', 'no hierarchy nodes rendered within 15s');
    await ctx.close();
    continue;
  }

  // SOURCE indicator (#194): a design fetched from design.json is the "live"
  // source. The top bar must expose it via data-source so the live poller and
  // LIVE badge know to engage; its absence is the migration regression.
  const src = await page.getAttribute('[data-source]', 'data-source').catch(() => null);
  if (src !== 'live') {
    note(design, 'source indicator',
      src === null ? 'no [data-source] element in the top bar' : `expected data-source="live", got "${src}"`);
  }
  drain('source indicator');

  // Theme toggle (issue #2): the top-bar button flips data-theme on <html> and
  // persists the choice. Its absence is the migration regression we restored.
  {
    const before = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || 'light');
    const btn = page.locator('[data-theme-toggle]');
    if (await btn.count() === 0) {
      note(design, 'theme toggle', 'no [data-theme-toggle] button in the top bar');
    } else {
      await btn.click().catch((e) => note(design, 'theme toggle', e.message));
      const after = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || 'light');
      if (after === before) note(design, 'theme toggle', `clicking did not change the theme (stayed "${before}")`);
      const saved = await page.evaluate(() => { try { return localStorage.getItem('socless-theme'); } catch { return null; } });
      if (saved !== after) note(design, 'theme toggle', `theme not persisted (data-theme="${after}", localStorage="${saved}")`);
      await btn.click().catch(() => {}); // restore so later steps render in the original theme
    }
  }
  drain('theme toggle');

  // Open-in-editor presets (#123): right-clicking the Source-tab chip cycles
  // VS Code -> IntelliJ -> off and persists the choice. Only present when the
  // module embedded a source file (the Source tab is active on load).
  {
    const btn = page.locator('[data-open-editor]');
    if (await btn.count() > 0) {
      const before = await btn.first().getAttribute('data-editor');
      await btn.first().click({ button: 'right' }).catch((e) => note(design, 'editor presets', e.message));
      const after = await btn.first().getAttribute('data-editor');
      if (after === before) note(design, 'editor presets', `right-click did not cycle the editor (stayed "${before}")`);
      const saved = await page.evaluate(() => { try { return localStorage.getItem('socless-editor'); } catch { return null; } });
      if (saved !== after) note(design, 'editor presets', `editor choice not persisted (data-editor="${after}", localStorage="${saved}")`);
    }
  }
  drain('editor presets');

  // Fully expand the hierarchy tree (repeat until row count is stable).
  for (let i = 0; i < 12; i++) {
    const before = await page.locator('[data-hier-path]').count();
    const toggles = await page.locator('[data-hier-toggle]').all();
    for (const t of toggles) { await t.click({ timeout: 1500 }).catch(() => {}); }
    const after = await page.locator('[data-hier-path]').count();
    if (after === before) break;
  }

  // Drill into every node in the hierarchy and check the app survives.
  const paths = await page.locator('[data-hier-path]').evaluateAll(
    (els) => els.map((e) => ({ path: e.getAttribute('data-hier-path'), leaf: e.getAttribute('data-hier-leaf') === '1' })),
  );
  console.log(`[${design}] ${paths.length} hierarchy nodes`);
  for (const { path, leaf } of paths) {
    await page.locator(`[data-hier-path="${path}"]`).first().click({ timeout: 3000 }).catch((e) => note(design, `click ${path}`, e.message));
    await page.waitForTimeout(120);
    // App must still be mounted: the hierarchy panel and a schematic host stay present.
    const alive = await page.locator('[data-hier-path]').count();
    if (alive === 0) note(design, `drill ${path}${leaf ? ' (leaf)' : ''}`, 'app unmounted / blank page');
    // The canvas must show SOMETHING: instance boxes, boundary stubs, or the
    // explicit empty-module notice. A silent blank canvas is a bug (this is
    // exactly how the layoutOf regression in #87 shipped). A missing-layout
    // notice is also a failure here: fixtures are exported with layouts.
    const content = await page.evaluate(() => ({
      boxes: document.querySelectorAll('[data-box-inst]').length,
      stubs: document.querySelectorAll('[data-boundary-stub]').length,
      empty: !!document.querySelector('[data-empty-module]'),
      layoutMissing: !!document.querySelector('[data-layout-missing]'),
      logicNodes: document.querySelectorAll('[data-logic-node]').length,
      symbols: Array.from(document.querySelectorAll('[data-logic-node]'))
        .filter(e => (e.getAttribute('data-symbol') || '').length > 0).length,
      symbolKinds: Array.from(new Set(Array.from(document.querySelectorAll('[data-symbol]'))
        .map(e => e.getAttribute('data-symbol')).filter(Boolean))),
    }));
    const where = `drill ${path}${leaf ? ' (leaf)' : ''}`;
    if (content.layoutMissing) note(design, where, 'missing-layout notice shown for a fixture exported with layouts');
    else if (content.boxes + content.stubs === 0 && !content.empty) note(design, where, 'blank canvas: no boxes, stubs, or empty-module notice');
    // #203 (Task 4): every logic node must render a non-empty data-symbol.
    if (content.logicNodes !== content.symbols)
      note(design, 'symbols', `${content.logicNodes - content.symbols} logic node(s) missing data-symbol`);
    for (const k of content.symbolKinds) seenSymbolKinds.add(k);
    drain(where);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__${path.replace(/[^\w.-]/g, '_')}.png` }).catch(() => {});
  }

  // Back to the top module (richest view: instances, wires, interface pins).
  if (paths.length) {
    await page.locator(`[data-hier-path="${paths[0].path}"]`).first().click().catch(() => {});
    await page.waitForTimeout(150);
  }

  // Expand every bundle port in the top view.
  const toggles = await page.locator('[data-bundle-toggle]').all();
  console.log(`[${design}] ${toggles.length} bundle toggles in top view`);
  // #207: expanding a bundle must NOT reset the view (pan/zoom). Record the
  // content layer's transform before any expand and compare after: the box
  // grows (width/height change) but the transform (translate+scale) must hold.
  const contentXform = () =>
    page.locator('[data-content]').first().evaluate((el) => getComputedStyle(el).transform).catch(() => null);
  const xformBefore = toggles.length ? await contentXform() : null;
  for (const t of toggles) {
    // dispatchEvent fires the exact toggle's handler regardless of overlapping
    // full-width label divs (which intercept a normal click).
    await t.dispatchEvent('click').catch((e) => note(design, 'bundle expand', e.message));
    await page.waitForTimeout(80);
    drain('bundle expand');
  }
  if (xformBefore) {
    await page.waitForTimeout(400); // let the re-layout settle
    const xformAfter = await contentXform();
    if (xformAfter && xformAfter !== xformBefore)
      note(design, 'bundle expand reset view (#207)', `content transform changed ${xformBefore} -> ${xformAfter}`);
  }
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__bundles-expanded.png` }).catch(() => {});

  // ── Interaction coverage (still on the top module: wires + inspector) ──────
  // Command palette: open (Ctrl-K), search, navigate to a result, close.
  await page.locator('body').click({ position: { x: 5, y: 5 } }).catch(() => {});
  await page.keyboard.press('Control+k');
  const palette = page.locator('[data-command-palette]');
  if (await palette.count()) {
    await page.locator('[data-cmd-input]').fill('io').catch((e) => note(design, 'palette type', e.message));
    await page.waitForTimeout(120);
    const nResults = await page.locator('[data-cmd-result]').count();
    if (nResults === 0) note(design, 'command palette', 'no results for a generic query');
    await page.locator('[data-cmd-result]').first().click().catch((e) => note(design, 'palette select', e.message));
    await page.waitForTimeout(150);
    drain('command palette navigate');
    if (await page.locator('[data-command-palette]').count()) note(design, 'command palette', 'did not close after selecting');
  } else {
    note(design, 'command palette', 'Ctrl-K did not open the palette');
  }

  // Keyboard help overlay: `?` opens, Escape closes.
  await page.locator('body').click({ position: { x: 5, y: 5 } }).catch(() => {});
  await page.keyboard.press('Shift+Slash');
  await page.waitForTimeout(100);
  if (await page.locator('[data-help-overlay]').count() === 0) {
    note(design, 'keyboard help', '? did not open the help overlay');
  } else {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);
    if (await page.locator('[data-help-overlay]').count()) note(design, 'keyboard help', 'Escape did not close the overlay');
  }
  drain('keyboard help');

  // Wire hover + select, then expand the inspector bundle tree it populates.
  // Hovering/selecting a wire re-renders the schematic, detaching the path
  // mid-action; .hover()/.click() then flake on "not visible / detached".
  // dispatchEvent re-resolves the locator and fires without an actionability
  // wait (the pattern the bundle toggles above already use).
  const wire = page.locator('[data-wire]').first();
  if (await page.locator('[data-wire]').count()) {
    await wire.dispatchEvent('mouseover').catch((e) => note(design, 'wire hover', e.message));
    await page.waitForTimeout(80);
    drain('wire hover');
    await wire.dispatchEvent('click').catch((e) => note(design, 'wire select', e.message));
    await page.waitForTimeout(150);
    drain('wire select');
    // Inspector tree: expand every aggregate node the selection populated.
    const inspToggles = await page.locator('[data-inspector-toggle]').all();
    for (const t of inspToggles) {
      await t.click({ timeout: 1500 }).catch(() => {});
      await page.waitForTimeout(60);
      drain('inspector expand');
    }
  }

  // #197: the multi-top page must expose a design picker that switches the
  // active design in place. Only the `multitop` fixture ships a designs.json.
  if (design === 'multitop') {
    const picker = page.locator('[data-design-picker]');
    if (await picker.count() === 0) {
      note(design, 'design picker (#197)', 'no [data-design-picker] in the top bar on a multi-top page');
    } else {
      const opts = await page.locator('[data-design-option]').evaluateAll((els) => els.map((e) => e.getAttribute('value')));
      const topBefore = await page.getAttribute('[data-top]', 'data-top').catch(() => null);
      const other = opts.find((v) => v !== 'design.json') || opts[opts.length - 1];
      await picker.selectOption(other).catch((e) => note(design, 'design picker', e.message));
      await page.waitForTimeout(500);
      drain('design switch');
      const topAfter = await page.getAttribute('[data-top]', 'data-top').catch(() => null);
      if (topBefore && topAfter && topBefore === topAfter)
        note(design, 'design picker did not switch (#197)', `selecting another top left data-top "${topAfter}"`);
    }
    drain('design picker');
  }

  await ctx.close();
}

await browser.close();

// #203 (Task 5): the expected symbol families must render at least once across
// the whole run. Registers (ff) appear in regheavy/datapath; combinational ops
// (block) in rvpipeline/gcd/datapath. No bundled example exercises a mux op, so
// 'mux' is intentionally not required here. Lenient (presence, not counts) so
// design changes do not break it.
console.log(`Symbol families seen: ${Array.from(seenSymbolKinds).sort().join(', ') || '(none)'}`);
const need = ['block', 'ff'];
for (const k of need)
  if (!seenSymbolKinds.has(k)) note('(all)', 'symbol-family', `no node rendered data-symbol="${k}" in any design`);

console.log('\n=== SMOKE SUMMARY ===');
if (failures.length === 0) {
  console.log('OK - no page errors, crashes, or console errors.');
  process.exit(0);
} else {
  console.log(`${failures.length} problem(s):`);
  for (const f of failures) console.log(`  [${f.design}] ${f.where}: ${f.msg}`);
  process.exit(1);
}
