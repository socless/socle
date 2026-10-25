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
    const t = m.text();
    // A "wire dropped, no anchor" warning means the schematic silently lost a
    // connection (stale/empty layout, or a fold that drifted from the routed
    // edge set). Treat it as a failure so the wire-dropping bugs of #258 and
    // its follow-up can never regress unnoticed, even though it is a warning.
    if (m.type() === 'warning' && /wire dropped, no anchor/.test(t)) {
      errors.push('wire dropped: ' + t);
      return;
    }
    if (m.type() !== 'error') return;
    // The generic "Failed to load resource" console line has no URL; real
    // resource 404s are reported precisely by the response handler below.
    if (/Failed to load resource/.test(t)) return;
    errors.push('console.error: ' + t);
  });
  page.on('response', (r) => {
    // designs.json is the optional plugin multi-top index (#117): a 404 just
    // means single-top / non-plugin serve, which is the smoke's case.
    // ide.json is the language-server link (plan 2): a 404 disables it.
    const optional = ['/favicon.ico', '/designs.json', '/ide.json'];
    if (r.status() === 404 && !optional.some((s) => r.url().endsWith(s))) {
      errors.push('http 404: ' + r.url());
    }
  });

  const drain = (where) => { while (errors.length) note(design, where, errors.shift()); };

  // The captured view stands in for the language server: `/ide.json` enables
  // the editor link and `POST /open` records what selecting asks to open.
  const opened = [];
  if (design === 'deepsoc-view') {
    await page.route('**/ide.json', (r) => r.fulfill({ contentType: 'application/json', body: '{"status":"idle"}' }));
    await page.route('**/open', (r) => {
      try { opened.push(JSON.parse(r.request().postData() || '{}')); } catch { opened.push({}); }
      return r.fulfill({ status: 204 });
    });
  }

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

  // Generator copies fold into one box: in the captured DeepSoc, `cluster0`
  // holds group `cores` (cores_0, cores_1). Unfold with `+`, fold back with a
  // copy's `-`, unfold again with a double-click on the folded box.
  if (design === 'deepsoc-view') {
    const where = 'fold cores';
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(150);
    };
    const count = (sel) => page.locator(sel).count();
    // The header's name (and count) is never clipped and its fold toggle sits
    // fully inside the box. innerText cannot see a CSS ellipsis, and
    // scrollWidth rounds to whole pixels, so the text's own width is compared.
    const expectHeader = async (step, box) => {
      const r = await page.evaluate((id) => {
        const b = document.querySelector(`[data-box-inst="${id}"]`);
        const name = b?.querySelector(`[data-box-name="${id}"]`);
        const t = b?.querySelector('[data-fold-toggle]');
        if (!name || !t) return { missing: !name ? 'name' : 'toggle' };
        const range = document.createRange();
        range.selectNodeContents(name);
        const tw = range.getBoundingClientRect().width, nw = name.getBoundingClientRect().width;
        const br = b.getBoundingClientRect(), hr = b.firstElementChild.getBoundingClientRect(), tr = t.getBoundingClientRect();
        return {
          clipped: name.scrollWidth > name.clientWidth || tw > nw + 0.01 ? `text ${tw}px in ${nw}px` : '',
          outside: tr.width > 0 && tr.left >= Math.max(br.left, hr.left) && tr.right <= Math.min(br.right, hr.right) ? ''
            : `toggle [${tr.left}, ${tr.right}] box [${br.left}, ${br.right}] header [${hr.left}, ${hr.right}]`,
        };
      }, box);
      if (r.missing) note(design, where, `${step}: ${box} header has no ${r.missing}`);
      if (r.clipped) note(design, where, `${step}: ${box} name clipped (${r.clipped})`);
      if (r.outside) note(design, where, `${step}: ${box} fold toggle clipped (${r.outside})`);
    };
    const expectFolded = async (step) => {
      const n = await count('[data-fold-group="cores"]');
      const head = n ? await page.locator('[data-fold-group="cores"]').first().innerText() : '';
      if (n !== 1) note(design, where, `${step}: expected one [data-fold-group="cores"] box, got ${n}`);
      else if (!head.includes('cores × 2')) note(design, where, `${step}: folded header lacks "cores × 2": ${JSON.stringify(head)}`);
      else await expectHeader(step, 'cores');
      if (await count('[data-box-inst="cores_0"]')) note(design, where, `${step}: cores_0 shown while folded`);
    };
    const expectUnfolded = async (step) => {
      for (const m of ['cores_0', 'cores_1'])
        if (!(await count(`[data-box-inst="${m}"]`))) note(design, where, `${step}: no [data-box-inst="${m}"]`);
        else await expectHeader(step, m);
      if (await count('[data-fold-group]')) note(design, where, `${step}: a [data-fold-group] box remains`);
    };
    await page.locator('[data-hier-path$="/cluster0"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, e.message));
    await settle();
    await expectFolded('initial');
    await page.locator('[data-fold-toggle="cores"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, `unfold: ${e.message}`));
    await settle();
    await expectUnfolded('after +');
    // Expanding a bundle on an unfolded copy grows that box: the engine must
    // lay it out at its grown height, so the copies never overlap and the gap
    // between them (the engine's node spacing) does not shrink by the growth.
    const rects = () => page.evaluate(() => ['cores_0', 'cores_1'].map((m) => {
      const b = document.querySelector(`[data-box-inst="${m}"]`)?.getBoundingClientRect();
      return b && { l: b.left, t: b.top, r: b.right, b: b.bottom };
    }));
    const gapOf = ([a, b]) => Math.max(b.t - a.b, a.t - b.b, b.l - a.r, a.l - b.r);
    const before = await rects();
    const bt = page.locator('[data-box-inst="cores_0"] [data-bundle-toggle]');
    // The renderer pushes boxes down (data-box-shift) only to make room for a
    // grown box the engine did not size: once laid out, no copy is shifted.
    const expectUnshifted = async (step) => {
      const shifts = await page.$$eval('[data-fold-member="cores"]', (els) =>
        els.map((e) => `${e.getAttribute('data-box-inst')}=${e.getAttribute('data-box-shift')}`));
      if (!shifts.length) note(design, where, `${step}: no [data-fold-member="cores"] box`);
      if (shifts.some((s) => !/=0(\.0)?$/.test(s)))
        note(design, where, `${step}: copies laid out at their old height, renderer shifted ${shifts.join(', ')}`);
    };
    if (!(await bt.count())) {
      // Locked scattered groups (cores_0.io feeds both the logic block and the
      // io port): nothing to expand, but the copy's locked group rows must
      // still be sized by the engine, so no copy is shifted.
      if (!(await count('[data-box-inst="cores_0"] [data-bundle-group]')))
        note(design, where, 'cores_0 has neither a [data-bundle-toggle] nor a locked [data-bundle-group] row');
      await expectUnshifted('locked groups');
    }
    else if (!before[0] || !before[1]) note(design, where, 'before expand: a copy box is missing');
    else {
      // Expand every bundle on cores_0 (nested ones appear as parents open).
      const opened = new Set();
      for (let round = 0; round < 6; round++) {
        const ids = await bt.evaluateAll((els) => els.map((e) => e.getAttribute('data-bundle-toggle')));
        const fresh = ids.filter((id) => !opened.has(id));
        if (!fresh.length) break;
        for (const id of fresh) {
          opened.add(id);
          await page.locator(`[data-box-inst="cores_0"] [data-bundle-toggle="${id}"]`).first().dispatchEvent('click')
            .catch((e) => note(design, where, `expand ${id} on cores_0: ${e.message}`));
          await settle();
        }
      }
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__fold-expand.png` }).catch(() => {});
      const after = await rects();
      if (!after[0] || !after[1]) note(design, where, 'after expand: a copy box is missing');
      else {
        const grew = (after[0].b - after[0].t) - (before[0].b - before[0].t);
        const g0 = gapOf(before), g1 = gapOf(after);
        if (grew <= 0) note(design, where, `after expand: cores_0 did not grow (${grew}px)`);
        if (g1 <= 0) note(design, where, `after expand: cores_0 overlaps cores_1 ${JSON.stringify(after)}`);
        else if (g1 < g0 - 2) note(design, where, `after expand: gap between copies shrank ${g0.toFixed(1)} -> ${g1.toFixed(1)}px`);
      }
      await expectUnshifted('after expand');
    }
    drain(`${where} (expand)`);
    // Select a copy (its header, left of the toggle), then fold its group back:
    // the fold removes the selected box, so the selection (and cores_0's expanded
    // bundles) are cleared; both are checked once the copies are back.
    await page.locator('[data-box-inst="cores_1"] > div').first().click({ position: { x: 20, y: 8 }, timeout: 3000 })
      .catch((e) => note(design, where, `select cores_1: ${e.message}`));
    if (!(await count('[data-inst-inspect="cores_1"]'))) note(design, where, 'cores_1 not selected (no inspector entry)');
    await page.locator('[data-fold-toggle="cores"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, `fold back: ${e.message}`));
    await settle();
    await expectFolded('after -');
    await page.locator('[data-fold-group="cores"]').first().dblclick({ timeout: 3000 }).catch((e) => note(design, where, `dblclick: ${e.message}`));
    await settle();
    await expectUnfolded('after double-click');
    // A stale selection would show cores_1 selected again once it is back.
    if (await count('[data-inst-inspect="cores_1"]')) note(design, where, 'after double-click: cores_1 selected again (selection not cleared on fold)');
    const back = await rects();
    if (before[0] && back[0] && Math.abs((back[0].b - back[0].t) - (before[0].b - before[0].t)) > 1)
      note(design, where, 'after double-click: cores_0 still expanded (expansions not cleared on fold)');
    drain(where);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__fold.png` }).catch(() => {});
  }

  // Logic blocks (slice 2): the captured view groups a module's logic into
  // `block` boxes (`data-block`) instead of op/reg nodes. Every non-top module
  // of the captured DeepSoc has one `logic` block (`pipe` is a Queue).
  // Selecting a block opens its source in the editor (`POST /open`, routed above).
  if (design === 'deepsoc-view') {
    const where = 'logic blocks';
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(150);
    };
    for (const m of ['cluster0', 'pipe']) {
      await page.locator(`[data-hier-path$="/${m}"]`).first().click({ timeout: 3000 }).catch((e) => note(design, where, `drill ${m}: ${e.message}`));
      await settle();
      const r = await page.evaluate(() => ({
        blocks: Array.from(document.querySelectorAll('[data-block]')).map((b) => ({
          id: b.getAttribute('data-block'),
          label: (b.querySelector('[data-block-label]')?.textContent || '').trim(),
        })),
        logicNodes: document.querySelectorAll('[data-logic-node]').length,
      }));
      if (!r.blocks.length) note(design, where, `${m}: no [data-block] box`);
      for (const b of r.blocks) if (!b.label) note(design, where, `${m}: block ${b.id} has no header label`);
      if (r.logicNodes) note(design, where, `${m}: ${r.logicNodes} op/reg [data-logic-node] box(es) next to the blocks`);
      if (m === 'cluster0') {
        const id = 'blk$Cluster$logic';
        opened.length = 0;
        await page.locator(`[data-block="${id}"] [data-block-label]`).first().click({ timeout: 3000 })
          .catch((e) => note(design, where, `select ${id}: ${e.message}`));
        await page.waitForTimeout(250);
        const insp = page.locator(`[data-logic-inspect="${id}"]`);
        if (!(await insp.count())) note(design, where, `${id} not selected (no inspector entry)`);
        else {
          const text = await insp.first().innerText();
          if (!text.includes('logic block') || text.includes('constant'))
            note(design, where, `${id} inspector does not read "logic block": ${JSON.stringify(text)}`);
        }
        if (!opened.some((o) => o.file === 'example/src/deephier/DeepSoc.scala' && o.line === 119))
          note(design, where, `selecting ${id} did not open its source: ${JSON.stringify(opened)}`);
        // A live reload that drops the selected block (here: renames it) clears
        // the selection instead of leaving the Inspector blank on a stale id.
        const fresh = (await (await page.request.get(`${BASE}/${design}/design.json`)).text())
          .split('blk$Cluster$logic').join('blk$Cluster$logic2');
        await page.route('**/design.json', (rt) => rt.fulfill({
          contentType: 'application/json', body: fresh,
          headers: { 'Last-Modified': 'Wed, 01 Jan 2031 00:00:00 GMT' },
        }));
        const reloaded = await page.waitForSelector('[data-block="blk$Cluster$logic2"]', { timeout: 10000 })
          .then(() => true).catch(() => false);
        if (!reloaded) note(design, where, 'live reload: the renamed block never showed');
        else {
          await page.waitForTimeout(150);
          if (await page.locator('[data-logic-inspect]').count()) note(design, where, 'live reload: a stale [data-logic-inspect] remains');
          if (await page.locator('.scl-insp-clear').count()) note(design, where, `live reload: the selection of the dropped ${id} survived`);
        }
        drain(`${where} (live reload)`);
        // An FSM block opens its source too. No captured fixture holds an FSM,
        // so the same block is served again as an `fsm` box.
        const fsmId = 'blk$Cluster$fsm$state';
        const asFsm = JSON.parse(fresh.split('blk$Cluster$logic2').join(fsmId));
        for (const mod of asFsm.modules) for (const n of mod.logic || []) if (n.id === fsmId) n.kind = 'fsm';
        await page.unroute('**/design.json');
        await page.route('**/design.json', (rt) => rt.fulfill({
          contentType: 'application/json', body: JSON.stringify(asFsm),
          headers: { 'Last-Modified': 'Thu, 02 Jan 2031 00:00:00 GMT' },
        }));
        const fsmShown = await page.waitForSelector(`[data-fsm="${fsmId}"]`, { timeout: 10000 })
          .then(() => true).catch(() => false);
        if (!fsmShown) note(design, where, 'live reload: the fsm block never showed');
        else {
          await page.waitForTimeout(150);
          opened.length = 0;
          await page.locator(`[data-fsm="${fsmId}"]`).first().click({ timeout: 3000, position: { x: 8, y: 8 } })
            .catch((e) => note(design, where, `select ${fsmId}: ${e.message}`));
          await page.waitForTimeout(250);
          if (!opened.some((o) => o.file === 'example/src/deephier/DeepSoc.scala' && o.line === 119))
            note(design, where, `selecting ${fsmId} did not open its source: ${JSON.stringify(opened)}`);
        }
        drain(`${where} (fsm block)`);
      }
      // A design with debug-layer blocks hides them until "show layers" is on.
      const toggle = page.locator('[data-toggle-layers]');
      if (await toggle.count()) {
        const before = await page.locator('[data-block]').count();
        await toggle.click().catch((e) => note(design, where, `layers toggle: ${e.message}`));
        await settle();
        const shown = await page.locator('[data-block]').count();
        await toggle.click().catch(() => {});
        await settle();
        const hidden = await page.locator('[data-block]').count();
        if (shown <= before || hidden !== before) note(design, where, `${m}: layer blocks ${before} -> ${shown} -> ${hidden}`);
      }
      drain(`${where} (${m})`);
    }
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__blocks.png` }).catch(() => {});
  }

  // Back to the top module (richest view: instances, wires, interface pins).
  if (paths.length) {
    await page.locator(`[data-hier-path="${paths[0].path}"]`).first().click().catch(() => {});
    await page.waitForTimeout(150);
  }

  // Expand every bundle port in the top view.
  const toggles = await page.locator('[data-bundle-toggle]').all();
  console.log(`[${design}] ${toggles.length} bundle toggles in top view`);
  // #237: a scattered bundle (fields wired to different peers) must render as
  // locked in/out groups on the owning box, NOT as a replicated expandable
  // bundle on every peer. The `scatter` design's producer `p.io` is the case:
  // it should show an `in` group + an `out` group and zero bundle-toggles.
  if (design === 'scatter') {
    const groups = await page.$$eval('[data-bundle-group]', (els) =>
      els.map((e) => e.getAttribute('data-bundle-group') + ':' + e.getAttribute('data-bundle-group-dir')));
    const hasIn = groups.includes('p.io:in');
    const hasOut = groups.includes('p.io:out');
    if (!hasIn || !hasOut) note(design, 'scattered-groups (#237)', `expected p.io in+out bundle groups, got ${JSON.stringify(groups)}`);
    else console.log(`[${design}] bundle groups: ${groups.join(', ')}`);
    if (toggles.length > 0) note(design, 'scattered-replica (#237)', `expected 0 bundle-toggles on a scattered design, got ${toggles.length}`);
  }
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

  // #206: panel toggles hide/show the side+bottom panels (focus on schematic),
  // and the bottom panel carries a resize handle.
  {
    const hierToggle = page.locator('[data-toggle-pane="hierarchy"]');
    const hierPane = () => page.locator('[data-pane="hierarchy"]').count();
    if (await hierToggle.count() && await hierPane()) {
      await hierToggle.click().catch((e) => note(design, 'panel toggle', e.message));
      await page.waitForTimeout(120);
      if (await hierPane() !== 0) note(design, 'panel toggle (#206)', 'hierarchy pane still present after hiding');
      await hierToggle.click().catch(() => {}); // restore for later steps
      await page.waitForTimeout(120);
      if (await hierPane() === 0) note(design, 'panel toggle (#206)', 'hierarchy pane did not return after toggling back');
    } else {
      note(design, 'panel toggle (#206)', 'missing [data-toggle-pane=hierarchy] or [data-pane=hierarchy]');
    }
    if (await page.locator('[data-bottom-resize]').count() === 0)
      note(design, 'panel resize (#206)', 'no [data-bottom-resize] handle on the bottom panel');
    drain('panel toggle');
  }

  // #212: verification-layer content is hidden by default; the "layers" toggle
  // reveals it. Only the `layers` fixture has layerblock content.
  if (design === 'layers') {
    const toggle = page.locator('[data-toggle-layers]');
    if (await toggle.count() === 0) {
      note(design, 'layers toggle (#212)', 'no [data-toggle-layers] control on a design with layer content');
    } else {
      const before = await page.locator('[data-box-inst]').count();
      await toggle.click().catch((e) => note(design, 'layers toggle', e.message));
      await page.waitForTimeout(250);
      const after = await page.locator('[data-box-inst]').count();
      if (after <= before) note(design, 'layers toggle (#212)', `showing layers did not add boxes (${before} -> ${after})`);
      await toggle.click().catch(() => {}); // restore
    }
    drain('layers toggle');
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
