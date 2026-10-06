package com.modbridge.shim;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 vanilla's
 * {@code net.minecraft.world.item.RecordItem} is GONE on 1.21.1 (music discs became
 * data-driven JUKEBOX_SONG holders; no renamed class). Measured surface across
 * supplementaries: {@code PancakeItem extends RecordItem} with the 4-arg
 * super-ctor {@code (int, SoundEvent, Item.Properties, int)} and one
 * super-call {@code useOn} (m_6225_ via the SRG table); plus INSTANCEOF
 * (ModCreativeTabs) and return-type positions through moonlight's
 * newMusicDisc API. Extends the REAL 21.1 Item so inherited behavior
 * (useOn super-call) stays live; the disc/jukebox registration itself is data-driven
 * on 21.1 and NOT claimed (the translated disc item functions as a plain Item).
 * Relocated per-mod like every shim.
 */
public class RecordItem extends Item {

    private final int comparatorValue;
    private SoundEvent sound;
    private java.util.function.Supplier<SoundEvent> soundSupplier;

    public RecordItem(int comparatorValue, SoundEvent sound, Item.Properties properties,
            int useDuration) {
        super(properties);
        this.comparatorValue = comparatorValue;
        this.sound = sound;
    }

    /** the moonlight newMusicDisc shape (PlatHelperImpl passes the disc sound as a
     *  SUPPLIER - the 1.20.1 vanilla RecordItem ctor took Supplier<SoundEvent>);
     *  resolved lazily: sound events register before items, but the supplier may be
     *  a registration wrapper */
    public RecordItem(int comparatorValue, java.util.function.Supplier<SoundEvent> sound,
            Item.Properties properties, int useDuration) {
        super(properties);
        this.comparatorValue = comparatorValue;
        this.soundSupplier = sound;
    }

    /** 1.20.1 RecordItem surface (m_6422_-family accessor), inert on 21.1. */
    public int getComparatorValue() {
        return comparatorValue;
    }

    /** 1.20.1 RecordItem surface, inert on 21.1. */
    public SoundEvent getSound() {
        return sound != null ? sound : (soundSupplier != null ? soundSupplier.get() : null);
    }
}
