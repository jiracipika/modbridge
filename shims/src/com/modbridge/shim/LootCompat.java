package com.modbridge.shim;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;

/**
 * ModBridge shim (phase 5.14, the loot-inject lane): 1.20.1's
 * {@code LootTableReference.lootTableReference(ResourceLocation)} moved twice on the
 * way to 1.21.1 - the class was renamed {@code NestedLootTable} (1.20.5) AND the
 * factory's parameter widened from {@link ResourceLocation} to
 * {@code ResourceKey<LootTable>} (javap'd both vanilla jars; the single measured
 * call site is moonlight RegHelperImpl$1.addTableReference, fed by supp
 * LootTablesInjects with the {@code BuiltInLootTables} ids the field-unwrap pass
 * already restored to ResourceLocations).
 *
 * <p>The bridge is exact: the same registry the 21.1 factory dispatches through is
 * {@code minecraft:loot_table}, so {@code ResourceKey.create(LOOT_TABLE.key(), loc)}
 * reconstructs the key the caller's ResourceLocation names. Shared (owner moonlight)
 * because the loot-inject API crosses mod boundaries: supp's handlers run inside
 * moonlight's RegHelper lambdas.
 */
public final class LootCompat {

    private LootCompat() {}

    public static LootPoolSingletonContainer.Builder<?> lootTableReference(ResourceLocation table) {
        return NestedLootTable.lootTableReference(
                ResourceKey.create(Registries.LOOT_TABLE, table));
    }
}
