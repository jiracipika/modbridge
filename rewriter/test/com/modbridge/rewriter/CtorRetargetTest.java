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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
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
        interfaceCallSites();
        interfaceInventoryMissingFileTolerated();
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
            check(RECEIVER_DESC.equals(f.stack.get(0)),
                    "first uninit slot became the receiver type");
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
        check(r.ctorRetargets.size() == 1, "rules/ctorretargets.txt parses (1 rule)");
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

    /** the shipped memberretargets.txt must parse and hold the two static shapes */
    void realMemberRetargetsLoad() throws IOException {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        check(r.memberRetargets.size() == 7, "rules/memberretargets.txt loads (3 setCanceled + 4 static-kind: 2 DFU drift + criteria register + isValidResourceLocation)");
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
        check(r.methodDrops.size() == 2, "rules/methoddrops.txt parses (2 rules)");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/platform/configs/forge/ConfigSpecWrapper|()V|register"),
                "register() drop present");
        check(r.methodDrops.contains(
                "net/mehvahdjukaar/moonlight/api/platform/configs/forge/ConfigSpecWrapper|()V|loadFromFile"),
                "loadFromFile() drop present");
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
