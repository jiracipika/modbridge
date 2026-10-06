package com.modbridge.shim;

import java.util.function.Supplier;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * ModBridge shim carrier (items lane, phase 5.11): 1.21 changed BucketItem's ctor
 * from {@code (Supplier<Fluid>, Properties)} to {@code (Fluid, Properties)}
 * (supplier resolved). Measured subclass: supplementaries LumiseneBucketItem
 * (super-call desc (Supplier, Properties); moonlight's ModBucketItem already calls
 * the 21.1 shape). The shim resolves the supplier at construction. Relocated
 * per-mod.
 */
public class BucketItemCompat extends BucketItem {

    public BucketItemCompat(Supplier<?> fluidSupplier, Item.Properties properties) {
        super((Fluid) fluidSupplier.get(), properties);
    }
}
