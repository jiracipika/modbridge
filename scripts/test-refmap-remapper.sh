#!/usr/bin/env bash
# Unit tests for the phase-4 mixin resource remap: pure logic on fixture data.
# No network, no Minecraft jars — verifies JSON round-tripping, SRG->mojmap reference
# rewriting, and mixin-config/refmap pruning of neutralized mixins
# (see rewriter/src/com/modbridge/rewriter/RefmapRemapper*.java).
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
cd "$MB"
rm -rf build/refmaptest
mkdir -p build/refmaptest
PASS=0; FAIL=0

check() {
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== compile rewriter + tests ==="
"$JDK/bin/javac" -cp "tools/lib/*" -d build/refmaptest $(find rewriter/src -name '*.java') 2>/tmp/refmaptest-javac.log
check "javac rewriter/src (Json, RefmapRemapper, Rewriter, test) compiles clean" $?

echo "=== run RefmapRemapperTest ==="
"$JDK/bin/java" -cp "build/refmaptest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar" \
  com.modbridge.rewriter.RefmapRemapperTest > /tmp/refmaptest-out.log 2>&1
check "all refmap-remap logic checks pass" $?
grep -q "checks passed" /tmp/refmaptest-out.log
check "test runner reached completion summary" $?
cat /tmp/refmaptest-out.log

echo
echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
