package com.modbridge.shim;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;

/**
 * ModBridge shim carrier (food lane, phase 5.18): 1.20.1
 * {@code Item.isEdible()} / {@code Item.getFoodProperties()} are GONE on 21.1
 * (measured boot wall: supplementaries ThrowableSlimeballBehavior.appliesToItem
 * NSME in the tags-updated event) - food became the
 * {@code DataComponents.FOOD} component (1.21.1 single-component shape; the
 * FOOD/CONSUMABLE split is 1.21.2+).
 *
 * <p>Dispatch-preserving: translated mod classes still DEFINE overrides of both
 * members (the SRG rename lands {@code isEdible}/{@code getFoodProperties} on
 * the mod class - measured: ml BlockPlacerItem mimics food through exactly
 * these), so the bridge duck-types the receiver's class for a renamed override
 * FIRST (public {@code getMethod} probe, cached per class - the proven ObfRefl
 * idiom) and only falls back to the component map for plain vanilla items.
 * A plain static lookup without the probe would silently bypass mod food
 * mimicry; an unruled call site would NSME at first food-item interaction.</p>
 *
 * <p>|static retarget, receiver-as-first-param (the proven DyeColorCompat
 * idiom): stack- and frame-neutral. Relocated per-mod like every shim.</p>
 */
public final class ItemFoodCompat {

    private ItemFoodCompat() {
    }

    private static final Map<Class<?>, Optional<Method>> IS_EDIBLE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> GET_FOOD = new ConcurrentHashMap<>();

    /** 1.20.1 Item.isEdible(): renamed mod override if the receiver's class has
     *  one, else FOOD-component presence. */
    public static boolean isEdible(Item item) {
        Method override = IS_EDIBLE
                .computeIfAbsent(item.getClass(), c -> probe(c, "isEdible"))
                .orElse(null);
        if (override != null) {
            return (Boolean) invoke(override, item);
        }
        return item.components().has(DataComponents.FOOD);
    }

    /** 1.20.1 Item.getFoodProperties(): renamed mod override first, else the
     *  FOOD component (nullable, like the 1.20.1 field-backed getter). */
    public static FoodProperties getFoodProperties(Item item) {
        Method override = GET_FOOD
                .computeIfAbsent(item.getClass(), c -> probe(c, "getFoodProperties"))
                .orElse(null);
        if (override != null) {
            return (FoodProperties) invoke(override, item);
        }
        return item.components().get(DataComponents.FOOD);
    }

    /** the rewriter renames overridden members by NAME table, so a mod override
     *  is a public method with the 1.20.1 name on the receiver's own class;
     *  21.1 Item has neither member, so a hit is always a mod override */
    private static Optional<Method> probe(Class<?> c, String name) {
        try {
            return Optional.of(c.getMethod(name));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    private static Object invoke(Method m, Item item) {
        try {
            return m.invoke(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mod food override invoke failed: " + m, e);
        }
    }
}
