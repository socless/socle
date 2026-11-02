// Schematic timings (#331, #335): first view, a drill, a bundle open and close,
// measured to the layout-ready flag. Serve a site as for smoke.mjs (README).
// node timings.mjs <base-url> <design> <drill-path|-> [runs]   (CHROMIUM=/path as smoke.mjs)
// Prints medians (ms) and the view's size: boxes, edge stubs, rows.
import { chromium } from 'playwright-core';
import { existsSync } from 'node:fs';
const [BASE, design, drill, runsArg] = process.argv.slice(2);
const runs = +(runsArg || 3);
const EXEC = process.env.CHROMIUM || ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable'].find((p) => existsSync(p));
const browser = await chromium.launch(EXEC ? { executablePath: EXEC, args: ['--no-sandbox'] } : { args: ['--no-sandbox'] });
const med = (xs) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : null; };
const T = { first: [], drill: [], open: [], close: [] };
let size = null, toggled = null;
for (let r = 0; r < runs; r++) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 950 } });
  await ctx.addInitScript(() => { try { localStorage.setItem('socle-tips', 'done'); } catch {} });
  const page = await ctx.newPage();
  // Time to a laid-out view: the layout flag set (no pending engine run) with boxes drawn.
  const settled = async (t0) => {
    await page.waitForFunction(() => document.querySelector('[data-layout-ready="1"]') && document.querySelector('[data-box-inst], [data-block], [data-boundary-stub]'), null, { timeout: 120000, polling: 16 });
    return performance.now() - t0;
  };
  let t0 = performance.now();
  await page.goto(`${BASE}/${design}/socle.html`);
  T.first.push(await settled(t0));
  if (drill !== '-') {
    await page.waitForTimeout(500);
    t0 = performance.now();
    await page.locator(`[data-hier-path="${drill}"]`).first().dispatchEvent('click');
    await page.waitForTimeout(50); // the flag drops on the drill
    T.drill.push(await settled(t0));
  }
  await page.waitForTimeout(800);
  size = await page.evaluate(() => ({
    boxes: document.querySelectorAll('[data-box-inst]').length,
    blocks: document.querySelectorAll('[data-block], [data-logic-node]').length,
    stubs: document.querySelectorAll('[data-boundary-stub]').length,
    rows: document.querySelectorAll('[data-row]').length,
  }));
  // The first bundle toggle of the box with the most rows.
  const tg = await page.evaluate(() => {
    let best = null, n = -1;
    for (const b of document.querySelectorAll('[data-box-inst]')) {
      const t = b.querySelector('[data-bundle-toggle]');
      const k = b.querySelectorAll('[data-row]').length;
      if (t && k > n) { n = k; best = [b.getAttribute('data-box-inst'), t.getAttribute('data-bundle-toggle')]; }
    }
    return best;
  });
  if (tg) {
    toggled = tg.join(' ');
    for (const key of ['open', 'close']) {
      t0 = performance.now();
      await page.evaluate(([id, p]) => {
        const t = document.querySelector(`[data-box-inst="${CSS.escape(id)}"] [data-bundle-toggle="${CSS.escape(p)}"]`);
        t && t.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      }, tg);
      await page.waitForTimeout(16);
      T[key].push(await settled(t0));
      await page.waitForTimeout(500);
    }
  }
  await ctx.close();
}
await browser.close();
const r = (xs) => (xs.length ? Math.round(med(xs)) : '-');
console.log(`${design}${drill !== '-' ? ' > ' + drill : ''}: first view ${r(T.first)} ms, drill ${r(T.drill)} ms, open ${r(T.open)} ms, close ${r(T.close)} ms (median of ${runs}); ` +
  `${JSON.stringify(size)}; toggle ${toggled || 'none'}`);
