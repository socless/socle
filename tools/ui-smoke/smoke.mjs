// Socle UI interactivity smoke test.
//
// Drives the single-file UI in a headless browser and exercises the
// interactive surface that unit tests cannot reach: each design on its own page
// (out/<id>/socle.html), drilling into every module in the hierarchy
// (including leaves), and expanding every bundle port. It fails on any uncaught
// page exception or console error, so regressions like "drilling into a leaf
// module blanks the page" are caught automatically.
//
// Usage:
//   node smoke.mjs --base http://localhost:8765 [--design <id>] [--shots <dir>] [--concurrency <n>]
//
// --concurrency runs n designs at once, each in its own browser context (CI
// uses 4); failures are still attributed per design and summarized in manifest
// order.
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
// Designs smoked at once, each in its own browser context (default 1).
const CONCURRENCY = Math.max(1, parseInt(args.concurrency, 10) || 1);
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
// Designs whose zoomed-in top view showed the minimap (its checks ran there).
const minimapDesigns = new Set();
// Designs whose top view had a visible top-level pin to rest on (highlight check).
const pinRestDesigns = new Set();
// Designs whose top view had a clickable bundle toggle / a drillable box for the
// gesture checks (each must run on at least one design).
const gestureToggleDesigns = new Set();
const gestureDrillDesigns = new Set();
const gestureBlockDesigns = new Set();
const gestureNavDesigns = new Set();
// Designs whose hovered box lit connected wires, whose box card gave instance
// counts, and that had a drillable block for the cursor check (hover feedback).
const hoverWireDesigns = new Set();
const hoverCountDesigns = new Set();
const cursorZoomDesigns = new Set();
const cursorPointerDesigns = new Set();
// Designs whose hint bar followed the target under the pointer (help).
const hintDesigns = new Set();
// Designs that walked the view history, flew to a block (in the module, from
// another one), stopped a flight with a drag, and framed a selected wire.
const historyDesigns = new Set();
// Designs that walked the history with the Back / Forward buttons, and that
// opened a leaf module by a double-click with the hierarchy following it.
const navButtonDesigns = new Set();
const leafDrillDesigns = new Set();
// The designs with a drillable child of the top and a leaf with content:
// each must run both checks (a design that skips one fails on its own).
// The designs with a state diagram: each runs the diagram's Back / Forward check.
const fsmNavDesigns = new Set();
const FSM_NAV_DESIGNS = ['fsm-view'];
const NAV_DESIGNS = ['axisystem', 'rvpipeline', 'deepsoc', 'interconnect', 'datapath', 'scatter', 'deepsoc-view'];
const flyDesigns = new Set();
const flyCrossDesigns = new Set();
const flightCancelDesigns = new Set();
const zoomWireDesigns = new Set();
// Designs that located a hierarchy row in its parent, picked an instance with
// no editor open, and dropped a pending fly by a drag.
const locateDesigns = new Set();
const pickNoOpenDesigns = new Set();
const stalePendingDesigns = new Set();
// Designs that followed a net across levels, and whose resolution self-check
// round-tripped at least one wire.
const followDesigns = new Set();
const followCheckDesigns = new Set();
// Designs whose live reload kept the history and the followed net, and whose
// fresh load cleared both (reload keeps navigation).
const reloadNavDesigns = new Set();
// Designs whose Esc closing the "?" card kept the followed net, and whose
// interface pin dot toggled its bundle.
const escCardDesigns = new Set();
const pinDotDesigns = new Set();
const fieldDotDesigns = new Set();

const launchOpts = { args: ['--no-sandbox'] };
if (EXEC) launchOpts.executablePath = EXEC;
const browser = await chromium.launch(launchOpts);

// Discover designs from the manifest.
const ctx0 = await browser.newContext();
const p0 = await ctx0.newPage();
await p0.goto(`${BASE}/manifest.json`);
const manifest = await p0.evaluate(() => JSON.parse(document.body.innerText));
await ctx0.close();
// A `perf` design (hundreds of boxes) gets the performance guard only
// (smokePerf), not the per-module and per-bundle walk of the others.
let designs = (manifest.designs || []).filter((d) => !d.perf).map((d) => d.id);
let perfDesigns = (manifest.designs || []).filter((d) => d.perf).map((d) => d.id);
if (ONLY) { designs = designs.filter((d) => d === ONLY); perfDesigns = perfDesigns.filter((d) => d === ONLY); }
console.log(`Designs: ${designs.join(', ')}${perfDesigns.length ? ` (performance guard: ${perfDesigns.join(', ')})` : ''}`);

// Each design runs in its own browser context (own page, routes and storage),
// closed whatever happens.
async function smokeDesign(design) {
  if (design === designs[0]) await smokeHelpFirstRun(design);
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  // The first-time tips are seen: they would sit over the schematic on every
  // first load (smokeHelpFirstRun checks them in a context of its own).
  await ctx.addInitScript(() => { try { localStorage.setItem('socle-tips', 'done'); } catch {} });
  try {
    await smokeDesignIn(design, ctx);
    await smokeToggles(design, ctx);
  } finally {
    await ctx.close().catch(() => {});
  }
}

// Bundle toggles (#331): in the first modules of the design, on every
// instance box and edge stub, no port path shows twice (on either side), and
// each bundle toggle opens and then closes back to the rows it had, also after
// one of its children was opened; the opened row must keep a toggle to close
// it with. Each problem is a key `design|kind|module|box|path`. A known one
// (KNOWN_TOGGLE) is reported, not failed; a new one fails, and so does a known
// one that no longer happens (remove it from the list), so the list only
// shrinks. The port model (#332) emptied it.
const KNOWN_TOGGLE = new Set([]);
const toggleSeen = new Set();
const toggleDesigns = new Set();
async function smokeToggles(design, ctx) {
  const where = 'bundle toggles';
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const problem = (key, msg) => {
    toggleSeen.add(key);
    if (KNOWN_TOGGLE.has(key)) console.log(`  known [${design}] ${where}: ${msg} (#332)`);
    else note(design, where, `${msg} [${key}]`);
  };
  try {
    await page.goto(`${BASE}/${design}/socle.html`, { waitUntil: 'networkidle' });
    await page.waitForSelector('[data-layout-ready="1"]', { timeout: 30000 });
    const ready = async () => {
      await page.waitForTimeout(150);
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(100);
    };
    // A box's (or an edge stub's) rows, in order: `side:path`.
    const rows = (box) => page.evaluate((id) => {
      const b = document.querySelector(`[data-box-inst="${CSS.escape(id)}"],[data-boundary-stub="${CSS.escape(id)}"]`);
      return b ? [...b.querySelectorAll('[data-row]')].map((e) => `${e.getAttribute('data-row-side')}:${e.getAttribute('data-row')}`) : null;
    }, box);
    const toggles = (box) => page.evaluate((id) => {
      const b = document.querySelector(`[data-box-inst="${CSS.escape(id)}"],[data-boundary-stub="${CSS.escape(id)}"]`);
      return b ? [...new Set([...b.querySelectorAll('[data-bundle-toggle]')].map((e) => e.getAttribute('data-bundle-toggle')))] : [];
    }, box);
    const click = async (box, path) => {
      const ok = await page.evaluate(([id, p]) => {
        const b = document.querySelector(`[data-box-inst="${CSS.escape(id)}"],[data-boundary-stub="${CSS.escape(id)}"]`);
        const t = b && b.querySelector(`[data-bundle-toggle="${CSS.escape(p)}"]`);
        if (!t) return false;
        t.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        return true;
      }, [box, path]);
      if (ok) await ready();
      return ok;
    };
    const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
    const modules = (await page.evaluate(() => [...document.querySelectorAll('[data-hier-path]')].map((e) => e.getAttribute('data-hier-path')))).slice(0, 6);
    let checked = 0;
    for (const mod of modules) {
      await page.locator(`[data-hier-path="${mod}"]`).first().dispatchEvent('click');
      await ready();
      const boxes = await page.evaluate(() => [...document.querySelectorAll('[data-box-inst],[data-boundary-stub]')]
        .map((e) => e.getAttribute('data-box-inst') || e.getAttribute('data-boundary-stub')));
      for (const box of boxes) {
        const base = await rows(box);
        if (!base) continue;
        const paths = base.map((r) => r.slice(r.indexOf(':') + 1));
        for (const d of new Set(paths.filter((x, i) => paths.indexOf(x) !== i)))
          problem(`${design}|twice|${mod}|${box}|${d}`, `${mod}: ${d} shows twice on ${box}`);
        for (const t of await toggles(box)) {
          checked++;
          await click(box, t);
          const opened = await rows(box);
          if (same(opened, base)) problem(`${design}|inert|${mod}|${box}|${t}`, `${mod}: opening ${t} on ${box} added no row`);
          const kid = (await toggles(box)).find((k) => k !== t && (k.startsWith(t + '.') || k.startsWith(t + '[')));
          if (kid) await click(box, kid);
          if (!(await click(box, t))) {
            problem(`${design}|noclose|${mod}|${box}|${t}`, `${mod}: once open, ${t} on ${box} has no toggle to close it`);
            // put the box back for the next toggle: a reload of the module
            await page.reload({ waitUntil: 'networkidle' });
            await ready();
            await page.locator(`[data-hier-path="${mod}"]`).first().dispatchEvent('click');
            await ready();
            continue;
          }
          const closed = await rows(box);
          if (!same(closed, base))
            problem(`${design}|stuck|${mod}|${box}|${t}`, `${mod}: ${t} on ${box} opened${kid ? ` (and ${kid})` : ''} then closed left ${closed.length} rows, not ${base.length}`);
          if (kid && !same(closed, base)) {
            // the child kept open under the closed parent: close it too
            await click(box, kid);
          }
          if (!same(await rows(box), base)) {
            await page.reload({ waitUntil: 'networkidle' });
            await ready();
            await page.locator(`[data-hier-path="${mod}"]`).first().dispatchEvent('click');
            await ready();
          }
        }
      }
    }
    toggleDesigns.add(design);
    console.log(`[${design}] ${where}: ${checked} toggles on ${modules.length} modules`);
  } catch (e) {
    note(design, where, (e && e.message) || String(e));
  } finally {
    for (const e of errors) note(design, where, e);
    await page.close().catch(() => {});
  }
}

// Help on a first visit, in a fresh context (empty storage): the first-time
// tips show, Next moves on, Skip closes them for good; closing the hint bar
// lasts across a reload, and the settings panel brings it back.
async function smokeHelpFirstRun(design) {
  const where = 'help first run';
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  try {
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    const load = async () => {
      await page.goto(`${BASE}/${design}/socle.html`, { waitUntil: 'networkidle' });
      await page.waitForSelector('[data-hier-path]', { timeout: 15000 });
      await page.waitForTimeout(300);
    };
    const dashes = /[\u2013\u2014]/;
    const tips = page.locator('[data-first-tips]');
    const bar = page.locator('[data-hint-bar]');
    await load();
    if (await tips.count() !== 1) note(design, where, 'no first-time tips on the first visit');
    else {
      const t1 = await page.locator('[data-first-tips-text]').textContent();
      await page.locator('[data-first-tips-next]').click();
      const t2 = await page.locator('[data-first-tips-text]').textContent().catch(() => null);
      if (!t1 || !t2 || t1 === t2) note(design, where, `Next did not show another tip (${JSON.stringify(t1)} -> ${JSON.stringify(t2)})`);
      if (dashes.test(t1 + t2)) note(design, where, `a tip has an en or em dash: ${t1} / ${t2}`);
      await page.locator('[data-first-tips-skip]').click();
      if (await tips.count()) note(design, where, 'Skip did not close the tips');
    }
    if (!await bar.isVisible()) note(design, where, 'no hint bar on the first visit');
    else {
      await page.locator('[data-hint-close]').click();
      if (await bar.isVisible()) note(design, where, 'closing the hint bar did not hide it');
    }
    await load();
    if (await tips.count()) note(design, where, 'the tips showed again after Skip and a reload');
    if (await bar.isVisible()) note(design, where, 'the closed hint bar came back after a reload');
    await page.locator('[data-settings-toggle]').click();
    const tg = page.locator('[data-hint-toggle]');
    if (!await tg.count()) note(design, where, 'no hint bar toggle in the settings panel');
    else {
      await tg.click();
      await page.keyboard.press('Escape');
      if (!await bar.isVisible()) note(design, where, 'the settings toggle did not bring the hint bar back');
      await load();
      if (!await bar.isVisible()) note(design, where, 'the hint bar turned back on was off again after a reload');
    }
    for (const e of errors) note(design, where, e);
  } catch (e) {
    note(design, where, (e && e.message) || String(e));
  } finally {
    await ctx.close().catch(() => {});
  }
}

