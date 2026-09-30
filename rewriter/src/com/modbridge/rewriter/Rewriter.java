package com.modbridge.rewriter;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * ModBridge bytecode rewriter: translates a Forge 1.20.1 mod jar to run on NeoForge 1.21.1.
 *
 * Pipeline per class:
 *   1. surgery on raw (pre-remap) bytecode:
 *        - @Mod class: rewrite the no-arg constructor to take the mod event bus
 *          (IEventBus) and splice out the FMLJavaModLoadingContext lookup chain.
 *        - new ResourceLocation(a, b) -> ResourceLocation.fromNamespaceAndPath(a, b).
 *   2. member renames (old-space owner|desc|name -> new name), then
 *   3. type renames everywhere: exact type table, then package-prefix table.
 *
 * Resources: META-INF/mods.toml is dropped and a fresh META-INF/neoforge.mods.toml is
 * generated from the parsed fields; pack.mcmeta is bumped to the target pack format.
 * Stack frames are kept (not recomputed) — every rewrite preserves the operand stack
 * shape, and frame type names are remapped like everything else.
 */
public class Rewriter {

    private static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String FML_CONTEXT = "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";

    private static final String TARGET_NEOFORGE_RANGE = "[21.1.0,)";
    private static final String TARGET_MINECRAFT = "[1.21.1]";
    private static final int TARGET_PACK_FORMAT = 48;

    /** matches an L-reference inside descriptors and signature strings; group 1 = internal name */
    private static final Pattern L_REF = Pattern.compile("L([A-Za-z0-9_/$]+)(;|<)");

    final Map<String, String> typeRenames = new HashMap<>();
    final List<String[]> packageRenames = new ArrayList<>();
    final Map<String, String> memberRenames = new HashMap<>();
    final Map<String, String> memberDescRenames = new HashMap<>();

    int fmlChainsSpliced;
    int resourceLocationCtors;
    int memberRenamesApplied;
    int classesTransformed;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: Rewriter <in.jar> <out.jar> <rulesDir>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Path rulesDir = Paths.get(args[2]);

