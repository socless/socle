// SoCLess standalone-server smoke test (#194).
//
// The static smoke (smoke.mjs) loads a pre-baked design.json per design and so
// cannot exercise the design-INPUT layer: drag-dropping a .fir (which the UI
// POSTs to /convert) and the live poller (#120, which re-fetches design.json
// when `serve --watch` re-converts a changed .fir). This driver runs the real
// `socless.Launcher serve` from the assembled jar and checks all three input
// features that the Scala.js cutover dropped:
//
//   1. live source + LIVE badge   - `serve --watch a.fir` shows the design with
//                                    data-source="live" and a LIVE badge
//   2. live poller (#120)         - overwriting the watched .fir hot-reloads the
//                                    schematic in place (top module changes)
//   3. drag-drop .fir -> /convert - dropping a .fir on a plain `serve` renders it
//   4. drag-drop .json            - dropping a design.json renders it (no server)
//
// Usage:
//   node serve-smoke.mjs --jar <assembly.jar> --fixtures <chirrtl-dir> [--shots <dir>]
//
// Requires a system Chromium (or CHROMIUM=/path) and a built assembly jar
// (`mill socless.assembly`). Exit code is non-zero on any failure.

import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { writeFile, copyFile, mkdir, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import net from 'node:net';

const args = Object.fromEntries(
  process.argv.slice(2).join(' ').split('--').filter(Boolean).map((s) => {
    const [k, ...v] = s.trim().split(/\s+/);
    return [k, v.join(' ') || true];
  }),
);

const JAR = args.jar;
const FIXTURES = args.fixtures || 'socless/test/resources/chirrtl';
const SHOTS = args.shots || null;
if (!JAR || !existsSync(JAR)) {
  console.error(`serve-smoke: --jar <assembly.jar> required (got ${JAR}). Build with: mill socless.assembly`);
  process.exit(2);
}
if (SHOTS) await mkdir(SHOTS, { recursive: true });

const EXEC = process.env.CHROMIUM
  || ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome-stable'].find((p) => existsSync(p));

const failures = [];
const note = (where, msg) => { failures.push({ where, msg }); console.log(`  FAIL [${where}] ${msg}`); };

// Pick a free loopback port so parallel runs / a busy 8765 do not collide.
function freePort() {
  return new Promise((res, rej) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => res(port)); });
    srv.on('error', rej);
  });
}

// Start `java -jar JAR serve ...`, resolve once it answers on /SoCLess.html.
async function startServe(extra) {
  const port = await freePort();
  const proc = spawn('java', ['-jar', JAR, 'serve', '--port', String(port), '--no-open', ...extra],
    { stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  proc.stdout.on('data', (d) => { log += d; });
  proc.stderr.on('data', (d) => { log += d; });
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch(`${base}/SoCLess.html`);
      if (r.ok) return { proc, base, port, log: () => log };
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 125));
  }
  proc.kill('SIGKILL');
  throw new Error(`serve did not come up on ${base}\n${log}`);
}

// Drop a File built from `content` onto the page (bubbles to the window drop
// handler). Mirrors a real user dragging a file from the OS.
async function dropFile(page, content, name, type) {
  const dt = await page.evaluateHandle(({ content, name, type }) => {
    const d = new DataTransfer();
    d.items.add(new File([content], name, { type }));
    return d;
  }, { content, name, type });
  await page.dispatchEvent('body', 'dragenter', { dataTransfer: dt });
  await page.dispatchEvent('body', 'drop', { dataTransfer: dt });
}

const browser = await chromium.launch({ args: ['--no-sandbox'], ...(EXEC ? { executablePath: EXEC } : {}) });
const tmp = await mkdtemp(join(tmpdir(), 'socless-serve-smoke-'));