// A live reload (a save in the editor) keeps the view history and the
// followed net; a fresh load (here a dropped file) clears both and does not
// seed the history with the path it leaves. On a page of its own: the dropped
// file stops the live poller for the rest of that page.
async function smokeReloadNav(design, ctx, top, W) {
  const where = 'reload keeps navigation';
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  const fmt = JSON.stringify;
  try {
    await page.goto(`${BASE}/${design}/socle.html`, { waitUntil: 'networkidle' });
    await page.waitForSelector('[data-hier-path]', { timeout: 15000 });
    const readView = () => page.evaluate(() => {
      const t = document.querySelector('[data-content]')?.style.transform || '';
      const m = t.match(/translate\(([-\d.e]+)px, ([-\d.e]+)px\) scale\(([-\d.e]+)\)/);
      return m ? { x: +m[1], y: +m[2], z: +m[3] } : null;
    });
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForFunction(() => !(window.__socleFlying && window.__socleFlying()), null, { timeout: 10000 }).catch(() => {});
      let v = await readView();
      for (let i = 0; i < 25; i++) {
        await page.waitForTimeout(150);
        const n = await readView();
        if (fmt(n) === fmt(v)) break;
        v = n;
      }
    };
    const crumbs = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > '));
    const go = async (p) => { await page.locator(`[data-hier-path="${p}"]`).first().dispatchEvent('click'); await settle(); };
    const hist = () => page.evaluate(() => window.__socleHistory());
    const follow = () => page.evaluate(() => window.__socleFollow());
    const sameNet = (a, b) => !!a && !!b && a.from === b.from && a.to === b.to && a.origin === b.origin;
    await settle();
    // Follow W with a real click on the wire.
    const pt = await page.evaluate(([f, t]) => {
      const el = document.querySelector(`path[data-wire="${f}"][data-wire-to="${t}"]`);
      if (!el) return null;
      const L = el.getTotalLength(); const m = el.getScreenCTM();
      for (const k of [0.5, 0.3, 0.7, 0.15, 0.85]) {
        const q = el.getPointAtLength(L * k);
        const x = m.a * q.x + m.c * q.y + m.e, y = m.b * q.x + m.d * q.y + m.f;
        const h = document.elementFromPoint(x, y);
        if (h && (h === el || el.contains(h))) return { x, y };
      }
      return null;
    }, W);
    if (!pt) { note(design, where, `no visible wire ${W.join(' <= ')} to follow`); return; }
    await page.mouse.click(pt.x, pt.y);
    await page.waitForTimeout(150);
    await go(`${top}/cluster0`);
    await page.waitForTimeout(800); // past the settle delay: nothing pending
    const h0 = await hist(); const f0 = await follow(); const c0 = await crumbs();
    if (!f0 || !f0.net) { note(design, where, `no followed net before the reload: ${fmt(f0)}`); return; }
    if (!h0 || h0.size < 2 || h0.index < 1) { note(design, where, `no Back entry before the reload: ${fmt(h0)}`); return; }

    // The live reload: the served design, changed, under a later Last-Modified.
    const served = JSON.parse(await page.evaluate(() => fetch('design.json', { cache: 'no-store' }).then((r) => r.text())));
    served.sources = { ...(served.sources || {}), 'smoke/Reload.scala': '// a live reload\n' };
    const body = JSON.stringify(served);
    let gets = 0;
    await page.route('**/design.json', (rt) => {
      if (rt.request().method() === 'GET') gets++;
      return rt.fulfill({ contentType: 'application/json', body, headers: { 'Last-Modified': 'Wed, 01 Jan 2031 00:00:00 GMT' } });
    });
    for (let t = 0; t < 10000 && gets === 0; t += 100) await page.waitForTimeout(100);
    if (!gets) { note(design, where, 'the live poller never fetched the changed design'); return; }
    await page.waitForTimeout(400);
    await settle();
    const h1 = await hist(); const f1 = await follow(); const c1 = await crumbs();
    if (c1 !== c0) note(design, where, `the live reload moved the drill path: "${c0}" -> "${c1}"`);
    if (!h1 || h1.size !== h0.size || h1.index !== h0.index)
      note(design, where, `the live reload changed the history: ${fmt(h0)} -> ${fmt(h1)}`);
    if (!f1 || !sameNet(f0.net, f1.net)) note(design, where, `the live reload dropped the followed net: ${fmt(f0 && f0.net)} -> ${fmt(f1 && f1.net)}`);
    else if (!f1.wires.length) note(design, where, `after the live reload the followed net lights no wire in cluster0: ${fmt(f1)}`);
    const litNow = await page.evaluate(() => document.querySelectorAll('[data-content] .scl-w.follow').length);
    if (!litNow) note(design, where, 'after the live reload no wire is drawn followed in cluster0');

    // A fresh load (a dropped design file), still drilled into cluster0, while
    // a live reload is on its way: the served design changes again and its
    // download is slow. The dropped file wins: the late live design is not
    // applied over it.
    await page.unroute('**/design.json');
    let slowGets = 0;
    await page.route('**/design.json', async (rt) => {
      const headers = { 'Last-Modified': 'Thu, 01 Jan 2032 00:00:00 GMT' };
      if (rt.request().method() !== 'GET') return rt.fulfill({ contentType: 'application/json', body: '', headers });
      slowGets++;
      await new Promise((r) => setTimeout(r, 2500));
      return rt.fulfill({ contentType: 'application/json', body, headers }).catch(() => {});
    });
    for (let t = 0; t < 10000 && slowGets === 0; t += 100) await page.waitForTimeout(100);
    if (!slowGets) note(design, where, 'the live poller never started the slow reload');
    await page.evaluate((txt) => {
      const dt = new DataTransfer();
      dt.items.add(new File([txt], 'fresh.json', { type: 'application/json' }));
      window.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, body);
    const dropped = await page.waitForSelector('[data-source="file"]', { timeout: 10000 }).then(() => true).catch(() => false);
    if (!dropped) { note(design, where, 'the dropped design file never loaded'); return; }
    await page.waitForTimeout(3000); // the slow live reload lands
    const src = await page.evaluate(() => document.querySelector('[data-source]')?.getAttribute('data-source'));
    if (src !== 'file') note(design, where, `a live reload in flight replaced the dropped file (source ${src})`);
    await page.unroute('**/design.json');
    await settle();
    await page.waitForTimeout(300);
    const h2 = await hist(); const f2 = await follow(); const c2 = await crumbs();
    if (c2 !== top) note(design, where, `a fresh load did not reset to the top: "${c2}"`);
    if (!h2 || h2.size > 1 || h2.entries.some((e) => e.path !== top))
      note(design, where, `a fresh load kept or seeded the old history: ${fmt(h2 && h2.entries.map((e) => e.path))}`);
    if (f2 && f2.net) note(design, where, `a fresh load kept the followed net: ${fmt(f2.net)}`);
    // Another fresh load with the top already shown (the same path): its fit
    // is still the history's first entry, so the next move has a Back.
    await page.evaluate((txt) => {
      const dt = new DataTransfer();
      dt.items.add(new File([txt], 'again.json', { type: 'application/json' }));
      window.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, body);
    await page.waitForTimeout(300);
    await settle();
    await page.waitForTimeout(300);
    const h3 = await hist();
    if (!h3 || h3.size !== 1 || h3.entries[0].path !== top)
      note(design, where, `a fresh load onto the path shown left the history ${fmt(h3 && h3.entries.map((e) => e.path))}, not the top's fit`);
    if (!errors.length) reloadNavDesigns.add(design);
  } catch (e) {
    note(design, where, (e && e.message) || String(e));
  } finally {
    for (const e of errors) note(design, where, e);
    await page.close().catch(() => {});
  }
}

async function smokeDesignIn(design, ctx) {
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
    if (/\/design\.json$/.test(r.url())) dj.push(`${r.request().method()} ${r.status()} ${r.headers()['last-modified'] || '-'} @${Date.now() - t0}ms`);
  });
  // (Diagnostics: every design.json request and every design load of the page.)
  const t0 = Date.now(), dj = [];
  const loadLog = async () => {
    const loads = await page.evaluate(() => JSON.stringify((window.__socleLoads || []).map((l) => `${l.kind}@${Math.round(l.t)}`))).catch(() => '?');
    const runs = await page.evaluate(() => JSON.stringify((window.__socleEngineRuns || []).slice(-4).map((r) => `${r.key.slice(0, 120)} @${Math.round(r.t)} cache=${r.cached}`))).catch(() => '?');
    const nRuns = await page.evaluate(() => (window.__socleEngineRuns || []).length).catch(() => '?');
    return `loads ${loads}, design.json [${dj.slice(-6).join('; ')}] (${dj.length}), engine misses ${nRuns}: ${runs}`;
  };

  const drain = (where) => { while (errors.length) note(design, where, errors.shift()); };

  // The first view of a module (on load, after a drill) must already be the
  // fitted view: once the layout settles, pressing "Fit to view" must not move
  // it. The armed fit once ran on the new width with a stale height, leaving the
  // content pushed down until the user pressed fit.
  const readView = () => page.evaluate(() => {
    const t = document.querySelector('[data-content]')?.style.transform || '';
    const m = t.match(/translate\(([-\d.e]+)px, ([-\d.e]+)px\) scale\(([-\d.e]+)\)/);
    return m ? { x: +m[1], y: +m[2], z: +m[3] } : null;
  });
  const expectFitted = async (where) => {
    await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
    let v = await readView();
    for (let i = 0; i < 20; i++) {           // wait for the view to stop moving
      await page.waitForTimeout(100);
      const n = await readView();
      if (JSON.stringify(n) === JSON.stringify(v)) break;
      v = n;
    }
    const fit = page.locator('button[title="Fit to view"]');
    if (!v || await fit.count() === 0) { note(design, where, 'first view: no content transform or Fit button'); return; }
    await fit.first().click().catch((e) => note(design, where, e.message));
    await page.waitForTimeout(60);
    const f = await readView();
    if (!f || Math.abs(f.x - v.x) > 2 || Math.abs(f.y - v.y) > 2 || Math.abs(f.z - v.z) > 0.005)
      note(design, where, `first view is not the fitted view: ${JSON.stringify(v)} vs fit ${JSON.stringify(f)}`);
  };

  // The captured view stands in for the language server: `/ide.json` enables
  // the editor link and `POST /open` records what selecting asks to open.
  const opened = [];
  if (design === 'deepsoc-view' || design === 'fsm-view') {
    await page.route('**/ide.json', (r) => r.fulfill({ contentType: 'application/json', body: '{"status":"idle"}' }));
    await page.route('**/open', (r) => {
      try { opened.push(JSON.parse(r.request().postData() || '{}')); } catch { opened.push({}); }
      return r.fulfill({ status: 204 });
    });
  }

  await page.goto(`${BASE}/${design}/socle.html`, { waitUntil: 'networkidle' });
  // A design that renders zero hierarchy nodes is a failure, not a skip:
  // every fixture has at least a top module (issue #103 - the blank-canvas
  // regression class must not pass silently).
  const loaded = await page.waitForSelector('[data-hier-path]', { timeout: 15000 })
    .then(() => true).catch(() => false);
  drain('initial load');
  if (!loaded) {
    note(design, 'initial load', 'no hierarchy nodes rendered within 15s');
    return;
  }
  await expectFitted('first view fit');

  // The schematic's Back and Forward buttons: there, and both disabled on a
  // fresh load (one view, nowhere to go). The history block walks them.
  {
    const st = await page.evaluate(() => ({
      back: document.querySelector('[data-nav-back]')?.disabled ?? null,
      fwd: document.querySelector('[data-nav-forward]')?.disabled ?? null,
    }));
    if (st.back === null || st.fwd === null) note(design, 'nav buttons', 'no [data-nav-back] / [data-nav-forward] button on the schematic');
    else if (!st.back || !st.fwd) note(design, 'nav buttons', `Back / Forward enabled on a fresh load: ${JSON.stringify(st)}`);
  }
  drain('nav buttons');

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
      const saved = await page.evaluate(() => { try { return localStorage.getItem('socle-theme'); } catch { return null; } });
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
      const saved = await page.evaluate(() => { try { return localStorage.getItem('socle-editor'); } catch { return null; } });
      if (saved !== after) note(design, 'editor presets', `editor choice not persisted (data-editor="${after}", localStorage="${saved}")`);
    }
  }
  drain('editor presets');

  // Fully expand the hierarchy tree: open every closed row (a click on an
  // open one would close it), until none is left.
  for (let i = 0; i < 200; i++) {
    const shut = page.locator('[data-hier-toggle]', { hasText: '▸' });
    if (!await shut.count()) break;
    await shut.first().click({ timeout: 1500 }).catch(() => {});
  }

  // Drill into every node in the hierarchy and check the app survives.
  const paths = await page.locator('[data-hier-path]').evaluateAll(
    (els) => els.map((e) => ({ path: e.getAttribute('data-hier-path'), leaf: e.getAttribute('data-hier-leaf') === '1' })),
  );
  console.log(`[${design}] ${paths.length} hierarchy nodes`);
  for (const { path, leaf } of paths) {
    await page.locator(`[data-hier-path="${path}"]`).first().click({ timeout: 3000 }).catch((e) => note(design, `click ${path}`, e.message));
    await page.waitForTimeout(120);
    await expectFitted(`drill ${path} view fit`);
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

  // Expansions are kept per module, and returning to a module never hangs its
  // layout. In AXISystem, `mem` has an expandable `respQ.io.deq`: once a path of
  // one module reached the top module's layout run, whose stale run then left
  // "laying out..." on for good. Expand it in `mem`, go to the top (the layout
  // must settle within 5 s), then come back: `respQ.io.deq` is still expanded.
  if (design === 'axisystem') {
    const where = 'expansions per module';
    const top = paths[0].path;
    const mem = `${top}/mem`;
    const pin = 'respQ.io.deq';
    const ready = () => page.waitForSelector('[data-layout-ready="1"]', { timeout: 5000 }).then(() => true).catch(() => false);
    const mark = () => page.evaluate((p) => {
      const e = document.querySelector(`[data-bundle-toggle="${p}"]`);
      return e ? (e.getAttribute('data-open') === '1' ? '\u2212' : '+') : null;
    }, pin);
    await page.locator(`[data-hier-path="${top}"]`).first().click().catch((e) => note(design, where, e.message));
    await ready();
    await page.locator(`[data-hier-path="${mem}"]`).first().click().catch((e) => note(design, where, e.message));
    if (!await ready()) note(design, where, `drill ${mem}: layout not ready within 5 s`);
    await page.locator(`[data-bundle-toggle="${pin}"]`).first().click({ timeout: 3000 }).catch((e) => note(design, where, `expand ${pin}: ${e.message}`));
    if (!await ready()) note(design, where, `expand ${pin}: layout not ready within 5 s`);
    if (await mark() !== '−') note(design, where, `${pin} not expanded after its click (mark ${await mark()})`);
    await page.locator(`[data-hier-path="${top}"]`).first().click().catch((e) => note(design, where, e.message));
    if (!await ready()) note(design, where, `back to ${top}: "laying out..." stuck (data-layout-ready stays 0 after 5 s)`);
    await page.locator(`[data-hier-path="${mem}"]`).first().click().catch((e) => note(design, where, e.message));
    if (!await ready()) note(design, where, `return to ${mem}: layout not ready within 5 s`);
    if (await mark() !== '−') note(design, where, `${pin} not kept expanded on return to ${mem} (mark ${await mark()})`);
    // Collapse it again, so the later checks start from the default view.
    await page.locator(`[data-bundle-toggle="${pin}"]`).first().click({ timeout: 3000 }).catch(() => {});
    await ready();
    drain(where);
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
    // The fold toggle takes no click in the overview (zoomed out past the label
    // size), so zoom in until the detail shows: at fit, Cluster's code-block
    // boxes (with their condition pins) can land just under that threshold.
    const detail = async () => {
      for (let i = 0; i < 4 && (await count('.ov')); i++) {
        await page.locator('button[title="Zoom in"]').first().click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(150);
      }
    };
    await page.locator('[data-hier-path$="/cluster0"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, e.message));
    await settle();
    await expectFolded('initial');
    await detail();
    await page.locator('[data-fold-toggle="cores"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, `unfold: ${e.message}`));
    await settle();
    await expectUnfolded('after +');
    // Expanding a bundle on an unfolded copy grows that box: the engine must
    // lay it out at its grown height, so the copies never overlap and keep at
    // least the engine's smallest in-layer spacing. That is not the gap they
    // had before: two boxes sit nodeNode (40) apart, or 2 x edgeNode (10 + 10)
    // when a wire runs between them, and the re-layout may route one through.
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
      // Every declared bundle port is a closed row with a toggle (#332).
      note(design, where, 'cores_0 has no [data-bundle-toggle]');
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
        const g1 = gapOf(after);
        // Screen px per layout unit: the content layer's zoom.
        const z = await page.evaluate(() => parseFloat(document.querySelector('[data-content="1"]').style.getPropertyValue('--z')));
        const minGap = 20 * z; // 2 x ELK spacing.edgeNode (10), the closest two boxes of a layer get
        if (grew <= 0) note(design, where, `after expand: cores_0 did not grow (${grew}px)`);
        if (g1 <= 0) note(design, where, `after expand: cores_0 overlaps cores_1 ${JSON.stringify(after)}`);
        else if (g1 < minGap - 2) note(design, where, `after expand: copies ${g1.toFixed(1)}px apart, under the engine's ${minGap.toFixed(1)}px minimum`);
      }
      await expectUnshifted('after expand');
    }
    drain(`${where} (expand)`);
    // Select a copy (its header, left of the toggle), then fold its group back:
    // the fold removes the selected box, so the selection (and cores_0's expanded
    // bundles) are cleared; both are checked once the copies are back.
    await detail();
    await page.locator('[data-box-inst="cores_1"] > div').first().click({ position: { x: 20, y: 8 }, timeout: 3000 })
      .catch((e) => note(design, where, `select cores_1: ${e.message}`));
    if (!(await count('[data-inst-inspect="cores_1"]'))) note(design, where, 'cores_1 not selected (no inspector entry)');
    await detail();
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

  // Tied ports: the captured view drops a constant tie-off from the layout and
  // records it in `tieOffs`; the tied pin shows a `|-` value stub
  // (`data-tied`), and the layout is the same with or without the tie-offs.
  // Core ties `rf.io.raddr := 0.U`. `alu.io.op := 0.U` and `rf.io.waddr` are
  // assigned inside the `when(io.in.fire)` box (code view), so they are its
  // wires, not ties; `alu.io.b := 0.U` is a default next to a signal, no tie.
  // A derived design gives one pin two values, covered sinks and long lists.
  // It runs before the logic-block checks on purpose: their live reload after
  // this drill into a copy caught a stale hover blanking the Inspector.
  if (design === 'deepsoc-view') {
    const where = 'tied ports';
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(150);
    };
    // Core is reached through cluster0's unfolded cores_0 (copies are not in the tree).
    await page.locator('[data-hier-path$="/cluster0"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, `cluster0: ${e.message}`));
    await settle();
    if (await page.locator('[data-fold-group="cores"]').count()) {
      await page.locator('[data-fold-group="cores"]').first().dblclick({ timeout: 3000 }).catch((e) => note(design, where, `unfold: ${e.message}`));
      await settle();
    }
    // On the body: a click on the header selects, which re-renders the box mid double-click.
    await page.locator('[data-box-inst="cores_0"]').first().dblclick({ timeout: 3000 })
      .catch((e) => note(design, where, `drill cores_0: ${e.message}`));
    await settle();
    const read = () => page.evaluate(() => ({
      tied: Array.from(document.querySelectorAll('[data-tied]')).map((e) => ({
        pin: e.getAttribute('data-tied'), label: e.innerText.trim(), title: e.getAttribute('title') || '',
        r: e.querySelector('span')?.getBoundingClientRect().toJSON(),
      })),
      boxes: Object.fromEntries(['alu', 'rf'].map((id) => [id, document.querySelector(`[data-box-inst="${id}"]`)?.getBoundingClientRect().toJSON()])),
      ids: Array.from(document.querySelectorAll('[data-box-inst],[data-boundary-stub],[data-block],[data-logic-node]'))
        .map((e) => e.getAttribute('data-box-inst') || e.getAttribute('data-boundary-stub') || e.getAttribute('data-block') || e.getAttribute('data-logic-node')),
    }));
    const r0 = await read();
    const op = r0.tied.find((t) => t.pin === 'rf.io.raddr');
    if (!op) note(design, where, `no [data-tied="rf.io.raddr"] stub: ${JSON.stringify(r0.tied.map((t) => t.pin))}`);
    else {
      if (op.label !== '0') note(design, where, `rf.io.raddr stub label ${JSON.stringify(op.label)}`);
      if (op.title !== 'tied to 0') note(design, where, `rf.io.raddr stub title ${JSON.stringify(op.title)}`);
      // A WEST pin: the value label sits left of the rf box.
      const b = r0.boxes.rf;
      if (!b || !op.r || op.r.right > b.left + 1 || op.r.bottom < b.top || op.r.top > b.bottom)
        note(design, where, `rf.io.raddr label ${JSON.stringify(op.r)} not beside rf ${JSON.stringify(b)}`);
    }
    // io.out.bits.valid lands on the closed io.out stub (#332).
    for (const [pin, v] of [['rf.io.raddr', '0'], ['io.out', '1']])
      if (!r0.tied.some((t) => t.pin === pin && t.label === v)) note(design, where, `no [data-tied="${pin}"] stub reading ${v}`);
    // `when` defaults next to another driver, and assignments inside a `when` box, are not ties.
    for (const pin of ['alu.io.b', 'rf.io.wdata', 'rf.io.wen', 'alu.io.op', 'rf.io.waddr'])
      if (r0.tied.some((t) => t.pin === pin)) note(design, where, `the default at ${pin} shows as tied`);
    if (r0.ids.some((id) => (id || '').includes('const$'))) note(design, where, `a const node is laid out: ${r0.ids}`);
    // Derived designs: the same layout without tie-offs; one pin, two values.
    const base = JSON.parse(await (await page.request.get(`${BASE}/${design}/design.json`)).text());
    let stamp = 2020;
    const serve = async (mutate) => {
      const d = structuredClone(base);
      for (const m of d.modules) if (m.tieOffs) mutate(m);
      await page.unroute('**/design.json');
      await page.route('**/design.json', (rt) => rt.fulfill({
        contentType: 'application/json', body: JSON.stringify(d),
        headers: { 'Last-Modified': `Wed, 01 Jan ${stamp++} 00:00:00 GMT` },
      }));
    };
    await serve((m) => { m.tieOffs = []; });
    const gone = await page.waitForFunction(() => !document.querySelector('[data-tied]'), null, { timeout: 10000 })
      .then(() => true).catch(() => false);
    await settle();
    if (!gone) note(design, where, 'without tie-offs: a [data-tied] stub remains');
    else {
      const r1 = await read();
      if (JSON.stringify(r1.boxes) !== JSON.stringify(r0.boxes) || r1.ids.join() !== r0.ids.join())
        note(design, where, `the tie-offs changed the layout: ${JSON.stringify(r0.boxes)} vs ${JSON.stringify(r1.boxes)}`);
    }
    // Sinks below a laid pin (`rf.io.raddr[3]`) land on the pin covering it; a
    // tooltip lists at most 10 sinks or `sink = value` lines, then `… and N more`.
    const many = (pre, n, val) => Array.from({ length: n }, (_, i) => ({ sink: `${pre}${i}`, value: val(i) }));
    await serve((m) => { m.tieOffs = [...m.tieOffs, { sink: 'alu.io.op', value: '0' }, { sink: 'alu.io.op', value: '3' },
      { sink: 'rf.io.raddr[3]', value: '0' }, { sink: 'rf.io.waddr', value: '0' }, { sink: 'rf.io.waddr.x', value: '1' }, ...many('rf.io.raddr.g', 11, () => '0'), ...many('io.out.bits.valid.f', 12, (i) => String(i))]; });
    const two = await page.waitForFunction(() => document.querySelector('[data-tied="alu.io.op"]')?.innerText.trim() === '2 tied', null, { timeout: 10000 })
      .then(() => true).catch(() => false);
    await settle();
    if (!two) note(design, where, `two values: no "2 tied" stub at alu.io.op: ${JSON.stringify((await read()).tied.find((x) => x.pin === 'alu.io.op'))}`);
    else {
      const tied = (await read()).tied;
      const at = (pin) => tied.find((x) => x.pin === pin);
      const expect = (pin, label, lines) => {
        const t = at(pin);
        if (t?.label !== label || t?.title !== lines.join('\n')) note(design, where, `${pin}: expected ${JSON.stringify([label, ...lines])}, got ${JSON.stringify(t)}`);
      };
      expect('alu.io.op', '2 tied', ['alu.io.op = 0', 'alu.io.op = 3']);
      expect('rf.io.waddr', '2 tied', ['rf.io.waddr = 0', 'rf.io.waddr.x = 1']);
      expect('rf.io.raddr', '0', ['tied to 0', 'rf.io.raddr', 'rf.io.raddr[3]', ...Array.from({ length: 8 }, (_, i) => `rf.io.raddr.g${i}`), '… and 3 more']);
      expect('io.out', '13 tied', ['io.out.bits.valid = 1', ...Array.from({ length: 9 }, (_, i) => `io.out.bits.valid.f${i} = ${i}`), '… and 3 more']);
      // Only the two pins given ties here (alu.io.op, rf.io.waddr) add a stub.
      if (tied.length !== r0.tied.length + 2) note(design, where, `covered sinks made new stubs: ${tied.length} vs ${r0.tied.length} + 2`);
    }
    // Back to the served design for the checks below.
    await page.unroute('**/design.json');
    await page.waitForFunction(() => !document.querySelector('[data-tied="alu.io.op"]')
      && document.querySelector('[data-tied="rf.io.raddr"]')?.innerText.trim() === '0', null, { timeout: 10000 })
      .catch(() => note(design, where, 'restore: the alu.io.op stub is not gone or rf.io.raddr not back to 0'));
    await settle();
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__tied.png` }).catch(() => {});
    drain(where);
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
        logicNodes: Array.from(document.querySelectorAll('[data-logic-node]')).map((e) => e.getAttribute('data-logic-node')).sort(),
      }));
      if (!r.blocks.length) note(design, where, `${m}: no [data-block] box`);
      for (const b of r.blocks) if (!b.label) note(design, where, `${m}: block ${b.id} has no header label`);
      // Only the registers the `when` boxes write sit next to the blocks (code view).
      const plain = m === 'cluster0' ? ['outSel', 'sel'] : [];
      if (JSON.stringify(r.logicNodes) !== JSON.stringify(plain))
        note(design, where, `${m}: [data-logic-node] boxes next to the blocks ${JSON.stringify(r.logicNodes)}, expected ${JSON.stringify(plain)}`);
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
        // Each selection opens its own line: the wire into a block pin opens
        // that connection, a register inside the open block its declaration.
        const src = 'example/src/deephier/DeepSoc.scala';
        const expectLine = async (step, line) => {
          await page.waitForTimeout(250);
          if (!opened.some((o) => o.file === src && o.line === line))
            note(design, where, `${step}: expected POST /open ${src}:${line}, got ${JSON.stringify(opened)}`);
        };
        opened.length = 0;
        await page.locator(`[data-wire="io.in"][data-wire-to="${id}.io_in_ready"]`).first().dispatchEvent('click').catch((e) => note(design, where, `wire click: ${e.message}`));
        await expectLine('the wire into a block pin', 136);
        await page.keyboard.press('Escape');
        // A register a `when` box writes, at module level, opens its declaration.
        opened.length = 0;
        await page.locator('[data-logic-node="sel"]').first().dispatchEvent('click').catch((e) => note(design, where, `select sel: ${e.message}`));
        await expectLine('register sel', 128);
        await page.keyboard.press('Escape');
        // A double-click opens the block: its contents, its label in the breadcrumb.
        await page.locator(`[data-block="${id}"] [data-block-label]`).first().dblclick({ timeout: 3000 })
          .catch((e) => note(design, where, `open ${id}: ${e.message}`));
        await settle();
        const inside = await page.evaluate(() => ({
          crumb: (document.querySelector('[data-crumb-block]')?.textContent || '').trim(),
          regs: document.querySelectorAll('[data-logic-node]').length,
          sel: !!document.querySelector('[data-logic-node="sel"]'),
          blocks: document.querySelectorAll('[data-block]').length,
          chip: document.querySelector('[data-module-chip]')?.getAttribute('data-module-chip'),
          meta: !!document.querySelector('[data-block-meta]'),
        }));
        if (inside.chip !== 'block' || !inside.meta) note(design, where, `open block: chip ${inside.chip}, Inspector block view ${inside.meta}`);
        if (inside.crumb !== 'logic') note(design, where, `open block: breadcrumb shows ${JSON.stringify(inside.crumb)}, not "logic"`);
        if (!inside.regs || inside.sel || inside.blocks) note(design, where, `open block: not its contents ${JSON.stringify(inside)}`);
        drain(`${where} (open block)`);
        // Back to the module.
        await page.locator('.scl-crumb').nth(-2).click({ timeout: 3000 }).catch((e) => note(design, where, `crumb back: ${e.message}`));
        await settle();
        if (!(await page.locator(`[data-block="${id}"]`).count())) note(design, where, 'the breadcrumb did not return to the module');
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
          if (await page.locator('.scl-insp-clear').count()) note(design, where, `live reload: the selection or hover of the dropped ${id} survived (blank Inspector)`);
        }
        drain(`${where} (live reload)`);
        // An FSM block opens its source too. No captured fixture holds an FSM,
        // so the same block is served again as an `fsm` box, named and shaped
        // as IrWalk names one (its label and ordinal, its register in `state`).
        const fsmId = 'fsm$Cluster$switch_state$0';
        const asFsm = JSON.parse(fresh.split('blk$Cluster$logic2').join(fsmId));
        for (const mod of asFsm.modules) for (const n of mod.logic || []) if (n.id === fsmId) { n.kind = 'fsm'; n.state = 'state'; }
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

  // FSM diagram (slice 3): the captured TrafficFsm's FSM block opens into its
  // state diagram. Clicking a state or a transition opens its source; Escape,
  // a breadcrumb segment and a live reload that drops the block close it.
  if (design === 'fsm-view') {
    const where = 'fsm diagram';
    const id = 'fsm$TrafficFsm$switch_state$0';
    const file = 'example/src/fsm/TrafficFsm.scala';
    const diag = `[data-fsm-diagram="${id}"]`;
    const ready = () => page.waitForSelector(`${diag}[data-fsm-ready="1"]`, { timeout: 10000 }).then(() => true).catch(() => false);
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(150);
    };
    // Everything the diagram shows, in one read: states, transitions (with the
    // distance from each label's center to its path's midpoint), markers, note.
    const readDiagram = () => page.evaluate((sel) => {
      const root = document.querySelector(sel);
      if (!root) return null;
      const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
      return {
        states: Array.from(root.querySelectorAll('[data-fsm-state]')).map((s) => {
          const r = s.getBoundingClientRect();
          return { name: s.getAttribute('data-fsm-state'), initial: s.getAttribute('data-initial') === '1',
                   box: { l: r.left, t: r.top, r: r.right, b: r.bottom } };
        }),
        transitions: Array.from(root.querySelectorAll('[data-fsm-transition]')).map((t) => {
          const path = t.querySelector('[data-fsm-path]');
          const label = t.querySelector('[data-fsm-label]');
          let dist = null, len = 0;
          if (path) {
            len = path.getTotalLength();
            const p = path.getPointAtLength(len / 2);
            const m = path.getScreenCTM();
            const mid = { x: m.a * p.x + m.c * p.y + m.e, y: m.b * p.x + m.d * p.y + m.f };
            if (label) { const c = center(label.getBoundingClientRect()); dist = Math.hypot(c.x - mid.x, c.y - mid.y); }
          }
          return { from: t.getAttribute('data-from'), to: t.getAttribute('data-to'),
                   text: (label?.textContent || '').trim(), len, dist };
        }),
        any: root.querySelectorAll('[data-fsm-any]').length,
        note: (root.querySelector('[data-fsm-note]')?.textContent || '').trim(),
      };
    }, diag);
    const overlaps = (a, b) => a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b;
    const crumbs = () => page.$$eval('.scl-crumb', (els) => els.map((e) => e.textContent.trim()));
    const schematicShown = () => page.evaluate((fid) => {
      const b = document.querySelector(`[data-fsm="${fid}"]`);
      if (!b) return false;
      const r = b.getBoundingClientRect();
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return !document.querySelector('[data-fsm-diagram]') && !!top && b.contains(top);
    }, id);
    // A reload of a design derived from the served one (a later Last-Modified each time).
    const base = JSON.parse(await (await page.request.get(`${BASE}/${design}/design.json`)).text());
    let stamp = 2031;
    const serve = async (mutate) => {
      const d = structuredClone(base);
      mutate(d);
      await page.unroute('**/design.json');
      await page.route('**/design.json', (rt) => rt.fulfill({
        contentType: 'application/json', body: JSON.stringify(d),
        headers: { 'Last-Modified': `Wed, 01 Jan ${stamp++} 00:00:00 GMT` },
      }));
    };
    const fsmOf = (d) => d.modules.flatMap((m) => m.logic || []).find((n) => n.id === id).fsm;
    const openDiagram = async (step) => {
      await page.locator(`[data-fsm="${id}"]`).first().dblclick({ timeout: 3000, position: { x: 30, y: 8 } })
        .catch((e) => note(design, where, `${step}: dblclick: ${e.message}`));
      if (!(await ready())) { note(design, where, `${step}: no ready ${diag} after a double-click`); return false; }
      return true;
    };
    const expectOpened = async (step, line) => {
      await page.waitForTimeout(250);
      if (!opened.some((o) => o.file === file && o.line === line))
        note(design, where, `${step}: expected POST /open ${file}:${line}, got ${JSON.stringify(opened)}`);
    };
    // A real (hit-tested) click on a state's box.
    const clickState = async (name, line) => {
      opened.length = 0;
      await page.locator(`${diag} [data-fsm-state="${name}"] rect`).first().click({ timeout: 3000 })
        .catch((e) => note(design, where, `click ${name}: ${e.message}`));
      await expectOpened(`click ${name}`, line);
    };
    // A real (hit-tested) mouse click at a transition path's midpoint.
    const clickTransition = async (from, to, line) => {
      const step = `click ${from}->${to}`;
      opened.length = 0;
      const pt = await page.evaluate(([sel, f, t]) => {
        const path = document.querySelector(`${sel} [data-fsm-transition][data-from="${f}"][data-to="${t}"] [data-fsm-path]`);
        if (!path) return null;
        const p = path.getPointAtLength(path.getTotalLength() / 2);
        const m = path.getScreenCTM();
        return { x: m.a * p.x + m.c * p.y + m.e, y: m.b * p.x + m.d * p.y + m.f };
      }, [diag, from, to]);
      if (!pt) { note(design, where, `${step}: no path`); return; }
      await page.mouse.click(pt.x, pt.y);
      await expectOpened(step, line);
    };

    await page.locator(`[data-hier-path="${paths[0].path}"]`).first().click().catch(() => {});
    await settle();
    if (await openDiagram('open')) {
      const r = await readDiagram();
      const names = r.states.map((s) => s.name).sort().join(',');
      if (names !== 'sGreen,sRed,sYellow') note(design, where, `states ${names}`);
      const initial = r.states.filter((s) => s.initial).map((s) => s.name);
      if (initial.join() !== 'sRed') note(design, where, `initial states ${JSON.stringify(initial)}`);
      const pairs = r.transitions.map((t) => `${t.from}->${t.to}`).sort().join(',');
      if (pairs !== 'sGreen->sYellow,sRed->sGreen,sYellow->sRed') note(design, where, `transitions ${pairs}`);
      for (const t of r.transitions) {
        if (t.text !== 'io.tick') note(design, where, `${t.from}->${t.to} label ${JSON.stringify(t.text)}`);
        if (t.dist === null || t.dist > 40) note(design, where, `${t.from}->${t.to} label ${t.dist?.toFixed(1)}px from its path's midpoint`);
      }
      for (let i = 0; i < r.states.length; i++) for (let j = i + 1; j < r.states.length; j++)
        if (overlaps(r.states[i].box, r.states[j].box)) note(design, where, `${r.states[i].name} overlaps ${r.states[j].name}`);
      // The box id (a source marker) names no register: the crumb reads the
      // box's `state` field.
      const stateField = await page.evaluate(async (box) => {
        const d = await (await fetch('design.json')).json();
        const all = [...d.modules.flatMap((m) => m.logic || []), ...Object.values(d.regions || {}).flatMap((b) => b.logic || [])];
        return all.find((n) => n.id === box)?.state ?? null;
      }, id).catch((e) => `error: ${e.message}`);
      if (stateField !== 'state') note(design, where, `${id} carries state ${JSON.stringify(stateField)}, not "state"`);
      const cs = await crumbs();
      if (cs[cs.length - 1] !== 'FSM: state') note(design, where, `last breadcrumb ${JSON.stringify(cs)}`);
      // The start dot sits above the initial state (it is laid out in the first layer).
      const start = await page.evaluate((sel) => {
        const dot = document.querySelector(`${sel} [data-fsm-start]`)?.getBoundingClientRect();
        const init = document.querySelector(`${sel} [data-fsm-state][data-initial="1"]`)?.getBoundingClientRect();
        return dot && init && { dotY: dot.top + dot.height / 2, initTop: init.top };
      }, diag);
      if (!start || !(start.dotY < start.initTop))
        note(design, where, `the start dot is not above the initial state: ${JSON.stringify(start)}`);
      // `/` does not focus the schematic filter hidden under the diagram.
      await page.evaluate(() => document.activeElement?.blur?.());
      await page.keyboard.press('/');
      await page.waitForTimeout(100);
      if (await page.evaluate(() => !!document.activeElement?.closest?.('[data-filter-input]') || document.activeElement?.hasAttribute?.('data-filter-input')))
        note(design, where, '`/` focused the hidden schematic filter while the diagram is open');
      await page.evaluate(() => document.activeElement?.blur?.());
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__diagram.png` }).catch(() => {});
      // Clicks: a transition opens its assignment, a state its `is` line.
      await clickTransition('sGreen', 'sYellow', 17);
      await clickState('sYellow', 18);
      drain(`${where} (open)`);
      // Escape that closes the palette, or leaves the filter input, keeps the diagram.
      await page.keyboard.press('Control+k');
      await page.waitForSelector('[data-command-palette]', { timeout: 3000 }).catch(() => note(design, where, 'Ctrl-K did not open the palette over the diagram'));
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      if (await page.locator('[data-command-palette]').count()) note(design, where, 'Escape did not close the palette');
      if (!(await page.locator(diag).count())) note(design, where, 'Escape in the palette also closed the diagram');
      await page.locator('[data-filter-input]').first().focus().catch((e) => note(design, where, `filter focus: ${e.message}`));
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      if (!(await page.locator(diag).count())) note(design, where, 'Escape in the filter input also closed the diagram');
      drain(`${where} (escape in an overlay)`);
      // Escape closes it and the schematic is back.
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      if (!(await schematicShown())) note(design, where, 'Escape did not bring the schematic back');
      // The header's open control opens it too; the current module's
      // breadcrumb segment closes it.
      await page.locator(`[data-fsm-open="${id}"]`).first().click({ timeout: 3000 })
        .catch((e) => note(design, where, `open control: ${e.message}`));
      if (!(await ready())) note(design, where, `no ready ${diag} after clicking [data-fsm-open]`);
      else {
        await page.locator('.scl-crumb').first().click().catch((e) => note(design, where, `crumb: ${e.message}`));
        await page.waitForTimeout(200);
        if (!(await schematicShown())) note(design, where, 'the breadcrumb did not bring the schematic back');
      }
      // The open control is keyboard-reachable: focus + Enter opens.
      await page.locator(`[data-fsm-open="${id}"]`).first().focus().catch((e) => note(design, where, `open control focus: ${e.message}`));
      await page.keyboard.press('Enter');
      if (!(await ready())) note(design, where, `no ready ${diag} after Enter on [data-fsm-open]`);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      if (!(await schematicShown())) note(design, where, 'Escape after a keyboard open did not bring the schematic back');
      drain(`${where} (close)`);
    }

    // Back and Forward over the diagram: its own nav chip (Back always
    // enabled), Back closes it with the module's view untouched, Forward then
    // reopens it, and Alt+Left closes it too.
    {
      const w2 = 'fsm diagram navigation';
      const view = () => page.evaluate(() => document.querySelector('[data-content]')?.style.transform || '');
      const crumbsNow = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > '));
      const v0 = await view(), c0 = await crumbsNow();
      if (await openDiagram('navigation')) {
        const chip = await page.evaluate((sel) => {
          const d = document.querySelector(sel);
          const b = d?.querySelector('[data-nav-back]'), f = d?.querySelector('[data-nav-forward]');
          if (!b || !f) return null;
          const r = b.getBoundingClientRect(), dr = d.getBoundingClientRect();
          const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
          const n = d.querySelector('[data-fsm-note]')?.getBoundingClientRect();
          const overlap = !!n && !(n.left >= r.right || n.right <= r.left || n.top >= r.bottom || n.bottom <= r.top);
          return { back: !b.disabled, visible: !!top && b.contains(top), inset: [Math.round(r.left - dr.left), Math.round(r.top - dr.top)], overlap };
        }, diag);
        if (!chip) note(design, w2, 'no Back / Forward buttons on the state diagram');
        else {
          if (!chip.visible) note(design, w2, 'the diagram\'s Back button is covered');
          if (!chip.back) note(design, w2, 'Back is disabled on the state diagram');
          if (chip.overlap) note(design, w2, 'the diagram\'s note overlaps its Back / Forward buttons');
          await page.locator(`${diag} [data-nav-back]`).first().click();
          await page.waitForTimeout(250);
          if (await page.locator(diag).count()) note(design, w2, 'Back did not close the diagram');
          if (await view() !== v0 || await crumbsNow() !== c0) note(design, w2, `Back from the diagram moved the schematic: ${v0} at "${c0}" -> ${await view()} at "${await crumbsNow()}"`);
          const fwd = page.locator('[data-nav-forward]').first();
          if (await fwd.isDisabled()) note(design, w2, 'Forward is not enabled after Back closed the diagram');
          await fwd.click();
          if (!(await ready())) note(design, w2, 'Forward did not reopen the diagram');
          else {
            await page.keyboard.press('Alt+ArrowLeft');
            await page.waitForTimeout(250);
            if (await page.locator(diag).count()) note(design, w2, 'Alt+Left did not close the diagram');
          }
          if (await page.locator(diag).count()) { await page.keyboard.press('Escape'); await page.waitForTimeout(200); }
          fsmNavDesigns.add(design);
        }
      }
      drain(w2);
    }

    // Live reload with the diagram open, keeping the block: a self-loop, a
    // transition from any state and renamed labels show up in place; a label
    // over 60 characters is cut with an ellipsis and kept whole in the tooltip.
    const longLabel = 'io.tick && io.mode === 3.U && !(io.hold || io.stall) && io.count =/= 0.U';
    if (await openDiagram('before reload')) {
      await serve((d) => {
        const f = fsmOf(d);
        f.transitions[0].label = 'io.tick && go';
        f.transitions[1].label = longLabel;
        f.transitions.push({ from: 'sGreen', to: 'sGreen', label: 'io.hold', source: { file, line: 30, col: 5 } });
        f.transitions.push({ from: '*', to: 'sRed', label: 'io.reset', source: { file, line: 31, col: 5 } });
        f.transitions.push({ from: 'sRed', to: 'sBlue', label: 'io.odd', source: { file, line: 32, col: 5 } });
      });
      const updated = await page.waitForFunction((sel) => document.querySelectorAll(`${sel}[data-fsm-ready="1"] [data-fsm-transition]`).length === 5,
        diag, { timeout: 10000 }).then(() => true).catch(() => false);
      if (!updated) note(design, where, 'reload keeping the block: the open diagram did not show the 5 new transitions');
      else {
        const r = await readDiagram();
        if (!r.transitions.some((t) => t.text === 'io.tick && go')) note(design, where, 'reload: the renamed label is missing');
        const long = await page.evaluate((sel) => {
          const t = document.querySelector(`${sel} [data-fsm-transition][data-from="sGreen"][data-to="sYellow"]`);
          return t && { text: t.querySelector('[data-fsm-label]')?.textContent || '', tip: t.querySelector('title')?.textContent || '' };
        }, diag);
        if (!long || long.text !== longLabel.slice(0, 59) + '…' || !long.tip.includes(longLabel))
          note(design, where, `long label not cut to 60 characters with its full text as a tooltip: ${JSON.stringify(long)}`);
        const loop = r.transitions.find((t) => t.from === 'sGreen' && t.to === 'sGreen');
        if (!loop || !(loop.len > 10)) note(design, where, `self-loop not drawn: ${JSON.stringify(loop)}`);
        if (r.any !== 1) note(design, where, `expected one [data-fsm-any], got ${r.any}`);
        if (!r.transitions.some((t) => t.from === '*' && t.to === 'sRed')) note(design, where, 'no transition from * to sRed');
        if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__diagram-reload.png` }).catch(() => {});
        const unknown = '1 transition to an unknown state is not shown.';
        if (!r.note.includes(unknown)) note(design, where, `reload: note ${JSON.stringify(r.note)} lacks ${JSON.stringify(unknown)}`);
        await clickTransition('sGreen', 'sGreen', 30);
        await clickTransition('*', 'sRed', 31);
      }
      drain(`${where} (reload, self-loop, any)`);

      // Fallback: no transitions, a note; the states still show.
      const fallback = 'Transitions are not available: the state machine\'s Scala source could not be read.';
      await serve((d) => { const f = fsmOf(d); f.transitions = []; f.note = fallback; });
      const fell = await page.waitForFunction((sel) =>
        (document.querySelector(`${sel}[data-fsm-ready="1"] [data-fsm-note]`)?.textContent || '').startsWith('Transitions are not available'),
        diag, { timeout: 10000 }).then(() => true).catch(() => false);
      if (!fell) note(design, where, 'fallback: no [data-fsm-note]');
      else {
        const r = await readDiagram();
        if (r.note !== fallback) note(design, where, `fallback note ${JSON.stringify(r.note)}`);
        if (r.states.length !== 3) note(design, where, `fallback: ${r.states.length} states`);
        if (r.transitions.length) note(design, where, `fallback: ${r.transitions.length} transitions`);
        if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__diagram-fallback.png` }).catch(() => {});
      }
      drain(`${where} (fallback)`);

      // A diagram wider than the pane fits it by default; at 1:1 it scrolls
      // and both ends stay reachable.
      await serve((d) => {
        const f = fsmOf(d);
        f.note = '';
        f.states = Array.from({ length: 15 }, (_, i) => ({ name: `sState${i}`, initial: i === 0, source: { file, line: 40 + i, col: 5 } }));
        f.transitions = f.states.slice(1).map((s, i) => ({ from: 'sState0', to: s.name,
          label: `io.cmd === ${i + 1}.U && io.valid && !io.busy`, source: { file, line: 60 + i, col: 5 } }));
      });
      const wide = await page.waitForFunction((sel) => document.querySelectorAll(`${sel}[data-fsm-ready="1"] [data-fsm-state]`).length === 15,
        diag, { timeout: 10000 }).then(() => true).catch(() => false);
      if (!wide) note(design, where, 'wide diagram: the 15 states never showed');
      else {
        const geo = () => page.evaluate((sel) => {
          const pane = document.querySelector(`${sel} [data-fsm-scroll]`);
          const states = Array.from(document.querySelectorAll(`${sel} [data-fsm-state]`)).map((s) => s.getBoundingClientRect());
          const p = pane?.getBoundingClientRect();
          return pane && { pane: { l: p.left, r: p.right, t: p.top, b: p.bottom },
            left: Math.min(...states.map((r) => r.left)), right: Math.max(...states.map((r) => r.right)),
            top: Math.min(...states.map((r) => r.top)), bottom: Math.max(...states.map((r) => r.bottom)),
            scrollW: pane.scrollWidth, clientW: pane.clientWidth };
        }, diag);
        const fit = await geo();
        if (!fit) note(design, where, 'wide diagram: no [data-fsm-scroll] pane');
        else if (fit.left < fit.pane.l || fit.right > fit.pane.r || fit.top < fit.pane.t || fit.bottom > fit.pane.b)
          note(design, where, `wide diagram (fit): states [${fit.left}, ${fit.right}] outside the pane ${JSON.stringify(fit.pane)}`);
        if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__diagram-wide-fit.png` }).catch(() => {});
        // The fit follows the pane, not only the window: toggling the inspector
        // resizes the pane, and the SVG is rescaled to fit it again.
        const fitError = () => page.evaluate((sel) => {
          const pane = document.querySelector(`${sel} [data-fsm-scroll]`);
          const svg = pane?.querySelector('svg');
          if (!svg) return null;
          const [, , vw, vh] = svg.getAttribute('viewBox').split(' ').map(Number);
          const pad = 24;
          const s = Math.max(0.05, Math.min(1, (pane.clientWidth - 2 * pad) / vw, (pane.clientHeight - 2 * pad) / vh));
          const r = svg.getBoundingClientRect();
          return { paneW: pane.clientWidth, err: Math.max(Math.abs(r.width - vw * s), Math.abs(r.height - vh * s)) };
        }, diag);
        const togglePanel = () => page.locator('button[title="Toggle the inspector panel"]').first().click({ timeout: 3000 })
          .catch((e) => note(design, where, `inspector toggle: ${e.message}`));
        const before = await fitError();
        for (const step of ['toggled', 'toggled back']) {
          await togglePanel();
          await page.waitForTimeout(300);
          const after = await fitError();
          if (!after || after.err > 1.5)
            note(design, where, `wide diagram (fit), inspector ${step}: the SVG does not fit the resized pane ${JSON.stringify({ before, after })}`);
          else if (step === 'toggled' && before && after.paneW === before.paneW)
            note(design, where, `wide diagram (fit): toggling the inspector did not resize the pane (${after.paneW})`);
        }
        await page.locator(`${diag} [data-fsm-zoom="1:1"]`).first().click({ timeout: 3000 }).catch((e) => note(design, where, `1:1 toggle: ${e.message}`));
        await page.waitForTimeout(200);
        const one = await geo();
        if (one && !(one.scrollW > one.clientW)) note(design, where, `wide diagram (1:1): does not scroll (${one.scrollW} <= ${one.clientW})`);
        await page.evaluate((sel) => { document.querySelector(`${sel} [data-fsm-scroll]`).scrollLeft = 0; }, diag);
        const atLeft = await geo();
        if (atLeft && atLeft.left < atLeft.pane.l - 1) note(design, where, `wide diagram (1:1): the leftmost state is unreachable (${atLeft.left} < ${atLeft.pane.l})`);
        await page.evaluate((sel) => { const e = document.querySelector(`${sel} [data-fsm-scroll]`); e.scrollLeft = e.scrollWidth; }, diag);
        const atRight = await geo();
        if (atRight && atRight.right > atRight.pane.r + 1) note(design, where, `wide diagram (1:1): the rightmost state is unreachable (${atRight.right} > ${atRight.pane.r})`);
        if (SHOTS) await page.screenshot({ path: `${SHOTS}/${design}__diagram-wide-1to1.png` }).catch(() => {});
        // Zoom and pan, as on the schematic: the wheel changes the scale at the
        // pointer, a drag scrolls (and opens no source), f fits again.
        const scale = () => page.evaluate((sel) => parseFloat(document.querySelector(`${sel} svg[data-fsm-scale]`)?.getAttribute('data-fsm-scale') || 'NaN'), diag);
        const pane = await page.evaluate((sel) => { const r = document.querySelector(`${sel} [data-fsm-scroll]`).getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }, diag);
        const s0 = await scale();
        await page.mouse.move(pane.x, pane.y);
        await page.mouse.wheel(0, -200);
        await page.waitForTimeout(150);
        const s1 = await scale();
        if (!(s1 > s0)) note(design, where, `wheel up did not zoom the diagram in (${s0} -> ${s1})`);
        const sc0 = await page.evaluate((sel) => { const e = document.querySelector(`${sel} [data-fsm-scroll]`); return [e.scrollLeft, e.scrollTop]; }, diag);
        await page.mouse.down();
        for (let i = 1; i <= 6; i++) await page.mouse.move(pane.x - 15 * i, pane.y - 8 * i);
        await page.mouse.up();
        await page.waitForTimeout(100);
        const sc1 = await page.evaluate((sel) => { const e = document.querySelector(`${sel} [data-fsm-scroll]`); return [e.scrollLeft, e.scrollTop]; }, diag);
        if (sc1[0] <= sc0[0]) note(design, where, `a drag did not pan the diagram (${sc0} -> ${sc1})`);
        if (!(await page.locator(diag).count())) note(design, where, 'a drag on the diagram closed it');
        await page.keyboard.press('f');
        await page.waitForTimeout(150);
        const s2 = await scale();
        if (!(s2 < s1)) note(design, where, `f did not fit the diagram again (${s1} -> ${s2})`);
      }
      drain(`${where} (wide)`);

      // A reload that drops the block closes the diagram.
      // The block leaves the baked layouts too, as an export would leave it: an
      // optimized bake is drawn as is, with no re-layout (#335).
      await serve((d) => {
        // ...and its wires, as a design without the block has none to it
        const onBlock = (e) => e === id || e.startsWith(`${id}.`);
        for (const m of d.modules) {
          m.logic = (m.logic || []).filter((n) => n.id !== id);
          m.wires = (m.wires || []).filter((w) => !onBlock(w.from) && !onBlock(w.to));
        }
        for (const l of Object.values(d.layouts || {})) {
          const gone = new Set((l.nodes.find((n) => n.id === id)?.ports || []).map((p) => p.path));
          l.nodes = l.nodes.filter((n) => n.id !== id);
          l.edges = l.edges.filter((e) => !gone.has(e.from) && !gone.has(e.to));
        }
      });
      const closed = await page.waitForFunction(() => !document.querySelector('[data-fsm-diagram]') && !document.querySelector('[data-fsm]'),
        null, { timeout: 10000 }).then(() => true).catch(() => false);
      if (!closed) note(design, where, 'reload dropping the block: the diagram stayed open');
      drain(`${where} (reload dropping the block)`);
      // Restore the served design for the later steps: the poller sees the
      // real file's stamp again and reloads it. Wait for that reload, or it
      // (and its re-layout) lands in the middle of a later check.
      const loadsBefore = await page.evaluate(() => (window.__socleLoads || []).length);
      await page.unroute('**/design.json');
      await page.waitForFunction((n) => (window.__socleLoads || []).length > n, loadsBefore, { timeout: 10000 })
        .catch(() => note(design, where, 'restore: the served design was never reloaded'));
      await page.waitForSelector(`[data-fsm="${id}"]`, { timeout: 10000 }).catch(() => note(design, where, 'restore: the block never came back'));
      await settle();
    }
    drain(where);
  }

  // Back to the top module (richest view: instances, wires, interface pins).
  if (paths.length) {
    await page.locator(`[data-hier-path="${paths[0].path}"]`).first().click().catch(() => {});
    await page.waitForTimeout(150);
  }

  // Minimap (perf): zoomed in, it shows; panning moves only its viewport rect
  // (its node rects are built once per geometry, never per pan), and a click or
  // a drag in it moves the main view there.
  await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
  {
    const where = 'minimap';
    const zoomIn = page.locator('button[title="Zoom in"]');
    for (let i = 0; i < 5 && await page.locator('[data-minimap]').count() === 0; i++) {
      await zoomIn.click().catch((e) => note(design, where, e.message));
      await page.waitForTimeout(60);
    }
    if (await page.locator('[data-minimap]').count()) {
      minimapDesigns.add(design);
      await page.evaluate(() => {
        const mm = document.querySelector('[data-minimap]');
        const S = window.__mmSmoke = { el: mm, muts: 0, swaps: 0 };
        S.obs = new MutationObserver((recs) => { for (const r of recs) if (r.type === 'childList') S.muts++; });
        S.obs.observe(mm, { childList: true, subtree: true });
        // (Diagnostics for a rebuild: how often the minimap left its parent,
        // and the viewport size around the pan.)
        const vpEl = document.querySelector('[data-content]').parentElement;
        S.vp = () => `${vpEl.clientWidth}x${vpEl.clientHeight}`;
        S.vp0 = S.vp();
        S.pobs = new MutationObserver((recs) => {
          for (const r of recs) for (const n of r.removedNodes) if (n === S.el) S.swaps++;
        });
        S.pobs.observe(mm.parentElement, { childList: true });
        // A layout landing (data-layout-ready) or a content rebuild during the pan.
        const ready = document.querySelector('[data-layout-ready]');
        const content = document.querySelector('[data-content]');
        S.ready0 = ready && ready.getAttribute('data-layout-ready');
        S.readyFlips = 0; S.contentMuts = 0;
        S.robs = new MutationObserver(() => S.readyFlips++);
        if (ready) S.robs.observe(ready, { attributes: true, attributeFilter: ['data-layout-ready'] });
        S.cobs = new MutationObserver(() => S.contentMuts++);
        if (content) S.cobs.observe(content, { childList: true });
      });
      const rectOf = () => page.evaluate(() => {
        const r = document.querySelector('[data-minimap-viewport]');
        return r ? r.style.transform : null;
      });
      const rect0 = await rectOf();
      const vp = await page.locator('[data-content]').first().evaluate((el) => {
        const r = el.parentElement.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      });
      await page.mouse.move(vp.x, vp.y);
      await page.mouse.down({ button: 'middle' });
      for (let i = 1; i <= 10; i++) { await page.mouse.move(vp.x - 6 * i, vp.y - 4 * i); await page.waitForTimeout(16); }
      await page.mouse.up({ button: 'middle' });
      await page.waitForTimeout(80);
      const r = await page.evaluate(() => {
        const S = window.__mmSmoke; S.obs.disconnect(); S.pobs.disconnect(); S.robs.disconnect(); S.cobs.disconnect();
        return {
          same: S.el.isConnected && document.querySelector('[data-minimap]') === S.el, muts: S.muts,
          swaps: S.swaps, shown: !!document.querySelector('[data-minimap]'), vp: `${S.vp0} -> ${S.vp()}`,
          ready: `${S.ready0}, ${S.readyFlips} flips`, contentMuts: S.contentMuts,
        };
      });
      const rect1 = await rectOf();
      if (!r.same) note(design, where, `panning rebuilt the minimap element (left its parent ${r.swaps}x, shown after: ${r.shown}, viewport ${r.vp}, layout-ready ${r.ready}, content rebuilds ${r.contentMuts}; ${await loadLog()})`);
      else if (r.muts) note(design, where, `panning mutated the minimap's children (${r.muts} childList records)`);
      if (rect0 === rect1) note(design, where, `panning did not move the minimap viewport rect (${rect0})`);
      // A click in the minimap, then a drag in it, each move the main view.
      const mmBox = await page.locator('[data-minimap]').first().boundingBox();
      if (mmBox) {
        const v0 = await readView();
        await page.mouse.click(mmBox.x + mmBox.width * 0.85, mmBox.y + mmBox.height * 0.85);
        await page.waitForTimeout(60);
        const v1 = await readView();
        if (!v0 || !v1 || (Math.abs(v1.x - v0.x) < 1 && Math.abs(v1.y - v0.y) < 1))
          note(design, where, `a click in the minimap did not move the view (${JSON.stringify(v0)} -> ${JSON.stringify(v1)})`);
        await page.mouse.move(mmBox.x + mmBox.width * 0.8, mmBox.y + mmBox.height * 0.8);
        await page.mouse.down();
        for (let i = 1; i <= 6; i++) { await page.mouse.move(mmBox.x + mmBox.width * (0.8 - 0.1 * i), mmBox.y + mmBox.height * (0.8 - 0.1 * i)); await page.waitForTimeout(16); }
        await page.mouse.up();
        await page.waitForTimeout(60);
        const v2 = await readView();
        if (!v1 || !v2 || (Math.abs(v2.x - v1.x) < 1 && Math.abs(v2.y - v1.y) < 1))
          note(design, where, `a drag in the minimap did not move the view (${JSON.stringify(v1)} -> ${JSON.stringify(v2)})`);
      }
    }
    // Leave a user-set view (1:1), as the drill back left it: an automatic fit
    // here would refit on the bundle expansions below and trip the #207 check.
    await page.locator('button[title="Reset to 100%"]').first().click().catch(() => {});
    await page.waitForTimeout(60);
    drain(where);
  }

  // A point on the element's visible part where it is the topmost hit (the
  // highlight and gesture checks below).
  const pointOn = (sel) => page.evaluate((sel) => {
    const hits = (el, x, y) => { const h = document.elementFromPoint(x, y); return h && (h === el || el.contains(h)); };
    const vp = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
    const inside = (x, y) => x > vp.left + 4 && y > vp.top + 4 && x < vp.right - 4 && y < vp.bottom - 4;
    for (const el of document.querySelectorAll(sel)) {
      if (el instanceof SVGPathElement) {
        const L = el.getTotalLength(); if (!L) continue;
        const m = el.getScreenCTM();
        for (const f of [0.5, 0.3, 0.7, 0.15, 0.85]) {
          const q = el.getPointAtLength(L * f);
          const x = m.a * q.x + m.c * q.y + m.e, y = m.b * q.x + m.d * q.y + m.f;
          if (inside(x, y) && hits(el, x, y)) return { x, y };
        }
      } else {
        // The centre of its part inside the viewport.
        const r = el.getBoundingClientRect();
        const l = Math.max(r.left, vp.left + 5), t = Math.max(r.top, vp.top + 5);
        const rr = Math.min(r.right, vp.right - 5), b = Math.min(r.bottom, vp.bottom - 5);
        const x = (l + rr) / 2, y = (t + b) / 2;
        if (rr > l && b > t && inside(x, y) && hits(el, x, y)) return { x, y };
      }
    }
    return null;
  }, sel);

  // The tooltip card when shown (null when hidden): kind, fields, whether it
  // sits inside the content layer (it must not).
  const tipRead = () => page.evaluate(() => {
    const t = document.querySelector('[data-tooltip]');
    if (!t || getComputedStyle(t).display === 'none') return null;
    const f = (k) => t.querySelector(`[data-tip-${k}]`)?.textContent || '';
    return { kind: t.getAttribute('data-tip-kind'), name: f('name'), type: f('type'), detail: f('detail'),
             actions: f('actions'), text: t.textContent, inContent: !!t.closest('[data-content]') };
  });
  const tipShown = async () => !!(await tipRead());

  // Highlights are styling, never a rebuild (perf): resting the pointer on a
  // wire, a box or a pin changes nothing in the schematic after it entered (a
  // re-render under the pointer re-fired mouseenter and looped every frame),
  // and a selection only toggles classes (no child list changes). Real pointer
  // moves, so the browser's own enter/leave events run.
  {
    const where = 'highlight rest/select';
    const watch = () => page.evaluate(() => {
      const S = window.__hlSmoke = { child: 0, attrs: 0 };
      S.obs = new MutationObserver((rs) => { for (const r of rs) { if (r.type === 'childList') S.child++; else S.attrs++; } });
      S.obs.observe(document.querySelector('[data-content]'), { childList: true, subtree: true, attributes: true });
    });
    const unwatch = () => page.evaluate(() => { const S = window.__hlSmoke; S.obs.disconnect(); return { child: S.child, attrs: S.attrs }; });
    const away = async () => { const vp = await page.locator('[data-content]').first().evaluate((el) => { const r = el.parentElement.getBoundingClientRect(); return { x: r.right - 8, y: r.bottom - 8 }; }); await page.mouse.move(vp.x, vp.y); await page.waitForTimeout(80); };
    // A wire and a box light on hover (`.hov`, the box's `.ohov` outline). The
    // tooltip card appears inside the watched second (600 ms after the pointer
    // stopped) and must change nothing in the content: it is checked shown at
    // its end, so the guard provably spans it.
    const targets = [
      ['wire', 'path[data-wire]'],
      ['box', '[data-box-inst]'],
      ['block', '[data-block]'],
      ['pin', '[data-pin]'],
    ];
    // What a click selects: a box's header, or a register / op symbol.
    const selectTargets = [targets[0], ['box', '[data-box-inst] > div:first-child, [data-logic-node]']];
    for (const [kind, sel] of targets) {
      const pt = await pointOn(sel);
      // Not every top view has a top-level pin (scattered bundles are all rows):
      // the pin check must run on at least one design (summary below).
      // Logic blocks exist only in captured views.
      if (!pt) { if (kind !== 'pin' && kind !== 'block') note(design, where, `no visible ${kind} to rest on (${sel})`); continue; }
      if (kind === 'pin') pinRestDesigns.add(design);
      if (kind === 'block') console.log(`[${design}] rest on a logic block`);
      await page.mouse.move(pt.x, pt.y);
      await page.waitForTimeout(200);
      // The pointer really is on the wire: its hover lit (else the rest below
      // would pass on a wire that never entered).
      if (kind === 'wire' && !(await page.evaluate(() => document.querySelectorAll('[data-content] .scl-w.hov').length)))
        note(design, where, 'resting on a wire did not light it (no .scl-w.hov)');
      if (kind === 'box' && !(await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y)?.closest('[data-box-inst]')?.classList.contains('ohov'), [pt.x, pt.y])))
        note(design, where, 'resting on a box did not outline it (no .ohov)');
      await watch();
      await page.waitForTimeout(1000);
      const m = await unwatch();
      if (m.child || m.attrs) note(design, where, `resting on a ${kind} for 1 s mutated the schematic (${m.child} childList, ${m.attrs} attribute records)`);
      if (!(await tipShown())) note(design, where, `resting on a ${kind} for 1.2 s showed no tooltip card`);
      await away();
    }
    // Select (and deselect) a wire, then a box: classes only.
    for (const [kind, sel] of selectTargets) {
      const pt = await pointOn(sel);
      if (!pt) { note(design, where, `no visible ${kind} to select (${sel})`); continue; }
      await watch();
      await page.mouse.click(pt.x, pt.y);
      await page.waitForTimeout(150);
      const lit = await page.evaluate(() => document.querySelectorAll('[data-content] .hl, [data-content] .sel').length);
      await page.mouse.click(pt.x, pt.y);
      await page.waitForTimeout(150);
      const m = await unwatch();
      if (m.child) note(design, where, `selecting a ${kind} rebuilt the schematic (${m.child} childList records)`);
      else if (!lit) note(design, where, `selecting a ${kind} lit nothing`);
      await away();
    }
    // The indexed highlight matching agrees with a full scan on every key of
    // this view (wire ends, pins, boxes and their prefixes).
    const chk = await page.evaluate(() => window.__socleHighlightCheck ? window.__socleHighlightCheck() : null);
    if (!chk) note(design, where, 'no window.__socleHighlightCheck hook');
    else if (chk.bad.length) note(design, where, `indexed matches differ from a scan for ${chk.bad.length}/${chk.keys} keys: ${chk.bad.slice(0, 5).join(', ')}`);
    else if (!chk.keys) note(design, where, 'the highlight self-check saw no keys');
    else console.log(`[${design}] highlight self-check: ${chk.keys} keys agree`);
    drain(where);
  }

  // Hover feedback: a hovered box gets an outline and its connected wires are
  // drawn thicker in the same colour, by classes only (no child list change);
  // after 600 ms a tooltip card (outside the content layer) names what is under
  // the pointer and what the mouse does there; leaving, a press that drags and
  // the wheel hide it, and it never shows during a drag; the cursor is
  // `zoom-in` over a block a double-click opens.
  {
    const where = 'hover feedback';
    // Off every target: the bottom-right corner, or the nearest empty spot to it
    // (a wire may run through the corner).
    const away = async () => {
      const vp = await page.locator('[data-content]').first().evaluate((el) => {
        const vpEl = el.parentElement, r = vpEl.getBoundingClientRect();
        for (let d = 8; d < Math.min(r.width, r.height) / 2; d += 10)
          for (const [x, y] of [[r.right - d, r.bottom - 8], [r.right - 8, r.bottom - d], [r.right - d, r.bottom - d]]) {
            const h = document.elementFromPoint(x, y);
            if (h && vpEl.contains(h) && !h.closest('[data-pan-block], .scl-w, path, button')) return { x, y };
          }
        return { x: r.right - 8, y: r.bottom - 8 };
      });
      await page.mouse.move(vp.x, vp.y); await page.waitForTimeout(80);
    };
    const dashes = /[\u2013\u2014]/;
    const checkTip = (t, what) => {
      if (!t) { note(design, where, `no tooltip card after resting on ${what}`); return false; }
      if (t.inContent) note(design, where, `the tooltip card is inside the content layer (${what})`);
      if (!t.name) note(design, where, `the tooltip card for ${what} has no name: ${JSON.stringify(t)}`);
      if (dashes.test(t.text)) note(design, where, `the tooltip card for ${what} has an en or em dash: ${t.text}`);
      return true;
    };
    // The box (or boundary stub) id a wire end belongs to: the path itself, its
    // head, or the head of a logic node's synthetic pin (`x~q`, `x~in0`).
    const lit = (pt) => page.evaluate(([x, y]) => {
      const box = document.elementFromPoint(x, y)?.closest('[data-box-inst], [data-boundary-stub]');
      if (!box) return null;
      const id = box.getAttribute('data-box-inst') ?? box.getAttribute('data-boundary-stub');
      const ids = new Set(Array.from(document.querySelectorAll('[data-content] [data-box-inst], [data-content] [data-boundary-stub]'))
        .map((e) => e.getAttribute('data-box-inst') ?? e.getAttribute('data-boundary-stub')));
      const owner = (p) => { if (ids.has(p)) return p; const h = p.split('.')[0]; return ids.has(h) || h.startsWith('const$') ? h : h.split('~')[0]; };
      const wires = Array.from(document.querySelectorAll('[data-content] .scl-w'));
      const want = wires.filter((w) => owner(w.getAttribute('data-from') || '') === id || owner(w.getAttribute('data-to') || '') === id);
      const got = wires.filter((w) => w.classList.contains('ohov'));
      const missing = want.filter((w) => !got.includes(w)).length, extra = got.filter((w) => !want.includes(w)).length;
      const sw = (w) => parseFloat(getComputedStyle(w.querySelectorAll('path')[1]).strokeWidth);
      const other = wires.find((w) => !w.classList.contains('ohov') && !w.classList.contains('fl'));
      // Thicker than an unlit wire of its own kind: a bundle wire is thick at rest (#334).
      const restOf = (w) => wires.find((x) => !x.classList.contains('ohov') && !x.classList.contains('fl') && x.classList.contains('bw') === w.classList.contains('bw'));
      const thick = want.filter((w) => !w.classList.contains('fl')).every((w) => { const o = restOf(w); return !o || sw(w) > sw(o); });
      const colour = getComputedStyle(box).outlineColor;
      const same = got.every((w) => getComputedStyle(w.querySelectorAll('path')[1]).stroke === colour);
      const label = box.querySelector(':scope > div:first-child')?.textContent || id;
      return { id, label, outlined: box.classList.contains('ohov'), outline: getComputedStyle(box).outlineStyle,
               want: want.length, missing, extra, thick, same, colour, boxesLit: document.querySelectorAll('[data-content] .ohov:not(.scl-w)').length,
               drillable: box.getAttribute('data-box-drillable') === '1' && !box.hasAttribute('data-fold-group'),
               folded: box.hasAttribute('data-fold-group') };
    }, [pt.x, pt.y]);
    const watchChild = () => page.evaluate(() => {
      const S = window.__hfSmoke = { child: 0 };
      S.obs = new MutationObserver((rs) => { for (const r of rs) if (r.type === 'childList') S.child++; });
      S.obs.observe(document.querySelector('[data-content]'), { childList: true, subtree: true });
    });
    const unwatchChild = () => page.evaluate(() => { const S = window.__hfSmoke; S.obs.disconnect(); return S.child; });

    // The hint bar: outside the content layer, one line that follows the
    // target under the pointer.
    const hintRead = () => page.evaluate(() => {
      const b = document.querySelector('[data-hint-bar]');
      const t = b && b.querySelector('[data-hint-text]');
      const name = b && b.querySelector('[data-hint-name]');
      return t ? { text: (name && name.textContent ? name.textContent + ' ' : '') + t.textContent, inContent: !!b.closest('[data-content]') } : null;
    });
    const hintBg = { text: '' };

    // A box: prefer an instance (they carry data-box-drillable).
    const boxSel = (await pointOn('[data-box-drillable]')) ? '[data-box-drillable]' : '[data-box-inst]';
    const pt = await pointOn(boxSel);
    if (!pt) note(design, where, `no visible box to hover (${boxSel})`);
    else {
      await away();
      const hb0 = await hintRead();
      if (!hb0) note(design, where, 'no hint bar text ([data-hint-bar] [data-hint-text])');
      else {
        hintBg.text = hb0.text;
        if (hb0.inContent) note(design, where, 'the hint bar is inside the content layer');
        if (!/Wheel: zoom/.test(hb0.text)) note(design, where, `the hint off any target does not say what the wheel does: ${hb0.text}`);
      }
      await watchChild();
      await page.mouse.move(pt.x, pt.y);
      await page.waitForTimeout(200);
      const h = await lit(pt);
      const hb1 = await hintRead();
      if (hb0 && hb1) {
        if (hb1.text === hb0.text) note(design, where, `the hint did not change on box ${h && h.id}: ${hb1.text}`);
        if (h && h.drillable && !/Double-click: open/.test(hb1.text)) note(design, where, `the hint on drillable box ${h.id} does not say how to open it: ${hb1.text}`);
        if (/[\u2013\u2014]/.test(hb0.text + hb1.text)) note(design, where, `the hint has an en or em dash: ${hb1.text}`);
      }
      if (!h) note(design, where, 'no box under the pointer');
      else {
        if (!h.outlined || h.outline === 'none') note(design, where, `hovering box ${h.id} did not outline it (${JSON.stringify(h)})`);
        if (h.boxesLit !== 1) note(design, where, `hovering box ${h.id} outlined ${h.boxesLit} boxes`);
        if (h.missing || h.extra) note(design, where, `hovering box ${h.id}: ${h.missing} of its ${h.want} wires not lit, ${h.extra} other wires lit`);
        if (!h.thick) note(design, where, `hovering box ${h.id} did not draw its wires thicker`);
        if (!h.same) note(design, where, `hovering box ${h.id} drew its wires in another colour than its outline (${h.colour})`);
        if (h.want) hoverWireDesigns.add(design);
      }
      const child = await unwatchChild();
      if (child) note(design, where, `hovering a box changed the content's children (${child} childList records)`);
      await page.waitForTimeout(800);
      const t = await tipRead();
      if (h && checkTip(t, `box ${h.id}`)) {
        if (t.kind !== 'box') note(design, where, `the card on box ${h.id} is a ${t.kind} card`);
        if (!t.name.startsWith(h.id) && !h.label.includes(t.name)) note(design, where, `the card on box ${h.id} (${h.label}) names ${t.name}`);
        if (!t.type) note(design, where, `the card on box ${h.id} has no type`);
        const opens = /Double-click: open/.test(t.actions);
        if (opens !== h.drillable) note(design, where, `the card on box ${h.id} (drillable ${h.drillable}) says: ${t.actions}`);
        if (h.folded && !/Double-click: unfold/.test(t.actions)) note(design, where, `the card on folded box ${h.id} says: ${t.actions}`);
        if (!/Drag: pan/.test(t.actions)) note(design, where, `the card on box ${h.id} does not mention the drag: ${t.actions}`);
        if (/instance/.test(t.detail)) hoverCountDesigns.add(design);
        const hb = await hintRead();
        if (hb && !hb.text.includes(t.actions)) note(design, where, `the hint on box ${h.id} (${hb.text}) does not give the card's actions (${t.actions})`);
        else if (hb) hintDesigns.add(design);
      }
      // Leaving hides the card and the outline.
      await away();
      if (await tipShown()) note(design, where, 'the card stayed after the pointer left the box');
      if (await page.evaluate(() => document.querySelectorAll('[data-content] .ohov').length)) note(design, where, 'the outline stayed after the pointer left the box');
      // A press that drags hides the card at once, and nothing shows or lights during the drag.
      await page.mouse.move(pt.x, pt.y);
      await page.waitForTimeout(900);
      if (!await tipShown()) note(design, where, 'no card before the drag check');
      await page.mouse.down();
      await page.mouse.move(pt.x + 10, pt.y + 8);
      await page.waitForTimeout(30);
      if (await tipShown()) note(design, where, 'a drag did not hide the card');
      for (let i = 1; i <= 4; i++) { await page.mouse.move(pt.x + 10 + 3 * i, pt.y + 8 + 2 * i); await page.waitForTimeout(40); }
      await page.waitForTimeout(800);
      if (await tipShown()) note(design, where, 'a card showed during a drag');
      if (await page.evaluate(() => document.querySelectorAll('[data-content] .ohov').length)) note(design, where, 'something is outlined during a drag');
      await page.mouse.up();
      await page.waitForTimeout(60);
      // The wheel hides it too.
      const pt2 = await pointOn(boxSel);
      if (pt2) {
        await page.mouse.move(pt2.x, pt2.y);
        await page.waitForTimeout(900);
        if (await tipShown()) {
          await page.mouse.wheel(0, 40);
          await page.waitForTimeout(60);
          if (await tipShown()) note(design, where, 'the wheel did not hide the card');
        } else note(design, where, 'no card before the wheel check');
      }
      await away();
      await page.locator('button[title="Reset to 100%"]').first().click().catch(() => {});
      await page.waitForTimeout(60);
    }

    // A wire: the card names both ends.
    {
      const pw = await pointOn('path[data-wire]');
      if (pw) {
        const ends = await page.evaluate(([x, y]) => { const p = document.elementFromPoint(x, y).closest('path[data-wire]'); return [p.getAttribute('data-wire'), p.getAttribute('data-wire-to')]; }, [pw.x, pw.y]);
        await page.mouse.move(pw.x, pw.y);
        await page.waitForTimeout(900);
        const t = await tipRead();
        if (checkTip(t, `wire ${ends[0]}`)) {
          if (t.kind !== 'wire') note(design, where, `the card on wire ${ends[0]} is a ${t.kind} card`);
          // As the card writes a logic pin: `x~in0` is `x.in0`, `r~q` is `r`.
          const pretty = (p) => p.replace(/^([^.]*?)~in(\d+)(?=\.|$)/, '$1.in$2').replace(/^([^.]*?)~q(?=\.|$)/, '$1');
          if (!ends[1] || !t.detail.includes(pretty(ends[0])) || !t.detail.includes(pretty(ends[1]))) note(design, where, `the card on wire ${ends.join(' / ')} does not name both ends: ${t.text}`);
        }
        const hb = await hintRead();
        if (hb && (!/^Wire/.test(hb.text) || !/Click: select and follow the net/.test(hb.text) || hb.text === hintBg.text)) note(design, where, `the hint on wire ${ends[0]} is: ${hb.text}`);
        await away();
      }
    }
    // A pin: direction (and width when known).
    {
      const pp = await pointOn('[data-pin]');
      if (pp) {
        await page.mouse.move(pp.x, pp.y);
        await page.waitForTimeout(900);
        const t = await tipRead();
        if (checkTip(t, 'a pin')) {
          if (t.kind !== 'pin') note(design, where, `the card on a pin is a ${t.kind} card`);
          if (!/^(input|output|inout|master|slave)/.test(t.type)) note(design, where, `the card on a pin gives no direction: ${t.text}`);
        }
        await away();
      }
    }
    // A logic block or an FSM (captured views): what it is.
    for (const sel of ['[data-block]', '[data-fsm]']) {
      const pb = await pointOn(sel);
      if (!pb) continue;
      await page.mouse.move(pb.x, pb.y);
      await page.waitForTimeout(900);
      const t = await tipRead();
      if (checkTip(t, sel)) {
        if (!t.type) note(design, where, `the card on ${sel} does not say what it is: ${t.text}`);
        console.log(`[${design}] hover card on ${sel}: ${t.name} | ${t.type} | ${t.actions}`);
      }
      await away();
    }

    // The cursor says what a click does: zoom-in on a block a double-click opens
    // (its title too), pointer on the title of one it does not and on what a
    // click anywhere selects (a register or op symbol, a boundary stub).
    {
      const cur = (p) => page.evaluate(([x, y]) => { const h = document.elementFromPoint(x, y); return h ? getComputedStyle(h).cursor : null; }, [p.x, p.y]);
      const pd = await pointOn('[data-box-drillable="1"] > div:first-child');
      if (pd) {
        cursorZoomDesigns.add(design);
        const c = await cur(pd);
        if (c !== 'zoom-in') note(design, where, `the cursor on a drillable block's title is ${c}, not zoom-in`);
        const pb = await pointOn('[data-box-drillable="1"]');
        const onRow = pb && await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y).closest('[data-bundle-toggle], [data-pin], [data-fold-toggle]'), [pb.x, pb.y]);
        if (pb && !onRow && await cur(pb) !== 'zoom-in') note(design, where, `the cursor on a drillable block is ${await cur(pb)}, not zoom-in`);
      }
      const pn = await pointOn('[data-box-drillable="0"]:not([data-fold-group]) > div:first-child');
      if (pn && await cur(pn) !== 'pointer') note(design, where, `the cursor on a leaf block's title is ${await cur(pn)}, not pointer`);
      // A register or op symbol, and a boundary stub, select on a click anywhere on them.
      for (const [what, sel] of [['logic symbol', '[data-logic-node]'], ['boundary stub', '[data-boundary-stub]']]) {
        const ps = await pointOn(sel);
        if (!ps) continue;
        cursorPointerDesigns.add(design);
        const c = await cur(ps);
        if (c !== 'pointer') note(design, where, `the cursor on a ${what} is ${c}, not pointer`);
      }
    }

    // The tooltip content function on every box, wire and pin of this view.
    const tc = await page.evaluate(() => window.__socleTooltipCheck ? window.__socleTooltipCheck() : null);
    if (!tc) note(design, where, 'no window.__socleTooltipCheck hook');
    else if (tc.bad.length) note(design, where, `tooltip content is empty or has a dash for ${tc.bad.length}/${tc.n} targets: ${tc.bad.slice(0, 5).join(', ')}`);
    else console.log(`[${design}] tooltip content: ${tc.n} targets`);
    drain(where);
  }

  // Gestures: a drag from anywhere (a box, a wire, a title) pans the view once
  // past 4 px, with the `grabbing` cursor, and selects, opens or expands
  // nothing (no class toggles mid-drag either); a press that moves less is
  // still a click (select, bundle toggle, double-click drill); a middle-button
  // drag pans at once; the plain wheel zooms, Shift + wheel scrolls
  // horizontally and Ctrl + wheel vertically (trackpads through the classify
  // hook); the arrow, +, -, f and 0 keys act only with the focus on the body
  // or the schematic.
  await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
  {
    const where = 'gestures';
    // What a click selects: an instance's title, or a logic symbol (a view
    // with no instances, like GCD's).
    const titleSel = '[data-box-inst] > div:first-child, [data-logic-node]';
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(150);
    };
    const crumbs = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > '));
    const isSelected = () => page.evaluate(() => document.querySelector('[data-content]').classList.contains('hl-sel'));
    const vpRect = () => page.locator('[data-content]').first().evaluate((el) => {
      const r = el.parentElement.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height };
    });
    // A background point: in the viewport, on no element of the schematic.
    const bgPoint = () => page.evaluate(() => {
      const vpEl = document.querySelector('[data-content]').parentElement; const vp = vpEl.getBoundingClientRect();
      for (let fy = 0.1; fy < 0.95; fy += 0.08) for (let fx = 0.05; fx < 0.95; fx += 0.05) {
        const x = vp.left + vp.width * fx, y = vp.top + vp.height * fy;
        const h = document.elementFromPoint(x, y);
        if (h && vpEl.contains(h) && !h.closest('[data-pan-block]')) return { x, y };
      }
      return null;
    });
    const jitterClick = async (pt, jx, jy) => {
      await page.mouse.move(pt.x, pt.y);
      await page.mouse.down();
      await page.mouse.move(pt.x + jx, pt.y + jy);
      await page.mouse.up();
      await page.waitForTimeout(120);
    };
    const clearSel = async () => {
      if (!await isSelected()) return;
      const b = await bgPoint();
      if (b) await jitterClick(b, 0, 0);
    };
    const hovCount = () => page.evaluate(() => document.querySelectorAll('[data-content] .scl-w.hov').length);
    // Records under the content layer, except its own (the transform moves every frame).
    const watchDrag = () => page.evaluate(() => {
      const content = document.querySelector('[data-content]');
      const S = window.__gSmoke = { child: 0, attrs: 0 };
      S.obs = new MutationObserver((rs) => { for (const r of rs) { if (r.type === 'childList') S.child++; else if (r.target !== content) S.attrs++; } });
      S.obs.observe(content, { childList: true, subtree: true, attributes: true });
    });
    const unwatchDrag = () => page.evaluate(() => { const S = window.__gSmoke; S.obs.disconnect(); return { child: S.child, attrs: S.attrs }; });

    // A drag of (40, 30) px in 5 steps from each kind of element.
    for (const [kind, sel] of [['box', '[data-box-inst]'], ['wire', 'path[data-wire]'], ['title', titleSel]]) {
      await clearSel();
      const pt = await pointOn(sel);
      if (!pt) { note(design, where, `no visible ${kind} to drag from (${sel})`); continue; }
      const v0 = await readView(); const c0 = await crumbs();
      await page.mouse.move(pt.x, pt.y);
      await page.waitForTimeout(60);
      await page.mouse.down();
      let cursor = null;
      for (let i = 1; i <= 5; i++) {
        const x = pt.x + 8 * i, y = pt.y + 6 * i;
        await page.mouse.move(x, y);
        await page.waitForTimeout(20);
        if (i === 2) await watchDrag(); // the pan has started: nothing toggles from here on
        // The cursor over the schematic: a drag that leaves it (onto the hint
        // bar below a small design) is not covered by the pan overlay.
        if (i === 3) cursor = await page.evaluate(([x, y]) => {
          const vp = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
          if (x < vp.left || x > vp.right || y < vp.top || y > vp.bottom) return 'grabbing';
          const h = document.elementFromPoint(x, y); return h ? getComputedStyle(h).cursor : null;
        }, [x, y]);
      }
      const m = await unwatchDrag();
      await page.mouse.up();
      await page.waitForTimeout(100);
      const v1 = await readView();
      if (!v0 || !v1 || Math.abs(v1.x - v0.x - 40) > 1.5 || Math.abs(v1.y - v0.y - 30) > 1.5 || v1.z !== v0.z)
        note(design, where, `a drag of (40, 30) from a ${kind} did not pan the view by it: ${JSON.stringify(v0)} -> ${JSON.stringify(v1)}`);
      if (await isSelected()) note(design, where, `a drag from a ${kind} selected something`);
      const c1 = await crumbs();
      if (c1 !== c0 || await page.locator('[data-fsm-diagram]').count()) note(design, where, `a drag from a ${kind} opened something (${c0} -> ${c1})`);
      if (m.child) note(design, where, `a drag from a ${kind} rebuilt the schematic (${m.child} childList records: an expand, a fold or a drill)`);
      if (m.attrs) note(design, where, `a drag from a ${kind} toggled classes or attributes mid-drag (${m.attrs} records)`);
      if (cursor !== 'grabbing') note(design, where, `the cursor mid-drag from a ${kind} is ${cursor}, not grabbing`);
      const txt = await page.evaluate(() => String(window.getSelection()));
      if (txt) note(design, where, `a drag from a ${kind} selected text (${JSON.stringify(txt.slice(0, 40))})`);
      // The hover follows the pointer again after the drag: ending off a wire leaves none lit.
      const end = { x: pt.x + 41, y: pt.y + 31 };
      await page.mouse.move(end.x, end.y);
      await page.waitForTimeout(80);
      const onWire = await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y)?.closest('path[data-wire]'), [end.x, end.y]);
      if (!onWire && await hovCount()) note(design, where, `a drag from a ${kind} ending off a wire left a wire hovered`);
      // A drag from a wire ends on it (the content follows the pointer): its hover comes back.
      if (kind === 'wire' && onWire) {
        if (!await hovCount()) note(design, where, 'a drag from a wire ending on it left no wire hovered after a move');
        else console.log(`[${design}] gestures: the wire hover is back after a drag from it`);
      }
    }
    // A click with no press before it (a keyboard click) right after a drag
    // is a click: it selects (#14).
    {
      await clearSel();
      // the drag last: a press after it (as clearSel's) would hide the bug
      const b = await bgPoint();
      if (b) {
        await page.mouse.move(b.x, b.y);
        await page.mouse.down();
        await page.mouse.move(b.x + 30, b.y + 20, { steps: 4 });
        await page.mouse.up();
        await page.waitForTimeout(100);
      } else note(design, where, 'no background point to drag from before the keyboard click');
      const clicked = await page.evaluate((sel) => {
        const el = document.querySelector(sel);
        if (!el) return false;
        el.click();
        return true;
      }, titleSel);
      await page.waitForTimeout(120);
      if (!clicked) note(design, where, `no title to click after the drags (${titleSel})`);
      else if (!await isSelected()) note(design, where, 'a click with no press after a drag selected nothing (the drag swallowed it)');
      await clearSel();
    }

    // A press that moves less than 4 px is a click: it selects, a second one
    // deselects, and one on the background clears the selection.
    {
      await clearSel();
      const pt = await pointOn(titleSel);
      if (!pt) note(design, where, 'no visible title to click');
      else {
        for (const [jx, jy] of [[3, 0], [2, 2]]) {
          await jitterClick(pt, jx, jy);
          if (!await isSelected()) note(design, where, `a click with (${jx}, ${jy}) px of jitter on a title did not select`);
          await jitterClick(pt, jx, jy);
          if (await isSelected()) note(design, where, `a second click with (${jx}, ${jy}) px of jitter on a title did not deselect`);
        }
        await jitterClick(pt, 1, 1);
        const b = await bgPoint();
        if (b) {
          await jitterClick(b, 2, 1);
          if (await isSelected()) note(design, where, 'a click with jitter on the background did not clear the selection');
        }
      }
    }

    // A jittered click on a bundle toggle still toggles it (and back).
    {
      const pt = await pointOn('[data-bundle-toggle]');
      if (pt) {
        gestureToggleDesigns.add(design);
        const key = await page.evaluate(([x, y]) => document.elementFromPoint(x, y).closest('[data-bundle-toggle]').getAttribute('data-bundle-toggle'), [pt.x, pt.y]);
        const mark = () => page.evaluate((k) => { const e = document.querySelector(`[data-bundle-toggle="${k}"]`); return e ? (e.getAttribute('data-open') === '1' ? '\u2212' : '+') : null; }, key);
        const m0 = await mark();
        await jitterClick(pt, 3, 0);
        await settle();
        const m1 = await mark();
        // An expanded Vec shows its elements' rows in place of its own (no toggle back).
        const replaced = m1 === null && await page.evaluate((k) => !!document.querySelector(`[data-bundle-toggle^="${k}["]`), key);
        if (m1 === m0) note(design, where, `a click with jitter on bundle toggle ${key} did not toggle it (${m0})`);
        else if (replaced) console.log(`[${design}] gestures: ${key} expanded into its elements (no toggle back)`);
        else {
          const pt2 = await pointOn(`[data-bundle-toggle="${key}"]`);
          if (pt2) { await jitterClick(pt2, 2, 2); await settle(); }
          if (await mark() !== m0) note(design, where, `bundle toggle ${key} did not toggle back (${m0} -> ${m1} -> ${await mark()})`);
        }
      }
    }

    // The dot of an expandable pin toggles its bundle too, with a pointer
    // cursor, and the hint bar (its card) says so: a top-level bundle's dot
    // (data-pin) and an open bundle's expandable field's (data-pin-field), each
    // where a design has one.
    for (const [attr, cls, set] of [['data-pin', 'scl-pin.if', pinDotDesigns], ['data-pin-field', 'scl-fpin', fieldDotDesigns]]) {
      const find = () => page.evaluate(([attr, cls]) => {
        for (const d of document.querySelectorAll(`[data-content] .${cls}[${attr}]`)) {
          const k = d.getAttribute(attr);
          if (!k.includes('[') && d.parentElement.querySelector(`[data-bundle-toggle="${CSS.escape(k)}"]`)) return k;
        }
        return null;
      }, [attr, cls]);
      let key = await find();
      // A field row shows once its parent is open (#332): open top-level
      // bundles, one at a time, until one has an expandable field.
      if (!key && attr === 'data-pin-field') {
        const tops = await page.evaluate(() => Array.from(document.querySelectorAll('[data-content] .scl-pin.if[data-pin]'))
          .map((d) => d.getAttribute('data-pin')).filter((k) => !k.includes('[')).slice(0, 8));
        for (const t of tops) {
          await page.locator(`[data-bundle-toggle="${t.replace(/["\\]/g, '\\$&')}"]`).first().dispatchEvent('click');
          await settle();
          if ((key = await find())) break;
          await page.locator(`[data-bundle-toggle="${t.replace(/["\\]/g, '\\$&')}"]`).first().dispatchEvent('click');
          await settle();
        }
      }
      const dotSel = key && `.${cls}[${attr}="${key.replace(/["\\]/g, '\\$&')}"]`;
      const pt = key && await pointOn(dotSel);
      if (!pt) continue;
      const mark = () => page.evaluate((k) => { const e = document.querySelector(`[data-bundle-toggle="${CSS.escape(k)}"]`); return e ? (e.getAttribute('data-open') === '1' ? '\u2212' : '+') : null; }, key);
      const cur = await page.evaluate((s) => getComputedStyle(document.querySelector(s)).cursor, dotSel);
      if (cur !== 'pointer') note(design, where, `the expandable pin dot ${key} has cursor ${cur}, expected pointer`);
      await page.mouse.move(pt.x, pt.y);
      await page.waitForTimeout(150);
      const hint = await page.locator('[data-hint-text]').first().textContent().catch(() => '');
      if (!/expand/.test(hint || '')) note(design, where, `resting on the pin dot of ${key}, the hint bar says ${JSON.stringify(hint)}, not expand`);
      const m0 = await mark();
      await page.mouse.click(pt.x, pt.y);
      await settle();
      const m1 = await mark();
      // An expanded Vec shows its elements' rows in place of its own (no toggle back).
      const replaced = m1 === null && await page.evaluate((k) => !!document.querySelector(`[data-bundle-toggle^="${CSS.escape(k)}["]`), key);
      if (m1 === m0) note(design, where, `a click on the pin dot of ${key} did not toggle it (${m0})`);
      else if (replaced) { set.add(design); console.log(`[${design}] gestures: the pin dot of ${key} expanded it into its elements (no toggle back)`); }
      else {
        set.add(design);
        const pt2 = await pointOn(dotSel);
        if (pt2) { await page.mouse.click(pt2.x, pt2.y); await settle(); }
        if (await mark() !== m0) note(design, where, `the pin dot of ${key} did not toggle back (${m0} -> ${m1} -> ${await mark()})`);
      }
    }

    // A double-click with 1 to 2 px of jitter on a drillable title drills in.
    {
      const pt = await pointOn('[data-box-inst][data-box-drillable="1"]:not([data-fold-group]) > div:first-child');
      if (pt) {
        gestureDrillDesigns.add(design);
        const c0 = await crumbs();
        await page.mouse.move(pt.x, pt.y);
        await page.mouse.down();
        await page.mouse.move(pt.x + 1, pt.y + 1);
        await page.mouse.up();
        await page.mouse.down({ clickCount: 2 });
        await page.mouse.move(pt.x + 2, pt.y);
        await page.mouse.up({ clickCount: 2 });
        await settle();
        const c1 = await crumbs();
        if (c1 === c0) note(design, where, `a double-click with jitter on a drillable title did not drill (${c0})`);
        else {
          await page.locator('.scl-crumbs .scl-crumb').first().click().catch((e) => note(design, where, e.message));
          await settle();
          if (await crumbs() !== c0) note(design, where, `could not return to ${c0} after the drill (${await crumbs()})`);
        }
      }
    }

    // A jittered click on a logic block's header (captured views) selects it.
    {
      await clearSel();
      const pt = await pointOn('[data-block] > div:first-child');
      if (pt) {
        gestureBlockDesigns.add(design);
        await jitterClick(pt, 3, 0);
        if (!await isSelected()) note(design, where, 'a click with (3, 0) px of jitter on a logic block did not select');
        await clearSel();
      }
    }

    // A navigation ends a drag in progress: the rest of that drag does not
    // move the new module's view. Navigate by a script click on a hierarchy
    // row (no pointer event), mid-drag.
    {
      await clearSel();
      const child = paths.find((p) => p.path.startsWith(paths[0].path + '/'));
      const b = await bgPoint();
      if (child && b) {
        gestureNavDesigns.add(design);
        const c0 = await crumbs();
        await page.mouse.move(b.x, b.y);
        await page.mouse.down();
        for (let i = 1; i <= 4; i++) { await page.mouse.move(b.x + 5 * i, b.y + 3 * i); await page.waitForTimeout(20); }
        await page.evaluate((p) => document.querySelector(`[data-hier-path="${p}"]`).click(), child.path);
        await settle();
        await page.waitForTimeout(300);
        const vNav = await readView(); const c1 = await crumbs();
        for (let i = 1; i <= 5; i++) { await page.mouse.move(b.x + 20 + 6 * i, b.y + 12 + 4 * i); await page.waitForTimeout(20); }
        await page.mouse.up();
        await page.waitForTimeout(100);
        const vEnd = await readView();
        if (c1 === c0) note(design, where, `the mid-drag navigation to ${child.path} did not navigate`);
        if (JSON.stringify(vNav) !== JSON.stringify(vEnd)) note(design, where, `a drag kept panning after navigating to ${child.path}: ${JSON.stringify(vNav)} -> ${JSON.stringify(vEnd)}`);
        if (await isSelected()) note(design, where, `the drag across the navigation to ${child.path} selected something`);
        await page.locator('.scl-crumbs .scl-crumb').first().click().catch((e) => note(design, where, e.message));
        await settle();
        if (await crumbs() !== c0) note(design, where, `could not return to ${c0} after the mid-drag navigation (${await crumbs()})`);
      }
    }

    // A middle-button drag pans at once (3 px, under the left-button threshold).
    {
      await clearSel();
      const pt = await pointOn(titleSel);
      if (pt) {
        const v0 = await readView();
        await page.mouse.move(pt.x, pt.y);
        await page.mouse.down({ button: 'middle' });
        await page.mouse.move(pt.x + 3, pt.y + 2);
        await page.waitForTimeout(60);
        await page.mouse.up({ button: 'middle' });
        await page.waitForTimeout(80);
        const v1 = await readView();
        if (!v0 || !v1 || Math.abs(v1.x - v0.x - 3) > 0.5 || Math.abs(v1.y - v0.y - 2) > 0.5)
          note(design, where, `a 3 px middle-button drag did not pan at once: ${JSON.stringify(v0)} -> ${JSON.stringify(v1)}`);
        if (await isSelected()) note(design, where, 'a middle-button drag from a title selected it');
      }
    }

    // Wheel: plain zooms at the cursor, Shift scrolls horizontally, Ctrl vertically.
    {
      const r = await vpRect();
      const b = (await bgPoint()) || { x: r.x + r.w / 2, y: r.y + r.h / 2 };
      await page.mouse.move(b.x, b.y);
      const v0 = await readView();
      await page.mouse.wheel(0, 100);
      await page.waitForTimeout(100);
      const v1 = await readView();
      if (!v0 || !v1 || !(v1.z < v0.z)) note(design, where, `a plain wheel down did not zoom out: ${JSON.stringify(v0)} -> ${JSON.stringify(v1)}`);
      await page.keyboard.down('Shift');
      await page.mouse.wheel(0, 100);
      await page.keyboard.up('Shift');
      await page.waitForTimeout(100);
      const v2 = await readView();
      if (!v1 || !v2 || v2.z !== v1.z || Math.abs(v2.y - v1.y) > 0.5 || !(v2.x < v1.x - 50))
        note(design, where, `Shift + wheel did not scroll horizontally: ${JSON.stringify(v1)} -> ${JSON.stringify(v2)}`);
      await page.keyboard.down('Control');
      await page.mouse.wheel(0, 100);
      await page.keyboard.up('Control');
      await page.waitForTimeout(100);
      const v3 = await readView();
      if (!v2 || !v3 || v3.z !== v2.z || Math.abs(v3.x - v2.x) > 0.5 || !(v3.y < v2.y - 50))
        note(design, where, `Ctrl + wheel did not scroll vertically: ${JSON.stringify(v2)} -> ${JSON.stringify(v3)}`);
      // The classifier, on what a mouse, a trackpad and Firefox report.
      const cases = [
        // [deltaX, deltaY, deltaMode, ctrl, shift, expected]
        [0, 100, 0, false, false, 'zoom-'],       // a mouse notch
        [0, -100, 0, false, false, 'zoom+'],
        [0, 66.666, 0, false, false, 'zoom-'],    // a notch at a fractional scale
        [0, 90.909, 0, false, false, 'zoom-'],
        [0, 3, 1, false, false, 'zoom-'],         // Firefox, in lines
        [0, 100, 0, true, false, 'pan:0,-100'],   // Ctrl + wheel: vertical scroll
        [0, 3, 1, true, false, 'pan:0,-48'],
        [0, 1.37, 0, true, false, 'zoom-'],       // a pinch (Ctrl, small fractional)
        [0, -2.6, 0, true, false, 'zoom+'],
        [100, 0, 0, false, true, 'pan:-100,0'],   // Shift + wheel, as Chrome reports it
        [0, 100, 0, false, true, 'pan:-100,0'],
        [4.2, 1.1, 0, false, false, 'pan:-4.2,-1.1'], // a two-finger swipe
        [0, 2.5, 0, false, false, 'pan:0,-2.5'],
        [-7, 0, 0, false, false, 'pan:7,0'],
      ];
      const got = await page.evaluate((cs) => {
        const f = window.__socleWheelClassify;
        if (!f) return null;
        return cs.map(([dx, dy, m, c, s]) => {
          const o = f(dx, dy, m, c, s);
          return o.kind === 'zoom' ? (o.log < 0 ? 'zoom-' : 'zoom+') : `pan:${o.dx + 0},${o.dy + 0}`;
        });
      }, cases);
      if (!got) note(design, where, 'no window.__socleWheelClassify hook');
      else cases.forEach((c, i) => { if (got[i] !== c[5]) note(design, where, `wheel classify ${JSON.stringify(c.slice(0, 5))}: ${got[i]}, expected ${c[5]}`); });
    }

    // Keys, with the focus on the body: the arrows pan by 10% of the view
    // (ArrowRight looks right, so the content moves left), + and - zoom, f and
    // 0 fit as the Fit button does.
    {
      await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
      const { w, h } = await vpRect();
      const press = async (k) => { await page.keyboard.press(k); await page.waitForTimeout(60); return readView(); };
      const v0 = await readView();
      const v1 = await press('ArrowRight');
      if (!v0 || !v1 || Math.abs((v0.x - v1.x) - 0.1 * w) > 1.5 || Math.abs(v1.y - v0.y) > 0.5)
        note(design, where, `ArrowRight (focus on the body) did not pan by 10% of the width ${w}: ${JSON.stringify(v0)} -> ${JSON.stringify(v1)}`);
      const v2 = await press('ArrowDown');
      if (!v1 || !v2 || Math.abs((v1.y - v2.y) - 0.1 * h) > 1.5 || Math.abs(v2.x - v1.x) > 0.5)
        note(design, where, `ArrowDown did not pan by 10% of the height ${h}: ${JSON.stringify(v1)} -> ${JSON.stringify(v2)}`);
      await press('ArrowLeft');
      const v3 = await press('ArrowUp');
      if (!v0 || !v3 || Math.abs(v3.x - v0.x) > 0.5 || Math.abs(v3.y - v0.y) > 0.5)
        note(design, where, `ArrowLeft and ArrowUp did not pan back: ${JSON.stringify(v0)} -> ${JSON.stringify(v3)}`);
      const v4 = await press('+');
      if (!v3 || !v4 || !(v4.z > v3.z)) note(design, where, `+ did not zoom in: ${JSON.stringify(v3)} -> ${JSON.stringify(v4)}`);
      const v5 = await press('-');
      if (!v4 || !v5 || !(v5.z < v4.z)) note(design, where, `- did not zoom out: ${JSON.stringify(v4)} -> ${JSON.stringify(v5)}`);
      const vf = await press('f');
      await page.locator('button[title="Fit to view"]').first().click().catch((e) => note(design, where, e.message));
      await page.waitForTimeout(60);
      const vfit = await readView();
      if (JSON.stringify(vf) !== JSON.stringify(vfit)) note(design, where, `f did not fit: ${JSON.stringify(vf)}, Fit gives ${JSON.stringify(vfit)}`);
      await press('ArrowRight');
      const v0k = await press('0');
      if (JSON.stringify(v0k) !== JSON.stringify(vfit)) note(design, where, `0 did not fit: ${JSON.stringify(v0k)}, Fit gives ${JSON.stringify(vfit)}`);
      // Not from a field or a control outside the schematic.
      for (const sel of ['[data-filter-input]', '[data-search-trigger]']) {
        const el = page.locator(sel).first();
        if (!await el.count()) continue;
        await el.focus();
        const a = await readView();
        await page.keyboard.press('ArrowRight');
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('-');
        await page.waitForTimeout(60);
        const b = await readView();
        if (JSON.stringify(a) !== JSON.stringify(b)) note(design, where, `keys moved the view with the focus on ${sel}: ${JSON.stringify(a)} -> ${JSON.stringify(b)}`);
        if (sel === '[data-filter-input]') await page.keyboard.press('Escape'); // clears and blurs the filter
      }
      // Nor after a click outside the schematic that leaves the focus on the
      // body: a hierarchy row (the current module's), the inspector, the
      // bottom panel.
      for (const sel of [`[data-hier-path="${paths.length ? paths[0].path : ''}"]`, '[data-pane="inspector"]', '.scl-bottom-body']) {
        const el = page.locator(sel).first();
        if (!await el.count() || !await el.isVisible()) continue;
        await el.click({ position: { x: 4, y: 4 } }).catch((e) => note(design, where, `click ${sel}: ${e.message}`));
        await settle();
        const a = await readView();
        await page.keyboard.press('ArrowRight');
        await page.keyboard.press('-');
        await page.waitForTimeout(60);
        const b = await readView();
        if (JSON.stringify(a) !== JSON.stringify(b)) note(design, where, `keys moved the view after a click on ${sel}: ${JSON.stringify(a)} -> ${JSON.stringify(b)}`);
      }
      // A click in the schematic gives it the focus back: the keys act again.
      const bg = await bgPoint();
      if (bg) {
        await jitterClick(bg, 0, 0);
        const a = await readView();
        const b = await press('ArrowRight');
        if (!a || !b || !(a.x - b.x > 1)) note(design, where, `after a click in the schematic, ArrowRight did not pan (focus on ${await page.evaluate(() => document.activeElement && document.activeElement.tagName)})`);
        // Not behind the "?" card.
        await page.keyboard.press('Shift+Slash');
        await page.waitForTimeout(60);
        if (!await page.locator('[data-help-overlay]').count()) note(design, where, '? did not open the help card');
        else {
          const c = await readView();
          await page.keyboard.press('ArrowRight');
          await page.keyboard.press('ArrowDown');
          await page.keyboard.press('-');
          await page.waitForTimeout(60);
          const d = await readView();
          if (JSON.stringify(c) !== JSON.stringify(d)) note(design, where, `keys moved the view behind the ? card: ${JSON.stringify(c)} -> ${JSON.stringify(d)}`);
          await page.keyboard.press('/');
          await page.waitForTimeout(60);
          if (await page.evaluate(() => !!(document.activeElement && document.activeElement.matches('[data-filter-input]'))))
            note(design, where, '/ focused the filter behind the ? card');
          if (!await page.locator('[data-help-overlay]').count()) note(design, where, '/ closed the ? card');
          await page.keyboard.press('Escape');
          await page.waitForTimeout(60);
          const e = await press('ArrowRight');
          if (!d || !e || !(d.x - e.x > 1)) note(design, where, `ArrowRight did not pan once the ? card closed: ${JSON.stringify(d)} -> ${JSON.stringify(e)}`);
        }
      }
    }

    // Leave a user-set view (1:1) and no selection for the checks below.
    await clearSel();
    await page.locator('button[title="Reset to 100%"]').first().click().catch(() => {});
    await page.waitForTimeout(60);
    drain(where);
  }

  // History, fly to and zoom to the selection. The stack model (a cap of 50,
  // a new place after Back drops the forward entries) through a hook; on a
  // hierarchical design, a walk: drill in, pan and let it settle, drill out,
  // then Back, Back, Forward, Forward (Alt+Left / Alt+Right) each land on the
  // view they name; a new place after Back leaves nothing to go forward to;
  // the mouse's back and forward buttons walk it without leaving the page;
  // Alt+Left in the filter does nothing; the palette has Back and Forward. A
  // palette instance flies to its box (animated, ends centred), from another
  // module too; with reduced motion it jumps; a drag during a flight stops
  // it. `z` frames the selected box or wire, and does nothing with no selection.
  {
    const where = 'history';
    const fmt = JSON.stringify;
    // (No flight running and the view still for 150 ms: on a loaded runner one
    // frame can outlast a shorter poll, which then read a flight as settled.
    // Then the view the history holds as its current entry: on a slow runner
    // the module's own view (its fit or restored view) can land after a quiet
    // spell, and the entry is that view, not the one shown before it.)
    const near0 = (a, b) => !!a && !!b && Math.abs(a.x - b.x) < 1.5 && Math.abs(a.y - b.y) < 1.5 && Math.abs(a.z - b.z) < 0.002;
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForFunction(() => !(window.__socleFlying && window.__socleFlying()), null, { timeout: 10000 }).catch(() => {});
      let v = await readView();
      for (let i = 0; i < 25; i++) {
        await page.waitForTimeout(150);
        const n = await readView();
        if (fmt(n) === fmt(v)) break;
        v = n;
      }
      for (let i = 0; i < 20; i++) {
        const h = await page.evaluate(() => (window.__socleHistory ? window.__socleHistory() : null));
        const cur = h && h.entries[h.index];
        if (!cur || near0(v, cur)) break;
        await page.waitForTimeout(150);
        v = await readView();
      }
      return v;
    };
    const crumbs = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > '));
    const near = (a, b) => !!a && !!b && Math.abs(a.x - b.x) < 1.5 && Math.abs(a.y - b.y) < 1.5 && Math.abs(a.z - b.z) < 0.002;
    const focusSchematic = () => page.evaluate(() => document.querySelector('[data-content]').parentElement.focus({ preventScroll: true }));
    const key = async (k) => { await focusSchematic(); await page.keyboard.press(k); return settle(); };
    const hist = () => page.evaluate(() => (window.__socleHistory ? window.__socleHistory() : null));
    const vpBox = () => page.evaluate(() => {
      const r = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2, l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height };
    });
    const rectOf = (sel) => page.evaluate((sel) => {
      const els = Array.from(document.querySelectorAll(sel));
      if (!els.length) return null;
      const rs = els.map((e) => e.getBoundingClientRect());
      const l = Math.min(...rs.map((r) => r.left)), t = Math.min(...rs.map((r) => r.top));
      const r = Math.max(...rs.map((q) => q.right)), b = Math.max(...rs.map((q) => q.bottom));
      return { x: (l + r) / 2, y: (t + b) / 2, l, t, r, b, w: r - l, h: b - t };
    }, sel);
    const boxSel = (id) => `[data-box-inst="${id.replace(/["\\]/g, '\\$&')}"]`;
    const offCentre = async (sel) => { const a = await rectOf(sel); const v = await vpBox(); return a ? Math.hypot(a.x - v.x, a.y - v.y) : null; };
    // The distinct content transforms seen from now until `unwatchXform`.
    const watchXform = () => page.evaluate(() => {
      const c = document.querySelector('[data-content]');
      const S = window.__flySmoke = { seen: new Set([c.style.transform]) };
      S.obs = new MutationObserver(() => S.seen.add(c.style.transform));
      S.obs.observe(c, { attributes: true, attributeFilter: ['style'] });
    });
    const unwatchXform = () => page.evaluate(() => { const S = window.__flySmoke; S.obs.disconnect(); return S.seen.size - 1; });
    // Choose `label` in the command palette (false when it is not listed).
    const pick = async (label) => {
      await page.keyboard.press('Control+k');
      const inp = page.locator('[data-cmd-input]');
      if (!await inp.count()) return false;
      await inp.fill(label);
      await page.waitForTimeout(100);
      const r = page.locator(`[data-cmd-result="${label.replace(/["\\]/g, '\\$&')}"]`).first();
      if (!await r.count()) { await page.keyboard.press('Escape'); return false; }
      await r.click();
      return true;
    };
    const resetView = async () => {
      await page.locator('button[title="Reset to 100%"]').first().click().catch(() => {});
      await page.waitForTimeout(60);
    };
    const top = paths.length ? paths[0].path : null;
    const toTop = async () => {
      if (top && await crumbs() !== top) {
        await page.locator(`[data-hier-path="${top}"]`).first().dispatchEvent('click').catch(() => {});
        await settle();
      }
    };

    // The stack model.
    const model = await page.evaluate(() => {
      const f = window.__socleHistoryModel;
      if (!f) return null;
      return {
        cap: f(Array(60).fill('p')),
        drop: f(['p', 'p', 'p', 'p', 'p', 'b', 'b', 'p']),
        ends: f(['p', 'b', 'b', 'f', 'f', 'f']),
      };
    });
    // The module step of the previous / next module buttons: past every view
    // of the module shown, to the latest view of the module before or after.
    {
      const steps = await page.evaluate(() => {
        const f = window.__socleModuleStep;
        if (!f) return null;
        const h = ['T', 'T/a', 'T/a', 'T/a/x', 'T/a/x', 'T/a/x', 'T', 'T'];
        return [
          ['back over three views of T/a/x to T/a', f(h, 5, 'T/a/x', -1), 2],
          ['back from T/a to T', f(h, 2, 'T/a', -1), 0],
          ['back from the start', f(h, 0, 'T', -1), -1],
          ['forward from T/a to the latest view of T/a/x', f(h, 2, 'T/a', 1), 5],
          ['forward from T/a/x to the latest view of T', f(h, 3, 'T/a/x', 1), 7],
          ['forward from the end', f(h, 7, 'T', 1), -1],
        ];
      });
      if (!steps) note(design, where, 'no window.__socleModuleStep hook');
      else for (const [what, got, want] of steps) if (got !== want) note(design, where, `module step ${what}: landed on ${got}, expected ${want}`);
    }
    if (!model) note(design, where, 'no window.__socleHistoryModel hook');
    else {
      if (model.cap.size !== 50 || model.cap.index !== 49)
        note(design, where, `60 places kept ${model.cap.size} entries at ${model.cap.index}, expected 50 at 49`);
      if (model.drop.size !== 4 || model.drop.index !== 3 || model.drop.canForward)
        note(design, where, `a new place after two Backs did not drop the forward entries: ${fmt(model.drop)}`);
      if (model.ends.size !== 1 || model.ends.index !== 0)
        note(design, where, `Back and Forward past the ends moved: ${fmt(model.ends)}`);
    }

    // The walk, on a design with a module to drill into from the top.
    await toTop();
    const child = await page.evaluate(() => {
      const b = document.querySelector('[data-box-inst][data-box-drillable="1"]:not([data-fold-group])');
      return b ? b.getAttribute('data-box-inst') : null;
    });
    if (top && child && await page.locator(`[data-hier-path="${top}/${child}"]`).count()) {
      historyDesigns.add(design);
      await settle();
      await page.locator(`[data-hier-path="${top}/${child}"]`).first().dispatchEvent('click');
      const C0 = await settle();
      const cChild = await crumbs();
      if (await page.evaluate(() => document.querySelector('[data-nav-back]')?.disabled !== false))
        note(design, where, 'the Back button is not enabled after a drill');
      const vc = await vpBox();
      await page.mouse.move(vc.x, vc.y);
      await page.mouse.down();
      for (let i = 1; i <= 4; i++) { await page.mouse.move(vc.x + 15 * i, vc.y + 10 * i); await page.waitForTimeout(16); }
      await page.mouse.up();
      await page.waitForTimeout(800); // the pan settles into an entry
      const C1 = await readView();
      await page.locator('.scl-crumbs .scl-crumb').first().click();
      const T1 = await settle();
      const cTop = await crumbs();
      const steps = [
        ['Alt+ArrowLeft', C1, cChild, 'Back to the drilled and panned view'],
        ['Alt+ArrowLeft', C0, cChild, 'Back again to the drilled view'],
        ['Alt+ArrowRight', C1, cChild, 'Forward to the drilled and panned view'],
        ['Alt+ArrowRight', T1, cTop, 'Forward to the top'],
      ];
      for (const [k, want, wantCrumbs, what] of steps) {
        const v = await key(k);
        const c = await crumbs();
        if (!near(v, want) || c !== wantCrumbs)
          note(design, where, `${k}: ${what}: got ${fmt(v)} at "${c}", expected ${fmt(want)} at "${wantCrumbs}"; history ${fmt(await hist())}`);
      }
      // A new place after Back drops the forward entries. Back has landed once
      // the child shows (on a slow runner its drill can outlast settle's wait):
      // a click on the top crumb before that is a no-op, and Back then lands.
      await key('Alt+ArrowLeft');
      await page.waitForFunction((c) => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > ') === c,
        cChild, { timeout: 15000 }).catch(() => {});
      await settle();
      await page.locator('.scl-crumbs .scl-crumb').first().click();
      await settle();
      const h = await hist();
      if (!h || h.index !== h.size - 1) note(design, where, `a drill out after Back left forward entries: ${fmt(h)}`);
      const vN = await readView();
      const vNF = await key('Alt+ArrowRight');
      if (!near(vN, vNF) || await crumbs() !== cTop) note(design, where, `Forward moved after a new place: ${fmt(vN)} -> ${fmt(vNF)}`);
      // The mouse's back and forward buttons, without leaving the page.
      const url0 = page.url();
      const cdp = await page.context().newCDPSession(page);
      const side = async (button) => {
        const p = await vpBox();
        await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y });
        await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button, buttons: button === 'back' ? 8 : 16, clickCount: 1 });
        await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button, buttons: 0, clickCount: 1 });
        await page.waitForTimeout(100);
        return settle();
      };
      const mb = await side('back');
      if (!near(mb, C1) || await crumbs() !== cChild) note(design, where, `the mouse back button did not go back: ${fmt(mb)} at "${await crumbs()}", expected ${fmt(C1)}`);
      const mf = await side('forward');
      if (!near(mf, vN) || await crumbs() !== cTop) note(design, where, `the mouse forward button did not go forward: ${fmt(mf)} at "${await crumbs()}", expected ${fmt(vN)}`);
      if (page.url() !== url0) note(design, where, `the mouse buttons left the page: ${page.url()}`);
      await cdp.detach().catch(() => {});
      // Not from the filter input.
      {
        const h0 = await hist(); const v0 = await readView();
        await page.locator('[data-filter-input]').first().focus();
        await page.keyboard.press('Alt+ArrowLeft');
        await page.waitForTimeout(200);
        const h1 = await hist(); const v1 = await readView();
        if (!near(v0, v1) || await crumbs() !== cTop || fmt(h0) !== fmt(h1)) note(design, where, `Alt+Left in the filter input moved: ${fmt(v0)} -> ${fmt(v1)}`);
        await page.keyboard.press('Escape');
        if (page.url() !== url0) note(design, where, `Alt+Left in the filter input left the page: ${page.url()}`);
      }
      // The palette's Back and Forward.
      if (!await pick('Back')) note(design, where, 'no Back command in the palette');
      else {
        const v = await settle();
        if (!near(v, C1) || await crumbs() !== cChild) note(design, where, `the palette's Back did not go back: ${fmt(v)} at "${await crumbs()}", expected ${fmt(C1)}; history ${fmt(await hist())}`);
        if (!await pick('Forward')) note(design, where, 'no Forward command in the palette');
        else {
          const w = await settle();
          if (!near(w, vN) || await crumbs() !== cTop) note(design, where, `the palette's Forward did not go forward: ${fmt(w)} at "${await crumbs()}", expected ${fmt(vN)}; history ${fmt(await hist())}`);
        }
      }
      // The Back and Forward buttons walk it like Alt+Left / Alt+Right, are
      // disabled at an end, and a press leaves the keys to the schematic.
      {
        const dis = () => page.evaluate(() => ({
          back: document.querySelector('[data-nav-back]')?.disabled ?? null,
          fwd: document.querySelector('[data-nav-forward]')?.disabled ?? null,
        }));
        const d0 = await dis();
        if (d0.back !== false || d0.fwd !== true) note(design, where, `at the end of the history the buttons read ${fmt(d0)}, expected Back enabled and Forward disabled`);
        await focusSchematic();
        await page.locator('[data-nav-back]').first().click();
        const v = await settle();
        if (!near(v, C1) || await crumbs() !== cChild) note(design, where, `the Back button did not go back: ${fmt(v)} at "${await crumbs()}", expected ${fmt(C1)}`);
        if (await page.evaluate(() => !!document.activeElement?.closest('[data-nav-back], [data-nav-forward]'))) note(design, where, 'the Back button took the keyboard focus');
        if ((await dis()).fwd !== false) note(design, where, 'the Forward button is not enabled after a Back');
        await page.locator('[data-nav-forward]').first().click();
        const w = await settle();
        if (!near(w, vN) || await crumbs() !== cTop) note(design, where, `the Forward button did not go forward: ${fmt(w)} at "${await crumbs()}", expected ${fmt(vN)}`);
        // The keys still act after a press, without focusing the schematic again.
        await page.keyboard.press('Alt+ArrowLeft');
        const k = await settle();
        if (!near(k, C1) || await crumbs() !== cChild) note(design, where, `Alt+Left after a press on the Forward button did not go back: ${fmt(k)} at "${await crumbs()}"`);
        await key('Alt+ArrowRight');
        navButtonDesigns.add(design);
      }
      if (!await page.evaluate(() => Array.isArray(window.__socleHistory && window.__socleHistory().entries))) note(design, where, 'no window.__socleHistory hook');
    }
    drain(where);

    // A double-click opens a leaf module that has content (registers, logic,
    // wires), and the hierarchy follows the open module: with the tree
    // collapsed, the drill expands the rows above the leaf's, which shows,
    // in view and marked current; Back marks its parent again.
    {
      const where2 = 'leaf drill';
      const pane = '[data-pane="hierarchy"]';
      const esc = (x) => x.replace(/["\\]/g, '\\$&');
      let tried = 0;
      // The deepest leaves first: more rows above to expand.
      const deepest = [...paths].sort((a, b) => b.path.split('/').length - a.path.split('/').length);
      for (const { path: lp, leaf } of deepest) {
        const segs = lp.split('/');
        if (!leaf || segs.length < 2 || tried >= 6) continue;
        const pp = segs.slice(0, -1).join('/'), id = segs[segs.length - 1];
        tried++;
        await page.locator(`[data-hier-path="${esc(pp)}"]`).first().dispatchEvent('click').catch(() => {});
        await settle();
        const title = page.locator(`[data-box-inst="${esc(id)}"][data-box-drillable="1"]:not([data-fold-group]) > div:first-child`).first();
        if (!await title.count()) continue;
        // Collapse the tree at the top (the smoke expanded all of it).
        const tt = page.locator(`[data-hier-toggle="${esc(top)}"]`).first();
        if (await tt.count() && (await tt.textContent()) === '▾') await tt.click();
        const pre = await readView(), preC = await crumbs();
        await title.dblclick();
        await settle();
        const c = await crumbs();
        if (!c.endsWith(` > ${id}`)) { note(design, where2, `a double-click on the leaf ${lp} did not open it ("${c}")`); break; }
        await page.waitForTimeout(100); // the pane scrolls on the next frame
        const t = await page.evaluate(([lp, pane]) => {
          const row = document.querySelector(`[data-hier-path="${CSS.escape(lp)}"]`);
          const cur = Array.from(document.querySelectorAll('[data-hier-current="1"]')).map((e) => e.getAttribute('data-hier-path'));
          const ups = lp.split('/').slice(0, -1).map((_, i, a) => a.slice(0, i + 1).join('/'));
          const open = ups.map((u) => document.querySelector(`[data-hier-toggle="${CSS.escape(u)}"]`)?.textContent);
          const p = document.querySelector(pane)?.getBoundingClientRect(), r = row?.getBoundingClientRect();
          return { row: !!row, cur, open, inView: !!(p && r && r.top >= p.top - 1 && r.bottom <= p.bottom + 1),
                   active: !!row?.classList.contains('active'), focus: document.activeElement?.closest(pane) ? 'tree' : 'other' };
        }, [lp, pane]);
        if (!t.row) note(design, where2, `after opening ${lp} its hierarchy row is not shown (rows above: ${fmt(t.open)})`);
        else {
          if (t.cur.length !== 1 || t.cur[0] !== lp || !t.active) note(design, where2, `after opening ${lp} the current rows are ${fmt(t.cur)} (active ${t.active})`);
          if (t.open.some((g) => g !== '▾')) note(design, where2, `after opening ${lp} the rows above it are not all expanded: ${fmt(t.open)}`);
          if (!t.inView) note(design, where2, `after opening ${lp} its hierarchy row is out of view`);
          if (t.focus === 'tree') note(design, where2, `opening ${lp} moved the focus into the hierarchy`);
        }
        // Views of the leaf that settle into the history (two pans and a zoom):
        // one press on the previous module button still goes back to the parent.
        for (const [dx, dy] of [[40, 25], [-30, 20]]) {
          const vc = await vpBox();
          await page.mouse.move(vc.x, vc.y); await page.mouse.down();
          for (let i = 1; i <= 4; i++) { await page.mouse.move(vc.x + dx * i / 4, vc.y + dy * i / 4); await page.waitForTimeout(16); }
          await page.mouse.up();
          await page.waitForTimeout(800);
        }
        await key('-');
        await page.waitForTimeout(800);
        await page.locator('[data-nav-back]').first().click();
        const vb = await settle();
        if (!near(vb, pre) || await crumbs() !== preC) note(design, where2, `Back from ${lp} did not return to the view before the drill: ${fmt(vb)} at "${await crumbs()}", expected ${fmt(pre)} at "${preC}"`);
        const back = await page.evaluate(() => Array.from(document.querySelectorAll('[data-hier-current="1"]')).map((e) => e.getAttribute('data-hier-path')));
        if (back.length !== 1 || back[0] !== pp) note(design, where2, `after Back from ${lp} the current rows are ${fmt(back)}, expected ${pp}`);
        leafDrillDesigns.add(design);
        console.log(`[${design}] leaf drill: double-click opened ${lp}`);
        break;
      }
      // Expand the whole tree again, as the checks below found it.
      for (let i = 0; i < 200; i++) {
        const shut = page.locator('[data-hier-toggle]', { hasText: '▸' });
        if (!await shut.count()) break;
        await shut.first().click({ timeout: 1500 }).catch(() => {});
      }
      await toTop();
      await settle();
    }
    drain('leaf drill');

    // Fly to a block from the palette, on the top module: the instance box
    // farthest from the centre after a zoom out.
    await toTop();
    const topMod = await page.getAttribute('[data-top]', 'data-top').catch(() => null);
    // (`inView`: only a box wholly in the viewport, so its title can be clicked.)
    const farthest = (inView = false) => page.evaluate((inView) => {
      const vp = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
      const cx = vp.left + vp.width / 2, cy = vp.top + vp.height / 2;
      let best = null, bd = -1;
      for (const b of document.querySelectorAll('[data-box-inst][data-box-drillable]:not([data-fold-group])')) {
        const r = b.getBoundingClientRect();
        if (inView && (r.left < vp.left + 5 || r.top < vp.top + 5 || r.right > vp.right - 5 || r.bottom > vp.bottom - 5)) continue;
        const d = Math.hypot(r.left + r.width / 2 - cx, r.top + r.height / 2 - cy);
        if (d > bd) { bd = d; best = b.getAttribute('data-box-inst'); }
      }
      return best ? { id: best, d: bd } : null;
    }, inView);
    const zoomOut = async () => { await resetView(); await key('-'); await key('-'); };
    const fly = async (label, id, what, minFrames, maxFrames) => {
      await watchXform();
      const ok = await pick(label);
      if (!ok) { await unwatchXform(); note(design, where, `${what}: no "${label}" in the palette`); return; }
      // A flight seen running counts as animated: a starved runner can paint
      // all of its 250 ms in one or two frames.
      let flew = false;
      for (let i = 0; i < 50 && !flew; i++) {
        flew = await page.evaluate(() => !!(window.__socleFlying && window.__socleFlying()));
        if (!flew) await page.waitForTimeout(20);
      }
      await page.waitForTimeout(400);
      await settle();
      const n = await unwatchXform();
      const d = await offCentre(boxSel(id));
      if (d === null || d > 2) note(design, where, `${what}: ${label} did not end centred (${d === null ? 'no box' : d.toFixed(1) + ' px off'})`);
      const animated = minFrames > 1;
      if (animated ? !(flew || n >= minFrames) : (flew || n < minFrames || n > maxFrames))
        note(design, where, `${what}: ${n} view changes${flew ? ' (a flight ran)' : ''}, expected ${animated ? 'a flight' : `${minFrames} to ${maxFrames}`} (${await loadLog()})`);
    };
    if (topMod) {
      await zoomOut();
      const far = await farthest();
      if (far) {
        flyDesigns.add(design);
        await fly(`${topMod}.${far.id}`, far.id, 'fly to', 3, Infinity);
        // With reduced motion it jumps.
        await zoomOut();
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await fly(`${topMod}.${far.id}`, far.id, 'fly to with reduced motion', 1, 1);
        await page.emulateMedia({ reducedMotion: null });
        // From another module: shows the top, then flies.
        // (Zoomed out first, so the top's restored view is not already the target.)
        if (child && far.id !== child && await page.locator(`[data-hier-path="${top}/${child}"]`).count()) {
          await zoomOut();
          await page.locator(`[data-hier-path="${top}/${child}"]`).first().dispatchEvent('click');
          await settle();
          await fly(`${topMod}.${far.id}`, far.id, 'fly to from another module', 3, Infinity);
          if (await crumbs() !== top) note(design, where, `fly to from another module did not show the top: "${await crumbs()}"`);
          else flyCrossDesigns.add(design);
        }
        // A drag during a flight stops it where it is.
        await zoomOut();
        const far2 = await farthest();
        if (far2 && far2.d > 60) {
          const vc = await vpBox();
          await page.mouse.move(vc.x, vc.y);
          if (await pick(`${topMod}.${far2.id}`)) {
            await page.mouse.move(vc.x, vc.y);
            const flying = await page.evaluate(() => !!(window.__socleFlying && window.__socleFlying()));
            await page.mouse.down();
            for (let i = 1; i <= 3; i++) await page.mouse.move(vc.x + 10 * i, vc.y);
            await page.mouse.up();
            await page.waitForTimeout(400);
            const a = await readView();
            await page.waitForTimeout(150);
            const b = await readView();
            if (!flying) console.log(`[${design}] history: the flight was over before the drag (no cancel check)`);
            else {
              flightCancelDesigns.add(design);
              const d = await offCentre(boxSel(far2.id));
              if (!near(a, b)) note(design, where, `the view kept moving after a drag during a flight: ${fmt(a)} -> ${fmt(b)}`);
              if (d !== null && d < 5) note(design, where, `a drag during a flight did not stop it (the box ended ${d.toFixed(1)} px from the centre)`);
            }
          }
        }
      }
    }
    drain(where);

    // `z` frames the selection: a box (by its title), a wire; nothing with no selection.
    await toTop();
    {
      const isSel = () => page.evaluate(() => document.querySelector('[data-content]').classList.contains('hl-sel'));
      // Nothing selected: nothing happens.
      if (await isSel()) await pick('Clear selection');
      await zoomOut();
      if (!await isSel()) {
        const v0 = await readView();
        const v1 = await key('z');
        if (!near(v0, v1)) note(design, where, `z with nothing selected moved the view: ${fmt(v0)} -> ${fmt(v1)}`);
      }
      const framed = async (sel, what) => {
        const v = await vpBox(); const r = await rectOf(sel);
        if (!r) { note(design, where, `z: no ${what} after it`); return; }
        const d = Math.hypot(r.x - v.x, r.y - v.y);
        if (d > 2) note(design, where, `z did not centre the selected ${what} (${d.toFixed(1)} px off)`);
        if (r.l < v.l - 1 || r.t < v.t - 1 || r.r > v.r + 1 || r.b > v.b + 1) {
          // Bigger than the view only at the smallest zoom.
          const z = (await readView())?.z;
          if (z > 0.081) note(design, where, `z left the selected ${what} outside the view: ${fmt(r)} in ${fmt(v)}`);
        }
      };
      const far = await farthest(true);
      if (far) {
        // Real clicks (a press and a release): a dispatched click after a
        // drag is swallowed as the drag's own.
        const pt = await pointOn(`${boxSel(far.id)} > div:first-child`);
        if (pt) await page.mouse.click(pt.x, pt.y);
        await page.waitForTimeout(80);
        if (!pt || !await isSel()) note(design, where, `a click on ${far.id}'s title did not select it`);
        else {
          const h0 = await hist();
          const vz = await key('z');
          await framed(boxSel(far.id), 'box');
          const h1 = await hist();
          // A new current entry (at the cap of 50 the index stays, the oldest goes).
          // A push: the index grew, or at the cap of 50 (the oldest goes) the
          // old current entry is now the one below a new current one (a
          // relayout replacing the current entry in place is not a push).
          const made = h0 && h1 && (h1.index > h0.index ||
            (h1.size === h0.size && h1.index === h0.index && h1.index > 0 &&
             fmt(h1.entries[h1.index - 1]) === fmt(h0.entries[h0.index]) && fmt(h1.entries[h1.index]) !== fmt(h0.entries[h0.index])));
          if (!made) note(design, where, `z made no entry: ${fmt(h0)} -> ${fmt(h1)}`);
          const back = await key('Alt+ArrowLeft');
          if (near(back, vz)) note(design, where, 'Back after z stayed on the framed view');
        }
      }
      await zoomOut();
      const wpt = await pointOn('path[data-wire]');
      const from = wpt && await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.getAttribute('data-wire'), [wpt.x, wpt.y]);
      if (from) {
        await page.mouse.click(wpt.x, wpt.y);
        await page.waitForTimeout(80);
        if (await isSel()) {
          zoomWireDesigns.add(design);
          await key('z');
          // With the box it starts from, when the wire's path is one (a
          // boundary stub, a state machine).
          const q = from.replace(/["\\]/g, '\\$&');
          // The wire's route (its paths), not its type label (#334).
          await framed(`[data-content] .scl-w[data-from="${q}"] > path, [data-content] [data-boundary-stub="${q}"], [data-content] [data-box-inst="${q}"]`, 'wire');
        }
        await pick('Clear selection');
      }
    }
    // Alt+Left in a field is neither the history nor the browser's Back: the
    // page stays, and the key is consumed (not left to the browser).
    await toTop();
    {
      const url0 = page.url(); const v0 = await readView(); const h0 = await hist();
      await page.evaluate(() => {
        window.__altSeen = null;
        window.addEventListener('keydown', (e) => { if (e.altKey && e.key === 'ArrowLeft') window.__altSeen = e.defaultPrevented; });
      });
      await page.locator('[data-filter-input]').first().focus();
      await page.keyboard.press('Alt+ArrowLeft');
      await page.waitForTimeout(200);
      const seen = await page.evaluate(() => window.__altSeen);
      const v1 = await readView(); const h1 = await hist();
      await page.keyboard.press('Escape');
      if (page.url() !== url0) note(design, where, `Alt+Left in the filter input left the page: ${page.url()}`);
      if (!near(v0, v1) || fmt(h0) !== fmt(h1)) note(design, where, `Alt+Left in the filter input moved the view or the history: ${fmt(v0)} -> ${fmt(v1)}`);
      if (seen !== true) note(design, where, `Alt+Left in the filter input was left to the browser (defaultPrevented ${seen})`);
    }

    // The hierarchy's "Show in parent": from the row's own module, the parent
    // is shown at the row's parent path with the instance's box centred and
    // selected; the row itself does not open.
    {
      const nested = paths.map((p) => p.path).filter((p) => p.split('/').length >= 2);
      const deep = nested.find((p) => p.split('/').length >= 3) || nested[0];
      if (deep) {
        const parentPath = deep.split('/').slice(0, -1).join('/');
        const inst = deep.split('/').pop();
        await page.locator(`[data-hier-path="${deep}"]`).first().dispatchEvent('click');
        await settle();
        const row = page.locator(`[data-hier-path="${deep}"]`).first();
        await row.hover().catch(() => {});
        const btn = row.locator('[data-hier-locate]');
        if (!await btn.count()) note(design, where, `no "Show in parent" button on the row ${deep}`);
        else {
          if (await btn.getAttribute('title') !== 'Show in parent') note(design, where, 'the locate button is not titled "Show in parent"');
          opened.length = 0;
          await btn.click();
          await page.waitForTimeout(400);
          await settle();
          const c = await crumbs();
          if (c !== parentPath.split('/').join(' > ')) note(design, where, `Show in parent on ${deep} showed "${c}", expected the parent ${parentPath}`);
          const d = await offCentre(boxSel(inst));
          // A folded copy is shown unfolded; its own box is then the target.
          if (d === null || d > 2) note(design, where, `Show in parent on ${deep}: the box ${inst} is not centred (${d === null ? 'no box' : d.toFixed(1) + ' px off'})`);
          else locateDesigns.add(design);
          const sel = await page.evaluate(() => document.querySelector('[data-content]').classList.contains('hl-sel'));
          if (!sel) note(design, where, `Show in parent on ${deep} did not select ${inst}`);
          if (opened.length) note(design, where, `Show in parent asked the editor to open a source: ${fmt(opened)}`);
        }
      }
    }

    // A palette pick does not ask the editor to open the instance's source
    // (deepsoc-view stands in for the language server: selecting its blocks
    // and instances opens them).
    if (design === 'deepsoc-view' && topMod) {
      await toTop();
      const inst = await farthest();
      if (inst) {
        opened.length = 0;
        if (await pick(`${topMod}.${inst.id}`)) {
          await page.waitForTimeout(400);
          await settle();
          if (opened.length) note(design, where, `a palette pick asked the editor to open a source: ${fmt(opened)}`);
          else pickNoOpenDesigns.add(design);
        }
        await pick('Clear selection');
      }
    }

    // A fly still waiting for its module's view is dropped by a drag. The
    // pick and the drag run in one task (the result's click, then a drag
    // dispatched on the viewport), so the drag lands before the new module's
    // view is fitted and the fly can start: deterministic. Afterwards the view
    // stays where the drag left it.
    if (child && topMod && await page.locator(`[data-hier-path="${top}/${child}"]`).count()) {
      await page.locator(`[data-hier-path="${top}/${child}"]`).first().dispatchEvent('click');
      await settle();
      const label = `${topMod}.${child}`;
      await page.keyboard.press('Control+k');
      const inp = page.locator('[data-cmd-input]');
      if (await inp.count()) {
        await inp.fill(label);
        await page.waitForTimeout(100);
        const r = await page.evaluate((label) => {
          const res = Array.from(document.querySelectorAll('[data-cmd-result]')).find((e) => e.getAttribute('data-cmd-result') === label);
          if (!res) return null;
          res.click(); // shows the top; the fly waits for its view
          const vp = document.querySelector('[data-content]').parentElement;
          const b = vp.getBoundingClientRect(); const x = b.left + b.width / 2, y = b.top + b.height / 2;
          const ev = (type, dx) => new PointerEvent(type, { bubbles: true, pointerId: 7, button: 0, buttons: type === 'pointerup' ? 0 : 1, clientX: x + dx, clientY: y });
          vp.dispatchEvent(ev('pointerdown', 0));
          window.dispatchEvent(ev('pointermove', 30));
          window.dispatchEvent(ev('pointerup', 30));
          return true;
        }, label);
        if (!r) { note(design, where, `no "${label}" in the palette`); await page.keyboard.press('Escape'); }
        else {
          const a = await readView();
          await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(800);
          const b = await readView();
          stalePendingDesigns.add(design);
          if (!near(a, b)) note(design, where, `a fly waiting for its module's view still flew after a drag: ${fmt(a)} -> ${fmt(b)}`);
        }
      }
      await settle();
    }

    await resetView();
    drain(where);
  }

  // Follow a net through ports. A click on a wire follows it (its own state,
  // apart from the selection): drilling into the instance at one end lights
  // the child's wires on that boundary port, drilling out lights the original
  // wire again, Back and Forward restore it, a sibling instance lights
  // nothing, and Esc or a click on the background clears it; a drag does not.
  // Classes only: following and clearing change no child list, and resting
  // with a followed net mutates nothing. A collapsed and an expanded bundle pin
  // both light. Everywhere: the pure resolution round-trips through every
  // drawn port of an instance (hook).
  {
    const where = 'follow net';
    const fmt = JSON.stringify;
    const settle = async () => {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      await page.waitForFunction(() => !(window.__socleFlying && window.__socleFlying()), null, { timeout: 10000 }).catch(() => {});
      let v = await readView();
      for (let i = 0; i < 25; i++) {
        await page.waitForTimeout(150);
        const n = await readView();
        if (fmt(n) === fmt(v)) break;
        v = n;
      }
    };
    const crumbs = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-crumbs .scl-crumb')).map((b) => b.textContent).join(' > '));
    const top = paths.length ? paths[0].path : null;
    const go = async (p) => { await page.locator(`[data-hier-path="${p}"]`).first().dispatchEvent('click'); await settle(); };
    const toTop = async () => { if (top && await crumbs() !== top) await go(top); };
    const focusSchematic = () => page.evaluate(() => document.querySelector('[data-content]').parentElement.focus({ preventScroll: true }));
    // A boundary-correct prefix test, both ways (`io.in` vs `io.in.valid`, never `io.inx`).
    const ov = (a, b) => a === b || a.startsWith(b + '.') || a.startsWith(b + '[') || b.startsWith(a + '.') || b.startsWith(a + '[');
    // The followed wires drawn now, as [from, to], and the other lit marks.
    const lit = () => page.evaluate(() => ({
      wires: Array.from(document.querySelectorAll('[data-content] .scl-w.follow')).map((g) => [g.getAttribute('data-from'), g.getAttribute('data-to')]),
      // Top-level pin dots, and the dots of a scattered bundle's fields (DeepSoc's `io.in`).
      pins: Array.from(document.querySelectorAll('[data-content] [data-pin].follow, [data-content] [data-pin-field].follow'))
        .map((e) => e.getAttribute('data-pin') || e.getAttribute('data-pin-field')),
      stubs: Array.from(document.querySelectorAll('[data-content] [data-boundary-stub].follow')).map((e) => e.getAttribute('data-boundary-stub')),
      rows: document.querySelectorAll('[data-content] .scl-rl.follow').length,
      any: document.querySelectorAll('[data-content] .follow').length,
    }));
    const wireLit = async (f, t) => (await lit()).wires.some(([a, b]) => a === f && b === t);
    const watchChild = () => page.evaluate(() => {
      const S = window.__folSmoke = { child: 0, attrs: 0 };
      S.obs = new MutationObserver((rs) => { for (const r of rs) { if (r.type === 'childList') S.child++; else S.attrs++; } });
      S.obs.observe(document.querySelector('[data-content]'), { childList: true, subtree: true, attributes: true });
    });
    const unwatchChild = () => page.evaluate(() => { const S = window.__folSmoke; S.obs.disconnect(); return { child: S.child, attrs: S.attrs }; });
    const clickWire = async (f, t) => {
      const pt = await pointOn(`path[data-wire="${f}"][data-wire-to="${t}"]`);
      if (!pt) return false;
      await page.mouse.click(pt.x, pt.y);
      await page.waitForTimeout(120);
      return true;
    };
    // A point on the viewport's background (no element of the content under it).
    const bgPoint = () => page.evaluate(() => {
      const vp = document.querySelector('[data-content]').parentElement;
      const r = vp.getBoundingClientRect();
      for (let y = r.top + 12; y < r.bottom - 12; y += 17)
        for (let x = r.left + 12; x < r.right - 12; x += 23) {
          const h = document.elementFromPoint(x, y);
          if (h && vp.contains(h) && !h.closest('[data-pan-block], [data-no-pan], button, input')) return { x, y };
        }
      return null;
    });

    // The pure resolution, on every design: following each wire with an end
    // on an instance, the child's lit wires touch that port and following
    // one of them back lights the original wire.
    await toTop();
    const chk = await page.evaluate(() => (window.__socleFollowCheck ? window.__socleFollowCheck() : null));
    if (!chk) note(design, where, 'no window.__socleFollowCheck hook');
    else if (chk.bad.length) note(design, where, `follow resolution failed for ${chk.bad.length}/${chk.n} wires: ${chk.bad.slice(0, 4).join(' | ')}`);
    else { if (chk.n) followCheckDesigns.add(design); console.log(`[${design}] follow self-check: ${chk.n} wires round-trip`); }

    // The walk, on DeepSoc: cluster0.io.in <= io.in into cluster0, and back.
    const W = ['cluster0.io.in', 'io.in'];
    const other = ['pipe.io.enq', 'cluster0.io.out'];
    if (top && await pointOn(`path[data-wire="${W[0]}"][data-wire-to="${W[1]}"]`)) {
      followDesigns.add(design);
      await page.locator('button[title="Reset to 100%"]').first().click().catch(() => {});
      await page.locator('button[title="Fit to view"]').first().click().catch(() => {});
      await settle();
      await watchChild();
      await clickWire(...W);
      const m0 = await unwatchChild();
      let L = await lit();
      if (!await wireLit(...W)) note(design, where, `clicking ${W.join(' <= ')} did not follow it: ${fmt(L)}`);
      if (L.wires.some(([f, t]) => f === other[0] && t === other[1])) note(design, where, `following ${W[0]} also lit ${other[0]}`);
      if (!L.pins.includes(W[0])) note(design, where, `the followed wire's pin ${W[0]} is not lit: ${fmt(L.pins)}`);
      if (m0.child) note(design, where, `following a wire changed the content's child list (${m0.child} records)`);
      const st = await page.evaluate(() => (window.__socleFollow ? window.__socleFollow() : null));
      if (!st || !st.net || st.net.from !== W[0] || st.net.to !== W[1] || st.net.origin !== top)
        note(design, where, `window.__socleFollow() does not name the clicked wire at ${top}: ${fmt(st)}`);
      // The click both selects and follows (the net outlives the selection,
      // which a drill clears).
      const sel = await page.evaluate(() => document.querySelectorAll('[data-content] .scl-w.hl').length);
      if (!sel) note(design, where, 'the clicked wire is not selected too');

      // Resting on a box with a followed net mutates nothing.
      const bpt = await pointOn('[data-box-inst]');
      if (bpt) {
        await page.mouse.move(bpt.x, bpt.y);
        await page.waitForTimeout(200);
        await watchChild();
        await page.waitForTimeout(1000);
        const m = await unwatchChild();
        if (m.child || m.attrs) note(design, where, `resting on a box with a followed net mutated the schematic (${m.child} childList, ${m.attrs} attribute records)`);
      }
      // A drag from the background pans and keeps the net.
      const bg = await bgPoint();
      if (!bg) note(design, where, 'no background point to drag from');
      else {
        await page.mouse.move(bg.x, bg.y);
        await page.mouse.down();
        for (let i = 1; i <= 6; i++) { await page.mouse.move(bg.x + 6 * i, bg.y + 4 * i); await page.waitForTimeout(16); }
        await page.mouse.up();
        await page.waitForTimeout(120);
        if (!await wireLit(...W)) note(design, where, 'a drag from the background cleared the followed net');
      }

      // Into cluster0: the child's wires on io.in light, nothing else.
      await go(`${top}/cluster0`);
      L = await lit();
      if (!L.wires.length) note(design, where, `drilling into cluster0 lit no wire on its io.in port: ${fmt(L)}`);
      const stray = L.wires.filter(([f, t]) => !ov(f, 'io.in') && !ov(t, 'io.in'));
      if (stray.length) note(design, where, `in cluster0, wires off the io.in port are lit: ${fmt(stray.slice(0, 4))}`);
      if (!L.stubs.some((s) => ov(s, 'io.in')) && !L.pins.some((p) => ov(p, 'io.in')))
        note(design, where, `in cluster0, no boundary pin of io.in is lit: ${fmt(L)}`);
      const unlit = await page.evaluate(() => document.querySelectorAll('[data-content] .scl-w:not(.follow)').length);
      if (!unlit) note(design, where, 'in cluster0 every wire is lit');
      // cores_0's io.in is one closed row (#332): its pin lights.
      if (!L.pins.some((p) => ov(p, 'cores_0.io.in'))) note(design, where, `no pin of cores_0.io.in is lit: ${fmt(L.pins)}`);

      // Out again: the original wire.
      await go(top);
      if (!await wireLit(...W)) note(design, where, `drilling out did not light ${W.join(' <= ')} again: ${fmt(await lit())}`);
      // Its bundle pin lights collapsed, and still lights (with its field
      // rows) once expanded.
      const pin = W[0];
      if (!(await lit()).pins.includes(pin)) note(design, where, `the collapsed pin ${pin} is not lit`);
      const tg = page.locator(`[data-bundle-toggle="${pin}"]`).first();
      // An earlier step may have left it open: start from it closed.
      const fieldRows = () => page.evaluate((p) => document.querySelectorAll(`[data-row^="${CSS.escape(p)}."]`).length, pin);
      // The pin opens it, in the overview too (a faded label takes no clicks, #334).
      const tgPin = page.locator(`[data-pin="${pin}"]`).first();
      if (await tg.count() && await fieldRows()) { await tgPin.click(); await settle(); }
      if (await tg.count()) {
        await tgPin.click();
        await settle();
        const E = await lit();
        if (!E.pins.includes(pin)) note(design, where, `the expanded pin ${pin} is not lit: ${fmt(E.pins)}`);
        if (!E.rows) note(design, where, `no field row of the expanded ${pin} is lit`);
        if (!E.wires.length) note(design, where, 'expanding a bundle unlit the followed wire');
        await tgPin.click();
        await settle();
      } else note(design, where, `no bundle toggle on ${pin}`);
      // Back (into cluster0) and Forward (the top) restore it.
      await focusSchematic();
      await page.keyboard.press('Alt+ArrowLeft');
      await settle();
      if (await crumbs() !== `${top} > cluster0`) note(design, where, `Back did not return to cluster0 (${await crumbs()})`);
      else if (!(await lit()).wires.length) note(design, where, 'Back into cluster0 lit no wire');
      await focusSchematic();
      await page.keyboard.press('Alt+ArrowRight');
      await settle();
      if (!await wireLit(...W)) note(design, where, `Forward to the top did not light ${W.join(' <= ')}`);
      // A sibling instance lights nothing (its io.in is another port).
      await go(`${top}/cluster1`);
      L = await lit();
      if (L.any) note(design, where, `cluster1 lit ${L.any} elements for cluster0's net: ${fmt(L)}`);
      await go(top);

      // Esc clears it, by classes only.
      await focusSchematic();
      await watchChild();
      await page.keyboard.press('Escape');
      await page.waitForTimeout(80);
      const mE = await unwatchChild();
      L = await lit();
      if (L.any) note(design, where, `Escape did not clear the followed net (${L.any} lit)`);
      if (mE.child) note(design, where, `clearing the net changed the content's child list (${mE.child} records)`);

      // An Esc that closes the "?" card is the card's: the net stays lit; the
      // next Esc stops following.
      if (await clickWire(...W)) {
        await focusSchematic();
        await page.keyboard.press('Shift+Slash');
        await page.waitForTimeout(60);
        if (!await page.locator('[data-help-overlay]').count()) note(design, where, '? did not open the card over a followed net');
        else {
          await page.keyboard.press('Escape');
          await page.waitForTimeout(80);
          if (await page.locator('[data-help-overlay]').count()) note(design, where, 'Esc did not close the ? card');
          if (!await wireLit(...W)) note(design, where, 'the Esc that closed the ? card also stopped following the net');
          else escCardDesigns.add(design);
          await focusSchematic();
          await page.keyboard.press('Escape');
          await page.waitForTimeout(80);
          if ((await lit()).any) note(design, where, 'a second Esc did not stop following the net');
        }
      } else note(design, where, `no visible wire ${W.join(' <= ')} for the Esc-over-the-card check`);

      // Up a level: following io.in in cluster0 lights the top wire and its
      // collapsed bundle pin cluster0.io.in.
      await go(`${top}/cluster0`);
      if (await clickWire('cores_0.io.in', 'io.in')) {
        await go(top);
        if (!await wireLit(...W)) note(design, where, `following io.in in cluster0 did not light ${W.join(' <= ')} at the top`);
        if (!(await lit()).pins.includes(W[0])) note(design, where, `following io.in did not light the collapsed pin ${W[0]}`);
      } else { note(design, where, 'no visible wire cores_0.io.in <= io.in in cluster0'); await go(top); }
      await focusSchematic();
      await page.keyboard.press('Escape');

      // The other end: pipe.io.enq <= cluster0.io.out lights io.out in cluster0, never io.in.
      if (await clickWire(...other)) {
        await go(`${top}/cluster0`);
        L = await lit();
        if (!L.wires.length) note(design, where, `following ${other.join(' <= ')} lit nothing in cluster0`);
        const off = L.wires.filter(([f, t]) => !ov(f, 'io.out') && !ov(t, 'io.out'));
        if (off.length) note(design, where, `following cluster0.io.out lit wires off io.out in cluster0: ${fmt(off.slice(0, 4))}`);
        await go(top);
      } else note(design, where, `no visible wire ${other.join(' <= ')}`);

      // A click on the background clears it.
      const bg2 = await bgPoint();
      if (bg2) {
        if (!(await lit()).any) await clickWire(...W);
        await page.mouse.click(bg2.x, bg2.y);
        await page.waitForTimeout(120);
        L = await lit();
        if (L.any) note(design, where, `a click on the background did not clear the net (${L.any} lit)`);
      }
      await focusSchematic();
      await page.keyboard.press('Escape');
      await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
      await smokeReloadNav(design, ctx, top, W);
    }
    drain(where);
  }

  // The Source tab renders its file once (perf): a selection, or a drill to a
  // module of the same file, only moves the highlighted lines. Count the nodes
  // the change touches in the bottom panel's body (added, removed, or with a
  // changed attribute); a rebuild of the file is thousands.
  {
    const where = 'source render-once';
    const lines = await page.locator('.scl-bottom-body [data-line]').count();
    if (lines) {
      const watch = () => page.evaluate(() => {
        const S = window.__srcSmoke = { nodes: new Set(), added: 0, removed: 0 };
        S.obs = new MutationObserver((rs) => {
          for (const r of rs) {
            // A node and everything under it: replacing the tab is one record of thousands of nodes.
            const size = (n) => 1 + (n.getElementsByTagName ? n.getElementsByTagName('*').length : 0);
            if (r.type === 'childList') {
              for (const n of r.addedNodes) S.added += size(n);
              for (const n of r.removedNodes) S.removed += size(n);
            }
            else S.nodes.add(r.target);
          }
        });
        S.obs.observe(document.querySelector('.scl-bottom-body'), { childList: true, subtree: true, attributes: true, characterData: true });
      });
      const unwatch = () => page.evaluate(() => {
        const S = window.__srcSmoke; S.obs.disconnect();
        return { touched: S.added + S.removed + S.nodes.size, added: S.added, removed: S.removed, attrs: S.nodes.size };
      });
      // Highlighted lines: their number cell has a background.
      const lit = () => page.evaluate(() => Array.from(document.querySelectorAll('.scl-bottom-body [data-line]'))
        .filter((el) => getComputedStyle(el).backgroundColor !== 'rgba(0, 0, 0, 0)').length);
      const changes = [
        ['selecting a wire', async () => { await page.locator('path[data-wire]').first().dispatchEvent('click'); }],
        ['deselecting a wire', async () => { await page.locator('path[data-wire]').first().dispatchEvent('click'); }],
        ['selecting a box', async () => { await page.locator('[data-box-inst] > div:first-child').first().dispatchEvent('click'); }],
        ['deselecting a box', async () => { await page.locator('[data-box-inst] > div:first-child').first().dispatchEvent('click'); }],
      ];
      let most = 0;
      for (const [what, act] of changes) {
        if (/wire/.test(what) && !(await page.locator('path[data-wire]').count())) continue;
        if (/box/.test(what) && !(await page.locator('[data-box-inst] > div:first-child').count())) continue;
        await watch();
        await act();
        await page.waitForTimeout(250);
        const m = await unwatch();
        most = Math.max(most, m.touched);
        if (m.touched > 50) note(design, where, `${what} touched ${m.touched} nodes of the Source tab (${m.added} added, ${m.removed} removed, ${m.attrs} attributes); expected at most 50`);
        if (!(await lit())) note(design, where, `no highlighted line after ${what}`);
      }
      // A drill into a child module of the same file, and back.
      const hier = await page.locator('[data-hier-path]').evaluateAll((els) => els.map((e) => e.getAttribute('data-hier-path')));
      if (hier.length > 1) {
        for (const p of [hier[1], hier[0]]) {
          await watch();
          await page.locator(`[data-hier-path="${p}"]`).first().click();
          await page.waitForTimeout(400);
          const m = await unwatch();
          const n = await page.locator('.scl-bottom-body [data-line]').count();
          if (n === lines) most = Math.max(most, m.touched);
          if (n === lines && m.touched > 50) note(design, where, `drilling to ${p} touched ${m.touched} nodes of the Source tab (${m.added} added, ${m.removed} removed, ${m.attrs} attributes); expected at most 50`);
          if (n && !(await lit())) note(design, where, `no highlighted line after drilling to ${p}`);
        }
      }
      console.log(`[${design}] source render-once checked (${lines} lines, at most ${most} nodes touched)`);
    }
    drain(where);
  }

  // Export (SVG, PNG): the same with a selection as without (the selection is
  // cleared for the capture, #131), and styled as on screen: the highlight
  // styles live in a stylesheet now, which the export must carry.
  {
    const where = 'export';
    const grab = async (btn) => {
      const [dl] = await Promise.all([
        page.waitForEvent('download', { timeout: 10000 }),
        page.locator(btn).first().click(),
      ]);
      const p = await dl.path();
      return (await import('node:fs')).readFileSync(p);
    };
    const wireSel = '[data-content] > svg > g';
    if (await page.locator('[data-export-svg]').count() && await page.locator(wireSel).count()) {
      try {
        const svgPlain = (await grab('[data-export-svg]')).toString();
        const pngPlain = await grab('[data-export-png]');
        await page.locator('path[data-wire]').first().dispatchEvent('click');
        await page.waitForTimeout(100);
        const svgSel = (await grab('[data-export-svg]')).toString();
        const pngSel = await grab('[data-export-png]');
        await page.locator('path[data-wire]').first().dispatchEvent('click');
        await page.waitForTimeout(100);
        if (svgSel !== svgPlain) note(design, where, 'the SVG export with a selection differs from the one without');
        if (!pngSel.equals(pngPlain)) note(design, where, 'the PNG export with a selection differs from the one without');
        // Render the SVG on its own: a wire is drawn in ink2 at rest, its halo
        // faint, and a box carries the theme border.
        const look = async (svgText) => {
          const p2 = await ctx.newPage();
          try {
            await p2.setContent(svgText);
            return await p2.evaluate(() => {
              const wrap = document.querySelector('foreignObject > div');
              const probe = document.createElement('span');
              wrap.appendChild(probe);
              const col = (v) => { probe.style.color = `var(${v})`; return getComputedStyle(probe).color; };
              const g = document.querySelector('[data-content] > svg > g');
              const [halo, stroke] = g.querySelectorAll(':scope > path');
              const box = document.querySelector('[data-box-inst]');
              return { stroke: getComputedStyle(stroke).stroke, haloOp: getComputedStyle(halo).opacity,
                ink2: col('--ink2'), border: col('--border'),
                boxBorder: box ? getComputedStyle(box).borderTopColor : null,
                boxIsSymbol: box ? box.hasAttribute('data-logic-node') || box.hasAttribute('data-fsm') : true };
            });
          } finally { await p2.close(); }
        };
        const L = await look(svgPlain);
        if (L.stroke !== L.ink2) note(design, where, `exported wire stroke ${L.stroke}, expected ink2 ${L.ink2}`);
        if (L.haloOp !== '0.07') note(design, where, `exported wire halo opacity ${L.haloOp}, expected 0.07`);
        if (!L.boxIsSymbol && L.boxBorder !== L.border) note(design, where, `exported box border ${L.boxBorder}, expected ${L.border}`);
        // Colorized wires export in their colors.
        await page.locator('[data-settings-toggle]').first().click();
        await page.waitForTimeout(80);
        const cw = page.locator('[data-toggle-colorize-wires]').first();
        if (await cw.count()) {
          await cw.click();
          await page.waitForTimeout(80);
          const C = await look((await grab('[data-export-svg]')).toString());
          if (C.stroke === C.ink2) note(design, where, 'with colorize wires on, the exported wire is still ink2');
          await cw.click();
          await page.waitForTimeout(80);
        } else note(design, where, 'no [data-toggle-colorize-wires] in the settings panel');
        await page.locator('[data-settings-toggle]').first().click().catch(() => {});
        await page.waitForTimeout(80);
      } catch (e) { note(design, where, e.message); }
    }
    drain(where);
  }

  // Expand every bundle port in the top view.
  const toggles = await page.locator('[data-bundle-toggle]').all();
  console.log(`[${design}] ${toggles.length} bundle toggles in top view`);
  // #237, #332: a bundle whose fields are wired to different peers shows its
  // declared fields on their own sides, once each, with no group labels and no
  // replicated bundle on the peers. The `scatter` design's producer `p.io`
  // is the case: outputs a, b EAST, input c WEST.
  // #334: an overview at fit. Box names readable (at least 12px on screen),
  // pin labels hidden; at 1:1 the detail is back; fit again, the overview.
  if (design === 'rvpipeline' || design === 'bigarray') {
    const where = 'overview at fit (#334)';
    const click = (t) => page.locator(`button[title="${t}"]`).first().click().catch(() => {});
    const state = () => page.evaluate(() => {
      const c = document.querySelector('[data-content]');
      const z = parseFloat((c.style.transform.match(/scale\(([-\d.e]+)\)/) || [])[1] || '1');
      const names = Array.from(document.querySelectorAll('[data-box-overview-name]')).filter((e) => getComputedStyle(e).display !== 'none');
      const px = names.length ? parseFloat(getComputedStyle(names[0]).fontSize) * z : 0;
      const overflow = names.filter((e) => e.scrollWidth > e.clientWidth + 1).length;
      const label = document.querySelector('.scl-rlab');
      return { ov: c.classList.contains('ov'), names: names.length, px, overflow, labelShown: !!label && getComputedStyle(label).opacity !== '0' };
    });
    await click('Fit to view'); await page.waitForTimeout(400);
    const a = await state();
    if (!a.ov) note(design, where, `at fit, no overview: ${JSON.stringify(a)}`);
    else {
      // Readable where the boxes have room (BigArray's are smaller than the text).
      if (!a.names || (design === 'rvpipeline' && a.px < 12)) note(design, where, `at fit, box names not readable: ${JSON.stringify(a)}`);
      if (a.overflow) note(design, where, `at fit, ${a.overflow} box names overflow their box`);
      if (a.labelShown) note(design, where, 'at fit, pin labels still shown');
    }
    // An export from the overview has the full detail (no overview class, no --z).
    if (a.ov && await page.locator('[data-export-svg]').count()) {
      const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }), page.locator('[data-export-svg]').first().click()]);
      const svg = (await import('node:fs')).readFileSync(await dl.path()).toString();
      if (/class="[^"]*\bov\b/.test(svg) || /--z:/.test(svg)) note(design, where, 'an SVG exported from the overview keeps the overview (pin text faded)');
    }
    // In the overview, a faded label does not take clicks: a click on a box's
    // name never opens a bundle the user cannot see.
    if (a.ov && design === 'rvpipeline') {
      const pe = await page.evaluate(() => { const l = document.querySelector('[data-box-inst="decode"] .scl-rlab'); return l ? getComputedStyle(l).pointerEvents : null; });
      if (pe !== 'none') note(design, where, `a faded row label takes clicks in the overview (pointer-events ${pe})`);
    }
    // In the overview, a bundle wire's type is readable (at least 9px on screen)
    // and an edge stub's label fades like the pin text.
    if (a.ov) {
      const ov = await page.evaluate(() => {
        const c = document.querySelector('[data-content]');
        const z = parseFloat((c.style.transform.match(/scale\(([-\d.e]+)\)/) || [])[1] || '1');
        const wl = document.querySelector('.scl-wl:not(.sig)');
        const st = document.querySelector('.scl-stubl');
        return { wirePx: wl ? parseFloat(getComputedStyle(wl).fontSize) * z : null, stubOpacity: st ? getComputedStyle(st).opacity : null };
      });
      if (ov.wirePx != null && ov.wirePx < 9) note(design, where, `a bundle wire label is ${ov.wirePx.toFixed(1)}px at fit`);
      if (ov.stubOpacity != null && ov.stubOpacity !== '0') note(design, where, `an edge stub label is not faded in the overview (opacity ${ov.stubOpacity})`);
    }
    await click('Reset to 100%'); await page.waitForTimeout(400);
    const b = await state();
    if (b.ov || !b.labelShown) note(design, where, `at 1:1, the detail is not back: ${JSON.stringify(b)}`);
    await click('Fit to view'); await page.waitForTimeout(400);
    if (!(await state()).ov) note(design, where, 'lod toggles back: fit again shows no overview');
    // Leave a view the user set, as the checks below expect (an automatic fit
    // refits when a bundle grows the canvas, #207).
    await click('Reset to 100%'); await page.waitForTimeout(300);
  }
  // Clock domains: AXISystem served again with `periph` on its own clock. A
  // click on a legend row (or a coloured clk pin) fades the other domain's
  // instances; a second click, or a click on the background, lights none.
  if (design === 'axisystem') {
    const where = 'clock domains';
    const orig = await (await page.request.get(`${BASE}/${design}/design.json`)).text();
    const d = JSON.parse(orig);
    for (const c of d.modules.find((m) => m.name === d.top).children) if (c.name === 'periph') c.clock = 'periphClk';
    const serve = async (body, stamp) => {
      await page.unroute('**/design.json').catch(() => {});
      await page.route('**/design.json', (rt) => rt.fulfill({ contentType: 'application/json', body,
        headers: { 'Last-Modified': `Wed, 01 Jan ${stamp} 00:00:00 GMT` } }));
    };
    await serve(JSON.stringify(d), 2032);
    const shown = await page.waitForSelector('[data-clock-legend] [data-legend-domain="periphClk"]', { timeout: 10000 }).then(() => true).catch(() => false);
    if (!shown) note(design, where, 'no legend for two domains');
    else {
      await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      const faded = () => page.evaluate(() => Array.from(document.querySelectorAll('[data-box-inst].domoff')).map((b) => b.getAttribute('data-box-inst')).sort().join(','));
      if (!(await page.locator('[data-box-domain="periphClk"]').count())) note(design, where, 'periph has no domain stripe');
      await page.locator('[data-legend-domain="periphClk"]').click();
      await page.waitForTimeout(150);
      const lit = await faded();
      if (lit !== 'cpu,dma,mem,xbar') note(design, where, `legend click fades ${JSON.stringify(lit)}, not the clock domain`);
      await page.locator('[data-legend-domain="periphClk"]').click();
      await page.waitForTimeout(150);
      if (await faded()) note(design, where, 'a second click leaves boxes faded');
      // A real click: a synthetic one after an earlier drag is swallowed (no pointerdown).
      await page.locator('[data-clk-domain="clock"]').first().click({ timeout: 3000 }).catch((e) => note(design, where, `clk pin click: ${e.message}`));
      await page.waitForTimeout(150);
      if ((await faded()) !== 'periph') note(design, where, `clk pin click fades ${JSON.stringify(await faded())}, not periph`);
      await page.keyboard.press('Escape');
    }
    await serve(orig, 2033);
    await page.waitForFunction(() => !document.querySelector('[data-clock-legend]'), null, { timeout: 10000 })
      .catch(() => note(design, where, 'restore: the legend stays with one domain'));
    await page.unroute('**/design.json');
    await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
    drain(where);
  }
  // #334: a box's anatomy. No + or − text, clk and rst pins at the bottom of
  // the West side (no header marks), and an open bundle's fields hang from a guide line.
  if (design === 'rvpipeline') {
    const where = 'box anatomy (#334)';
    const a = await page.evaluate(() => {
      const b = document.querySelector('[data-box-inst="decode"]');
      if (!b) return null;
      const texts = Array.from(b.querySelectorAll('*')).filter((e) => e.children.length === 0).map((e) => e.textContent.trim());
      const clk = b.querySelector('[data-clk-pin]');
      const rst = b.querySelector('[data-rst-pin]');
      const hd = b.firstElementChild.getBoundingClientRect();
      const bottom = (e) => !!e?.firstElementChild && e.firstElementChild.getBoundingClientRect().top > hd.bottom;
      return { pm: texts.filter((t) => t === '+' || t === '−').length, clk: bottom(clk), rst: bottom(rst),
        marks: !!b.querySelector('[data-clk-cue], [data-rst-cue]') };
    });
    if (!a) note(design, where, 'no decode box');
    else {
      if (a.pm) note(design, where, `decode shows ${a.pm} + or − marks`);
      if (!a.clk || !a.rst) note(design, where, 'decode shows no clk or rst pin below its header');
      if (a.marks) note(design, where, 'decode still shows the header clock marks');
      const t = page.locator('[data-bundle-toggle="decode.io.in"]').first();
      const guides = () => page.locator('[data-box-inst="decode"] [data-row-guide]').count();
      // A real press, not a dispatched click: after a drag (an earlier check
      // pans) the schematic drops a click no press started.
      const press = async () => {
        const r = await t.boundingBox();
        if (r) await page.mouse.click(r.x + r.width / 2, r.y + r.height / 2); else await t.dispatchEvent('click');
      };
      if (await t.count()) {
        if (!(await guides())) {
          await press();
          await page.waitForTimeout(400);
          await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
          await page.waitForTimeout(300);
        }
        if (!(await guides())) note(design, where, 'an open decode.io.in draws no guide line');
        await press();
        await page.waitForTimeout(400);
        await page.waitForSelector('[data-layout-ready="1"]', { timeout: 15000 }).catch(() => {});
      } else note(design, where, 'no toggle on decode.io.in');
    }
  }
  if (design === 'scatter') {
    const pRows = await page.$$eval('[data-box-inst="p"] [data-row]', (els) =>
      els.map((e) => e.getAttribute('data-row-side') + ':' + e.getAttribute('data-row')));
    const want = ['EAST:p.io.a', 'EAST:p.io.b', 'WEST:p.io.c'];
    if (JSON.stringify([...pRows].sort()) !== JSON.stringify(want))
      note(design, 'scattered fields (#237)', `expected p rows ${JSON.stringify(want)}, got ${JSON.stringify(pRows)}`);
    if (await page.locator('[data-bundle-group]').count())
      note(design, 'scattered fields (#237)', 'a [data-bundle-group] row is still drawn');
    if (toggles.length > 0) note(design, 'scattered-replica (#237)', `expected 0 bundle-toggles on a scattered design, got ${toggles.length}`);
  }
  // Clock domains on registers: SpiSlave's registers run on `rise` and `fall`
  // (derived from io.sclk), reset by `ssReset` (from io.ss). Each register
  // shows its domain's bar, the legend lists both, each derived clock or reset
  // ends on its stub, and a legend click dims the other domain's registers.
  if (design === 'domains') {
    const where = 'clock domains';
    const st = await page.evaluate(() => ({
      legend: document.querySelectorAll('[data-clock-legend]').length,
      domains: [...document.querySelectorAll('[data-legend-domain]')].map((e) => e.getAttribute('data-legend-domain')),
      bars: [...document.querySelectorAll('[data-reg-domain]')].map((e) => e.getAttribute('data-reg-domain')),
      stubs: [...document.querySelectorAll('[data-domain-stub]')].map((e) => e.getAttribute('data-domain-stub')),
    }));
    if (st.legend !== 1) note(design, where, `expected one clock legend, got ${st.legend}`);
    if (JSON.stringify([...st.domains].sort()) !== JSON.stringify(['fall', 'rise']))
      note(design, where, `expected legend domains fall, rise; got ${JSON.stringify(st.domains)}`);
    if (!st.bars.includes('rise') || !st.bars.includes('fall'))
      note(design, where, `expected register bars on rise and fall, got ${JSON.stringify(st.bars)}`);
    for (const s of ['rise', 'fall', 'ssReset'])
      if (!st.stubs.includes(s)) note(design, where, `no domain stub for ${s} (got ${JSON.stringify(st.stubs)})`);
    const legendRise = page.locator('[data-legend-domain="rise"]').first();
    if (await legendRise.count()) {
      await legendRise.dispatchEvent('click');
      await page.waitForTimeout(300);
      const lit = await page.evaluate(() => {
        const off = (sel) => [...document.querySelectorAll(sel)].map((e) => e.closest('[data-box-inst]'))
          .filter(Boolean).map((b) => b.classList.contains('domoff'));
        return { fall: off('[data-reg-domain="fall"]'), rise: off('[data-reg-domain="rise"]') };
      });
      if (!lit.fall.length || !lit.fall.every(Boolean)) note(design, where, `lighting rise leaves a fall register undimmed: ${JSON.stringify(lit.fall)}`);
      if (lit.rise.some(Boolean)) note(design, where, `lighting rise dims a rise register: ${JSON.stringify(lit.rise)}`);
      await legendRise.dispatchEvent('click');
      await page.waitForTimeout(300);
      if (await page.locator('.domoff').count()) note(design, where, 'a second legend click leaves boxes dimmed');
    }
    console.log(`[${design}] clock domains checked (${st.bars.length} register bars, stubs ${JSON.stringify(st.stubs)})`);
  }
  // A signal row shows its width once, never also its type (`UInt<8> 8b`).
  {
    const twice = await page.$$eval('.scl-rt', (els) => els.map((e) => e.textContent).filter((t) => /^[SU]Int<\d+> \d+b/.test(t)));
    if (twice.length) note(design, 'row types', `a signal row repeats its type: ${JSON.stringify(twice.slice(0, 3))}`);
  }
  // The module's scaladoc in the Inspector (DeepSoc documents its top class).
  if (design === 'deepsoc') {
    const doc = await page.locator('[data-scaladoc]').first().textContent().catch(() => null);
    if (!doc || !doc.startsWith('Top-level SoC')) note(design, 'scaladoc', `the Inspector shows no module scaladoc: ${JSON.stringify(doc)}`);
  }
  // #334: a bundle wire carries its type once.
  if (design === 'axisystem') {
    const labels = await page.$$eval('[data-wire-label]', (els) => els.map((e) => e.getAttribute('data-wire-label')));
    if (!labels.includes('AXI4')) note(design, 'wire labels (#334)', `no AXI4 wire label, got ${JSON.stringify(labels)}`);
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
    // Mouse actions per element next to the keys, the navigation keys included.
    const card = await page.evaluate(() => {
      const o = document.querySelector('[data-help-overlay]');
      return { sections: Array.from(o.querySelectorAll('[data-help-section]')).map((e) => e.getAttribute('data-help-section')),
               text: o.innerText };
    });
    for (const s of ['Anywhere', 'Blocks', 'Wires', 'Pins', 'Logic blocks', 'Schematic'])
      if (!card.sections.includes(s)) note(design, 'keyboard help', `no "${s}" section in the help card (${card.sections.join(', ')})`);
    for (const k of ['Double-click', 'Click title', 'Middle drag', 'Shift + wheel', 'Ctrl + wheel', '+ / -', '0 / f', 'Zoom to the selection', 'Alt ←', 'Alt →', 'side buttons', 'in its parent', 'Select and follow the net', 'stop following a net'])
      if (!card.text.includes(k)) note(design, 'keyboard help', `the help card does not mention "${k}"`);
    if (/[\u2013\u2014]/.test(card.text)) note(design, 'keyboard help', 'the help card has an en or em dash');
    const helpTitle = await page.locator('[data-help-trigger]').first().getAttribute('title').catch(() => null);
    if (helpTitle && !/^Mouse and keyboard/.test(helpTitle)) note(design, 'keyboard help', `the ? button is titled ${JSON.stringify(helpTitle)}`);
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
      // Hidden, the debug band is not drawn and adds no room to the view.
      const canvas = () => page.evaluate(() => { const c = document.querySelector('[data-content]'); return { w: parseFloat(c.style.width), h: parseFloat(c.style.height) }; });
      const c0 = await canvas();
      if (await page.locator('[data-debug-band]').count()) note(design, 'debug band', 'the debug band is drawn with layers hidden');
      await toggle.click().catch((e) => note(design, 'layers toggle', e.message));
      await page.waitForTimeout(250);
      const after = await page.locator('[data-box-inst]').count();
      if (after <= before) note(design, 'layers toggle (#212)', `showing layers did not add boxes (${before} -> ${after})`);
      // Shown, layer content sits in a band below the design.
      const band = await page.evaluate(() => {
        const b = document.querySelector('[data-debug-band]');
        return b ? parseFloat(b.style.top) : null;
      });
      const c1 = await canvas();
      if (band == null) note(design, 'debug band', 'no debug band with layers shown');
      else if (!(c1.h > c0.h)) note(design, 'debug band', `showing layers did not grow the view (${c0.h} -> ${c1.h})`);
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
  console.log(`[${design}] ${await loadLog()}`);
}

