package com.modbridge.fixture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

/** Second class so the rewriter must handle cross-class member and type references. */
public final class Aux {
    private Aux() {}

    public static String describe(RegistryObject<Item> registryObject, ResourceLocation id) {
        if (registryObject.isPresent() && id.getNamespace().equals(FixtureMod.MODID)) {
            return registryObject.getId() + " -> " + registryObject.get();
        }
        return "unresolved:" + id;
    }
}
