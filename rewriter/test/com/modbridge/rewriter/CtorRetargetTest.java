package com.modbridge.rewriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * ConfigTracker.registerConfig retarget that unblocks moonlight's ConfigSpecWrapper
 * (trial 002 phase 5.5). Covers the straight-line site, frames inside the ctor-arg
 * region (a branch among the args names the uninitialized ref in two NEW+DUP slots,
 * which collapse to one receiver entry), the nested-new scan-stop, no-rule ctors left
 * untouched, and rule parsing against the shipped rule file.
 */
public final class CtorRetargetTest {

    static final String OLD_OWNER = "net/minecraftforge/fml/config/ModConfig";
    static final String NEW_OWNER = "net/neoforged/fml/config/ConfigTracker";
    static final String RECEIVER_DESC = "L" + NEW_OWNER + ";";
    static final String OLD_DESC =
            "(Lnet/minecraftforge/fml/config/ModConfig$Type;Lnet/minecraftforge/fml/config/IConfigSpec;"
                    + "Lnet/minecraftforge/fml/ModContainer;Ljava/lang/String;)V";
    static final String NEW_DESC =
            "(Lnet/minecraftforge/fml/config/ModConfig$Type;Lnet/minecraftforge/fml/config/IConfigSpec;"
                    + "Lnet/minecraftforge/fml/ModContainer;Ljava/lang/String;)Lnet/neoforged/fml/config/ModConfig;";

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
        realRuleFileLoads();
        methodDrops();
        realMethodDropsLoad();
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
        check(r.ctorRetargets.get(OLD_OWNER + "|" + OLD_DESC) != null,
                "ModConfig 4-arg ctor rule present");
        check(r.ctorRetargetOwners.contains(OLD_OWNER), "owner pre-scan index populated");
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
