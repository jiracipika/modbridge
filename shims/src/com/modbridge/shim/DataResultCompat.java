package com.modbridge.shim;

import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.DataResult;

/**
 * ModBridge shim carrier (config lane, phase 5.9): DFU 6 (1.20.1) -> DFU 8 (1.21.1)
 * member drift on {@link DataResult}. DataResult itself went class -> interface (the
 * rewriter's interface call-site repair fixes the surviving members' call kinds);
 * these three member shapes have NO 21.1 counterpart and are bridged here as static
 * adapters the rewriter's "static" memberretarget kind routes call sites to (the
 * receiver becomes the first argument — operand-stack shape identical, frames
 * untouched). Measured across both trial jars: {@code getOrThrow(Z,Consumer)} x20
 * (supplementaries 15, moonlight 5) and {@code get()->Either} x2 (moonlight).
 *
 * Semantics are PORTED FROM THE DFU 6.0.8 BYTECODE (javap of lambda$getOrThrow$5),
 * not from the misleading parameter name: {@code addMessage} actually means "return
 * the partial result instead of throwing when one exists". Error path: accept the
 * message into {@code onError} (both flag values); with {@code addMessage} and a
 * partial value present, return the partial; otherwise throw
 * {@code RuntimeException(message)}. Success path returns the value unchanged.
 * {@code get()} returns the success/error split as an {@link Either} — left the
 * value, right the 21.1 {@link DataResult.Error} record (1.20.1 handed back
 * DataResult$PartialResult; the one {@code PartialResult.message()} call site in
 * moonlight is owner-retargeted to {@code DataResult$Error} by rule — same name and
 * erased descriptor, the record accessor is byte-compatible).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim.
 */
public final class DataResultCompat {

    private DataResultCompat() {
    }

    /** DFU 6 {@code DataResult.getOrThrow(boolean, Consumer<String>)} — see class doc. */
    public static <R> R getOrThrow(DataResult<R> result, boolean addMessage, Consumer<String> onError) {
        return result.mapOrElse(
                r -> r,
                (DataResult.Error<R> error) -> {
                    final String message = error.message();
                    if (onError != null) {
                        onError.accept(message);
                    }
                    if (addMessage) {
                        final Optional<R> partial = error.partialValue();
                        if (partial.isPresent()) {
                            return partial.get();
                        }
                    }
                    throw new RuntimeException(message);
                });
    }

    /** DFU 6 {@code DataResult.get()} — the success/error split as an Either. */
    public static <R> Either<R, DataResult.Error<R>> get(DataResult<R> result) {
        final Optional<R> ok = result.result();
        if (ok.isPresent()) {
            return Either.left(ok.get());
        }
        return Either.right(result.error().orElseThrow());
    }
}
