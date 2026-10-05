package net.neoforged.neoforge.common.capabilities;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.common.util.LazyOptional;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's per-instance capability provider
 * interface (implemented by translated mod classes like supplementaries'
 * AntiqueInkProvider; queried by the mod's own helper code). The identically-named
 * NeoForge 1.21 interface has a different shape (Object-typed caps, context objects),
 * so the retargeted references are served by this shim inside the mod's module.
 * Both measured call shapes are kept: the 2-arg abstract query and the 1-arg default.
 */
public interface ICapabilityProvider {

    LazyOptional<?> getCapability(Capability<?> cap, Direction side);

    default LazyOptional<?> getCapability(Capability<?> cap) {
        return getCapability(cap, null);
    }
}
