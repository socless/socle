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
// Next it opens the bottom panel and marks the window, writes state-ready.json; index.js clicks `inst` in
// the Hierarchy view and writes drill-request.json, the driver checks the UI drilled (breadcrumb) in
// drill-result.json; index.js views the design again and runs Show design panel, writes
// state-request.json, and the driver checks all three survived (no iframe reload) in state-result.json.
// Then index.js switches the VS Code theme and writes <runDir>/theme-request.json ({theme}); the driver
// waits for the UI's data-theme to follow and writes <runDir>/theme-result.json.
// Last, the wave phase: index.js sets socless.waveViewer = surfer-panel and simulates GCD, which opens
// Surfer in its own editor tab (a second webview, its UI frame at ?view=surfer). index.js then writes
// numbered requests <runDir>/wave-request-<n>.json ({op, ...}) and the driver answers each in
// wave-result-<n>.json: `surfer` (the panel's Waveform tab is the remote one, the tab's Surfer is
// ready on GCD's plan; screenshots surfer.png and panel.png), `select` (a panel selection focuses
// its signal in the tab), `keep` (the tab's frame did not reload: same target, marker, one boot) and
// `errors` (no page errors in any UI frame of either webview).
//
// Two UI frames share the server url: the Surfer tab's has view=surfer, the panel's has no view.
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
const drillRequestFile = join(runDir, 'drill-request.json');
const drillResultFile = join(runDir, 'drill-result.json');
const stateRequestFile = join(runDir, 'state-request.json');
const stateResultFile = join(runDir, 'state-result.json');
const themeRequestFile = join(runDir, 'theme-request.json');
const themeResultFile = join(runDir, 'theme-result.json');

/** The panel's UI frame url (`host=vscode`, no `view`) and the Surfer tab's (`view=surfer`), on `base`. */
const isPanelUrl = (u, base) => {
  if (!u || !u.startsWith(base)) return false;
  const q = new URL(u).searchParams;
  return q.get('host') === 'vscode' && !q.get('view');
};
const isSurferUrl = (u, base) => !!u && u.startsWith(base) && new URL(u).searchParams.get('view') === 'surfer';

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
    const self = {
      closed: false,
      send: (method, params = {}) => new Promise((res, rej) => {
        const n = ++id;
        pending.set(n, { resolve: res, reject: rej, method });
        ws.send(JSON.stringify({ id: n, method, params }));
        setTimeout(() => pending.has(n) && (pending.delete(n), rej(new Error(`${method} timed out`))), 10000);
      }),
      on: (event, h) => handlers.set(event, [...(handlers.get(event) || []), h]),
    };
    // A closed frame (its webview closed) closes the socket: the cache below forgets it.
    ws.onclose = () => { self.closed = true; conns.delete(target.id); };
    ws.onopen = () => resolve(self);
  });
}

/** One connection per target id, while its socket is open. */
const conns = new Map();
function conn(target) {
  if (!conns.has(target.id)) conns.set(target.id, connect(target).catch((e) => { conns.delete(target.id); throw e; }));
  return conns.get(target.id);
}

/** The page errors of every UI frame watched (both webviews), each tagged with its frame. */
const pageErrors = [];
const watched = new Set();
// As tools/ui-smoke/embedded.mjs: a failed load (designs.json, an old trace's 404) is no page
// error; a Surfer file that fails to load shows as data-surfer-status="error". Nor is Chromium
// refusing the autofocus of egui's hidden text input: our iframe is cross-origin in the webview,
// and Surfer gets the keyboard once clicked.
const benign = /Failed to load resource|Blocked autofocusing on a <input> element in a cross-origin subframe/;

/** Collects the page errors of a UI frame from now on: uncaught exceptions, console.error, and the
  * browser's own error entries (a CSP violation, a failed load). */
