#!/usr/bin/env bash
# Checks the README media: each file exists, each PNG is $WIDTH px wide
# (default 1500), each GIF at most 3 MB. Exits 1 naming the first bad file.
#
#   WIDTH=1500 check.sh <mediaDir> <file>...
set -euo pipefail
dir=$1; shift
width=${WIDTH:-1500}
max=$((3 * 1024 * 1024))
for f in "$@"; do
  p="$dir/$f"
  [ -f "$p" ] || { echo "check: missing $f" >&2; exit 1; }
  case "$f" in
    *.png)
      w=$(python3 -c 'import struct,sys; d=open(sys.argv[1],"rb").read(24); print(struct.unpack(">I", d[16:20])[0])' "$p")
      [ "$w" = "$width" ] || { echo "check: $f is $w px wide, expected $width" >&2; exit 1; } ;;
    *.gif)
      s=$(stat -c %s "$p")
      [ "$s" -le "$max" ] || { echo "check: $f is $s bytes, over 3 MB" >&2; exit 1; } ;;
  esac
done
echo "check: ${#} files ok"
