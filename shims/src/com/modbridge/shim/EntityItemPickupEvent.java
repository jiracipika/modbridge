package com.modbridge.shim;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.item.ItemEvent;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * ModBridge shim carrier (event lane, phase 5.10): forge 1.20.1's
 * {@code EntityItemPickupEvent} has NO 21.1 equivalent (the item-event package kept
 * only ItemEvent/ItemExpireEvent/ItemTossEvent — pickup detection moved). Measured
 * surface (supplementaries ServerEventsForge.onItemPickup, the single referrer):
 * isCanceled/setCanceled, getResult/setResult(Event.Result), getItem(),
 * getEntity():Player. This carrier extends the REAL {@link PlayerEvent} (1.20.1
 * parentage; ctor (Player, ItemEntity)) and carries its own inert
 * cancellation/result state. FIRING is documented dead — no 21.1 event exists to
 * dispatch it. The nested Result enum stands in for forge's removed
 * EventBusApi Event.Result.
 */
public class EntityItemPickupEvent extends PlayerEvent implements ICancellableEvent {

    public enum Result {
        DENY,
        DEFAULT,
        ALLOW
    }

    private final Player player;
    private boolean canceled;
    private Result result = Result.DEFAULT;

    private final ItemEntity item;

    public EntityItemPickupEvent(Player player, ItemEntity item) {
        super(player);
        this.player = player;
        this.item = item;
    }

    /** 1.20.1 surface — covariant: the pickup subject is the player. */
    @Override
    public Player getEntity() {
        return player;
    }

    /** 1.20.1 surface — the picked-up item entity. */
    public ItemEntity getItem() {
        return item;
    }

    /** 1.20.1 surface — inert (never fired). */
    public boolean isCanceled() {
        return canceled;
    }

    /** 1.20.1 surface — inert (never fired). */
    @Override
    public void setCanceled(boolean cancel) {
        canceled = cancel;
    }

    /** 1.20.1 surface — inert Result stand-in (forge's Event.Result is gone). */
    public Result getResult() {
        return result;
    }

    /** 1.20.1 surface — inert Result stand-in. */
    public void setResult(Result result) {
        this.result = result;
    }
}
