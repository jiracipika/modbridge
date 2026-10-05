package net.neoforged.neoforge.common.util;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's NonNullConsumer (see
 * NonNullSupplier). Descriptor-compatible replacement for LazyOptional.ifPresent.
 */
@FunctionalInterface
public interface NonNullConsumer<T> extends java.util.function.Consumer<T> {
}
