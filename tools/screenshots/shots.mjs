// README screenshots of the Socle UI, in one theme: three stills and the
// hero video, from the site run.sh assembles (pipeline/ and fsm/, each the
// single-file UI beside a view-mode design.json).
//
//   node shots.mjs <baseUrl> <dark|light> <outDir>
//
// Writes <outDir>/schematic-<theme>.png, inspector-<theme>.png,
// fsm-<theme>.png and the hero frames in <outDir>/frames-<theme>/<ms>.png. Fails on a page error, a
// console.error, or a UI in the other theme.

import { chromium } from 'playwright-core';
import { existsSync } from 'node:fs';
import { readdir, rm, mkdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

const [base, theme, outDir] = process.argv.slice(2);
if (!base || !['dark', 'light'].includes(theme) || !outDir) {
  console.error('usage: node shots.mjs <baseUrl> <dark|light> <outDir>');
  process.exit(2);
}
await mkdir(outDir, { recursive: true });

// Browser: CHROMIUM, else a system Chromium, else Playwright's managed one.
const managed = join(homedir(), '.cache/ms-playwright');
const candidates = [process.env.CHROMIUM, '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable'];
if (existsSync(managed)) {
  for (const d of (await readdir(managed)).filter((d) => /^chromium-\d+$/.test(d)).sort().reverse())
    candidates.push(join(managed, d, 'chrome-linux64/chrome'));
}
const executablePath = candidates.find((p) => p && existsSync(p));

const VIEW = { width: 1500, height: 980 };
const browser = await chromium.launch({ executablePath });
const errors = [];

async function newPage(opts = {}) {
  const ctx = await browser.newContext({ viewport: VIEW, ...opts });
  // The theme, and no first-run tips over the schematic.
  await ctx.addInitScript((t) => { localStorage.setItem('socle-theme', t); localStorage.setItem('socle-tips', 'done'); }, theme);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`page error: ${e.message}`));
  // As tools/ui-smoke: the generic "Failed to load resource" line has no URL; the
  // response handler reports 404s, except designs.json and ide.json (optional).
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(`console.error: ${m.text()}`);
  });
  page.on('response', (r) => {
    if (r.status() >= 400 && !/\/(designs|ide)\.json$/.test(new URL(r.url()).pathname)) errors.push(`HTTP ${r.status()}: ${r.url()}`);
  });
  return { ctx, page };
}

const settled = (page) => page.waitForSelector('[data-layout-ready="1"]', { timeout: 30000 });

async function open(page, design) {
  await page.goto(`${base}/${design}/socle.html`);
  await settled(page);
  // A light theme has no data-theme attribute.
  const t = await page.evaluate(() => document.documentElement.getAttribute('data-theme') || 'light');
  if (t !== theme) throw new Error(`${design}: UI theme ${t}, expected ${theme}`);
}

// A real pointer move to the element, then a click (the video shows the motion).
async function glideClick(page, locator, { dbl = false, position } = {}) {
  const box = await locator.boundingBox();
  if (!box) throw new Error('no box to click');
  const x = box.x + (position ? position.x : box.width / 2), y = box.y + (position ? position.y : box.height / 2);
  await page.mouse.move(x, y, { steps: 25 });
  if (dbl) await page.mouse.dblclick(x, y); else await page.mouse.click(x, y);
}

const exec = (page) => page.locator('[data-box-inst="exec"]').first();
const HEADER = { x: 20, y: 8 };   // a box's title strip: select
const BODY = { x: 40, y: 40 };    // its body: double-click opens it

async function openFsm(page) {
  await page.locator('[data-fsm-open]').first().click();
  await page.waitForSelector('[data-fsm-diagram][data-fsm-ready="1"]', { timeout: 10000 });
}

// 1. The pipeline with exec selected: its code lit in the Source tab.
{
  const { ctx, page } = await newPage();
  await open(page, 'pipeline');
  await exec(page).click({ position: HEADER });
  await page.waitForTimeout(600);
  // The Source tab opens on the module's class; exec's line is a few below.
  await page.mouse.move(700, VIEW.height - 60);
  await page.mouse.wheel(0, 120);
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${outDir}/schematic-${theme}.png` });

  // 2. Inside exec, nothing selected: the inspector sums up the module (type,
  // connects, registers, LOC, Scala class) and the Source tab shows its class.
  await exec(page).dblclick({ position: BODY });
  await page.waitForTimeout(300);
  await settled(page);
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${outDir}/inspector-${theme}.png` });
  await ctx.close();
}

// 3. TrafficFsm's state diagram.
{
  const { ctx, page } = await newPage();
  await open(page, 'fsm');
  await openFsm(page);
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${outDir}/fsm-${theme}.png` });
  await ctx.close();
}

// 4. The hero frames: select decode then exec (their code lights up), open
// exec, then the FSM diagram. Lossless screenshots in a loop (a lossy video
// changes every pixel of every frame and makes the GIF huge), each named by
// its time in ms; run.sh turns them into the GIF. A pointer drawn in the page
// shows where the clicks go (screenshots have no cursor).
{
  const framesDir = join(outDir, `frames-${theme}`);
  await rm(framesDir, { recursive: true, force: true });
  await mkdir(framesDir, { recursive: true });
  const { ctx, page } = await newPage();
  await ctx.addInitScript(() => {
    addEventListener('DOMContentLoaded', () => {
      const p = document.createElement('div');
      p.id = 'readme-pointer';
      p.innerHTML = '<svg width="22" height="22" viewBox="0 0 22 22"><path d="M2 2 L2 18 L7 13 L10 20 L13 19 L10 12 L17 12 Z" fill="white" stroke="black" stroke-width="1.5"/></svg>';
      Object.assign(p.style, { position: 'fixed', left: '-40px', top: '-40px', zIndex: 2147483647, pointerEvents: 'none' });
      document.body.appendChild(p);
      addEventListener('mousemove', (e) => { p.style.left = e.clientX + 'px'; p.style.top = e.clientY + 'px'; }, true);
    });
  });
  let recording = true;
  const t0 = Date.now();
  const recorder = (async () => {
    while (recording) {
      const t = Date.now() - t0;
      await page.screenshot({ path: join(framesDir, `${String(t).padStart(6, '0')}.png`) }).catch(() => {});
      await page.waitForTimeout(60).catch(() => {});
    }
  })();
  await open(page, 'pipeline');
  await page.mouse.move(700, 600);
  await page.waitForTimeout(900);
  await glideClick(page, page.locator('[data-box-inst="decode"]').first(), { position: HEADER });
  await page.waitForTimeout(1300);
  await glideClick(page, exec(page), { position: HEADER });
  await page.waitForTimeout(1600);
  await glideClick(page, exec(page), { dbl: true, position: BODY });
  await settled(page);
  await page.waitForTimeout(1800);
  await open(page, 'fsm');
  await page.mouse.move(700, 600);
  await page.waitForTimeout(700);
  await glideClick(page, page.locator('[data-fsm-open]').first());
  await page.waitForSelector('[data-fsm-diagram][data-fsm-ready="1"]', { timeout: 10000 });
  await page.waitForTimeout(2400);
  recording = false;
  await recorder;
  await ctx.close();
}

await browser.close();
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`shots: ${theme} done in ${outDir}`);