async function watchErrors(c, target, label) {
  if (watched.has(target.id)) return;
  watched.add(target.id);
  c.on('Runtime.exceptionThrown', (p) =>
    pageErrors.push(`${label}: exception: ${p.exceptionDetails.exception?.description || p.exceptionDetails.text}`));
  c.on('Runtime.consoleAPICalled', (p) => p.type === 'error' &&
    pageErrors.push(`${label}: console.error: ${p.args.map((a) => a.value ?? a.description).join(' ')}`));
  c.on('Log.entryAdded', (p) => p.entry.level === 'error' && !benign.test(p.entry.text) &&
    pageErrors.push(`${label}: ${p.entry.source}: ${p.entry.text}${p.entry.url ? ' ' + p.entry.url : ''}`));
  await c.send('Runtime.enable');
  await c.send('Log.enable');
}

/** The src of the UI iframe inside a vscode-webview target (VS Code's active-frame holds it). */
const innerSrc = `(() => {
  const a = document.getElementById('active-frame');
  const f = a && a.contentDocument && a.contentDocument.querySelector('iframe');
  return f ? f.src : null;
})()`;

/** The vscode-webview target of the panel (`kind` 'panel') or of the Surfer tab ('surfer'), told
  * apart by the url of the UI frame it holds; null when there is none (yet). */
async function webviewOf(kind, base) {
  const all = (await targets()).filter((t) => t.type === 'iframe' && t.url.startsWith('vscode-webview://'));
  for (const t of all) {
    const c = await conn(t).catch(() => null);
    const src = c && (await evaluate(c, innerSrc).catch(() => null));
    if (kind === 'surfer' ? isSurferUrl(src, base) : isPanelUrl(src, base)) return t;
  }
  return null;
}

/** The workbench <iframe> element of a vscode-webview target, as a JS expression. */
const outerFrame = (webview) => `[...document.querySelectorAll('iframe')].find((f) => f.src === ${JSON.stringify(webview.url)})
  || [...document.querySelectorAll('iframe')].find((f) => f.src.startsWith(${JSON.stringify(webview.url.split('?')[0])}))`;

