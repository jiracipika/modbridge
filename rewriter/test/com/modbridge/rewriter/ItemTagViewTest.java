package com.modbridge.rewriter;

import java.lang.reflect.Method;

/**
 * phase 5.13 (NBT item-tag removal wall): the shared {@code ItemStackTagCompat}
 * view, proven against the REAL 21.1 component store (scripts/test-shims.sh,
 * second JVM). The WRITE-BACK semantics are the one guarantee byte-level checks
 * cannot make (CustomData.of copies; getUnsafe is the live internal tag; the
 * 21.1 two-arg save RETURNS a new tag instead of filling the prefix).
 *
 * <p>Runs in its OWN JVM with the PLATFORM JARS FIRST on the classpath: the
 * runtime bootstrap pulls the vanilla registry cascade, which must NOT see the
 * loot-lane type-holder shims parked at vanilla package names (the main
 * ShimFacadeTest JVM keeps the opposite order so ITS loot tests observe the
 * shims - the two orderings are mutually exclusive, hence the split).
 */
public final class ItemTagViewTest {

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) throws Exception {
        ItemTagViewTest t = new ItemTagViewTest();
        t.run();
        System.out.println("all " + t.passed + " checks passed");
    }

    void run() throws Exception {
        itemTagViewShimExactness();
    }

    static byte[] classBytes(String name) throws Exception {
        try (var in = ItemTagViewTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    static boolean containsText(byte[] bytes, String needle) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return s.contains(needle);
    }

    /** Desc-exactness reflectively (cheap); then the runtime semantics. Bootstrap
     *  pulls the vanilla registry cascade (JDK-only env, a few seconds). */
    void itemTagViewShimExactness() throws Exception {
        Class<?> ivc = com.modbridge.shim.ItemStackTagCompat.class;
        check(java.lang.reflect.Modifier.isFinal(ivc.getModifiers()),
                "ItemStackTagCompat: final utility (shared-shim carrier)");
        Method getTag = ivc.getMethod("getTag", net.minecraft.world.item.ItemStack.class);
        check(getTag.getReturnType() == net.minecraft.nbt.CompoundTag.class,
                "ItemStackTagCompat.getTag: (ItemStack)CompoundTag static view");
        Method getOrCreateTag = ivc.getMethod("getOrCreateTag", net.minecraft.world.item.ItemStack.class);
        check(getOrCreateTag.getReturnType() == net.minecraft.nbt.CompoundTag.class,
                "ItemStackTagCompat.getOrCreateTag: (ItemStack)CompoundTag static view");
        check(ivc.getMethod("hasTag", net.minecraft.world.item.ItemStack.class).getReturnType() == boolean.class,
                "ItemStackTagCompat.hasTag: (ItemStack)Z static view");
        check(ivc.getMethod("setTag", net.minecraft.world.item.ItemStack.class, net.minecraft.nbt.CompoundTag.class).getReturnType() == void.class,
                "ItemStackTagCompat.setTag: (ItemStack,CompoundTag)V static view");
        Method gte = ivc.getMethod("getTagElement", net.minecraft.world.item.ItemStack.class, String.class);
        check(gte.getReturnType() == net.minecraft.nbt.CompoundTag.class,
                "ItemStackTagCompat.getTagElement: (ItemStack,String)CompoundTag static view");
        check(ivc.getMethod("getOrCreateTagElement", net.minecraft.world.item.ItemStack.class, String.class).getReturnType() == net.minecraft.nbt.CompoundTag.class,
                "ItemStackTagCompat.getOrCreateTagElement: (ItemStack,String)CompoundTag static view");
        check(ivc.getMethod("addTagElement", net.minecraft.world.item.ItemStack.class, String.class, net.minecraft.nbt.Tag.class).getReturnType() == void.class,
                "ItemStackTagCompat.addTagElement: (ItemStack,String,Tag)V static view");
        check(ivc.getMethod("save", net.minecraft.world.item.ItemStack.class, net.minecraft.nbt.CompoundTag.class).getReturnType() == net.minecraft.nbt.CompoundTag.class,
                "ItemStackTagCompat.save: (ItemStack,CompoundTag)CompoundTag static view");
        check(ivc.getMethod("of", net.minecraft.nbt.CompoundTag.class).getReturnType() == net.minecraft.world.item.ItemStack.class,
                "ItemStackTagCompat.of: (CompoundTag)ItemStack static view");
        byte[] code = classBytes("com/modbridge/shim/ItemStackTagCompat.class");
        check(containsText(code, "CUSTOM_DATA") && containsText(code, "getUnsafe"),
                "ItemStackTagCompat body: the view is the CUSTOM_DATA component (live internal tag)");

        // runtime semantics against the real component store. NB: bootStrap's last
        // step (wrapStreams) REPLACES System.out/err with vanilla logger-backed
        // streams - with the provider-less slf4j-api (log4j-core deliberately off
        // the classpath, see test-shims.sh) those writes are silently DROPPED, so
        // the whole run would "pass invisibly" (exit 0, no output, no summary).
        // Snapshot and restore OUR streams around the bootstrap.
        java.io.PrintStream ourOut = System.out;
        java.io.PrintStream ourErr = System.err;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.setOut(ourOut);
        System.setErr(ourErr);
        check(!net.minecraft.core.registries.BuiltInRegistries.REGISTRY.keySet().isEmpty(),
                "bootstrap: the vanilla registry cascade completed (Items/Blocks/CreativeModeTabs)");
        net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE);
        check(net.minecraft.nbt.CompoundTag.class.cast(getTag.invoke(null, stack)) == null,
                "getTag: null on a fresh stack (1.20.1 absent-tag convention)");
        net.minecraft.nbt.CompoundTag t = (net.minecraft.nbt.CompoundTag) getOrCreateTag.invoke(null, stack);
        t.putString("Color", "red");
        check("red".equals(((net.minecraft.nbt.CompoundTag) getOrCreateTag.invoke(null, stack)).getString("Color")),
                "getOrCreateTag: LIVE view - mutations land in the stored CUSTOM_DATA component");
        check((boolean) ivc.getMethod("hasTag", net.minecraft.world.item.ItemStack.class).invoke(null, stack),
                "hasTag: true once the custom tag exists");
        net.minecraft.nbt.CompoundTag el = (net.minecraft.nbt.CompoundTag)
                ivc.getMethod("getOrCreateTagElement", net.minecraft.world.item.ItemStack.class, String.class)
                        .invoke(null, stack, "display");
        el.putString("Lore", "hi");
        check("hi".equals(((net.minecraft.nbt.CompoundTag) gte.invoke(null, stack, "display")).getString("Lore")),
                "getOrCreateTagElement: sub-compound ATTACHED (mutating it writes through)");
        check(gte.invoke(null, stack, "nope") == null,
                "getTagElement: null when no sub-compound (1.20.1 convention)");
        net.minecraft.nbt.CompoundTag out = (net.minecraft.nbt.CompoundTag)
                ivc.getMethod("save", net.minecraft.world.item.ItemStack.class, net.minecraft.nbt.CompoundTag.class)
                        .invoke(null, stack, new net.minecraft.nbt.CompoundTag());
        check(out.contains("id") && out.contains("count") && out.contains("components"),
                "save: 21.1-native payload (id/count/components; two-arg save RETURNS the new tag)");
        net.minecraft.world.item.ItemStack back = (net.minecraft.world.item.ItemStack)
                ivc.getMethod("of", net.minecraft.nbt.CompoundTag.class).invoke(null, out);
        check("red".equals(((net.minecraft.nbt.CompoundTag) getTag.invoke(null, back)).getString("Color")),
                "save+of: mod-internal round-trip preserves the tag view");
        net.minecraft.nbt.CompoundTag emptyOut = (net.minecraft.nbt.CompoundTag)
                ivc.getMethod("save", net.minecraft.world.item.ItemStack.class, net.minecraft.nbt.CompoundTag.class)
                        .invoke(null, net.minecraft.world.item.ItemStack.EMPTY, new net.minecraft.nbt.CompoundTag());
        check("minecraft:air".equals(emptyOut.getString("id")),
                "save: empty stack encodes the 1.20.1 air shape (21.1 save throws on empty)");
        check(!(boolean) ivc.getMethod("hasTag", net.minecraft.world.item.ItemStack.class).invoke(null, net.minecraft.world.item.ItemStack.EMPTY),
                "hasTag: false on the empty stack (the !isEmpty() guard)");
        ivc.getMethod("setTag", net.minecraft.world.item.ItemStack.class, net.minecraft.nbt.CompoundTag.class)
                .invoke(null, stack, null);
        check(getTag.invoke(null, stack) == null,
                "setTag(null): clears the custom data (1.20.1 convention)");
    }
}
