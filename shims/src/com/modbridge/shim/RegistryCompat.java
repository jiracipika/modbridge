package com.modbridge.shim;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (registry lane, phase 5.10): 1.20.1 forge's instance
 * method {@code IForgeRegistry.register(ResourceLocation, Object)} has no void
 * instance equivalent on 21.1 — {@code Registry.register(Registry, ResourceLocation,
 * T)} is STATIC and RETURNS the value, while the translated call site expects a
 * void-returning virtual-style call (memberretargets "static" kind + this wrapper
 * keeps the operand stack identical). Single measured call-site family:
 * supplementaries SupplementariesForge.registerOverrides (RegisterEvent-time
 * compat registrations). Relocated per-mod like every shim.
 */
public final class RegistryCompat {

    private static boolean warned = false;

    private RegistryCompat() {
    }

    /** 1.20.1 {@code IForgeRegistry.register} surface — delegates to the 21.1 static.
     *  DUPLICATE-TOLERANT: 1.20.1 forge allowed mod overrides of existing ids
     *  (supp shulker_shell); 21.1 throws "Adding duplicate key". Overrides are NOT
     *  claimed — the duplicate is logged once and the existing entry kept, so the
     *  registry dispatch completes and boot proceeds. */
    public static void register(Registry<Object> registry, ResourceLocation id, Object value) {
        try {
            Registry.register(registry, id, value);
        } catch (IllegalArgumentException duplicate) {
            if (!warned) {
                warned = true;
                System.out.println("[ModBridge shim] duplicate registry key kept existing entry: "
                        + id + " (" + duplicate.getMessage() + ")");
            }
        }
    }
}
