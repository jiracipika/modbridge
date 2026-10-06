package net.minecraft.world.level.storage.loot;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;

/**
 * ModBridge shim carrier (loot lane): Forge 1.20.1's vanilla loot
 * {@code Serializer<T>} interface (Gson-JsonObject based). NeoForge 1.21.1 removed
 * it entirely (javap-verified: no {@code net/minecraft/world/level/storage/loot/Serializer}
 * in the 1.21.1 server jar) when loot conditions/functions/entries moved to
 * codec-based registration — the 1.21.1 {@code LootItemConditionType},
 * {@code LootPoolEntryType} and {@code LootItemFunctionType} are final records over
 * a {@code MapCodec}.
 *
 * Translated mod serializer implementers keep their compiled member shapes: javac
 * emitted SRG bridge methods ({@code m_6170_}/{@code m_7561_}) implementing this
 * interface, and the rewriter renames those declarations to the mojmap names
 * serialize/deserialize — exactly the members declared here, so a translated
 * implementer remains a true implementer of this shim (its Gson bodies stay
 * verifiable and would even dispatch if ever invoked).
 *
 * Nothing on 1.21.1 ever dispatches through this interface (loot JSON parsing is
 * codec-only), so the bodies are unreachable; loot-condition/function/entry
 * SERIALIZATION is explicitly not claimed (same acceptance as the inert capability
 * events — "semantics preserved for the measured surface only").
 */
public interface LootSerializer<T> {

    void serialize(JsonObject json, T value, JsonSerializationContext context);

    T deserialize(JsonObject json, JsonDeserializationContext context);
}
