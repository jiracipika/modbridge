#!/usr/bin/env bash
# Clones the NeoForge 21.1.250 server install (libraries + launcher) from Mayview
# into an isolated testbed, then writes a minimal headless config with RCON.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
SRC=/Volumes/ADATA/mayview-121/server
TGT="$MB/testenv/server-121"
if [ -d "$TGT/libraries" ]; then echo "testbed server already installed"; exit 0; fi
mkdir -p "$TGT"
cp -pR "$SRC/libraries" "$TGT/libraries"
cp -p "$SRC/run.sh" "$TGT/run.sh"
cat > "$TGT/user_jvm_args.txt" <<JVM
-Xms512M
-Xmx2048M
-Dlog4j2.formatMsgNoLookups=true
-Dterminal.ansi=false
JVM
echo "eula=true" > "$TGT/eula.txt"
cat > "$TGT/server.properties" <<PROPS
server-port=25585
enable-rcon=true
rcon-port=25586
rcon-password=modbridge
broadcast-rcon-to-ops=false
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
level-name=world
motd=modbridge testbed
view-distance=3
simulation-distance=3
spawn-protection=0
sync-chunk-writes=false
max-tick-time=180000
PROPS
echo "testbed server installed at $TGT"
