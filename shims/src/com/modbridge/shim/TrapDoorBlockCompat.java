package com.modbridge.shim;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): TrapDoorBlock got the same
 * 1.21 ctor drift as DoorBlock — {@code (BlockSetType, Properties)} PROTECTED
 * (1.20.1: {@code (Properties, BlockSetType)} public). Measured surface:
 * supplementaries GoldTrapdoorBlock + NetheriteTrapdoorBlock. Pure forward (the
 * caller's explicit BlockSetType passes through); codec() concrete on the 21.1
 * parent. Relocated per-mod like every shim.
 */
public class TrapDoorBlockCompat extends TrapDoorBlock {

    public TrapDoorBlockCompat(BlockBehaviour.Properties properties, BlockSetType setType) {
        super(setType, properties);
    }
}
