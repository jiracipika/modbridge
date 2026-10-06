package com.modbridge.shim;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * ModBridge shim carrier (potion lane, phase 5.12): 1.20.1 forge's
 * {@code PotionContents.setPotion(ItemStack, Potion)} extension (measured boot
 * wall: supplementaries FMLCommonSetupEvent) is gone on 21.1 - potion content
 * became a DATA COMPONENT ({@code DataComponents.POTION_CONTENTS} carrying a
 * {@code PotionContents} with a {@code Holder<Potion>}). This static-kind
 * retarget target keeps the 1.20.1 call shape: wraps the bare potion through the
 * (plain-Registry) POTION registry's wrapAsHolder and sets the component in place.
 * Relocated per-mod like every shim.
 */
public final class PotionContentsCompat {

    private PotionContentsCompat() {
    }

    /** the potion argument arrives in BOTH shapes on the translated runtime: a bare
     *  Potion (PotionUtils.getPotion flows) or a Holder<Potion> (the retargeted
     *  Potions.POISON-family fields) - accept Object and normalize */
    public static ItemStack setPotion(ItemStack stack, Object potionOrHolder) {
        Potion potion = potionOrHolder instanceof Holder<?> h
                ? (Potion) h.value()
                : (Potion) potionOrHolder;
        Holder<Potion> holder = BuiltInRegistries.POTION.wrapAsHolder(potion);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder));
        return stack;
    }
}
