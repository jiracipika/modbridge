package net.minecraftforge.eventbus.api;

import java.util.function.Consumer;

/** 1.20.1 Forge API stub (real interface: net.minecraftforge.eventbus.api.IEventBus). */
public interface IEventBus {
    <T extends Event> void addListener(Consumer<T> listener);
}
