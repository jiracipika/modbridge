package com.modbridge.shim;

/**
 * ModBridge shim carrier (enchantment lane, phase 5.10): 1.20.1 vanilla's
 * {@code Enchantment$Rarity} enum is GONE on 1.21.1. Measured surface: a single
 * GETSTATIC VERY_RARE (supplementaries StasisEnchantment ctor); the full 1.20.1
 * enum value set (javap-verified: COMMON, UNCOMMON, RARE, VERY_RARE) carried for
 * completeness. Statics only — nothing is dispatched on rarity.
 */
public class Enchantment$Rarity {

    public static final Enchantment$Rarity COMMON = new Enchantment$Rarity("common", 0);
    public static final Enchantment$Rarity UNCOMMON = new Enchantment$Rarity("uncommon", 1);
    public static final Enchantment$Rarity RARE = new Enchantment$Rarity("rare", 2);
    public static final Enchantment$Rarity VERY_RARE = new Enchantment$Rarity("very_rare", 3);

    public final String name;
    public final int weight;

    private Enchantment$Rarity(String name, int weight) {
        this.name = name;
        this.weight = weight;
    }
}
