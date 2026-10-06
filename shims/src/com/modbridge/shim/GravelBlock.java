package com.modbridge.shim;

import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import com.mojang.serialization.MapCodec;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 vanilla's
 * {@code net.minecraft.world.level.block.GravelBlock} is GONE on 1.21.1 (the
 * falling-block family was restructured; no renamed counterpart). Single measured
 * subclass: supplementaries RakedGravelBlock — super-ctor
 * {@code (BlockBehaviour$Properties)} (identical 1.20.1 GravelBlock shape) and one
 * super-call {@code defaultBlockState} (m_49966_), inherited from Block. Extends the REAL
 * 21.1 FallingBlock; implements the 21.1-added abstract {@code codec()} inertly
 * (null — block SERIALIZATION of the raked gravel is NOT claimed; falling behavior
 * comes from the real FallingBlock superclass where reachable).
 */
public class GravelBlock extends FallingBlock {

    public GravelBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** 21.1-added abstract member — inert: serialization not claimed. */
    @Override
    protected MapCodec<? extends FallingBlock> codec() {
        return null;
    }

}
