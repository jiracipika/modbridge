#!/usr/bin/env bash
# Compiles the phase-5 shim carrier classes (shims/src) into rules/shims/ and writes
# the shim manifest (shims.txt). The rewriter injects every listed class into each
# translated mod jar so the retargeted removed-API names resolve inside the mod's own
# module. Compiled against the sandbox's 1.21.1 platform jars (minecraft srg jar for
# vanilla names, neoforge universal for Event) — descriptor shapes must match what
# 1.20.1 production mods call after the type rules run.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
OUT="$MB/rules/shims"

SRV=$(ls "$LIB"/net/minecraft/server/1.21.1-*/server-*-srg.jar)
NJAR=$(ls "$LIB"/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar)
BUS=$(ls "$LIB"/net/neoforged/bus/*/bus-*.jar)
LOADER=$(ls "$LIB"/net/neoforged/fancymodloader/loader/*/loader-*.jar)

rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/bin/javac" --release 17 -cp "$SRV:$NJAR:$BUS:$LOADER" -d "$OUT" $(find "$MB/shims/src" -name '*.java')

# list EVERY class file, including anonymous inner classes (ForgeCapabilities$1/$2):
# the rewriter injects and relocates exactly this list, so a holder without its
# nested token classes would NoClassDefFoundError at clinit.
{
  cd "$OUT"
  find . -name '*.class' | sed 's|^\./||; s|\.class$||' | sort
} > "$OUT/shims.txt"

echo "shims built:"
cat "$OUT/shims.txt"
