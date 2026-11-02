// End-to-end check of the staged Socle extension inside a real VS Code.
// VS Code loads this file through --extensionTestsPath and calls run(); a rejection fails the run.
// Plain CommonJS: only Node built-ins and the `vscode` module VS Code provides.
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');
const vscode = require('vscode');

const GCD_FILE = 'example/src/gcd/GCD.scala';
const SPEC_FILE = 'example/test/src/gcd/GcdSpec.scala';
const GCD_CLASS_LINE = 23; // 0-based line of `class GCD extends Module`
const DEEP_SPEC_FILE = 'example/test/src/deephier/DeepSocSpec.scala';
const DEEP_TEST_LINE = 7; // 0-based line of test("DeepSoc elaborates and steps")
const CLICK_INST = 'cluster0'; // a deephier.Cluster instance in DeepSoc
const GCD_TITLE = 'Socle: GCD computes gcd(48, 18)';
const DEEP_TITLE = 'Socle: DeepSoc elaborates and steps';
const SURFER_TITLE = 'Surfer: GCD computes gcd(48, 18)';

/** The UI's data-theme for a vscode.ColorThemeKind (null: the default light theme, no attribute). */
const uiTheme = (kind) => (kind === 2 ? 'dark' : kind === 3 ? 'contrast' : null);

const t0 = Date.now();
const secs = (ms) => (ms / 1000).toFixed(1) + 's';
const log = (msg) => console.log(`[e2e +${secs(Date.now() - t0)}] ${msg}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Calls `probe` every `everyMs` until it returns a truthy value; fails with `what` after `timeoutMs`. */
async function poll(what, timeoutMs, probe, everyMs = 500) {
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
      throw new Error(`timed out after ${secs(timeoutMs)} waiting for ${what}` + (last ? ` (last error: ${last.message})` : ''));
    }
    await sleep(everyMs);
  }
}

/** One loopback HTTP request with the Host header LiveHttp accepts; resolves to {status, body}. */
function request(url, method = 'GET', json) {
  const u = new URL(url);
  const body = json === undefined ? undefined : JSON.stringify(json);
  const headers = { Host: `127.0.0.1:${u.port}` };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = Buffer.byteLength(body);
  }
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: u.port, path: u.pathname, method, headers, timeout: 5000 },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve({ status: res.statusCode, body: data }));
      }
    );
    req.on('timeout', () => req.destroy(new Error(`${method} ${url} timed out`)));
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

async function getJson(url) {
  const r = await request(url);
  if (r.status !== 200) throw new Error(`GET ${url} -> ${r.status} ${r.body}`);
  return JSON.parse(r.body);
}

function socleTabs() {
  return vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .filter((t) => t.input instanceof vscode.TabInputWebview && t.input.viewType.endsWith('socle.panel'));
}

const isWebview = (t, viewType) => !!t && t.input instanceof vscode.TabInputWebview && t.input.viewType.endsWith(viewType);

/** The Surfer tab(s): webview `socle.surfer`. */
function surferTabs() {
  return vscode.window.tabGroups.all.flatMap((g) => g.tabs).filter((t) => isWebview(t, 'socle.surfer'));
}
const surferTab = () => surferTabs()[0];

/** The editor groups and their tabs, for the log: `1[GcdSpec.scala*] 2[Socle: ...*]` (* active). */
const groupsText = () => vscode.window.tabGroups.all
  .map((g) => `${g.viewColumn}[${g.tabs.map((t) => t.label + (t.isActive ? '*' : '')).join(', ')}]`).join(' ');

/** The Surfer tab and the Socle panel are in different editor groups, and both are visible. */
function apart() {
  const s = surferTabs();
  const p = socleTabs();
  if (s.length !== 1 || p.length !== 1) throw new Error(`expected one Surfer tab and one Socle panel, found ${s.length} and ${p.length}: ${groupsText()}`);
  if (s[0].group === p[0].group) throw new Error(`the Surfer tab shares the panel's group: ${groupsText()}`);
  if (!p[0].isActive) throw new Error(`the Socle panel is hidden in its group: ${groupsText()}`);
  if (!s[0].isActive) throw new Error(`the Surfer tab is hidden in its group: ${groupsText()}`);
}

