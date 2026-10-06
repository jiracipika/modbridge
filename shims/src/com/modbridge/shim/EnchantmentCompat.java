package com.modbridge.shim;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * ModBridge shim carrier (enchantment lane, phase 5.10): 21.1 made
 * {@code net.minecraft.world.item.enchantment.Enchantment} FINAL (the enchantment
 * rework made enchantments data-driven) — code subclasses like supplementaries'
 * StasisEnchantment can no longer DEFINE (ICCE "cannot inherit from final class").
 * No shim can restore the IS-A chain, so this carrier takes the superclass slot
 * via the rewriter's SUPER-SWAP mechanism (rules/superswaps.txt, guarded: swaps
 * only when the current super equals the rule's old super). Measured ctor surface:
 * {@code (Enchantment$Rarity, EnchantmentCategory, EquipmentSlot[])} (the 1.20.1
 * Enchantment ctor shape, all types carried by their own shims). Enchantment
 * BEHAVIOR on 21.1 is NOT claimed — the instance floats through the erased
 * registration path; custom enchantments are data-only on 21.1.
 */
public abstract class EnchantmentCompat {

    protected EnchantmentCompat(Enchantment$Rarity rarity, EnchantmentCategory category,
            EquipmentSlot[] slots) {
    }

    /** 1.20.1 super-call surface (m_5975_; StasisEnchantment overrides + super-calls
     *  it) — inert: enchantment semantics not claimed, this only links the super-call
     *  the verifier resolves against the class's superclass. */
    protected boolean checkCompatibility(net.minecraft.world.item.enchantment.Enchantment other) {
        return false;
    }

    /** 1.20.1 super-call surface (m_6081_) — inert, linking only. */
    protected boolean canEnchant(net.minecraft.world.item.ItemStack stack) {
        return false;
    }
}
