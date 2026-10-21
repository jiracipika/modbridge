package com.modbridge.rewriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Pure-logic + link-time tests for the static-field-bridge pass
 * (rules/staticfieldbridges.txt, scripts/test-staticfieldbridge.sh, phase 5.20):
 * GETSTATIC of a field REMOVED from the 21.1 static registries
 * (BuiltInRegistries.ENCHANTMENT — the data-driven-enchantment whale) rewritten to
 * INVOKESTATIC on a shim returning the live server-side registry. Covers the
 * shipped rule file, the desc-equality frame-safety invariant against the COMPILED
 * shim (the erased return must equal the field desc or every following frame is a
 * lie), the GETSTATIC rewrite with @SHIM@ resolution, the isCurse |static
 * memberretarget over the same shim (name kept, receiver-as-first-param), frames
 * left byte-identical, and a child-classloader RUN of rewritten bytes against a
 * stub bridge (the verifier types the stack from the CP — proven live in 5.14).
 */
public final class StaticFieldBridgeTest {

    static final String OWNER = "net/minecraft/core/registries/BuiltInRegistries";
    static final String FIELD = "ENCHANTMENT";
    static final String DESC = "Lnet/minecraft/core/Registry;";
    static final String SHIM_SIMPLE = "EnchantmentRegistryCompat";

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) throws Exception {
        StaticFieldBridgeTest t = new StaticFieldBridgeTest();
        t.run();
        System.out.println("all " + t.passed + " checks passed");
    }

    void run() throws Exception {
        shippedRuleLoads();
        descEqualityWithCompiledShim();
        isCurseRuleLoadsAndShapesMatchCompiledShim();
        getstaticRewrite();
        framesByteIdentical();
        nonMatchingSitesUntouched();
        isCurseStaticRetarget();
        linkTimeRun();
    }

    /** the shipped rule file parses and carries the census-backed key */
    void shippedRuleLoads() throws Exception {
        Rewriter rs = new Rewriter();
        rs.loadRules(Path.of("rules"));
        check(rs.staticFieldBridges.size() == 1, "staticfieldbridges: the shipped file has exactly 1 rule");
        String[] rule = rs.staticFieldBridges.get(OWNER + "|" + FIELD + "|" + DESC);
        check(rule != null, "rule key is the census BuiltInRegistries|ENCHANTMENT|Registry shape");
        check(rule[0].equals("@SHIM@/" + SHIM_SIMPLE) && rule[1].equals("enchantmentRegistry"),
                "rule targets @SHIM@/EnchantmentRegistryCompat.enchantmentRegistry");
    }

    /** the frame-safety invariant, against the COMPILED shim bytecode: the bridge's
     *  erased return type must be exactly the rewritten GETSTATIC's CP desc (the
     *  verifier types the pushed value from the CP; a mismatch is a VerifyError —
     *  measured live in phase 5.14 for the field-unwrap cousin of this pass).
     *  STRUCTURAL on purpose (ASM parse of the shim's own .class resource): loading
     *  the shim's vanilla parameter/return classes in-JVM triggers the clinit
     *  cascade (Enchantment clinit -> ComponentSerialization -> DFU/guava — the
     *  documented ResourceLocation-cascade gotcha, bigger here). */
    void descEqualityWithCompiledShim() throws Exception {
        String resource = "com/modbridge/shim/" + SHIM_SIMPLE + ".class";
        byte[] bytes;
        try (var in = StaticFieldBridgeTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new AssertionError("compiled shim not on the test classpath: " + resource);
            bytes = in.readAllBytes();
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode shim = new ClassNode();
        reader.accept(shim, 0);
        MethodNode reg = shim.methods.stream()
                .filter(m -> m.name.equals("enchantmentRegistry")).findFirst().orElseThrow();
        check(reg.desc.equals("()" + DESC),
                "compiled enchantmentRegistry() returns exactly the field desc " + DESC + " (got " + reg.desc + ")");
        MethodNode curse = shim.methods.stream()
                .filter(m -> m.name.equals("isCurse")).findFirst().orElseThrow();
        check(curse.desc.equals("(Lnet/minecraft/world/item/enchantment/Enchantment;)Z"),
                "compiled isCurse desc is the receiver-first-param widened shape");
        check((curse.access & Opcodes.ACC_STATIC) != 0 && (reg.access & Opcodes.ACC_STATIC) != 0,
                "both bridge methods are static");
    }

    /** the memberretargets.txt isCurse rule: loads, and its widened desc matches the
     *  compiled bridge's parameter list (receiver-as-first-param) */
    void isCurseRuleLoadsAndShapesMatchCompiledShim() throws Exception {
        Rewriter rs = new Rewriter();
        rs.loadRules(Path.of("rules"));
        String[] rule = rs.memberRetargets.get(
                "net/minecraft/world/item/enchantment/Enchantment|()Z|isCurse");
        check(rule != null, "isCurse |static rule is present in the shipped memberretargets");
        check("static".equals(rule[2]), "isCurse rule kind is |static");
        check(rule[1].equals("(Lnet/minecraft/world/item/enchantment/Enchantment;)Z"),
                "isCurse rule widened desc = receiver-first-param");
    }

    /** GETSTATIC rewrite through transform(): opcode/owner/name/desc + @SHIM@ root */
    void getstaticRewrite() throws Exception {
        Rewriter rm = new Rewriter();
        rm.loadRules(Path.of("rules"));
        rm.initShimRoot("supplementaries");
        ClassNode cn = siteClass((m) -> m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                OWNER, FIELD, DESC)));
        rm.transform(cn);
        MethodInsnNode call = (MethodInsnNode) cn.methods.get(0).instructions.get(0);
        check(call.getOpcode() == Opcodes.INVOKESTATIC, "GETSTATIC became INVOKESTATIC");
        check(call.owner.equals("com/modbridge/shim/supplementaries/" + SHIM_SIMPLE),
                "@SHIM@ resolved to the per-mod shim package");
        check(call.name.equals("enchantmentRegistry"), "bridge method name");
        check(call.desc.equals("()" + DESC),
                "bridge desc is ()+fieldDesc (no args, field type as return — frame-safety)");
        check(cn.methods.get(0).instructions.size() == 2, "field instruction replaced, not duplicated");
    }

    /** frames read verbatim: the pass must not touch any FullFrame even on the
     *  rewritten site (both sides of the rewrite type the slot as Registry) */
    void framesByteIdentical() throws Exception {
        Rewriter rm = new Rewriter();
        rm.loadRules(Path.of("rules"));
        rm.initShimRoot("supplementaries");
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "()Lnet/minecraft/core/Registry;", null, null);
        LabelNode l0 = new LabelNode();
        mn.instructions.add(l0);
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, FIELD, DESC));
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        FrameNode frame = new FrameNode(Opcodes.F_FULL, 0, new Object[0],
                1, new Object[] {"net/minecraft/core/Registry"});
        mn.instructions.insert(l0, frame);
        ClassNode cn = new ClassNode();
        cn.name = "test/SfbFrames";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        cn.methods.add(mn);
        int before = mn.instructions.indexOf(frame);
        rm.transform(cn);
        check(cn.methods.get(0).instructions.get(before) == frame,
                "frame node untouched at the same index");
        FrameNode after = (FrameNode) cn.methods.get(0).instructions.get(before);
        check(after.local.size() == 0 && after.stack.size() == 1
                && after.stack.get(0).equals("net/minecraft/core/Registry"),
                "frame stack still types the produced value as Registry");
    }

    /** GETSTATIC sites that don't match the rule exactly stay untouched (the
     *  ResourceKey cousin field Registries.ENCHANTMENT still exists on 21.1) */
    void nonMatchingSitesUntouched() throws Exception {
        Rewriter rm = new Rewriter();
        rm.loadRules(Path.of("rules"));
        rm.initShimRoot("supplementaries");
        ClassNode cn = siteClass((m) -> m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "net/minecraft/core/registries/Registries", FIELD, "Lnet/minecraft/resources/ResourceKey;")));
        rm.transform(cn);
        check(cn.methods.get(0).instructions.get(0) instanceof FieldInsnNode,
                "different-owner GETSTATIC untouched");
        ClassNode cn2 = siteClass((m) -> m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                OWNER, FIELD, DESC)));
        rm.transform(cn2);
        check(cn2.methods.get(0).instructions.get(0) instanceof FieldInsnNode,
                "PUTSTATIC untouched (pass is read-site only)");
    }

    /** the isCurse |static retarget through the SAME pass that ships it
     *  (applyMemberRenames): name kept, owner -> shim, receiver widened to arg 1 */
    void isCurseStaticRetarget() throws Exception {
        Rewriter rm = new Rewriter();
        rm.loadRules(Path.of("rules"));
        rm.initShimRoot("supplementaries");
        ClassNode cn = new ClassNode();
        cn.name = "test/SfbCurse";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check",
                "(Lnet/minecraft/world/item/enchantment/Enchantment;)Z", null, null);
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/item/enchantment/Enchantment", "isCurse", "()Z", false));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(mn);
        rm.transform(cn);
        MethodInsnNode call = (MethodInsnNode) cn.methods.get(0).instructions.get(1);
        check(call.getOpcode() == Opcodes.INVOKESTATIC, "isCurse call became INVOKESTATIC");
        check(call.owner.endsWith(SHIM_SIMPLE), "isCurse owner resolved to the shim");
        check(call.name.equals("isCurse"), "|static keeps the call-site name (boot-5.16 lesson)");
        check(call.desc.equals("(Lnet/minecraft/world/item/enchantment/Enchantment;)Z"),
                "isCurse widened to receiver-first-param");
    }

    /** RUN-time proof in a child classloader: rewritten GETSTATIC site executes and
     *  dispatches to the stub bridge (structural-only is not enough for a pass that
     *  exists because a field vanished — the 5.14 verifier lesson). Stub types only,
     *  no vanilla clinit cascade. */
    void linkTimeRun() throws Exception {
        Rewriter rm = new Rewriter();
        rm.addStaticFieldBridgeRules(List.of("test/StubRegistries|ENCHANTMENT|Ltest/StubRegistry;"
                + " -> test/StubBridge|enchantmentRegistry"));
        ClassNode registry = new ClassNode();
        registry.name = "test/StubRegistry";
        registry.version = Opcodes.V17;
        registry.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        registry.superName = "java/lang/Object";
        ClassNode registries = new ClassNode();
        registries.name = "test/StubRegistries";
        registries.version = Opcodes.V17;
        registries.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        registries.superName = "java/lang/Object";
        registries.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ENCHANTMENT",
                "Ltest/StubRegistry;", null, null));
        ClassNode bridge = new ClassNode();
        bridge.name = "test/StubBridge";
        bridge.version = Opcodes.V17;
        bridge.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        bridge.superName = "java/lang/Object";
        bridge.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "HIT", "Z", null, null));
        MethodNode br = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enchantmentRegistry",
                "()Ltest/StubRegistry;", null, null);
        br.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "test/StubBridge", "live",
                "()Ltest/StubRegistry;", false));
        br.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.methods.add(br);
        MethodNode brLive = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "live",
                "()Ltest/StubRegistry;", null, null);
        brLive.instructions.add(new InsnNode(Opcodes.ICONST_1));
        brLive.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, "test/StubBridge", "HIT", "Z"));
        brLive.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        brLive.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.methods.add(brLive);
        ClassNode caller = new ClassNode();
        caller.name = "test/SfbSite";
        caller.version = Opcodes.V17;
        caller.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        caller.superName = "java/lang/Object";
        MethodNode go = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "()Ltest/StubRegistry;", null, null);
        go.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "test/StubRegistries", "ENCHANTMENT", "Ltest/StubRegistry;"));
        go.instructions.add(new InsnNode(Opcodes.ARETURN));
        caller.methods.add(go);
        rm.transform(caller);
        MethodInsnNode rewritten = (MethodInsnNode) caller.methods.get(0).instructions.get(0);
        check(rewritten.getOpcode() == Opcodes.INVOKESTATIC && rewritten.owner.equals("test/StubBridge"),
                "caller rewritten to the stub bridge before loading");
        Child child = new Child();
        child.define("test.StubRegistry", toBytes(registry));
        child.define("test.StubBridge", toBytes(bridge));
        child.define("test.StubRegistries", toBytes(registries));
        Class<?> callerC = child.define("test.SfbSite", toBytes(caller));
        callerC.getMethod("go").invoke(null);
        Class<?> bridgeC = child.loadClass("test.StubBridge");
        check(bridgeC.getField("HIT").getBoolean(null),
                "rewritten site executes and dispatches to the bridge (verifier accepted)");
    }

    interface Body {
        void build(MethodNode m);
    }

    ClassNode siteClass(Body body) {
        ClassNode cn = new ClassNode();
        cn.name = "test/SfbSite";
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.superName = "java/lang/Object";
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "()" + DESC, null, null);
        body.build(mn);
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(mn);
        return cn;
    }

    static class Child extends java.net.URLClassLoader {
        Child() { super(new java.net.URL[0], StaticFieldBridgeTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }

    static byte[] toBytes(ClassNode cn) {
        // COMPUTE_MAXS: synthetic MethodNodes carry maxStack=0, which the verifier
        // reads as "operand stack overflow" on the first push
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
