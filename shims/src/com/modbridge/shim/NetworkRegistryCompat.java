package com.modbridge.shim;

import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (networking lane, phase 5.10, the converged live-boot wall:
 * both mods died CNFE net.neoforged.network.NetworkRegistry). 1.20.1 forge's
 * {@code NetworkRegistry.newSimpleChannel} is GONE on 21.1 (payloads +
 * RegisterPayloadHandlersEvent replaced the SimpleChannel world). This INERT facade
 * preserves the 1.20.1 static surface: the call site (single measured site:
 * moonlight ChannelHandlerImpl.&lt;init&gt;) reaches the per-mod relocated shim by
 * typerename, and the returned {@link SimpleChannelCompat} carries the channel
 * object so the caller's field keeps its value.
 *
 * Channel REGISTRATION is stored; nothing is wired to the 21.1 payload system —
 * network play is NOT claimed (same acceptance as the loot/condition/criteria
 * lanes); the faithful payload-registrar bridge is a later slice. Relocated
 * per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim.
 */
public final class NetworkRegistryCompat {

    private NetworkRegistryCompat() {
    }

    /** 1.20.1 {@code newSimpleChannel} surface — inert: wraps an inert channel. */
    public static SimpleChannelCompat newSimpleChannel(ResourceLocation name,
            Supplier<String> networkProtocolVersion, Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        return new SimpleChannelCompat(name);
    }
}
