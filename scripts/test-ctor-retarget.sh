#!/usr/bin/env bash
# Unit tests for the ctor-retarget pass (rules/ctorretargets.txt): NEW+DUP+INVOKESPECIAL
# -> GETSTATIC receiver + INVOKEVIRTUAL surgery, including frame stacks inside the
# ctor-arg region (branch among the args) and @SHIM@ rule owners resolving to the
# per-mod shim package — the ModConfig -> ModConfigBridge.registerAndLoad retarget
# (registration + eager COMMON load) that unblocks moonlight's ConfigSpecWrapper
# (trial 002 phase 5.5 + the eager-config slice) — plus the interface call-site
# repair pass (rules/interfaces-1211.txt): Methodref-on-interface constants left by
# 1.20.1 bytecode (the DFU 8 DataResult ICCE wall) repaired to InterfaceMethodref /
# invokeinterface / Object-owner shapes.
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
cd "$MB"

# platform jars for ShimFacadeTest (compiles alongside); loot-lane shims need Gson+DFU
SRV=$(ls "$LIB"/net/minecraft/server/1.21.1-*/server-*-srg.jar)
NJAR=$(ls "$LIB"/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-universal.jar)
BUS=$(ls "$LIB"/net/neoforged/bus/*/bus-*.jar)
LOADER=$(ls "$LIB"/net/neoforged/fancymodloader/loader/*/loader-*.jar)
GSON=$(ls "$LIB"/com/google/code/gson/gson/*/gson-*.jar | head -n 1)
DFU=$(ls "$LIB"/com/mojang/datafixerupper/*/datafixerupper-*.jar | head -n 1)
BRIG=$(ls "$LIB"/com/mojang/brigadier/*/brigadier-*.jar | head -n 1)
FAST=$(ls "$LIB"/it/unimi/dsi/fastutil/*/fastutil-*.jar | head -n 1)
PLATFORM_CP="$SRV:$NJAR:$BUS:$LOADER:$GSON:$DFU:$BRIG:$FAST"

rm -rf build/ctortargettest
mkdir -p build/ctortargettest
PASS=0; FAIL=0

check() {
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== compile rewriter + tests ==="
"$JDK/bin/javac" -cp "tools/lib/*:$PLATFORM_CP" -d build/ctortargettest \
  $(find shims/src rewriter/src rewriter/test -name '*.java') 2>/tmp/ctortest-javac.log
check "javac rewriter/src + rewriter/test compiles clean" $?

echo "=== run CtorRetargetTest ==="
"$JDK/bin/java" -cp "build/ctortargettest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar" \
  com.modbridge.rewriter.CtorRetargetTest > /tmp/ctortest-out.log 2>&1
check "all ctor-retarget logic checks pass" $?
grep -q "checks passed" /tmp/ctortest-out.log
check "test runner reached completion summary" $?
cat /tmp/ctortest-out.log

echo
echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
