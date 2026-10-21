package com.modbridge.shim;

import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * ModBridge shim carrier (enchantment-registry lane, phase 5.20 — the first layer
 * of the data-driven-enchantment whale): 1.21 removed
 * {@code BuiltInRegistries.ENCHANTMENT} from the static registries entirely
 * (enchantments are datapack-driven; javap: only the ENCHANTMENT_*_TYPE codec
 * registries survive as statics). The bridge returns the LIVE server-side registry
 * instead of the dead static:
 *
 * <pre>
 * GETSTATIC BuiltInRegistries.ENCHANTMENT  -&gt;  INVOKESTATIC enchantmentRegistry()
 * </pre>
 *
 * <p>The erased field desc ({@code Lnet/minecraft/core/Registry;}) equals the
 * bridge's return desc, so the stack and every StackMapTable frame stay
 * byte-identical (the fieldunwraps argument, in its purest form).</p>
 *
 * <p><b>Timing is the whale's teeth:</b> the measured wall (supp
 * {@code CurseLootFunction.rebuild} via the datapack tag-update dispatch) runs in
 * Main BEFORE a MinecraftServer instance exists — ServerLifecycleHooks is null
 * there (measured live: the first boot died on this bridge's own fail-loud guard).
 * No static anywhere carries the pre-server datapack registries (NeoForge threads
 * them through TagsUpdatedEvent.getRegistryAccess only). So: live registry once a
 * server exists; otherwise an EMPTY MappedRegistry substitute plus a one-time loud
 * warning. Documented degradation, the house inert pattern: the tag wave passes,
 * the curse-enumeration loot function sees zero curses and its own
 * {@code CURSES.isEmpty()} branch makes it a clean no-op until a post-start
 * reload serves the real registry. Upstream supplementaries DELETED this feature
 * on 1.21.1 rather than port it (3.9.9 has no CurseLootFunction) — inert beats
 * fatal, and the honest line is in the log.</p>
 *
 * <p>{@code isCurse} bridges the second half of the same wall: 1.20.1
 * {@code Enchantment.isCurse()} does not exist on 21.1 (Enchantment became a
 * final record; no curse member survived — the SRG table renames the call site
 * to {@code isCurse} and the |static retarget keeps that name). The 21.1 semantic
 * is the vanilla {@code minecraft:curse} enchantment tag, tested through the same
 * registry this class returns. |static retarget, receiver-as-first-param (the
 * ItemFoodCompat idiom): stack- and frame-neutral.</p>
 */
public final class EnchantmentRegistryCompat {

    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile Registry<Enchantment> lastLive;

    private EnchantmentRegistryCompat() {
    }

    /** the datapack-driven enchantment registry (was BuiltInRegistries.ENCHANTMENT):
     *  live while a server exists, else the documented empty substitute. */
    public static Registry<Enchantment> enchantmentRegistry() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            Registry<Enchantment> live = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            lastLive = live;
            return live;
        }
        if (lastLive != null) {
            // a post-start reload raced its own server hook — serve the last live view
            return lastLive;
        }
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[ModBridge] BuiltInRegistries.ENCHANTMENT accessed before a server "
                    + "exists (datapack-driven on 1.21, and the tag-update wave runs in Main before "
                    + "the server instance). Serving an EMPTY substitute: curse-enumeration loot is "
                    + "inert until a post-start reload; upstream supplementaries deleted this feature "
                    + "on 1.21.1.");
        }
        return new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
    }

    /** 1.20.1 Enchantment.isCurse(): curse-ness = membership in the vanilla curse tag. */
    public static boolean isCurse(Enchantment enchantment) {
        Registry<Enchantment> registry = enchantmentRegistry();
        return registry.getResourceKey(enchantment)
                .flatMap(registry::getHolder)
                .map((Holder.Reference<Enchantment> h) -> h.is(EnchantmentTags.CURSE))
                .orElse(false);
    }
}
