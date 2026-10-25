// Drives the SoCLess webview of the e2e VS Code over the Chrome DevTools Protocol.
//
//   node webview-click.mjs <runDir> <cdpPort>
//
// Started in the background by run.sh. It waits for index.js to write <runDir>/click-request.json
// ({url, inst}), then works on VS Code's CDP targets: the workbench page, the webview iframe
// (vscode-webview://, holding VS Code's active-frame and our iframe) and our iframe on the loopback
// server, which is out of process and so its own target. It waits until the UI in our iframe has
// fetched ide.json (200) and drawn the box of `inst`, checks the UI is in embedded mode with VS Code's
// theme (request `theme`: the expected data-theme), takes a screenshot, clicks the box header with
// a real mouse event on the workbench page, records the UI's `POST open` and writes
// <runDir>/click-result.json: {ok, stage, message, ...}. index.js then checks the editor moved.
// Next it drills into `inst`, opens the bottom panel and marks the window, writes state-ready.json;
// index.js views the design again and runs Show design panel, writes state-request.json, and the
// driver checks all three survived (no iframe reload) in state-result.json.
// Then index.js switches the VS Code theme and writes <runDir>/theme-request.json ({theme}); the driver
// waits for the UI's data-theme to follow and writes <runDir>/theme-result.json.
//
// Raw CDP over Node's built-in WebSocket, no npm package: Playwright's connectOverCDP (the one in
// tools/ui-smoke) attached to VS Code's Electron sees only the workbench frame, not the webview's
// out-of-process iframes, although /json/list lists them.
import { existsSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { join } from 'node:path';

const [runDir, cdpPort] = process.argv.slice(2);
const requestFile = join(runDir, 'click-request.json');
const resultFile = join(runDir, 'click-result.json');
const shotFile = join(runDir, 'webview.png');
const cdp = `http://127.0.0.1:${cdpPort}`;

const t0 = Date.now();
const log = (m) => console.log(`[click +${((Date.now() - t0) / 1000).toFixed(1)}s] ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stateReadyFile = join(runDir, 'state-ready.json');
const stateRequestFile = join(runDir, 'state-request.json');
const stateResultFile = join(runDir, 'state-result.json');
const themeRequestFile = join(runDir, 'theme-request.json');
const themeResultFile = join(runDir, 'theme-result.json');

let stage = 'start';
let result = { ok: false };
let out = resultFile; // the click phase, then the theme phase

/** Writes the current phase's result; a failure ends the driver. */
function finish(extra) {
  Object.assign(result, extra, { stage });
  writeFileSync(out + '.tmp', JSON.stringify(result, null, 2));
  renameSync(out + '.tmp', out); // index.js never sees a half-written file
  log(`${result.ok ? 'OK' : 'FAIL'} at stage "${stage}": ${result.message}`);
  if (!result.ok) process.exit(0);
}

async function until(what, timeoutMs, probe, everyMs = 250) {
  const start = Date.now();
  let last;
  for (;;) {
    try {
      const v = await probe();
      if (v) return v;
    } catch (e) {
      last = e;
    }
    if (Date.now() - start > timeoutMs) {
      throw new Error(`timed out after ${timeoutMs / 1000}s waiting for ${what}` + (last ? ` (last error: ${last.message})` : ''));
    }
    await sleep(everyMs);
  }
}

const targets = async () => (await fetch(`${cdp}/json/list`)).json();

/** One CDP connection to a target: `send(method, params)` and `on(event, handler)`. */
function connect(target) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    let id = 0;
    const pending = new Map();
    const handlers = new Map();
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id !== undefined) {
        const p = pending.get(m.id);
        pending.delete(m.id);
        if (p) m.error ? p.reject(new Error(`${p.method}: ${m.error.message}`)) : p.resolve(m.result);
      } else (handlers.get(m.method) || []).forEach((h) => h(m.params));
    };
    ws.onerror = () => reject(new Error(`cannot connect to ${target.url}`));
    ws.onopen = () => resolve({
      send: (method, params = {}) => new Promise((res, rej) => {
        const n = ++id;
        pending.set(n, { resolve: res, reject: rej, method });
        ws.send(JSON.stringify({ id: n, method, params }));
        setTimeout(() => pending.has(n) && (pending.delete(n), rej(new Error(`${method} timed out`))), 10000);
      }),
      on: (event, h) => handlers.set(event, [...(handlers.get(event) || []), h]),
    });
  });
}

/** Evaluates `expr` in the target and returns its value. */
async function evaluate(conn, expr) {
  const r = await conn.send('Runtime.evaluate', { expression: expr, returnByValue: true });
  if (r.exceptionDetails) throw new Error(`${expr.slice(0, 60)}...: ${r.exceptionDetails.text}`);
  return r.result.value;
}

/** The top-left corner of an iframe's content box in its parent document. */
const frameOrigin = (selector) => `(() => {
  const f = ${selector};
  if (!f) return null;
  const r = f.getBoundingClientRect();
  return { x: r.left + f.clientLeft, y: r.top + f.clientTop };
})()`;

async function main() {
  stage = 'wait for the click request';
  // Bounded by run.sh, which kills this process when VS Code exits; 15 min covers a cold Mill build.
  const req = await until(`${requestFile} from index.js`, 15 * 60 * 1000, () =>
    existsSync(requestFile) && JSON.parse(readFileSync(requestFile, 'utf8')), 500);
  log(`request ${JSON.stringify(req)}`);
  Object.assign(result, { inst: req.inst, url: req.url });

  stage = 'find the UI frame in the webview';
  const ui = await until(`a CDP iframe target whose URL starts with ${req.url}`, 60000, async () =>
    (await targets()).find((t) => t.type === 'iframe' && t.url.startsWith(req.url))
  ).catch(async (e) => {
    result.targets = (await targets().catch(() => [])).map((t) => `${t.type} ${t.url}`);
    throw e;
  });
  const all = await targets();
  const webview = all.find((t) => t.type === 'iframe' && t.url.startsWith('vscode-webview://'));
  const workbench = all.find((t) => t.type === 'page' && t.url.includes('workbench'));
  if (!webview || !workbench) throw new Error('no webview iframe or workbench page target');
  log(`frame ${ui.url} in ${webview.url.split('?')[0]}`);
  const uiConn = await connect(ui);

  stage = 'UI polls ide.json from the iframe';
  // Network events of our iframe: every request the UI makes, with its method and status.
  // The headers as sent (Origin, Host) come only in the ExtraInfo event: the network stack adds them.
  const methods = new Map();
  const sent = new Map();
  const responses = [];
  const header = (hs, name) => Object.entries(hs || {}).find(([k]) => k.toLowerCase() === name)?.[1];
  uiConn.on('Network.requestWillBeSent', (p) => methods.set(p.requestId, p.request.method));
  uiConn.on('Network.requestWillBeSentExtraInfo', (p) =>
    sent.set(p.requestId, { origin: header(p.headers, 'origin'), host: header(p.headers, 'host') }));
  uiConn.on('Network.responseReceived', (p) =>
    responses.push({ id: p.requestId, method: methods.get(p.requestId), url: p.response.url, status: p.response.status }));
  await uiConn.send('Network.enable');
  await until('a 200 GET ide.json from the UI (the IDE link is enabled)', 30000, () =>
    responses.some((r) => r.method === 'GET' && r.url.startsWith(req.url + 'ide.json') && r.status === 200));

  const wbConn = await connect(workbench);
  const screenshot = async () => {
    const shot = await wbConn.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(shotFile, Buffer.from(shot.data, 'base64'));
    result.screenshot = shotFile;
  };
  const boxes = () => evaluate(uiConn, `({ viewport: [innerWidth, innerHeight],
    boxes: [...document.querySelectorAll('[data-box-inst]')].map((e) => {
      const r = e.getBoundingClientRect();
      return { inst: e.getAttribute('data-box-inst'), x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) };
    }) })`);

  stage = 'UI draws the instance box';
  const sel = JSON.stringify(`[data-box-inst="${req.inst}"]`);
  const box = await until(`a visible ${sel} box in the iframe viewport`, 60000, () => evaluate(uiConn, `(() => {
    const e = document.querySelector(${sel});
    if (!e) return null;
    const r = e.getBoundingClientRect();
    const inView = r.width > 0 && r.height > 0 && r.left >= 0 && r.top >= 0 && r.left + 20 < innerWidth && r.top + 12 < innerHeight;
    return inView ? { x: r.left, y: r.top, w: r.width, h: r.height } : null;
  })()`)).catch(async (e) => {
    result.seen = await boxes().catch((err) => String(err));
    await screenshot().catch(() => {});
    throw e;
  });
  result.seen = await boxes();
  log(`boxes ${JSON.stringify(result.seen)}; ${req.inst} at ${JSON.stringify(box)}`);

  stage = 'embedded UI';
  // The panel loads the UI in embedded mode: no Source tab, logo or theme toggle, the bottom panel
  // closed, and the theme of VS Code (req.theme: the expected data-theme, null for light).
  const ui0 = await evaluate(uiConn, `({
    href: location.href,
    sourceTab: !!document.querySelector('[data-bottom-tab="source"]'),
    logo: !!document.querySelector('.scl-logo'),
    themeToggle: !!document.querySelector('[data-theme-toggle]'),
    bottomPanel: !!document.querySelector('[data-bottom-panel]'),
    theme: document.documentElement.getAttribute('data-theme'),
  })`);
  result.embedded = ui0;
  if (!new URL(ui0.href).searchParams.get('host')) throw new Error(`UI loaded without host=vscode: ${ui0.href}`);
  for (const k of ['sourceTab', 'logo', 'themeToggle', 'bottomPanel']) if (ui0[k]) throw new Error(`embedded UI shows ${k}`);
  if (ui0.theme !== req.theme) throw new Error(`UI data-theme ${ui0.theme}, expected ${req.theme} (VS Code's theme)`);

  stage = 'screenshot';
  await screenshot();

  stage = 'click the box header';
  // A point of the box header (the schematic is zoomed to fit, so the header can be a few pixels
  // high): the centre of the Scala class label of a captured design, else just below the top edge.
  // elementFromPoint proves that point hits this box and not a port row or another box.
  const local = await evaluate(uiConn, `(() => {
    const box = document.querySelector(${sel});
    const label = box.querySelector('[data-box-class]');
    const b = box.getBoundingClientRect();
    const r = label && label.getBoundingClientRect();
    const p = r && r.width > 0 ? { x: r.left + r.width / 2, y: r.top + r.height / 2 } : { x: b.left + b.width / 2, y: b.top + 2 };
    const hit = document.elementFromPoint(p.x, p.y);
    return { ...p, hits: !!hit && hit.closest('[data-box-inst]') === box, by: label ? 'class label' : 'top edge' };
  })()`);
  result.local = local;
  if (!local.hits) throw new Error(`the header point ${JSON.stringify(local)} does not hit the ${req.inst} box`);
  // Workbench coordinates of that point: workbench -> webview iframe -> active-frame -> our iframe.
  const wvConn = await connect(webview);
  const outer = await evaluate(wbConn, frameOrigin(`[...document.querySelectorAll('iframe')].find((f) => f.src.startsWith(${JSON.stringify(webview.url.split('?')[0])}))`));
  const active = await evaluate(wvConn, frameOrigin(`document.getElementById('active-frame')`));
  const inner = await evaluate(wvConn, frameOrigin(`document.getElementById('active-frame').contentDocument.querySelector('iframe')`));
  if (!outer || !active || !inner) throw new Error(`iframe chain not found: ${JSON.stringify({ outer, active, inner })}`);
  const x = outer.x + active.x + inner.x + local.x;
  const y = outer.y + active.y + inner.y + local.y;
  result.click = { x, y };
  const before = responses.length;
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await wbConn.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 });
  }

  stage = 'UI sends POST open';
  const post = await until('a POST open response from the UI', 10000, () =>
    responses.slice(before).find((r) => r.method === 'POST' && r.url === req.url + 'open')
  ).catch(async (e) => {
    // Whether the click selected the box at all (the inspector names the selected instance).
    result.inspector = await evaluate(uiConn, `(document.querySelector('[data-inspector]') || document.body).innerText.slice(0, 300)`).catch(String);
    throw e;
  });
  result.post = post;
  if (post.status < 200 || post.status >= 300) throw new Error(`POST open answered ${post.status} (Host/Origin check?)`);

  stage = 'POST open carries the iframe Origin';
  // LiveHttp rejects a wrong Origin but accepts none, so check one was sent: the iframe's own.
  const headers = await until('the request headers of POST open', 5000, () => sent.get(post.id));
  Object.assign(post, headers);
  const origin = req.url.replace(/\/$/, '');
  if (headers.origin !== origin) throw new Error(`POST open sent Origin ${headers.origin}, expected ${origin}`);

  finish({ ok: true, message: `clicked ${req.inst} at (${x}, ${y}), POST open (Origin ${headers.origin}, Host ${headers.host}) -> ${post.status}` });

  // State phase: drill into a module, open the bottom panel and mark the window, then index.js views
  // the design again and runs Show design panel; the iframe must not have reloaded (all three kept).
  out = stateResultFile;
  result = { ok: false };
  stage = 'prepare the UI state';
  const drill = JSON.stringify(`[data-hier-path="${req.top}/${req.inst}"]`);
  await evaluate(uiConn, `(() => {
    window.__soclessKeep = 1;
    document.querySelector(${drill}).click();
    document.querySelector('[data-toggle-pane="bottom"]').click();
    return true;
  })()`);
  const state = () => evaluate(uiConn, `({
    marker: window.__soclessKeep === 1,
    bottomPanel: !!document.querySelector('[data-bottom-panel]'),
    drilled: !!document.querySelector(${drill} + '.active'),
  })`);
  await until('the UI drilled with the bottom panel open', 5000, async () => {
    const s = await state();
    return s.bottomPanel && s.drilled;
  });
  writeFileSync(stateReadyFile, '{}');
  stage = 'wait for the state request';
  await until(`${stateRequestFile} from index.js`, 5 * 60 * 1000, () => existsSync(stateRequestFile), 250);
  stage = 'UI state survives';
  const after = await state().catch((e) => ({ error: e.message }));
  result.state = after;
  if (!after.marker || !after.bottomPanel || !after.drilled) throw new Error(`UI state lost (iframe reloaded?): ${JSON.stringify(after)}`);
  finish({ ok: true, message: `UI state kept: ${JSON.stringify(after)}` });

  // Theme phase: index.js switches the VS Code theme and says which data-theme the UI must reach.
  out = themeResultFile;
  result = { ok: false };
  stage = 'wait for the theme request';
  const treq = await until(`${themeRequestFile} from index.js`, 5 * 60 * 1000, () =>
    existsSync(themeRequestFile) && JSON.parse(readFileSync(themeRequestFile, 'utf8')), 250);
  stage = 'UI follows the VS Code theme';
  const theme = () => evaluate(uiConn, `document.documentElement.getAttribute('data-theme')`);
  await until(`UI data-theme ${treq.theme}`, 15000, async () => (await theme()) === treq.theme).catch(async (e) => {
    result.theme = await theme().catch(String);
    // The webview page side: its theme class and whether its forwarding script is there.
    result.page = await evaluate(wvConn, `(() => {
      const d = document.getElementById('active-frame').contentDocument;
      return { bodyClass: d.body.className, scripts: [...d.querySelectorAll('script')].map((s) => s.outerHTML.slice(0, 120)) };
    })()`).catch(String);
    throw e;
  });
  finish({ ok: true, message: `UI data-theme is now ${treq.theme}` });
  process.exit(0);
}

main().catch((e) => finish({ ok: false, message: e.message }));
