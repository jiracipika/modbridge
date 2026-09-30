package net.minecraftforge.registries;

import java.util.function.Supplier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraftforge.eventbus.api.IEventBus;

/** 1.20.1 Forge API stub (real class: net.minecraftforge.registries.DeferredRegister). */
public class DeferredRegister<T> {
    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String modName) {
        return null;
    }
    public void register(IEventBus bus) {}
    public <R extends T> RegistryObject<R> register(String name, Supplier<? extends R> sup) { return null; }
}