// ── 1+2. live source, LIVE badge, and the live poller (#120) ───────────────────
{
  const watched = join(tmp, 'watched.fir');
  await copyFile(join(FIXTURES, 'axisystem.fir'), watched);
  const srv = await startServe(['--watch', watched]);
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console.error: ' + m.text()); });
    await page.goto(`${srv.base}/SoCLess.html`, { waitUntil: 'networkidle' });
    await page.waitForSelector('[data-hier-path]', { timeout: 15000 }).catch(() => {});

    const src = await page.getAttribute('[data-source]', 'data-source').catch(() => null);
    if (src !== 'live') note('watch/source', `expected data-source="live", got ${src === null ? 'no element' : `"${src}"`}`);
    if (await page.locator('[data-live]').count() === 0) note('watch/badge', 'no LIVE badge ([data-live]) while serving --watch');

    const topBefore = await page.getAttribute('[data-top]', 'data-top').catch(() => null);

    // Overwrite the watched .fir with a DIFFERENT design; the watcher re-converts
    // (~1s poll) and the UI's live poller (1.5s) must reload in place.
    await copyFile(join(FIXTURES, 'gcd.fir'), watched);
    let topAfter = topBefore;
    for (let i = 0; i < 40; i++) {
      await page.waitForTimeout(500);
      topAfter = await page.getAttribute('[data-top]', 'data-top').catch(() => topBefore);
      if (topAfter && topAfter !== topBefore) break;
    }
    if (!topAfter || topAfter === topBefore) {
      note('live-poller', `schematic did not hot-reload after the watched .fir changed (top stayed "${topBefore}")`);
    }
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/serve-live.png` }).catch(() => {});
    while (errors.length) note('watch/console', errors.shift());
    await page.close();
  } finally { srv.proc.kill('SIGKILL'); }
}

// ── 3+4. drag-drop .fir (-> /convert) and .json on a plain serve ───────────────
{
  const srv = await startServe([]);
  try {
    const firText = readFileSync(join(FIXTURES, 'gcd.fir'), 'utf8');

    // (3) .fir -> POST /convert -> render.
    {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
      page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console.error: ' + m.text()); });
      await page.goto(`${srv.base}/SoCLess.html`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(300); // initial design.json 404s on a plain serve; that is expected
      await dropFile(page, firText, 'gcd.fir', 'application/octet-stream');
      const ok = await page.waitForSelector('[data-hier-path]', { timeout: 15000 }).then(() => true).catch(() => false);
      if (!ok) note('drop-fir', 'dropping a .fir did not render a hierarchy (POST /convert not wired?)');
      const src = await page.getAttribute('[data-source]', 'data-source').catch(() => null);
      if (ok && src !== 'fir') note('drop-fir/source', `expected data-source="fir" after dropping a .fir, got "${src}"`);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/serve-drop-fir.png` }).catch(() => {});
      while (errors.length) note('drop-fir/console', errors.shift());
      await page.close();
    }

    // (4) .json -> load directly (no server round-trip).
    {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
      page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console.error: ' + m.text()); });
      await page.goto(`${srv.base}/SoCLess.html`, { waitUntil: 'networkidle' });
      // Convert a fixture to JSON via the server, then drop that JSON as a file.
      const json = await (await fetch(`${srv.base}/convert`, { method: 'POST', body: firText })).text();
      await dropFile(page, json, 'gcd.json', 'application/json');
      const ok = await page.waitForSelector('[data-hier-path]', { timeout: 15000 }).then(() => true).catch(() => false);
      if (!ok) note('drop-json', 'dropping a design.json did not render a hierarchy');
      const src = await page.getAttribute('[data-source]', 'data-source').catch(() => null);
      if (ok && src !== 'file') note('drop-json/source', `expected data-source="file" after dropping a .json, got "${src}"`);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/serve-drop-json.png` }).catch(() => {});
      while (errors.length) note('drop-json/console', errors.shift());
      await page.close();
    }
  } finally { srv.proc.kill('SIGKILL'); }
}

await browser.close();

console.log('\n=== SERVE-SMOKE SUMMARY ===');
if (failures.length === 0) {
  console.log('OK - input layer (drag-drop .fir/.json, /convert, live poller, LIVE badge) all working.');
  process.exit(0);
} else {
  console.log(`${failures.length} problem(s):`);
  for (const f of failures) console.log(`  [${f.where}] ${f.msg}`);
  process.exit(1);
}
