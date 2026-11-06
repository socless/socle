#!/usr/bin/env bash
# Retakes the README screenshots and demo GIF of the Socle UI, dark and light,
# into docs/media/. See README.md here.
#
#   tools/screenshots/run.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
media="$root/docs/media"
out="$here/out"
site="$out/site"

cd "$root"
./mill socle-ui.dist example.socleCapturedPipeline example.socleCapturedFsm
html="$root/out/socle-ui/dist.dest/socle.html"
mkdir -p "$site/pipeline" "$site/fsm" "$out/raw" "$media"
cp "$html" "$site/pipeline/socle.html"
cp "$html" "$site/fsm/socle.html"
cp "$root/out/example/socleCapturedPipeline.dest/design.json" "$site/pipeline/design.json"
cp "$root/out/example/socleCapturedFsm.dest/design.json" "$site/fsm/design.json"

[ -d "$here/node_modules/playwright-core" ] || (cd "$here" && npm install --silent)

port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$site" >"$out/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$port/fsm/design.json" >/dev/null && break; sleep 0.1; done

# Timed frames (<ms>.png) to a GIF of at most 3 MB: 1200 px wide, else smaller.
# Each frame lasts until the next one; the last one 1 s.
to_gif() {
  local frames=$1 gif=$2 list="$1/frames.txt" prev="" width
  : >"$list"
  for f in $(ls "$frames"/*.png | sort); do
    t=$(basename "$f" .png); t=$((10#$t))
    [ -n "$prev" ] && printf "file '%s'\nduration %s\n" "$prevf" "$(awk -v d=$((t - prev)) 'BEGIN{printf "%.3f", d/1000}')" >>"$list"
    prev=$t; prevf=$f
  done
  printf "file '%s'\nduration 1\nfile '%s'\n" "$prevf" "$prevf" >>"$list"
  for width in 1200 1100 1000; do
    ffmpeg -loglevel error -y -f concat -safe 0 -i "$list" \
      -vf "scale=$width:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
      -fps_mode vfr "$gif"
    [ "$(stat -c %s "$gif")" -le $((3 * 1024 * 1024)) ] && return 0
  done
  return 0  # check.sh reports the size
}

for theme in dark light; do
  node "$here/shots.mjs" "http://127.0.0.1:$port" "$theme" "$out/raw"
  for s in schematic inspector fsm; do cp "$out/raw/$s-$theme.png" "$media/"; done
  to_gif "$out/raw/frames-$theme" "$media/hero-$theme.gif"
done

"$here/check.sh" "$media" hero-dark.gif hero-light.gif schematic-dark.png schematic-light.png \
  inspector-dark.png inspector-light.png fsm-dark.png fsm-light.png
