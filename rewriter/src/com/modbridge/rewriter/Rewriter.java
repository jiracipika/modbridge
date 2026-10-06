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
import org.objectweb.asm.tree.JumpInsnNode;
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
    /** internal names of every ACC_INTERFACE class on the 21.1 platform (rules/
     *  interfaces-1211.txt, all shipped libraries). Drives the interface call-site
     *  repair: 1.20.1 bytecode that called these types as CLASSES carries Methodref
     *  constants which the JVM rejects with IncompatibleClassChangeError once the
     *  type is an interface (measured wall: DFU 6 DataResult class statics vs DFU 8
     *  interface statics — trial 002 eager-config slice round 2). Optional — empty
     *  disables the pass. */
    final Set<String> interfaces1211 = new HashSet<>();
    /** call-site retargets for members that became interface-default methods:
     *  owner|desc|name -> newOwner|newDesc|interface-or-virtual */
    final Map<String, String[]> memberRetargets = new HashMap<>();
    /** holder-unwrap call sites (rules/methodunwraps.txt): getters whose 21.1 return
     *  became a Holder (owner|desc|name, FINAL space, post desc-rewrite) -> the bare
     *  value type their remaining consumers expect. See repairHolderUnwraps. */
    final Map<String, String> holderUnwraps = new HashMap<>();
    int holderUnwrapsApplied;
    /** bare-wrap call sites (rules/methodwraps.txt): 1.20.1 checkcast-desc -> the
     *  21.1 Holder type + the registry whose wrapAsHolder bridges. See repairBareWraps. */
    final Map<String, String[]> bareWraps = new HashMap<>();
    int bareWrapsApplied;
    /** ctor-call retargets for constructors replaced by a public factory-style call:
     *  owner|ctorDesc -> newOwner|receiverField|newMethod|newMethodDesc */
    final Map<String, String[]> ctorRetargets = new HashMap<>();
    /** owners named by ctorRetargets (fast pre-scan before the full-key lookup) */
    final Set<String> ctorRetargetOwners = new HashSet<>();
    /** method-body drops for whole methods whose 1.20.1 semantics are already covered
     *  elsewhere on 21.1 (or are documented no-ops): owner|desc|name -> typed return */
    final Set<String> methodDrops = new HashSet<>();
    int methodDropsApplied;
    /** interface call-site repairs (Methodref -> InterfaceMethodref kind fixes +
     *  invokevirtual-on-interface -> invokeinterface). See interfaces1211. */
    int interfaceRepairsApplied;
    /** class -> {oldSuper, newSuper}: superclass swaps, guarded on the current super */
    final Map<String, String[]> superSwaps = new HashMap<>();
    int superswapsApplied;
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
    /** shared shims (rules/shims-shared.txt): simple name -> owner mod id. A shared
     *  shim is injected ONLY into its owner's jar at the FIXED package
     *  com/modbridge/shim/shared/ (never per-mod): rules referencing it resolve
     *  identically in every translated jar, so shim-typed CROSS-MOD API signatures
     *  (measured: moonlight PlatHelper.newMusicDisc returning the RecordItem shim,
     *  called from supplementaries) keep matching - per-mod relocation diverged the
     *  two descriptors and the call NSME'd at the item RegisterEvent. The owner's
     *  module exports the package (FML exports every package of a mod module) and
     *  consumer mods already read it (cross-mod API). */
    final Map<String, String> sharedShims = new HashMap<>();

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
        addUnwrapRules(readLinesOptional(rulesDir.resolve("methodunwraps.txt")));
        addWrapRules(readLinesOptional(rulesDir.resolve("methodwraps.txt")));
        addCtorRetargetRules(readLines(rulesDir.resolve("ctorretargets.txt")));
        addMethodDrops(readLines(rulesDir.resolve("methoddrops.txt")));
        addSuperSwapRules(readLinesOptional(rulesDir.resolve("superswaps.txt")));
        addClasslist(readLines(rulesDir.resolve("classlist-1211.txt")));
        addInterfaceInventory(readLines(rulesDir.resolve("interfaces-1211.txt")));
        addMojMemberRenames(readLines(rulesDir.resolve("mojmemberrenames.txt")));
        addMemberTargets(readLines(rulesDir.resolve("membertargets-1211.txt")));
        addSharedShims(readLinesOptional(rulesDir.resolve("shims-shared.txt")));
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
        addUnwrapRules(resourceLinesOptional("/rules/methodunwraps.txt"));
        addWrapRules(resourceLinesOptional("/rules/methodwraps.txt"));
        addCtorRetargetRules(resourceLines("/rules/ctorretargets.txt"));
        addMethodDrops(resourceLinesOptional("/rules/methoddrops.txt"));
        addSuperSwapRules(resourceLinesOptional("/rules/superswaps.txt"));
        addClasslist(resourceLinesOptional("/rules/classlist-1211.txt"));
        addInterfaceInventory(resourceLinesOptional("/rules/interfaces-1211.txt"));
        addMojMemberRenames(resourceLines("/rules/mojmemberrenames.txt"));
        addMemberTargets(resourceLinesOptional("/rules/membertargets-1211.txt"));
        addSharedShims(resourceLinesOptional("/rules/shims-shared.txt"));
        loadShimsFromClasspath("/rules/shims");
        printRuleStats();
    }

    public static void translate(Path in, Path out) throws Exception {
        Rewriter r = new Rewriter();
        r.loadRulesFromClasspath();
        r.run(in, out);
    }

    private void printRuleStats() {
        System.out.printf("rules: %d package, %d type, %d member, %d desc, %d srg, %d retarget, %d unwrap, %d wrap, %d ctor-retarget, %d method-drops, %d superswaps, %d classlist, %d interfaces, %d mojrenames, %d member-targets classes, %d shims, %d shared%n",
                packageRenames.size(), typeRenames.size(), memberRenames.size(),
                memberDescRenames.size(), srgMemberRenames.size(), memberRetargets.size(),
                holderUnwraps.size(), bareWraps.size(), ctorRetargets.size(), methodDrops.size(), superSwaps.size(), classlist.size(),
                interfaces1211.size(),
                mojMemberRenames.size(), memberTargets.size(), shims.size(), sharedShims.size());
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

    /** sharedName=ownerModId (rules/shims-shared.txt); validated against the shim
     *  manifest after it loads */
    void addSharedShims(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("bad shims-shared line: " + line);
            sharedShims.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    /** SUPER-SWAP: replaces a class's superclass only when it still equals the
     *  rule's old super (e.g. a subclass of a platform class that became FINAL on
     *  21.1 - the data-driven enchantment rework - retargeted to an inert carrier;
     *  nothing else can restore the IS-A chain). */
    String applySuperSwap(String className, String superName) {
        String[] swap = superSwaps.get(className);
        if (swap == null || !swap[0].equals(superName)) return superName;
        superswapsApplied++;
        return resolveShimTarget(swap[1]);
    }

    /** INTERFACE-STRIP (superswaps.txt, rule key "interface:<name> -> REMOVE"): drops
     *  a renamed-away interface from a class's implements list. Use case: a 1.20.1
     *  INTERFACE whose typerename target is a 21.1 CLASS (BlockSource -> the dispense
     *  context record) - the rename is right for behavior PARAMS but breaks any class
     *  still IMPLEMENTing it (ICCE "can not implement ... not an interface"). */
    boolean stripInterface(String className, String itf) {
        String[] swap = superSwaps.get(className);
        if (swap == null || !swap[0].equals("interface:" + itf)) return false;
        superswapsApplied++;
        return true;
    }

    /** resolves the "@SHIM@/<SimpleName>" placeholder used in typerenames targets */
    String resolveShimTarget(String mapped) {
        if (mapped != null && mapped.startsWith("@SHIM-SHARED@/")) {
            String simple = mapped.substring("@SHIM-SHARED@/".length());
            if (!shimSimpleNames.containsKey(simple)) {
                throw new IllegalStateException("@SHIM-SHARED@ target " + simple + " is not in the shim manifest");
            }
            return "com/modbridge/shim/shared/" + simple;
        }
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
        // the owner id this jar's shim package was pinned to (initShimRoot)
        String currentModId = shimRoot.substring("com/modbridge/shim/".length());
        for (Map.Entry<String, byte[]> e : shims.entrySet()) {
            String original = e.getKey();
            String simple = original.substring(original.lastIndexOf('/') + 1);
            String owner = sharedShims.get(simple);
            String relocatedName;
            if (owner != null) {
                // shared shim: injected ONLY into its owner's jar, at the FIXED
                // shared package every jar's rules resolve to
                if (!owner.equals(currentModId)) continue;
                relocatedName = "com/modbridge/shim/shared/" + simple;
            } else {
                relocatedName = shimRoot + "/" + simple;
            }
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
            String otherOwner = sharedShims.get(otherSimple);
            names.put(other, otherOwner != null
                    ? "com/modbridge/shim/shared/" + otherSimple
                    : shimRoot + "/" + otherSimple);
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
                    // array-form descs ([Lcom/modbridge/shim/EnumShim; - the CHECKCAST
                    // in a synthesized enum values()) must go through descriptor text
                    // replacement, not the plain-name map
                    ti.desc = ti.desc.startsWith("[")
                            ? relocateDescText(ti.desc, names)
                            : names.getOrDefault(ti.desc, ti.desc);
                } else if (n instanceof LdcInsnNode l && l.cst instanceof Type ty) {
                    // class literals: javac's synthesized enum valueOf() LDCs
                    // NetworkDirection.class - base-name constant otherwise survives
                    l.cst = Type.getType(relocateDescText(ty.getDescriptor(), names));
                } else if (n instanceof MultiANewArrayInsnNode ma) {
                    ma.desc = relocateDescText(ma.desc, names);
                } else if (n instanceof MethodInsnNode mi) {
                    // array-form owners exist: javac compiles enum values()'s
                    // \$VALUES.clone() as invokevirtual with owner [Lcom/.../Enum;
                    mi.owner = mi.owner.startsWith("[")
                            ? relocateDescText(mi.owner, names)
                            : names.getOrDefault(mi.owner, mi.owner);
                    mi.desc = relocateDescText(mi.desc, names);
                } else if (n instanceof FieldInsnNode fi) {
                    fi.owner = fi.owner.startsWith("[")
                            ? relocateDescText(fi.owner, names)
                            : names.getOrDefault(fi.owner, fi.owner);
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
                            } else if (id.bsmArgs[i] instanceof Handle h) {
                                // LambdaMetafactory's implMethod arg: a shim's own
                                // synthetic lambda$ methods live in the ORIGINAL class
                                // constant (live-boot CNFE com.modbridge.shim.
                                // DataResultCompat from DataResultCompat's lambdas)
                                id.bsmArgs[i] = new Handle(h.getTag(),
                                        names.getOrDefault(h.getOwner(), h.getOwner()),
                                        h.getName(), relocateDescText(h.getDesc(), names),
                                        h.isInterface());
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

    /** relocates InnerClasses outer/inner names. The MAPPED name must stay SLASHED:
     *  ClassWriter emits the outer_name as a raw Class constant without converting
     *  dots, and a dotted constant = ClassFormatError "Illegal class name" at define
     *  (live-boot: the first NAMED nested shim class, EntityItemPickupEvent\$Result;
     *  anonymous nesteds have null outer names and never hit this). */
    private static String relocateSimpleAware(String dotted, Map<String, String> names) {
        String slashed = dotted.replace('.', '/');
        String replaced = names.get(slashed);
        return replaced != null ? replaced : dotted;
    }

    private void addClasslist(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) classlist.add(t);
        }
    }

    /** internal names of the 21.1 platform's ACC_INTERFACE classes
     *  (rules/interfaces-1211.txt, scripts/gen-interfaces-1211.sh) */
    void addInterfaceInventory(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) interfaces1211.add(t);
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

    /** owner|desc|name -> bare target owner (rules/methodunwraps.txt). Keys are
     *  FINAL-space (the pass runs after remapClass) and the desc is the ALREADY
     *  REWRITTEN Holder-returning desc. Optional file - absent = pass disabled. */
    void addUnwrapRules(List<String> lines) throws IOException {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] sides = line.split("->");
            if (sides.length != 2) throw new IOException("bad methodunwrap line: " + line);
            String[] key = sides[0].trim().split("\\|");
            if (key.length != 3 || sides[1].trim().isEmpty()) {
                throw new IOException("bad methodunwrap line: " + line);
            }
            holderUnwraps.put(key[0] + "|" + key[1] + "|" + key[2], sides[1].trim());
        }
    }

    /** bareType|holderType -> registryOwner|registryName|registryDesc (rules/
     *  methodwraps.txt). Keys are FINAL-space. Optional file - absent = pass disabled. */
    void addWrapRules(List<String> lines) throws IOException {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] sides = line.split("->");
            if (sides.length != 2) throw new IOException("bad methodwrap line: " + line);
            String[] key = sides[0].trim().split("\\|");
            String[] val = sides[1].trim().split("\\|");
            if (key.length != 2 || val.length != 3) throw new IOException("bad methodwrap line: " + line);
            bareWraps.put(key[0], new String[]{key[1], val[0], val[1], val[2]});
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

    /** class|oldSuper -> newSuper; applied only when the remapped superName still
     *  equals oldSuper (never guessed). Optional file - absent = no swaps. */
    void addSuperSwapRules(List<String> lines) throws IOException {
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            line = t;
            String[] sides = line.split("->");
            if (sides.length != 2) throw new IOException("bad superswap line: " + line);
            String[] key = sides[0].trim().split("\\|");
            if (key.length != 2) throw new IOException("bad superswap line: " + line);
            superSwaps.put(key[0].trim(), new String[]{key[1].trim(), sides[1].trim()});
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

    /** CLI variant that tolerates a missing optional rule file (empty list). */
    private List<String> readLinesOptional(java.nio.file.Path file) throws IOException {
        if (!java.nio.file.Files.exists(file)) return new java.util.ArrayList<>();
        return java.nio.file.Files.readAllLines(file).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
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
        System.out.printf("translated %d classes; fml chains spliced=%d, ResourceLocation ctors rewritten=%d, ctor retargets=%d, method drops=%d, interface repairs=%d, holder unwraps=%d, bare wraps=%d, member renames=%d, super swaps=%d%n",
                classesTransformed, fmlChainsSpliced, resourceLocationCtors, ctorRetargetsApplied,
                methodDropsApplied, interfaceRepairsApplied, holderUnwrapsApplied, bareWrapsApplied, memberRenamesApplied, superswapsApplied);
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
        // must run AFTER remapClass: call-site owners are final-space here, matching
        // the interfaces-1211.txt inventory
        repairInterfaceCallSites(cn);
        // must run AFTER remapClass too: unwrap keys are final-space and the rewritten
        // (Holder-returning) descs landed in applyMemberRenames
        repairHolderUnwraps(cn);
        // inverse repair, same ordering constraints (final-space checkcast + rewritten
        // Holder-taking target descs)
        repairBareWraps(cn);
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
                        // frames inside the arg region name the uninitialized pair in
                        // NEW+DUP slots; the rewrite leaves ONE factory reference -
                        // collapse them exactly like the ctor-retarget pass does. MUST
                        // run BEFORE removing the window nodes: removed InsnList nodes
                        // have their links cleared, and walking from a removed node
                        // silently iterates nothing (first attempt did exactly that).
                        // (live-boot: StackMapTable "bad offset for Uninitialized" in
                        // moonlight BlocksColorInternal$ColoredSet.makeHolderSet, whose
                        // ResourceLocation args contain a branch)
                        fixArgRegionFrames(mn, dup, ctor, null);
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
     * entry: the Forge 1.20.1 ModConfig ctor -> the per-mod ModConfigBridge shim,
     * which calls ConfigTracker.registerConfig AND eagerly loads COMMON configs so
     * construction-time reads return file/default values — the 1.20.1-visible timing
     * moonlight's eager loadFromFile used to provide).
     *
     * Same NEW+DUP forward-scan as the ResourceLocation rewrite; the receiver field is
     * pushed under the args and INVOKESPECIAL becomes INVOKEVIRTUAL — the returned
     * instance replaces the uninitialized ref 1:1 on the operand stack, so maxStack and
     * every frame AFTER the window stay valid. Frames INSIDE the argument region (a
     * branch among the ctor args) still name the uninitialized ref in two slots
     * (NEW+DUP): the first becomes the receiver type, the DUP copy is removed (see
     * fixArgRegionFrames). A rule whose newOwner is "@SHIM@/&lt;SimpleName&gt;" resolves
     * to the per-mod shim package (com/modbridge/shim/&lt;modid&gt;/...) at rewrite
     * time; the shim manifest must list the class or translation fails fast.
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
                String owner = resolveShimTarget(rule[0]);
                AbstractInsnNode after = ctor.getNext();
                FieldInsnNode receiver = new FieldInsnNode(Opcodes.GETSTATIC,
                        owner, rule[1], "L" + owner + ";");
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        owner, rule[2], rule[3], false);
                fixArgRegionFrames(mn, dup, ctor, "L" + owner + ";");
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
        // frame stack slots carry the INTERNAL name (no L...; delimiters) - strip a
        // caller-supplied descriptor form defensively (a "L...;" slot would crash the
        // class with ClassFormatError "Illegal class name", live-boot: the
        // ResourceLocation rewrite in makeHolderSet)
        if (receiverTypeDesc != null && receiverTypeDesc.startsWith("L")
                && receiverTypeDesc.endsWith(";")) {
            receiverTypeDesc = receiverTypeDesc.substring(1, receiverTypeDesc.length() - 1);
        }
        for (AbstractInsnNode i = from.getNext(); i != to; ) {
            AbstractInsnNode next = i.getNext();
            if (i instanceof FrameNode fr && fr.stack != null) {
                List<Object> stack = new ArrayList<>(fr.stack);
                boolean replaced = false;
                for (int k = 0; k < stack.size(); k++) {
                    if (stack.get(k) instanceof LabelNode) {
                        if (receiverTypeDesc != null && !replaced) {
                            // receiver-style rewrite (ctor-retarget): the receiver
                            // GETSTATIC sits exactly where the first uninit slot was
                            stack.set(k, receiverTypeDesc);
                            replaced = true;
                        } else {
                            // factory-style rewrite (ResourceLocation): the result only
                            // exists at the ctor position - mid-arg merges carry NO
                            // placeholder, so every uninit entry drops
                            // (live-boot VerifyError "Inconsistent stackmap frames",
                            // replacing-instead-of-dropping left a phantom slot)
                            stack.remove(k);
                            k--;
                        }
                    }
                }
                if (replaced || !stack.equals(fr.stack)) {
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

    /**
     * Interface call-site repair (rules/interfaces-1211.txt): 1.20.1 bytecode that
     * called a type as a CLASS carries Methodref constants; when that type is an
     * ACC_INTERFACE on 21.1 the JVM rejects every such site at execution:
     * invokestatic -> "must be InterfaceMethodref constant" (the DFU 8 DataResult
     * wall — moonlight databuddy TomlConfigOps during the eager config load),
     * invokevirtual -> "Found interface, but class was expected". The 1.20.1-legal
     * shapes and their proven repairs (JDK 21 matrix experiment, trial 002 round 2):
     *
     *   INVOKESTATIC   Methodref on an interface static -> keep opcode, itf = true
     *   INVOKEVIRTUAL  Methodref on an interface member -> INVOKEINTERFACE, itf = true
     *   INVOKEVIRTUAL  Methodref for a java/lang/Object member named through the
     *                  interface owner (javac-on-a-class emits the receiver type as
     *                  owner) -> keep opcode, owner becomes java/lang/Object — exactly
     *                  what javac emits for an interface-typed receiver; virtual
     *                  dispatch reaches the object's own override (or the interface
     *                  default never being involved).
     *
     * Sites already carrying itf = true (types that were interfaces on 1.20.1 too —
     * DynamicOps, IEventBus, ...) are left untouched, so the repair only fires on the
     * actually-drifted constants. Field refs (getstatic/putstatic) carry no itf bit
     * and need no repair. Runs AFTER remapClass, so owners are final-space and match
     * the inventory; a member that genuinely drifted away is a NoSuchMethodError
     * either way (semantic shim territory, not a kind repair).
     */
    void repairInterfaceCallSites(ClassNode cn) {
        if (interfaces1211.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode n : mn.instructions) {
                if (!(n instanceof MethodInsnNode m) || m.itf) continue;
                if (!interfaces1211.contains(m.owner)) continue;
                int op = m.getOpcode();
                if (op == Opcodes.INVOKESTATIC) {
                    m.itf = true;
                    interfaceRepairsApplied++;
                } else if (op == Opcodes.INVOKEVIRTUAL) {
                    if (OBJECT_MEMBER_NAMES.contains(m.name)) {
                        m.owner = "java/lang/Object";
                    } else {
                        m.setOpcode(Opcodes.INVOKEINTERFACE);
                        m.itf = true;
                    }
                    interfaceRepairsApplied++;
                }
                // INVOKESPECIAL on an interface (private interface methods, super-
                // defaults) has no 1.20.1-class-shaped producer in mods calling into
                // platform code; INVOKEINTERFACE needs no repair. Never guessed.
            }
        }
    }

    /** names of the java/lang/Object members javac may name through a receiver's
     *  static type; these keep INVOKEVIRTUAL and move to the Object owner */
    private static final Set<String> OBJECT_MEMBER_NAMES = Set.of(
            "equals", "hashCode", "toString", "getClass", "clone", "finalize",
            "notify", "notifyAll", "wait");

    /**
     * Holder-unwrap call-site repair (rules/methodunwraps.txt): a 1.20.1 getter whose
     * 21.1 return became a Holder (measured: MobEffectInstance.getEffect, the phase
     * 5.11 effects cascade) flows at SOME call sites into consumers that still expect
     * the BARE value — a receiver call into a bare-type member, a bare-type
     * instanceof/checkcast, an areturn of a bare-typed method, a bare-typed putfield,
     * or the same via a local. Without repair such classes die at LINK time with
     * VerifyError "Bad type on operand stack: Holder is not assignable to MobEffect"
     * (measured: supplementaries BambooSpikesBlockTile.interactWithEntity during the
     * entity registration event). Sites whose consumer was ALREADY migrated to the
     * Holder shape (the 5.11 desc rewrites: hasEffect/getEffect-arg sites, the
     * Holder-taking MobEffectInstance ctors) must NOT be unwrapped — the two consumer
     * families coexist, so the repair is purely consumer-local, pattern by pattern:
     *
     *   next insn consumes the value as the bare type (see consumesAsBare) -> unwrap
     *   next insn = ASTORE n and a later ALOAD n feeds a pattern above -> unwrap
     *                at the load site (linear pending-slot tracking; any other
     *                store to the slot clears it)
     *   otherwise: BOUNDED forward stack scan (measured third shape: the receiver
     *                sits on the stack while ARGUMENTS are pushed before the consume
     *                — BambooSpikes applyInstantenousEffect). Walks straight-line
     *                push-only arg code tracking the slots above the receiver; the
     *                first insn popping at/below the receiver is the consumer — unwrap
     *                iff it is a virtual/interface/special call with owner == bare
     *                type. Anything not understood (branches, DUPs, exotic ops) aborts
     *                the scan and leaves the site untouched (ladder-measurable).
     *
     * The inserted call is INVOKEINTERFACE net/minecraft/core/Holder.value
     * ()LbareType; — stack-neutral (one ref in, one ref out: maxStack and every
     * frame stay valid; a frame at the consumer still correctly names the Holder the
     * ORIGINAL bytecode produced at that point). Runs AFTER remapClass: keys are
     * final-space and the getEffect descs have already been rewritten to Holder by
     * the memberdescrenames pass.
     */
    void repairHolderUnwraps(ClassNode cn) {
        if (holderUnwraps.isEmpty()) return;
        // @SHIM@-owned entries resolve to THIS jar's per-mod path (the pass runs
        // after remapClass + shim resolution, so the field owners are final-space)
        Map<String, String> holderFieldUnwraps = new HashMap<>(holderUnwraps);
        for (Map.Entry<String, String> e : holderUnwraps.entrySet()) {
            String owner = e.getKey().split("\\|", 2)[0];
            if (owner.startsWith("@SHIM@/")) {
                String resolved = resolveShimTarget(owner)
                        + e.getKey().substring(owner.length());
                holderFieldUnwraps.putIfAbsent(resolved, e.getValue());
            }
        }
        for (MethodNode mn : cn.methods) {
            Map<Integer, String> pending = new HashMap<>();
            // the ASTORE that captures a producer's result must not clear the pending
            // slot it just armed (the loop reaches it right after the producer)
            boolean skipNextStore = false;
            for (AbstractInsnNode n : mn.instructions) {
                if (n instanceof MethodInsnNode m) {
                    String target = holderUnwraps.get(m.owner + "|" + m.desc + "|" + m.name);
                    if (target == null || m.getOpcode() == Opcodes.INVOKESTATIC) continue;
                    AbstractInsnNode next = m.getNext();
                    if (next == null) continue;
                    if (consumesAsBare(next, mn, target)) {
                        insertUnwrap(mn, m, target);
                        continue;
                    }
                    if (next instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                        pending.put(v.var, target);
                        skipNextStore = true;
                        continue;
                    }
                    unwrapAfterArgPushes(mn, m, next, target);
                } else if (n instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC) {
                    // FIELD producers (phase 5.12): memberretargets swapped the desc to
                    // Holder (Potions.*, Attributes.*); where the value flows into a
                    // LOCAL, the ORIGINAL frames type that local as the BARE type - a
                    // bare-consumer or a store must see the bare value (measured
                    // VerifyError: TippedBambooSpikesRecipe.assemble local[3] Holder vs
                    // Potion). Holder-arg consumers stay untouched (Holder-consistent).
                    String target = holderFieldUnwraps.get(fi.owner + "|" + fi.desc + "|" + fi.name);
                    if (target == null) continue;
                    AbstractInsnNode next = fi.getNext();
                    if (next == null) continue;
                    if (consumesAsBare(next, mn, target)) {
                        insertUnwrap(mn, fi, target);
                    } else if (next instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                        insertUnwrap(mn, fi, target);
                    } else if (next instanceof JumpInsnNode j
                            && (j.getOpcode() == Opcodes.IF_ACMPEQ
                            || j.getOpcode() == Opcodes.IF_ACMPNE)) {
                        // the 1.20.1 code compared the field value against another
                        // value by reference - bare-vs-bare is the original semantic
                        // (measured: TippedBambooSpikesRecipe `potion != Potions.EMPTY`)
                        insertUnwrap(mn, fi, target);
                    } else {
                        unwrapAfterArgPushes(mn, fi, next, target);
                    }
                } else if (n instanceof VarInsnNode v) {
                    if (v.getOpcode() == Opcodes.ASTORE) {
                        if (skipNextStore) {
                            skipNextStore = false;
                        } else {
                            pending.remove(v.var);
                        }
                    } else if (v.getOpcode() == Opcodes.ALOAD) {
                        String target = pending.get(v.var);
                        if (target != null && v.getNext() != null && consumesAsBare(v.getNext(), mn, target)) {
                            insertUnwrap(mn, v, target);
                        }
                    }
                }
            }
        }
    }

    /** bounded forward scan from a producer's result sitting on the stack: walk
     *  straight-line code tracking the slots ABOVE the receiver; the first insn that
     *  pops at/below the receiver is the consumer. Understood: constant/local loads,
     *  in-place numeric conversions, void-arg-ish invokes, field loads. Anything
     *  else (branches, DUPs, stores) aborts with no rewrite. */
    private void unwrapAfterArgPushes(MethodNode mn, AbstractInsnNode producer,
                                      AbstractInsnNode from, String target) {
        int covered = 0; // stack slots sitting above the receiver
        for (AbstractInsnNode n = from; n != null; n = n.getNext()) {
            if (n instanceof FrameNode || n instanceof LabelNode || n instanceof LineNumberNode) {
                if (n instanceof LineNumberNode) continue;
                return; // branch target boundary: local reasoning is void
            }
            int[] pp = stackEffect(n);
            if (pp == null) return; // unknown shape: abort, never guess
            int pops = pp[0];
            int pushes = pp[1];
            if (pops > covered) {
                // this insn reaches the receiver (or deeper): it is the consumer
                if (n instanceof MethodInsnNode cons) {
                    int op = cons.getOpcode();
                    if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE
                            || op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC) {
                        // receiver consumption OR a landing argument slot the callee
                        // declares as the BARE type (measured: the retargeted
                        // Potions.POISON field feeding supp's own
                        // BambooSpikesTippedItem.makeSpikeItem(Potion) - a mod-owned
                        // static whose desc was never Holder-migrated)
                        int idx = landingArgIndex(cons, covered);
                        boolean receiver = idx < 0 && target.equals(cons.owner);
                        boolean bareArg = idx >= 0
                                && Type.getArgumentTypes(cons.desc)[idx].getDescriptor()
                                        .equals("L" + target + ";");
                        if (receiver || bareArg) {
                            insertUnwrap(mn, producer, target);
                        }
                    }
                }
                return;
            }
            covered += pushes - pops;
        }
    }

    /** [pops, pushes] for the arg-push shapes this walker understands; null = abort */
    private static int[] stackEffect(AbstractInsnNode n) {
        if (n instanceof VarInsnNode v) {
            return switch (v.getOpcode()) {
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD -> new int[]{0, 1};
                case Opcodes.LLOAD, Opcodes.DLOAD -> new int[]{0, 2};
                case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE -> new int[]{1, 0};
                case Opcodes.LSTORE, Opcodes.DSTORE -> new int[]{2, 0};
                case Opcodes.IINC -> new int[]{0, 0}; // local-only
                default -> null;
            };
        }
        if (n instanceof FieldInsnNode fi) {
            int size = org.objectweb.asm.Type.getType(fi.desc).getSize();
            return switch (fi.getOpcode()) {
                case Opcodes.GETSTATIC -> new int[]{0, size};
                case Opcodes.GETFIELD -> new int[]{1, size};
                case Opcodes.PUTSTATIC -> new int[]{size, 0};
                case Opcodes.PUTFIELD -> new int[]{1 + size, 0};
                default -> null;
            };
        }
        if (n instanceof MethodInsnNode mi) {
            // getArgumentsAndReturnSizes ALREADY counts the implicit receiver slot:
            // (Largs...)V on a virtual call returns argsSize+1 in the high bits
            int sizes = org.objectweb.asm.Type.getArgumentsAndReturnSizes(mi.desc);
            int pushes = sizes & 3;
            int pops = (sizes >> 2) - (mi.getOpcode() == Opcodes.INVOKESTATIC ? 1 : 0);
            return new int[]{pops, pushes};
        }
        if (n instanceof InvokeDynamicInsnNode id) {
            int sizes = org.objectweb.asm.Type.getArgumentsAndReturnSizes(id.desc);
            return new int[]{sizes >> 2, sizes & 3};
        }
        if (n instanceof LdcInsnNode l) {
            if (l.cst instanceof Long || l.cst instanceof Double) return new int[]{0, 2};
            return new int[]{0, 1};
        }
        if (n instanceof TypeInsnNode t) {
            return switch (t.getOpcode()) {
                case Opcodes.CHECKCAST -> new int[]{0, 0};
                case Opcodes.INSTANCEOF -> new int[]{1, 1};
                case Opcodes.NEW -> new int[]{0, 1};
                default -> null;
            };
        }
        if (n instanceof IntInsnNode ii) {
            // BIPUSH/SIPUSH int constants; NEWARRAY pops the count, pushes the array
            return switch (ii.getOpcode()) {
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> new int[]{0, 1};
                case Opcodes.NEWARRAY -> new int[]{1, 1};
                default -> null;
            };
        }
        if (n instanceof JumpInsnNode j) {
            // conditional jumps fall through (the walk follows the fall-through path);
            // GOTO/JSR/switch hand control elsewhere - abort
            return switch (j.getOpcode()) {
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                     Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL -> new int[]{1, 0};
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> new int[]{2, 0};
                default -> null;
            };
        }
        if (n instanceof InsnNode i) {
            return switch (i.getOpcode()) {
                case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
                     Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
                     Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> new int[]{0, 1};
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> new int[]{0, 2};
                // slot-neutral conversions
                case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.I2F, Opcodes.L2I, Opcodes.F2I,
                     Opcodes.D2I, Opcodes.L2F, Opcodes.D2F, Opcodes.D2L, Opcodes.L2D -> new int[]{0, 0};
                // widening conversions (one extra slot)
                case Opcodes.I2L, Opcodes.I2D, Opcodes.F2D, Opcodes.F2L -> new int[]{0, 1};
                // DUP (the NEW+DUP ctor idiom among args duplicates the uninit ref)
                case Opcodes.DUP -> new int[]{0, 1};
                // int/float arithmetic: pops 2, pushes 1
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                     Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR,
                     Opcodes.IUSHR, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV,
                     Opcodes.FREM, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                     Opcodes.DCMPL, Opcodes.DCMPG -> new int[]{2, 1};
                // long/double arithmetic: pops 4, pushes 2
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                     Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR,
                     Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> new int[]{4, 2};
                // negation: in-place
                case Opcodes.INEG, Opcodes.FNEG, Opcodes.LNEG, Opcodes.DNEG -> new int[]{0, 0};
                default -> null; // DUP2/X-forms, SWAP, returns, other control flow: abort
            };
        }
        return null;
    }

    /** does this instruction consume the tos value as the BARE type (the unwrap
     *  patterns above)? Arg-position consumption is deliberately not covered: those
     *  consumers are already migrated to the Holder shape by the desc rewrites. */
    private static boolean consumesAsBare(AbstractInsnNode n, MethodNode mn, String target) {
        if (n instanceof MethodInsnNode m) {
            int op = m.getOpcode();
            return (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE
                    || op == Opcodes.INVOKESPECIAL) && target.equals(m.owner);
        }
        if (n instanceof TypeInsnNode t) {
            int op = t.getOpcode();
            return (op == Opcodes.INSTANCEOF || op == Opcodes.CHECKCAST) && target.equals(t.desc);
        }
        if (n instanceof InsnNode i && i.getOpcode() == Opcodes.ARETURN) {
            return Type.getReturnType(mn.desc).getDescriptor().equals("L" + target + ";");
        }
        if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) {
            return f.desc.equals("L" + target + ";");
        }
        return false;
    }

    /** index of the desc argument the value lands in when `coveredSlots` slots sit
     *  above it on the stack at the invoke (walking the arg list from the top, so
     *  long/double args count double); -1 = the value is the RECEIVER. */
    private static int landingArgIndex(MethodInsnNode m, int coveredSlots) {
        Type[] args = Type.getArgumentTypes(m.desc);
        int fromTop = coveredSlots;
        for (int i = args.length - 1; i >= 0; i--) {
            int size = args[i].getSize();
            if (fromTop < size) return i;
            fromTop -= size;
        }
        return -1;
    }

    /**
     * The INVERSE of repairHolderUnwraps (rules/methodwraps.txt): the translated
     * wrapper-get family pushes the BARE registry VALUE (EntryWrapperCompat.get ->
     * checkcast MobEffect) into call sites whose target methods the 5.11 desc
     * rewrites migrated to Holder<MobEffect> args — vanilla's MobEffectInstance
     * ctor then calls Holder.value() on the bare instance at RUNTIME
     * ("IncompatibleClassChangeError: FlammableEffect does not implement the
     * requested interface net.minecraft.core.Holder", measured inside the fluid
     * RegisterEvent dispatch). Where a bare-typed value lands in a Holder-typed
     * argument slot, insert the 21.1-native bridge:
     *
     *   getstatic BuiltInRegistries.MOB_EFFECT; SWAP;
     *   invokeinterface Registry.wrapAsHolder(Object)Holder
     *
     * (stack: one ref in, one ref out; maxStack bumped once for the transient
     * registry pair). Consumer-local like the unwrap pass: the consumer must be a
     * virtual/interface/special call whose landing argument slot (computed from the
     * slots stacked above the value, long/double-aware) is Holder-typed; producers
     * whose consumer takes the BARE type (ml Utils.getID duck-typing) or is a static
     * call (supp QuarkCompat Set.of) are left untouched. Branch boundaries abort the
     * scan — a rewrite never crosses a frame, so the original StackMapTable (which
     * names the value MobEffect at such boundaries) stays valid. Runs AFTER
     * remapClass: checkcast descs and rewritten target descs are final-space.
     */
    void repairBareWraps(ClassNode cn) {
        if (bareWraps.isEmpty()) return;
        for (MethodNode mn : cn.methods) {
            Map<Integer, String[]> pending = new HashMap<>();
            boolean skipNextStore = false;
            for (AbstractInsnNode n : mn.instructions) {
                if (n instanceof TypeInsnNode t && t.getOpcode() == Opcodes.CHECKCAST) {
                    String[] rule = bareWraps.get(t.desc);
                    if (rule == null) continue;
                    AbstractInsnNode next = t.getNext();
                    if (next == null) continue;
                    if (consumesHolderArg(next, rule, 0)) {
                        insertWrap(mn, t, rule);
                        continue;
                    }
                    if (next instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                        pending.put(v.var, rule);
                        skipNextStore = true;
                        continue;
                    }
                    wrapAfterArgPushes(mn, t, next, rule, pending);
                } else if (n instanceof VarInsnNode v) {
                    if (v.getOpcode() == Opcodes.ASTORE) {
                        if (skipNextStore) {
                            skipNextStore = false;
                        } else {
                            pending.remove(v.var);
                        }
                    } else if (v.getOpcode() == Opcodes.ALOAD) {
                        String[] rule = pending.get(v.var);
                        if (rule != null && v.getNext() != null) {
                            if (consumesHolderArg(v.getNext(), rule, 0)) {
                                insertWrap(mn, v, rule);
                            } else {
                                wrapAfterArgPushes(mn, v, v.getNext(), rule, pending);
                            }
                        }
                    }
                }
            }
        }
    }

    /** immediate consumer test: a virtual-family invoke whose landing argument slot
     *  is Holder-typed */
    private static boolean consumesHolderArg(AbstractInsnNode n, String[] rule, int covered) {
        if (!(n instanceof MethodInsnNode m)) return false;
        int op = m.getOpcode();
        if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE
                && op != Opcodes.INVOKESPECIAL) return false;
        int idx = landingArgIndex(m, covered);
        return idx >= 0 && args(m)[idx].getDescriptor().equals("L" + rule[0] + ";");
    }

    private static Type[] args(MethodInsnNode m) {
        return Type.getArgumentTypes(m.desc);
    }

    /** bounded forward scan (same shapes as unwrapAfterArgPushes) for the wrap side.
     *  pendingLocals: slots holding a pending bare value — an ALOAD of such a slot
     *  pushes a NEW candidate (measured: BombEntity stores the effect, null-checks,
     *  reloads it as a ctor arg): recurse for the reloaded value, and keep the outer
     *  walk alive (the outer value sits below). */
    private void wrapAfterArgPushes(MethodNode mn, AbstractInsnNode producer,
                                    AbstractInsnNode from, String[] rule,
                                    Map<Integer, String[]> pendingLocals) {
        int covered = 0;
        for (AbstractInsnNode n = from; n != null; n = n.getNext()) {
            if (n instanceof FrameNode || n instanceof LabelNode) return;
            if (n instanceof LineNumberNode) continue;
            if (n instanceof VarInsnNode pv && pv.getOpcode() == Opcodes.ALOAD
                    && pendingLocals.containsKey(pv.var)) {
                // the reloaded candidate: recurse (its own consumer analysis), then
                // keep walking — for the OUTER value this load is just a push
                if (pv.getNext() != null) {
                    wrapAfterArgPushes(mn, pv, pv.getNext(), rule, Map.of());
                }
                covered += 1;
                continue;
            }
            int[] pp = stackEffect(n);
            if (pp == null) return;
            int pops = pp[0];
            int pushes = pp[1];
            if (pops > covered) {
                if (n instanceof MethodInsnNode cons
                        && (cons.getOpcode() == Opcodes.INVOKEVIRTUAL
                        || cons.getOpcode() == Opcodes.INVOKEINTERFACE
                        || cons.getOpcode() == Opcodes.INVOKESPECIAL)) {
                    int idx = landingArgIndex(cons, covered);
                    if (idx >= 0 && args(cons)[idx].getDescriptor().equals("L" + rule[0] + ";")) {
                        insertWrap(mn, producer, rule);
                    }
                }
                return;
            }
            covered += pushes - pops;
        }
    }

    /** inserts getstatic registry + SWAP + Registry.wrapAsHolder after the producer
     *  (one ref in, one ref out; the transient registry pair costs one maxStack slot) */
    private void insertWrap(MethodNode mn, AbstractInsnNode after, String[] rule) {
        mn.instructions.insert(after, new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Registry", "wrapAsHolder",
                "(Ljava/lang/Object;)Lnet/minecraft/core/Holder;", true));
        mn.instructions.insert(after, new InsnNode(Opcodes.SWAP));
        mn.instructions.insert(after, new FieldInsnNode(Opcodes.GETSTATIC,
                rule[1], rule[2], rule[3]));
        mn.maxStack++;
        bareWrapsApplied++;
    }

    /** inserts Holder.value() + a bare-type CHECKCAST right after the producer
     *  (stack-neutral, frames valid). The call desc must be the ERASED ()Object:
     *  Holder.value is a generic interface method whose only declared descriptor is
     *  the erased one - a specialized ()Ltarget; desc NSMEs at the call
     *  (live-boot: "NoSuchMethodError: MobEffect net.minecraft.core.Holder.value()").
     *  The checkcast re-establishes the bare type for the consumer. */
    private void insertUnwrap(MethodNode mn, AbstractInsnNode after, String target) {
        mn.instructions.insert(after, new TypeInsnNode(Opcodes.CHECKCAST, target));
        mn.instructions.insert(after, new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Holder", "value", "()Ljava/lang/Object;", true));
        holderUnwrapsApplied++;
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
        if (memberRenames.isEmpty() && memberRetargets.isEmpty()) return;
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
                    String[] retarget = retargetFor(m.owner, m.desc, m.name);
                    if (retarget != null) {
                        m.owner = resolveShimTarget(retarget[0]);
                        m.desc = retarget[1];
                        if ("static".equals(retarget[2])) {
                            // member removed on 21.1, bridged by a per-mod shim whose
                            // first parameter IS the receiver: same pops/pushes, frames
                            // untouched (DFU 6->8 DataResult.getOrThrow(Z,Consumer)/get)
                            m.setOpcode(Opcodes.INVOKESTATIC);
                            m.itf = false;
                        } else {
                            boolean itf = "interface".equals(retarget[2]);
                            m.setOpcode(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
                            m.itf = itf;
                        }
                        memberRenamesApplied++;
                    }
                    applyDescRewrite(cn, mn, m);
                } else if (n instanceof FieldInsnNode f) {
                    f.name = mapSrg(f.name);
                    String renamed = memberRenames.get(f.owner + "|" + f.desc + "|" + f.name);
                    if (renamed != null) { f.name = renamed; memberRenamesApplied++; }
                    // field retargets (phase 5.10 registry lane): owner+desc swap
                    // plus the NEW NAME from the third value slot (fields have no
                    // dispatch kind - getstatic/putstatic opcode kept, no itf bit).
                    // "*" owner fallback (phase 5.12): javac emits the SUBCLASS as
                    // owner for inherited statics (WrenchItem.BASE_ATTACK_DAMAGE_UUID)
                    String[] fRetarget = memberRetargets.get(f.owner + "|" + f.desc + "|" + f.name);
                    if (fRetarget == null) {
                        fRetarget = memberRetargets.get("*|" + f.desc + "|" + f.name);
                    }
                    if (fRetarget != null) {
                        f.owner = resolveShimTarget(fRetarget[0]);
                        f.desc = fRetarget[1];
                        f.name = fRetarget[2];
                        memberRenamesApplied++;
                    }
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
        if (newDesc == null) {
            // "*" owner fallback: (name, desc) matches regardless of the receiver's
            // static type - javac emits the RECEIVER type as owner, so an inherited
            // vanilla method called on a SUBCLASS/mixin-self receiver has its own
            // owner (the boot-injected ZombieHorse mixin handler's hasEffect call)
            newDesc = memberDescRenames.get("*|" + m.name + "|" + m.desc);
        }
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
        String beforeSuperSwap = cn.superName;
        cn.superName = applySuperSwap(cn.name, cn.superName);
        if (!cn.superName.equals(beforeSuperSwap)) {
            // the swap must carry the super PLUMBING: every invokespecial to the
            // old super (ctor <init> AND member super-calls like super.canEnchant)
            // is a separate constant from superName (live-boot VerifyErrors)
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode n : m.instructions) {
                    if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
                            && beforeSuperSwap.equals(mi.owner)) {
                        mi.owner = cn.superName;
                    }
                }
            }
        }
        replaceAll(cn.interfaces, this::remapType);
        // interface-strip AFTER the interfaces remap (the guard name is remapped-space:
        // a 1.20.1 interface renamed onto a 21.1 CLASS - see superswaps.txt)
        if (cn.interfaces != null) {
            cn.interfaces.removeIf(itf -> stripInterface(cn.name, itf));
        }
        if (cn.nestHostClass != null) cn.nestHostClass = remapType(cn.nestHostClass);
        replaceAll(cn.nestMembers, this::remapType);
        replaceAll(cn.permittedSubclasses, this::remapType);
        if (cn.outerClass != null) cn.outerClass = remapType(cn.outerClass);
        if (cn.signature != null) cn.signature = remapDesc(cn.signature);
        if (cn.innerClasses != null) {
            for (InnerClassNode ic : cn.innerClasses) {
                if (ic.name != null) {
                    ic.name = remapType(ic.name);
                    // the entry's simple name must follow the renamed internal name
                    // (reflection-level: e.g. DFU 6->8 DataResult$PartialResult ->
                    // $Error left a stale "PartialResult" inner name behind)
                    if (ic.innerName != null) ic.innerName = ic.name.substring(ic.name.lastIndexOf('$') + 1);
                }
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
                // the indy NAME is the implemented SAM method name: lambdas over
                // VANILLA functional interfaces (Shapes$DoubleLineConsumer.consume,
                // DiscreteVoxelShape$IntLineConsumer, ...) carry 1.20.1 SRG ids there
                // and the metafactory generates the lambda method under that name ->
                // AbstractMethodError at dispatch ("does not define or inherit an
                // implementation of the resolved method 'consume'"). Java-interface
                // lambdas (accept/apply/get) are unaffected (names never SRG).
                id.name = mapSrg(id.name);
                id.desc = remapDesc(id.desc);
                if (id.bsm != null) id.bsm = remapHandle(id.bsm);
                if (id.bsmArgs != null) {
                    for (int i = 0; i < id.bsmArgs.length; i++) {
                        if (id.bsmArgs[i] instanceof Type ty) {
                            id.bsmArgs[i] = Type.getType(remapDesc(ty.getDescriptor()));
                        } else if (id.bsmArgs[i] instanceof Handle h) {
                            id.bsmArgs[i] = retargetHandle(remapHandle(h));
                        }
                    }
                }
            } else if (n instanceof FrameNode fr) {
                if (fr.local != null) fr.local.replaceAll(o -> o instanceof String s ? remapType(s) : o);
                if (fr.stack != null) fr.stack.replaceAll(o -> o instanceof String s ? remapType(s) : o);
            }
        }
    }

    /** exact owner first, then the "*" owner fallback (phase 5.12): javac emits the
     *  RECEIVER'S STATIC TYPE as a call-site owner, so an inherited vanilla method
     *  called on a SUBCLASS receiver carries that subclass as the owner (measured:
     *  OverencumberedEffect.<init> calling the MobEffect addAttributeModifier it
     *  inherits - same reason applyDescRewrite grew its "*" fallback in 5.11). Rules
     *  opt in by naming "*" as the owner; write * rules ONLY for (name,desc) pairs
     *  no mod class plausibly owns. */
    private String[] retargetFor(String owner, String desc, String name) {
        String[] exact = memberRetargets.get(owner + "|" + desc + "|" + name);
        if (exact != null) return exact;
        return memberRetargets.get("*|" + desc + "|" + name);
    }

    /** ASM Handle fields are private, so remapping produces a new Handle. */
    private Handle remapHandle(Handle h) {
        return new Handle(h.getTag(), remapType(h.getOwner()), mapSrg(h.getName()), remapDesc(h.getDesc()), h.isInterface());
    }

    /** SRG member ids are globally unique, so a bare name table is safe to apply anywhere */
    /** METHOD-REF lambdas: javac emits the referenced method as the indy's
     *  implMethod Handle bsmArg — invisible to call-site scans, resolved by the
     *  metafactory at indy bootstrap (NSME "Registry.register(RL,Object)" from
     *  moonlight's Registry::register method ref). Apply the same memberretarget
     *  rules to the Handle: static-kind rules rewrite it to the shim static, whose
     *  receiver-prepended desc exactly matches the metafactory's captured-args +
     *  SAM-args adaptation (the receiver was captured by the indy). */
    Handle retargetHandle(Handle h) {
        if (h.getTag() != Opcodes.H_INVOKEVIRTUAL && h.getTag() != Opcodes.H_INVOKESTATIC
                && h.getTag() != Opcodes.H_INVOKEINTERFACE && h.getTag() != Opcodes.H_INVOKESPECIAL) {
            return h;
        }
        String[] retarget = retargetFor(h.getOwner(), h.getDesc(), h.getName());
        if (retarget != null) {
            return new Handle(Opcodes.H_INVOKESTATIC, resolveShimTarget(retarget[0]),
                    h.getName(), retarget[1], false);
        }
        // method-ref RENAMES ride memberrenames (same rules as call sites; the
        // metafactory resolves the Handle's Methodref, so a renamed member must
        // follow - FoodProperties$Builder::saturationMod -> saturationModifier)
        String renamed = memberRenames.get(h.getOwner() + "|" + h.getDesc() + "|" + h.getName());
        if (renamed != null) {
            return new Handle(h.getTag(), h.getOwner(), renamed, h.getDesc(), h.isInterface());
        }
        return h;
    }

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
