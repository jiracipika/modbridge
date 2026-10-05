package com.modbridge.pairer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans jar files (real mods) for every bytecode-level reference to an SRG-named member
 * (method calls, field accesses, lambda method handles) and reports which of those
 * references hit the UNRESOLVED set — i.e. the ambiguities that actually matter for the
 * real mods. Read-only: jars are opened for reading only.
 *
 * usage: SrgUsageScanner <unresolved.txt> <report.txt> <jar> [jar...]
 *   unresolved.txt  rows "srgId|owner|static|desc|reason" (from SrgExactJoinResolver)
 *   report.txt      per-jar + global usage report
 */
public class SrgUsageScanner {

    static final Pattern SRG = Pattern.compile("^([mf])_(\\d+)_+$");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SrgUsageScanner <unresolved.txt> <report.txt> <jar> [jar...]");
            System.exit(2);
        }
        Map<String, String[]> unresolved = readUnresolved(Path.of(args[0]));
        System.out.printf("unresolved set: %d ids%n", unresolved.size());
        PrintStream report = new PrintStream(Files.newOutputStream(Path.of(args[1])), true, "UTF-8");
        // srgId -> (mod -> count)
        Map<String, Map<String, Integer>> used = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
        for (int i = 2; i < args.length; i++) {
            Path jar = Path.of(args[i]);
            Map<String, Integer> jarRefs = scanJar(jar, unresolved);
            String mod = jar.getFileName().toString();
            report.println("== " + mod + ": " + jarRefs.size() + " distinct unresolved srg ids referenced, "
                    + jarRefs.values().stream().mapToInt(Integer::intValue).sum() + " ref sites");
            jarRefs.forEach((id, n) -> report.printf("   %s (%d)%n", id, n));
            jarRefs.forEach((id, n) -> used.computeIfAbsent(id, k -> new HashMap<>()).put(mod, n));
            System.out.printf("%-45s %d unresolved ids (%d sites)%n", mod, jarRefs.size(),
                    jarRefs.values().stream().mapToInt(Integer::intValue).sum());
        }
        report.println();
        report.println("== global: " + used.size() + " distinct unresolved ids used by scanned mods");
        used.forEach((id, mods) -> {
            StringBuilder sb = new StringBuilder("   " + id);
            String[] meta = unresolved.get(id);
            if (meta != null) sb.append("  [").append(meta[0]).append(" ").append(meta[2]).append("]");
            mods.forEach((mod, n) -> sb.append("  ").append(mod).append(":").append(n));
            report.println(sb);
        });
        report.flush();
        report.close();
        System.out.println("global unresolved-used distinct ids: " + used.size());
    }

    /** All unresolved-id reference sites in one jar: id -> site count. */
    public static Map<String, Integer> scanJar(Path jarPath, Map<String, String[]> unresolved) throws IOException {
        Map<String, Integer> hits = new TreeMap<>((a, b) -> Long.compare(numId(a), numId(b)));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                try (InputStream is = jar.getInputStream(je)) {
                    new ClassReader(is).accept(cn, 0);
                }
                for (MethodNode mn : cn.methods) {
                    for (AbstractInsnNode in : mn.instructions) {
                        if (in instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) in;
                            note(hits, unresolved, mi.name);
                        } else if (in instanceof FieldInsnNode) {
                            FieldInsnNode fi = (FieldInsnNode) in;
                            note(hits, unresolved, fi.name);
                        } else if (in instanceof InvokeDynamicInsnNode) {
                            InvokeDynamicInsnNode id = (InvokeDynamicInsnNode) in;
                            for (Object bsmArg : id.bsmArgs) {
                                if (bsmArg instanceof Handle) {
                                    note(hits, unresolved, ((Handle) bsmArg).getName());
                                }
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    static void note(Map<String, Integer> hits, Map<String, String[]> unresolved, String name) {
        if (name == null) return;
        if (unresolved.containsKey(name)) hits.merge(name, 1, Integer::sum);
    }

    static Map<String, String[]> readUnresolved(Path p) throws IOException {
        Map<String, String[]> out = new HashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 5) out.put(parts[0], new String[]{parts[1], parts[2], parts[3], parts[4]});
        }
        return out;
    }

    static long numId(String srg) {
        Matcher m = SRG.matcher(srg);
        return m.matches() ? Long.parseLong(m.group(2)) : -1;
    }
}
