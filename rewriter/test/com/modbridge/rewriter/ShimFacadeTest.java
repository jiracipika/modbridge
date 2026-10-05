package com.modbridge.rewriter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.neoforged.neoforge.common.capabilities.Capability;
import net.neoforged.neoforge.common.capabilities.CapabilityManager;
import net.neoforged.neoforge.common.capabilities.CapabilityToken;
import net.neoforged.neoforge.common.capabilities.ForgeCapabilities;
import net.neoforged.neoforge.common.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.common.util.LazyOptional;
import net.neoforged.neoforge.common.util.NonNullConsumer;
import net.neoforged.neoforge.common.util.NonNullSupplier;

/**
 * Pure-logic tests for the phase-5 capability facade (scripts/test-shims.sh):
 * 1. shim runtime semantics (LazyOptional laziness/invalidation, CapabilityManager
 *    token identity, fail-soft reflection),
 * 2. DESCRIPTOR EXACTNESS — every shim member the translated mods call must exist
 *    with the exact erased descriptor the 1.20.1 production call sites carry after
 *    the type rules run (measured inventory: trial 002 phase 5),
 * 3. rules-level: the real rules/ retarget ToolAction->ItemAbility and load all
 *    shims; injectShims seeds an entry map without clobbering existing classes.
 */
