#!/usr/bin/env bash
# Re-link the Scala.js + Laminar frontend (socless-ui) and refresh the committed
# artifact ui/socless-ui.js that ui/SoCLess.scala.src.html loads and
# build-scala.mjs inlines into ui/SoCLess.scala.html (epic #172).
#
# Run after editing anything under socless-ui/ (or the shared socless sources),
# then rebuild the dist:
#   tools/refresh-socless-ui.sh
#   (cd tools/ui-build && node build-scala.mjs)
# and commit ui/socless-ui.js + ui/SoCLess.scala.html together.
#
# CI runs the same steps and fails if the committed artifact is stale.
set -euo pipefail
cd "$(dirname "$0")/.."

./mill socless-ui.fullLinkJS
SRC="out/socless-ui/fullLinkJS.dest/main.js"

# Strip the trailing //# sourceMappingURL comment for the offline single file.
node -e '
  const fs = require("fs");
  let js = fs.readFileSync(process.argv[1], "utf8");
  js = js.replace(/\n?\/\/# sourceMappingURL=.*\s*$/, "\n");
  if (js.includes("</script")) throw new Error("linked JS contains </script and cannot be inlined safely");
  fs.writeFileSync("ui/socless-ui.js", js);
  console.log("[refresh-socless-ui] wrote ui/socless-ui.js (" + js.length + " bytes)");
' "$SRC"
