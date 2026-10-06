package com.modbridge.shim;

import java.util.Map;

import net.minecraft.core.cauldron.CauldronInteraction;

/**
 * ModBridge shim carrier (setup lane, phase 5.12): 1.20.1's
 * {@code CauldronInteraction.WATER/LAVA/EMPTY/BANNER/DYED_ITEM} were public static
 * {@code java.util.Map}s (mods read and put interactions directly); 21.1 turned
 * them into {@code InteractionMap} records. Field-retarget target (getstatic kept):
 * these static fields mirror the LIVE inner maps at clinit, so the translated
 * reads AND puts land on the real 21.1 interaction maps (the put path is the
 * actual 1.20.1 registration semantic, preserved by reference). Erased generics:
 * the 1.20.1 code's key/value types ride the type rules. Relocated per-mod like
 * every shim.
 */
public final class CauldronCompat {

    private CauldronCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map rawMap(CauldronInteraction.InteractionMap m) {
        return (Map) m.map();
    }

    public static final Map<Object, Object> EMPTY = rawMap(CauldronInteraction.EMPTY);
    public static final Map<Object, Object> WATER = rawMap(CauldronInteraction.WATER);
    // BANNER/DYED_ITEM stay plain CauldronInteraction VALUES on 21.1 (same name+desc):
    // the 1.20.1 code PUTS them into the maps as values, so no shim field needed.
}
