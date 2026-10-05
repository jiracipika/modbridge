package net.neoforged.neoforge.common.capabilities;

import net.neoforged.neoforge.common.util.LazyOptional;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's Capability token object. NeoForge 1.21
 * replaced Capability instances with typed BlockCapability/EntityCapability/ItemCapability
 * handles that cannot be retro-fitted onto per-instance attach semantics; this shim keeps
 * the token identity (name) and the two helpers the translated mods call so their classes
 * load and run their own provider chains. Instances are created via CapabilityManager.get
 * (like the original) or the internal factory below.
 *
 * Only the measured call surface is implemented: getName() and static orEmpty(...).
 */
public final class Capability<T> {

    private final String name;

    Capability(String name) {
        this.name = name;
    }

    /** internal factory for shim-side token creation (CapabilityManager, ForgeCapabilities) */
    public static <T> Capability<T> create(String name) {
        return new Capability<>(name);
    }

    public String getName() {
        return name;
    }

    /** forge semantics: cap.orEmpty(supplier) resolves to the supplier when the cap matches */
    public static <T> LazyOptional<T> orEmpty(Capability<T> cap, LazyOptional<T> supplier) {
        return supplier;
    }
}
