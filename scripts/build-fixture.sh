#!/usr/bin/env bash
# Compiles the 1.20.1 API stubs + fixture mod (bytecode targets Java 17, like real 1.20.1 mods).
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
cd "$MB"
rm -rf build/stubs build/fixture build/pkg
mkdir -p build/stubs build/fixture build/pkg out
"$JDK/bin/javac" --release 17 -cp "tools/lib/*" -d build/stubs $(find stubs/src -name '*.java')
"$JDK/bin/javac" --release 17 -cp "build/stubs:tools/lib/*" -d build/fixture $(find fixture/src -name '*.java')
cp -R build/fixture/* build/pkg/
cp -R fixture/resources/* build/pkg/
(cd build/pkg && "$JDK/bin/jar" cf "$MB/out/fixture-1.20.1.jar" .)
echo "built out/fixture-1.20.1.jar"
