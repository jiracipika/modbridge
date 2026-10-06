package com.modbridge.shim;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * ModBridge shim carrier (attributes lane, phase 5.12): moonlight's
 * ForgeHelper(Impl).getReachDistance reads the removed forge-side
 * {@code ForgeMod.BLOCK_REACH} RegistryObject (forge's block-reach attribute —
 * unregistered on 21.1, the field itself is a package-fallback phantom).
 * Mojang absorbed the attribute into vanilla as
 * {@code Attributes.BLOCK_INTERACTION_RANGE} (same 5.0 default, same semantics), so
 * this static-kind retarget target forwards the whole helper onto it. The orphaned
 * 1.20.1 body is method-dropped (rules/methoddrops.txt). Relocated per-mod like
 * every shim.
 */
public final class BlockReachCompat {

    private BlockReachCompat() {
    }

    public static double getReachDistance(LivingEntity entity) {
        return entity.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }
}
