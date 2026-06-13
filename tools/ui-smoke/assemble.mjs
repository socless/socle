// Assemble a static serve directory for the UI smoke test: the single-file UI,
// every elaborated example design.json under examples/<id>.json, an
// examples/manifest.json the picker reads, and a default design.json.
//
// Run from the repo root after elaborating the examples:
//   node tools/ui-smoke/assemble.mjs <out-dir> [--require-dist]   (default out: site)
//
// --require-dist fails if ui/SoCLess.dist.html is missing instead of silently
// serving the dev source (CI passes it so the smoke always validates the
// shipped artifact, issue #136).

import { mkdirSync, copyFileSync, writeFileSync, existsSync } from 'node:fs';

const args = process.argv.slice(2);
const requireDist = args.includes('--require-dist');
const out = args.find((a) => !a.startsWith('--')) || 'site';
const designs = [
  ['axisystem',    'soclessRun',          'AXISystem (AXI crossbar)'],
  ['rvpipeline',   'soclessRunPipeline',  'RVPipeline (4-stage)'],
  ['gcd',          'soclessRunGcd',       'GCD'],
  ['deepsoc',      'soclessRunDeepSoc',   'DeepSoc (deep hierarchy)'],
  ['interconnect', 'soclessRunIoBundles', 'Interconnect (Vec-of-bundle)'],
  ['datapath',     'soclessRunRegHeavy',  'Datapath (register-heavy)'],
];

mkdirSync(`${out}/examples`, { recursive: true });
// Serve the BUILT artifact (precompiled JSX + vendored production React,
// issue #105): it is what ships in the JAR, so it is what gets smoked. Fall
// back to the dev source when the dist has not been built.
const hasDist = existsSync('ui/SoCLess.dist.html');
if (requireDist && !hasDist) {
  console.error('--require-dist: ui/SoCLess.dist.html is missing. Build it: cd tools/ui-build && npm ci && node build.mjs');
  process.exit(1);
}
const ui = hasDist ? 'ui/SoCLess.dist.html' : 'ui/SoCLess.html';
console.log(`[assemble] serving UI from ${ui}${hasDist ? ' (built dist)' : ' (DEV SOURCE - not the shipped artifact)'}`);
copyFileSync(ui, `${out}/SoCLess.html`);

const manifest = [];
for (const [id, task, label] of designs) {
  const src = `out/example/${task}.dest/design.json`;
  if (!existsSync(src)) { console.warn('skip (not elaborated):', src); continue; }
  copyFileSync(src, `${out}/examples/${id}.json`);
  manifest.push({ id, label, path: `examples/${id}.json`, doc: '' });
}
writeFileSync(`${out}/examples/manifest.json`, JSON.stringify({ designs: manifest }, null, 2));

// Default live design.json (the no-param landing) - first available design.
const def = manifest[0] && `out/example/${designs.find(d => d[0] === manifest[0].id)[1]}.dest/design.json`;
if (def && existsSync(def)) copyFileSync(def, `${out}/design.json`);

if (manifest.length === 0) { console.error('No elaborated designs found; run the mill example tasks first.'); process.exit(1); }
console.log('assembled', out, '->', manifest.map((m) => m.id).join(', '));
