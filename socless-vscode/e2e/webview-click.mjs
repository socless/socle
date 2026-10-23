// Drives the SoCLess webview of the e2e VS Code over the Chrome DevTools Protocol.
//
//   node webview-click.mjs <runDir> <cdpPort>
//
// Started in the background by run.sh. It waits for index.js to write <runDir>/click-request.json
// ({url, inst}), then works on VS Code's CDP targets: the workbench page, the webview iframe
// (vscode-webview://, holding VS Code's active-frame and our iframe) and our iframe on the loopback
// server, which is out of process and so its own target. It waits until the UI in our iframe has
// fetched ide.json (200) and drawn the box of `inst`, takes a screenshot, clicks the box header with
// a real mouse event on the workbench page, records the UI's `POST open` and writes
// <runDir>/click-result.json: {ok, stage, message, ...}. index.js then checks the editor moved.
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

let stage = 'start';
const result = { ok: false };

function finish(extra) {
  Object.assign(result, extra, { stage });
  writeFileSync(resultFile + '.tmp', JSON.stringify(result, null, 2));
  renameSync(resultFile + '.tmp', resultFile); // index.js never sees a half-written file
  log(`${result.ok ? 'OK' : 'FAIL'} at stage "${stage}": ${result.message}`);
  process.exit(0);
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
}

main().catch((e) => finish({ ok: false, message: e.message }));
