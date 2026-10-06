package com.modbridge.shim;

import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): WallSkullBlock got the same
 * 1.21 ctor drift as SkullBlock ({@code (Type, Properties)} protected). Measured
 * subclass: supplementaries EndermanSkullWallBlock. Relocated per-mod.
 */
public class WallSkullBlockCompat extends WallSkullBlock {

    public WallSkullBlockCompat(BlockBehaviour.Properties properties, SkullBlock.Type type) {
        super(type, properties);
    }
}
