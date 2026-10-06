package com.modbridge.shim;

/**
 * ModBridge shim carrier (enchantment lane, phase 5.10): 1.20.1 forge's
 * {@code net.minecraftforge.common.crafting.EnchantmentCategory} is GONE on 21.1
 * (categories became item tags). Measured surface: a single GETSTATIC CROSSBOW
 * (supplementaries StasisEnchantment ctor); other 1.20.1 category constants
 * deliberately absent (unmeasured — measure, don't guess). Statics only.
 */
public class EnchantmentCategory {

    public static final EnchantmentCategory CROSSBOW = new EnchantmentCategory();

    private EnchantmentCategory() {
    }
}
