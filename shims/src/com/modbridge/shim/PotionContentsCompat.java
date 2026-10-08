package com.modbridge.shim;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
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
 *
 * <p>Phase 5.17 (potion member drift, measured boot wall: moonlight
 * SoftFluidStack.fromItem NSME): the whole 1.20.1 {@code PotionUtils} static
 * family is GONE on 21.1 (the class does not exist there - the 5.9 typerename
 * moved its call sites onto {@code PotionContents}, a record with a different
 * surface: no getPotion at all, getColor only in Holder/Iterable forms). The
 * bridges below keep the RAW bytecode types end to end: they return/accept the
 * BARE registered Potion instances the translated code's locals, fields and
 * identity compares ({@code getPotion(stack) == WATER/EMPTY}) expect - the
 * repairHolderUnwraps pass already unwraps Holder-kind constants to bare at
 * those consumers, so bare-vs-bare is the one coherent identity universe.
 * "No potion" is {@link PotionCompat#EMPTY}'s bare sentinel (the value inside
 * the synthetic Holder - the exact instance the EMPTY field sites compare
 * against). Shim method names equal the raw call-site names (the |static
 * retarget keeps the site's name): getPotion/getColor/getMobEffects.</p>
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

    /** 1.20.1 PotionUtils.getPotion(ItemStack) - the measured 5.17 boot wall
     *  (moonlight SoftFluidStack.fromItem). 21.1 shape: the POTION_CONTENTS
     *  component's potion Holder, unwrapped to bare; "no potion" = the bare
     *  sentinel so identity compares against EMPTY hold. */
    public static Potion getPotion(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion().map(Holder::value).orElse(PotionCompat.EMPTY.value());
    }

    /** 1.20.1 PotionUtils.getPotion(CompoundTag): tag.getString("Potion") ->
     *  registry lookup, absent/garbage -> EMPTY. 1.20.1 Potion.byName semantics. */
    public static Potion getPotion(CompoundTag tag) {
        String name = tag.getString("Potion");
        if (!name.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(name);
            if (rl != null) {
                Potion potion = BuiltInRegistries.POTION.get(rl);
                if (potion != null) return potion;
            }
        }
        return PotionCompat.EMPTY.value();
    }

    /** 1.20.1 PotionUtils.getColor(Potion): EMPTY -> -1, else mix the potion's
     *  effect colors. 21.1 PotionContents.getColor only takes Holder/Iterable. */
    public static int getColor(Potion potion) {
        if (potion == PotionCompat.EMPTY.value()) return -1;
        return PotionContents.getColor(BuiltInRegistries.POTION.wrapAsHolder(potion));
    }

    /** 1.20.1 PotionUtils.getColor(ItemStack) (tint/tooltip lane): component
     *  color with the custom-color override; no content -> -1 (record mirrors). */
    public static int getColor(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .getColor();
    }

    /** 1.20.1 PotionUtils.getMobEffects(ItemStack): base potion effects + custom
     *  effects as a mutable List (JEI/tooltip consumers re-add to it). */
    public static List<MobEffectInstance> getMobEffects(ItemStack stack) {
        List<MobEffectInstance> out = new ArrayList<>();
        stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .getAllEffects().forEach(out::add);
        return out;
    }
}
