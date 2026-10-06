package net.minecraft.world.level.storage.loot.functions;

import net.minecraft.world.level.storage.loot.LootSerializer;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's inner-class serializer base
 * {@code LootItemConditionalFunction$Serializer<T>} has no 1.21.1 successor — loot
 * functions are codec-based there. supplementaries'
 * {@code RandomArrowFunction$Serializer} and {@code CurseLootFunction$Serializer}
 * extend it (measured: 2 subclasses across the trial jars).
 *
 * The shim keeps the superclass chain loadable and keeps the subclasses
 * {@link LootSerializer}s (the type-holder shims take a LootSerializer argument).
 * Its 1.20.1 members (serialize/deserialize-with-conditions over JsonObject, the
 * m_6170_/m_7561_/m_6821_ SRG surface) are deliberately NOT re-declared: the
 * translated subclasses keep them as plain (renamed) methods and nothing on 1.21.1
 * dispatches through them — loot JSON serialization is not claimed.
 */
public abstract class LootItemConditionalFunctionSerializer<T extends LootItemConditionalFunction> implements LootSerializer<T> {

    public LootItemConditionalFunctionSerializer() {
    }
}
