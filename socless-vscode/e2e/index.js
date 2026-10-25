// End-to-end check of the staged SoCLess extension inside a real VS Code.
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
const GCD_TITLE = 'SoCLess: GCD computes gcd(48, 18)';
const DEEP_TITLE = 'SoCLess: DeepSoc elaborates and steps';

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

function soclessTabs() {
  return vscode.window.tabGroups.all
    .flatMap((g) => g.tabs)
    .filter((t) => t.input instanceof vscode.TabInputWebview && t.input.viewType.endsWith('socless.panel'));
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

  const lens = await step('1. View design lens on GcdSpec.scala', async () => {
    const editor = await openEditor(SPEC_FILE);
    if (editor.document.languageId !== 'scala') {
      throw new Error(`GcdSpec.scala opened with language "${editor.document.languageId}", expected "scala"`);
    }
    const l = await viewDesignLens(specUri);
    log(`      lens ${l.command.command} at line ${l.range.start.line}`);
    return l;
  });

  const url = await step('2. View design shows the GCD design', async () => {
    await vscode.commands.executeCommand(lens.command.command, ...(lens.command.arguments || []));
    const u = await poll('socless._panelUrl', 180000, () => vscode.commands.executeCommand('socless._panelUrl'));
    log(`      panel url ${u}`);
    await poll(`design.json with top GCD at ${u}`, 180000, async () => (await getJson(u + 'design.json')).top === 'GCD', 1000);
    return u;
  });

  await step('3. SoCLess webview tab exists', async () => {
    const tabs = await poll('a SoCLess webview tab', 30000, () => soclessTabs().length > 0 && soclessTabs());
    log(`      tab "${tabs[0].label}" (${tabs[0].input.viewType})`);
    if (tabs.length !== 1) throw new Error(`expected one SoCLess tab, found ${tabs.length}`);
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
  // `_panelUrl` stays the same even if the server never sends socless/openPanel again.
  await step('6. closed panel reopens on View design', async () => {
    for (const t of soclessTabs()) await vscode.window.tabGroups.close(t);
    await poll('the SoCLess tab to close', 10000, () => soclessTabs().length === 0);
    await openEditor(SPEC_FILE);
    const again = await viewDesignLens(specUri);
    await vscode.commands.executeCommand(again.command.command, ...(again.command.arguments || []));
    await poll('the SoCLess tab to reopen', 180000, () => soclessTabs().length > 0);
    await sleep(2000); // a duplicate panel would show up here
    const n = soclessTabs().length;
    if (n !== 1) throw new Error(`expected exactly one SoCLess tab, found ${n}`);
    const u = await vscode.commands.executeCommand('socless._panelUrl');
    if (u !== url) throw new Error(`panel url changed: ${url} -> ${u}`);
  });

  // The UI inside the webview, driven over CDP by webview-click.mjs (started by run.sh): it loads
  // design.json / ide.json through relative fetches in the iframe, and a click on an instance box makes
  // the UI send POST open with the iframe's Origin, which LiveHttp must accept. GCD has no instance
  // box, so this step views DeepSoc and clicks cluster0 (module Cluster, a source file of this repo).
  await step('7. click in the webview UI opens the instance source', async () => {
    const runDir = process.env.SOCLESS_E2E_DIR;
    if (!runDir || !fs.existsSync(runDir)) throw new Error('SOCLESS_E2E_DIR is not set or missing: run through run.sh');
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
    await poll(`the SoCLess tab titled "${DEEP_TITLE}"`, 10000, () => soclessTabs().some((t) => t.label === DEEP_TITLE));

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
  });

  // The panel's page is set once per server URL: a second View design or Show design panel must not
  // reload the iframe. The driver drilled into cluster0 and opened the bottom panel after step 7.
  await step('8. UI state survives View design and Show design panel', async () => {
    const runDir = process.env.SOCLESS_E2E_DIR;
    await poll('state-ready.json from webview-click.mjs', 30000, () => fs.existsSync(path.join(runDir, 'state-ready.json')));
    const deepUri = vscode.Uri.file(path.join(root(), DEEP_SPEC_FILE));
    await openEditor(DEEP_SPEC_FILE);
    const lens = await viewDesignLens(deepUri, DEEP_SPEC_FILE, (l) => l.range.start.line === DEEP_TEST_LINE);
    await vscode.commands.executeCommand(lens.command.command, ...(lens.command.arguments || []));
    await sleep(1000);
    await poll('the second view run to end', 180000, async () => {
      const s = (await getJson(url + 'ide.json')).status;
      return s !== 'running' && s !== 'error';
    }, 1000);
    await vscode.commands.executeCommand('socless.showPanel');
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
    const runDir = process.env.SOCLESS_E2E_DIR;
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

  log('ALL PASSED');
}

module.exports = { run };
