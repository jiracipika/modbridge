#!/usr/bin/env bash
# Builds out/modbridge-agent.jar: rewriter + agent + ASM + bundled rules,
# usable standalone (-javaagent) or as a CLI translator.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
cd "$MB"
rm -rf build/agent
mkdir -p build/agent
"$JDK/bin/javac" --release 17 -cp "tools/lib/*" -d build/agent $(find rewriter/src agent/src -name '*.java')
# embed ASM (baseline classes only; ASM 9.7 runs fine on Java 17+ without its MR-jar sidecars)
unzip -q -o tools/lib/asm-9.7.jar -x 'module-info.class' 'META-INF/versions/*' -d build/agent
unzip -q -o tools/lib/asm-tree-9.7.jar -x 'module-info.class' 'META-INF/versions/*' -d build/agent
mkdir -p build/agent/rules
cp rules/*.txt build/agent/rules/
# phase-5 shim carrier classes + manifest (built by scripts/build-shims.sh)
if [ -d rules/shims ]; then
  cp -r rules/shims build/agent/rules/shims
fi
cat > build/agent/MANIFEST.MF <<MF
Premain-Class: com.modbridge.agent.ModBridgeAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
MF
(cd build/agent && "$JDK/bin/jar" cfm "$MB/out/modbridge-agent.jar" MANIFEST.MF com org rules)
ls -la "$MB/out/modbridge-agent.jar"
echo "built out/modbridge-agent.jar"
