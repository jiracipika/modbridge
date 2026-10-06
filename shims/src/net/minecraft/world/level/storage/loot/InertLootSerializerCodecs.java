package net.minecraft.world.level.storage.loot;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import java.util.stream.Stream;

/**
 * ModBridge shim carrier (loot lane): the single inert {@link MapCodec} the three
 * loot type-holder shims ({@code LootItemConditionType}/{@code LootPoolEntryType}/
 * {@code LootItemFunctionType}) hand out from their 21.1-shaped {@code codec()}
 * accessor.
 *
 * The 21.1 type holders are records over a {@code MapCodec}, and vanilla's loot-JSON
 * dispatch reads {@code codec()} off every registered value at datapack-load time.
 * A translated mod's value is the SHIM holder (1.20.1 construction surface), so its
 * codec() must return something with the erased record shape. Encoding/decoding
 * through it fails loudly with a ModBridge-named error: a loot table that actually
 * references a translated condition/function/entry logs a parse failure for THAT
 * table instead of crashing the loader — the loot serialization RUNTIME surface is
 * explicitly not claimed (same acceptance as the inert capability events).
 */
public final class InertLootSerializerCodecs {

    private InertLootSerializerCodecs() {
    }

    public static <A> MapCodec<A> inert(String what) {
        return new InertCodec<>(what);
    }

    static final class InertCodec<A> extends MapCodec<A> {

        private final String what;

        InertCodec(String what) {
            this.what = what;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.empty();
        }

        @Override
        public <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            throw new IllegalStateException(message());
        }

        @Override
        public <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input) {
            return DataResult.error(this::message);
        }

        private String message() {
            return "ModBridge: this " + what
                    + " was registered through a translated 1.20.1 Gson loot Serializer; "
                    + "its codec bridge is deliberately not implemented (loot serialization "
                    + "is not claimed on 1.21.1)";
        }

        @Override
        public String toString() {
            return "ModBridge@inert-codec(" + what + ")";
        }
    }
}
