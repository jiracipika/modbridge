package net.neoforged.neoforge.common.util;

import java.util.Optional;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's LazyOptional (lazy value holder with
 * invalidation) was removed in NeoForge 1.21 — capability queries now return plain
 * objects and the event-driven attach API is gone. This shim restores the class at the
 * retargeted name so translated capability provider/holder classes load and their
 * internal chains run. It is self-contained: only the mod's own code ever sees these
 * instances (the 1.21 capability query path is a different API surface).
 *
 * Implemented members are exactly the call sites measured in supplementaries +
 * moonlight 1.20.1 (javap inventory, trial 002 phase 5): of/empty/isPresent/ifPresent/
 * orElse/orElseGet/orElseThrow/cast/invalidate/resolve. Resolution is lazy and cached
 * (like the original); invalidate() detaches the value.
 */
public final class LazyOptional<T> {

    private static final LazyOptional<?> EMPTY = new LazyOptional<>(null, true);

    private final NonNullSupplier<? extends T> supplier;
    private volatile boolean invalid;
    private volatile boolean resolved;
    private T value;

    private LazyOptional(NonNullSupplier<? extends T> supplier) {
        this(supplier, false);
    }

    private LazyOptional(NonNullSupplier<? extends T> supplier, boolean invalid) {
        this.supplier = supplier;
        this.invalid = invalid;
        this.resolved = invalid;
    }

    /** forge semantics: wraps a supplier, resolves it lazily on first query */
    public static <T> LazyOptional<T> of(NonNullSupplier<? extends T> supplier) {
        return new LazyOptional<>(supplier);
    }

    @SuppressWarnings("unchecked")
    public static <T> LazyOptional<T> empty() {
        return (LazyOptional<T>) EMPTY;
    }

    /** true while not invalidated (does not resolve the supplier — forge semantics) */
    public boolean isPresent() {
        return !invalid;
    }

    /** lazily resolves and caches (null stays null, like Optional.ofNullable) */
    private T compute() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    value = supplier == null ? null : supplier.get();
                    resolved = true;
                }
            }
        }
        return value;
    }

    /** forge semantics: Optional of the (possibly null) resolved value; empty when invalidated */
    public Optional<T> resolve() {
        if (invalid) {
            return Optional.empty();
        }
        return Optional.ofNullable(compute());
    }

    public void ifPresent(NonNullConsumer<? super T> consumer) {
        resolve().ifPresent(consumer);
    }

    public T orElse(T other) {
        if (invalid) {
            return other;
        }
        T v = compute();
        return v != null ? v : other;
    }

    public T orElseGet(NonNullSupplier<? extends T> other) {
        T v = invalid ? null : compute();
        return v != null ? v : other.get();
    }

    public T orElseThrow(NonNullSupplier<? extends RuntimeException> exceptionSupplier) {
        T v = invalid ? null : compute();
        if (v == null) {
            throw exceptionSupplier.get();
        }
        return v;
    }

    /** forge semantics: an unchecked self-returning view under a different type variable */
    @SuppressWarnings("unchecked")
    public <X> LazyOptional<X> cast() {
        return (LazyOptional<X>) (invalid ? EMPTY : this);
    }

    /** detaches the value; later queries see empty (listeners are not modeled) */
    public void invalidate() {
        invalid = true;
        synchronized (this) {
            value = null;
            resolved = true;
        }
    }
}
