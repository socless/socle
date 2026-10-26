#!/usr/bin/env bash
# Mill selective execution for CI pull requests: run only the tasks whose
# inputs changed since the PR's base commit.
#
#   tools/ci/selective.sh prepare <base-sha>   snapshot the base commit, then return to HEAD
#                                              (no snapshot when .github/workflows or tools/ci changed)
#   tools/ci/selective.sh run <selector>       run <selector>, selectively when a snapshot exists
#   tools/ci/selective.sh selected <selector>  print the tasks of <selector> that would run
#
# Without a snapshot (pushes to main, or a base that could not be snapshotted)
# `run` runs everything and `selected` prints the whole selector, so the
# fallback is always the full CI.
#
# Mill gotchas this script exists for:
# - One selector per call: `./mill selective.run a.test b.test` passes `b.test`
#   as an argument to `a.test`, and `+` starts a separate top-level command.
#   Combine tasks in one brace selector instead: '{a.test,b.dist}'.
# - The snapshot only covers the tasks given to `prepare`; any other task counts
#   as changed. So `prepare` takes ALL_TASKS, the union of every selector used.
# - --no-daemon: no daemon state carried across the git checkouts.
set -euo pipefail

ALL_TASKS='{socless.test,socless-probe.test,socless-vscode.test,socless-lsp.test,socless-ui.dist,socless.assembly,example.soclessRun,example.soclessRunPipeline,example.soclessRunGcd,example.soclessRunDeepSoc,example.soclessRunIoBundles,example.soclessRunRegHeavy,example.soclessRunFsm,example.soclessRunLayers,example.soclessRunScatter,example.soclessCapturedDeepSoc,example.soclessCapturedFsm,example.soclessCapturedGcd}'
SNAPSHOT=out/mill-selective-execution.json
MILL=(./mill --no-daemon --ticker false)

cmd=${1:?usage: selective.sh prepare <base-sha> | run <selector> | selected <selector>}
arg=${2:?missing argument}

case "$cmd" in
  prepare)
    # A change to CI itself must test itself: no snapshot, so every job runs
    # everything, as on a push to main.
    if [ -n "$(git diff --name-only "$arg" HEAD -- .github/workflows tools/ci)" ]; then
      echo "CI itself changed (.github/workflows or tools/ci): no snapshot, running everything"
      rm -f "$SNAPSHOT"
      exit 0
    fi
    # Come back to the branch when on one (a local run), else to the commit
    # (CI checks out a detached merge commit).
    head=$(git symbolic-ref -q --short HEAD || git rev-parse HEAD)
    git checkout -q "$arg"
    # A base without one of ALL_TASKS (a task the PR adds) cannot be
    # snapshotted: fall back to the full run rather than fail the build.
    if ! "${MILL[@]}" selective.prepare "$ALL_TASKS"; then
      echo "::warning::selective.prepare failed on the base commit; running everything"
      rm -f "$SNAPSHOT"
    fi
    git checkout -q "$head"
    ;;
  run)
    if [ -f "$SNAPSHOT" ]; then "${MILL[@]}" selective.run "$arg"
    else "${MILL[@]}" "$arg"; fi
    ;;
  selected)
    if [ -f "$SNAPSHOT" ]; then "${MILL[@]}" selective.resolve "$arg" 2>/dev/null
    else echo "$arg"; fi
    ;;
  *)
    echo "unknown command: $cmd" >&2; exit 2
    ;;
esac
