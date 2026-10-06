package com.modbridge.shim;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;

/**
 * ModBridge shim carrier (potion lane, phase 5.12): 1.20.1 {@code Potions.EMPTY}
 * (the no-effect sentinel potion) has NO 21.1 counterpart - the empty potion left
 * the registry on 1.20.5+ (absence of potion content IS "empty" there). This shim
 * carries a synthetic sentinel Holder (an effect-less Potion in a
 * {@code Holder.direct}) so the translated sentinel comparisons keep a stable
 * never-equal-to-a-real-potion identity. NOT registered anywhere; the sentinel only
 * ever compares unequal to registry potions - exactly the 1.20.1 sentinel
 * semantics the translated code relies on. Relocated per-mod like every shim.
 */
public final class PotionCompat {

    private PotionCompat() {
    }

    public static final Holder<Potion> EMPTY = Holder.direct(new Potion());

    static {
        // touch Potions so the class-init ordering matches the 1.20.1 shape the
        // translated code was compiled against (EMPTY lived next to the registry
        // constants there); the field itself is unused
        if (Potions.WATER == null) {
            throw new IllegalStateException("unreachable");
        }
    }
}
