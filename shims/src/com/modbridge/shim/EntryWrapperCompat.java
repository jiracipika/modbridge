package com.modbridge.shim;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * ModBridge shim carrier (registry lane, phase 5.11): the typerename
 * RegistryObject -> DeferredHolder turns every 1.20.1 RegistryObject.get() into
 * DeferredHolder.get() - whose holders only BIND through the RegisterEvent helper
 * path. Entries registered by the translated 1.20.1 direct-register path
 * (RegistryCompat) exist in the registry but their holders never bind, so
 * DeferredHolder.get() throws "Trying to access unbound value" (live-boot: supp
 * RegUtils.registerPlacements querying the pancake item via moonlight's
 * EntryWrapper, which rides Supplier INTERFACE dispatch - invisible to call-site
 * rules - and delegates to the inner RegistryObject.get()). This adapter queries
 * the registry DIRECTLY via the holder's key (works for every BuiltInRegistries
 * entry regardless of binding; same value for bound holders). Routed by the
 * memberretargets "static" kind on RegistryObject.get(). Relocated per-mod.
 */
public final class EntryWrapperCompat {

    private EntryWrapperCompat() {
    }

    /** 1.20.1 {@code RegistryObject.get()} surface — direct registry lookup. Also
     *  covers moonlight's EntryWrapper.get() (its body delegates here through the
     *  same retarget; the wrapper itself rides Supplier interface dispatch, so the
     *  CALL-SITE rule cannot see it - the INNER RegistryObject.get() retarget is
     *  the effective one). Safe for bound holders too (same value). */
    public static Object get(DeferredHolder<?, ?> holder) {
        ResourceKey<Object> key = (ResourceKey<Object>) holder.getKey();
        Registry<Object> registry = (Registry<Object>) BuiltInRegistries.REGISTRY.get(key.registry());
        Object value = registry.get(key.location());
        if (value == null) {
            throw new IllegalStateException("ModBridge: registry value not present: " + key);
        }
        return value;
    }
}
