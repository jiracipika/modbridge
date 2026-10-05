package net.neoforged.neoforge.common.capabilities;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's ForgeCapabilities holder class.
 * NeoForge 1.21 moved the built-in capability keys to
 * net.neoforged.neoforge.capabilities.Capabilities (typed sub-classes, different
 * members). The translated mods only ever GET these fields (measured usage:
 * ITEM_HANDLER x14, FLUID_HANDLER x2) and pass them to the shim provider chain, so
 * stable token identity is all that is required. Token creation uses the same
 * CapabilityManager.get(new CapabilityToken<>(){}) idiom as the original.
 */
public final class ForgeCapabilities {

    public static final Capability<?> ITEM_HANDLER =
            CapabilityManager.get(new CapabilityToken<Object>() {
            });

    public static final Capability<?> FLUID_HANDLER =
            CapabilityManager.get(new CapabilityToken<Object>() {
            });

    private ForgeCapabilities() {
    }
}
