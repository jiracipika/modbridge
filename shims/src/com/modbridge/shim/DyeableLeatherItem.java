package com.modbridge.shim;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 vanilla's
 * {@code net.minecraft.world.item.DyeableLeatherItem} (marker sub-interface of
 * DyeableItem) is GONE on 1.21.1. Measured implementer: supplementaries QuiverItem
 * (implements it). Everything functional lives in the {@link DyeableItem} defaults.
 */
public interface DyeableLeatherItem extends DyeableItem {
}
