#!/usr/bin/env bash
# Runs the ModBridge rewriter: 1.20.1 Forge jar -> NeoForge 1.21.1 jar.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
IN="${1:-$MB/out/fixture-1.20.1.jar}"
OUT="${2:-$MB/out/modbridgefixture-1.21.1-neoforge.jar}"
cd "$MB"
if [ ! -d build/rewriter ]; then
  mkdir -p build/rewriter
  "$JDK/bin/javac" -cp "tools/lib/*" -d build/rewriter $(find rewriter/src -name '*.java')
fi
"$JDK/bin/java" -cp "build/rewriter:tools/lib/*" com.modbridge.rewriter.Rewriter "$IN" "$OUT" rules
