#!/usr/bin/env bash
# docs/06 "Patched Media3": rebuilds androidx.media3:media3-exoplayer:1.11.1-jb1 from Google's 1.11.1
# AAR by recompiling only the classes the .patch touches (tag-1.11.1 sources + the patch) and swapping
# them in; every other class stays byte-identical to the release. Inputs are pinned in inputs.sha256.
#
#   tools/media3-patch/build.sh           build into third_party/maven and print the AAR's sha256
#   tools/media3-patch/build.sh --check   rebuild in a temp dir and compare with the committed AAR
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SERVICE="${JELLYBEAM_BUILD_SERVICE:-android-build-arm64}"

# javac and android.jar live in the build container; re-run there (the checkout is /app).
if [ ! -d /app/tools/media3-patch ] || [ "$ROOT" != /app ]; then
  cd "$ROOT"
  docker compose up -d --no-recreate "$SERVICE" >/dev/null
  exec docker compose exec -T -w /app "$SERVICE" tools/media3-patch/build.sh "$@"
fi

VERSION=1.11.1-jb1
BASE=1.11.1
REPO_DIR="$ROOT/third_party/maven/androidx/media3/media3-exoplayer/$VERSION"
NAME="media3-exoplayer-$VERSION"
# Fixed entry time so the same inputs give the same bytes.
DATE=1980-02-01T00:00:00Z
SDK="${ANDROID_HOME:-/opt/android-sdk}"
# What AGP compiles Java against: the platform jar plus the lambda stubs (javac emits invokedynamic, D8 desugars).
BOOT="$SDK/platforms/android-36/android.jar:$SDK/build-tools/35.0.0/core-lambda-stubs.jar"
CHECK=0
[ "${1:-}" = --check ] && CHECK=1

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/in" "$W/cp" "$W/src" "$W/out" "$W/classes" "$W/aar" "$W/dist"

# 1. Pinned inputs: fetch and verify every file.
grep -v '^#' "$HERE/inputs.sha256" | while read -r sum file url; do
  [ -n "$sum" ] || continue
  curl -sfL --retry 3 -o "$W/in/$file" "$url"
  echo "$sum  $W/in/$file" | sha256sum -c --quiet -
done

# 2. Compile classpath: the released AARs' classes.jar plus the plain jars.
for f in "$W"/in/*.aar; do
  n="$(basename "$f" .aar)"
  unzip -q -p "$f" classes.jar > "$W/cp/$n.jar"
done
cp "$W"/in/*.jar "$W/cp/"
CP="$(ls "$W"/cp/*.jar | LC_ALL=C sort | paste -sd: -)"

# 3. Tag sources, compiled as released (Java 8 class files, like Media3 1.11.1's).
PKG=libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio
AUDIO=androidx/media3/exoplayer/audio
mkdir -p "$W/src/$PKG" "$W/out0" "$W/release"
cp "$W/in/AudioTrackPositionTracker.java" "$W/in/DefaultAudioSink.java" "$W/src/$PKG/"
compile() { # $1 output dir
  javac -nowarn -Xlint:-options -proc:none -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$BOOT" -cp "$CP" -d "$1" "$W/src/$PKG"/*.java
}
compile "$W/out0"

# Toolchain proof: the unpatched sources must compile to Google's released bytecode (instructions
# and members; constant-pool numbering ignored), so the patched classes differ by the patch alone.
(cd "$W/release" && unzip -q "$W/cp/media3-exoplayer-$BASE.jar" "$AUDIO/AudioTrackPositionTracker*" "$AUDIO/DefaultAudioSink*")
names() { (cd "$1" && find . -name '*.class' | LC_ALL=C sort); }
[ "$(names "$W/release")" = "$(names "$W/out0")" ] \
  || { echo "media3-patch: toolchain does not reproduce the released class set; not building" >&2; exit 1; }
norm() { javap -c -p -constants -cp "$1" "$2" | sed -E 's/#[0-9]+(, *[0-9]+)?//g; s/^ *[0-9]+: //'; }
for c in $(cd "$W/release" && find . -name '*.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | LC_ALL=C sort); do
  diff -q <(norm "$W/release" "$c") <(norm "$W/out0" "$c") >/dev/null \
    || { echo "media3-patch: toolchain does not reproduce $c; not building" >&2; exit 1; }
done

# 4. The patch, compiled the same way.
(cd "$W/src" && patch -p1 --fuzz=0 -N --quiet --no-backup-if-mismatch < "$HERE/0001-position-decrease-is-not-a-wrap.patch")
compile "$W/out"

# 5. Swap exactly the recompiled top-level classes (with all their nested classes) into classes.jar.
(cd "$W/classes" && unzip -q "$W/cp/media3-exoplayer-$BASE.jar")
for top in AudioTrackPositionTracker DefaultAudioSink; do
  rm -f "$W/classes/$AUDIO/$top.class" "$W/classes/$AUDIO/$top\$"*.class
  cp "$W/out/$AUDIO/$top.class" "$W/out/$AUDIO/$top\$"*.class "$W/classes/$AUDIO/"
done

# jar in a fixed entry order and time; no manifest (the release classes.jar has none).
pack() { # $1 dir, $2 output
  (cd "$1" && find . -type f | sed 's|^\./||' | LC_ALL=C sort | awk '{print "-C"; print "."; print $0}') > "$W/args"
  (cd "$1" && jar --create --no-manifest --date="$DATE" --file "$2" @"$W/args")
}
(cd "$W/aar" && unzip -q "$W/in/media3-exoplayer-$BASE.aar")
pack "$W/classes" "$W/aar/classes.jar"
pack "$W/aar" "$W/dist/$NAME.aar"

# 6. POM: the release POM at the patched version (deps unchanged), without the Gradle-metadata
# marker since no .module file is published.
# Only the first <version> is the project's; the dependencies keep theirs.
sed -e "0,/<version>$BASE<\/version>/s//<version>$VERSION<\/version>/" \
    -e '/do_not_remove: published-with-gradle-metadata/d' \
    -e '/richer model, Gradle metadata/d' -e '/should be used instead/d' \
    -e '/they should prefer consuming it instead/d' -e '/is to indicate to Gradle/d' \
    -e "s|<name>Media3 ExoPlayer module</name>|<name>Media3 ExoPlayer module</name>\n  <description>Media3 $BASE with androidx/media commit 4c95cd9 backported (Jellybeam tools/media3-patch).</description>|" \
    "$W/in/media3-exoplayer-$BASE.pom" > "$W/dist/$NAME.pom"

SUM="$(sha256sum "$W/dist/$NAME.aar" | cut -d' ' -f1)"
if [ "$CHECK" = 1 ]; then
  HAVE="$(sha256sum "$REPO_DIR/$NAME.aar" | cut -d' ' -f1)"
  [ "$SUM" = "$HAVE" ] && cmp -s "$W/dist/$NAME.pom" "$REPO_DIR/$NAME.pom" \
    && { echo "media3-patch: reproduced $NAME.aar $SUM"; exit 0; }
  echo "media3-patch: MISMATCH built $SUM, committed $HAVE" >&2
  exit 1
fi
mkdir -p "$REPO_DIR"
cp "$W/dist/$NAME.aar" "$W/dist/$NAME.pom" "$REPO_DIR/"
echo "$SUM  $NAME.aar" > "$HERE/output.sha256"
echo "media3-patch: $REPO_DIR/$NAME.aar $SUM"
