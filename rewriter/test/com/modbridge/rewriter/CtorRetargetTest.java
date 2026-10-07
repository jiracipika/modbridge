package com.modbridge.rewriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Pure-logic tests for the ctor-retarget pass (scripts/test-ctor-retarget.sh):
 * NEW+DUP+INVOKESPECIAL surgery against rules/ctorretargets.txt — the ModConfig ->
 * per-mod ModConfigBridge shim retarget that unblocks moonlight's ConfigSpecWrapper
 * (trial 002 phase 5.5 + the eager-config slice). Covers the straight-line site,
 * frames inside the ctor-arg region (a branch among the args names the uninitialized
 * ref in two NEW+DUP slots, which collapse to one receiver entry), the nested-new
 * scan-stop, no-rule ctors left untouched, @SHIM@ rule owners resolving to the
 * per-mod shim package, and rule parsing against the shipped rule file.
 */
public final class CtorRetargetTest {

    static final String OLD_OWNER = "net/minecraftforge/fml/config/ModConfig";
    static final String NEW_OWNER = "net/neoforged/fml/config/ConfigTracker";
    static final String RECEIVER_DESC = "L" + NEW_OWNER + ";";
    static final String OLD_DESC =
            "(Lnet/minecraftforge/fml/config/ModConfig$Type;Lnet/minecraftforge/fml/config/IConfigSpec;"
                    + "Lnet/minecraftforge/fml/ModContainer;Ljava/lang/String;)V";
    /** final-space factory desc (inserted names are NOT remapped — the shipped rule
     *  carries 21.1 types; the pre-eager-config constant had a raw-space Type arg) */
    static final String NEW_DESC =
            "(Lnet/neoforged/fml/config/ModConfig$Type;Lnet/neoforged/fml/config/IConfigSpec;"
                    + "Lnet/neoforged/fml/ModContainer;Ljava/lang/String;)Lnet/neoforged/fml/config/ModConfig;";

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) throws Exception {
        CtorRetargetTest t = new CtorRetargetTest();
        t.run();
        System.out.println("all " + t.passed + " checks passed");
    }

    void run() throws Exception {
        ruleParsing();
        straightLineSite();
        framesInsideArgRegion();
        nestedNewUntouched();
        checkcastInArgRegion();
        otherCtorDescUntouched();
        shimOwnerResolution();
        unresolvedShimOwnerFailsFast();
        realRuleFileLoads();
        realRuleFileResolvesThroughShim();
        staticKindMemberRetargets();
        staticKindUnruledShapeUntouched();
        realMemberRetargetsLoad();
        methodDrops();
        realMethodDropsLoad();
        holderUnwrapMechanism();
        holderUnwrapArgPushes();
        holderUnwrapLocal();
        holderUnwrapHolderConsumerUntouched();
        realMethodUnwrapsLoad();
        bareWrapMechanism();
        bareWrapArgPushes();
        bareWrapLocalReload();
        bareWrapUntouchedShapes();
        realMethodWrapsLoad();
        starOwnerRetarget();
        realPhase512RulesLoad();
        interfaceCallSites();
        interfaceInventoryMissingFileTolerated();
        indySamNameRemap();
        indySamShapeRewrite();
        indySamCapturedStatic();
        indySamArgLoadFailsFast();
        metadataSectionOverrideFix();
        metadataSectionOverrideUntouchedElsewhere();
        superSwapMechanism();
        fieldRetargetMechanism();
    }

    Rewriter rewriterWithRule() throws IOException {
        Rewriter r = new Rewriter();
        r.addCtorRetargetRules(List.of(OLD_OWNER + "|" + OLD_DESC + " -> "
                + NEW_OWNER + "|INSTANCE|registerConfig|" + NEW_DESC));
        return r;
    }

    /**
     * static make(Z)Ljava/lang/Object; with a branch INSIDE the ctor args: the frames
     * at the branch targets carry the uninit ref in both NEW+DUP slots, exactly like
     * javac emits for `new ModConfig(type, flag ? a : b, c, d)`.
     */
    ClassNode branchyHost() {
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "(Z)Ljava/lang/Object;", null, null);
        mn.maxStack = 6;
        mn.maxLocals = 1;
        LabelNode lNew = new LabelNode();
        LabelNode lElse = new LabelNode();
        LabelNode lEnd = new LabelNode();
        mn.instructions.add(lNew);
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, OLD_OWNER));
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new LdcInsnNode("a"));            // arg 1
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFEQ, lElse));
        mn.instructions.add(new LdcInsnNode("b"));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, lEnd));
        mn.instructions.add(lElse);
        // frames: static (Z) locals = [INTEGER]; stack at lElse/lEnd = [uninit, uninit, "a"]
        mn.instructions.add(new FrameNode(Opcodes.F_FULL,
                1, new Object[]{Opcodes.INTEGER},
                3, new Object[]{lNew, lNew, "java/lang/String"}));
        mn.instructions.add(new LdcInsnNode("c"));
        mn.instructions.add(lEnd);
        mn.instructions.add(new FrameNode(Opcodes.F_FULL,
                1, new Object[]{Opcodes.INTEGER},
                3, new Object[]{lNew, lNew, "java/lang/String"}));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // arg 3 (container)
        mn.instructions.add(new LdcInsnNode("f"));              // arg 4 (fileName)
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                OLD_OWNER, "<init>", OLD_DESC, false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        return cn;
    }

    void ruleParsing() {
        Rewriter r = new Rewriter();
        try {
            r.addCtorRetargetRules(List.of("no-arrow-here"));
            check(false, "rule without '->' rejected");
        } catch (IOException expected) {
            check(true, "rule without '->' rejected");
        }
        try {
            r.addCtorRetargetRules(List.of("a|b -> c|d|e"));
            check(false, "rule with wrong field count rejected");
        } catch (IOException expected) {
            check(true, "rule with wrong field count rejected");
        }
    }

    void straightLineSite() throws IOException {
        Rewriter r = rewriterWithRule();
        ClassNode cn = branchyHost();
        r.rewriteCtorRetargets(cn);
        MethodNode mn = cn.methods.get(0);
        check(r.ctorRetargetsApplied == 1, "site retargeted (counter=1)");
        check(count(mn, TypeInsnNode.class, t -> t.getOpcode() == Opcodes.NEW) == 0,
                "NEW gone");
        check(countInsns(mn, n -> n.getOpcode() == Opcodes.DUP) == 0, "DUP gone");
        check(countInsns(mn, n -> n instanceof MethodInsnNode m
                && m.getOpcode() == Opcodes.INVOKESPECIAL) == 0, "INVOKESPECIAL gone");
        FieldInsnNode gs = first(mn, FieldInsnNode.class,
                f -> f.getOpcode() == Opcodes.GETSTATIC && NEW_OWNER.equals(f.owner));
        check(gs != null && "INSTANCE".equals(gs.name) && RECEIVER_DESC.equals(gs.desc),
                "GETSTATIC receiver inserted");
        MethodInsnNode iv = first(mn, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKEVIRTUAL && NEW_OWNER.equals(m.owner));
        check(iv != null && "registerConfig".equals(iv.name) && NEW_DESC.equals(iv.desc) && !iv.itf,
                "INVOKEVIRTUAL registerConfig with new desc");
        check(indexOf(mn, gs) < indexOf(mn, iv), "receiver sits under the args");
        // round-trip serialization must not throw
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        check(cw.toByteArray().length > 0, "serialized without error");
    }

    void framesInsideArgRegion() throws IOException {
        Rewriter r = rewriterWithRule();
        ClassNode cn = branchyHost();
        MethodNode mn = cn.methods.get(0);
        int framesBefore = countInsns(mn, n -> n instanceof FrameNode);
        r.rewriteCtorRetargets(cn);
        check(countInsns(mn, n -> n instanceof FrameNode) == framesBefore,
                "arg-region frames kept (not dropped)");
        check(countInsns(mn, n -> n instanceof FrameNode f
                && f.stack.stream().anyMatch(e -> e instanceof LabelNode)) == 0,
                "no uninit LabelNode left in any frame stack");
        for (AbstractInsnNode n : mn.instructions) {
            if (!(n instanceof FrameNode f)) continue;
            // frame stack slots carry the INTERNAL name (the L...;-form would crash
            // the class with ClassFormatError "Illegal class name" - the 5.12
            // normalization in fixArgRegionFrames)
            check(NEW_OWNER.equals(f.stack.get(0)),
                    "first uninit slot became the receiver type (internal name)");
            check("java/lang/String".equals(f.stack.get(1)),
                    "second uninit slot (the DUP copy) removed, args shifted up");
            check(f.stack.size() == 2, "frame stack has receiver + one arg");
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        check(cw.toByteArray().length > 0, "fixed frames serialize without error");
    }

    void nestedNewUntouched() throws IOException {
        Rewriter r = rewriterWithRule();
        ClassNode cn = new ClassNode();
        cn.name = "test/Nested";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, OLD_OWNER));
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object")); // scan stops here
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new LdcInsnNode("f"));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                OLD_OWNER, "<init>", OLD_DESC, false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.rewriteCtorRetargets(cn);
        check(r.ctorRetargetsApplied == 0, "nested-new site left untouched (scan stop)");
        check(countInsns(mn, n -> n instanceof MethodInsnNode m
                && m.getOpcode() == Opcodes.INVOKESPECIAL
                && OLD_OWNER.equals(m.owner)) == 1, "original INVOKESPECIAL still present");
    }

    /**
     * phase 5.13 regression: a CHECKCAST (or INSTANCEOF/ANEWARRAY) INSIDE the ctor
     * argument region must not abort the forward scan. Live boot: moonlight
     * DynamicResourcePack.lambda$registerPack$2 builds
     * {@code new Pack.Info(supplier.get() CAST PackMetadataSection).description(),
     * .packFormat(), ...)} — the arg region's checkcast stopped the old
     * any-TypeInsnNode scan, the Pack$Info rule never fired and the removed ctor
     * stayed in the bytecode (CNFE at class-load). Shape mirrors that site.
     */
    void checkcastInArgRegion() throws IOException {
        Rewriter r = rewriterWithRule();
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "()Ljava/lang/Object;", null, null);
        mn.maxStack = 6;
        mn.maxLocals = 0;
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, OLD_OWNER));
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/Src", "get",
                "()Ljava/lang/Object;", false));        // returns Object
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "test/Arg")); // the cast
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, OLD_OWNER));  // trailing cast, same type
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new LdcInsnNode("f"));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                OLD_OWNER, "<init>", OLD_DESC, false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.rewriteCtorRetargets(cn);
        MethodNode out = cn.methods.get(0);
        check(r.ctorRetargetsApplied == 1,
                "checkcast-in-args site retargeted (counter=1; the old scan bailed)");
        check(countInsns(out, n -> n.getOpcode() == Opcodes.INVOKESPECIAL) == 0,
                "INVOKESPECIAL gone despite the arg-region casts");
        check(countInsns(out, n -> n.getOpcode() == Opcodes.CHECKCAST) == 2,
                "both arg-region casts untouched (args flow through verbatim)");
        MethodInsnNode iv = first(out, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKEVIRTUAL && NEW_OWNER.equals(m.owner));
        check(iv != null && "registerConfig".equals(iv.name),
                "INVOKEVIRTUAL registerConfig inserted after the cast args");
    }

    void otherCtorDescUntouched() throws IOException {
        Rewriter r = rewriterWithRule();
        ClassNode cn = new ClassNode();
        cn.name = "test/Other";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "(I)Ljava/lang/Object;", null, null);
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, OLD_OWNER));
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                OLD_OWNER, "<init>", "(I)V", false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.rewriteCtorRetargets(cn);
        check(r.ctorRetargetsApplied == 0, "ctor with unruled descriptor left untouched");
    }

    /** the shipped rule file must parse and contain the ModConfig entry */
    void realRuleFileLoads() throws IOException {
        Rewriter r = new Rewriter();
        List<String> lines = Files.readAllLines(Path.of("rules/ctorretargets.txt")).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        r.addCtorRetargetRules(lines);
        check(r.ctorRetargets.size() >= 1, "rules/ctorretargets.txt parses (the 5.12 AttributeModifier rule grew the file)");
        String[] rule = r.ctorRetargets.get(OLD_OWNER + "|" + OLD_DESC);
        check(rule != null, "ModConfig 4-arg ctor rule present");
        check(rule != null && "@SHIM@/ModConfigBridge".equals(rule[0])
                        && "INSTANCE".equals(rule[1])
                        && "registerAndLoad".equals(rule[2])
                        && NEW_DESC.equals(rule[3]),
                "rule targets the ModConfigBridge shim (eager-config slice)");
        check(r.ctorRetargetOwners.contains(OLD_OWNER), "owner pre-scan index populated");
    }

    /** a @SHIM@ rule owner must land on the per-mod shim package in rewritten bytecode */
    void shimOwnerResolution() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        r.initShimRoot("moonlight");
        ClassNode cn = branchyHost();
        r.rewriteCtorRetargets(cn);
        MethodNode mn = cn.methods.get(0);
        String shimOwner = "com/modbridge/shim/moonlight/ModConfigBridge";
        FieldInsnNode gs = first(mn, FieldInsnNode.class,
                f -> f.getOpcode() == Opcodes.GETSTATIC && shimOwner.equals(f.owner));
        check(r.ctorRetargetsApplied == 1, "shim-ruled site retargeted");
        check(gs != null && "INSTANCE".equals(gs.name)
                        && ("L" + shimOwner + ";").equals(gs.desc),
                "@SHIM@ rule owner resolved to the per-mod shim package");
        MethodInsnNode iv = first(mn, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKEVIRTUAL && shimOwner.equals(m.owner));
        check(iv != null && "registerAndLoad".equals(iv.name) && NEW_DESC.equals(iv.desc) && !iv.itf,
                "INVOKEVIRTUAL ModConfigBridge.registerAndLoad with new desc");
    }

    /** a @SHIM@ rule naming a class outside the shim manifest must fail translation */
    void unresolvedShimOwnerFailsFast() throws IOException {
        Rewriter r = new Rewriter();
        r.addCtorRetargetRules(List.of(OLD_OWNER + "|" + OLD_DESC + " -> "
                + "@SHIM@/NoSuchShim|INSTANCE|nope|" + NEW_DESC));
        ClassNode cn = branchyHost();
        try {
            r.rewriteCtorRetargets(cn);
            check(false, "@SHIM@ rule with unknown manifest entry rejected");
        } catch (IllegalStateException expected) {
            check(true, "@SHIM@ rule with unknown manifest entry rejected");
        }
    }

    /** the shipped rule file, fully loaded like the CLI does, rewrites through the shim */
    void realRuleFileResolvesThroughShim() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        r.initShimRoot("moonlight");
        ClassNode cn = branchyHost();
        r.rewriteCtorRetargets(cn);
        check(r.ctorRetargetsApplied == 1, "shipped rule drives the rewrite end-to-end");
        check(countInsns(cn.methods.get(0), n -> n instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && m.owner.startsWith("com/modbridge/shim/moonlight/")) == 1,
                "shipped rule lands on the per-mod ModConfigBridge");
    }

    // ------------------------------------------- memberretargets "static" kind (phase 5.9)

    /** one call site per drifted DFU 6->8 member shape (the phase-5.8 boot wall) */
    ClassNode dataResultHost() {
        ClassNode cn = new ClassNode();
        cn.name = "test/DataResultHost";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "use", "(Lcom/mojang/serialization/DataResult;ZLjava/util/function/Consumer;)Ljava/lang/Object;", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult", "getOrThrow",
                "(ZLjava/util/function/Consumer;)Ljava/lang/Object;", false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        MethodNode mn2 = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "split", "(Lcom/mojang/serialization/DataResult;)Ljava/lang/Object;", null, null);
        mn2.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn2.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult", "get",
                "()Lcom/mojang/datafixers/util/Either;", false));
        mn2.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn2);
        MethodNode mn3 = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "msg", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        mn3.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn3.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult$PartialResult", "message",
                "()Ljava/lang/String;", false));
        mn3.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn3);
        return cn;
    }

    void staticKindMemberRetargets() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        r.initShimRoot("testmod");
        ClassNode cn = dataResultHost();
        r.remapClass(cn);            // production order: type renames run first
        r.applyMemberRenames(cn);
        String shimOwner = "com/modbridge/shim/testmod/DataResultCompat";
        MethodInsnNode got = first(cn.methods.get(0), MethodInsnNode.class,
                m -> "getOrThrow".equals(m.name));
        check(r.memberRenamesApplied == 2, "both drifted shapes retargeted (counter=2)");
        check(got != null && got.getOpcode() == Opcodes.INVOKESTATIC,
                "getOrThrow(Z,Consumer) site became INVOKESTATIC");
        check(got.owner.equals(shimOwner), "getOrThrow owner = per-mod DataResultCompat");
        check(got.desc.equals("(Lcom/mojang/serialization/DataResult;ZLjava/util/function/Consumer;)Ljava/lang/Object;"),
                "getOrThrow desc = receiver-prepended adapter shape");
        check(!got.itf, "getOrThrow site not flagged itf");
        MethodInsnNode get = first(cn.methods.get(1), MethodInsnNode.class,
                m -> "get".equals(m.name));
        check(get != null && get.getOpcode() == Opcodes.INVOKESTATIC && get.owner.equals(shimOwner),
                "get() site became INVOKESTATIC on the shim");
        check(get.desc.equals("(Lcom/mojang/serialization/DataResult;)Lcom/mojang/datafixers/util/Either;"),
                "get() desc = receiver-prepended, erased Either return");
        // the third shape (PartialResult.message) is a TYPERENAME, not a memberretarget:
        // remapClass renamed the owner before memberretargets ever see it; the Error
        // record accessor is byte-compatible (same name + erased desc)
        MethodInsnNode msg = first(cn.methods.get(2), MethodInsnNode.class,
                m -> "message".equals(m.name));
        check(msg != null && msg.owner.equals("com/mojang/serialization/DataResult$Error"),
                "PartialResult.message owner renamed to the Error record by remapClass");
        check(msg.desc.equals("()Ljava/lang/String;") && !msg.itf
                        && msg.getOpcode() == Opcodes.INVOKEVIRTUAL,
                "message keeps its erased desc and virtual kind");
        check(!cn.methods.get(2).desc.contains("PartialResult"),
                "host method desc carries no PartialResult after remapClass");
        // shim relocation must leave ZERO base-name refs in relocated bytes - the
        // live boot died twice here: lambda Handle bsmArgs (DataResultCompat) and the
        // ENUM synthesis positions ($VALUES field desc / LDC class constant in the
        // synthesized valueOf) on NetworkDirection
        Rewriter rs = new Rewriter();
        rs.loadRules(Path.of("rules"));
        rs.initShimRoot("reloc");
        Map<String, byte[]> shimEntries = new HashMap<>();
        rs.injectShims(shimEntries);
        for (Map.Entry<String, byte[]> en : shimEntries.entrySet()) {
            String text = new String(en.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1);
            // strip the class's own relocated name prefix, then ANY remaining
            // com/modbridge/shim/ occurrence is a missed base-name ref
            boolean clean = !text.replace("com/modbridge/shim/reloc/", "")
                    .contains("com/modbridge/shim/");
            check(clean, "relocated " + en.getKey().substring(en.getKey().lastIndexOf('/') + 1)
                    + ": no base-name refs (byte-level)");
            // and no DOTTED binary names: a dotted InnerClasses outer-name constant
            // is ClassFormatError "Illegal class name" at define time (EntityItemPickupEvent\$Result)
            boolean noDotted = !text.contains("com.modbridge.shim.");
            check(noDotted, "relocated " + en.getKey().substring(en.getKey().lastIndexOf('/') + 1)
                    + ": no dotted binary names (byte-level)");
        }

        // InnerClasses entries: the simple name follows the renamed internal name
        ClassNode innerHost = new ClassNode();
        innerHost.name = "test/InnerHost";
        innerHost.version = Opcodes.V17;
        innerHost.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        innerHost.superName = "java/lang/Object";
        innerHost.innerClasses = new java.util.ArrayList<>();
        innerHost.innerClasses.add(new org.objectweb.asm.tree.InnerClassNode(
                "com/mojang/serialization/DataResult$PartialResult",
                "com/mojang/serialization/DataResult", "PartialResult", 0));
        r.remapClass(innerHost);
        check(innerHost.innerClasses.get(0).name.endsWith("$Error")
                        && "Error".equals(innerHost.innerClasses.get(0).innerName),
                "InnerClasses simple name follows the renamed internal name");
        // shim manifest must carry the adapter (injected per-jar, relocated)
        check(r.shims.containsKey("com/modbridge/shim/DataResultCompat"),
                "DataResultCompat present in the shim manifest");
    }

    void staticKindUnruledShapeUntouched() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        r.initShimRoot("testmod");
        ClassNode cn = new ClassNode();
        cn.name = "test/Unruled";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "noArg", "(Lcom/mojang/serialization/DataResult;)Ljava/lang/Object;", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult", "getOrThrow",
                "()Ljava/lang/Object;", false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.applyMemberRenames(cn);
        MethodInsnNode iv = first(mn, MethodInsnNode.class, m -> "getOrThrow".equals(m.name));
        check(r.memberRenamesApplied == 0, "unruled desc left alone (counter=0)");
        check(iv != null && iv.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && iv.owner.equals("com/mojang/serialization/DataResult"),
                "no-arg getOrThrow stays a DataResult virtual (21.1 default member)");
    }

    // ------------------------------------------- indy SAM-name remap (shapes lane)

    void indySamNameRemap() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        ClassNode cn = new ClassNode();
        cn.name = "test/Indy";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "use", "(Lnet/minecraft/world/phys/shapes/VoxelShape;)V", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new InvokeDynamicInsnNode("m_83161_",
                "(DLnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/Shapes$DoubleLineConsumer;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;"
                                + ")Ljava/lang/invoke/CallSite;", false),
                new Object[]{Opcodes.ASM9, org.objectweb.asm.Type.getType("(DDDDDD)V"),
                        new Handle(Opcodes.H_INVOKESTATIC, "test/Indy", "lambda$use$0",
                                "(DLnet/minecraft/core/Direction;DDDDD)V", false),
                        org.objectweb.asm.Type.getType("(DDDDDD)V")}));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/phys/shapes/VoxelShape", "forAllBoxes",
                "(Lnet/minecraft/world/phys/shapes/Shapes$DoubleLineConsumer;)V", false));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.remapClass(cn);
        InvokeDynamicInsnNode id = first(mn, InvokeDynamicInsnNode.class, x -> true);
        check(id != null && id.name.equals("consume"),
                "indy SAM name remapped m_83161_ -> consume (metafactory implements the right method)");
        check(id.desc.contains("Shapes$DoubleLineConsumer"),
                "indy desc type untouched (same name both versions)");
        // java-interface lambdas pass through untouched
        ClassNode cn2 = new ClassNode();
        cn2.name = "test/Indy2";
        cn2.version = Opcodes.V17;
        cn2.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn2.superName = "java/lang/Object";
        MethodNode mn2 = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "(Ljava/lang/Runnable;)V", null, null);
        mn2.instructions.add(new InvokeDynamicInsnNode("run", "(Ljava/lang/Runnable;)V",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;"
                                + ")Ljava/lang/invoke/CallSite;", false),
                new Object[]{Opcodes.ASM9, org.objectweb.asm.Type.getType("()V"),
                        new Handle(Opcodes.H_INVOKESTATIC, "test/Indy2", "lambda$run$0",
                                "()V", false),
                        org.objectweb.asm.Type.getType("()V")}));
        mn2.instructions.add(new InsnNode(Opcodes.RETURN));
        cn2.methods.add(mn2);
        r.remapClass(cn2);
        InvokeDynamicInsnNode id2 = first(mn2, InvokeDynamicInsnNode.class, x -> true);
        check(id2 != null && id2.name.equals("run"),
                "non-SRG indy names pass through (java-interface lambdas unaffected)");
    }

    // ------------------------------------------- indy SAM SHAPE rewrites (indysams.txt)

    static final String SUPPLIER_IFACE = "net/minecraft/server/packs/repository/Pack$ResourcesSupplier";
    static final String OPEN_FULL_DESC = "(Lnet/minecraft/server/packs/PackLocationInfo;"
            + "Lnet/minecraft/server/packs/repository/Pack$Metadata;)Lnet/minecraft/server/packs/PackResources;";

    /** mirrors the real pipeline order: remapClass (indy name m_247679_ -> "open",
     *  bsm types final-space) then rewriteIndySams (final-space keys). */
    ClassNode resourcesSupplierHost(Rewriter r, String implDesc, boolean implLoadsArg) throws IOException {
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PROTECTED, "registerPack", "()V", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new InvokeDynamicInsnNode("m_247679_",
                "(Ltest/Host;)L" + SUPPLIER_IFACE + ";",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;"
                                + ")Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getType(
                        "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, "test/Host", "lambda$registerPack$1",
                        implDesc, false),
                org.objectweb.asm.Type.getType(
                        "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;")));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        MethodNode impl = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "lambda$registerPack$1", implDesc, null, null);
        if (implLoadsArg) impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        impl.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(impl);
        r.remapClass(cn);
        r.rewriteIndySams(cn);
        return cn;
    }

    void indySamShapeRewrite() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules")); // the shipped indysams.txt
        ClassNode cn = resourcesSupplierHost(r,
                "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;", false);
        InvokeDynamicInsnNode id = first(cn.methods.get(0), InvokeDynamicInsnNode.class, x -> true);
        check(id != null && "openFull".equals(id.name),
                "indysams: indy name open -> openFull (the SAM the 21.1 runtime dispatches)");
        check(id.bsmArgs[0] instanceof org.objectweb.asm.Type t
                        && OPEN_FULL_DESC.equals(t.getDescriptor())
                        && id.bsmArgs[2] instanceof org.objectweb.asm.Type t2
                        && OPEN_FULL_DESC.equals(t2.getDescriptor()),
                "indysams: samMethodType + instantiatedMethodType both follow the 21.1 SAM");
        Handle h = (Handle) id.bsmArgs[1];
        check(OPEN_FULL_DESC.equals(h.getDesc()),
                "indysams: impl handle desc widened to the 21.1 shape");
        MethodNode impl = cn.methods.get(1);
        check(impl.name.equals("lambda$registerPack$1") && OPEN_FULL_DESC.equals(impl.desc),
                "indysams: the lambda impl method's declared desc follows the handle");
        check(impl.maxLocals >= 3,
                "indysams: impl maxLocals grew to fit the widened args (no ClassFormatError)");
        check(r.indySamsApplied == 1, "indysams: applied counter = 1");
    }

    void indySamArgLoadFailsFast() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        try {
            resourcesSupplierHost(r,
                    "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;", true);
            check(false, "indysams: arg-loading impl rejected (fail fast, never mis-adapted)");
        } catch (IllegalStateException expected) {
            check(true, "indysams: arg-loading impl rejected (fail fast, never mis-adapted)");
        }
    }

    /** the ClientHelperImpl shape: a STATIC impl with a CAPTURED prefix
     *  (PathPackResources in the real site) + the String SAM arg. The captured prefix
     *  must survive the desc derivation and loading it stays legal. */
    void indySamCapturedStatic() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        ClassNode cn = new ClassNode();
        cn.name = "test/Captured";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "reg", "()V", null, null);
        mn.instructions.add(new InvokeDynamicInsnNode("m_247679_",
                "()L" + SUPPLIER_IFACE + ";",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;"
                                + ")Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getType(
                        "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;"),
                new Handle(Opcodes.H_INVOKESTATIC, "test/Captured", "lambda$reg$16",
                        "(Ltest/CapturedPack;Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;", false),
                org.objectweb.asm.Type.getType(
                        "(Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;")));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        MethodNode impl = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_STATIC,
                "lambda$reg$16", "(Ltest/CapturedPack;Ljava/lang/String;)Lnet/minecraft/server/packs/PackResources;",
                null, null);
        impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the CAPTURED pack - legal
        impl.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(impl);
        r.remapClass(cn);
        r.rewriteIndySams(cn);
        InvokeDynamicInsnNode id = first(mn, InvokeDynamicInsnNode.class, x -> true);
        check("openFull".equals(id.name), "indysams captured-static: name rewritten");
        Handle h = (Handle) id.bsmArgs[1];
        check(h.getDesc().equals("(Ltest/CapturedPack;" + OPEN_FULL_DESC.substring(1)),
                "indysams captured-static: derived desc keeps the captured prefix + new SAM args");
        MethodNode impl2 = cn.methods.get(1);
        check(impl2.desc.equals(h.getDesc()),
                "indysams captured-static: declared impl desc follows");
    }

    // ------------------------------------------- getMetadataSection override surgery

    static final String DRP = "net/mehvahdjukaar/moonlight/api/resources/pack/DynamicResourcePack";

    /** the exact raw 1.20.1 body shape (javap'd): the always-true serializer
     *  instanceof + the metadata SUPPLIER returned unwrapped. */
    ClassNode metadataSectionHost(String className, String serializerDesc) {
        ClassNode cn = new ClassNode();
        cn.name = className;
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "getMetadataSection",
                "(Lnet/minecraft/server/packs/metadata/MetadataSectionSerializer;)Ljava/lang/Object;",
                null, null);
        LabelNode lNull = new LabelNode();
        LabelNode lEnd = new LabelNode();
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, serializerDesc));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFEQ, lNull));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className,
                "metadata", "Ljava/util/function/Supplier;"));
        mn.instructions.add(new JumpInsnNode(Opcodes.GOTO, lEnd));
        mn.instructions.add(lNull);
        // frame at the merge: stack [Supplier] (the goto path) - the pass must retype it
        mn.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[]{},
                1, new Object[]{"java/util/function/Supplier"}));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(lEnd);
        mn.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[]{},
                1, new Object[]{"java/lang/Object"}));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        return cn;
    }

    void metadataSectionOverrideFix() {
        Rewriter r = new Rewriter();
        // production order: the serializer type was already typerenamed by remapClass
        ClassNode cn = metadataSectionHost(DRP,
                "net/minecraft/server/packs/metadata/MetadataSectionSerializer");
        r.fixMetadataSectionOverride(cn);
        MethodNode mn = cn.methods.get(0);
        check(countInsns(mn, n -> n instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF) == 0,
                "metaSection fix: the (gone) serializer instanceof is gone (renamed spelling)");
        FieldInsnNode gs = first(mn, FieldInsnNode.class,
                f -> f.getOpcode() == Opcodes.GETSTATIC
                        && f.owner.equals("net/minecraft/server/packs/metadata/pack/PackMetadataSection"));
        check(gs != null && "TYPE".equals(gs.name),
                "metaSection fix: reference compare against PackMetadataSection.TYPE");
        JumpInsnNode acmp = first(mn, JumpInsnNode.class, j -> j.getOpcode() == Opcodes.IF_ACMPNE);
        check(acmp != null, "metaSection fix: IFEQ became IF_ACMPNE (equal stack depth, frames valid)");
        check(countInsns(mn, i -> i instanceof VarInsnNode v
                        && v.getOpcode() == Opcodes.ALOAD && v.var == 1) >= 1,
                "metaSection fix: the ALOAD_1 comparand is kept (acmp pops two refs)");
        check(mn.maxStack >= 2, "metaSection fix: maxStack grew for the two-ref compare");
        MethodInsnNode get = first(mn, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKEINTERFACE && "get".equals(m.name)
                        && "java/util/function/Supplier".equals(m.owner));
        check(get != null, "metaSection fix: the Supplier field load is unwrapped with .get()");
        int supplierFrames = count(mn, FrameNode.class,
                fr -> fr.stack != null && fr.stack.contains("java/util/function/Supplier"));
        int objectFrames = count(mn, FrameNode.class,
                fr -> fr.stack != null && fr.stack.contains("java/lang/Object"));
        check(supplierFrames == 0 && objectFrames >= 1,
                "metaSection fix: the merge frame carries Object, not the Supplier");
        // the raw 1.20.1 spelling (pre-typerename) matches too, for robustness
        ClassNode raw = metadataSectionHost(DRP,
                "net/minecraft/server/packs/metadata/pack/PackMetadataSectionSerializer");
        r.fixMetadataSectionOverride(raw);
        check(countInsns(raw.methods.get(0), n -> n instanceof TypeInsnNode
                        && ((TypeInsnNode) n).getOpcode() == Opcodes.INSTANCEOF) == 0,
                "metaSection fix: the raw spelling matches as well");
    }

    void metadataSectionOverrideUntouchedElsewhere() {
        Rewriter r = new Rewriter();
        ClassNode other = metadataSectionHost("some/other/Class",
                "net/minecraft/server/packs/metadata/MetadataSectionSerializer");
        r.fixMetadataSectionOverride(other); // no-op, no throw
        MethodNode mn = other.methods.get(0);
        check(countInsns(mn, n -> n instanceof TypeInsnNode
                && ((TypeInsnNode) n).getOpcode() == Opcodes.INSTANCEOF) == 1,
                "metaSection fix: other classes untouched (single measured site)");
    }

    // ------------------------------------------- superswap pass (enchantment lane)

    void superSwapMechanism() throws IOException {
        Rewriter r = new Rewriter();
        r.addSuperSwapRules(List.of(
                "test/Sub|test/old/Super -> @SHIM@/NewSuper",
                "test/Guarded|test/other/Super -> test/never/Applied"));
        r.initShimRoot("testmod");
        // NOTE: @SHIM@ resolution checks the shim simple-name manifest
        r.shimSimpleNames.put("NewSuper", "com/modbridge/shim/NewSuper");
        check(applySuperSwapIn(r, "test/Sub", "test/old/Super").equals("com/modbridge/shim/testmod/NewSuper"),
                "superswap applied when the current super matches the rule's old super");
        check(applySuperSwapIn(r, "test/Sub", "test/other/Super").equals("test/other/Super"),
                "superswap NOT applied when the current super differs (never guessed)");
        check(applySuperSwapIn(r, "test/Guarded", "test/old/Super").equals("test/old/Super"),
                "superswap NOT applied for unmatched classes");
        check(r.superswapsApplied == 1, "superswap counter = 1");
        // the swap carries the super plumbing: invokespecial oldSuper.<init> follows
        ClassNode sub = new ClassNode();
        sub.name = "test/Sub";
        sub.version = Opcodes.V17;
        sub.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        sub.superName = "test/old/Super";
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "test/old/Super", "<init>", "(I)V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        sub.methods.add(ctor);
        MethodNode member = new MethodNode(Opcodes.ACC_PUBLIC, "check", "(Z)Z", null, null);
        member.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        member.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        member.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "test/old/Super", "origMember", "(Z)Z", false));
        member.instructions.add(new InsnNode(Opcodes.IRETURN));
        sub.methods.add(member);
        r.remapClass(sub);
        MethodInsnNode superCall = first(ctor, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(m.name));
        check(superCall != null && superCall.owner.equals("com/modbridge/shim/testmod/NewSuper"),
                "superswap retargets the ctor's invokespecial oldSuper.<init> owner");
        MethodInsnNode memberCall = first(member, MethodInsnNode.class,
                m -> m.getOpcode() == Opcodes.INVOKESPECIAL);
        check(memberCall != null && memberCall.owner.equals("com/modbridge/shim/testmod/NewSuper"),
                "superswap retargets MEMBER super-calls too (super.canEnchant family)");
        check(sub.superName.equals("com/modbridge/shim/testmod/NewSuper"),
                "superswap retargets superName itself");
        // shipped rule file parses + guards
        Rewriter rs = new Rewriter();
        rs.loadRules(Path.of("rules"));
        check(rs.superSwaps.size() >= 2
                        && rs.superSwaps.containsKey("net/mehvahdjukaar/supplementaries/common/misc/StasisEnchantment")
                        && rs.superSwaps.containsKey("net/mehvahdjukaar/supplementaries/common/entities/dispenser_minecart/MovingBlockSource"),
                "rules/superswaps.txt parses (>=2 rules incl. the interface-strip)");
    }

    void fieldRetargetMechanism() throws IOException {
        Rewriter r = new Rewriter();
        r.memberRetargets.put("test/Old|Ltest/Val;|FIELD",
                new String[]{"test/New", "Ltest/NewVal;", "keep"});
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "get", "()Ltest/Val;", null, null);
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "test/Old", "FIELD", "Ltest/Val;"));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.applyMemberRenames(cn);
        FieldInsnNode fi = first(mn, FieldInsnNode.class, f -> true);
        check(fi != null && fi.owner.equals("test/New") && fi.desc.equals("Ltest/NewVal;"),
                "field retarget swaps owner+desc, opcode kept");
        check(r.memberRenamesApplied == 1, "field retarget counter = 1");
    }

    /** rules/methodunwraps.txt (phase 5.12): the getEffect Holder-unwrap pass */
    void holderUnwrapMechanism() {
        Rewriter r = new Rewriter();
        r.holderUnwraps.put("net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect",
                "net/minecraft/world/effect/MobEffect");
        // immediate receiver consumer: getEffect -> isBeneficial
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "m", "(Lnet/minecraft/world/effect/MobEffectInstance;)Z", null, null);
        mn.maxStack = 2;
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getEffect",
                "()Lnet/minecraft/core/Holder;", false));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffect", "isBeneficial", "()Z", false));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(mn);
        r.repairHolderUnwraps(cn);
        int values = count(mn, MethodInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/Holder") && x.name.equals("value"));
        check(values == 1, "immediate receiver consumer: value() inserted once");
        check(mn.instructions.get(2) instanceof MethodInsnNode,
                "value() inserted between producer and consumer");
        check(r.holderUnwrapsApplied == 1, "unwrap counter = 1");
    }

    /** the measured BambooSpikes third shape: args pushed between producer and the
     *  receiver-consuming call (aconst_null, aload, invoke, i2d...) */
    void holderUnwrapArgPushes() {
        Rewriter r = new Rewriter();
        r.holderUnwraps.put("net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect",
                "net/minecraft/world/effect/MobEffect");
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC,
                "m", "(Lnet/minecraft/world/entity/LivingEntity;)V", null, null);
        mn.maxStack = 8;
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getEffect",
                "()Lnet/minecraft/core/Holder;", false));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getAmplifier", "()I", false));
        mn.instructions.add(new InsnNode(Opcodes.I2D));
        mn.instructions.add(new InsnNode(Opcodes.DCONST_1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffect", "applyInstantenousEffect",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/world/entity/LivingEntity;ID)V", false));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.repairHolderUnwraps(cn);
        int values = count(mn, MethodInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/Holder") && x.name.equals("value"));
        check(values == 1, "arg-pushes shape: value() inserted right after the producer");
        check(r.holderUnwrapsApplied == 1, "arg-pushes counter = 1");
    }

    /** the measured PotionNBTHelper shape: producer -> ASTORE -> ALOAD -> consumer */
    void holderUnwrapLocal() {
        Rewriter r = new Rewriter();
        r.holderUnwraps.put("net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect",
                "net/minecraft/world/effect/MobEffect");
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC,
                "m", "(Lnet/minecraft/world/effect/MobEffectInstance;)V", null, null);
        mn.maxStack = 2;
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getEffect",
                "()Lnet/minecraft/core/Holder;", false));
        mn.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffect", "getCategory",
                "()Lnet/minecraft/world/effect/MobEffectCategory;", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.repairHolderUnwraps(cn);
        int values = count(mn, MethodInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/Holder") && x.name.equals("value"));
        check(values == 1, "local shape: value() inserted at the load site");
        check(r.holderUnwrapsApplied == 1, "local counter = 1");
    }

    /** a consumer ALREADY on the Holder shape (the 5.11 desc rewrites) must stay
     *  untouched: hasEffect(Holder) takes the producer result as an ARG */
    void holderUnwrapHolderConsumerUntouched() {
        Rewriter r = new Rewriter();
        r.holderUnwraps.put("net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect",
                "net/minecraft/world/effect/MobEffect");
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC,
                "m", "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/effect/MobEffectInstance;)Z", null, null);
        mn.maxStack = 2;
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/effect/MobEffectInstance", "getEffect",
                "()Lnet/minecraft/core/Holder;", false));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/LivingEntity", "hasEffect",
                "(Lnet/minecraft/core/Holder;)Z", false));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(mn);
        r.repairHolderUnwraps(cn);
        int values = count(mn, MethodInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/Holder") && x.name.equals("value"));
        check(values == 0, "Holder-shaped consumer: no insert");
        check(r.holderUnwrapsApplied == 0, "Holder-shaped consumer: counter stays 0");
    }

    /** the shipped methodunwraps.txt parses and the CLI loader picks it up */
    void realMethodUnwrapsLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.addUnwrapRules(Files.readAllLines(Path.of("rules/methodunwraps.txt")));
        check(r.holderUnwraps.containsKey(
                "net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect"),
                "rules/methodunwraps.txt parses with the getEffect key");
        check("net/minecraft/world/effect/MobEffect".equals(
                r.holderUnwraps.get("net/minecraft/world/effect/MobEffectInstance|()Lnet/minecraft/core/Holder;|getEffect")),
                "getEffect unwrap target is the bare MobEffect owner");
        Rewriter full = new Rewriter();
        full.loadRules(Path.of("rules"));
        check(full.holderUnwraps.size() >= 1, "full rules load includes unwraps");
    }

    /** phase 5.12: the bare-wrap pass (the inverse of the unwrap) - a wrapper-get
     *  value (checkcast bare type) landing in a Holder-typed argument slot gets
     *  Registry.wrapAsHolder inserted. Measured wall: supp ModFluids.lambda$static$2
     *  building the LumiseneBottleItem food effect - vanilla MobEffectInstance.<init>
     *  called Holder.value() on the bare FlammableEffect (ICCE at the fluid
     *  RegisterEvent). */
    void bareWrapMechanism() {
        Rewriter r = new Rewriter();
        r.bareWraps.put("net/minecraft/world/effect/MobEffect",
                new String[]{"net/minecraft/core/Holder", "net/minecraft/core/registries/BuiltInRegistries",
                        "MOB_EFFECT", "Lnet/minecraft/core/Registry;"});
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/Object;I)Lnet/minecraft/world/effect/MobEffectInstance;", null, null);
        mn.maxStack = 4;
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/effect/MobEffect"));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/world/effect/MobEffectInstance", "<init>",
                "(Lnet/minecraft/core/Holder;I)V", false));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        r.repairBareWraps(cn);
        MethodInsnNode wrap = first(mn, MethodInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/Registry") && x.name.equals("wrapAsHolder"));
        check(wrap != null && wrap.itf && wrap.desc.equals("(Ljava/lang/Object;)Lnet/minecraft/core/Holder;"),
                "wrap inserted: Registry.wrapAsHolder (itf, erased desc)");
        FieldInsnNode reg = first(mn, FieldInsnNode.class,
                x -> x.owner.equals("net/minecraft/core/registries/BuiltInRegistries"));
        check(reg != null && reg.name.equals("MOB_EFFECT") && reg.desc.equals("Lnet/minecraft/core/Registry;"),
                "wrap inserted: BuiltInRegistries.MOB_EFFECT getstatic before the swap");
        check(count(mn, InsnNode.class, x -> x.getOpcode() == Opcodes.SWAP) == 1,
                "wrap inserted: SWAP between getstatic and the call");
        int idx = indexOf(mn, wrap);
        int castIdx = indexOf(mn, first(mn, TypeInsnNode.class, x -> true));
        check(idx == castIdx + 3, "wrap sits right after the producer (getstatic, swap, call)");
        check(r.bareWrapsApplied == 1, "wrap counter = 1");
        check(mn.maxStack >= 2, "maxStack bumped for the transient registry pair");
    }

    /** arg-push shape (FlammableLiquidBlock/SackItem): constants and conversions
     *  pushed between the producer and the Holder-arg consumer; a MobEffect-typed
     *  consumer (ml Utils.getID duck-typing) must stay untouched */
    void bareWrapArgPushes() {
        Rewriter r = new Rewriter();
        r.bareWraps.put("net/minecraft/world/effect/MobEffect",
                new String[]{"net/minecraft/core/Holder", "net/minecraft/core/registries/BuiltInRegistries",
                        "MOB_EFFECT", "Lnet/minecraft/core/Registry;"});
        //Holder-landing site with arithmetic between producer and consumer
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/Object;I)V", null, null);
        mn.maxStack = 6;
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/effect/MobEffect"));
        mn.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 400));
        mn.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        mn.instructions.add(new InsnNode(Opcodes.IDIV));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/world/effect/MobEffectInstance", "<init>",
                "(Lnet/minecraft/core/Holder;I)V", false));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.repairBareWraps(cn);
        check(r.bareWrapsApplied == 1, "arg-push shape with arithmetic: wrap fires");
        // bare-typed consumer: NO wrap (the value is the receiver of a MobEffect call)
        ClassNode cn2 = new ClassNode();
        cn2.name = "test/Host2";
        cn2.version = Opcodes.V17;
        cn2.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn2.superName = "java/lang/Object";
        MethodNode mn2 = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/Object;)I", null, null);
        mn2.maxStack = 2;
        mn2.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/effect/MobEffect"));
        mn2.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "test/Utils", "getID", "(Lnet/minecraft/world/effect/MobEffect;)I", false));
        mn2.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn2.methods.add(mn2);
        r.repairBareWraps(cn2);
        check(r.bareWrapsApplied == 1, "MobEffect-typed consumer stays untouched");
    }

    /** local + reload shape (BombEntity): producer -> ASTORE -> ALOAD (ifnull
     *  null-check) -> reload -> arg-pushes -> Holder-arg ctor: wrap at the RELOAD
     *  site (the null-check load keeps the bare value) */
    void bareWrapLocalReload() {
        Rewriter r = new Rewriter();
        r.bareWraps.put("net/minecraft/world/effect/MobEffect",
                new String[]{"net/minecraft/core/Holder", "net/minecraft/core/registries/BuiltInRegistries",
                        "MOB_EFFECT", "Lnet/minecraft/core/Registry;"});
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/Object;Lnet/minecraft/world/entity/LivingEntity;)V", null, null);
        mn.maxStack = 6;
        LabelNode lNull = new LabelNode();
        LabelNode lEnd = new LabelNode();
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/effect/MobEffect"));
        mn.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFNULL, lEnd));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/world/effect/MobEffectInstance"));
        mn.instructions.add(new InsnNode(Opcodes.DUP));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        mn.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 400));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/world/effect/MobEffectInstance", "<init>",
                "(Lnet/minecraft/core/Holder;I)V", false));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/LivingEntity", "addEffect",
                "(Lnet/minecraft/world/effect/MobEffectInstance;)Z", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(lEnd);
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.repairBareWraps(cn);
        check(r.bareWrapsApplied == 1, "local + reload shape: exactly one wrap");
        int reload = indexOf(mn, first(mn, VarInsnNode.class,
                x -> x.getOpcode() == Opcodes.ALOAD && x.var == 2
                        && x.getNext() instanceof FieldInsnNode fi && fi.name.equals("MOB_EFFECT")));
        int wrap = indexOf(mn, first(mn, MethodInsnNode.class,
                x -> x.name.equals("wrapAsHolder")));
        check(wrap == reload + 3, "wrap inserted right after the RELOAD (getstatic, swap, call; the null-check load keeps the bare value)");
    }

    /** consumers that must NOT wrap: invokestatic targets (QuarkCompat Set.of) and
     *  MobEffect-typed landing args */
    void bareWrapUntouchedShapes() {
        Rewriter r = new Rewriter();
        r.bareWraps.put("net/minecraft/world/effect/MobEffect",
                new String[]{"net/minecraft/core/Holder", "net/minecraft/core/registries/BuiltInRegistries",
                        "MOB_EFFECT", "Lnet/minecraft/core/Registry;"});
        ClassNode cn = new ClassNode();
        cn.name = "test/Host";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "(Ljava/lang/Object;)V", null, null);
        mn.maxStack = 3;
        mn.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/effect/MobEffect"));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Set", "of", "(Ljava/lang/Object;)Ljava/util/Set;", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.repairBareWraps(cn);
        check(r.bareWrapsApplied == 0, "invokestatic consumer (erased Object arg): no wrap");
    }

    /** the shipped methodwraps.txt parses */
    void realMethodWrapsLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.addWrapRules(Files.readAllLines(Path.of("rules/methodwraps.txt")));
        String[] rule = r.bareWraps.get("net/minecraft/world/effect/MobEffect");
        check(rule != null && rule[0].equals("net/minecraft/core/Holder")
                && rule[1].equals("net/minecraft/core/registries/BuiltInRegistries")
                && rule[2].equals("MOB_EFFECT"),
                "rules/methodwraps.txt parses with the MobEffect -> MOB_EFFECT registry bridge");
        Rewriter full = new Rewriter();
        full.loadRules(Path.of("rules"));
        check(full.bareWraps.size() >= 1, "full rules load includes wraps");
    }

    /** phase 5.12: the "*" owner fallback for memberretargets (javac emits the
     *  RECEIVER'S static type as owner - OverencumberedEffect calls the inherited
     *  MobEffect.addAttributeModifier) */
    void starOwnerRetarget() {
        Rewriter r = new Rewriter();
        String desc = "(Lnet/minecraft/world/entity/ai/attributes/Attribute;Ljava/lang/String;"
                + "DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Lnet/minecraft/world/effect/MobEffect;";
        String shimDesc = "(Lnet/minecraft/world/effect/MobEffect;Lnet/minecraft/core/Holder;Ljava/lang/String;"
                + "DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Lnet/minecraft/world/effect/MobEffect;";
        r.memberRetargets.put("*|" + desc + "|addAttributeModifier",
                new String[]{"@SHIM@/MobEffectCompat", shimDesc, "static"});
        ClassNode cn = new ClassNode();
        cn.name = "test/SomeEffect";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "net/minecraft/world/effect/MobEffect";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mn.maxStack = 6;
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/world/effect/MobEffect", "<init>", "()V", false));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // the attribute arg
        mn.instructions.add(new LdcInsnNode("uuid"));
        mn.instructions.add(new InsnNode(Opcodes.DCONST_0));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // operation
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "test/SomeEffect", "addAttributeModifier", desc, false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(mn);
        r.shimRoot = "com/modbridge/shim/testmod";
        r.shimSimpleNames.put("MobEffectCompat",
                "com/modbridge/shim/base/MobEffectCompat");
        r.applyMemberRenames(cn);
        MethodInsnNode call = first(mn, MethodInsnNode.class,
                x -> x.name.equals("addAttributeModifier"));
        check(call != null && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals("com/modbridge/shim/testmod/MobEffectCompat")
                && call.desc.equals(shimDesc),
                "star-owner retarget: subclass receiver -> per-mod shim static");
    }

    /** the shipped phase-5.12 rule lines must parse with keys in owner|desc|name
     *  order (the swapped-order lesson: a name-in-desc-position key loads fine and
     *  silently never fires) */
    void realPhase512RulesLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        String addAttrDesc = "(Lnet/minecraft/world/entity/ai/attributes/Attribute;Ljava/lang/String;"
                + "DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Lnet/minecraft/world/effect/MobEffect;";
        check(r.memberRetargets.containsKey("*|" + addAttrDesc + "|addAttributeModifier"),
                "rules: star addAttributeModifier retarget present (desc before name)");
        String[] attr = r.memberRetargets.get(
                "net/minecraft/world/entity/ai/attributes/Attributes|Lnet/minecraft/world/entity/ai/attributes/Attribute;|MOVEMENT_SPEED");
        check(attr != null && attr[1].equals("Lnet/minecraft/core/Holder;")
                        && attr[2].equals("MOVEMENT_SPEED"),
                "rules: Attributes.MOVEMENT_SPEED field desc retarget present");
        check(r.memberRetargets.containsKey(
                "net/mehvahdjukaar/moonlight/api/platform/forge/ForgeHelperImpl|(Lnet/minecraft/world/entity/LivingEntity;)D|getReachDistance"),
                "rules: getReachDistance static retarget present (desc before name)");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/platform/forge/ForgeHelperImpl|(Lnet/minecraft/world/entity/LivingEntity;)D|getReachDistance"),
                "rules: getReachDistance body drop present (owner|desc|name)");
        check(r.remapType("net/minecraftforge/common/extensions/IForgeMenuType")
                        .equals("net/neoforged/neoforge/common/extensions/IMenuTypeExtension"),
                "rules: IForgeMenuType -> IMenuTypeExtension typerename present");
        check(r.memberDescRenames.containsKey(
                "*|getAttributeValue|(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D"),
                "rules: * getAttributeValue desc rewrite present");
        check(r.memberRenames.get(
                "net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation|Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;|MULTIPLY_TOTAL")
                .equals("ADD_MULTIPLIED_TOTAL"),
                "rules: Operation.MULTIPLY_TOTAL = ADD_MULTIPLIED_TOTAL enum-constant rename");
        check(r.memberRenames.get(
                "net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation|Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;|ADDITION")
                .equals("ADD_VALUE"),
                "rules: Operation.ADDITION = ADD_VALUE enum-constant rename");
    }

    String applySuperSwapIn(Rewriter r, String className, String superName) {
        return r.applySuperSwap(className, superName);
    }

    /** the shipped memberretargets.txt must parse and hold the two static shapes */
    void realMemberRetargetsLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        // count-independent: the file grows with every lane; assert the SENTINEL rules
        check(r.memberRetargets.size() >= 14, "rules/memberretargets.txt loads (>=14 rules)");
        check(r.memberRetargets.containsKey(
                "net/minecraft/util/ExtraCodecs|Lcom/mojang/serialization/Codec;|COMPONENT"),
                "rules: ExtraCodecs.COMPONENT field retarget present");
        String[] val = r.memberRetargets.get(
                "com/mojang/serialization/DataResult|(ZLjava/util/function/Consumer;)Ljava/lang/Object;|getOrThrow");
        check(val != null && val[0].equals("@SHIM@/DataResultCompat") && "static".equals(val[2]),
                "static-kind getOrThrow rule present with @SHIM@ target");
        check(r.memberRetargets.containsKey(
                "com/mojang/serialization/DataResult|()Lcom/mojang/datafixers/util/Either;|get"),
                "static-kind get() rule present");
        // the third shape rides the typerename, not a memberretarget rule
        check(r.remapType("com/mojang/serialization/DataResult$PartialResult")
                        .equals("com/mojang/serialization/DataResult$Error"),
                "PartialResult -> Error typerename present (desc/link-level drift)");
    }

    /** rules/methoddrops.txt must parse and hold the two ConfigSpecWrapper drops */
    void realMethodDropsLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.addMethodDrops(Files.readAllLines(Path.of("rules/methoddrops.txt")));
        check(r.methodDrops.size() >= 3, "rules/methoddrops.txt parses (>=3 rules)");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/platform/configs/forge/ConfigSpecWrapper|()V|register"),
                "register() drop present");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/platform/configs/forge/ConfigSpecWrapper|()V|loadFromFile"),
                "loadFromFile() drop present");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/resources/recipe/BlockTypeSwapIngredient|()V|init"),
                "block_type_swap init() drop present (dead-path switch)");
    }

    // ------------------------------------------- interface call-site repair pass

    /** host with one call site per drifted shape against DFU types: DataResult is a
     *  21.1 interface (inventoried), Pair stayed a class (not inventoried) */
    ClassNode driftHost() {
        ClassNode cn = new ClassNode();
        cn.name = "test/Drift";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "sites", "(Lcom/mojang/serialization/DataResult;)V", null, null);
        // 0: invokestatic DataResult.success (itf=false) — the live-boot ICCE
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/mojang/serialization/DataResult", "success",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        // 1: already-repaired static (itf=true) — must stay untouched
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/mojang/serialization/DataResult", "error",
                "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/DataResult;", true));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        // 2: invokevirtual DataResult.map (itf=false) — "Found interface, but class"
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult", "map",
                "(Ljava/util/function/Function;)Lcom/mojang/serialization/DataResult;", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        // 3: invokevirtual DataResult.equals — Object member via interface owner
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/mojang/serialization/DataResult", "equals",
                "(Ljava/lang/Object;)Z", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        // 4: invokestatic Pair.of — owner stayed a class, must stay untouched
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/mojang/datafixers/util/Pair", "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;", false));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.maxStack = 4;
        cn.methods.add(mn);
        return cn;
    }

    void interfaceCallSites() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules")); // real inventory: DataResult in, Pair out
        ClassNode cn = driftHost();
        r.repairInterfaceCallSites(cn);
        MethodNode mn = cn.methods.get(0);
        MethodInsnNode staticSuccess = first(mn, MethodInsnNode.class,
                m -> "success".equals(m.name));
        check(staticSuccess != null && staticSuccess.getOpcode() == Opcodes.INVOKESTATIC && staticSuccess.itf,
                "invokestatic on inventoried interface -> InterfaceMethodref (itf=true)");
        MethodInsnNode errorItf = first(mn, MethodInsnNode.class, m -> "error".equals(m.name));
        check(errorItf != null && errorItf.getOpcode() == Opcodes.INVOKESTATIC && errorItf.itf,
                "already-interface static site left untouched");
        MethodInsnNode map = first(mn, MethodInsnNode.class, m -> "map".equals(m.name));
        check(map != null && map.getOpcode() == Opcodes.INVOKEINTERFACE && map.itf,
                "invokevirtual on interface member -> invokeinterface + itf=true");
        MethodInsnNode equals = first(mn, MethodInsnNode.class, m -> "equals".equals(m.name));
        check(equals != null && "java/lang/Object".equals(equals.owner)
                        && equals.getOpcode() == Opcodes.INVOKEVIRTUAL && !equals.itf,
                "Object member named through interface owner -> java/lang/Object owner");
        MethodInsnNode pairOf = first(mn, MethodInsnNode.class, m -> "of".equals(m.name));
        check(pairOf != null && pairOf.getOpcode() == Opcodes.INVOKESTATIC && !pairOf.itf,
                "non-inventoried owner (Pair, still a class) untouched");
        check(r.interfaceRepairsApplied == 3, "repair counter = 3 (static, virtual, object-member)");
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        check(cw.toByteArray().length > 0, "repaired class serializes without error");

        // empty inventory = pass disabled (agent bundles without the inventory)
        Rewriter bare = new Rewriter();
        ClassNode cn2 = driftHost();
        bare.repairInterfaceCallSites(cn2);
        check(bare.interfaceRepairsApplied == 0, "empty inventory: pass is a no-op");
    }

    /** the CLI rule loader tolerates a rules dir without interfaces-1211.txt */
    void interfaceInventoryMissingFileTolerated() throws IOException {
        Path tmp = Files.createTempDirectory("mb-rules");
        try {
            Rewriter r = new Rewriter();
            r.loadRules(tmp);
            check(r.interfaces1211.isEmpty(), "missing interfaces-1211.txt parses as empty");
            ClassNode cn = driftHost();
            r.repairInterfaceCallSites(cn);
            check(r.interfaceRepairsApplied == 0,
                    "missing inventory: drifted sites left untouched (pass disabled)");
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    void methodDrops() throws IOException {
        Rewriter r = new Rewriter();
        String owner = "test/Dropped";
        r.addMethodDrops(List.of(
                owner + "|()V|voidOne",
                owner + "|()Ljava/lang/String;|stringOne",
                owner + "|()D|doubleOne",
                owner + "|(Ljava/lang/String;)V|initGuard"));

        ClassNode cn = new ClassNode();
        cn.name = owner;
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";

        MethodNode voidOne = new MethodNode(Opcodes.ACC_PUBLIC, "voidOne", "()V", null, null);
        voidOne.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(voidOne);

        MethodNode stringOne = new MethodNode(Opcodes.ACC_PUBLIC, "stringOne",
                "()Ljava/lang/String;", null, null);
        stringOne.instructions.add(new LdcInsnNode("real value"));
        stringOne.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(stringOne);

        MethodNode doubleOne = new MethodNode(Opcodes.ACC_PUBLIC, "doubleOne", "()D", null, null);
        doubleOne.instructions.add(new InsnNode(Opcodes.DCONST_1));
        doubleOne.instructions.add(new InsnNode(Opcodes.DRETURN));
        cn.methods.add(doubleOne);

        // the pass must refuse to drop a constructor (super-call invariant)
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(ctor);

        r.applyMethodDrops(cn);
        check(r.methodDropsApplied == 3, "three non-ctor bodies dropped");
        check(voidOne.instructions.size() == 1 && voidOne.instructions.get(0).getOpcode() == Opcodes.RETURN,
                "void drop = RETURN only");
        check(stringOne.instructions.size() == 2
                && stringOne.instructions.get(0).getOpcode() == Opcodes.ACONST_NULL
                && stringOne.instructions.get(1).getOpcode() == Opcodes.ARETURN,
                "object drop = ACONST_NULL + ARETURN");
        check(doubleOne.instructions.size() == 2
                && doubleOne.instructions.get(0).getOpcode() == Opcodes.DCONST_0
                && doubleOne.instructions.get(1).getOpcode() == Opcodes.DRETURN,
                "double drop = DCONST_0 + DRETURN");
        check(ctor.instructions.size() == 3, "constructor body untouched");

        try {
            Rewriter strict = new Rewriter();
            strict.addMethodDrops(List.of(owner + "|(Ljava/lang/String;)V|<init>"));
            ClassNode victim = new ClassNode();
            victim.name = owner;
            victim.version = Opcodes.V17;
            victim.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
            victim.superName = "java/lang/Object";
            MethodNode ctor2 = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
            ctor2.instructions.add(new InsnNode(Opcodes.RETURN));
            victim.methods.add(ctor2);
            strict.applyMethodDrops(victim);
            check(false, "ctor drop rejected");
        } catch (IllegalStateException expected) {
            check(true, "ctor drop rejected");
        }

        // boolean (int-sort) return: ICONST_0 + IRETURN
        Rewriter rb = new Rewriter();
        rb.addMethodDrops(List.of(owner + "|()Z|boolOne"));
        ClassNode cb = new ClassNode();
        cb.name = owner;
        cb.version = Opcodes.V17;
        cb.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cb.superName = "java/lang/Object";
        MethodNode boolOne = new MethodNode(Opcodes.ACC_PUBLIC, "boolOne", "()Z", null, null);
        boolOne.instructions.add(new InsnNode(Opcodes.ICONST_1));
        boolOne.instructions.add(new InsnNode(Opcodes.IRETURN));
        cb.methods.add(boolOne);
        rb.applyMethodDrops(cb);
        check(boolOne.instructions.size() == 2
                && boolOne.instructions.get(0).getOpcode() == Opcodes.ICONST_0
                && boolOne.instructions.get(1).getOpcode() == Opcodes.IRETURN,
                "boolean drop = ICONST_0 + IRETURN");
    }

    // ---- tiny inspection helpers over the ASM tree ----

    interface Pred<T> { boolean test(T t); }

    static <T extends AbstractInsnNode> int count(MethodNode mn, Class<T> type, Pred<T> p) {
        int c = 0;
        for (AbstractInsnNode n : mn.instructions) {
            if (type.isInstance(n) && p.test(type.cast(n))) c++;
        }
        return c;
    }

    static int countInsns(MethodNode mn, Pred<AbstractInsnNode> p) {
        int c = 0;
        for (AbstractInsnNode n : mn.instructions) {
            if (p.test(n)) c++;
        }
        return c;
    }

    static <T extends AbstractInsnNode> T first(MethodNode mn, Class<T> type, Pred<T> p) {
        for (AbstractInsnNode n : mn.instructions) {
            if (type.isInstance(n) && p.test(type.cast(n))) return type.cast(n);
        }
        return null;
    }

    static int indexOf(MethodNode mn, AbstractInsnNode node) {
        int i = 0;
        for (AbstractInsnNode n : mn.instructions) {
            if (n == node) return i;
            i++;
        }
        return -1;
    }
}
