package com.modbridge.shim;

import net.minecraft.advancements.CriterionTrigger;

/**
 * ModBridge shim carrier (criteria lane, phase 5.9): 1.20.1's static
 * {@code CriteriaTriggers.register(CriterionTrigger)} is GONE on 1.21.1 — vanilla
 * triggers became static finals and custom ones register through
 * {@code BuiltInRegistries.TRIGGER_TYPES} with an explicit ResourceKey. The key
 * comes from the trigger on 1.20.1 (.getID()), but the 21.1 CriterionTrigger
 * interface exposes NO id accessor (javap: codec()/listener methods only), so the
 * registration cannot be performed faithfully from the object the 1.20.1 call site
 * passes — this adapter is an INERT pass-through: log-free, returns the trigger so
 * the caller's field assignment keeps its value (moonlight MoonlightRegistry
 * &lt;clinit&gt;, the single measured call site: m_10595_ x1).
 *
 * Advancement-criteria REGISTRATION/RUNTIME is not claimed (same acceptance as the
 * loot-, condition-, and criteria-lane shims): the trigger instance survives, the
 * registry entry does not exist. Routed by the memberretargets "static" kind
 * (INVOKESTATIC -> INVOKESTATIC, desc unchanged).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim.
 */
public final class CriteriaTriggersCompat {

    private CriteriaTriggersCompat() {
    }

    /** 1.20.1 {@code CriteriaTriggers.register} surface — inert pass-through. */
    public static CriterionTrigger register(CriterionTrigger trigger) {
        return trigger;
    }
}
