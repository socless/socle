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
//      @babel/standalone version (preset-react, modern-ES output) and inlined
//      as a plain <script>. See the preset note at the Babel.transform call:
//      this is NOT byte-identical to the dev page's transform, by design.
//
// The dist file is COMMITTED (like the golden snapshots) so the Scala build
// never needs Node; CI rebuilds it and fails if it is out of sync.
//
// Usage:  cd tools/ui-build && npm ci && node build.mjs
//
// The output is deterministic for a given source + dependency lockfile.

import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
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
// The version is captured FROM the dev CDN tag (the source of truth a dev
// would bump) and asserted against the vendored UMD, so upgrading React in
// ui/SoCLess.html without re-vendoring fails the build instead of silently
// shipping a different React major than the dev page (issue #136).
const cdnTag = (pkg) =>
  new RegExp(`<script src="https://unpkg\\.com/${pkg}@([^/"]+)[^"]*"[^>]*></script>`);
const vendorMatchingTag = (pkg, file) => {
  const re = cdnTag(pkg);
  const m = html.match(re);
  if (!m) throw new Error(`CDN tag for ${pkg} not found in SoCLess.html`);
  const version = m[1];
  const code = vendor(file);
  if (!code.includes(version)) {
    throw new Error(
      `${file} does not embed version ${version} from the ${pkg} CDN tag. ` +
      `Re-vendor it (see tools/ui-build/vendor/README.md) before building.`,
    );
  }
  // Replacer FUNCTION: minified React contains '$&' sequences, which the
  // string form of replace() would expand to the matched tag.
  html = html.replace(re, () => `<script>/* ${pkg}@${version} production (vendored) */\n${code}\n</script>`);
};
vendorMatchingTag('react', 'react.production.min.js');
vendorMatchingTag('react-dom', 'react-dom.production.min.js');
{
  const re = cdnTag('@babel/standalone');
  if (!re.test(html)) throw new Error('CDN tag for @babel/standalone not found in SoCLess.html');
  html = html.replace(re, () => '<!-- @babel/standalone not needed: JSX precompiled by tools/ui-build/build.mjs -->');
}

// 1b. Inline elkjs (issue #167). Unlike React, the elk.bundled.js UMD does not
// embed its own version string, so the version-substring assert used above is
// not usable. Pin the vendored bundle by SHA-256 instead: a stronger guarantee
// that the inlined engine is exactly the reviewed artifact, and a bump of the
// dev CDN tag without re-vendoring (+ updating this hash) fails the build.
const ELK_SHA256 = 'b0745abd7f23cd91690a1587e377edbe19fd7233c783300290936720546216d4';
{
  const re = cdnTag('elkjs');
  const m = html.match(re);
  if (!m) throw new Error('CDN tag for elkjs not found in SoCLess.html');
  const version = m[1];
  const code = vendor('elk.bundled.js');
  const sha = createHash('sha256').update(code).digest('hex');
  if (sha !== ELK_SHA256) {
    throw new Error(
      `vendor/elk.bundled.js sha256 ${sha} != pinned ${ELK_SHA256}. ` +
      `Re-vendor elkjs (see tools/ui-build/vendor/README.md) and update ELK_SHA256 in build.mjs.`,
    );
  }
  html = html.replace(re, () => `<script>/* elkjs@${version} bundled (vendored, sha256 ${ELK_SHA256.slice(0, 12)}…) */\n${code}\n</script>`);
}

// 1c. Inline the Scala.js layout engine (issue #167). Unlike the CDN deps this
// is a LOCAL build artifact committed next to the dev page (ui/socless-layout.js,
// from `./mill socless-js.fullLinkJS`), referenced by a relative <script src>.
// Its integrity is guarded in CI by re-linking from the Scala source and diffing
// the committed file (a SHA pin here would be redundant and re-edited on every
// socless-js change), so we just inline it.
{
  const re = /<script src="socless-layout\.js"><\/script>/;
  if (!re.test(html)) throw new Error('socless-layout.js script tag not found in SoCLess.html');
  const code = readFileSync(join(repo, 'ui', 'socless-layout.js'), 'utf8');
  if (code.includes('</script')) throw new Error('socless-layout.js contains </script and cannot be inlined safely');
  html = html.replace(re, () => `<script>/* socless-js layout engine (Scala.js, vendored ui/socless-layout.js) */\n${code}\n</script>`);
}

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
    // preset-react only: the dist intentionally ships MODERN ES (async/await,
    // classes, ...), matching this tool's existing modern-browser baseline
    // (CSS custom properties, oklch, foreignObject). The dev page's in-browser
    // @babel/standalone may apply additional env transforms, but on the modern
    // browsers SoCLess targets the runtime behavior is equivalent; do not rely
    // on byte-for-byte transform parity between dev and dist (issue #136).
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
