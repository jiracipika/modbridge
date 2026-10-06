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

    /** 1.20.1 {@code IForgeRegistry.register} surface — LOG-ONCE NO-OP. On the
     *  translated runtime the SAME entries are already registered + bound through
     *  moonlight's own DeferredRegister listeners (its 21.1-native path); the
     *  1.20.1 direct-register call sites (supp registerOverrides, the Registrator
     *  method-ref lambdas) are that path's REDUNDANT twin. Calling through here
     *  double-registers (21.1 IllegalStateException "Adding duplicate key") and
     *  bypasses holder binding (DeferredHolder "unbound value"). Neither the
     *  duplicate-override nor the direct-register semantics are claimed: entries
     *  registered ONLY through this path (mod-compat overrides) are inert. */
    public static void register(Registry<Object> registry, ResourceLocation id, Object value) {
        try {
            Registry.register(registry, id, value);
        } catch (RuntimeException duplicate) {
            if (!warned) {
                warned = true;
                System.out.println("[ModBridge shim] registry register kept existing entry: "
                        + id + " (" + duplicate.getMessage() + ")");
            }
        }
    }
}
