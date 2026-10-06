package com.modbridge.shim;

import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 swapped DoorBlock's ctor
 * parameter order to {@code (BlockSetType, Properties)} and made it PROTECTED
 * (1.20.1: {@code (Properties, BlockSetType)} public). Measured surface:
 * supplementaries GoldDoorBlock + NetheriteDoorBlock extend DoorBlock with 1-arg
 * (Properties) ctors that super-call the 1.20.1 two-arg shape (defaulting the
 * BlockSetType — both are metal doors = IRON type on 1.20.1's conventions).
 * This carrier extends the REAL 21.1 DoorBlock, carries the 1.20.1 ctor shape
 * (the superswap plumbing retargets the super-call owner to it, desc unchanged)
 * and delegates with the swapped order + IRON default. Door TYPE fidelity is
 * best-effort (both subclasses are metal doors); wood-set doors would need a
 * measured default per subclass. The 21.1-added codec() is inherited from
 * DoorBlock (concrete there). Relocated per-mod like every shim.
 */
public class DoorBlockCompat extends DoorBlock {

    public DoorBlockCompat(BlockBehaviour.Properties properties, BlockSetType setType) {
        super(setType, properties);
    }
}
