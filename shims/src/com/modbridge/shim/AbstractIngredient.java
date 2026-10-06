package com.modbridge.shim;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 forge's
 * {@code net.minecraftforge.common.crafting.AbstractIngredient} is GONE on 21.1
 * (the ingredient system went codec/data-component; the 21.1 vanilla
 * {@code net.minecraft.world.item.crafting.Ingredient} is a FINAL class, so the
 * shim CANNOT extend it and still be carried — this carrier is a plain abstract
 * class instead). Measured surface across both trial jars: exactly ONE subclass
 * (moonlight BlockTypeSwapIngredientImpl) using the no-arg super-ctor and the
 * invalidate() member. Ingredient BEHAVIOR (test/getItems on the platform, recipe
 * JSON use of moonlight:block_type_swap) is NOT claimed — the class becomes
 * loadable/linkable at boot, nothing more (same acceptance as the loot/condition
 * lanes). Relocated per-mod like every shim.
 */
public abstract class AbstractIngredient {

    public AbstractIngredient() {
    }

    /** 1.20.1 AbstractIngredient.invalidate() surface — inert. */
    public void invalidate() {
    }
}
