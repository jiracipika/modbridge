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

    private RegistryCompat() {
    }

    /** 1.20.1 {@code IForgeRegistry.register} surface — delegates to the 21.1 static. */
    public static void register(Registry<Object> registry, ResourceLocation id, Object value) {
        Registry.register(registry, id, value);
    }
}
