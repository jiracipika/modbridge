#!/usr/bin/env bash
# Unit tests for the phase-5 capability facade: shim runtime semantics, descriptor
# exactness against the measured 1.20.1 call-site inventory, and rules-level checks
# (ToolAction->ItemAbility retarget, frame-array remap, shim injection).
# Compiles shims + rewriter against the sandbox 1.21.1 platform jars (Direction,
# ResourceLocation, bus Event) — see shims/src and ShimFacadeTest.
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
cd "$MB"

SRV=$(ls "$LIB"/net/minecraft/server/1.21.1-*/server-*-srg.jar)
NJAR=$(ls "$LIB"/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar)
BUS=$(ls "$LIB"/net/neoforged/bus/*/bus-*.jar)
LOADER=$(ls "$LIB"/net/neoforged/fancymodloader/loader/*/loader-*.jar)
# loot lane: shim compile+run need Gson (LootSerializer shapes) and DFU (MapCodec)
GSON=$(ls "$LIB"/com/google/code/gson/gson/*/gson-*.jar | head -n 1)
DFU=$(ls "$LIB"/com/mojang/datafixerupper/*/datafixerupper-*.jar | head -n 1)
BRIG=$(ls "$LIB"/com/mojang/brigadier/*/brigadier-*.jar | head -n 1)
FAST=$(ls "$LIB"/it/unimi/dsi/fastutil/*/fastutil-*.jar | head -n 1)
PLATFORM_CP="$SRV:$NJAR:$BUS:$LOADER:$GSON:$DFU:$BRIG:$FAST"

rm -rf build/shimtest
mkdir -p build/shimtest
PASS=0; FAIL=0

check() {
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== compile shims + rewriter + tests ==="
"$JDK/bin/javac" --release 17 -cp "tools/lib/*:$PLATFORM_CP" -d build/shimtest \
  $(find shims/src rewriter/src rewriter/test -name '*.java') 2>/tmp/shimtest-javac.log
check "javac shims/src + rewriter/src compiles clean" $?

echo "=== run ShimFacadeTest ==="
"$JDK/bin/java" -cp "build/shimtest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar:$PLATFORM_CP" \
  com.modbridge.rewriter.ShimFacadeTest > /tmp/shimtest-out.log 2>&1
check "all shim-facade checks pass" $?
grep -q "checks passed" /tmp/shimtest-out.log
check "test runner reached completion summary" $?
cat /tmp/shimtest-out.log

echo
if [ "$FAIL" -ne 0 ]; then
  echo "shim tests: $PASS passed, $FAIL failed (javac log: /tmp/shimtest-javac.log)"
  exit 1
fi
echo "shim tests: $PASS script checks, all green"
