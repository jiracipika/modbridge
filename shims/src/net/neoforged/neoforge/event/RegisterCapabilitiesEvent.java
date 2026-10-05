package net.neoforged.neoforge.event;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's RegisterCapabilitiesEvent (mod-bus,
 * fired during construction; register(Capability)/register(Class) to enable capability
 * queries). NeoForge 21.1 kept an identically-named event in a DIFFERENT package
 * (net.neoforged.neoforge.capabilities) with a different API (typed BlockCapability/
 * ItemCapability/EntityCapability registrations) — translated handlers wired to the
 * real event would either not resolve (package rule lands on a nonexistent name) or
 * die at fire time on NoSuchMethodError.
 *
 * This shim is parked at the @SHIM@ namespace instead: the @SubscribeEvent handler
 * registers against a resolvable type, and the event NEVER FIRES (nothing dispatches
 * the shim), so capability registration is structurally present but inert — exactly
 * like the AttachCapabilitiesEvent treatment. It implements IModBusEvent because the
 * mod event bus rejects listener types that do not carry the marker (boot evidence:
 * "This bus only accepts subclasses of interface net.neoforged.fml.event.IModBusEvent"). The measured member surface
 * (register(Class), x6 in supplementaries' CapabilityHandler) is kept as a no-op
 * recording for the same reason.
 */
public class RegisterCapabilitiesEvent extends Event implements IModBusEvent {

    private final Set<Class<?>> registered = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void register(Class<?> capabilityClass) {
        registered.add(capabilityClass);
    }

    public boolean isRegistered(Class<?> capabilityClass) {
        return registered.contains(capabilityClass);
    }
}
