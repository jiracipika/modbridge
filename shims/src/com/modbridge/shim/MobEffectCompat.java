package com.modbridge.shim;

import java.util.Locale;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * ModBridge shim carrier (effects lane, phase 5.12): 1.20.1
 * {@code MobEffect.addAttributeModifier(Attribute, String uuid, double, Operation)}
 * is gone on 21.1 — the attribute argument became a {@code Holder<Attribute>} AND
 * the String uuid key became a {@code ResourceLocation}
 * ({@code addAttributeModifier(Holder, ResourceLocation, double, Operation)}; the
 * (UUID, String, ...) AttributeModifier ctor went with it). The translated mod side
 * still pushes a bare uuid String constant (measured boot wall:
 * supplementaries OverencumberedEffect.&lt;init&gt; NoSuchMethodError'd inside the
 * mob_effect RegisterEvent dispatch, which aborted the DeferredRegister's
 * addEntries and left the second effect — supplementaries:flammable — unregistered,
 * surfacing later as the item-time "registry value not present" wall). This
 * static-kind retarget target keeps the 1.20.1 stack shape (receiver prepended) and
 * converts the uuid into a stable modifier key
 * ("modbridge/&lt;uuid lowercase&gt;" — path-legal and unique per call site).
 * Relocated per-mod like every shim.
 */
public final class MobEffectCompat {

    private MobEffectCompat() {
    }

    public static MobEffect addAttributeModifier(MobEffect effect, Holder<Attribute> attribute,
                                                 String uuid, double amount,
                                                 AttributeModifier.Operation operation) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath(
                "modbridge", uuid.toLowerCase(Locale.ROOT));
        return effect.addAttributeModifier(attribute, key, amount, operation);
    }
}
