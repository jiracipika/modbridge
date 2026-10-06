package com.modbridge.shim;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * ModBridge shim carrier (armor lane, phase 5.11): 1.20.1 vanilla's
 * {@code net.minecraft.world.item.ArmorMaterial} INTERFACE became a final RECORD on
 * 21.1 (the armor rework) — code implementers cannot even DEFINE (ICCE "can not
 * implement ... not an interface"). Measured implementer: supplementaries
 * ShulkerShellItem$SkulkerShellArmorMaterial declaring the full 1.20.1 surface:
 * getDurability(Type), getDefense(Type), getEnchantmentValue(), getEquipSound(),
 * getRepairIngredient(), getName(), getToughness(), getKnockbackResistance().
 * This shim INTERFACE receives the superswap interface-replacement so the
 * implementer links; armor registration against the 21.1 Holder-based ArmorItem
 * is a separate (unmeasured) surface — armor stats NOT claimed. Relocated per-mod.
 */
public interface ArmorMaterialCompat {

    /** 1.20.1 surface (m_266425_). */
    int getDurability(ArmorItem.Type type);

    /** 1.20.1 surface (m_7366_). */
    int getDefense(ArmorItem.Type type);

    /** 1.20.1 surface (m_6646_). */
    int getEnchantmentValue();

    /** 1.20.1 surface (m_7344_). */
    SoundEvent getEquipSound();

    /** 1.20.1 surface (m_6230_). */
    Ingredient getRepairIngredient();

    /** 1.20.1 surface (m_6082_). */
    String getName();

    /** 1.20.1 surface (m_6651_). */
    float getToughness();

    /** 1.20.1 surface (m_6649_). */
    float getKnockbackResistance();
}
