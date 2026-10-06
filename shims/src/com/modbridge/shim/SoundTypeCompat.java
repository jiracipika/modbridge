package com.modbridge.shim;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): companion for the superswap of
 * moonlight's ModSoundType (a SoundType subclass). MEASURED call shape (live-boot
 * NSME): ModSoundType's ctor resolves its 1.20.1 lazy suppliers ITSELF and calls
 * super with {@code (float, float, SoundEvent x5)} — the 21.1 SoundType ctor is
 * PUBLIC with exactly that shape, so this carrier is a pure pass-through: it exists
 * only to receive the superswap-plumbing-retargeted super-call owner while keeping
 * the 21.1 SoundType as the real superclass. Relocated per-mod.
 */
public class SoundTypeCompat extends SoundType {

    public SoundTypeCompat(float volumeIn, float pitchIn, SoundEvent breakSound,
            SoundEvent stepSound, SoundEvent placeSound, SoundEvent hitSound,
            SoundEvent fallSound) {
        super(volumeIn, pitchIn, breakSound, stepSound, placeSound, hitSound, fallSound);
    }
}
