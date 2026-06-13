#!/usr/bin/env bash
# Guard against drift between the committed CHIRRTL fixtures
# (socless/test/resources/chirrtl/*.fir, the input to every golden test) and
# what the current example sources + Chisel actually emit. Editing an example's
# Scala, or bumping Chisel (which changes emitted CHIRRTL), would otherwise
# leave the goldens testing stale input while CI stays green (issue #135).
#
# The @[file line:col] source markers carry the ABSOLUTE path of the .scala
# file at elaboration time, which differs per checkout, so we normalize that
# prefix away before comparing: only SEMANTIC drift (structure, line numbers,
# dialect) fails the check.
#
# Run from the repo root: tools/check-chirrtl-fixtures.sh
set -euo pipefail

dir=socless/test/resources/chirrtl
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Strip any absolute prefix up to the 'example/' source anchor inside @[...].
norm() { sed -E 's#@\[[^]]*/(example/)#@[\1#g'; }

for f in "$dir"/*.fir; do norm < "$f" > "$tmp/$(basename "$f").committed"; done
./mill example.dumpChirrtlFixtures >/dev/null 2>&1
for f in "$dir"/*.fir; do norm < "$f" > "$tmp/$(basename "$f").fresh"; done
# Restore the committed bytes (dumpChirrtlFixtures overwrote them in place).
git checkout -- "$dir"

rc=0
for f in "$dir"/*.fir; do
  b=$(basename "$f")
  if ! diff -q "$tmp/$b.committed" "$tmp/$b.fresh" >/dev/null 2>&1; then
    echo "CHIRRTL fixture drift in $b (committed vs freshly dumped, paths normalized):"
    diff "$tmp/$b.committed" "$tmp/$b.fresh" | head -20
    rc=1
  fi
done

if [ "$rc" = 0 ]; then
  echo "CHIRRTL fixtures are in sync with example/src."
else
  echo
  echo "Re-dump the fixtures and regenerate the goldens, then commit:"
  echo "  ./mill example.dumpChirrtlFixtures && ./mill socless.regenerateGoldens"
fi
exit $rc
