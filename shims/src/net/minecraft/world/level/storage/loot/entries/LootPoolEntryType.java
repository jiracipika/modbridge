package net.minecraft.world.level.storage.loot.entries;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.storage.loot.InertLootSerializerCodecs;
import net.minecraft.world.level.storage.loot.LootSerializer;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's {@code LootPoolEntryType}
 * extends {@code SerializerType} and is constructed with a Gson-based loot
 * {@code Serializer}; NeoForge 1.21.1 rebuilt it as a final record over a
 * {@code MapCodec}. Translated mods construct it once per custom pool entry with
 * their serializer instance (measured: 1 site in moonlight MoonlightRegistry for
 * the optional_item entry) and hand it to their registry helper, which registers
 * the value into the REAL 21.1 {@code Registries.LOOT_POOL_ENTRY_TYPE}.
 *
 * Same design as the {@code LootItemConditionType} shim: 1.20.1 construction
 * surface + erased 21.1 record shape for registry reads, inert codec behind
 * {@link #codec()}; loot-entry SERIALIZATION is explicitly not claimed.
 */
public class LootPoolEntryType {

    private final LootSerializer<? extends LootPoolEntryContainer> serializer;

    public LootPoolEntryType(LootSerializer<? extends LootPoolEntryContainer> serializer) {
        this.serializer = serializer;
    }

    /** 1.20.1 SerializerType accessor surface. */
    public LootSerializer<? extends LootPoolEntryContainer> getSerializer() {
        return serializer;
    }

    /** 21.1 record accessor surface — erased desc must stay ()Lcom/mojang/serialization/MapCodec;. */
    public MapCodec<? extends LootPoolEntryContainer> codec() {
        return InertLootSerializerCodecs.inert("loot pool entry type");
    }

    @Override
    public String toString() {
        return "ModBridge@shim/LootPoolEntryType(" + serializer + ")";
    }
}
