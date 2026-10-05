package net.neoforged.neoforge.common.capabilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's CapabilityManager handed out
 * Capability instances for type-coins and registered them with the mod bus for the
 * RegisterCapabilitiesEvent round-trip. On 1.21 that registry is a different API with
 * different attach semantics, so this shim only mints ONE stable Capability per token
 * class (the identity the translated mod compares against) — it never registers
 * anything platform-side. get()/query against vanilla objects was never reachable
 * through this surface in the first place (it went through ICapabilityProvider of the
 * mod's own objects, which the shim interface serves).
 */
public final class CapabilityManager<T> {

    private static final Map<Class<?>, Capability<?>> TOKENS = new ConcurrentHashMap<>();

    private CapabilityManager() {
    }

    public static <T> Capability<T> get(CapabilityToken<T> token) {
        @SuppressWarnings("unchecked")
        Capability<T> cap = (Capability<T>) TOKENS.computeIfAbsent(token.getClass(),
                c -> Capability.create("mb-shim:" + c.getName()));
        return cap;
    }
}
