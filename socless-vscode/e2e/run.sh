#!/usr/bin/env bash
# End-to-end check of the staged SoCLess extension in a headless VS Code.
#
#   ./mill socless-vscode.stage
#   socless-vscode/e2e/run.sh out/socless-vscode/stage.dest
#
# Not a Mill command on purpose: the language server inside VS Code runs `mill --no-daemon`
# on this repo, which would contend with a Mill process running the test.
# The exit code is VS Code's: 0 when every step of index.js passed.
# VS Code gets a remote debugging port so webview-click.mjs (run in the background) can drive the
# webviews (the SoCLess panel, the Surfer tab) for steps 7 to 10; the screenshots and the driver's
# results are kept in $SOCLESS_E2E_ARTIFACTS
# (default ${TMPDIR:-/tmp}/socless-e2e-artifacts).
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <stageDir>" >&2
  exit 2
fi
stage=$(cd "$1" && pwd)
e2e=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$e2e/../.." && pwd)

# The VS Code Electron binary itself, not the `code` CLI: the CLI starts VS Code detached and
# exits 0 at once, so its exit code would not be the test result.
# Order: $SOCLESS_E2E_ELECTRON, then next to the resolved `code` CLI, then /usr/share/code/code.
find_electron() {
  if [[ -n "${SOCLESS_E2E_ELECTRON:-}" ]]; then
    echo "$SOCLESS_E2E_ELECTRON"
    return
  fi
  local cli bin
  if cli=$(command -v code) && cli=$(readlink -f "$cli"); then
    bin=$(dirname "$cli")
    # Linux: <root>/bin/code -> <root>/code
    # macOS: .../Contents/Resources/app/bin/code -> .../Contents/MacOS/Electron (or Code)
    for c in "$bin/../code" "$bin/../../../MacOS/Electron" "$bin/../../../MacOS/Code"; do
      if [[ -f "$c" && -x "$c" ]]; then
        readlink -f "$c"
        return
      fi
    done
  fi
  echo /usr/share/code/code
}
electron=$(find_electron)
if [[ ! -x "$electron" ]]; then
  echo "e2e: VS Code binary $electron not found; set SOCLESS_E2E_ELECTRON" >&2
  exit 2
fi
echo "e2e: VS Code binary $electron"

# The screenshot and the driver's result survive the run here (the temp dir is trashed).
artifacts=${SOCLESS_E2E_ARTIFACTS:-${TMPDIR:-/tmp}/socless-e2e-artifacts}
mkdir -p "$artifacts"
for f in "$artifacts"/*.png "$artifacts"/*-result*.json "$artifacts/click.log"; do
  [[ -f "$f" ]] && { trash "$f" 2>/dev/null || true; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/socless-e2e.XXXXXX")
mkdir -p "$tmp/user-data/User" "$tmp/extensions"
# A window filling the xvfb screen, so the webview is wide enough for step 7 to click a box.
echo '{ "window.newWindowDimensions": "fullscreen" }' >"$tmp/user-data/User/settings.json"
cdp_port=$(node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')

cleanup() {
  # The screenshots, the driver's results (click, drill, state, theme, wave-result-<n>) and its log.
  for f in "$tmp"/*.png "$tmp"/*-result*.json "$tmp/click.log"; do
    [[ -f "$f" ]] && cp "$f" "$artifacts/"
  done
  echo "e2e: artifacts (screenshot, click result, driver log) in $artifacts"
  # Leftovers (language server, helpers) of this run only: the temp path is unique to it.
  if pgrep -f "$tmp" >/dev/null 2>&1; then
    pkill -f "$tmp" || true
    sleep 1
  fi
  if command -v trash >/dev/null 2>&1; then
    trash "$tmp"
  else
    echo "e2e: left temp dir $tmp (no trash command)"
  fi
}
trap cleanup EXIT

# Run from a VS Code terminal, the environment carries ELECTRON_RUN_AS_NODE and the parent's
# VSCODE_* handles, which would turn the binary into plain Node or attach it to that instance.
for v in $(compgen -e | grep -E '^(ELECTRON_|VSCODE_)' || true); do unset "$v"; done

# The webview driver waits for index.js's click request; its args hold $tmp, so cleanup reaps it.
node "$e2e/webview-click.mjs" "$tmp" "$cdp_port" >"$tmp/click.log" 2>&1 &

# A fixed, roomy screen so the webview shows the schematic boxes step 7 clicks.
status=0
SOCLESS_E2E_DIR="$tmp" xvfb-run -a -s "-screen 0 1600x1000x24" "$electron" \
  --remote-debugging-port="$cdp_port" \
  --user-data-dir "$tmp/user-data" \
  --extensions-dir "$tmp/extensions" \
  --disable-extensions \
  --disable-workspace-trust \
  --skip-welcome \
  --skip-release-notes \
  --extensionDevelopmentPath="$stage" \
  --extensionTestsPath="$e2e" \
  "$repo" || status=$?

echo "e2e: webview driver log:"
cat "$tmp/click.log" 2>/dev/null || true
if [[ $status -ne 0 ]]; then
  echo "e2e: FAILED (exit $status); SoCLess language server output:"
  find "$tmp/user-data/logs" -name '*SoCLess*.log' -print -exec cat {} \; 2>/dev/null || true
fi
exit $status
