#!/usr/bin/env bash
# Re-link the Scala.js layout engine (socless-js) and refresh the committed
# artifact ui/socless-layout.js that the dev page loads and build.mjs inlines
# into the dist (issue #167).
#
# Run this after editing anything under socless-js/ or the shared
# Schema.scala / LayoutCore.scala, then rebuild the dist:
#   tools/refresh-socless-js.sh
#   cd tools/ui-build && node build.mjs
# and commit ui/socless-layout.js + ui/SoCLess.dist.html together.
#
# CI runs the same steps and fails if the committed artifact is stale.
set -euo pipefail
cd "$(dirname "$0")/.."

./mill socless-js.fullLinkJS
SRC="out/socless-js/fullLinkJS.dest/main.js"

# Strip the trailing //# sourceMappingURL comment so the offline single-file
# page never tries to fetch the (absent) .map.
node -e '
  const fs = require("fs");
  let js = fs.readFileSync(process.argv[1], "utf8");
  js = js.replace(/\n?\/\/# sourceMappingURL=.*\s*$/, "\n");
  if (js.includes("</script")) throw new Error("linked JS contains </script and cannot be inlined safely");
  fs.writeFileSync("ui/socless-layout.js", js);
  console.log("[refresh-socless-js] wrote ui/socless-layout.js (" + js.length + " bytes)");
' "$SRC"
