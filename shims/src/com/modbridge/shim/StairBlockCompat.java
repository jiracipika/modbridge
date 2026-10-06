package com.modbridge.shim;

import java.util.function.Supplier;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 changed StairBlock's ctor
 * from {@code (Supplier<BlockState>, Properties)} to
 * {@code (BlockState, Properties)} PROTECTED. Measured subclass: moonlight
 * ModStairBlock (its moonlight API passes state suppliers). The shim resolves the
 * supplier at construction — same eager semantics the 21.1 parent expects.
 * Relocated per-mod.
 */
public class StairBlockCompat extends StairBlock {

    public StairBlockCompat(Supplier<?> stateSupplier, BlockBehaviour.Properties properties) {
        super((BlockState) stateSupplier.get(), properties);
    }
}