/** Makes the Surfer tab the active editor, by focusing its group (where it is the active tab). */
async function activateSurfer() {
  const names = ['First', 'Second', 'Third', 'Fourth', 'Fifth', 'Sixth', 'Seventh', 'Eighth'];
  const col = surferTab().group.viewColumn;
  await vscode.commands.executeCommand(`workbench.action.focus${names[col - 1]}EditorGroup`);
  await poll('the Surfer tab active', 10000, () => isWebview(vscode.window.tabGroups.activeTabGroup.activeTab, 'socle.surfer'));
}

/** One op of webview-click.mjs's wave phase: writes wave-request-<n>.json, waits for its result. */
let waveOps = 0;
async function driver(op, args, timeoutMs) {
  const runDir = process.env.SOCLE_E2E_DIR;
  const n = ++waveOps;
  const requestFile = path.join(runDir, `wave-request-${n}.json`);
  const resultFile = path.join(runDir, `wave-result-${n}.json`);
  fs.writeFileSync(requestFile + '.tmp', JSON.stringify({ op, ...args }));
  fs.renameSync(requestFile + '.tmp', requestFile);
  const res = await poll(`${path.basename(resultFile)} (${op}) from webview-click.mjs`, timeoutMs, () =>
    fs.existsSync(resultFile) && JSON.parse(fs.readFileSync(resultFile, 'utf8')));
  for (const s of res.screenshots || []) log(`      screenshot ${path.basename(s)} (run.sh copies it to its artifacts dir)`);
  if (!res.ok) throw new Error(`webview driver failed at stage "${res.stage}": ${res.message}`);
  log(`      ${res.message}`);
  return res;
}

function root() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) throw new Error('no workspace folder: pass the repo as the folder to open');
  return folders[0].uri.fsPath;
}

async function openEditor(rel) {
  const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(root(), rel)));
  return vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.One, preview: false });
}

async function viewDesignLens(specUri, rel = SPEC_FILE, accept = () => true) {
  return poll('a "View design" code lens on ' + rel, 60000, async () => {
    const lenses = (await vscode.commands.executeCommand('vscode.executeCodeLensProvider', specUri, 10)) || [];
    return lenses.find((l) => l.command && l.command.title === 'View design' && accept(l));
  });
}

/** The rows of the side panel's three trees (`socle._views`): {tests, hierarchy, details}. */
async function views() {
  return (await vscode.commands.executeCommand('socle._views')) || { tests: [], hierarchy: [], details: [] };
}

/** The Tests view row of `test` (depth 1) under the suite labelled `suite`. */
function testRow(rows, suite, test) {
  let inSuite = false;
  for (const r of rows) {
    if (r.depth === 0) inSuite = r.label === suite;
    else if (inSuite && r.label === test) return r;
  }
  return undefined;
}

async function step(name, f) {
  const start = Date.now();
  log(`START ${name}`);
  try {
    const v = await f();
    log(`PASS  ${name} (${secs(Date.now() - start)})`);
    return v;
  } catch (e) {
    log(`FAIL  ${name} (${secs(Date.now() - start)}): ${e.message}`);
    throw e;
  }
}

