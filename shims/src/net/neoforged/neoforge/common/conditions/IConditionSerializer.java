package net.neoforged.neoforge.common.conditions;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (conditions lane): Forge 1.20.1's
 * {@code IConditionSerializer<T>} — the Gson read/write/getID interface recipes
 * used before NeoForge's codec rework — has no NeoForge 21.1 successor (javap
 * verified: absent from the 21.1 universal jar even after the standard package
 * rename; conditions are now registered as {@code MapCodec<? extends ICondition>}
 * values in {@code NeoForgeRegistries.Keys.CONDITION_CODECS} and dispatched by
 * {@code ICondition.CODEC}). The phase-1 rules deliberately left it unmapped, so
 * the bare forge→neoforged package fallback renamed references to a class that
 * does not exist — and the live boot died with NoClassDefFoundError when
 * {@code RegHelper} (the generic registration helper every mod registration goes
 * through) got linked: verifying its {@code registerSimpleRecipeCondition} body
 * forces the assignability constraint {@code OptionalRecipeCondition <:
 * IConditionSerializer}, which resolves the phantom.
 *
 * This shim keeps the exact 1.20.1 member surface (mojmap-style names — these are
 * forge-API names, no SRG involved): the translated implementer (moonlight
 * {@code OptionalRecipeCondition}, the only one measured across both trial jars)
 * keeps its compiled {@code write}/{@code read}/{@code getID} methods AND their
 * {@code ICondition}-erased bridges, so it remains a true implementer.
 *
 * Registration goes through the {@code CraftingHelper} shim's inert condition
 * registry; recipe-JSON condition PARSING of translated types fails loudly at
 * datapack-load time (see CraftingHelper#getCondition). Condition serialization
 * RUNTIME is explicitly not claimed (same acceptance as the inert capability
 * events and the loot-lane shims).
 *
 * Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim.
 */
public interface IConditionSerializer<T extends ICondition> {

    void write(JsonObject json, T value);

    T read(JsonObject json);

    ResourceLocation getID();

    /** 1.20.1 surface parity (forge's default body, mirrored). */
    default JsonObject getJson(T value) {
        JsonObject json = new JsonObject();
        json.addProperty("type", getID().toString());
        write(json, value);
        return json;
    }
}