// The performance guard, on a design of hundreds of boxes: what the pointer
// does costs the same there as on a small design. A hover restyles the box and
// its wires only (a few attribute changes, not one per box), a rest changes
// nothing, a wheel zoom and a drag pan change the content's transform (and
// light no more than a hover, for what comes under the pointer), a selection
// toggles a few classes; nothing rebuilds the schematic, and no long
// task (over PERF_LONG_MS) runs meanwhile. Counts, not timings, so a loaded
// runner cannot fail it; the timings are printed.
const PERF_MIN_BOXES = 200;
const PERF_HOVER_MAX = 24;  // attribute changes of a hover (3 on GCD and on BigArray's 256 boxes)
const PERF_SELECT_MAX = 48; // of a selection and its clearing
const PERF_LONG_MS = 250;
async function smokePerf(design) {
  const where = 'perf';
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  await ctx.addInitScript(() => { try { localStorage.setItem('socle-tips', 'done'); } catch {} });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
  try {
    const t0 = Date.now();
    await page.goto(`${BASE}/${design}/socle.html`);
    const shown = await page.waitForSelector('[data-box-inst]', { timeout: 60000 }).then(() => true).catch(() => false)
      && await page.waitForSelector('[data-layout-ready="1"]', { timeout: 60000 }).then(() => true).catch(() => false);
    if (!shown) { note(design, where, 'the schematic never showed laid out'); return; }
    await page.waitForTimeout(1000);
    const boxes = await page.locator('[data-box-inst]').count();
    console.log(`[${design}] perf: ${boxes} boxes shown in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
    if (boxes < PERF_MIN_BOXES) note(design, where, `only ${boxes} boxes shown, fewer than ${PERF_MIN_BOXES}: not a large design`);
    const watch = () => page.evaluate(() => {
      const c = document.querySelector('[data-content]');
      const S = window.__perfSmoke = { child: 0, attrs: 0, self: 0, long: [], t: performance.now() };
      S.obs = new MutationObserver((rs) => { for (const r of rs) { if (r.type === 'childList') S.child++; else if (r.target === c) S.self++; else S.attrs++; } });
      S.obs.observe(c, { childList: true, subtree: true, attributes: true });
      try { S.po = new PerformanceObserver((l) => { for (const e of l.getEntries()) S.long.push(Math.round(e.duration)); }); S.po.observe({ type: 'longtask' }); } catch {}
    });
    const unwatch = () => page.evaluate(() => {
      const S = window.__perfSmoke;
      S.obs.disconnect(); if (S.po) S.po.disconnect();
      return { child: S.child, attrs: S.attrs, self: S.self, long: S.long, ms: Math.round(performance.now() - S.t) };
    });
    // A box with a wire, fully on screen, and an empty corner of the viewport.
    const box = await page.evaluate(() => {
      for (const el of document.querySelectorAll('[data-box-inst]')) {
        const r = el.getBoundingClientRect();
        const x = r.left + r.width / 2, y = r.top + Math.min(10, r.height / 2);
        if (x > 0 && y > 0 && x < innerWidth && y < innerHeight && document.elementFromPoint(x, y)?.closest('[data-box-inst]') === el) return { x, y };
      }
      return null;
    });
    if (!box) { note(design, where, 'no box under the pointer to hover'); return; }
    const vp = await page.evaluate(() => {
      const r = document.querySelector('[data-content]').parentElement.getBoundingClientRect();
      return { x: r.right - 8, y: r.bottom - 8, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
    });
    const report = (what, m, max) => {
      console.log(`[${design}] perf ${what}: ${m.child} childList, ${m.attrs} attribute records, transform ${m.self}x, long tasks [${m.long.join(', ')}] in ${m.ms} ms`);
      if (m.child) note(design, where, `${what} rebuilt the schematic (${m.child} childList records)`);
      if (m.attrs > max) note(design, where, `${what} changed ${m.attrs} attributes in the schematic (at most ${max}): it touches more than what it lights`);
      const long = m.long.filter((d) => d > PERF_LONG_MS);
      if (long.length) note(design, where, `${what} ran long tasks of ${long.join(', ')} ms (over ${PERF_LONG_MS})`);
    };
    await page.mouse.move(vp.x, vp.y);
    await page.waitForTimeout(300);
    await watch(); await page.mouse.move(box.x, box.y); await page.waitForTimeout(300);
    report('a hover', await unwatch(), PERF_HOVER_MAX);
    await watch(); await page.waitForTimeout(1000);
    report('a rest on a box', await unwatch(), 0);
    await watch(); await page.mouse.click(box.x, box.y); await page.waitForTimeout(200); await page.mouse.click(vp.x, vp.y); await page.waitForTimeout(200);
    report('a selection and its clearing', await unwatch(), PERF_SELECT_MAX);
    await page.mouse.move(vp.cx, vp.cy); await page.waitForTimeout(300);
    await watch(); for (let i = 0; i < 10; i++) { await page.mouse.wheel(0, -100); await page.waitForTimeout(30); } await page.waitForTimeout(300);
    const wheel = await unwatch();
    report('a wheel zoom', wheel, PERF_HOVER_MAX);
    if (!wheel.self) note(design, where, 'the wheel did not zoom (no transform change)');
    await watch();
    await page.mouse.down(); for (let i = 1; i <= 20; i++) await page.mouse.move(vp.cx + i * 10, vp.cy + i * 5); await page.mouse.up();
    await page.waitForTimeout(300);
    const pan = await unwatch();
    report('a drag pan', pan, PERF_HOVER_MAX);
    if (!pan.self) note(design, where, 'the drag did not pan (no transform change)');
  } catch (e) {
    note(design, where, (e && e.message) || String(e));
  } finally {
    for (const e of errors) note(design, where, e);
    await ctx.close().catch(() => {});
  }
}

// A pool of CONCURRENCY workers takes the designs in manifest order. A throw
// inside one design is that design's failure; the others carry on.
const queue = designs.slice();
const worker = async () => {
  for (let design; (design = queue.shift()) !== undefined;) {
    const t0 = Date.now();
    try {
      await smokeDesign(design);
    } catch (e) {
      note(design, 'crash', (e && e.message) || String(e));
    }
    console.log(`[${design}] done in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  }
};
await Promise.all(Array.from({ length: Math.min(CONCURRENCY, designs.length) }, worker));
// Alone, after the others: a loaded browser would blur its timings.
for (const design of perfDesigns) await smokePerf(design);
if (!ONLY && perfDesigns.length === 0) note('(all)', 'perf', 'no performance-guard design in the manifest (bigarray)');

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
console.log(`Minimap checked on: ${Array.from(minimapDesigns).join(', ') || '(none)'}`);
if (!ONLY && minimapDesigns.size === 0) note('(all)', 'minimap', 'no design showed the minimap when zoomed in');
console.log(`Pin rest checked on: ${Array.from(pinRestDesigns).join(', ') || '(none)'}`);
if (!ONLY && pinRestDesigns.size === 0) note('(all)', 'highlight rest/select', 'no design had a visible pin to rest on');
console.log(`Gesture bundle toggle checked on: ${Array.from(gestureToggleDesigns).join(', ') || '(none)'}`);
if (!ONLY && gestureToggleDesigns.size === 0) note('(all)', 'gestures', 'no design had a clickable bundle toggle');
console.log(`Gesture double-click drill checked on: ${Array.from(gestureDrillDesigns).join(', ') || '(none)'}`);
if (!ONLY && gestureDrillDesigns.size === 0) note('(all)', 'gestures', 'no design had a drillable box to double-click');
console.log(`Gesture logic block click checked on: ${Array.from(gestureBlockDesigns).join(', ') || '(none)'}`);
if (!ONLY && gestureBlockDesigns.size === 0) note('(all)', 'gestures', 'no design had a logic block to click');
console.log(`Gesture drag across a navigation checked on: ${Array.from(gestureNavDesigns).join(', ') || '(none)'}`);
if (!ONLY && gestureNavDesigns.size === 0) note('(all)', 'gestures', 'no design had a child module to navigate to mid-drag');
console.log(`Hover box with wires checked on: ${Array.from(hoverWireDesigns).join(', ') || '(none)'}`);
if (!ONLY && hoverWireDesigns.size === 0) note('(all)', 'hover feedback', 'no hovered box had a connected wire to light');
console.log(`Hover card counts checked on: ${Array.from(hoverCountDesigns).join(', ') || '(none)'}`);
if (!ONLY && hoverCountDesigns.size === 0) note('(all)', 'hover feedback', 'no box card gave instance counts');
console.log(`Cursor zoom-in checked on: ${Array.from(cursorZoomDesigns).join(', ') || '(none)'}`);
if (!ONLY && cursorZoomDesigns.size === 0) note('(all)', 'hover feedback', 'no design had a drillable block for the cursor check');
console.log(`Cursor pointer on symbols and stubs checked on: ${Array.from(cursorPointerDesigns).join(', ') || '(none)'}`);
if (!ONLY && cursorPointerDesigns.size === 0) note('(all)', 'hover feedback', 'no design had a logic symbol or a stub for the cursor check');

console.log(`Hint bar checked on: ${Array.from(hintDesigns).join(', ') || '(none)'}`);
if (!ONLY && hintDesigns.size === 0) note('(all)', 'hover feedback', 'no design checked the hint bar against a card');
for (const [what, set] of [['History walk', historyDesigns], ['Fly to', flyDesigns], ['Fly to from another module', flyCrossDesigns],
                           ['Drag during a flight', flightCancelDesigns], ['Zoom to a selected wire', zoomWireDesigns],
                           ['Show in parent', locateDesigns], ['Palette pick without an editor open', pickNoOpenDesigns],
                           ['Drag dropping a waiting fly', stalePendingDesigns],
                           ['Back / Forward buttons', navButtonDesigns], ['Leaf drill with the tree following', leafDrillDesigns]]) {
  console.log(`${what} checked on: ${Array.from(set).join(', ') || '(none)'}`);
  if (!ONLY && set.size === 0) note('(all)', 'history', `no design ran the check: ${what}`);
}
for (const d of FSM_NAV_DESIGNS.filter((x) => designs.includes(x) && !fsmNavDesigns.has(x)))
  note(d, 'navigation', 'skipped the state diagram\'s Back / Forward check');
for (const [what, set] of [['the Back / Forward buttons', navButtonDesigns], ['the leaf drill with the tree following', leafDrillDesigns]])
  for (const d of NAV_DESIGNS.filter((x) => designs.includes(x) && !set.has(x)))
    note(d, 'navigation', `skipped ${what} (expected on ${NAV_DESIGNS.join(', ')})`);
for (const [what, set] of [['Follow a net', followDesigns], ['Follow resolution round trip', followCheckDesigns],
                           ['Reload keeps navigation, a fresh load clears it', reloadNavDesigns],
                           ['Esc closing the ? card keeps the net', escCardDesigns], ['Interface pin dot toggles', pinDotDesigns],
                           ['Field pin dot toggles', fieldDotDesigns]]) {
  console.log(`${what} checked on: ${Array.from(set).join(', ') || '(none)'}`);
  if (!ONLY && set.size === 0) note('(all)', 'follow net', `no design ran the check: ${what}`);
}


// The known toggle problems that no longer happen: the list must shrink with them.
if (!ONLY) for (const key of KNOWN_TOGGLE) {
  const d = key.slice(0, key.indexOf('|'));
  if (toggleDesigns.has(d) && !toggleSeen.has(key))
    note(d, 'bundle toggles', `fixed: remove ${key} from KNOWN_TOGGLE`);
}
console.log(`Bundle toggles checked on: ${Array.from(toggleDesigns).join(', ') || '(none)'}`);

// Concurrent designs interleave their failures: list them in manifest order
// (stable, so each design keeps its own order; '(all)' last).
const rank = (d) => { const i = designs.indexOf(d); return i < 0 ? designs.length : i; };
failures.sort((a, b) => rank(a.design) - rank(b.design));

console.log('\n=== SMOKE SUMMARY ===');
if (failures.length === 0) {
  console.log('OK - no page errors, crashes, or console errors.');
  process.exit(0);
} else {
  console.log(`${failures.length} problem(s):`);
  for (const f of failures) console.log(`  [${f.design}] ${f.where}: ${f.msg}`);
  process.exit(1);
}
