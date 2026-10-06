package com.modbridge.shim;

import java.util.Locale;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * ModBridge shim carrier (attributes lane, phase 5.12): the 1.20.1
 * {@code AttributeModifier(UUID, String, double, Operation)} constructor is GONE on
 * 21.1 — modifiers are keyed by a single ResourceLocation
 * ({@code (ResourceLocation, double, Operation)}). Ctor-retarget target
 * (rules/ctorretargets.txt, GETSTATIC INSTANCE + create shape): keeps the 1.20.1
 * call shape and derives the 21.1 key from the name
 * ("modbridge/&lt;name lowercase&gt;"; the name was already the unique modifier
 * label per call site). Measured sites: supplementaries WrenchItem's clinit-built
 * default attribute map (consumed only by its 1.20.1 getAttributeModifiers
 * override, which 21.1 never dispatches — inert data on the translated runtime).
 * Relocated per-mod like every shim.
 */
public final class AttributeModifierCompat {

    private AttributeModifierCompat() {
    }

    public static final AttributeModifierCompat INSTANCE = new AttributeModifierCompat();

    public AttributeModifier create(UUID uuid, String name, double amount,
                                    AttributeModifier.Operation operation) {
        // the 1.20.1 name was free-form ("tool modifier") - sanitize to a path-legal
        // [a-z0-9/._-] key (lowercase, spaces -> underscores, illegal chars dropped)
        StringBuilder path = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.'
                    || c == '_' || c == '-') {
                path.append(c);
            } else {
                path.append('_');
            }
        }
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("modbridge", path.toString());
        return new AttributeModifier(key, amount, operation);
    }
}
