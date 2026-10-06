package com.modbridge.shim;

import com.google.gson.JsonObject;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 forge's
 * {@code net.minecraftforge.common.crafting.IIngredientSerializer} is GONE on 21.1
 * (ingredients became codec-based; no serializer registry). Measured surface: the
 * single implementer is moonlight's BlockTypeSwapIngredientImpl$1 anon declaring
 * parse(FriendlyByteBuf)/parse(JsonObject)/write(FriendlyByteBuf, ...) against the
 * 1.20.1 shapes, plus the getSerializer() return type on the ingredient class.
 * The interface exists so the implementer links; serializer REGISTRATION and the
 * JSON recipes it would serve are NOT claimed (block_type_swap stays a documented
 * dead path, same acceptance as the loot/condition lanes). Relocated per-mod.
 */
public interface IIngredientSerializer<T extends Ingredient> {

    /** 1.20.1 surface. */
    T parse(FriendlyByteBuf buffer);

    /** 1.20.1 surface. */
    T parse(JsonObject json);

    /** 1.20.1 surface. */
    void write(FriendlyByteBuf buffer, T ingredient);
}