        Rewriter r = new Rewriter();
        r.loadRules(rulesDir);
        r.run(in, out);
    }

    void loadRules(Path rulesDir) throws IOException {
        addPackageRules(readLines(rulesDir.resolve("packagerenames.txt")));
        addTypeRules(readLines(rulesDir.resolve("typerenames.txt")));
        addMemberRules(readLines(rulesDir.resolve("memberrenames.txt")));
        addDescRules(readLines(rulesDir.resolve("memberdescrenames.txt")));
        printRuleStats();
    }

    /** rules bundled inside the agent jar (Connector-style drop-in operation) */
    void loadRulesFromClasspath() throws IOException {
        addPackageRules(resourceLines("/rules/packagerenames.txt"));
        addTypeRules(resourceLines("/rules/typerenames.txt"));
        addMemberRules(resourceLines("/rules/memberrenames.txt"));
        addDescRules(resourceLines("/rules/memberdescrenames.txt"));
        printRuleStats();
    }

    public static void translate(Path in, Path out) throws Exception {
        Rewriter r = new Rewriter();
        r.loadRulesFromClasspath();
        r.run(in, out);
    }

    private void printRuleStats() {
        System.out.printf("rules: %d package, %d type, %d member, %d desc%n",
                packageRenames.size(), typeRenames.size(), memberRenames.size(), memberDescRenames.size());
    }

    private void addPackageRules(List<String> lines) throws IOException {
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IOException("bad packagerename line: " + line);
            packageRenames.add(new String[]{line.substring(0, eq), line.substring(eq + 1)});
        }
    }

    private void addTypeRules(List<String> lines) throws IOException {
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length != 2) throw new IOException("bad typerename line: " + line);
            typeRenames.put(parts[0].trim(), parts[1].trim());
        }
    }

    private void addMemberRules(List<String> lines) throws IOException {
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IOException("bad memberrename line: " + line);
            String[] parts = line.substring(0, eq).split("\\|");
            if (parts.length != 3) throw new IOException("bad memberrename line: " + line);
            memberRenames.put(parts[0] + "|" + parts[1] + "|" + parts[2], line.substring(eq + 1).trim());
        }
    }

    private void addDescRules(List<String> lines) throws IOException {
        for (String line : lines) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) throw new IOException("bad memberdescrename line: " + line);
            String[] key = parts[0].split("\\|");
            if (key.length != 3) throw new IOException("bad memberdescrename line: " + line);
            memberDescRenames.put(key[0] + "|" + key[1] + "|" + key[2], parts[1].trim());
        }
    }

    private static List<String> resourceLines(String path) throws IOException {
        try (InputStream is = Rewriter.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("missing rules resource " + path);
            return new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }
    }

    private static List<String> readLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    void run(Path in, Path out) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String modsToml = null;
        String packMeta = null;
        try (JarFile jar = new JarFile(in.toFile())) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (je.isDirectory()) continue;
                byte[] bytes = readAll(jar.getInputStream(je));
                switch (je.getName()) {
                    case "META-INF/mods.toml" -> modsToml = new String(bytes, StandardCharsets.UTF_8);
                    case "pack.mcmeta" -> packMeta = new String(bytes, StandardCharsets.UTF_8);
                    default -> entries.put(je.getName(), bytes);
                }
            }
        }

        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            if (!name.endsWith(".class")) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(entries.get(name)).accept(cn, 0);
            transform(cn);
            entries.remove(name);
            entries.put(cn.name + ".class", toBytes(cn));
            classesTransformed++;
        }

        entries.put("META-INF/neoforge.mods.toml",
                generateModsToml(modsToml).getBytes(StandardCharsets.UTF_8));
        entries.put("pack.mcmeta", generatePackMeta(packMeta).getBytes(StandardCharsets.UTF_8));

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            List<String> sorted = new ArrayList<>(entries.keySet());
            Collections.sort(sorted);
            for (String name : sorted) {
                jos.putNextEntry(new ZipEntry(name));
                jos.write(entries.get(name));
                jos.closeEntry();
            }
        }

        verifyNoForgeRefs(out);
        System.out.printf("translated %d classes; fml chains spliced=%d, ResourceLocation ctors rewritten=%d, member renames=%d%n",
                classesTransformed, fmlChainsSpliced, resourceLocationCtors, memberRenamesApplied);
        System.out.println("wrote " + out);
    }

    void transform(ClassNode cn) {
        if (isModClass(cn)) {
            rewriteModConstructor(cn);
        }
        rewriteResourceLocationCtors(cn);
        applyMemberRenames(cn);
        remapClass(cn);
    }

    private static boolean isModClass(ClassNode cn) {
        if (cn.visibleAnnotations == null) return false;
        for (AnnotationNode an : cn.visibleAnnotations) {
            if (MOD_ANNOTATION.equals(an.desc)) return true;
        }
        return false;
    }

    /**
     * 1.20.1 mods grab the mod event bus via FMLJavaModLoadingContext inside a no-arg
     * constructor; NeoForge 1.21 injects the bus as a constructor parameter instead.
     * Rewrites {@code <init>()V} to {@code <init>(IEventBus)V} and splices the lookup
     * chain into {@code ALOAD 1}.
     */
    void rewriteModConstructor(ClassNode cn) {
        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || !"()V".equals(mn.desc)) continue;
            boolean found = false;
            AbstractInsnNode n = mn.instructions.getFirst();
            while (n != null) {
                AbstractInsnNode next = n.getNext();
                if (n instanceof MethodInsnNode m1
                        && m1.getOpcode() == Opcodes.INVOKESTATIC
                        && "get".equals(m1.name)
                        && FML_CONTEXT.equals(m1.owner)
                        && next instanceof MethodInsnNode m2
                        && m2.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "getModEventBus".equals(m2.name)
                        && FML_CONTEXT.equals(m2.owner)) {
                    VarInsnNode aload = new VarInsnNode(Opcodes.ALOAD, 1);
                    mn.instructions.insert(next, aload);
                    mn.instructions.remove(n);
                    mn.instructions.remove(next);
                    n = aload.getNext();
                    found = true;
                    fmlChainsSpliced++;
                } else {
                    n = next;
                }
            }
            if (found) {
                mn.desc = "(L" + EVENT_BUS + ";)V";
                changed = true;
            }
        }
        if (!changed) {
            throw new IllegalStateException(
                    "mod class " + cn.name + " has no ()V constructor with an FMLJavaModLoadingContext chain");
        }
    }

    /**
     * new ResourceLocation(ns, path) -> ResourceLocation.fromNamespaceAndPath(ns, path).
     * In bytecode the ctor arguments sit between DUP and INVOKESPECIAL, so after the
     * NEW+DUP pair we scan forward to the matching INVOKESPECIAL and rewrite both ends.
     * Dropping NEW+DUP and swapping the ctor call for a static factory keeps the
     * operand stack shape identical, so existing stack frames stay valid.
     */
    void rewriteResourceLocationCtors(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            AbstractInsnNode n = mn.instructions.getFirst();
            while (n != null) {
                AbstractInsnNode dup = n.getNext();
                if (!(n instanceof TypeInsnNode t)
                        || t.getOpcode() != Opcodes.NEW
                        || !RESOURCE_LOCATION.equals(t.desc)
                        || dup == null || dup.getOpcode() != Opcodes.DUP) {
                    n = n.getNext();
                    continue;
                }
                // forward-scan for the INVOKESPECIAL that consumes this NEW+DUP pair
                AbstractInsnNode c = dup.getNext();
                boolean matched = false;
                AbstractInsnNode after = null;
                while (c != null && !(c instanceof TypeInsnNode)) {
                    if (c instanceof MethodInsnNode ctor
                            && ctor.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(ctor.name)
                            && RESOURCE_LOCATION.equals(ctor.owner)) {
                        if (!"(Ljava/lang/String;Ljava/lang/String;)V".equals(ctor.desc)) {
                            throw new IllegalStateException(
                                    "unsupported ResourceLocation ctor descriptor " + ctor.desc + " in " + cn.name);
                        }
                        MethodInsnNode factory = new MethodInsnNode(
                                Opcodes.INVOKESTATIC, RESOURCE_LOCATION, "fromNamespaceAndPath",
                                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
                                false);
                        after = ctor.getNext();
                        mn.instructions.insert(ctor, factory);
                        mn.instructions.remove(n);
                        mn.instructions.remove(dup);
                        mn.instructions.remove(ctor);
                        resourceLocationCtors++;
                        matched = true;
                        break;
                    }
                    c = c.getNext();
                }
                n = matched ? after : n.getNext();
            }
        }
    }

    void applyMemberRenames(ClassNode cn) {
        if (memberRenames.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            String renamed = memberRenames.get(cn.name + "|" + mn.desc + "|" + mn.name);
            if (renamed != null) mn.name = renamed;
        }
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode n : mn.instructions) {
                if (n instanceof MethodInsnNode m) {
                    String renamed = memberRenames.get(m.owner + "|" + m.desc + "|" + m.name);
                    if (renamed != null) { m.name = renamed; memberRenamesApplied++; }
                    applyDescRewrite(cn, mn, m);
                } else if (n instanceof FieldInsnNode f) {
                    String renamed = memberRenames.get(f.owner + "|" + f.desc + "|" + f.name);
                    if (renamed != null) { f.name = renamed; memberRenamesApplied++; }
                }
            }
        }
    }

    /**
     * Rewrites a call-site descriptor (e.g. a return type that changed across versions).
     * javac discards an unused boolean/int return with POP (long/double: POP2); when the
     * return type shrinks to void that discard instruction must go with it, and when a
     * void call starts returning a value a discard must be added — both keep the operand
     * stack depth identical at every frame, so existing stack maps stay valid.
     */
    void applyDescRewrite(ClassNode cn, MethodNode mn, MethodInsnNode m) {
        String newDesc = memberDescRenames.get(m.owner + "|" + m.name + "|" + m.desc);
        if (newDesc == null) return;
        org.objectweb.asm.Type oldRet = org.objectweb.asm.Type.getReturnType(m.desc);
        org.objectweb.asm.Type newRet = org.objectweb.asm.Type.getReturnType(newDesc);
        m.desc = newDesc;
        if (oldRet.equals(newRet)) return;
        int oldSize = oldRet.getSize();
        int newSize = newRet.getSize();
        if (oldSize > newSize) {
            AbstractInsnNode next = m.getNext();
            int discard = oldSize == 2 ? Opcodes.POP2 : Opcodes.POP;
            if (next == null || next.getOpcode() != discard) {
                throw new IllegalStateException("unsupported descriptor rewrite for " + m.owner
                        + "#" + m.name + " in " + cn.name + ": return value is consumed by the caller");
            }
            mn.instructions.remove(next);
        } else if (newSize > oldSize) {
            mn.instructions.insert(m, new InsnNode(newSize == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
    }

    void remapClass(ClassNode cn) {
        cn.name = remapType(cn.name);
        cn.superName = remapType(cn.superName);
        replaceAll(cn.interfaces, this::remapType);
        if (cn.nestHostClass != null) cn.nestHostClass = remapType(cn.nestHostClass);
        replaceAll(cn.nestMembers, this::remapType);
        replaceAll(cn.permittedSubclasses, this::remapType);
        if (cn.outerClass != null) cn.outerClass = remapType(cn.outerClass);
        if (cn.signature != null) cn.signature = remapDesc(cn.signature);
        if (cn.innerClasses != null) {
            for (InnerClassNode ic : cn.innerClasses) {
                if (ic.name != null) ic.name = remapType(ic.name);
                if (ic.outerName != null) ic.outerName = remapType(ic.outerName);
            }
        }
        remapAnnotations(cn.visibleAnnotations);
        remapAnnotations(cn.invisibleAnnotations);

        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                f.desc = remapDesc(f.desc);
                if (f.signature != null) f.signature = remapDesc(f.signature);
                remapAnnotations(f.visibleAnnotations);
                remapAnnotations(f.invisibleAnnotations);
            }
        }
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                m.desc = remapDesc(m.desc);
                if (m.signature != null) m.signature = remapDesc(m.signature);
                if (m.exceptions != null) m.exceptions.replaceAll(this::remapType);
                remapAnnotations(m.visibleAnnotations);
                remapAnnotations(m.invisibleAnnotations);
                remapParameterAnnotations(m.visibleParameterAnnotations);
                remapParameterAnnotations(m.invisibleParameterAnnotations);
                if (m.localVariables != null) {
                    for (LocalVariableNode lv : m.localVariables) {
                        lv.desc = remapDesc(lv.desc);
                        if (lv.signature != null) lv.signature = remapDesc(lv.signature);
                    }
                }
                if (m.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                        if (tcb.type != null) tcb.type = remapType(tcb.type);
                    }
                }
                remapInsns(m);
            }
        }
    }

    private static <T> void replaceAll(List<T> list, java.util.function.UnaryOperator<T> fn) {
        if (list != null) list.replaceAll(fn);
    }

    private void remapInsns(MethodNode m) {
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof TypeInsnNode t) {
                t.desc = remapType(t.desc);
            } else if (n instanceof FieldInsnNode f) {
                f.owner = remapType(f.owner);
                f.desc = remapDesc(f.desc);
            } else if (n instanceof MethodInsnNode c) {
                c.owner = remapType(c.owner);
                c.desc = remapDesc(c.desc);
            } else if (n instanceof MultiANewArrayInsnNode a) {
                a.desc = remapDesc(a.desc);
            } else if (n instanceof LdcInsnNode l && l.cst instanceof Type ty) {
                l.cst = Type.getType(remapDesc(ty.getDescriptor()));
            } else if (n instanceof InvokeDynamicInsnNode id) {
                id.desc = remapDesc(id.desc);
                if (id.bsm != null) id.bsm = remapHandle(id.bsm);
                if (id.bsmArgs != null) {
                    for (int i = 0; i < id.bsmArgs.length; i++) {
                        if (id.bsmArgs[i] instanceof Type ty) {
                            id.bsmArgs[i] = Type.getType(remapDesc(ty.getDescriptor()));
                        } else if (id.bsmArgs[i] instanceof Handle h) {
                            id.bsmArgs[i] = remapHandle(h);
                        }
                    }
                }
            } else if (n instanceof FrameNode fr) {
                if (fr.local != null) fr.local.replaceAll(o -> o instanceof String s ? remapType(s) : o);
                if (fr.stack != null) fr.stack.replaceAll(o -> o instanceof String s ? remapType(s) : o);
            }
        }
    }

    /** ASM Handle fields are private, so remapping produces a new Handle. */
    private Handle remapHandle(Handle h) {
        return new Handle(h.getTag(), remapType(h.getOwner()), h.getName(), remapDesc(h.getDesc()), h.isInterface());
    }

    private void remapParameterAnnotations(List<AnnotationNode>[] annotations) {
        if (annotations == null) return;
        for (List<AnnotationNode> anns : annotations) {
            remapAnnotations(anns);
        }
    }

    private void remapAnnotations(List<AnnotationNode> annotations) {
        if (annotations == null) return;
        for (AnnotationNode an : annotations) {
            remapAnnotation(an);
        }
    }

    private void remapAnnotation(AnnotationNode an) {
        an.desc = remapDesc(an.desc);
        if (an.values == null) return;
        for (int i = 1; i < an.values.size(); i += 2) {
            an.values.set(i, remapAnnotationValue(an.values.get(i)));
        }
    }

    private Object remapAnnotationValue(Object v) {
        if (v instanceof Type ty) {
            return Type.getType(remapDesc(ty.getDescriptor()));
        }
        if (v instanceof AnnotationNode nested) {
            remapAnnotation(nested);
        } else if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.replaceAll(this::remapAnnotationValue);
            return copy;
        }
        return v;
    }

    String remapType(String internal) {
        if (internal == null) return null;
        String mapped = typeRenames.get(internal);
        if (mapped != null) return mapped;
        for (String[] pr : packageRenames) {
            if (internal.startsWith(pr[0])) {
                return pr[1] + internal.substring(pr[0].length());
            }
        }
        return internal;
    }

    String remapDesc(String d) {
        if (d == null || !d.contains("L")) return d;
        Matcher m = L_REF.matcher(d);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "L" + Matcher.quoteReplacement(remapType(m.group(1)))
                    + Matcher.quoteReplacement(m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] toBytes(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    String generateModsToml(String oldToml) {
        String modId = extract(oldToml, "modId");
        String version = extract(oldToml, "version");
        String displayName = extract(oldToml, "displayName");
        String license = extract(oldToml, "license");
        String description = extractMultiline(oldToml, "description");
        if (modId == null) throw new IllegalStateException("mods.toml has no modId");
        if (version == null) version = "1.0.0";
        if (license == null) license = "All Rights Reserved";
        if (displayName == null) displayName = modId;
        if (description == null) description = "Translated to NeoForge 1.21.1 by ModBridge.";
        return """
                modLoader="javafml"
                loaderVersion="[4,)"
                license="%s"

                [[mods]]
                modId="%s"
                version="%s"
                displayName="%s"
                description='''%s'''

                [[dependencies.%s]]
                modId="neoforge"
                type="required"
                versionRange="%s"
                ordering="NONE"
                side="BOTH"

                [[dependencies.%s]]
                modId="minecraft"
                type="required"
                versionRange="%s"
                ordering="NONE"
                side="BOTH"
                """.formatted(license, modId, version, displayName, description,
                modId, TARGET_NEOFORGE_RANGE, modId, TARGET_MINECRAFT);
    }

    private static String extract(String toml, String key) {
        if (toml == null) return null;
        Matcher m = Pattern.compile("^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"", Pattern.MULTILINE).matcher(toml);
        return m.find() ? m.group(1) : null;
    }

    private static String extractMultiline(String toml, String key) {
        if (toml == null) return null;
        Matcher m = Pattern.compile(key + "\\s*=\\s*'''([\\s\\S]*?)'''").matcher(toml);
        if (m.find()) return m.group(1).trim();
        return extract(toml, key);
    }

    String generatePackMeta(String oldMeta) {
        if (oldMeta == null) {
            oldMeta = "{\"pack\":{\"description\":\"ModBridge fixture\",\"pack_format\":15}}";
        }
        return oldMeta.replaceAll("\"pack_format\"\\s*:\\s*\\d+", "\"pack_format\": " + TARGET_PACK_FORMAT);
    }

    private static void verifyNoForgeRefs(Path jar) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                String s = new String(readAll(jf.getInputStream(je)), StandardCharsets.ISO_8859_1);
                if (s.contains("net/minecraftforge")) offenders.add(je.getName());
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("unmapped net/minecraftforge references in: " + offenders);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            return in.readAllBytes();
        }
    }
}
