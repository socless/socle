// Build ui/SoCLess.scala.html from ui/SoCLess.scala.src.html (epic #172).
//
// The Scala.js + Laminar frontend's shipped, self-contained artifact (embedded
// in the socless JAR as SoCLess.html): the dev template loads elkjs from a CDN
// and the Laminar app from a relative ui/socless-ui.js; this inlines both so the
// result is one offline single file.
//
// Usage:  cd tools/ui-build && node build-scala.mjs
//   (run `tools/refresh-socless-ui.sh` first to refresh ui/socless-ui.js)

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const srcPath = join(repo, 'ui', 'SoCLess.scala.src.html');
const outPath = join(repo, 'ui', 'SoCLess.scala.html');

let html = readFileSync(srcPath, 'utf8');

const inlineFile = (abs, label) => {
  const code = readFileSync(abs, 'utf8');
  if (code.includes('</script')) throw new Error(`${label} contains '</script' and cannot be inlined safely`);
  return code;
};

// 1. elkjs: swap the CDN tag for the vendored bundle (already SHA-pinned by
//    build.mjs; here we just inline the same file).
{
  const re = /<script src="https:\/\/unpkg\.com\/elkjs@([^/"]+)[^"]*"[^>]*><\/script>/;
  const m = html.match(re);
  if (!m) throw new Error('elkjs CDN tag not found in SoCLess.scala.src.html');
  const code = inlineFile(join(here, 'vendor', 'elk.bundled.js'), 'elk.bundled.js');
  html = html.replace(re, () => `<script>/* elkjs@${m[1]} bundled (vendored) */\n${code}\n</script>`);
}

// 2. socless-ui: the committed Laminar+engine artifact.
{
  const re = /<script src="socless-ui\.js"><\/script>/;
  if (!re.test(html)) throw new Error('socless-ui.js script tag not found in SoCLess.scala.src.html');
  const code = inlineFile(join(repo, 'ui', 'socless-ui.js'), 'socless-ui.js');
  html = html.replace(re, () => `<script>/* socless-ui (Scala.js + Laminar, vendored ui/socless-ui.js) */\n${code}\n</script>`);
}

// 3. Banner.
const doctype = /^<!doctype html>/i;
if (!doctype.test(html)) throw new Error('expected the file to start with a doctype');
html = html.replace(doctype, (m) =>
  m + '\n<!-- GENERATED FILE - DO NOT EDIT.\n     Built from ui/SoCLess.scala.src.html by tools/ui-build/build-scala.mjs\n' +
      '     (Scala.js + Laminar frontend inlined offline). Edit the source/Scala, then:\n' +
      '       tools/refresh-socless-ui.sh && (cd tools/ui-build && node build-scala.mjs) -->');

writeFileSync(outPath, html);
const kb = (n) => Math.round(n / 1024);
console.log(`[ui-build] wrote ${outPath} (${kb(html.length)} KB)`);
if (/unpkg\.com/.test(html)) {
  console.error('[ui-build] ERROR: scala UI dist still references unpkg.com');
  process.exit(1);
}
