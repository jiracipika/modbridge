#!/usr/bin/env bash
# Boots/stops a testbed server. usage: run-server.sh [start|stop] [instance]
#   instance: server-121 (default) | server-1201
# Prints SERVER_UP / SERVER_FAILED / TIMEOUT_NO_DONE / SERVER_STOPPED.
set -uo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
INST="${2:-server-121}"
case "$INST" in
  server-121)  S="$MB/testenv/server-121";  GAME=25585; RCONP=25586 ;;
  server-1201) S="$MB/testenv/server-1201"; GAME=25587; RCONP=25588 ;;
  *) echo "unknown instance $INST"; exit 2 ;;
esac
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
export JAVA_HOME=$JDK
export PATH="$JDK/bin:$PATH"
LOG="$S/logs/latest.log"
PIDFILE="$S/server.pid"

case "${1:-start}" in
  stop)
    python3 "$MB/testenv/rcon.py" "$RCONP" modbridge stop >/dev/null 2>&1
    for i in $(seq 1 30); do
      PIDS="$(lsof -ti :$GAME 2>/dev/null | tr '\n' ' ')"
      [ -z "$PIDS" ] && { rm -f "$PIDFILE"; echo "SERVER_STOPPED"; exit 0; }
      sleep 2
    done
    PIDS="$(lsof -ti :$GAME 2>/dev/null)"
    [ -n "$PIDS" ] && kill -TERM $PIDS 2>/dev/null
    for i in $(seq 1 15); do
      PIDS="$(lsof -ti :$GAME 2>/dev/null | tr '\n' ' ')"
      [ -z "$PIDS" ] && { rm -f "$PIDFILE"; echo "SERVER_STOPPED"; exit 0; }
      sleep 2
    done
    echo "SERVER_STOP_TIMEOUT"; exit 1
    ;;
  start)
    PIDS="$(lsof -ti :$GAME 2>/dev/null | tr '\n' ' ')"
    if [ -n "$PIDS" ]; then echo "SERVER_ALREADY_RUNNING($PIDS)"; exit 4; fi
    cd "$S"
    rm -f "$LOG"
    nohup ./run.sh nogui > "$MB/testenv/console-$INST.log" 2>&1 &
    echo $! > "$PIDFILE"
    echo "server pid $!"
    for i in $(seq 1 100); do
      sleep 3
      if grep -q 'Done (' "$LOG" 2>/dev/null; then
        grep 'Done (' "$LOG" | tail -1
        echo "SERVER_UP"
        exit 0
      fi
      if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
        echo "SERVER_FAILED"
        grep -m5 -E 'Caused by:|ERROR|FATAL' "$MB/testenv/console-$INST.log" "$LOG" 2>/dev/null | head -20
        exit 2
      fi
      if grep -qE 'Failed to start the minecraft server' "$LOG" "$MB/testenv/console-$INST.log" 2>/dev/null; then
        echo "SERVER_FAILED"
        grep -m5 -E 'Caused by:|ERROR|FATAL' "$MB/testenv/console-$INST.log" "$LOG" 2>/dev/null | head -20
        exit 2
      fi
    done
    echo "TIMEOUT_NO_DONE"
    exit 3
    ;;
esac