async function run() {
  log(`workspace ${root()}`);
  const specUri = vscode.Uri.file(path.join(root(), SPEC_FILE));

  await step('0. Socle side panel lists the tests', async () => {
    const cmds = await vscode.commands.getCommands(true);
    if (!cmds.includes('workbench.view.extension.socle')) throw new Error('no Socle view container (workbench.view.extension.socle)');
    await vscode.commands.executeCommand('workbench.view.extension.socle');
    const row = await poll('the Tests view row of gcd.GcdSpec / GCD computes gcd(48, 18)', 120000, async () =>
      testRow((await views()).tests, 'GcdSpec', 'GCD computes gcd(48, 18)'));
    log(`      Tests view: ${row.label} (${row.id}), icon ${row.icon}`);
    const suite = (await views()).tests.find((r) => r.depth === 0 && r.label === 'GcdSpec');
    if (!suite.description.endsWith('gcd/GcdSpec.scala')) throw new Error(`suite description "${suite.description}"`);
  });

  const lens = await step('1. View design lens on GcdSpec.scala', async () => {
    const editor = await openEditor(SPEC_FILE);
    if (editor.document.languageId !== 'scala') {
      throw new Error(`GcdSpec.scala opened with language "${editor.document.languageId}", expected "scala"`);
    }
    const l = await viewDesignLens(specUri);
    log(`      lens ${l.command.command} at line ${l.range.start.line}`);
    return l;
  });

  // From the Tests view item's inline action (the lens runs the same server command, see step 6).
  const url = await step('2. View design from the Tests view shows the GCD design', async () => {
    const row = testRow((await views()).tests, 'GcdSpec', 'GCD computes gcd(48, 18)');
    if (lens.command.arguments[1] !== row.element.suite || lens.command.arguments[2] !== row.element.test) {
      throw new Error(`Tests view item ${row.element.suite} / ${row.element.test} differs from the lens ${lens.command.arguments}`);
    }
    await vscode.commands.executeCommand('socle.viewDesignItem', row.element);
    const u = await poll('socle._panelUrl', 180000, () => vscode.commands.executeCommand('socle._panelUrl'));
    log(`      panel url ${u}`);
    await poll(`design.json with top GCD at ${u}`, 180000, async () => (await getJson(u + 'design.json')).top === 'GCD', 1000);
    await poll('the GCD test row marked "shown"', 30000, async () =>
      testRow((await views()).tests, 'GcdSpec', 'GCD computes gcd(48, 18)').description === 'shown');
    return u;
  });

  await step('3. Socle webview tab exists', async () => {
    const tabs = await poll('a Socle webview tab', 30000, () => socleTabs().length > 0 && socleTabs());
    log(`      tab "${tabs[0].label}" (${tabs[0].input.viewType})`);
    if (tabs.length !== 1) throw new Error(`expected one Socle tab, found ${tabs.length}`);
    if (tabs[0].label !== GCD_TITLE) throw new Error(`tab title "${tabs[0].label}", expected "${GCD_TITLE}"`);
  });

  await step('4. cursor in class GCD focuses gcd.GCD', async () => {
    const editor = await openEditor(GCD_FILE);
    editor.selection = new vscode.Selection(GCD_CLASS_LINE, 0, GCD_CLASS_LINE, 0);
    await poll('ide.json focusClass == gcd.GCD', 30000, async () => (await getJson(url + 'ide.json')).focusClass === 'gcd.GCD');
  });

  await step('5. POST /open jumps the editor to GCD.scala:24', async () => {
    // Make the jump observable: step 4 left GCD.scala on line 23, which a reopen without a selection
    // would restore. Reset that cursor, close every GCD.scala tab, then make GcdSpec active at the top.
    const gcd = await openEditor(GCD_FILE);
    gcd.selection = new vscode.Selection(0, 0, 0, 0);
    const gcdTabs = () => vscode.window.tabGroups.all
      .flatMap((g) => g.tabs)
      .filter((t) => t.input instanceof vscode.TabInputText && t.input.uri.fsPath.endsWith(GCD_FILE));
    await vscode.window.tabGroups.close(gcdTabs());
    await poll('GCD.scala tabs to close', 10000, () => gcdTabs().length === 0);
    const spec = await openEditor(SPEC_FILE);
    spec.selection = new vscode.Selection(0, 0, 0, 0);
    const active = vscode.window.activeTextEditor;
    if (!active || !active.document.uri.fsPath.endsWith(SPEC_FILE)) throw new Error('could not make GcdSpec.scala the active editor');
    const r = await request(url + 'open', 'POST', { file: GCD_FILE, line: 24 });
    if (r.status < 200 || r.status >= 300) throw new Error(`POST /open -> ${r.status} ${r.body}`);
    await poll(`active editor on ${GCD_FILE} line index ${GCD_CLASS_LINE}`, 15000, () => {
      const e = vscode.window.activeTextEditor;
      return e && e.document.uri.fsPath.endsWith(GCD_FILE) && e.selection.active.line === GCD_CLASS_LINE;
    });
  });

  // The tab count going 0 -> 1 is the real check here: Panel.lastUrl is never cleared on dispose, so
  // `_panelUrl` stays the same even if the server never sends socle/openPanel again.
  await step('6. closed panel reopens on View design', async () => {
    for (const t of socleTabs()) await vscode.window.tabGroups.close(t);
    await poll('the Socle tab to close', 10000, () => socleTabs().length === 0);
    await openEditor(SPEC_FILE);
    const again = await viewDesignLens(specUri);
    await vscode.commands.executeCommand(again.command.command, ...(again.command.arguments || []));
    await poll('the Socle tab to reopen', 180000, () => socleTabs().length > 0);
    await sleep(2000); // a duplicate panel would show up here
    const n = socleTabs().length;
    if (n !== 1) throw new Error(`expected exactly one Socle tab, found ${n}`);
    const u = await vscode.commands.executeCommand('socle._panelUrl');
    if (u !== url) throw new Error(`panel url changed: ${url} -> ${u}`);
  });

  // The UI inside the webview, driven over CDP by webview-click.mjs (started by run.sh): it loads
  // design.json / ide.json through relative fetches in the iframe, and a click on an instance box makes
  // the UI send POST open with the iframe's Origin, which LiveHttp must accept. GCD has no instance
  // box, so this step views DeepSoc and clicks cluster0 (module Cluster, a source file of this repo).
  await step('7. click in the webview UI opens the instance source', async () => {
    const runDir = process.env.SOCLE_E2E_DIR;
    if (!runDir || !fs.existsSync(runDir)) throw new Error('SOCLE_E2E_DIR is not set or missing: run through run.sh');
    // Room for the schematic: the side bars would leave the webview a few hundred pixels wide.
    await vscode.commands.executeCommand('workbench.action.closeSidebar');
    await vscode.commands.executeCommand('workbench.action.closeAuxiliaryBar');
    const deepUri = vscode.Uri.file(path.join(root(), DEEP_SPEC_FILE));
    await openEditor(DEEP_SPEC_FILE);
    const lens = await viewDesignLens(deepUri, DEEP_SPEC_FILE, (l) => l.range.start.line === DEEP_TEST_LINE);
    await vscode.commands.executeCommand(lens.command.command, ...(lens.command.arguments || []));
    const d = await poll(`design.json with top DeepSoc at ${url}`, 180000, async () => {
      const j = await getJson(url + 'design.json');
      return j.top === 'DeepSoc' && j;
    }, 1000);
    await poll(`the Socle tab titled "${DEEP_TITLE}"`, 10000, () => socleTabs().some((t) => t.label === DEEP_TITLE));
    // The Hierarchy view gets the design from the UI in the panel.
    const hier = await poll('the Hierarchy view showing DeepSoc > cluster0', 60000, async () => {
      const h = (await views()).hierarchy;
      return h.length > 0 && h[0].label === 'DeepSoc' && h.some((r) => r.depth === 1 && r.label === CLICK_INST) && h;
    });
    log(`      Hierarchy: ${hier.map((r) => '  '.repeat(r.depth) + r.label + ' (' + r.description + ')').join(', ')}`);

    // Where a click on CLICK_INST must lead, from the design itself, and the UI's precondition for
    // sending POST open: the module source is absolute or one of the embedded sources.
    const top = d.modules.find((m) => m.name === d.top);
    const child = top && top.children.find((c) => c.name === CLICK_INST);
    if (!child) throw new Error(`no instance ${CLICK_INST} in ${d.top}`);
    const mod = d.modules.find((m) => m.name === child.type);
    const src = mod && mod.source;
    if (!src || !(path.isAbsolute(src.file) || (d.sources && src.file in d.sources))) {
      throw new Error(`module ${child.type} has no openable source (${JSON.stringify(src)})`);
    }
    const file = path.isAbsolute(src.file) ? src.file : path.join(root(), src.file);
    log(`      ${CLICK_INST} is ${child.type} at ${src.file}:${src.line}`);

    // Make the jump observable: no tab on that file, the spec active at the top.
    const fileTabs = () => vscode.window.tabGroups.all
      .flatMap((g) => g.tabs)
      .filter((t) => t.input instanceof vscode.TabInputText && t.input.uri.fsPath === file);
    await vscode.window.tabGroups.close(fileTabs());
    await poll(`${src.file} tabs to close`, 10000, () => fileTabs().length === 0);
    const spec = await openEditor(DEEP_SPEC_FILE);
    spec.selection = new vscode.Selection(0, 0, 0, 0);

    const requestFile = path.join(runDir, 'click-request.json');
    const resultFile = path.join(runDir, 'click-result.json');
    // The driver also checks the embedded UI: no Source tab, logo or bottom panel, and VS Code's theme.
    const theme = uiTheme(vscode.window.activeColorTheme.kind);
    log(`      VS Code theme kind ${vscode.window.activeColorTheme.kind}, UI data-theme expected ${theme}`);
    fs.writeFileSync(requestFile + '.tmp', JSON.stringify({ url, inst: CLICK_INST, top: d.top, theme }));
    fs.renameSync(requestFile + '.tmp', requestFile);
    const res = await poll(`${resultFile} from webview-click.mjs`, 240000, () =>
      fs.existsSync(resultFile) && JSON.parse(fs.readFileSync(resultFile, 'utf8')));
    if (res.screenshot) log(`      screenshot taken (run.sh copies it to its artifacts dir)`);
    if (res.seen) log(`      boxes seen ${JSON.stringify(res.seen)}`);
    if (!res.ok) throw new Error(`webview driver failed at stage "${res.stage}": ${res.message}`);
    log(`      ${res.message}`);

    await poll(`active editor on ${src.file} line index ${src.line - 1}`, 15000, () => {
      const e = vscode.window.activeTextEditor;
      return e && e.document.uri.fsPath === file && e.selection.active.line === src.line - 1;
    });

    // The box click selected CLICK_INST: the Details view shows it.
    const det = await poll(`the Details view on ${CLICK_INST}`, 15000, async () => {
      const r = (await views()).details;
      return r.length > 0 && r[0].label === CLICK_INST && r;
    });
    log(`      Details: ${det.map((r) => '  '.repeat(r.depth) + r.label + (r.description ? ' (' + r.description + ')' : '')).join(', ')}`);
    if (det[0].description !== child.type) throw new Error(`Details names module ${det[0].description}, expected ${child.type}`);
    if (!det.some((r) => r.label === 'Ports')) throw new Error('no Ports in the Details view');
  });

  // A click on cluster0 in the Hierarchy view drills the schematic there (checked by the driver in the
  // webview: the breadcrumb ends in cluster0).
  await step('7b. Hierarchy click drills the schematic', async () => {
    const runDir = process.env.SOCLE_E2E_DIR;
    await poll('state-ready.json from webview-click.mjs', 30000, () => fs.existsSync(path.join(runDir, 'state-ready.json')));
    const row = (await views()).hierarchy.find((r) => r.depth === 1 && r.label === CLICK_INST);
    if (!row || !row.command) throw new Error(`no Hierarchy row with a command for ${CLICK_INST}`);
    await vscode.commands.executeCommand(row.command.command, ...(row.command.arguments || []));
    fs.writeFileSync(path.join(runDir, 'drill-request.json'), '{}');
    const resultFile = path.join(runDir, 'drill-result.json');
    const res = await poll(`${resultFile} from webview-click.mjs`, 30000, () =>
      fs.existsSync(resultFile) && JSON.parse(fs.readFileSync(resultFile, 'utf8')));
    if (!res.ok) throw new Error(`webview driver failed at stage "${res.stage}": ${res.message}`);
    log(`      ${res.message}`);
  });

  // The panel's page is set once per server URL: a second View design or Show design panel must not
  // reload the iframe. The driver opened the bottom panel after step 7, step 7b drilled into cluster0.
  await step('8. UI state survives View design and Show design panel', async () => {
    const runDir = process.env.SOCLE_E2E_DIR;
    const deepUri = vscode.Uri.file(path.join(root(), DEEP_SPEC_FILE));
    await openEditor(DEEP_SPEC_FILE);
    const lens = await viewDesignLens(deepUri, DEEP_SPEC_FILE, (l) => l.range.start.line === DEEP_TEST_LINE);
    await vscode.commands.executeCommand(lens.command.command, ...(lens.command.arguments || []));
    await sleep(1000);
    await poll('the second view run to end', 180000, async () => {
      const s = (await getJson(url + 'ide.json')).status;
      return s !== 'running' && s !== 'error';
    }, 1000);
    await vscode.commands.executeCommand('socle.showPanel');
    await sleep(3000); // a reload (and the live poll of the new design) would land here
    const resultFile = path.join(runDir, 'state-result.json');
    fs.writeFileSync(path.join(runDir, 'state-request.json'), '{}');
    const res = await poll(`${resultFile} from webview-click.mjs`, 30000, () =>
      fs.existsSync(resultFile) && JSON.parse(fs.readFileSync(resultFile, 'utf8')));
    if (!res.ok) throw new Error(`webview driver failed at stage "${res.stage}": ${res.message}`);
    log(`      ${res.message}`);
  });

  // Live theme switch: the webview page's script forwards VS Code's new theme kind to the UI.
  await step('9. the webview UI follows a VS Code theme change', async () => {
    const runDir = process.env.SOCLE_E2E_DIR;
    const from = vscode.window.activeColorTheme.kind;
    const [name, kind] = from === 1 ? ['Default Dark Modern', 2] : ['Default Light Modern', 1];
    await vscode.workspace.getConfiguration('workbench').update('colorTheme', name, vscode.ConfigurationTarget.Global);
    await poll(`VS Code theme ${name}`, 15000, () => vscode.window.activeColorTheme.kind === kind);
    const theme = uiTheme(kind);
    log(`      VS Code theme kind ${from} -> ${kind}, UI data-theme expected ${theme}`);
    const requestFile = path.join(runDir, 'theme-request.json');
    const resultFile = path.join(runDir, 'theme-result.json');
    fs.writeFileSync(requestFile + '.tmp', JSON.stringify({ theme }));
    fs.renameSync(requestFile + '.tmp', requestFile);
    const res = await poll(`${resultFile} from webview-click.mjs`, 60000, () =>
      fs.existsSync(resultFile) && JSON.parse(fs.readFileSync(resultFile, 'utf8')));
    if (!res.ok) throw new Error(`webview driver failed at stage "${res.stage}": ${res.message}`);
    log(`      ${res.message}`);
  });

  // Simulate with socle.waveViewer = surfer-panel: the server captures and simulates GCD
  // (Verilator) and sets the trace in ide.json; the panel's UI opens Surfer in its own editor tab
  // and drives it there. The driver checks both webviews over CDP (wave-request-<n>.json ops).
  const wave = {};
  await step('10. Simulate opens Surfer in its own tab, beside the panel', async () => {
    // A user setting: VS Code's client sends the `socle` section in didChangeConfiguration.
    await vscode.workspace.getConfiguration('socle').update('waveViewer', 'surfer-panel', vscode.ConfigurationTarget.Global);
    await poll('ide.json waveViewer == surfer-panel', 30000, async () => (await getJson(url + 'ide.json')).waveViewer === 'surfer-panel');
    await openEditor(SPEC_FILE);
    const sim = await poll('a "Simulate" code lens on ' + SPEC_FILE, 60000, async () => {
      const lenses = (await vscode.commands.executeCommand('vscode.executeCodeLensProvider', specUri, 10)) || [];
      return lenses.find((l) => l.command && l.command.title === 'Simulate' && l.command.arguments[2] === 'GCD computes gcd(48, 18)');
    });
    await vscode.commands.executeCommand(sim.command.command, ...(sim.command.arguments || []));
    const traceTest = 'gcd.GcdSpec#GCD computes gcd(48, 18)';
    // A cold Verilator build of the test takes minutes.
    const ide = await poll(`ide.json with the trace of ${traceTest}`, 600000, async () => {
      const j = await getJson(url + 'ide.json');
      return j.trace && j.traceTest === traceTest && j.status !== 'simulating' && j;
    }, 2000);
    log(`      trace ${ide.trace} (${ide.status}: ${ide.message})`);
    await poll(`the Socle tab titled "${GCD_TITLE}"`, 10000, () => socleTabs().some((t) => t.label === GCD_TITLE));
    const names = await getJson(url + ide.trace.replace(/^\//, '').replace(/\/trace$/, '/names'));
    log(`      trace prefix ${names.prefix}, ${names.names.length} names`);
    Object.assign(wave, { trace: ide.trace, prefix: names.prefix });
    await poll(`a webview tab titled "${SURFER_TITLE}"`, 60000, () => surferTabs().some((t) => t.label === SURFER_TITLE));
    await sleep(2000); // a second tab would show up here
    log(`      editor groups ${groupsText()}`);
    apart();
  });

  await step('10b. the Surfer tab shows GCD\'s plan; the panel has no Surfer pane', async () => {
    await vscode.commands.executeCommand('notifications.clearAll'); // the toasts would cover the webviews
    await driver('surfer', { ...wave, shot: '' }, 300000);
  });

  await step('10c. a selection in the panel focuses its signal in the Surfer tab', async () => {
    await driver('select', wave, 60000);
  });

  // Moving the tab must not reload its frame (retainContextWhenHidden): the driver compares the frame
  // (target id, a marker set in it, Surfer's boot count) with the one 10b saw.
  await step('10d. moving the Surfer tab to another group keeps Surfer (one boot)', async () => {
    // A group after the tab's, so the move lands in an existing group (beside GCD.scala) and the
    // tab's own group, emptied, closes: into a new last group the layout would look the same.
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path.join(root(), GCD_FILE)));
    await vscode.window.showTextDocument(doc, { viewColumn: surferTab().group.viewColumn + 1, preview: false });
    const from = surferTab().group;
    await activateSurfer();
    log(`      before: ${groupsText()}`);
    await vscode.commands.executeCommand('workbench.action.moveEditorToNextGroup');
    // By group identity, not column number: an emptied group closes and the columns renumber.
    await poll('the Surfer tab in the group of GCD.scala', 10000, () => {
      const t = surferTab();
      return t && t.group !== from && t.group.tabs.some((x) => x.input instanceof vscode.TabInputText && x.input.uri.fsPath.endsWith(GCD_FILE));
    });
    log(`      after:  ${groupsText()}`);
    apart();
    await sleep(2000);
    await driver('keep', {}, 60000);
  });

  // The panel reveals in its own column: with ViewColumn.Beside it would move beside the active
  // editor, the Surfer tab. The lens is read first, as opening its file would change the active editor.
  await step('10e. View design with the Surfer tab active keeps the panel in its column', async () => {
    const lens = await viewDesignLens(specUri);
    const panel0 = socleTabs()[0].group;
    await activateSurfer();
    const active = vscode.window.tabGroups.activeTabGroup.activeTab;
    if (!isWebview(active, 'socle.surfer')) throw new Error(`the active tab is "${active && active.label}", not the Surfer tab`);
    log(`      before: ${groupsText()} (active: ${active.label})`);
    // The view's completion: the stamp moves past the one read just before the lens and the
    // status is no longer running (a fast view can be over before a poll ever sees `running`).
    const stamp0 = (await getJson(url + 'ide.json')).stamp;
    await vscode.commands.executeCommand(lens.command.command, ...(lens.command.arguments || []));
    const done = await poll('the view run to end', 180000, async () => {
      const j = await getJson(url + 'ide.json');
      return j.stamp > stamp0 && j.status !== 'running' && j;
    }, 250);
    if (done.status === 'error') throw new Error(`the view failed: ${done.message}`);
    await sleep(3000); // Panel.show (the server's openPanel) lands here
    log(`      after:  ${groupsText()}`);
    const p = socleTabs();
    if (p.length !== 1) throw new Error(`expected one Socle tab, found ${p.length}`);
    if (p[0].label !== GCD_TITLE) throw new Error(`panel title "${p[0].label}", expected "${GCD_TITLE}"`);
    if (p[0].group !== panel0) throw new Error(`the panel moved: column ${panel0.viewColumn} -> ${p[0].group.viewColumn}`);
    apart();
    await driver('keep', {}, 60000);
  });

  await step('10f. a closed Surfer tab comes back on Show waveforms', async () => {
    await vscode.window.tabGroups.close(surferTab());
    await poll('the Surfer tab to close', 10000, () => surferTabs().length === 0);
    await sleep(1000);
    await vscode.commands.executeCommand('socle.showWaves');
    await poll(`the Surfer tab "${SURFER_TITLE}" back`, 30000, () => surferTabs().some((t) => t.label === SURFER_TITLE));
    log(`      editor groups ${groupsText()}`);
    apart();
    await driver('surfer', { ...wave, fresh: true }, 300000);
  });

  await step('10g. closed panel and tab come back on Show waveforms', async () => {
    await vscode.window.tabGroups.close([...surferTabs(), ...socleTabs()]);
    await poll('the panel and the Surfer tab to close', 10000, () => surferTabs().length === 0 && socleTabs().length === 0);
    await sleep(1000);
    await vscode.commands.executeCommand('socle.showWaves');
    await poll('the Socle panel back', 30000, () => socleTabs().some((t) => t.label === GCD_TITLE));
    await poll(`the Surfer tab "${SURFER_TITLE}" back`, 30000, () => surferTabs().some((t) => t.label === SURFER_TITLE));
    await sleep(2000);
    log(`      editor groups ${groupsText()}`);
    apart();
    await driver('surfer', { ...wave, fresh: true, freshPanel: true, shot: '-reopened' }, 300000);
  });

  await step('10h. no page errors in either webview', async () => {
    await driver('errors', {}, 30000);
  });

  log('ALL PASSED');
}

module.exports = { run };
