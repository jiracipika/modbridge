#!/usr/bin/env bash
# Full test matrix against the live testbed server:
#   1. smoke     — no mods; server boots (validates the testbed itself)
#   2. negative  — untranslated 1.20.1 jar; server must REJECT it
#   3. positive  — translated jar; server boots, fixture init logged, /fixtureping works via console
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
T="$MB/testenv"
S="$T/server-121"
PASS=0; FAIL=0

check() { # name condition(0=ok)
  if [ "$2" -eq 0 ]; then echo "  [PASS] $1"; PASS=$((PASS+1)); else echo "  [FAIL] $1"; FAIL=$((FAIL+1)); fi
}

echo "=== phase 1: smoke (no mods) ==="
rm -rf "$S/mods"; mkdir -p "$S/mods"
bash "$T/run-server.sh" start > /tmp/matrix-smoke.log 2>&1
grep -q SERVER_UP /tmp/matrix-smoke.log; check "server boots with zero mods" $?
bash "$T/run-server.sh" stop > /tmp/matrix-stop.log 2>&1
grep -q SERVER_STOPPED /tmp/matrix-stop.log; check "clean server stop before next phase" $?
for i in $(seq 1 15); do lsof -ti :25585 >/dev/null 2>&1 || break; sleep 2; done

echo "=== phase 2: negative (untranslated 1.20.1 jar must be rejected) ==="
rm -rf "$S/mods"; mkdir -p "$S/mods"
cp "$MB/out/fixture-1.20.1.jar" "$S/mods/"
bash "$T/run-server.sh" start > /tmp/matrix-neg.log 2>&1
RC=$?
if [ $RC -eq 0 ]; then
  # NeoForge 21.1 skips foreign jars with a warning at boot; the mod must NOT initialize.
  grep -q 'is for Minecraft Forge or an older version of NeoForge' "$S/logs/latest.log"
  check "loader flags untranslated jar (skip warning)" $?
  grep -q 'constructed via 1.20.1 API' "$S/logs/latest.log"
  [ $? -ne 0 ]; check "fixture did not initialize (no constructor line)" $?
else
  check "server booted with untranslated jar (got rc=$RC)" 1
fi
bash "$T/run-server.sh" stop > /tmp/matrix-stop.log 2>&1
grep -q SERVER_STOPPED /tmp/matrix-stop.log; check "clean server stop before next phase" $?
for i in $(seq 1 15); do lsof -ti :25585 >/dev/null 2>&1 || break; sleep 2; done

echo "=== phase 3: connector flow (drop 1.20.1 jar into source-1201, agent translates at boot) ==="
rm -rf "$S/mods" "$S/modbridge-cache"
mkdir -p "$S/mods/source-1201"
cp "$MB/out/fixture-1.20.1.jar" "$S/mods/source-1201/"
bash "$T/run-server.sh" start > /tmp/matrix-pos.log 2>&1
grep -q SERVER_UP /tmp/matrix-pos.log; check "server boots with agent-translated mod" $?
grep -q 'translating 1 jar' "$T/console-server-121.log"; check "agent detected and translated the source jar" $?
test -f "$S/mods/fixture-1.20.1.jar"; check "translated jar installed into mods/" $?
grep -q 'constructed via 1.20.1 API' "$S/logs/latest.log"
check "fixture constructor ran (original 1.20.1 bytecode path)" $?
grep -q '/fixtureping registered' "$S/logs/latest.log"
check "command registration event fired" $?
RCON_OUT=$(python3 "$T/rcon.py" 25586 modbridge fixtureping 2>&1)
echo "  rcon> fixtureping"
echo "$RCON_OUT" | sed 's/^/  rcon< /'
echo "$RCON_OUT" | grep -q 'modbridgefixture:magic_item'
check "fixtureping returns registered item id" $?
bash "$T/run-server.sh" stop > /tmp/matrix-stop.log 2>&1
grep -q SERVER_STOPPED /tmp/matrix-stop.log; check "clean server stop before next phase" $?
for i in $(seq 1 15); do lsof -ti :25585 >/dev/null 2>&1 || break; sleep 2; done

echo "=== phase 4: warm reboot (cache must make re-translation free) ==="
bash "$T/run-server.sh" start > /tmp/matrix-warm.log 2>&1
grep -q SERVER_UP /tmp/matrix-warm.log; check "server boots again with warm cache" $?
grep -q 'unchanged (sha' "$T/console-server-121.log"; check "agent reused the hash cache (no re-translate)" $?
grep -q 'constructed via 1.20.1 API' "$S/logs/latest.log"
check "mod initialized on reboot" $?
bash "$T/run-server.sh" stop > /tmp/matrix-stop.log 2>&1
grep -q SERVER_STOPPED /tmp/matrix-stop.log; check "clean final stop" $?

echo "=== matrix: $PASS passed, $FAIL failed ==="
exit $FAIL