/** Evaluates `expr` in the target and returns its value. */
async function evaluate(conn, expr) {
  const r = await conn.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
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
  const ui = await until(`a CDP iframe target of the panel UI (${req.url}, no view)`, 60000, async () =>
    (await targets()).find((t) => t.type === 'iframe' && isPanelUrl(t.url, req.url))
  ).catch(async (e) => {
    result.targets = (await targets().catch(() => [])).map((t) => `${t.type} ${t.url}`);
    throw e;
  });
  const webview = await until('the vscode-webview target holding the panel UI', 30000, () => webviewOf('panel', req.url));
  const workbench = (await targets()).find((t) => t.type === 'page' && t.url.includes('workbench'));
  if (!workbench) throw new Error('no workbench page target');
  log(`frame ${ui.url} in ${webview.url.split('?')[0]}`);
  const uiConn = await conn(ui);
  // The page errors of our iframe from here on, checked by the wave phase.
  await watchErrors(uiConn, ui, 'panel');

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

  const wbConn = await conn(workbench);
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
    hierarchyPane: !!document.querySelector('[data-pane="hierarchy"]'),
    inspectorPane: !!document.querySelector('[data-pane="inspector"]'),
    theme: document.documentElement.getAttribute('data-theme'),
  })`);
  result.embedded = ui0;
  if (!new URL(ui0.href).searchParams.get('host')) throw new Error(`UI loaded without host=vscode: ${ui0.href}`);
  for (const k of ['sourceTab', 'logo', 'themeToggle', 'bottomPanel', 'hierarchyPane', 'inspectorPane']) if (ui0[k]) throw new Error(`embedded UI shows ${k}`);
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
  const wvConn = await conn(webview);
  const outer = await evaluate(wbConn, frameOrigin(outerFrame(webview)));
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

  // State phase: open the bottom panel and mark the window; index.js clicks `inst` in the Hierarchy view
  // (drill phase), then views the design again and runs Show design panel; the iframe must not have
  // reloaded (all three kept).
  out = stateResultFile;
  result = { ok: false };
  stage = 'prepare the UI state';
  await evaluate(uiConn, `(() => {
    window.__soclessKeep = 1;
    document.querySelector('[data-toggle-pane="bottom"]').click();
    return true;
  })()`);
  const crumb = () => evaluate(uiConn, `(() => { const a = document.querySelector('.scl-crumb.active'); return a && a.textContent; })()`);
  const state = async () => ({
    ...(await evaluate(uiConn, `({
      marker: window.__soclessKeep === 1,
      bottomPanel: !!document.querySelector('[data-bottom-panel]'),
    })`)),
    drilled: (await crumb()) === req.inst,
  });
  await until('the UI with the bottom panel open', 5000, async () => (await state()).bottomPanel);
  writeFileSync(stateReadyFile, '{}');

  stage = 'wait for the drill request';
  const stateOut = out;
  out = drillResultFile;
  await until(`${drillRequestFile} from index.js`, 5 * 60 * 1000, () => existsSync(drillRequestFile), 250);
  stage = 'UI drills on the Hierarchy click';
  await until(`the breadcrumb to end in ${req.inst}`, 15000, async () => (await crumb()) === req.inst).catch(async (e) => {
    result.crumb = await crumb().catch(String);
    throw e;
  });
  finish({ ok: true, message: `the schematic drilled into ${req.inst} (breadcrumb)` });
  out = stateOut;
  result = { ok: false };
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

  // Wave phase: index.js set socless.waveViewer = surfer-panel and simulated GCD, so the panel's UI
  // drives Surfer in its own editor tab. Each numbered request is one op (see the header).
  const surferIds = new Set(); // the Surfer tab's UI frames seen
  const panelIds = new Set([ui.id]);
  let kept = null; // the Surfer frame marked by the last `surfer` op: {id, loadedTrace}
  const status = (c) => evaluate(c, `(() => { const e = document.querySelector('[data-surfer-status]'); return e && e.getAttribute('data-surfer-status'); })()`);
  const snapOf = (c) => evaluate(c, `window.__soclessWave ? window.__soclessWave.snapshot() : null`);
  const P = (prefix, leaf) => `${prefix}.${leaf}`;
  const plan = (prefix) => ['group io', 'group io.in', P(prefix, 'io_in_valid'), P(prefix, 'io_in_ready'), 'group io.in.bits',
    P(prefix, 'io_in_bits_a'), P(prefix, 'io_in_bits_b'), 'group io.out', P(prefix, 'io_out_valid'), P(prefix, 'io_out_ready'),
    'group io.out.bits', P(prefix, 'io_out_bits_result'), 'group Registers', P(prefix, 'x'), P(prefix, 'y'), P(prefix, 'busy')];

  /** The UI frame of the panel or of the Surfer tab, watched for page errors; `fresh`: one not seen
    * before (its webview was closed and opened again). */
  const frameOf = async (kind, fresh) => {
    const seen = kind === 'surfer' ? surferIds : panelIds;
    const is = kind === 'surfer' ? isSurferUrl : isPanelUrl;
    const t = await until(`the ${kind} UI frame${fresh ? ' (a new one)' : ''}`, 60000, async () =>
      (await targets()).find((x) => x.type === 'iframe' && is(x.url, req.url) && !(fresh && seen.has(x.id))));
    seen.add(t.id);
    const c = await conn(t);
    await watchErrors(c, t, kind);
    return { t, c };
  };

  /** A screenshot of one webview: the workbench capture clipped to its outer iframe. */
  const shotOf = async (kind, file) => {
    const wv = await webviewOf(kind, req.url);
    if (!wv) throw new Error(`no ${kind} webview to screenshot`);
    const r = await evaluate(wbConn, `(() => {
      const f = ${outerFrame(wv)};
      if (!f) return null;
      const b = f.getBoundingClientRect();
      return { x: b.left, y: b.top, width: b.width, height: b.height };
    })()`);
    if (!r || r.width < 1 || r.height < 1) throw new Error(`the ${kind} webview is not on screen: ${JSON.stringify(r)}`);
    const shot = await wbConn.send('Page.captureScreenshot', { format: 'png', clip: { ...r, scale: 1 } });
    writeFileSync(file, Buffer.from(shot.data, 'base64'));
    (result.screenshots ||= []).push(file);
  };

  /** On a failure: both frames' state, the page errors and a screenshot of the window. */
  const diagnose = async () => {
    const all = await targets().catch(() => []);
    result.frames = all.filter((t) => t.type === 'iframe').map((t) => t.url);
    for (const [kind, is] of [['panel', isPanelUrl], ['surfer', isSurferUrl]]) {
      const t = all.find((x) => x.type === 'iframe' && is(x.url, req.url));
      if (t) result[kind + 'Snapshot'] = await conn(t).then(snapOf).catch(String);
    }
    result.pageErrors = pageErrors;
    const shot = await wbConn.send('Page.captureScreenshot', { format: 'png' }).catch(() => null);
    if (shot) {
      writeFileSync(join(runDir, 'wave-fail.png'), Buffer.from(shot.data, 'base64'));
      result.screenshot = join(runDir, 'wave-fail.png');
    }
  };

  const ops = {
    // The panel's Waveform tab is the remote one (Show Surfer, no Surfer canvas), and the Surfer tab
    // is ready on GCD's top plan, in plan order; marks the tab's frame for `keep`.
    surfer: async (w) => {
      stage = 'panel: the remote Waveform tab';
      const pf = await frameOf('panel', w.freshPanel);
      const remote = await until('the panel\'s Waveform tab with Show Surfer', 60000, () => evaluate(pf.c, `(() => {
        if (!document.querySelector('[data-bottom-panel]')) document.querySelector('[data-toggle-pane="bottom"]').click();
        const tab = document.querySelector('[data-bottom-tab="wave"]');
        const pane = document.querySelector('[data-wave-pane]');
        if (tab && (!pane || pane.style.display === 'none')) tab.click();
        const btn = document.querySelector('[data-show-surfer]');
        const s = window.__soclessWave && window.__soclessWave.snapshot();
        return pane && pane.style.display !== 'none' && btn && btn.offsetParent !== null && s
          ? { surferPane: !!document.querySelector('[data-surfer-pane]'), remote: s.remote === true, button: btn.textContent } : null;
      })()`), 500);
      result.panel = remote;
      if (remote.surferPane) throw new Error('the panel has a [data-surfer-pane]: Surfer is embedded in it');
      if (!remote.remote) throw new Error('the panel\'s wave pane is not the remote one');

      stage = 'Surfer tab: ready';
      const sf = await frameOf('surfer', w.fresh);
      const st = await until('[data-surfer-status] ready (or error) in the Surfer tab', 120000, async () => {
        const s = await status(sf.c);
        return (s === 'ready' || s === 'error') && s;
      }, 500);
      if (st !== 'ready') throw new Error(`Surfer status "${st}"`);

      stage = 'Surfer tab: the top plan';
      const s = await until('the top plan of GCD on this trace in the Surfer tab', 60000, async () => {
        const s = await snapOf(sf.c);
        return s && s.idle && s.shown && s.shown.path === '["GCD"]' && s.loadedTrace.endsWith(w.trace) && s;
      });
      const items = await evaluate(sf.c, `window.__soclessWave.items()`);
      const got = items.map((i) => (i.type === 'Group' ? `group ${i.name}` : i.trace || `? ${i.name}`));
      result.items = got;
      if (JSON.stringify(got) !== JSON.stringify(plan(w.prefix))) {
        throw new Error(`Surfer items ${JSON.stringify(got)}, expected ${JSON.stringify(plan(w.prefix))}`);
      }

      stage = 'panel: mirrors the tab\'s state';
      const mirrored = await until('the panel\'s pane status "ready"', 15000, async () => {
        const p = await snapOf(pf.c);
        return p && p.status === 'ready' && p;
      });

      await evaluate(sf.c, `(window.__soclessKeep = 1, true)`);
      kept = { id: sf.t.id, loadedTrace: s.loadedTrace };
      if (w.shot !== undefined) {
        stage = 'screenshots';
        await sleep(500);
        await shotOf('surfer', join(runDir, `surfer${w.shot}.png`));
        await shotOf('panel', join(runDir, `panel${w.shot}.png`));
      }
      return `Surfer tab frame ${sf.t.id.slice(0, 8)} ready (boots ${s.boots}), ${got.length} items in GCD's plan order; ` +
        `panel frame ${pf.t.id.slice(0, 8)}: remote pane (status ${mirrored.status}, "${remote.button}"), no [data-surfer-pane]`;
    },

    // A selection in the panel (its __soclessWave.select) focuses its signal in the Surfer tab.
    select: async (w) => {
      stage = 'a panel selection focuses its signal in the tab';
      const pf = await frameOf('panel');
      const sf = await frameOf('surfer');
      const focus0 = (await snapOf(sf.c)).focusCount;
      await evaluate(pf.c, `window.__soclessWave.select('io.out.bits.result')`);
      const focused = await until('the focus of io.out.bits.result in the Surfer tab', 20000, async () => {
        const s = await snapOf(sf.c);
        return s && s.idle && s.focusCount > focus0 && s.lastFocus && s;
      });
      result.lastFocus = focused.lastFocus;
      const want = P(w.prefix, 'io_out_bits_result');
      if (focused.lastFocus.trace !== want || !focused.lastFocus.ok) {
        throw new Error(`io.out.bits.result focused ${JSON.stringify(focused.lastFocus)}, expected ${want}`);
      }
      stage = 'screenshot of the focus';
      await sleep(1000); // Surfer redraws the focus
      await shotOf('surfer', join(runDir, 'surfer-focus.png'));
      return `the panel's selection of io.out.bits.result focused ${want} in the Surfer tab (focus #${focused.focusCount})`;
    },

    // The Surfer tab's frame is the one `surfer` marked: same target, marker kept, one boot, still
    // ready on the same trace with a plan shown (no reload).
    keep: async () => {
      stage = 'the Surfer tab did not reload';
      const sf = await frameOf('surfer');
      const s = await snapOf(sf.c);
      const marker = await evaluate(sf.c, `window.__soclessKeep === 1`);
      const now = { id: sf.t.id, marker, boots: s && s.boots, status: s && s.status, loadedTrace: s && s.loadedTrace, shown: !!(s && s.shown) };
      result.keep = now;
      if (!kept || now.id !== kept.id || !now.marker || now.boots !== 1 || now.status !== 'ready' || now.loadedTrace !== kept.loadedTrace || !now.shown) {
        throw new Error(`the Surfer tab reloaded or rebooted: was ${JSON.stringify(kept)}, now ${JSON.stringify(now)}`);
      }
      return `Surfer tab frame ${now.id.slice(0, 8)} kept: marker set, boots 1, ready, same trace`;
    },

    // No page errors in any UI frame watched, of either webview.
    errors: async () => {
      stage = 'no page errors';
      result.pageErrors = pageErrors;
      result.frames = [...watched];
      if (pageErrors.length > 0) throw new Error(`page errors in the webview UIs: ${pageErrors.join(' | ')}`);
      return `no page errors in ${watched.size} UI frames (panel ${panelIds.size}, Surfer tab ${surferIds.size})`;
    },
  };

  for (let n = 1; ; n++) {
    const reqFile = join(runDir, `wave-request-${n}.json`);
    out = join(runDir, `wave-result-${n}.json`);
    result = { ok: false };
    stage = `wait for wave request ${n}`;
    const w = await until(`${reqFile} from index.js`, 20 * 60 * 1000, () =>
      existsSync(reqFile) && JSON.parse(readFileSync(reqFile, 'utf8')), 500);
    log(`wave request ${n} ${JSON.stringify(w)}`);
    result.op = w.op;
    if (!ops[w.op]) throw new Error(`unknown op ${w.op}`);
    let message;
    try {
      message = await ops[w.op](w);
    } catch (e) {
      await diagnose().catch(() => {});
      throw e;
    }
    finish({ ok: true, message });
    if (w.op === 'errors') process.exit(0);
  }
}

main().catch((e) => finish({ ok: false, message: e.message }));
