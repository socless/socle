// Assemble a static serve directory for the UI smoke test: one subdirectory per
// elaborated example, each holding the shipped Scala.js + Laminar UI (as
// SoCLess.html) and that design's design.json, plus a top-level manifest.json
// listing the design ids. The UI loads design.json from its own directory, so
// each design is smoked on its own page (epic #172 cutover; the old JS UI
// switched designs in-page via a picker + ?design= query).
//
// Run from the repo root after elaborating the examples:
//   node tools/ui-smoke/assemble.mjs <out-dir>     (default out: site)

import { mkdirSync, copyFileSync, writeFileSync, existsSync } from 'node:fs';

const args = process.argv.slice(2);
const out = args.find((a) => !a.startsWith('--')) || 'site';
// The shipped UI is built by `mill socless-ui.dist` (no committed artifact,
// issue #184). Default to its dest; override with SOCLESS_UI_HTML.
const UI = process.env.SOCLESS_UI_HTML || 'out/socless-ui/dist.dest/SoCLess.html';
const designs = [
  ['axisystem',    'soclessRun',          'AXISystem (AXI crossbar)'],
  ['rvpipeline',   'soclessRunPipeline',  'RVPipeline (4-stage)'],
  ['gcd',          'soclessRunGcd',       'GCD'],
  ['deepsoc',      'soclessRunDeepSoc',   'DeepSoc (deep hierarchy)'],
  ['interconnect', 'soclessRunIoBundles', 'Interconnect (Vec-of-bundle)'],
  ['datapath',     'soclessRunRegHeavy',  'Datapath (register-heavy)'],
  ['fsm',          'soclessRunFsm',       'TrafficFsm (3-state FSM)'],
  ['layers',       'soclessRunLayers',    'LayeredCounter (verification layers)'],
  ['scatter',      'soclessRunScatter',   'Scatter (bundle direction split)'],
];

if (!existsSync(UI)) {
  console.error(`${UI} is missing. Build it: ./mill socless-ui.dist`);
  process.exit(1);
}

mkdirSync(out, { recursive: true });
const manifest = [];
for (const [id, task, label] of designs) {
  const src = `out/example/${task}.dest/design.json`;
  if (!existsSync(src)) { console.warn('skip (not elaborated):', src); continue; }
  mkdirSync(`${out}/${id}`, { recursive: true });
  copyFileSync(UI, `${out}/${id}/SoCLess.html`);
  copyFileSync(src, `${out}/${id}/design.json`);
  manifest.push({ id, label });
}
// Multi-top fixture (#197): a page with a designs.json index + two design JSONs
// so the top-bar design picker renders and can be exercised. Reuses gcd as the
// primary (design.json / live) and datapath as the second top.
{
  const primary = 'out/example/soclessRunGcd.dest/design.json';
  const second = 'out/example/soclessRunRegHeavy.dest/design.json';
  if (existsSync(primary) && existsSync(second)) {
    const mt = `${out}/multitop`;
    mkdirSync(mt, { recursive: true });
    copyFileSync(UI, `${mt}/SoCLess.html`);
    copyFileSync(primary, `${mt}/design.json`);
    copyFileSync(second, `${mt}/design-datapath.json`);
    writeFileSync(`${mt}/designs.json`, JSON.stringify({ designs: [
      { id: 'gcd', label: 'GCD', path: 'design.json' },
      { id: 'datapath', label: 'Datapath', path: 'design-datapath.json' },
    ] }, null, 2));
    manifest.push({ id: 'multitop', label: 'Multi-top (picker)' });
  }
}

writeFileSync(`${out}/manifest.json`, JSON.stringify({ designs: manifest }, null, 2));

if (manifest.length === 0) { console.error('No elaborated designs found; run the mill example tasks first.'); process.exit(1); }
console.log('assembled', out, '->', manifest.map((m) => m.id).join(', '));
