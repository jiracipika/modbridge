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
        // pre-check the duplicate BEFORE calling Registry.register: on 21.1 the value
        // is already registered AND its intrusive holder (created at construction -
        // vanilla Item.<init> calls createIntrusiveHolder on itself) must not stay
        // orphaned. GameData's end-of-registration freeze fails the whole boot on any
        // unbound intrusive holder ("Some intrusive holders were not registered",
        // live-boot). The 1.20.1 override semantics are unchanged: the existing entry
        // stays, the override is not claimed (measured: supp's minecraft:shulker_shell
        // helmet override, constructed inline in its RegisterEvent handler).
        if (registry.get(id) != null) {
            forgetIntrusiveHolder(registry, value, id);
            if (!warned) {
                warned = true;
                System.out.println("[ModBridge shim] registry register kept existing entry: " + id);
            }
            return;
        }
        try {
            Registry.register(registry, id, value);
        } catch (RuntimeException duplicate) {
            forgetIntrusiveHolder(registry, value, id);
            if (!warned) {
                warned = true;
                System.out.println("[ModBridge shim] registry register kept existing entry: "
                        + id + " (" + duplicate.getMessage() + ")");
            }
        }
    }

    /** drops the value from the registry's private unregisteredIntrusiveHolders map
     *  (MappedRegistry, mojmap-named field, non-null only for intrusive-holder
     *  registries): the constructed-but-skipped value would otherwise fail the
     *  registration freeze. Reflective + fail-soft; on any shape drift the freeze
     *  failure resurfaces loudly (never silently wrong). */
    private static void forgetIntrusiveHolder(Registry<Object> registry, Object value, ResourceLocation id) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = registry.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("unregisteredIntrusiveHolders");
                } catch (NoSuchFieldException ignoredField) {
                    // walk up: the field lives on MappedRegistry, instances are
                    // DefaultedMappedRegistry (live-boot)
                }
            }
            if (f == null) return;
            f.setAccessible(true);
            Object map = f.get(registry);
            if (map instanceof java.util.Map<?, ?> m) {
                Object before = m.remove(value);
                System.out.println("[ModBridge DBG] forgetIntrusiveHolder id=" + id
                        + " removed=" + (before != null) + " remaining=" + m.size());
            } else {
                System.out.println("[ModBridge DBG] forgetIntrusiveHolder id=" + id + " map=null");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.out.println("[ModBridge DBG] forget failed: " + e);
        }
    }
}
