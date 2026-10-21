#!/usr/bin/env bash
# Unit tests for the static-field-bridge pass (rules/staticfieldbridges.txt, phase 5.20):
# GETSTATIC of a field removed from the 21.1 static registries (BuiltInRegistries
# .ENCHANTMENT — the data-driven-enchantment whale) -> INVOKESTATIC on a shim that
# returns the LIVE server-side registry. Erased-desc equality makes the rewrite
# stack- and frame-neutral. Also pins the isCurse |static memberretarget over the
# same shim (Enchantment became a final record with no curse member; the 21.1
# semantic is the vanilla curse tag).
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
cd "$MB"

# platform jars: the shim compiles against the same set build-shims.sh uses
SRV=$(ls "$LIB"/net/minecraft/server/1.21.1-*/server-*-srg.jar)
NJAR=$(ls "$LIB"/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar)
BUS=$(ls "$LIB"/net/neoforged/bus/*/bus-*.jar)
LOADER=$(ls "$LIB"/net/neoforged/fancymodloader/loader/*/loader-*.jar)
GSON=$(ls "$LIB"/com/google/code/gson/gson/*/gson-*.jar | head -n 1)
DFU=$(ls "$LIB"/com/mojang/datafixerupper/*/datafixerupper-*.jar | head -n 1)
BRIG=$(ls "$LIB"/com/mojang/brigadier/*/brigadier-*.jar | head -n 1)
FAST=$(ls "$LIB"/it/unimi/dsi/fastutil/*/fastutil-*.jar | head -n 1)
PLATFORM_CP="$SRV:$NJAR:$BUS:$LOADER:$GSON:$DFU:$BRIG:$FAST"

rm -rf build/sfbtest
mkdir -p build/sfbtest
PASS=0; FAIL=0

check() {
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== compile rewriter + shims + tests ==="
"$JDK/bin/javac" -cp "tools/lib/*:$PLATFORM_CP" -d build/sfbtest \
  $(find shims/src rewriter/src rewriter/test -name '*.java') 2>/tmp/sfb-javac.log
check "javac shims/src + rewriter/src + rewriter/test compiles clean" $?

echo "=== run StaticFieldBridgeTest ==="
"$JDK/bin/java" -cp "build/sfbtest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar:$PLATFORM_CP" \
  com.modbridge.rewriter.StaticFieldBridgeTest > /tmp/sfb-out.log 2>&1
RC=$?
cat /tmp/sfb-out.log
check "all static-field-bridge logic checks pass" $RC
grep -q "checks passed" /tmp/sfb-out.log
check "test runner reached completion summary" $?

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