public final class ShimFacadeTest {

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) throws Exception {
        ShimFacadeTest t = new ShimFacadeTest();
        t.run();
        System.out.println("all " + t.passed + " checks passed");
    }

    void run() throws Exception {
        runtimeSemantics();
        descriptorExactness();
        rulesAndInjection();
    }

    // ------------------------------------------------------------- runtime semantics

    void runtimeSemantics() {
        int[] resolves = {0};
        NonNullSupplier<String> counting = () -> {
            resolves[0]++;
            return "v";
        };
        LazyOptional<String> lo = LazyOptional.of(counting);
        check(lo.isPresent(), "of(): present before resolution");
        check(resolves[0] == 0, "of(): lazy (supplier not called)");
        check("v".equals(lo.orElse("x")), "orElse(): resolves through");
        check(resolves[0] == 1, "orElse(): resolved once");
        check("v".equals(lo.orElse("x")), "orElse(): second read uses cache");
        check(resolves[0] == 1, "orElse(): supplier called exactly once (cached)");
        check(Optional.of("v").equals(lo.resolve()), "resolve(): Optional of value");
        check("v".equals(lo.orElseGet(() -> "g")), "orElseGet(): cached value wins");
        check("v".equals(lo.orElseThrow(() -> new IllegalStateException("no"))), "orElseThrow(): value path");
        LazyOptional<String> casted = lo.cast();
        check("v".equals(casted.orElse("x")), "cast(): self-returning view");
        java.util.List<String> got = new java.util.ArrayList<>();
        lo.ifPresent(got::add);
        check(got.equals(java.util.List.of("v")), "ifPresent(): consumer ran");

        lo.invalidate();
        check(!lo.isPresent(), "invalidate(): no longer present");
        check("x".equals(lo.orElse("x")), "invalidate(): orElse falls back");
        check(Optional.empty().equals(lo.resolve()), "invalidate(): resolve() empty");
        check(resolves[0] == 1, "invalidate(): no new resolution");

        LazyOptional<String> empty = LazyOptional.empty();
        check(!empty.isPresent(), "empty(): not present");
        check("f".equals(empty.orElse("f")), "empty(): orElse fallback");
        check(Optional.empty().equals(empty.resolve()), "empty(): resolve() empty");
        try {
            empty.orElseThrow(() -> new IllegalStateException("boom"));
            check(false, "empty(): orElseThrow throws");
        } catch (IllegalStateException e) {
            check("boom".equals(e.getMessage()), "empty(): orElseThrow throws supplier's exception");
        }

        CapabilityToken<String> token = new CapabilityToken<>() {
        };
        Capability<String> c1 = CapabilityManager.get(token);
        Capability<String> c2 = CapabilityManager.get(token);
        Capability<Integer> c3 = CapabilityManager.get(new CapabilityToken<>() {
        });
        check(c1 == c2, "CapabilityManager.get(): one instance per token class");
        check(c1 != (Capability<String>) (Capability<?>) c3, "CapabilityManager.get(): distinct per token class");
        check(c1.getName() != null && c1.getName().startsWith("mb-shim:"), "Capability.getName(): shim identity");
        check(Capability.orEmpty(c1, lo) == lo, "Capability.orEmpty(): passes the supplier through");
        check(ForgeCapabilities.ITEM_HANDLER != null && ForgeCapabilities.FLUID_HANDLER != null,
                "ForgeCapabilities: fields initialized");

        Method m = com.modbridge.shim.ObfuscationReflectionHelper.findMethod(String.class, "valueOf", int.class);
        check(m != null, "ObfRefl shim: findMethod hit");
        check(com.modbridge.shim.ObfuscationReflectionHelper.findMethod(String.class, "setConfigData", Object.class) == null,
                "ObfRefl shim: findMethod miss returns null (fail-soft)");
        Field f = com.modbridge.shim.ObfuscationReflectionHelper.findField(Point.class, "x");
        check(f != null && f.getName().equals("x"), "ObfRefl shim: findField hit");
        check(com.modbridge.shim.ObfuscationReflectionHelper.findField(Point.class, "nope") == null,
                "ObfRefl shim: findField miss returns null (fail-soft)");
    }

    static class Point {
        @SuppressWarnings("unused")
        int x;
    }

    // --------------------------------------------------------- descriptor exactness

    /** erased descriptors must equal what the translated 1.20.1 call sites carry */
    void descriptorExactness() throws Exception {
        Class<?> lo = LazyOptional.class;
        check(desc(lo, "of").equals("(Lnet/neoforged/neoforge/common/util/NonNullSupplier;)Lnet/neoforged/neoforge/common/util/LazyOptional;"),
                "LazyOptional.of desc");
        check(desc(lo, "empty").equals("()Lnet/neoforged/neoforge/common/util/LazyOptional;"),
                "LazyOptional.empty desc");
        check(desc(lo, "isPresent").equals("()Z"), "LazyOptional.isPresent desc");
        check(desc(lo, "ifPresent").equals("(Lnet/neoforged/neoforge/common/util/NonNullConsumer;)V"),
                "LazyOptional.ifPresent desc");
        check(desc(lo, "orElse").equals("(Ljava/lang/Object;)Ljava/lang/Object;"),
                "LazyOptional.orElse desc");
        check(desc(lo, "orElseGet").equals("(Lnet/neoforged/neoforge/common/util/NonNullSupplier;)Ljava/lang/Object;"),
                "LazyOptional.orElseGet desc");
        check(desc(lo, "orElseThrow").equals("(Lnet/neoforged/neoforge/common/util/NonNullSupplier;)Ljava/lang/Object;"),
                "LazyOptional.orElseThrow desc");
        check(desc(lo, "cast").equals("()Lnet/neoforged/neoforge/common/util/LazyOptional;"),
                "LazyOptional.cast desc");
        check(desc(lo, "invalidate").equals("()V"), "LazyOptional.invalidate desc");
        check(desc(lo, "resolve").equals("()Ljava/util/Optional;"), "LazyOptional.resolve desc");

        Class<?> cap = Capability.class;
        check(desc(cap, "getName").equals("()Ljava/lang/String;"), "Capability.getName desc");
        check(desc(cap, "orEmpty").equals("(Lnet/neoforged/neoforge/common/capabilities/Capability;Lnet/neoforged/neoforge/common/util/LazyOptional;)Lnet/neoforged/neoforge/common/util/LazyOptional;"),
                "Capability.orEmpty desc");
        check(cap.getTypeParameters().length == 1, "Capability: single type variable ( Capability<T> )");

        Class<?> mgr = CapabilityManager.class;
        check(desc(mgr, "get").equals("(Lnet/neoforged/neoforge/common/capabilities/CapabilityToken;)Lnet/neoforged/neoforge/common/capabilities/Capability;"),
                "CapabilityManager.get desc");

        Class<?> tok = CapabilityToken.class;
        check(!java.lang.reflect.Modifier.isFinal(tok.getModifiers())
                && tok.getDeclaredConstructors().length >= 1, "CapabilityToken: subclassable (anonymous tokens)");

        Method one = methodNamed(ICapabilityProvider.class, "getCapability", 1);
        check(one.getParameterTypes()[0] == Capability.class && one.getReturnType() == LazyOptional.class,
                "ICapabilityProvider.getCapability(cap): returns LazyOptional");
        Method two = methodNamed(ICapabilityProvider.class, "getCapability", 2);
        check(two.getParameterTypes()[1].getName().equals("net.minecraft.core.Direction")
                && two.getReturnType() == LazyOptional.class,
                "ICapabilityProvider.getCapability(cap, Direction): returns LazyOptional");

        check(ForgeCapabilities.class.getField("ITEM_HANDLER").getType() == Capability.class,
                "ForgeCapabilities.ITEM_HANDLER: erased Capability field type");
        check(ForgeCapabilities.class.getField("FLUID_HANDLER").getType() == Capability.class,
                "ForgeCapabilities.FLUID_HANDLER: erased Capability field type");

        check(net.neoforged.neoforge.event.AttachCapabilitiesEvent.class
                .getMethod("getObject").getReturnType() == Object.class,
                "AttachCapabilitiesEvent.getObject desc");
        Method addCap = methodNamed(net.neoforged.neoforge.event.AttachCapabilitiesEvent.class, "addCapability", 2);
        check(addCap.getParameterTypes()[0].getName().equals("net.minecraft.resources.ResourceLocation")
                && addCap.getParameterTypes()[1] == ICapabilityProvider.class,
                "AttachCapabilitiesEvent.addCapability(cap provider) shape");

        // compile-time platform-free: check the base by name (runtime resolution happens
        // under test-shims.sh's platform classpath)
        check(net.neoforged.neoforge.event.AttachCapabilitiesEvent.class.getSuperclass() != null
                && "net.neoforged.bus.api.Event".equals(
                        net.neoforged.neoforge.event.AttachCapabilitiesEvent.class.getSuperclass().getName()),
                "AttachCapabilitiesEvent: extends the platform Event base");
    }

    static Method methodNamed(Class<?> c, String name, int paramCount) throws Exception {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        throw new AssertionError("no method " + name + "/" + paramCount);
    }

    static String desc(Class<?> c, String name) throws Exception {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return typeStr(m);
        }
        throw new AssertionError("no method " + name);
    }

    static String typeStr(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(internal(p));
        sb.append(')').append(internal(m.getReturnType()));
        return sb.toString();
    }

    /** JVM descriptor form of a type (V/I/Z primitives, L...; references, [ dims) */
    static String internal(Class<?> c) {
        if (c == void.class) return "V";
        if (c == int.class) return "I";
        if (c == boolean.class) return "Z";
        if (c == long.class) return "J";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        Class<?> e = c;
        int dims = 0;
        while (e.isArray()) {
            dims++;
            e = e.getComponentType();
        }
        String base = "L" + e.getName().replace('.', '/') + ";";
        return dims == 0 ? base : "[".repeat(dims) + base;
    }

    // ------------------------------------------------------- rules-level + injection

    void rulesAndInjection() throws Exception {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        check(r.shims.size() == 12, "rules/shims: manifest loads 12 shim classes");
        check(r.remapType("net/minecraftforge/common/ToolAction").equals("net/neoforged/neoforge/common/ItemAbility"),
                "rules: ToolAction -> ItemAbility");
        check(r.remapType("net/minecraftforge/common/ToolActions").equals("net/neoforged/neoforge/common/ItemAbilities"),
                "rules: ToolActions -> ItemAbilities");
        r.initShimRoot("testmod");
        check(r.remapType("net/minecraftforge/common/util/LazyOptional").equals("com/modbridge/shim/testmod/LazyOptional"),
                "rules: LazyOptional -> @SHIM@ expanded to the per-mod package");
        check(r.remapType("net/minecraftforge/fml/util/ObfuscationReflectionHelper").equals("com/modbridge/shim/testmod/ObfuscationReflectionHelper"),
                "rules: ObfuscationReflectionHelper -> per-mod shim");
        check(r.remapType("[Lnet/minecraftforge/common/util/LazyOptional;").equals("[Lcom/modbridge/shim/testmod/LazyOptional;"),
                "rules: frame-array form [L...LazyOptional; remapped (phase-5 rewriter fix)");
        check(r.remapType("[Ljava/lang/String;").equals("[Ljava/lang/String;"),
                "rules: frame-array form leaves primitives untouched");
        check(r.memberRenames.containsKey("net/minecraftforge/event/level/BlockEvent$BlockToolModificationEvent|()Lnet/minecraftforge/common/ToolAction;|getToolAction"),
                "rules: getToolAction=getItemAbility memberrename present");
        check(r.classKnown("com/modbridge/shim/testmod/LazyOptional"),
                "classKnown(): per-mod shim names vouch as known (mixin eager-ref check)");

        Map<String, byte[]> entries = new HashMap<>();
        entries.put("com/modbridge/shim/testmod/LazyOptional.class", new byte[]{1});
        r.injectShims(entries);
        check(entries.size() == 12, "injectShims(): all 12 shims present under the per-mod package");
        check(entries.get("com/modbridge/shim/testmod/LazyOptional.class")[0] == 1,
                "injectShims(): never clobbers a class the jar already defines");
        check(!entries.containsKey("net/neoforged/neoforge/common/util/LazyOptional.class"),
                "injectShims(): nothing parked at platform package names (JPMS split-package safety)");
        byte[] cap = entries.get("com/modbridge/shim/testmod/Capability.class");
        check(cap != null && new String(cap, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("com/modbridge/shim/testmod/Capability"),
                "injectShims(): injected bytecode self-identifies under the relocated name");
    }
}
