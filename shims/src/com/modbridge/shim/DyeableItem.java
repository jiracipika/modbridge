package com.modbridge.shim;

import net.minecraft.world.item.ItemStack;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 vanilla's
 * {@code net.minecraft.world.item.DyeableItem} is GONE on 1.21.1 — dyeing moved to
 * data components (DYED_COLOR); no renamed counterpart. Measured surface across
 * supplementaries (SRG-table mojmap names): hasCustomColor (m_41113_), getColor
 * (m_41121_), clearColor (m_41123_) — all INVOKEINTERFACE on the interface.
 * Defaults are INERT no-ops (the NBT tag store they read on 1.20.1 does not exist;
 * the 21.1 data-component store is not bridged — dyeing behavior NOT claimed).
 */
public interface DyeableItem {

    /** 1.20.1 surface — inert (no component store behind the shim). */
    default boolean hasCustomColor(ItemStack stack) {
        return false;
    }

    /** 1.20.1 surface — inert "no color" (1.20.1's absent-color convention). */
    default int getColor(ItemStack stack) {
        return -1;
    }

    /** 1.20.1 surface — inert no-op. */
    default void clearColor(ItemStack stack) {
    }
}
