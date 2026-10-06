#!/usr/bin/env bash
# Regenerates rules/interfaces-1211.txt: internal names of every ACC_INTERFACE class
# on the 1.21.1 platform (ALL shipped libraries — minecraft server, neoforge, fml,
# bus, mojang libs incl. datafixerupper 8, night-config, netty/guava/gson/fastutil,
# every jar under the sandbox libraries tree). Consulted by the rewriter's
# interface call-site repair: 1.20.1 bytecode that called these as CLASSES carries
# Methodref constants (e.g. DFU 6 DataResult.success — a class static on 1.20.1,
# an interface static on 21.1) which the JVM rejects with IncompatibleClassChangeError.
# Regenerate after a NeoForge/DFU upgrade. Presence here only vouches for the class
# being an interface on 21.1, nothing else.
set -euo pipefail
MB="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/Volumes/HARD/dev-cache/jdks/jdk-21.0.12+8/Contents/Home
LIB="$MB/testenv/server-121/libraries"
OUT="$MB/rules/interfaces-1211.txt"
WORK="$MB/build/gen-interfaces"

mkdir -p "$WORK"
cat > "$WORK/ScanInterfaces.java" <<'JAVA_EOF'
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Lists the internal names of every ACC_INTERFACE class inside a jar.
 *  Parses the class-file header: magic/minor/major, walks the constant pool
 *  (tag-aware, long/double take two slots), reads access_flags, masks ACC_INTERFACE
 *  (0x0200) — which also covers annotations (ACC_ANNOTATION = 0x2000 superset bit). */
public class ScanInterfaces {
    public static void main(String[] args) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Paths.get(args[0])))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                byte[] b = out.toByteArray();
                if (b.length < 10 || readU4(b, 0) != 0xCAFEBABEL) continue;
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
                in.readInt();          // magic
                in.readUnsignedShort(); // minor
                in.readUnsignedShort(); // major
                int cpCount = in.readUnsignedShort();
                for (int i = 1; i < cpCount; i++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1: in.skipBytes(in.readUnsignedShort()); break; // Utf8
                        case 3: case 4: in.skipBytes(4); break;              // Integer, Float
                        case 5: case 6: in.skipBytes(8); i++; break;         // Long, Double
                        case 7: case 8: case 16: case 19: case 20:
                            in.skipBytes(2); break;                          // Class, String, MethodType, Module, Package
                        case 9: case 10: case 11: case 12: case 17: case 18:
                            in.skipBytes(4); break;                          // refs, NameAndType, Dynamic, InvokeDynamic
                        case 15: in.skipBytes(3); break;                     // MethodHandle
                        default: throw new IOException("unknown cp tag " + tag);
                    }
                }
                int access = in.readUnsignedShort();
                if ((access & 0x0200) != 0) System.out.println(e.getName().substring(0, e.getName().length() - 6));
            }
        }
    }
    static long readU4(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }
}
JAVA_EOF

BODY=$(find "$LIB" -name '*.jar' | sort | while read -r j; do
  "$JDK/bin/java" "$WORK/ScanInterfaces.java" "$j"
done | LC_ALL=C sort -u)
{
  echo "# 1.21.1 platform interface universe (internal names), generated from the sandbox"
  echo "# server-121 libraries (minecraft srg jar + neoforge server/universal + fml + bus"
  echo "# + ALL shipped libraries: mojang datafixerupper/brigadier/authlib/logging,"
  echo "# night-config, netty, guava, gson, fastutil, ...)."
  echo "# Consulted by the rewriter's interface call-site repair: 1.20.1 bytecode that"
  echo "# called these as CLASSES carries Methodref constants which the JVM rejects with"
  echo "# IncompatibleClassChangeError once the type is an interface on 21.1 (measured"
  echo "# wall: DFU 6 DataResult was a class, DFU 8 DataResult is an interface)."
  echo "# Regenerate with scripts/gen-interfaces-1211.sh. Never guess: the rewriter"
  echo "# repairs a call site only when its owner is listed here."
  printf '%s\n' "$BODY"
} > "$OUT"
rm -rf "$WORK"
echo "wrote $OUT ($(grep -vc '^#' "$OUT") interfaces)"
