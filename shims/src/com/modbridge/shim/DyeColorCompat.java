package com.modbridge.shim;

import net.minecraft.world.item.DyeColor;

/**
 * ModBridge shim carrier (setup lane, phase 5.12): 1.20.1 vanilla
 * {@code DyeColor.getTextureDiffuseColors()} returned the packed texture color as a
 * float[3] ({r,g,b} in 0..1); 21.1 renamed it to the singular
 * {@code getTextureDiffuseColor():int} (one ARGB int). This static-kind retarget
 * target keeps the 1.20.1 call shape and unpacks the int back into the 1.20.1
 * float triple (same packed value, so the conversion is exact). Measured sites:
 * supp BookType (placeable books), ColoredDecoration; moonlight
 * FlagBlockTileRenderer. Relocated per-mod like every shim.
 */
public final class DyeColorCompat {

    private DyeColorCompat() {
    }

    public static float[] getTextureDiffuseColors(DyeColor color) {
        int packed = color.getTextureDiffuseColor();
        return new float[]{
                ((packed >> 16) & 0xFF) / 255.0f,
                ((packed >> 8) & 0xFF) / 255.0f,
                (packed & 0xFF) / 255.0f
        };
    }
}
