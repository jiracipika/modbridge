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
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *        - ctor-call retargets (rules/ctorretargets.txt): constructors whose semantics
 *          survive behind a public factory call (ModConfig -> ConfigTracker.registerConfig).
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

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR_ANNOTATION = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_ANNOTATION = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String SHADOW_ANNOTATION = "Lorg/spongepowered/asm/mixin/Shadow;";
    /** accessor/invoker idiom: Mixin derives the target member by stripping this prefix */
    private static final Pattern ACCESSOR_METHOD_NAME = Pattern.compile("(get|set|is|invoke|call)([A-Z].*)");
    /** MixinExtras injector annotations carry their own require/expect strictness and do
     * NOT inherit the config's defaultRequire — drifted call sites must fail soft. */
    private static final String MIXINEXTRAS_INJECTOR_PREFIX = "Lcom/llamalad7/mixinextras/injector/";
    /** synthetic mod-class members backing the bus-parameter constructor rewrite */
    private static final String MOD_BUS_FIELD = "mb$bus";
    private static final String MOD_INIT_METHOD = "mb$init";
    /** platform-owned package spaces whose classes the classlist can vouch for */
    private static final String[] PLATFORM_PACKAGES = {"net/minecraft/", "net/neoforged/", "net/minecraftforge/"};
    private static final String ACCESS_TRANSFORMER = "META-INF/accesstransformer.cfg";

    final Map<String, String> typeRenames = new HashMap<>();
    final List<String[]> packageRenames = new ArrayList<>();
    final Map<String, String> memberRenames = new HashMap<>();
    final Map<String, String> memberDescRenames = new HashMap<>();
    /** typerename keys ending in '$': match the type and all of its inner classes */
    final Map<String, String> prefixTypeRenames = new LinkedHashMap<>();
    /** SRG member id (m_123_/f_123_) -> mojmap name; 1.20.1 production mods reference
     *  vanilla members by SRG name while NeoForge 21.1 runs mojmap. */
    final Map<String, String> srgMemberRenames = new HashMap<>();
    /** 1.20.1 mojmap member name -> 1.21.1 mojmap name (renames only; bare names).
     *  Covers the mojmap->mojmap drift SRG ids already absorb for call sites but that
     *  leaks through dev-space names: @Accessor/@Invoker method-name derivation and
     *  dev-name strings in mixin annotations/refmaps (e.g. tryCatchFire -> checkBurnOut). */
    final Map<String, String> mojMemberRenames = new HashMap<>();
    /** 1.21.1 platform member inventory (owner -> name -> descriptors) backing the
     *  accessor/invoker and @Shadow drift checks. Optional — empty disables them. */
    final Map<String, Map<String, Set<String>>> memberTargets = new HashMap<>();
    /** call-site retargets for members that became interface-default methods:
     *  owner|desc|name -> newOwner|newDesc|interface-or-virtual */
    final Map<String, String[]> memberRetargets = new HashMap<>();
    /** ctor-call retargets for constructors replaced by a public factory-style call:
     *  owner|ctorDesc -> newOwner|receiverField|newMethod|newMethodDesc */
    final Map<String, String[]> ctorRetargets = new HashMap<>();
    /** owners named by ctorRetargets (fast pre-scan before the full-key lookup) */
    final Set<String> ctorRetargetOwners = new HashSet<>();
    /** method-body drops for whole methods whose 1.20.1 semantics are already covered
     *  elsewhere on 21.1 (or are documented no-ops): owner|desc|name -> typed return */
    final Set<String> methodDrops = new HashSet<>();
    int methodDropsApplied;
    /** 1.21.1 platform class universe (internal names): minecraft server + neoforge
     *  libraries. Optional — when absent, only leftover forge refs count as drift. */
    final Set<String> classlist = new HashSet<>();
    /** phase-5 shim carrier classes (original internal name -> bytecode): removed-API
     *  classes (LazyOptional, Capability token chain, NonNull* functional types, the
     *  inert AttachCapabilitiesEvent + ConfigFileTypeHandler, the fail-soft
     *  ObfuscationReflectionHelper) injected into every translated jar so the
     *  retargeted references resolve inside the mod's own module. Built by
     *  scripts/build-shims.sh. Rules reference them as "@SHIM@/<SimpleName>". */
    final Map<String, byte[]> shims = new LinkedHashMap<>();
    /** per-jar shim package: com/modbridge/shim/<modid>/ — MUST be unique per module:
     *  FML exports every package of a mod module, and JPMS kills module resolution
     *  when two modules in the layer export the same package (matrix-run005 caught
     *  the first draft parked at platform package names). */
    String shimRoot = null;
    /** shim simple names (last path segment) -> original internal name (unique) */
    final Map<String, String> shimSimpleNames = new HashMap<>();

    // phase-4 accounting (mixin refmap remap + drift neutralization)
    int mixinClassesSeen;
    int refmapsRemapped;
    int nestedJarsDropped;
    // phase-5 accounting (shim carrier injection)
    int shimsInjected;
    int atLinesRemapped;
    int atLinesUnmapped;
    /** slash-internal names of mixin classes removed from the translation */
    final Set<String> neutralizedMixins = new LinkedHashSet<>();
    /** per-neutralized-mixin drift reasons, for the translation report */
    final List<String> neutralizationLog = new ArrayList<>();

    int fmlChainsSpliced;
    int resourceLocationCtors;
    int ctorRetargetsApplied;
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
        addSrgRules(readLines(rulesDir.resolve("srgmembers.txt")));
        addRetargetRules(readLines(rulesDir.resolve("memberretargets.txt")));
        addCtorRetargetRules(readLines(rulesDir.resolve("ctorretargets.txt")));
        addMethodDrops(readLines(rulesDir.resolve("methoddrops.txt")));
        addClasslist(readLines(rulesDir.resolve("classlist-1211.txt")));
        addMojMemberRenames(readLines(rulesDir.resolve("mojmemberrenames.txt")));
        addMemberTargets(readLines(rulesDir.resolve("membertargets-1211.txt")));
        loadShimsFromDir(rulesDir.resolve("shims"));
        printRuleStats();
    }

    /** rules bundled inside the agent jar (Connector-style drop-in operation) */
    void loadRulesFromClasspath() throws IOException {
        addPackageRules(resourceLines("/rules/packagerenames.txt"));
        addTypeRules(resourceLines("/rules/typerenames.txt"));
        addMemberRules(resourceLines("/rules/memberrenames.txt"));
        addDescRules(resourceLines("/rules/memberdescrenames.txt"));
        addSrgRules(resourceLines("/rules/srgmembers.txt"));
        addRetargetRules(resourceLines("/rules/memberretargets.txt"));
        addCtorRetargetRules(resourceLines("/rules/ctorretargets.txt"));
        addMethodDrops(resourceLinesOptional("/rules/methoddrops.txt"));
        addClasslist(resourceLinesOptional("/rules/classlist-1211.txt"));
        addMojMemberRenames(resourceLines("/rules/mojmemberrenames.txt"));
        addMemberTargets(resourceLinesOptional("/rules/membertargets-1211.txt"));
        loadShimsFromClasspath("/rules/shims");
        printRuleStats();
    }

    public static void translate(Path in, Path out) throws Exception {
        Rewriter r = new Rewriter();
        r.loadRulesFromClasspath();
        r.run(in, out);
    }

    private void printRuleStats() {
        System.out.printf("rules: %d package, %d type, %d member, %d desc, %d srg, %d retarget, %d ctor-retarget, %d method-drops, %d classlist, %d mojrenames, %d member-targets classes, %d shims%n",
                packageRenames.size(), typeRenames.size(), memberRenames.size(),
                memberDescRenames.size(), srgMemberRenames.size(), memberRetargets.size(),
                ctorRetargets.size(), methodDrops.size(), classlist.size(), mojMemberRenames.size(),
                memberTargets.size(), shims.size());
    }

    /** loads the shim manifest + bytecode from a rules directory (CLI mode) */
    private void loadShimsFromDir(Path dir) throws IOException {
        Path manifest = dir.resolve("shims.txt");
        if (!Files.exists(manifest)) return;
        for (String line : readLines(manifest)) {
            Path classFile = dir.resolve(line + ".class");
            if (!Files.exists(classFile)) {
                throw new IOException("shim manifest lists " + line + " but " + classFile + " is missing");
            }
            shims.put(line, Files.readAllBytes(classFile));
        }
        indexShimSimpleNames();
    }

    /** loads the shim manifest + bytecode from agent-jar resources (drop-in mode) */
    private void loadShimsFromClasspath(String resourceDir) throws IOException {
        List<String> lines;
        try {
            lines = resourceLines(resourceDir + "/shims.txt");
        } catch (IOException e) {
            return; // no shims bundled (legacy agent jar)
        }
        for (String line : lines) {
            byte[] bytes;
            try (InputStream is = Rewriter.class.getResourceAsStream(resourceDir + "/" + line + ".class")) {
                if (is == null) throw new IOException("shim manifest lists " + line + " but the class resource is missing");
                bytes = readAll(is);
            }
            shims.put(line, bytes);
        }
        indexShimSimpleNames();
    }

    private void indexShimSimpleNames() {
        for (String name : shims.keySet()) {
            String simple = name.substring(name.lastIndexOf('/') + 1);
            shimSimpleNames.put(simple, name);
        }
    }

    /** pins the per-module shim package; called from run() once the mod id is known */
    void initShimRoot(String modId) {
        String id = (modId == null || modId.isBlank()) ? "unknown" : modId.replaceAll("[^A-Za-z0-9_$]", "_");
        shimRoot = "com/modbridge/shim/" + id;
    }

    /** resolves the "@SHIM@/<SimpleName>" placeholder used in typerenames targets */
    String resolveShimTarget(String mapped) {
        if (mapped != null && mapped.startsWith("@SHIM@/")) {
            String simple = mapped.substring("@SHIM@/".length());
            if (!shimSimpleNames.containsKey(simple)) {
                throw new IllegalStateException("@SHIM@ target " + simple + " is not in the shim manifest");
            }
            String root = shimRoot != null ? shimRoot : "com/modbridge/shim/unknown";
            return root + "/" + simple;
        }
        return mapped;
    }

    /** phase-5: inject the shim carrier classes into the translated jar, RELOCATED to
     *  the per-module shim package (com/modbridge/shim/<modid>/...). FML exports every
     *  package of a mod module and JPMS forbids split packages across layer modules —
     *  platform-name parking fails module resolution (matrix-run005). Unreferenced
     *  shims are inert bytecode; classes the jar already defines are never overridden. */
    void injectShims(Map<String, byte[]> entries) {
        if (shims.isEmpty() || shimRoot == null) return;
        for (Map.Entry<String, byte[]> e : shims.entrySet()) {
            String original = e.getKey();
            String simple = original.substring(original.lastIndexOf('/') + 1);
            String relocatedName = shimRoot + "/" + simple;
            String entryName = relocatedName + ".class";
            if (entries.putIfAbsent(entryName, relocateShimClass(e.getValue(), relocatedName)) == null) {
                shimsInjected++;
            }
        }
        if (shimsInjected > 0) {
            System.out.println("phase-5 shims injected into " + shimRoot + ": " + shimsInjected);
        }
    }

    /** rewrites a shim class's bytecode from its original internal name to
     *  <shimRoot>/<SimpleName> — same-shape rename via the ASM tree API (no
     *  asm-commons in tools/lib): class/super/interfaces/inner/nest headers, member
     *  descriptors + signatures, instruction owners/types, handles, frames, locals. */
    private byte[] relocateShimClass(byte[] bytes, String target) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        String original = cn.name;
        Map<String, String> names = new HashMap<>();
        for (String other : shims.keySet()) {
            String otherSimple = other.substring(other.lastIndexOf('/') + 1);
            names.put(other, shimRoot + "/" + otherSimple);
        }
        cn.name = target;
        cn.superName = names.getOrDefault(cn.superName, cn.superName);
        cn.interfaces.replaceAll(i -> names.getOrDefault(i, i));
        if (cn.nestHostClass != null) cn.nestHostClass = names.getOrDefault(cn.nestHostClass, cn.nestHostClass);
        if (cn.nestMembers != null) cn.nestMembers.replaceAll(i -> names.getOrDefault(i, i));
        if (cn.permittedSubclasses != null) cn.permittedSubclasses.replaceAll(i -> names.getOrDefault(i, i));
        if (cn.innerClasses != null) {
            for (InnerClassNode icn : cn.innerClasses) {
                icn.name = names.getOrDefault(icn.name, icn.name);
                if (icn.outerName != null) icn.outerName = relocateSimpleAware(icn.outerName, names);
                if (icn.innerName != null) icn.innerName = relocateSimpleAware(icn.innerName, names);
            }
        }
        if (cn.signature != null) cn.signature = relocateDescText(cn.signature, names);
        for (FieldNode f : cn.fields) {
            f.desc = relocateDescText(f.desc, names);
            if (f.signature != null) f.signature = relocateDescText(f.signature, names);
        }
        for (MethodNode m : cn.methods) {
            m.desc = relocateDescText(m.desc, names);
            if (m.signature != null) m.signature = relocateDescText(m.signature, names);
            if (m.exceptions != null) m.exceptions.replaceAll(x -> names.getOrDefault(x, x));
            if (m.tryCatchBlocks != null) {
                for (TryCatchBlockNode t : m.tryCatchBlocks) {
                    if (t.type != null) t.type = names.getOrDefault(t.type, t.type);
                }
            }
            for (AbstractInsnNode n : m.instructions) {
                if (n instanceof TypeInsnNode ti) {
                    ti.desc = names.getOrDefault(ti.desc, ti.desc);
                } else if (n instanceof MethodInsnNode mi) {
                    mi.owner = names.getOrDefault(mi.owner, mi.owner);
                    mi.desc = relocateDescText(mi.desc, names);
                } else if (n instanceof FieldInsnNode fi) {
                    fi.owner = names.getOrDefault(fi.owner, fi.owner);
                    fi.desc = relocateDescText(fi.desc, names);
                } else if (n instanceof InvokeDynamicInsnNode id) {
                    id.desc = relocateDescText(id.desc, names);
                    if (id.bsm != null) {
                        id.bsm = new Handle(id.bsm.getTag(),
                                names.getOrDefault(id.bsm.getOwner(), id.bsm.getOwner()),
                                id.bsm.getName(), relocateDescText(id.bsm.getDesc(), names),
                                id.bsm.isInterface());
                    }
                    if (id.bsmArgs != null) {
                        for (int i = 0; i < id.bsmArgs.length; i++) {
                            if (id.bsmArgs[i] instanceof Type ty) {
                                id.bsmArgs[i] = Type.getType(relocateDescText(ty.getDescriptor(), names));
                            }
                        }
                    }
                } else if (n instanceof FrameNode fr) {
                    if (fr.local != null) fr.local.replaceAll(o -> o instanceof String s ? names.getOrDefault(s, s) : o);
                    if (fr.stack != null) fr.stack.replaceAll(o -> o instanceof String s ? names.getOrDefault(s, s) : o);
                }
            }
            if (m.localVariables != null) {
                for (LocalVariableNode lv : m.localVariables) {
                    lv.desc = relocateDescText(lv.desc, names);
                    if (lv.signature != null) lv.signature = relocateDescText(lv.signature, names);
                }
            }
        }
        // sanity: the relocated class must now self-describe under the new name
        if (!cn.name.equals(target)) throw new IllegalStateException("shim relocation failed for " + original);
        return toBytes(cn);
    }

    /** replaces internal-name occurrences inside descriptor/signature text (whole-name) */
    private static String relocateDescText(String text, Map<String, String> names) {
        if (text == null) return null;
        String out = text;
        for (Map.Entry<String, String> e : names.entrySet()) {
            out = out.replace("L" + e.getKey() + ";", "L" + e.getValue() + ";");
        }
        return out;
    }

    /** binary-name (dots) form used by InnerClassNode outer/inner names */
    private static String relocateSimpleAware(String dotted, Map<String, String> names) {
        String slashed = dotted.replace('.', '/');
        String replaced = names.get(slashed);
        return replaced != null ? replaced.replace('/', '.') : dotted;
    }

    private void addClasslist(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) classlist.add(t);
        }
    }

    private void addMojMemberRenames(List<String> lines) throws IOException {
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            mojMemberRenames.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    private void addMemberTargets(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = t.split(" ");
            if (parts.length != 3) continue;
            memberTargets.computeIfAbsent(parts[0], k -> new HashMap<>())
                    .computeIfAbsent(parts[1], k -> new HashSet<>())
                    .add(parts[2]);
        }
    }

    private void addRetargetRules(List<String> lines) throws IOException {
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            String[] sides = line.split("->");
            if (sides.length != 2) throw new IOException("bad memberretarget line: " + line);
            String[] key = sides[0].trim().split("\\|");
            String[] val = sides[1].trim().split("\\|");
            if (key.length != 3 || val.length != 3) throw new IOException("bad memberretarget line: " + line);
            memberRetargets.put(key[0] + "|" + key[1] + "|" + key[2],
                    new String[]{val[0], val[1], val[2]});
        }
    }

    /** oldOwner|oldCtorDesc -> newOwner|receiverField|newMethod|newMethodDesc */
    void addCtorRetargetRules(List<String> lines) throws IOException {
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            String[] sides = line.split("->");
            if (sides.length != 2) throw new IOException("bad ctorretarget line: " + line);
            String[] key = sides[0].trim().split("\\|");
            String[] val = sides[1].trim().split("\\|");
            if (key.length != 2 || val.length != 4) throw new IOException("bad ctorretarget line: " + line);
            ctorRetargets.put(key[0] + "|" + key[1], val);
            ctorRetargetOwners.add(key[0]);
        }
    }

    /** owner|desc|name of methods whose whole body is replaced by a typed return */
    void addMethodDrops(List<String> lines) throws IOException {
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            String[] key = line.split("\\|");
            if (key.length != 3) throw new IOException("bad methoddrop line: " + line);
            methodDrops.add(key[0] + "|" + key[1] + "|" + key[2]);
        }
    }

    private void addSrgRules(List<String> lines) throws IOException {
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            srgMemberRenames.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
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
            String from = parts[0].trim();
            if (from.endsWith("$")) {
                prefixTypeRenames.put(from, parts[1].trim());
            } else {
                typeRenames.put(from, parts[1].trim());
            }
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

    /** like resourceLines, but a missing resource yields an empty list (optional rule file) */
    private static List<String> resourceLinesOptional(String path) throws IOException {
        try (InputStream is = Rewriter.class.getResourceAsStream(path)) {
            if (is == null) return List.of();
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

        // phase-4: parse mixin configs up front so drift-checking can tell which mixin
        // classes actually load on a dedicated server (common/"mixins"+"server" arrays vs "client")
        initShimRoot(modsToml == null ? null : extract(modsToml, "modId"));

        // phase-4: parse mixin configs up front so drift-checking can tell which mixin
        // classes actually load on a dedicated server (common/"mixins"+"server" arrays vs "client")
        Map<String, Object> mixinConfigs = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".json")) continue;
            try {
                Object parsed = Json.parse(new String(e.getValue(), StandardCharsets.UTF_8));
                if (RefmapRemapper.isMixinConfig(parsed)) mixinConfigs.put(e.getKey(), parsed);
            } catch (Json.JsonError ignored) {
                // not JSON we care about; leave untouched
            }
        }
        Set<String> serverMixins = serverSideMixinClasses(mixinConfigs);

        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            if (!name.endsWith(".class")) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(entries.get(name)).accept(cn, 0);
            boolean mixin = isMixinClass(cn);
            if (mixin) mixinClassesSeen++;
            transform(cn);
            if (mixin && driftCheckable(cn.name, serverMixins)) {
                List<String> reasons = mixinDriftReasons(cn);
                if (!reasons.isEmpty()) {
                    neutralizedMixins.add(cn.name);
                    for (String reason : reasons) {
                        neutralizationLog.add(cn.name + ": " + reason);
                    }
                    entries.remove(name); // drop the class from the translation entirely
                    continue;
                }
            }
            entries.remove(name);
            entries.put(cn.name + ".class", toBytes(cn));
            classesTransformed++;
        }

        // phase-4 resource passes: refmaps, mixin configs, accesstransformer, nested jars
        remapRefmaps(entries);
        pruneMixinConfigs(entries, mixinConfigs);
        remapAccessTransformer(entries);
        dropNestedJars(entries);

        entries.put("META-INF/neoforge.mods.toml",
                generateModsToml(modsToml).getBytes(StandardCharsets.UTF_8));
        entries.put("pack.mcmeta", generatePackMeta(packMeta).getBytes(StandardCharsets.UTF_8));

        injectShims(entries);

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
        System.out.printf("translated %d classes; fml chains spliced=%d, ResourceLocation ctors rewritten=%d, ctor retargets=%d, method drops=%d, member renames=%d%n",
                classesTransformed, fmlChainsSpliced, resourceLocationCtors, ctorRetargetsApplied,
                methodDropsApplied, memberRenamesApplied);
        System.out.printf("mixin phase-4: refmaps remapped=%d, mixin classes seen=%d, neutralized=%d, nested jars dropped=%d%n",
                refmapsRemapped, mixinClassesSeen, neutralizedMixins.size(), nestedJarsDropped);
        for (String line : neutralizationLog) {
            System.out.println("neutralized mixin: " + line);
        }
        if (atLinesRemapped > 0 || atLinesUnmapped > 0) {
            System.out.printf("accesstransformer.cfg: %d member refs remapped, %d left SRG (owner outside table universe)%n",
                    atLinesRemapped, atLinesUnmapped);
        }
        System.out.println("wrote " + out);
    }

    /**
     * Internal names of the mixin classes that a dedicated server will actually load:
     * every entry of a config's "mixins" and "server" arrays (package-relative). Classes
     * only listed under "client" never load there and are exempt from drift neutralization.
     */
    private static Set<String> serverSideMixinClasses(Map<String, Object> mixinConfigs) {
        Set<String> out = new HashSet<>();
        for (Object cfg : mixinConfigs.values()) {
            if (!(cfg instanceof Map<?, ?> m) || !(m.get("package") instanceof String pkg)) continue;
            String pkgSlash = pkg.replace('.', '/') + "/";
            for (String arrayKey : RefmapRemapper.dedicatedSideArrays()) {
                if (m.get(arrayKey) instanceof List<?> l) {
                    for (Object o : l) {
                        if (o instanceof String entry) {
                            // entries are package-relative and may carry subpackage dots
                            // ("self.SelfOpenableContainerMixin")
                            out.add(pkgSlash + entry.replace('.', '/'));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** unlisted @Mixin classes are treated as server-loadable (conservative default) */
    private static boolean driftCheckable(String internalName, Set<String> serverMixins) {
        return serverMixins.isEmpty() || serverMixins.contains(internalName);
    }

    void transform(ClassNode cn) {
        if (isModClass(cn)) {
            rewriteModConstructor(cn);
        }
        rewriteResourceLocationCtors(cn);
        rewriteCtorRetargets(cn);
        applyMethodDrops(cn);
        // phase-4: mixin annotations carry their injection targets as plain string
        // constants ASM cannot resolve — rewrite them in the raw 1.20.1 space before
        // the generic passes (remapClass only touches Type/enum values, not strings).
        if (isMixinClass(cn)) {
            remapMixinAnnotationStrings(cn);
            remapAccessorMembers(cn);
        }
        applyMemberRenames(cn);
        remapClass(cn);
    }

    private static boolean isModClass(ClassNode cn) {
        return hasAnnotation(cn, MOD_ANNOTATION);
    }

    private static boolean isMixinClass(ClassNode cn) {
        return hasAnnotation(cn, MIXIN_ANNOTATION);
    }

    /** @Mixin (and friends) are CLASS retention: they land in RuntimeInvisibleAnnotations */
    private static boolean hasAnnotation(ClassNode cn, String desc) {
        if (cn.visibleAnnotations != null) {
            for (AnnotationNode an : cn.visibleAnnotations) {
                if (desc.equals(an.desc)) return true;
            }
        }
        if (cn.invisibleAnnotations != null) {
            for (AnnotationNode an : cn.invisibleAnnotations) {
                if (desc.equals(an.desc)) return true;
            }
        }
        return false;
    }

    /** SRG id -> mojmap name, or null when outside the table universe (never guessed) */
    String srgOrNull(String id) {
        return srgMemberRenames.get(id);
    }

    /** remaps one mixin reference string (SRG ids, L-refs, dotted/slash class names) */
    String remapMemberRefString(String s) {
        return RefmapRemapper.remapRefString(s, this::srgOrNull, this::remapType, mojMemberRenames::get);
    }

    private void remapMixinAnnotationStrings(ClassNode cn) {
        remapAnnotationStrings(cn.visibleAnnotations);
        remapAnnotationStrings(cn.invisibleAnnotations);
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                remapAnnotationStrings(f.visibleAnnotations);
                remapAnnotationStrings(f.invisibleAnnotations);
            }
        }
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                remapAnnotationStrings(m.visibleAnnotations);
                remapAnnotationStrings(m.invisibleAnnotations);
                if (m.visibleParameterAnnotations != null) {
                    for (List<AnnotationNode> anns : m.visibleParameterAnnotations) remapAnnotationStrings(anns);
                }
                if (m.invisibleParameterAnnotations != null) {
                    for (List<AnnotationNode> anns : m.invisibleParameterAnnotations) remapAnnotationStrings(anns);
                }
            }
        }
    }

    private void remapAnnotationStrings(List<AnnotationNode> annotations) {
        if (annotations == null) return;
        for (AnnotationNode an : annotations) {
            if (an == null) continue;
            if (an.desc != null && an.desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX)) {
                // require=0: a drifted call site logs an error instead of InjectionError
                setAnnotationValue(an, "require", 0);
            }
            boolean accessorDecl = ACCESSOR_ANNOTATION.equals(an.desc) || INVOKER_ANNOTATION.equals(an.desc);
            if (an.values == null) continue;
            for (int i = 1; i < an.values.size(); i += 2) {
                Object v = remapAnnotationStringValue(an.values.get(i));
                // an explicit @Accessor("name")/@Invoker("name") is a dev-space member name:
                // apply the mojmap->mojmap rename table on exact match
                if (accessorDecl && "value".equals(an.values.get(i - 1))
                        && v instanceof String s && mojMemberRenames.containsKey(s)) {
                    v = mojMemberRenames.get(s);
                }
                an.values.set(i, v);
            }
        }
    }

    /**
     * Mixin derives an accessor/invoker's target member from the proxy method name
     * (invokeTryCatchFire -> tryCatchFire). That name is 1.20.1 dev-space; when Mojang
     * renamed the member across to 1.21.1 the derivation must follow
     * (mojmemberrenames.txt), or the accessor stops matching (InvalidAccessorException).
     */
    void remapAccessorMembers(ClassNode cn) {
        if (mojMemberRenames.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            if (hasAccessorAnnotation(mn)) {
                Matcher m = ACCESSOR_METHOD_NAME.matcher(mn.name);
                if (!m.matches()) continue;
                String derived = Character.toLowerCase(m.group(2).charAt(0)) + m.group(2).substring(1);
                String renamed = mojMemberRenames.get(derived);
                if (renamed != null) {
                    mn.name = m.group(1) + Character.toUpperCase(renamed.charAt(0)) + renamed.substring(1);
                }
            } else if (hasShadowAnnotation(mn.visibleAnnotations, mn.invisibleAnnotations)) {
                String renamed = mojMemberRenames.get(mn.name);
                if (renamed != null) mn.name = renamed;
            }
        }
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                if (hasShadowAnnotation(f.visibleAnnotations, f.invisibleAnnotations)) {
                    String renamed = mojMemberRenames.get(f.name);
                    if (renamed != null) f.name = renamed;
                }
            }
        }
    }

    private static boolean hasAccessorAnnotation(MethodNode mn) {
        for (List<AnnotationNode> anns : new List[]{mn.visibleAnnotations, mn.invisibleAnnotations}) {
            if (anns == null) continue;
            for (AnnotationNode an : anns) {
                if (ACCESSOR_ANNOTATION.equals(an.desc) || INVOKER_ANNOTATION.equals(an.desc)) return true;
            }
        }
        return false;
    }

    /** derived (lower-cased, prefix-stripped) target member name of an accessor/invoker method */
    private static String accessorTargetName(String methodName) {
        Matcher m = ACCESSOR_METHOD_NAME.matcher(methodName);
        if (!m.matches()) return null;
        return Character.toLowerCase(m.group(2).charAt(0)) + m.group(2).substring(1);
    }

    private static void setAnnotationValue(AnnotationNode an, String key, Object value) {
        if (an.values == null) an.values = new ArrayList<>();
        for (int i = 0; i < an.values.size(); i += 2) {
            if (key.equals(an.values.get(i))) {
                an.values.set(i + 1, value);
                return;
            }
        }
        an.values.add(key);
        an.values.add(value);
    }

    @SuppressWarnings("unchecked")
    private Object remapAnnotationStringValue(Object v) {
        if (v instanceof String s) {
            return remapMemberRefString(s);
        }
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, remapAnnotationStringValue(copy.get(i)));
            }
            return copy;
        }
        if (v instanceof AnnotationNode nested) {
            remapAnnotationStrings(java.util.Collections.singletonList(nested));
        }
        return v;
    }

    // ------------------------------------------------------------- phase-4: drift

    /**
     * A mixin class is neutralized (dropped, config entry removed, drift reported) when
     * its target or any eagerly-loaded platform class it declares is absent from the
     * 1.21.1 platform (cross-version class drift, e.g. removed forge capability holder
     * API). Left broken it would hard-fail the whole mixin config; dropped, the rest of
     * the mod still injects.
     */
    List<String> mixinDriftReasons(ClassNode cn) {
        List<String> reasons = new ArrayList<>();
        List<String> targets = mixinTargets(cn);
        for (String target : targets) {
            if (isPlatformType(target) && !classKnown(target)) {
                reasons.add("mixin target " + target + " absent from 1.21.1 platform");
            }
        }
        for (String ref : eagerPlatformRefs(cn)) {
            if (isPlatformType(ref) && !classKnown(ref)) {
                reasons.add("eagerly references " + ref + " absent from 1.21.1 platform");
            }
        }
        // accessor/invoker member existence: a proxy whose target member was removed or
        // renamed (and not covered by mojmemberrenames.txt) can never apply
        if (!memberTargets.isEmpty()) {
            for (MethodNode mn : cn.methods) {
                if (!hasAccessorAnnotation(mn)) continue;
                String derived = explicitAccessorValue(mn);
                if (derived == null) derived = accessorTargetName(mn.name);
                if (derived == null) continue;
                for (String target : targets) {
                    Map<String, Set<String>> members = memberTargets.get(target);
                    if (members == null) continue; // non-platform or unknown target
                    if (!members.containsKey(derived)) {
                        reasons.add("accessor target " + target + "." + derived
                                + " absent from 1.21.1 member inventory");
                    }
                }
            }
            // @Shadow members bind the mixin to the target's exact member: a drifted
            // name or descriptor (mojmap-version API churn, e.g. MobEffect ->
            // Holder<MobEffect>) breaks the apply — recoverable only by argument-level
            // shims, so the mixin is neutralized with the drift recorded.
            for (MethodNode mn : cn.methods) {
                if (!hasShadowAnnotation(mn.visibleAnnotations, mn.invisibleAnnotations)) continue;
                String desc = remapDesc(mn.desc);
                for (String target : targets) {
                    Map<String, Set<String>> members = memberTargets.get(target);
                    if (members == null) continue;
                    Set<String> descs = members.get(mn.name);
                    if (descs == null || !descs.contains(desc)) {
                        reasons.add("@Shadow " + mn.name + desc + " not present on " + target
                                + " in 1.21.1 (name or descriptor drifted)");
                    }
                }
            }
            if (cn.fields != null) {
                for (FieldNode f : cn.fields) {
                    if (!hasShadowAnnotation(f.visibleAnnotations, f.invisibleAnnotations)) continue;
                    String desc = remapDesc(f.desc);
                    for (String target : targets) {
                        Map<String, Set<String>> members = memberTargets.get(target);
                        if (members == null) continue;
                        Set<String> descs = members.get(f.name);
                        if (descs == null || !descs.contains(desc)) {
                            reasons.add("@Shadow " + f.name + " " + desc + " not present on " + target
                                    + " in 1.21.1 (name or descriptor drifted)");
                        }
                    }
                }
            }
        }
        return reasons;
    }

    private static boolean hasShadowAnnotation(List<AnnotationNode>... annotationLists) {
        for (List<AnnotationNode> anns : annotationLists) {
            if (anns == null) continue;
            for (AnnotationNode an : anns) {
                if (SHADOW_ANNOTATION.equals(an.desc)) return true;
            }
        }
        return false;
    }

    /** explicit @Accessor("...")/@Invoker("...") target member, when given */
    private static String explicitAccessorValue(MethodNode mn) {
        for (List<AnnotationNode> anns : new List[]{mn.visibleAnnotations, mn.invisibleAnnotations}) {
            if (anns == null) continue;
            for (AnnotationNode an : anns) {
                if (!ACCESSOR_ANNOTATION.equals(an.desc) && !INVOKER_ANNOTATION.equals(an.desc)) continue;
                if (an.values == null) continue;
                for (int i = 0; i + 1 < an.values.size(); i += 2) {
                    if ("value".equals(an.values.get(i)) && an.values.get(i + 1) instanceof String s) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /** @Mixin value types (already remapped to 1.21.1 space by transform) and targets strings */
    private static List<String> mixinTargets(ClassNode cn) {
        List<String> out = new ArrayList<>();
        for (List<AnnotationNode> anns : new List[]{cn.visibleAnnotations, cn.invisibleAnnotations}) {
            if (anns == null) continue;
            for (AnnotationNode an : anns) {
                if (!MIXIN_ANNOTATION.equals(an.desc) || an.values == null) continue;
                // ASM tree: values alternate name (even index), value (odd index)
                for (int i = 0; i + 1 < an.values.size(); i += 2) {
                    if ("targets".equals(an.values.get(i)) && an.values.get(i + 1) instanceof String s) {
                        out.add(s.replace('.', '/'));
                    } else if ("value".equals(an.values.get(i))) {
                        collectTargetTypes(an.values.get(i + 1), out);
                    }
                }
            }
        }
        return out;
    }

    private static void collectTargetTypes(Object v, List<String> out) {
        if (v instanceof Type t) {
            out.add(t.getDescriptor().replaceFirst("^L", "").replaceFirst(";$", ""));
        } else if (v instanceof List<?> l) {
            for (Object o : l) collectTargetTypes(o, out);
        }
    }

    /** every platform class the JVM must resolve the moment this mixin class loads */
    private static Set<String> eagerPlatformRefs(ClassNode cn) {
        Set<String> refs = new LinkedHashSet<>();
        if (cn.superName != null) refs.add(cn.superName);
        if (cn.interfaces != null) refs.addAll(cn.interfaces);
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) addDescInternals(refs, f.desc);
        }
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                addDescInternals(refs, m.desc);
                if (m.exceptions != null) {
                    for (String ex : m.exceptions) {
                        if (isPlatformType(ex)) refs.add(ex);
                    }
                }
            }
        }
        if (cn.recordComponents != null) {
            for (org.objectweb.asm.tree.RecordComponentNode rc : cn.recordComponents) {
                addDescInternals(refs, rc.descriptor);
            }
        }
        return refs;
    }

    private static void addDescInternals(Set<String> refs, String desc) {
        if (desc == null) return;
        Matcher m = L_REF.matcher(desc);
        while (m.find()) {
            String internal = m.group(1);
            if (isPlatformType(internal)) refs.add(internal);
        }
    }

    private static boolean isPlatformType(String internal) {
        for (String prefix : PLATFORM_PACKAGES) {
            if (internal.startsWith(prefix)) return true;
        }
        return false;
    }

    /** classlist empty (no inventory shipped) -> only leftover forge refs count as drift */
    boolean classKnown(String internal) {
        if (shimRoot != null && internal.startsWith(shimRoot + "/")) {
            return shimSimpleNames.containsKey(internal.substring(shimRoot.length() + 1)); // injected into this very jar
        }
        if (classlist.isEmpty()) return !internal.startsWith("net/minecraftforge/");
        return classlist.contains(internal);
    }

    // --------------------------------------------------- phase-4: jar resource passes

    /** rewrites every mixin refmap (keys + values SRG -> mojmap), then prunes neutralized entries */
    private void remapRefmaps(Map<String, byte[]> entries) throws IOException {
        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            boolean byName = name.endsWith("-refmap.json");
            if (!byName && !name.endsWith(".json")) continue;
            Object parsed;
            try {
                parsed = Json.parse(new String(entries.get(name), StandardCharsets.UTF_8));
            } catch (Json.JsonError e) {
                continue;
            }
            if (!RefmapRemapper.isRefmap(parsed)) continue;
            parsed = RefmapRemapper.remapTreeStrings(parsed, this::remapMemberRefString);
            RefmapRemapper.stripRefmapClasses(parsed, neutralizedMixins);
            entries.put(name, Json.write(parsed).getBytes(StandardCharsets.UTF_8));
            refmapsRemapped++;
        }
    }

    /**
     * Removes neutralized mixin classes from their configs' server-side arrays, and
     * downgrades injector strictness (defaultRequire 1 -> 0): refmap remapping cannot
     * prove the *wrapped call site* still exists in 1.21.1 (NeoForge also patches
     * vanilla bodies — e.g. PoiType's ctor no longer calls Set.copyOf), and a single
     * drifted injector must log an error, not kill the whole mod's boot.
     */
    private void pruneMixinConfigs(Map<String, byte[]> entries, Map<String, Object> mixinConfigs) throws IOException {
        for (Map.Entry<String, Object> e : mixinConfigs.entrySet()) {
            boolean changed = false;
            List<String> removed = RefmapRemapper.stripMixins(e.getValue(), neutralizedMixins);
            if (!removed.isEmpty()) {
                changed = true;
                for (String simple : removed) {
                    System.out.println("mixin config " + e.getKey() + ": dropped entry " + simple + " (neutralized)");
                }
            }
            if (e.getValue() instanceof Map<?, ?> m && m.get("injectors") instanceof Map<?, ?> inj) {
                @SuppressWarnings("unchecked")
                Map<String, Object> injectors = (Map<String, Object>) inj;
                Object require = injectors.get("defaultRequire");
                if (require instanceof Number n && n.intValue() != 0) {
                    injectors.put("defaultRequire", 0);
                    changed = true;
                    System.out.println("mixin config " + e.getKey()
                            + ": defaultRequire " + n.intValue() + " -> 0 (call-site drift fails soft, logged)");
                }
            }
            if (changed) {
                entries.put(e.getKey(), Json.write(e.getValue()).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** SRG member ids and class names inside accesstransformer.cfg meet the same runtime */
    private void remapAccessTransformer(Map<String, byte[]> entries) throws IOException {
        byte[] bytes = entries.get(ACCESS_TRANSFORMER);
        if (bytes == null) return;
        String content = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                out.append(line).append('\n');
                continue;
            }
            String code = line, tail = "";
            int hash = line.indexOf('#');
            if (hash >= 0) {
                code = line.substring(0, hash);
                tail = line.substring(hash);
            }
            String[] parts = code.trim().split("\\s+");
            if (parts.length >= 2) {
                // <modifier>+ <dotted.Class> [<member>] — class names stay dotted in the file
                parts[1] = RefmapRemapper.remapRefString(parts[1], this::srgOrNull, this::remapType);
                if (parts.length >= 3) {
                    String before = parts[2];
                    parts[2] = remapMemberRefString(before);
                    if (before.equals(parts[2]) && before.matches(".*(?:^|[^A-Za-z0-9_])(?:m|f)_\\d+_.*")) {
                        atLinesUnmapped++;
                    } else if (!before.equals(parts[2])) {
                        atLinesRemapped++;
                    }
                }
            }
            out.append(String.join(" ", parts)).append(tail).append('\n');
        }
        entries.put(ACCESS_TRANSFORMER, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Nested forge-side jars cannot ride along (the platform exports the same packages —
     * e.g. MixinExtras — at module level), and stripped META-INF/jars entries leave
     * META-INF/jarjar/metadata.json pointing at ghosts. Both are removed wholesale.
     */
    private void dropNestedJars(Map<String, byte[]> entries) {
        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            if (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/")) {
                entries.remove(name);
                nestedJarsDropped++;
            }
        }
    }

    /**
     * 1.20.1 mods grab the mod event bus via FMLJavaModLoadingContext inside a no-arg
     * constructor; NeoForge 1.21 injects the bus as a constructor parameter instead.
     *
     * The naive splice (insert ALOAD 1 into the old body) silently corrupts every
     * StackMapTable frame of the constructor: the body's locals all shift by one while
     * the copied frames keep describing the old layout (an AIOOBE at class-load time in
     * ASM's frame expander). Instead the original body MOVES verbatim into a synthetic
     * {@code mb$init()V} — identical local layout [this, locals...], frames stay valid —
     * and the new real constructor is a short frame-free stub: super call, stash the bus
     * into a synthetic static field, call mb$init.
     */
    void rewriteModConstructor(ClassNode cn) {
        for (MethodNode mn : new ArrayList<>(cn.methods)) {
            if (!"<init>".equals(mn.name) || !"()V".equals(mn.desc)) continue;

            // locate the super-constructor call; it must head a pure-push prefix
            AbstractInsnNode n = mn.instructions.getFirst();
            List<AbstractInsnNode> superArgs = new ArrayList<>();
            MethodInsnNode superCall = null;
            while (n != null) {
                if (n instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(m.name)) {
                    superCall = m;
                    break;
                }
                // pseudo-instructions are transparent to the prefix scan
                if (n instanceof LabelNode || n instanceof FrameNode || n instanceof LineNumberNode) {
                    n = n.getNext();
                    continue;
                }
                if (!isPurePush(n)) break;
                superArgs.add(n);
                n = n.getNext();
            }
            if (superCall == null) continue;

            boolean found = false;
            AbstractInsnNode i = superCall.getNext();
            while (i != null) {
                AbstractInsnNode next = i.getNext();
                if (i instanceof MethodInsnNode m1
                        && m1.getOpcode() == Opcodes.INVOKESTATIC
                        && "get".equals(m1.name)
                        && FML_CONTEXT.equals(m1.owner)
                        && next instanceof MethodInsnNode m2
                        && m2.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "getModEventBus".equals(m2.name)
                        && FML_CONTEXT.equals(m2.owner)) {
                    found = true;
                    i = next;
                    break;
                }
                i = next;
            }
            if (!found) continue; // plain no-arg ctor is also valid on NeoForge 1.21

            // synthetic static holder for the bus, and mb$init()V with the original body
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    MOD_BUS_FIELD, "L" + EVENT_BUS + ";", null, null));
            MethodNode mbInit = new MethodNode((mn.access & ~Opcodes.ACC_PUBLIC) | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_SYNTHETIC, MOD_INIT_METHOD, "()V", mn.signature,
                    mn.exceptions == null ? new String[0] : mn.exceptions.toArray(new String[0]));
            mbInit.visibleAnnotations = mn.visibleAnnotations;
            mbInit.invisibleAnnotations = mn.invisibleAnnotations;
            mbInit.localVariables = mn.localVariables;

            // move the body after the super call into mb$init, splicing the bus lookup
            int spliced = 0;
            AbstractInsnNode body = superCall.getNext();
            while (body != null) {
                AbstractInsnNode next = body.getNext();
                if (body instanceof MethodInsnNode m1
                        && m1.getOpcode() == Opcodes.INVOKESTATIC
                        && "get".equals(m1.name)
                        && FML_CONTEXT.equals(m1.owner)
                        && next instanceof MethodInsnNode m2
                        && m2.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "getModEventBus".equals(m2.name)
                        && FML_CONTEXT.equals(m2.owner)) {
                    AbstractInsnNode after = next.getNext(); // capture before removals (ASM nulls links)
                    FieldInsnNode getBus = new FieldInsnNode(Opcodes.GETSTATIC,
                            cn.name, MOD_BUS_FIELD, "L" + EVENT_BUS + ";");
                    mbInit.instructions.add(getBus);
                    mn.instructions.remove(m1);
                    mn.instructions.remove(m2);
                    fmlChainsSpliced++;
                    spliced++;
                    body = after;
                    continue;
                }
                mn.instructions.remove(body);
                mbInit.instructions.add(body);
                body = next;
            }
            mbInit.maxStack = mn.maxStack;
            mbInit.maxLocals = mn.maxLocals;
            cn.methods.add(mbInit);

            // the new real constructor: super call, stash bus, delegate
            MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                    "(L" + EVENT_BUS + ";)V", null, null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            for (AbstractInsnNode push : superArgs) {
                mn.instructions.remove(push);
                ctor.instructions.add(push);
            }
            mn.instructions.remove(superCall);
            ctor.instructions.add(superCall);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ctor.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                    cn.name, MOD_BUS_FIELD, "L" + EVENT_BUS + ";"));
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    cn.name, MOD_INIT_METHOD, "()V", false));
            ctor.instructions.add(new InsnNode(Opcodes.RETURN));
            ctor.maxStack = superArgs.size() + 2;
            ctor.maxLocals = 2;
            cn.methods.remove(mn);
            cn.methods.add(ctor);
        }
    }

    private static boolean isPurePush(AbstractInsnNode n) {
        if (n instanceof VarInsnNode v) return v.getOpcode() == Opcodes.ALOAD && v.var == 0;
        if (n instanceof InsnNode) {
            int op = n.getOpcode();
            return (op >= Opcodes.ICONST_M1 && op <= Opcodes.DCONST_1) || op == Opcodes.ACONST_NULL;
        }
        if (n instanceof IntInsnNode ii) {
            return ii.getOpcode() == Opcodes.BIPUSH || ii.getOpcode() == Opcodes.SIPUSH;
        }
        return n instanceof LdcInsnNode;
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
                        MethodInsnNode factory;
                        if ("(Ljava/lang/String;Ljava/lang/String;)V".equals(ctor.desc)) {
                            factory = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, RESOURCE_LOCATION, "fromNamespaceAndPath",
                                    "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
                                    false);
                        } else if ("(Ljava/lang/String;)V".equals(ctor.desc)) {
                            // single-arg ctor (ns:path or path) -> ResourceLocation.parse
                            factory = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, RESOURCE_LOCATION, "parse",
                                    "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
                                    false);
                        } else {
                            throw new IllegalStateException(
                                    "unsupported ResourceLocation ctor descriptor " + ctor.desc + " in " + cn.name);
                        }
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

    /**
     * new X(args) -> newOwner.field.newMethod(args) for constructors whose semantics
     * survive behind a public factory-style call (rules/ctorretargets.txt; current
     * entry: the Forge 1.20.1 ModConfig ctor -> ConfigTracker.registerConfig, which
     * field-sets AND tracks exactly like the old self-tracking ctor).
     *
     * Same NEW+DUP forward-scan as the ResourceLocation rewrite; the receiver field is
     * pushed under the args and INVOKESPECIAL becomes INVOKEVIRTUAL — the returned
     * instance replaces the uninitialized ref 1:1 on the operand stack, so maxStack and
     * every frame AFTER the window stay valid. Frames INSIDE the argument region (a
     * branch among the ctor args) still name the uninitialized ref in two slots
     * (NEW+DUP): the first becomes the receiver type, the DUP copy is removed (see
     * fixArgRegionFrames).
     */
    void rewriteCtorRetargets(ClassNode cn) {
        if (ctorRetargets.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            AbstractInsnNode n = mn.instructions.getFirst();
            while (n != null) {
                AbstractInsnNode dup = n.getNext();
                if (!(n instanceof TypeInsnNode t)
                        || t.getOpcode() != Opcodes.NEW
                        || !ctorRetargetOwners.contains(t.desc)
                        || dup == null || dup.getOpcode() != Opcodes.DUP) {
                    n = n.getNext();
                    continue;
                }
                // forward-scan for the INVOKESPECIAL that consumes this NEW+DUP pair
                AbstractInsnNode c = dup.getNext();
                MethodInsnNode ctor = null;
                String[] rule = null;
                while (c != null && !(c instanceof TypeInsnNode)) {
                    if (c instanceof MethodInsnNode mi
                            && mi.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(mi.name)
                            && t.desc.equals(mi.owner)) {
                        rule = ctorRetargets.get(t.desc + "|" + mi.desc);
                        ctor = mi;
                        break;
                    }
                    c = c.getNext();
                }
                if (rule == null) {
                    n = n.getNext(); // no rule for this ctor (or nested-new: leave untouched)
                    continue;
                }
                AbstractInsnNode after = ctor.getNext();
                FieldInsnNode receiver = new FieldInsnNode(Opcodes.GETSTATIC,
                        rule[0], rule[1], "L" + rule[0] + ";");
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        rule[0], rule[2], rule[3], false);
                fixArgRegionFrames(mn, dup, ctor, "L" + rule[0] + ";");
                mn.instructions.insert(dup, receiver);
                mn.instructions.insert(ctor, call);
                mn.instructions.remove(n);
                mn.instructions.remove(dup);
                mn.instructions.remove(ctor);
                ctorRetargetsApplied++;
                n = after;
            }
        }
    }

    /**
     * Inside the ctor-arg region the operand stack carries the uninitialized ref TWICE
     * (NEW pushes it, DUP copies it); frames there name it in both slots. The rewrite
     * replaces the pair with exactly one INSTANCE reference, so the first uninit entry
     * becomes the receiver type and any further copies (the DUP) are removed — other
     * entries keep their positions. A LabelNode on a frame stack is always an
     * uninitialized type, and within the window the only construction in flight is this
     * one (the forward scan stops at any NEW), so every LabelNode found belongs to it.
     */
    private static void fixArgRegionFrames(MethodNode mn, AbstractInsnNode from,
                                           AbstractInsnNode to, String receiverTypeDesc) {
        for (AbstractInsnNode i = from.getNext(); i != to; ) {
            AbstractInsnNode next = i.getNext();
            if (i instanceof FrameNode fr && fr.stack != null) {
                List<Object> stack = new ArrayList<>(fr.stack);
                boolean replaced = false;
                for (int k = 0; k < stack.size(); k++) {
                    if (stack.get(k) instanceof LabelNode) {
                        if (!replaced) {
                            stack.set(k, receiverTypeDesc);
                            replaced = true;
                        } else {
                            stack.remove(k);
                            k--;
                        }
                    }
                }
                if (replaced) {
                    // FrameNode's count fields are private (ASM 9.x) — rebuild the node
                    FrameNode fixed = new FrameNode(fr.type,
                            fr.local == null ? 0 : fr.local.size(),
                            fr.local == null ? null : fr.local.toArray(),
                            stack.size(), stack.toArray());
                    mn.instructions.insert(i, fixed);
                    mn.instructions.remove(i);
                }
            }
            i = next;
        }
    }

    /**
     * Replaces whole method bodies with a typed default return (rules/methoddrops.txt).
     * For surfaces whose 1.20.1 semantics have NO 21.1 counterpart at all and whose
     * call sites cannot succeed (moonlight's ConfigSpecWrapper.loadFromFile reflects
     * into ModConfig.setConfigData — a method the final 21.1 class can never carry),
     * a no-op body is the only reachable state; the alternative is construction death.
     * Used only where the documented behavior is already "logged no-op / defaults".
     * <init> is rejected: a constructor body must keep its super call.
     */
    void applyMethodDrops(ClassNode cn) {
        if (methodDrops.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            if (!methodDrops.contains(cn.name + "|" + mn.desc + "|" + mn.name)) continue;
            if ("<init>".equals(mn.name)) {
                throw new IllegalStateException("methoddrop rule targets a constructor: "
                        + cn.name + mn.desc);
            }
            if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            mn.instructions.clear();
            // the variable table references labels that no longer exist in the (now
            // straight-line) body, and try/catch ranges would be empty — both go
            mn.localVariables = null;
            mn.tryCatchBlocks = null;
            org.objectweb.asm.Type ret = Type.getReturnType(mn.desc);
            switch (ret.getSort()) {
                case Type.VOID -> mn.instructions.add(new InsnNode(Opcodes.RETURN));
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                    mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
                    mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                }
                case Type.LONG -> {
                    mn.instructions.add(new InsnNode(Opcodes.LCONST_0));
                    mn.instructions.add(new InsnNode(Opcodes.LRETURN));
                }
                case Type.FLOAT -> {
                    mn.instructions.add(new InsnNode(Opcodes.FCONST_0));
                    mn.instructions.add(new InsnNode(Opcodes.FRETURN));
                }
                case Type.DOUBLE -> {
                    mn.instructions.add(new InsnNode(Opcodes.DCONST_0));
                    mn.instructions.add(new InsnNode(Opcodes.DRETURN));
                }
                default -> {
                    mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                    mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                }
            }
            mn.maxStack = ret.getSize();
            methodDropsApplied++;
            System.out.println("method body dropped: " + cn.name + "." + mn.name + mn.desc);
        }
    }

    void applyMemberRenames(ClassNode cn) {
        // SRG declarations: production 1.20.1 mods override vanilla members by SRG id
        // (m_5744_, f_91074_ ...) because the jar ships in SRG member space. Call sites
        // are mapped below; the declarations themselves must be renamed too, or the
        // override silently stops overriding once the target runs mojmap (ids are
        // globally unique, so a bare-name lookup is exactly as safe as for call sites).
        if (!srgMemberRenames.isEmpty()) {
            for (MethodNode mn : cn.methods) {
                String mapped = srgMemberRenames.get(mn.name);
                if (mapped != null) mn.name = mapped;
            }
            for (FieldNode f : cn.fields) {
                String mapped = srgMemberRenames.get(f.name);
                if (mapped != null) f.name = mapped;
            }
        }
        if (memberRenames.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            String renamed = memberRenames.get(cn.name + "|" + mn.desc + "|" + mn.name);
            if (renamed != null) mn.name = renamed;
        }
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode n : mn.instructions) {
                if (n instanceof MethodInsnNode m) {
                    m.name = mapSrg(m.name);
                    String renamed = memberRenames.get(m.owner + "|" + m.desc + "|" + m.name);
                    if (renamed != null) { m.name = renamed; memberRenamesApplied++; }
                    String[] retarget = memberRetargets.get(m.owner + "|" + m.desc + "|" + m.name);
                    if (retarget != null) {
                        m.owner = retarget[0];
                        m.desc = retarget[1];
                        boolean itf = "interface".equals(retarget[2]);
                        m.setOpcode(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
                        m.itf = itf;
                        memberRenamesApplied++;
                    }
                    applyDescRewrite(cn, mn, m);
                } else if (n instanceof FieldInsnNode f) {
                    f.name = mapSrg(f.name);
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
        // record component descs live in the Record attribute (not FieldNodes); ASM models
        // them via recordComponents — leaving them stale keeps forge-space types visible to
        // reflection even though the backing field was remapped.
        if (cn.recordComponents != null) {
            for (org.objectweb.asm.tree.RecordComponentNode rc : cn.recordComponents) {
                rc.descriptor = remapDesc(rc.descriptor);
                if (rc.signature != null) rc.signature = remapDesc(rc.signature);
                remapAnnotations(rc.visibleAnnotations);
                remapAnnotations(rc.invisibleAnnotations);
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
        return new Handle(h.getTag(), remapType(h.getOwner()), mapSrg(h.getName()), remapDesc(h.getDesc()), h.isInterface());
    }

    /** SRG member ids are globally unique, so a bare name table is safe to apply anywhere */
    String mapSrg(String name) {
        String mapped = srgMemberRenames.get(name);
        return mapped != null ? mapped : name;
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
        if (v instanceof String[] enumRef && enumRef.length == 2 && enumRef[0].startsWith("L")) {
            // ASM tree represents an enum-constant annotation value as {typeDesc, name};
            // without this, e.g. @OnlyIn(Dist.CLIENT) keeps the forge-side Dist desc.
            return new String[]{remapDesc(enumRef[0]), enumRef[1]};
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
        // StackMapTable frame entries carry ARRAY descriptors for array locals
        // ("[Lnet/...;") — strip the dims, remap the element class, re-attach.
        int dims = 0;
        while (dims < internal.length() && internal.charAt(dims) == '[') dims++;
        if (dims > 0) {
            String elem = internal.substring(dims);
            String remapped = elem.startsWith("L")
                    ? "L" + remapType(elem.substring(1, elem.length() - 1)) + ";"
                    : elem;
            return "[".repeat(dims) + remapped;
        }
        String mapped = typeRenames.get(internal);
        if (mapped != null) return resolveShimTarget(mapped);
        for (Map.Entry<String, String> e : prefixTypeRenames.entrySet()) {
            if (internal.startsWith(e.getKey())) {
                return e.getValue() + internal.substring(e.getKey().length());
            }
        }
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
