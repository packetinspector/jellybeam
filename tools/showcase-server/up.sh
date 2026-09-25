#!/usr/bin/env bash
# Showcase Jellyfin for screenshots: a public-domain and CC-BY library (catalog.tsv) with real
# metadata and art, so marketing shots never show a personal library. Downloads the three Blender
# films played on screen; every other title is a stub of the right runtime (a static frame looped
# with stream copy), which is all the scanner needs to match and fetch metadata.
# Idempotent: existing media, config and seed state are kept. Everything here is synthetic.
#
#   tools/showcase-server/up.sh          # emulator: http://10.0.2.2:8098, user sam / showcase
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NAME="${SHOWCASE_CONTAINER:-jellybeam-showcase}"
IMAGE="${SHOWCASE_IMAGE:-jellyfin/jellyfin:10.11.11}"
PORT="${SHOWCASE_PORT:-8098}"
DATA="${SHOWCASE_DIR:-$ROOT/internal/showcase}"
MEDIA="$DATA/media"
SEG="$DATA/segments"

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
mkdir -p "$MEDIA" "$SEG" "$DATA/config" "$DATA/cache" "$DATA/downloads"

# One-minute segments per profile; stubs are these looped with stream copy, so 40 titles build in seconds.
segment() {
    local out="$SEG/$1.mkv"
    [ -f "$out" ] && { echo "$out"; return; }
    case "$1" in
        1080) ffmpeg -nostdin -v error -f lavfi -i "color=c=0x14100D:s=1920x1080:r=24" -f lavfi -i "anullsrc=cl=5.1:r=48000" -t 60 \
                -c:v libx264 -preset ultrafast -tune stillimage -x264-params keyint=1440:scenecut=0 -pix_fmt yuv420p \
                -c:a eac3 -b:a 32k -f matroska "$out.part" && mv "$out.part" "$out" ;;
        4k)   ffmpeg -nostdin -v error -f lavfi -i "color=c=0x14100D:s=3840x2160:r=24" -f lavfi -i "anullsrc=cl=5.1:r=48000" -t 60 \
                -c:v libx265 -preset ultrafast -x265-params keyint=1440:log-level=error -pix_fmt yuv420p10le -tag:v hvc1 \
                -c:a eac3 -b:a 32k -f matroska "$out.part" && mv "$out.part" "$out" ;;
        sd)   ffmpeg -nostdin -v error -f lavfi -i "color=c=0x14100D:s=1440x1080:r=24" -f lavfi -i "anullsrc=cl=stereo:r=48000" -t 60 \
                -c:v libx264 -preset ultrafast -tune stillimage -x264-params keyint=1440:scenecut=0 -pix_fmt yuv420p \
                -c:a aac -b:a 16k -f matroska "$out.part" && mv "$out.part" "$out" ;;
    esac
    echo "$out"
}

stub() {  # stub PROFILE MINUTES OUT
    [ -f "$3" ] && return
    ffmpeg -nostdin -v error -stream_loop -1 -i "$(segment "$1")" -t "$(( $2 * 60 ))" -c copy -f matroska "$3.part" && mv "$3.part" "$3"
}

real() {  # real URL OUT
    [ -f "$2" ] && return
    local dl="$DATA/downloads/$(basename "$1")"
    if [ ! -f "$dl" ]; then curl -fL --retry 3 -o "$dl.part" "$1" && mv "$dl.part" "$dl"; fi
    if [[ "$dl" == *.zip ]]; then
        local inner; inner="$(unzip -Z1 "$dl" | grep -vE '/$|__MACOSX' | head -1)"
        unzip -p "$dl" "$inner" > "$2.part" && mv "$2.part" "$(dirname "$2")/$(basename "$2" .mkv).${inner##*.}"
    else
        cp "$dl" "$(dirname "$2")/$(basename "$2" .mkv).${dl##*.}"
    fi
}

while IFS=$'\t' read -r kind library title minutes profile source; do
    [[ -z "$kind" || "$kind" == \#* ]] && continue
    dir="$MEDIA/$library/$title"
    mkdir -p "$dir"
    if [ "$kind" = movie ]; then
        base="$dir/$title.mkv"
        if [ "$profile" = real ]; then
            ls "$dir"/"$title".* >/dev/null 2>&1 || real "$source" "$base"
        else
            stub "$profile" "$minutes" "$base"
        fi
    else
        show="${title% (*}"
        from="${source%-*}"; to="${source#*-}"
        season="${from:1:2}"; first="${from:4:2}"; last="${to:4:2}"
        mkdir -p "$dir/Season $season"
        for ((e = 10#$first; e <= 10#$last; e++)); do
            stub "$profile" "$minutes" "$dir/Season $season/$show S${season}E$(printf %02d "$e").mkv"
        done
    fi
done < "$HERE/catalog.tsv"
echo "media ready: $(du -sh "$MEDIA" | cut -f1) in $MEDIA"

if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:8096" \
        -v "$DATA/config:/config" -v "$DATA/cache:/cache" -v "$MEDIA:/media:ro" "$IMAGE" >/dev/null
fi
python3 "$HERE/seed.py" "http://localhost:${PORT}"
echo "Showcase ready at http://localhost:${PORT} (emulator: http://10.0.2.2:${PORT}), user sam / showcase"
