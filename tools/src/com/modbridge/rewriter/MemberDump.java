package com.modbridge.rewriter;

import org.objectweb.asm.ClassReader;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Dump tool: emits "owner name desc" lines for every field and method of every class
 * in a jar. Used to generate rules/membertargets-1211.txt — the 1.21.1 member inventory
 * consulted by the phase-4 accessor/invoker drift checks (bare names) and the @Shadow
 * drift check (name + descriptor). CLI: MemberDump <platform.jar> [more.jar...] > out.txt
 */
public final class MemberDump {

    public static void main(String[] args) throws Exception {
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        for (String arg : args) {
            try (JarFile jar = new JarFile(arg)) {
                for (java.util.Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    JarEntry je = e.nextElement();
                    if (je.isDirectory() || !je.getName().endsWith(".class")) continue;
                    ClassReader cr = new ClassReader(jar.getInputStream(je));
                    dump(out, cr, cr.getClassName());
                }
            }
        }
        out.flush();
    }

    private static void dump(BufferedWriter out, ClassReader cr, String owner) throws Exception {
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (cn.fields != null) {
            for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
                out.write(owner + " " + f.name + " " + f.desc + "\n");
            }
        }
        if (cn.methods != null) {
            for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
                out.write(owner + " " + m.name + " " + m.desc + "\n");
            }
        }
    }
}
