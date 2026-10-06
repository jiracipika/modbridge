package net.minecraft.world.level.storage.loot.entries;

import net.minecraft.world.level.storage.loot.LootSerializer;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's inner-class serializer base
 * {@code LootPoolSingletonContainer$Serializer<T>} (itself extending the equally
 * removed {@code LootPoolEntryContainer$Serializer}) has no 1.21.1 successor — pool
 * entries are codec-based there. moonlight's {@code OptionalItemPool$Serializer}
 * extends it (measured: 1 subclass across the trial jars).
 *
 * The shim keeps the superclass chain loadable and keeps the subclass a
 * {@link LootSerializer} (the type-holder shims take a LootSerializer argument).
 * Its 1.20.1 members (serializeCustom/deserializeCustom over JsonObject, the
 * m_7219_/m_5921_/m_7267_ SRG surface) are deliberately NOT re-declared: the
 * translated subclass keeps them as plain (renamed) methods and nothing on 1.21.1
 * dispatches through them — loot JSON serialization is not claimed.
 */
public abstract class LootPoolSingletonContainerSerializer<T extends LootPoolSingletonContainer> implements LootSerializer<T> {

    public LootPoolSingletonContainerSerializer() {
    }
}
