package net.minecraft.advancements.critereon;

import java.util.Optional;

import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (criteria lane): Forge 1.20.1's
 * {@code AbstractCriterionTriggerInstance} — the abstract base class every custom
 * advancement-criterion instance extended — does not exist on NeoForge 1.21.1
 * (javap-verified): the class was FOLDED into the interface
 * {@link SimpleCriterionTrigger.SimpleInstance}, which requires
 * {@code player(): Optional<ContextAwarePredicate>} and inherits
 * {@code validate(CriterionValidator)} (default) from
 * {@code CriterionTriggerInstance}.
 *
 * The shim implements the real 21.1 SimpleInstance, so every translated subclass
 * is transitively IS-A {@code SimpleInstance} and IS-A
 * {@code CriterionTriggerInstance} for the platform's checks — that is exactly
 * what the boot needs: linking moonlight's {@code GrindItemTrigger} verifies its
 * methods against the generic bound {@code SimpleCriterionTrigger<T extends
 * SimpleInstance>} and resolves {@code GrindItemTrigger$Instance}'s hierarchy
 * (the live boot died with NoClassDefFoundError precisely there).
 *
 * Carried members (measured, 1.20.1 surface with the SRG table's mojmap names):
 * the {@code (ResourceLocation, ContextAwarePredicate)} super-constructor the
 * translated subclasses invoke, {@link #getCriterion()} (m_7294_),
 * {@link #getPlayerPredicate()} (m_285924_), and {@link #player()} — the one
 * abstract 21.1 SimpleInstance member, implemented faithfully from the stored
 * predicate.
 *
 * Deliberately NOT carried: {@code serializeToJson(SerializationContext)}
 * (m_7683_) and the whole JSON criteria surface —
 * {@code SerializationContext}/{@code DeserializationContext} no longer exist on
 * 1.21.1 (codec-based criteria), so the member is uncompilable AND unreachable;
 * the translated subclasses' super-calls into it fail lazily at criterion
 * serialization time. Trigger FIRING is also deferred (member-level drift inside
 * the triggers themselves, e.g. ItemPredicate.matches -> test). Advancement
 * criteria RUNTIME is explicitly not claimed (same acceptance as the loot- and
 * condition-lane shims).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim. Measured
 * subclass surface across the trial jars: exactly one — moonlight
 * {@code GrindItemTrigger$Instance} (supplementaries has none).
 */
public abstract class AbstractCriterionTriggerInstance implements SimpleCriterionTrigger.SimpleInstance {

    private final ResourceLocation id;
    private final ContextAwarePredicate playerPredicate;

    public AbstractCriterionTriggerInstance(ResourceLocation id, ContextAwarePredicate playerPredicate) {
        this.id = id;
        this.playerPredicate = playerPredicate;
    }

    /** the one abstract 21.1 SimpleInstance member — faithful from the ctor param. */
    @Override
    public Optional<ContextAwarePredicate> player() {
        return Optional.ofNullable(playerPredicate);
    }

    /** 1.20.1 surface (m_7294_): the criterion id. */
    public ResourceLocation getCriterion() {
        return id;
    }

    /** 1.20.1 surface (m_285924_): the player predicate. */
    protected ContextAwarePredicate getPlayerPredicate() {
        return playerPredicate;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + "]";
    }
}
