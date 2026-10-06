package net.minecraft.world.level.storage.loot.functions;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.storage.loot.InertLootSerializerCodecs;
import net.minecraft.world.level.storage.loot.LootSerializer;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's {@code LootItemFunctionType}
 * extends {@code SerializerType} and is constructed with a Gson-based loot
 * {@code Serializer}; NeoForge 1.21.1 rebuilt it as a final generic record
 * {@code LootItemFunctionType<T extends LootItemFunction>} over a {@code MapCodec}.
 * Translated mods construct it once per custom loot function with their serializer
 * instance (measured: 2 sites in supplementaries ModRegistry: curse_loot /
 * random_arrow; the trial jars reference the type RAW, so the shim is non-generic)
 * and hand it to their registry helper, which registers the value into the REAL
 * 21.1 {@code Registries.LOOT_FUNCTION_TYPE}.
 *
 * Same design as the other type-holder shims: 1.20.1 construction surface + erased
 * 21.1 record shape for registry reads, inert codec behind {@link #codec()};
 * loot-function SERIALIZATION is explicitly not claimed.
 */
public class LootItemFunctionType {

    private final LootSerializer<? extends LootItemFunction> serializer;

    public LootItemFunctionType(LootSerializer<? extends LootItemFunction> serializer) {
        this.serializer = serializer;
    }

    /** 1.20.1 SerializerType accessor surface. */
    public LootSerializer<? extends LootItemFunction> getSerializer() {
        return serializer;
    }

    /** 21.1 record accessor surface — erased desc must stay ()Lcom/mojang/serialization/MapCodec;. */
    public MapCodec<? extends LootItemFunction> codec() {
        return InertLootSerializerCodecs.inert("loot function type");
    }

    @Override
    public String toString() {
        return "ModBridge@shim/LootItemFunctionType(" + serializer + ")";
    }
}
