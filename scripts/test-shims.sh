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
# phase 5.13: the item-tag view's RUNTIME checks bootstrap the vanilla registry
# cascade (Bootstrap.bootStrap -> Items/Blocks/CreativeModeTabs.validate) - the
# cascade pulls guava(+failureaccess), netty, joml, commons-lang3, slf4j/log4j,
# mojang logging and authlib; the lang asset lives in rewriter/test/resources
# guava must be the 32.x the game ships (20.0 is a stale leftover of another lib;
# SoundEvents needs ImmutableList.toImmutableList, absent before 32.0.0)
GUAVA=$(ls "$LIB"/com/google/guava/guava/*/guava-*.jar | sort -V | tail -n 1)
FAILACC=$(ls "$LIB"/com/google/guava/failureaccess/*/failureaccess-*.jar | sort -V | tail -n 1)
NETTY=$(find "$LIB"/io/netty -name "netty-*.jar" ! -name "*native*" | tr '\n' ':')
JOML=$(ls "$LIB"/org/joml/joml/*/joml-*.jar | head -n 1)
LANG3=$(ls "$LIB"/org/apache/commons/commons-lang3/*/commons-lang3-*.jar | head -n 1)
SLF4J=$(ls "$LIB"/org/slf4j/slf4j-api/*/slf4j-api-*.jar | head -n 1)
# NOTE: log4j-core/slf4j2-impl deliberately EXCLUDED - with them on the classpath
# the vanilla cascade binds the real log4j config, which RE-ROUTES System.out
# through its console appender and (appender class missing in this env) drops
# the test output; slf4j-api alone NOPs (provider-less) and stdout stays ours
MLOG=$(ls "$LIB"/com/mojang/logging/*/logging-*.jar | head -n 1)
AUTHLIB=$(ls "$LIB"/com/mojang/authlib/*/authlib-*.jar | head -n 1)
JSR305=$(ls "$LIB"/com/google/code/findbugs/jsr305/*/jsr305-*.jar | head -n 1)
RESOURCES="$MB/rewriter/test/resources"
PLATFORM_CP="$SRV:$NJAR:$BUS:$LOADER:$GSON:$DFU:$BRIG:$FAST:$GUAVA:$FAILACC:$NETTY:$JOML:$LANG3:$SLF4J:$MLOG:$AUTHLIB:$JSR305"

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
# shimtest FIRST: the loot-lane tests assert the shims ARE what loads at the
# vanilla-named loot types inside a translated jar
"$JDK/bin/java" -cp "build/shimtest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar:$PLATFORM_CP" \
  com.modbridge.rewriter.ShimFacadeTest > /tmp/shimtest-out.log 2>&1
check "all shim-facade checks pass" $?
grep -q "checks passed" /tmp/shimtest-out.log
check "test runner reached completion summary" $?
cat /tmp/shimtest-out.log

echo "=== run ItemTagViewTest (phase 5.13, own JVM: platform jars first) ==="
# the OPPOSITE order: the item-tag view's runtime checks bootstrap vanilla, and
# the bootstrapped classes must not see the loot-lane shims parked at vanilla
# package names (mutually exclusive with the loot tests' shadowing assertion,
# hence the split). BUS/LOADER are dropped too: with the FML loader jar on the
# classpath the bootstrap JVM exits 0 mid-cascade (a loader static hook), which
# the item-tag checks don't need.
VIEW_CP="$SRV:$NJAR:$GSON:$DFU:$BRIG:$FAST:$GUAVA:$FAILACC:$NETTY:$JOML:$LANG3:$SLF4J:$MLOG:$AUTHLIB:$JSR305"
"$JDK/bin/java" -cp "$VIEW_CP:$RESOURCES:build/shimtest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar" \
  com.modbridge.rewriter.ItemTagViewTest > /tmp/itemtagtest-out.log 2>&1
check "all item-tag-view checks pass" $?
grep -q "checks passed" /tmp/itemtagtest-out.log
check "item-tag-view runner reached completion summary" $?
cat /tmp/itemtagtest-out.log

echo
if [ "$FAIL" -ne 0 ]; then
  echo "shim tests: $PASS passed, $FAIL failed (javac log: /tmp/shimtest-javac.log)"
  exit 1
fi
echo "shim tests: $PASS script checks, all green"
