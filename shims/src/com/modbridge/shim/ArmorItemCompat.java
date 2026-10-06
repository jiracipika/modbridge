package com.modbridge.shim;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;

/**
 * ModBridge shim carrier (armor lane, phase 5.11): supplementaries ShulkerShellItem
 * extends ArmorItem with a 1.20.1-shaped super-call
 * {@code (ArmorMaterial, ArmorItem$Type, Properties)}; on 21.1 ArmorMaterial is a
 * final record, so the translated material impl can never be IS-A it and the
 * super-call cannot verify against the REAL ArmorItem. This carrier extends plain
 * {@link Item} (NOT ArmorItem) and carries the call-site desc, ignoring the
 * material argument — the item constructs as a plain Item. Armor BEHAVIOR
 * (protection, equip sounds, texture layers) is NOT claimed. Relocated per-mod.
 */
public class ArmorItemCompat extends Item {

    public ArmorItemCompat(Object material, ArmorItem.Type type,
            Item.Properties properties) {
        super(properties);
    }
}
