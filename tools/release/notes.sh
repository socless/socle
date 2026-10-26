#!/usr/bin/env bash
# Print the release notes of one version: its section of the changelog, from
# the `## [<version>]` or `## <version>` heading (exclusive) to the next `## `
# heading. Used by .github/workflows/release.yml; runs locally too.
#
#   tools/release/notes.sh <version> [changelog]   # changelog: CHANGELOG.md
#
# Exits 1 (with a message on stderr) when the changelog, the section, or its
# text is missing.
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 <version> [changelog]" >&2
  exit 2
fi
ver=$1
log=${2:-CHANGELOG.md}

if [ ! -f "$log" ]; then
  echo "error: $log not found: add a '## [$ver]' section for the release notes" >&2
  exit 1
fi

# found=0: no heading for the version; 1: inside its section; 2: past it.
# The version is compared as a string (no regex), and must be followed by
# `]`, a blank or the end of the line, so 0.2.1 does not match 0.2.10.
notes=$(awk -v v="$ver" '
  /^## / {
    if (found == 1) { found = 2; exit }
    h = substr($0, 4); sub(/^\[/, "", h)
    if (index(h, v) == 1) {
      c = substr(h, length(v) + 1, 1)
      if (c == "" || c == "]" || c == " " || c == "\t") { found = 1; next }
    }
  }
  found == 1 { print }
  END { if (found == 0) exit 3 }
' "$log") || {
  echo "error: no '## [$ver]' or '## $ver' section in $log" >&2
  exit 1
}

# Drop the leading blank lines (the command substitution drops the trailing ones).
notes=$(printf '%s\n' "$notes" | sed -e '/./,$!d')
if [ -z "$notes" ]; then
  echo "error: the '$ver' section of $log is empty" >&2
  exit 1
fi
printf '%s\n' "$notes"
