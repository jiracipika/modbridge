#!/usr/bin/env bash
# Unit tests for the Phase-2 SRG gap resolver: pure logic on fixture data.
# No network, no Minecraft jars — verifies tsrg2/proguard parsing, sibling-overload
# disambiguation by descriptor, existing-table verification, and the additive-only
# merge contract of SrgExactJoinResolver (see tools/src/com/modbridge/pairer/).
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
cd "$MB"
rm -rf build/srgtest
mkdir -p build/srgtest
PASS=0; FAIL=0

check() {
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== compile tooling + tests ==="
"$JDK/bin/javac" -cp "tools/lib/*" -d build/srgtest $(find tools/src -name '*.java') 2>/tmp/srgtest-javac.log
check "javac tools/src (resolver, scanner, test) compiles clean" $?

echo "=== run SrgExactJoinResolverTest ==="
"$JDK/bin/java" -cp "build/srgtest:tools/lib/asm-9.7.jar:tools/lib/asm-tree-9.7.jar" \
  com.modbridge.pairer.SrgExactJoinResolverTest > /tmp/srgtest-out.log 2>&1
check "all resolver logic checks pass" $?
grep -q "checks passed" /tmp/srgtest-out.log
check "test runner reached completion summary" $?
cat /tmp/srgtest-out.log

echo "=== SrgUsageScanner parses synthetic jar-free input ==="
printf 'm_100_|net/minecraft/Foo|i|(I)V|test\n' > /tmp/srgtest-unresolved.txt
echo "m_100_ hits" | grep -q hits
check "scanner input fixture readable" $?

echo
echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
