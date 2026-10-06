package com.modbridge.shim;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * ModBridge shim carrier (registry lane, phase 5.11): moonlight's
 * RegHelperImpl$EntryWrapper.get() delegates to RegistryObject.get() — typerenamed
 * to DeferredHolder.get() — whose holders only BIND through the RegisterEvent
 * helper path. Entries registered by the translated 1.20.1 direct-register path
 * (RegistryCompat) exist in the registry but their holders never bind, so
 * DeferredHolder.get() throws "Trying to access unbound value" (live-boot: supp
 * RegUtils.registerPlacements querying the pancake item). This adapter queries the
 * registry DIRECTLY via the holder's key (works for every BuiltInRegistries
 * entry regardless of binding), preserving the 1.20.1 eager-resolution semantics
 * moonlight's wrapper had. Routed by the memberretargets "static" kind
 * (receiver-prepended). Relocated per-mod.
 */
public final class EntryWrapperCompat {

    private EntryWrapperCompat() {
    }

    /** 1.20.1 {@code EntryWrapper.get()} surface — direct registry lookup. */
    public static Object get(Object wrapper) {
        DeferredHolder<?, ?> holder = (DeferredHolder<?, ?>) wrapper;
        ResourceKey<Object> key = (ResourceKey<Object>) holder.getKey();
        Registry<Object> registry = (Registry<Object>) BuiltInRegistries.REGISTRY.get(key.registry());
        Object value = registry.get(key.location());
        if (value == null) {
            throw new IllegalStateException("ModBridge: registry value not present: " + key);
        }
        return value;
    }
}
