package net.neoforged.neoforge.common.capabilities;

/**
 * ModBridge shim carrier (phase 5): the anonymous-subclass type-coin used by the
 * forge idiom {@code CapabilityManager.get(new CapabilityToken<T>(){})}. Kept
 * non-final with an accessible constructor so translated anonymous subclasses
 * (CapabilityHandler$1..$6) compile-resolve at runtime.
 */
public class CapabilityToken<T> {
}
