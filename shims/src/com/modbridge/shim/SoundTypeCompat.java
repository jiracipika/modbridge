package com.modbridge.shim;

import java.util.function.Supplier;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 changed SoundType's ctor
 * from {@code (float, float, Supplier<SoundEvent> x5)} to
 * {@code (float, float, SoundEvent x5)} (suppliers resolved eagerly). Measured
 * subclass: moonlight ModSoundType (moonlight's API passes lazy sound suppliers).
 * The shim resolves all five at construction (boot-time clinit — the SoundEvents
 * registered by then are the deferred-register suppliers moonlight passes, same
 * as the 1.20.1 lazy contract's eventual resolution). Relocated per-mod.
 */
public class SoundTypeCompat extends SoundType {

    public SoundTypeCompat(float volumeIn, float pitchIn, Supplier<?> breakSound,
            Supplier<?> stepSound, Supplier<?> placeSound, Supplier<?> hitSound,
            Supplier<?> fallSound) {
        super(volumeIn, pitchIn,
                (SoundEvent) breakSound.get(),
                (SoundEvent) stepSound.get(),
                (SoundEvent) placeSound.get(),
                (SoundEvent) hitSound.get(),
                (SoundEvent) fallSound.get());
    }
}
