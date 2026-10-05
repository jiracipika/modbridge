package net.neoforged.neoforge.common.util;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's NonNullSupplier died in the 21.1
 * re-organization (no net.neoforged.neoforge.common.util.Non*Supplier exists). This
 * shim is injected into translated mod jars by the rewriter so the retargeted
 * references resolve inside the mod's own module. It keeps the original shape
 * (Supplier that promises non-null) so descriptors translated by the type rules
 * keep matching: LazyOptional.of/orElseGet/orElseThrow take this type.
 */
@FunctionalInterface
public interface NonNullSupplier<T> extends java.util.function.Supplier<T> {
}
