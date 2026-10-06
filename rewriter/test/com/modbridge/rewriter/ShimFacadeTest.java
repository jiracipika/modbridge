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

import net.minecraft.world.level.storage.loot.LootSerializer;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainerSerializer;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunctionSerializer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/**
 * Pure-logic tests for the phase-5 capability facade + the loot-lane serializer
 * shims (scripts/test-shims.sh):
 * 1. shim runtime semantics (LazyOptional laziness/invalidation, CapabilityManager
 *    token identity, fail-soft reflection),
 * 2. DESCRIPTOR EXACTNESS — every shim member the translated mods call must exist
 *    with the exact erased descriptor the 1.20.1 production call sites carry after
 *    the type rules run (measured inventory: trial 002 phase 5 + the loot lane),
 * 3. rules-level: the real rules/ retarget ToolAction->ItemAbility and map the
 *    removed loot-Serializer world to the per-mod shims; load all shims;
 *    injectShims seeds an entry map without clobbering existing classes.
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
        lootShimExactness();
        conditionShimExactness();
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

        // eager-config slice: the ModConfigBridge surface the ctor-retarget rule emits
        // (erased desc must equal rules/ctorretargets.txt's newMethodDesc exactly)
        Class<?> bridge = net.neoforged.fml.config.ModConfigBridge.class;
        Method ral = methodNamed(bridge, "registerAndLoad", 4);
        check(ral.getReturnType() == net.neoforged.fml.config.ModConfig.class,
                "ModConfigBridge.registerAndLoad returns the real 21.1 ModConfig");
        check(net.neoforged.fml.config.ModConfigBridge.class.getField("INSTANCE")
                        .getType() == bridge,
                "ModConfigBridge.INSTANCE: public receiver field for the GETSTATIC rewrite");
        check(desc(bridge, "registerAndLoad").equals(
                        "(Lnet/neoforged/fml/config/ModConfig$Type;Lnet/neoforged/fml/config/IConfigSpec;"
                                + "Lnet/neoforged/fml/ModContainer;Ljava/lang/String;)"
                                + "Lnet/neoforged/fml/config/ModConfig;"),
                "ModConfigBridge.registerAndLoad desc == the retarget rule's newMethodDesc");

        // compile-time platform-free: check the base by name (runtime resolution happens
        // under test-shims.sh's platform classpath)
        check(net.neoforged.neoforge.event.AttachCapabilitiesEvent.class.getSuperclass() != null
                && "net.neoforged.bus.api.Event".equals(
                        net.neoforged.neoforge.event.AttachCapabilitiesEvent.class.getSuperclass().getName()),
                "AttachCapabilitiesEvent: extends the platform Event base");
    }

    // ---------------------------------------------------------- loot-lane shims (phase 5.5 follow-up)

    /**
     * The loot lane's measured surface: 1.20.1's Gson loot Serializer world was
     * removed wholesale in 1.21.1 (type holders became final MapCodec records).
     * Translated implementers keep their compiled bridge shapes (m_6170_/m_7561_
     * renamed to serialize/deserialize), the type-holder shims keep the 1.20.1
     * ctor surface, and their codec() carries the ERASED 21.1 record accessor
     * shape so vanilla's loot-JSON dispatch resolves against the shim values.
     */
    void lootShimExactness() throws Exception {
        check(desc(LootSerializer.class, "serialize")
                .equals("(Lcom/google/gson/JsonObject;Ljava/lang/Object;Lcom/google/gson/JsonSerializationContext;)V"),
                "LootSerializer.serialize: erased (JsonObject,T,JsonSerializationContext)V (m_6170_ bridge shape)");
        check(desc(LootSerializer.class, "deserialize")
                .equals("(Lcom/google/gson/JsonObject;Lcom/google/gson/JsonDeserializationContext;)Ljava/lang/Object;"),
                "LootSerializer.deserialize: erased (JsonObject,JsonDeserializationContext)Object (m_7561_ bridge shape)");

        check(LootPoolSingletonContainerSerializer.class.isInterface() == false
                && LootSerializer.class.isAssignableFrom(LootPoolSingletonContainerSerializer.class),
                "LootPoolSingletonContainerSerializer: LootSerializer implementer (subclass stays assignable)");
        check(LootPoolSingletonContainerSerializer.class.getTypeParameters()[0].getBounds()[0].getTypeName()
                .equals("net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer"),
                "LootPoolSingletonContainerSerializer: <T extends LootPoolSingletonContainer> bound kept");
        check(LootSerializer.class.isAssignableFrom(LootItemConditionalFunctionSerializer.class),
                "LootItemConditionalFunctionSerializer: LootSerializer implementer (subclass stays assignable)");
        check(LootItemConditionalFunctionSerializer.class.getTypeParameters()[0].getBounds()[0].getTypeName()
                .equals("net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction"),
                "LootItemConditionalFunctionSerializer: <T extends LootItemConditionalFunction> bound kept");

        // the type-holder shims keep the 1.20.1 SerializerType ctor surface; classpath
        // order puts build/shimtest first, so these must resolve to the SHIMS (the real
        // 21.1 holders are final records with a MapCodec ctor) — guard that:
        for (Class<?> holder : new Class<?>[] {LootItemConditionType.class, LootPoolEntryType.class,
                LootItemFunctionType.class}) {
            check(holder.getRecordComponents() == null, holder.getSimpleName() + ": shim loaded (not the 21.1 record)");
            check(holder.getConstructor(LootSerializer.class).getParameterTypes()[0] == LootSerializer.class,
                    holder.getSimpleName() + ".<init>(LootSerializer): 1.20.1 ctor surface");
            check(desc(holder, "codec").equals("()Lcom/mojang/serialization/MapCodec;"),
                    holder.getSimpleName() + ".codec(): erased 21.1 record accessor shape");
            check(desc(holder, "getSerializer").equals("()Lnet/minecraft/world/level/storage/loot/LootSerializer;"),
                    holder.getSimpleName() + ".getSerializer(): SerializerType accessor surface");
        }
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

    // ------------------------------------------------- condition-lane shims (phase 5.6 follow-up)

    /**
     * The conditions lane's measured surface: Forge 1.20.1's IConditionSerializer
     * (Gson read/write/getID) is gone on 21.1 even after the package rename, and
     * the real 21.1 CraftingHelper lost every member the mods call. The shim keeps
     * the 1.20.1 serializer surface (the translated implementer's ICondition-erased
     * bridges match exactly) and a register()-into-inert-registry + working
     * getCondition bridge (ICondition.CODEC dispatch for known types).
     */
    void conditionShimExactness() throws Exception {
        Class<?> ser = Class.forName("net.neoforged.neoforge.common.conditions.IConditionSerializer");
        check(ser.isInterface(), "IConditionSerializer shim: interface");
        check(desc(ser, "write").equals("(Lcom/google/gson/JsonObject;Lnet/neoforged/neoforge/common/conditions/ICondition;)V"),
                "IConditionSerializer.write: erased <T extends ICondition> bridge shape");
        check(desc(ser, "read").equals("(Lcom/google/gson/JsonObject;)Lnet/neoforged/neoforge/common/conditions/ICondition;"),
                "IConditionSerializer.read: erased bridge return");
        check(desc(ser, "getID").equals("()Lnet/minecraft/resources/ResourceLocation;"),
                "IConditionSerializer.getID: 1.20.1 surface");
        check(methodNamed(ser, "getJson", 1).getParameterTypes()[0]
                == net.neoforged.neoforge.common.conditions.ICondition.class,
                "IConditionSerializer.getJson: default surface parity (erased to the ICondition bound)");

        Class<?> ch = Class.forName("net.neoforged.neoforge.common.crafting.CraftingHelper");
        check(desc(ch, "register").equals("(Lnet/neoforged/neoforge/common/conditions/IConditionSerializer;)Lnet/neoforged/neoforge/common/conditions/IConditionSerializer;"),
                "CraftingHelper.register: 1.20.1 condition overload shape (the boot-wall member)");
        check(desc(ch, "getCondition").equals("(Lcom/google/gson/JsonObject;)Lnet/neoforged/neoforge/common/conditions/ICondition;"),
                "CraftingHelper.getCondition: 1.20.1 shape");

        // runtime semantics: getCondition's loud contract. The FAITHFUL half (known
        // types parse via ICondition.CODEC) needs NeoForge's registry bootstrap — it
        // is live-boot territory; offline we can only prove (1) class-init is safe
        // (no ICondition.CODEC dependency until getCondition is CALLED) and (2) a
        // translated condition type fails LOUDLY naming the id (true offline AND at
        // boot: translated types have no codec registered).
        Object unusedInit = ch.getDeclaredMethod("registeredConditionSerializers").invoke(null);
        check(unusedInit != null, "CraftingHelper: class-init safe offline (inert registry only)");
        com.google.gson.JsonObject unknown = new com.google.gson.JsonObject();
        unknown.addProperty("type", "moonlight:some_translated_condition");
        try {
            ch.getDeclaredMethod("getCondition", com.google.gson.JsonObject.class).invoke(null, unknown);
            check(false, "getCondition: translated-type JSON fails loudly");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(e.getCause() instanceof IllegalStateException
                    && e.getCause().getMessage().contains("moonlight:some_translated_condition"),
                    "getCondition: translated-type JSON fails loudly with the condition id");
        }

        // runtime semantics: register() keeps the return-value contract + stores by id.
        java.util.Map<?, ?> before = (java.util.Map<?, ?>) ch.getDeclaredMethod("registeredConditionSerializers")
                .invoke(null);
        int sizeBefore = before.size();
        Object serializer = java.lang.reflect.Proxy.newProxyInstance(ser.getClassLoader(), new Class<?>[] {ser},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        // getID returns null on purpose: constructing a real
                        // ResourceLocation triggers the vanilla static-init cascade
                        // (DFU/fastutil/netty) which only exists in a full server env;
                        // the shim only uses the id as an inert map key.
                        case "getID": return null;
                        case "write": return null;
                        case "read": return null;
                        case "getJson": return new com.google.gson.JsonObject();
                        default: return method.getDefaultValue();
                    }
                });
        Object returned = ch.getDeclaredMethod("register", ser).invoke(null, serializer);
        check(returned == serializer, "CraftingHelper.register: returns the serializer (1.20.1 contract)");
        java.util.Map<?, ?> after = (java.util.Map<?, ?>) ch.getDeclaredMethod("registeredConditionSerializers")
                .invoke(null);
        check(after.size() == sizeBefore + 1, "CraftingHelper.register: stored in the inert registry");
    }

    // ------------------------------------------------------- rules-level + injection

    void rulesAndInjection() throws Exception {
        Rewriter r = new Rewriter();
        r.loadRules(Path.of("rules"));
        check(r.shims.size() == 26, "rules/shims: manifest loads 26 shim classes (incl. ForgeCapabilities tokens, FMLJavaModLoadingContext, ModConfigBridge, the 8 loot-lane classes, the 2 condition-lane classes)");
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
        // loot lane: the removed Gson serializer world resolves to the per-mod shims
        check(r.remapType("net/minecraft/world/level/storage/loot/Serializer")
                        .equals("com/modbridge/shim/testmod/LootSerializer"),
                "rules: loot Serializer -> per-mod LootSerializer shim");
        check(r.remapType("net/minecraft/world/level/storage/loot/entries/LootPoolSingletonContainer$Serializer")
                        .equals("com/modbridge/shim/testmod/LootPoolSingletonContainerSerializer"),
                "rules: LootPoolSingletonContainer$Serializer -> per-mod superclass shim");
        check(r.remapType("net/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction$Serializer")
                        .equals("com/modbridge/shim/testmod/LootItemConditionalFunctionSerializer"),
                "rules: LootItemConditionalFunction$Serializer -> per-mod superclass shim");
        check(r.remapType("net/minecraft/world/level/storage/loot/predicates/LootItemConditionType")
                        .equals("com/modbridge/shim/testmod/LootItemConditionType"),
                "rules: LootItemConditionType -> per-mod type-holder shim");
        check(r.remapType("net/minecraft/world/level/storage/loot/entries/LootPoolEntryType")
                        .equals("com/modbridge/shim/testmod/LootPoolEntryType"),
                "rules: LootPoolEntryType -> per-mod type-holder shim");
        check(r.remapType("net/minecraft/world/level/storage/loot/functions/LootItemFunctionType")
                        .equals("com/modbridge/shim/testmod/LootItemFunctionType"),
                "rules: LootItemFunctionType -> per-mod type-holder shim");
        check(r.remapDesc("(Lnet/minecraft/world/level/storage/loot/Serializer;)V")
                        .equals("(Lcom/modbridge/shim/testmod/LootSerializer;)V"),
                "rules: loot ctor desc remap (implementers keep the erased bridge shapes)");
        // conditions lane: the phantom-renamed serializer family resolves to the per-mod shims
        check(r.remapType("net/minecraftforge/common/crafting/conditions/IConditionSerializer")
                        .equals("com/modbridge/shim/testmod/IConditionSerializer"),
                "rules: IConditionSerializer -> per-mod shim (no more package-rename phantom)");
        check(r.remapType("net/minecraftforge/common/crafting/CraftingHelper")
                        .equals("com/modbridge/shim/testmod/CraftingHelper"),
                "rules: CraftingHelper -> per-mod shim (real 21.1 CraftingHelper untouched for platform code)");
        check(r.remapType("net/minecraftforge/common/crafting/conditions/ICondition")
                        .equals("net/neoforged/neoforge/common/conditions/ICondition"),
                "rules: ICondition still maps to the real 21.1 interface (not the shim)");
        check(r.remapDesc("(Lnet/minecraftforge/common/crafting/conditions/IConditionSerializer;)Lnet/minecraftforge/common/crafting/conditions/IConditionSerializer;")
                        .equals("(Lcom/modbridge/shim/testmod/IConditionSerializer;)Lcom/modbridge/shim/testmod/IConditionSerializer;"),
                "rules: CraftingHelper.register desc remaps whole (the boot-wall constant)");
        check(r.memberRenames.containsKey("net/minecraftforge/event/level/BlockEvent$BlockToolModificationEvent|()Lnet/minecraftforge/common/ToolAction;|getToolAction"),
                "rules: getToolAction=getItemAbility memberrename present");
        check(r.classKnown("com/modbridge/shim/testmod/LazyOptional"),
                "classKnown(): per-mod shim names vouch as known (mixin eager-ref check)");

        Map<String, byte[]> entries = new HashMap<>();
        entries.put("com/modbridge/shim/testmod/LazyOptional.class", new byte[]{1});
        r.injectShims(entries);
        check(entries.size() == 26, "injectShims(): all 26 shims present under the per-mod package");
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
