#!/usr/bin/env bash
# Regenerates rules/classlist-1211.txt: the internal class names of the 1.21.1 platform
# (minecraft server + neoforge + fml + eventbus + coremods libraries), taken from the
# sandbox server-121 install. The rewriter consults this inventory for phase-4 mixin
# target/eager-reference drift checks. Regenerate after a NeoForge upgrade.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
OUT="$MB/rules/classlist-1211.txt"

JARS=(
  "$LIB/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar"
  "$LIB/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-server.jar"
  "$LIB/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar"
  "$LIB/net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44.jar"
  "$LIB/net/neoforged/fancymodloader/earlydisplay/4.0.44/earlydisplay-4.0.44.jar"
  "$LIB/net/neoforged/bus/8.0.5/bus-8.0.5.jar"
  "$LIB/net/neoforged/coremods/7.0.3/coremods-7.0.3.jar"
  "$LIB/net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar"
  "$LIB/net/neoforged/JarJarMetadata/0.4.1/JarJarMetadata-0.4.1.jar"
  "$LIB/net/neoforged/JarJarSelector/0.4.1/JarJarSelector-0.4.1.jar"
  "$LIB/net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1.jar"
  "$LIB/net/neoforged/accesstransformers/at-modlauncher/10.0.1/at-modlauncher-10.0.1.jar"
)

{
  echo "# 1.21.1 platform class universe (internal names), generated from the sandbox"
  echo "# server-121 libraries (minecraft srg jar [mojmap class names] + neoforge"
  echo "# server/universal + fml/bus/coremods)."
  echo "# Consulted by the rewriter's phase-4 mixin drift checks; regenerate with"
  echo "# scripts/gen-classlist-1211.sh. Presence here only vouches for existence, nothing else."
  for j in "${JARS[@]}"; do
    [ -f "$j" ] || { echo "missing platform jar: $j" >&2; exit 1; }
    "$JDK/bin/jar" tf "$j" | grep '\.class$' | sed 's/\.class$//'
  done | sort -u
} > "$OUT"
echo "wrote $OUT ($(grep -vc '^#' "$OUT") classes)"
