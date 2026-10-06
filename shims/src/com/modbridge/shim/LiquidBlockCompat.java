package com.modbridge.shim;

import java.util.function.Supplier;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 changed LiquidBlock's ctor
 * from {@code (Supplier<FlowingFluid>, Properties)} to
 * {@code (FlowingFluid, Properties)} PROTECTED. Measured subclasses: moonlight
 * ModLiquidBlock + supplementaries FiniteLiquidBlock. The shim resolves the
 * supplier at construction. Relocated per-mod.
 */
public class LiquidBlockCompat extends LiquidBlock {

    public LiquidBlockCompat(Supplier<?> fluidSupplier, BlockBehaviour.Properties properties) {
        super((FlowingFluid) fluidSupplier.get(), properties);
    }
}
