package net.neoforged.neoforge.common.crafting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.IConditionSerializer;

/**
 * ModBridge shim carrier (conditions lane): Forge 1.20.1's {@code CraftingHelper}
 * was the static facade for serializer registration and condition parsing. The
 * real NeoForge 21.1 class still exists but was completely reshaped — only
 * ingredient-codec builders remain; ALL of the members the translated mods call
 * are gone (javap measured, 4 members referenced across the raw jars):
 *
 * - {@code register(IConditionSerializer)} — moonlight RegHelperImpl's
 *   registerSimpleRecipeCondition (the boot-wall call site);
 * - {@code getCondition(JsonObject)} — PlatHelperImpl + TemplateRecipeManagerImpl;
 * - {@code register(ResourceLocation, IIngredientSerializer)} /
 *   {@code getID(IIngredientSerializer)} — moonlight BlockTypeSwapIngredientImpl
 *   (the ingredient-serializer family is a DIFFERENT removed surface, still
 *   package-renamed phantom: not this lane's scope — these two members cannot be
 *   carried here without shimming IIngredientSerializer too; their call sites
 *   fail lazily at that class's load, same as the pre-shim baseline).
 *
 * register(): 1.20.1 forge stored the serializer in
 * ForgeRegistries.CONDITION_SERIALIZERS and returned it. The shim keeps the
 * return-value contract and stores it in an inert per-mod registry — the
 * serializer is reachable for the mod's own registration flow, nothing dispatches
 * through it (21.1 dispatches condition codecs, which a Gson serializer cannot
 * provide; synthesizing one is the deferred codec bridge).
 *
 * getCondition(): FAITHFUL where it can be — 21.1's {@code ICondition.CODEC}
 * dispatches by "type" over the registered condition codecs, so known
 * vanilla/NeoForge condition JSON parses for real. Translated mod conditions have
 * no codec: the failure is loud and names the condition id (logged per-recipe at
 * datapack load; same not-claimed acceptance as the loot-lane shims).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim (the
 * real 21.1 CraftingHelper at the platform name is never shadowed for platform
 * code; typerename sends ALL mod references here).
 */
public final class CraftingHelper {

    private static final Map<ResourceLocation, IConditionSerializer<?>> CONDITION_SERIALIZERS =
            new LinkedHashMap<>();

    private CraftingHelper() {
    }

    /** 1.20.1 surface: store + return the serializer (inert per-mod registry). */
    public static IConditionSerializer<?> register(IConditionSerializer<?> serializer) {
        CONDITION_SERIALIZERS.put(serializer.getID(), serializer);
        return serializer;
    }

    /**
     * 1.20.1 surface, faithful for known condition types; loud for translated ones.
     * The codec dispatch lives in {@code ICondition.CODEC}, which is only usable
     * once NeoForge's registry bootstrap has populated CONDITION_CODECS (always the
     * case at live datapack-load time); any failure — unknown/translated type or
     * un-bootstrapped environment — is converted into the loud ModBridge error
     * naming the condition id.
     */
    public static ICondition getCondition(JsonObject json) {
        String id = json.has("type") && json.get("type").isJsonPrimitive()
                ? json.get("type").getAsString()
                : "<missing type>";
        try {
            Optional<ICondition> parsed = ICondition.CODEC.parse(JsonOps.INSTANCE, json).result();
            if (parsed.isPresent()) {
                return parsed.get();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through: known types parse above; everything else may still be a
            // translated serializer registered through register() (phase 5.14)
        }
        // phase 5.14 (measured wall: supp's "supplementaries:flag" condition fatally
        // aborting the resource-conditions mixin at datapack load): the translated
        // serializers mods registered through {@link #register} ARE present in the
        // inert registry — their {@code read(json)} returns a translated ICondition
        // that implements the REAL 21.1 interface (the 1.20.1 forge ICondition was
        // typerenamed onto it, same test(IContext) shape), so the condition evaluates
        // for real (flag conditions read the mod's translated config machinery).
        IConditionSerializer<?> serializer = CONDITION_SERIALIZERS.get(parseId(id));
        if (serializer != null) {
            return serializer.read(json);
        }
        throw new IllegalStateException(message(id));
    }

    private static ResourceLocation parseId(String id) {
        int i = id.indexOf(':');
        if (i <= 0 || i == id.length() - 1) {
            throw new IllegalStateException(message(id));
        }
        return ResourceLocation.fromNamespaceAndPath(id.substring(0, i), id.substring(i + 1));
    }

    /**
     * Phase 5.14 (measured wall): the third 1.20.1 member — moonlight's
     * resource-conditions mixin (applyResourceConditions, injected into vanilla's
     * SimplePreparableReloadListener) calls
     * {@code processConditions(JsonObject, String, ICondition.Context)} during the
     * datapack reload to filter conditioned resources. 1.20.1 semantics: a missing
     * (or null) key means "load"; otherwise every condition under the key must test
     * true. Conditions parse through the same codec path as {@link #getCondition}
     * (faithful for known vanilla/NeoForge types, loud for translated ones). A
     * singleton object in place of an array is accepted (1.20.1 Forge allowed it).
     */
    public static boolean processConditions(JsonObject json, String key, ICondition.IContext context) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return true;
        }
        JsonElement cond = json.get(key);
        if (cond.isJsonArray()) {
            for (JsonElement e : cond.getAsJsonArray()) {
                if (!getCondition(e.getAsJsonObject()).test(context)) {
                    return false;
                }
            }
            return true;
        }
        return getCondition(cond.getAsJsonObject()).test(context);
    }

    private static String message(String id) {
        return "ModBridge: cannot parse recipe condition of type '" + id
                + "': translated 1.20.1 condition serializers carry no 21.1 condition codec "
                + "(condition runtime is not claimed)";
    }

    /** exposed for tests: the inert registry contents. */
    public static Map<ResourceLocation, IConditionSerializer<?>> registeredConditionSerializers() {
        return CONDITION_SERIALIZERS;
    }
}
