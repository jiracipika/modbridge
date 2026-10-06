package com.modbridge.shim;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * ModBridge shim carrier (event lane, phase 5.10): forge 1.20.1's
 * {@code LivingEvent$LivingTickEvent} has NO 21.1 equivalent — the tick events were
 * restructured into net.neoforged.neoforge.event.tick (EntityTickEvent Pre/Post,
 * PlayerTickEvent, ...) with no living-specific tick event. Measured surface
 * (supplementaries ServerEventsForge.onLivingTick, the single referrer): the handler
 * signature + one getEntity():LivingEntity call. This carrier extends the REAL
 * {@link EntityTickEvent.Post} with a covariant getEntity() (the tick subject of a
 * living tick is always a LivingEntity — the cast documents that assumption), so the
 * translated handler LINKS and REGISTERS cleanly. FIRING is not bridged: the bus
 * dispatches EntityTickEvent.Post instances, which are not IS-A this shim — the
 * living-tick hook is documented dead (no 21.1 event to hook). Relocated per-mod.
 */
public class LivingTickEvent extends EntityTickEvent.Post {

    public LivingTickEvent(net.minecraft.world.entity.Entity entity) {
        super(entity);
    }

    /** 1.20.1 surface — covariant: living-tick subjects are LivingEntities. */
    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }
}
