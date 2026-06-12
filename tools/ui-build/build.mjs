// Build ui/SoCLess.dist.html from ui/SoCLess.html (issue #105).
//
// The source file is the development artifact: it loads React dev builds and
// @babel/standalone from unpkg and compiles its JSX in the browser, which
// means no offline use, a multi-second in-browser compile on every load, and
// a supply-chain dependency at runtime. This script produces the SHIPPED
// artifact instead:
//
//   1. The three CDN <script src> tags are replaced by the vendored
//      production UMDs of react/react-dom, inlined (Babel is dropped).
//   2. Every <script type="text/babel"> block is precompiled with the same
//      @babel/standalone version and the same preset (react) the browser
//      would have used, becoming a plain <script>.
//
// The dist file is COMMITTED (like the golden snapshots) so the Scala build
// never needs Node; CI rebuilds it and fails if it is out of sync.
//
// Usage:  cd tools/ui-build && npm ci && node build.mjs
//
// The output is deterministic for a given source + dependency lockfile.

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import Babel from '@babel/standalone';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const srcPath = join(repo, 'ui', 'SoCLess.html');
const outPath = join(repo, 'ui', 'SoCLess.dist.html');

let html = readFileSync(srcPath, 'utf8');

const vendor = (f) => {
  const code = readFileSync(join(here, 'vendor', f), 'utf8');
  if (code.includes('</script')) {
    throw new Error(`${f} contains '</script' and cannot be inlined safely`);
  }
  return code;
};

// 1. Swap the CDN tags for inlined production builds; drop Babel entirely.
const cdnTag = (pkg) =>
  new RegExp(`<script src="https://unpkg\\.com/${pkg}@[^"]+"[^>]*></script>`);
const replaceTag = (pkg, replacement) => {
  const re = cdnTag(pkg);
  if (!re.test(html)) throw new Error(`CDN tag for ${pkg} not found in SoCLess.html`);
  // Replacer FUNCTION: minified React contains '$&' sequences, which the
  // string form of replace() would expand to the matched tag.
  html = html.replace(re, () => replacement);
};
replaceTag('react', `<script>/* react@18.3.1 production (vendored) */\n${vendor('react.production.min.js')}\n</script>`);
replaceTag('react-dom', `<script>/* react-dom@18.3.1 production (vendored) */\n${vendor('react-dom.production.min.js')}\n</script>`);
replaceTag('@babel/standalone', '<!-- @babel/standalone not needed: JSX precompiled by tools/ui-build/build.mjs -->');

// 2. Precompile every text/babel block in place.
const OPEN = '<script type="text/babel">';
let blocks = 0;
let idx = 0;
while ((idx = html.indexOf(OPEN, idx)) !== -1) {
  const start = idx + OPEN.length;
  const end = html.indexOf('</script>', start);
  if (end === -1) throw new Error('unterminated text/babel block');
  const jsx = html.slice(start, end);
  const { code } = Babel.transform(jsx, {
    presets: ['react'],
    // Keep output readable and diffs reviewable; no minification.
    compact: false,
  });
  if (code.includes('</script')) {
    throw new Error('compiled block contains </script and cannot be inlined');
  }
  html = html.slice(0, idx) + '<script>\n' + code + '\n' + html.slice(end);
  idx = idx + 1;
  blocks += 1;
}
if (blocks === 0) throw new Error('no text/babel blocks found; source layout changed?');

// 3. Banner.
const doctype = /^<!doctype html>/i;
if (!doctype.test(html)) throw new Error('expected the file to start with a doctype');
html = html.replace(
  doctype,
  (m) => m + '\n<!-- GENERATED FILE - DO NOT EDIT.\n     Built from ui/SoCLess.html by tools/ui-build/build.mjs (JSX precompiled,\n     production React inlined). Edit the source, then rebuild:\n       cd tools/ui-build && npm ci && node build.mjs -->',
);

writeFileSync(outPath, html);
const kb = (n) => Math.round(n / 1024);
console.log(`[ui-build] wrote ${outPath} (${kb(html.length)} KB, ${blocks} JSX blocks precompiled)`);
if (/unpkg\.com/.test(html)) {
  console.error('[ui-build] ERROR: dist still references unpkg.com');
  process.exit(1);
}
