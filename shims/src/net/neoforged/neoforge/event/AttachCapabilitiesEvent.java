package net.neoforged.neoforge.event;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.capabilities.ICapabilityProvider;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's AttachCapabilitiesEvent (per-object
 * capability attachment) has NO NeoForge 1.21 successor — attachment moved to the
 * data-attachment / RegisterCapabilitiesEvent APIs, which have different semantics.
 * The translated mods only need the CLASS to exist so their @SubscribeEvent listener
 * methods resolve during event-bus registration (parameter type resolution); nothing
 * on 1.21 ever FIRES this event, so the attach path is structurally present but inert
 * on a dedicated server (same treatment as client-only code).
 *
 * Implemented members are the measured call surface: getObject() and addCapability().
 */
public class AttachCapabilitiesEvent<T> extends Event {

    private final T object;
    private final Map<ResourceLocation, ICapabilityProvider> capabilities = new LinkedHashMap<>();

    public AttachCapabilitiesEvent(T object) {
        this.object = object;
    }

    public T getObject() {
        return object;
    }

    public void addCapability(ResourceLocation key, ICapabilityProvider provider) {
        capabilities.put(key, provider);
    }

    public Map<ResourceLocation, ICapabilityProvider> getCapabilities() {
        return capabilities;
    }
}
