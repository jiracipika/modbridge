package net.minecraft.world.level.storage.loot.predicates;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.storage.loot.InertLootSerializerCodecs;
import net.minecraft.world.level.storage.loot.LootSerializer;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's {@code LootItemConditionType}
 * extends {@code SerializerType} and is constructed with a Gson-based loot
 * {@code Serializer}; NeoForge 1.21.1 rebuilt it as a final record over a
 * {@code MapCodec}. Translated mods construct it once per custom condition with
 * their serializer instance (measured: 1 site in moonlight MoonlightRegistry, 2 in
 * ModLootConditions) and hand it to their registry helper, which registers the value
 * into the REAL 21.1 {@code Registries.LOOT_CONDITION_TYPE}.
 *
 * This shim keeps the 1.20.1 construction surface plus the SerializerType-style
 * {@link #getSerializer()} accessor, and adds the erased 21.1 record shape
 * ({@link #codec()}{@code : ()Lcom/mojang/serialization/MapCodec;}) so vanilla's
 * loot-JSON dispatch can READ the value it finds in the registry. The registry
 * entry is therefore the shim, not the 21.1 record — registration itself is
 * type-indifferent (erasure; no value class check anywhere in
 * DeferredRegister/RegisterEvent/Registry.register), so construction, registration
 * and boot proceed. Using the codec fails loudly per loot table (see
 * {@link InertLootSerializerCodecs}): loot-condition SERIALIZATION is explicitly
 * not claimed (same acceptance as the inert capability events).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim, so the
 * same-name real record in the platform jar is never shadowed for vanilla code.
 */
public class LootItemConditionType {

    private final LootSerializer<? extends LootItemCondition> serializer;

    public LootItemConditionType(LootSerializer<? extends LootItemCondition> serializer) {
        this.serializer = serializer;
    }

    /** 1.20.1 SerializerType accessor surface (m_79331_; not referenced by the trial jars — kept for shape parity). */
    public LootSerializer<? extends LootItemCondition> getSerializer() {
        return serializer;
    }

    /** 21.1 record accessor surface — erased desc must stay ()Lcom/mojang/serialization/MapCodec;. */
    public MapCodec<? extends LootItemCondition> codec() {
        return InertLootSerializerCodecs.inert("loot condition type");
    }

    @Override
    public String toString() {
        return "ModBridge@shim/LootItemConditionType(" + serializer + ")";
    }
}
