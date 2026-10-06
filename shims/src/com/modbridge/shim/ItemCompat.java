package com.modbridge.shim;

import java.util.UUID;

/**
 * ModBridge shim carrier (attributes lane, phase 5.12): 1.20.1
 * {@code Item.BASE_ATTACK_DAMAGE_UUID} / {@code Item.BASE_ATTACK_SPEED_UUID} (SRG
 * f_41374_/f_41375_) are GONE on 21.1 — default attribute modifiers moved to data
 * components keyed by ResourceLocation ({@code Item.BASE_ATTACK_DAMAGE_ID}).
 * supplementaries' WrenchItem copies both constants into its own clinit (and uses
 * them in the 1.20.1 getAttributeModifiers override, which 21.1 never dispatches —
 * Item attribute modifiers are component-driven there). This shim carries the
 * canonical 1.20.1 UUID values so the clinit executes and the dead override's body
 * stays link-safe; the values are otherwise inert (the 21.1 attribute pipeline
 * never consults them). Field-retarget target with the "*" owner fallback (javac
 * emits the subclass as owner for inherited statics). Relocated per-mod like every
 * shim.
 */
public final class ItemCompat {

    private ItemCompat() {
    }

    public static final UUID BASE_ATTACK_DAMAGE_UUID =
            UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");
    public static final UUID BASE_ATTACK_SPEED_UUID =
            UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
}
