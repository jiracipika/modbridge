package com.modbridge.shim;

import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.WoodType;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 swapped FenceGateBlock's
 * ctor parameter order to {@code (WoodType, Properties)} (1.20.1:
 * {@code (Properties, WoodType)}). Measured subclass: supplementaries IronGateBlock
 * (super-call desc (Properties, WoodType) — the IRON WoodType passes through the
 * forward). codec() concrete on the 21.1 parent. Relocated per-mod.
 */
public class FenceGateBlockCompat extends FenceGateBlock {

    public FenceGateBlockCompat(BlockBehaviour.Properties properties, WoodType woodType) {
        super(woodType, properties);
    }
}
