package net.neoforged.fml.javafmlmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;

/**
 * ModBridge shim carrier (phase 5.5): Forge 1.20.1's FMLJavaModLoadingContext — the
 * service locator for the mod event bus — does not exist on NeoForge 21.1. Inside
 * @Mod constructors the rewriter splices the lookup chain into the injected bus
 * parameter, but real mods call FMLJavaModLoadingContext.get().getModEventBus() from
 * arbitrary classes too: moonlight's ConfigSpecWrapper registers its config-load
 * listeners this way, and the phase 5.5 boot died with NoClassDefFoundError at
 * exactly that call — AFTER ModLoadingContext.get().getActiveContainer() had already
 * resolved fine on the same construction thread.
 *
 * getModEventBus() therefore routes through the ACTIVE container's real bus: during
 * mod construction (the only time 1.20.1 code reaches this) FML's active container is
 * the owning mod, so listeners land on the same bus the @Mod ctor splice injects.
 */
public final class FMLJavaModLoadingContext {

    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    private FMLJavaModLoadingContext() {
    }

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        return container.getEventBus();
    }
}
