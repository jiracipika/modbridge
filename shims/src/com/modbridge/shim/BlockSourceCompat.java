package com.modbridge.shim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ModBridge shim carrier (dispense-source lane, phase 5.19): 1.20.1
 * {@code net.minecraft.core.BlockSourceImpl} (the lazy
 * {@code (ServerLevel, BlockPos)} BlockSource) has NO 21.1 counterpart - the
 * interface {@code net.minecraft.core.BlockSource} moved to
 * {@code net.minecraft.core.dispenser} and became a final RECORD with a
 * 4-arg constructor {@code (ServerLevel, BlockPos, BlockState,
 * DispenserBlockEntity)} (measured boot wall: supplementaries FireBehaviorsManager
 * registerBehaviors -> EnderPearlBehavior.fireInner `new BlockSourceImpl` NCDFE
 * at class-verify time). This factory resolves what BlockSourceImpl resolved
 * LAZILY, eagerly: block state + block entity at the position, then hands back
 * the real record - so every consumer call site (typed against the record by
 * the BlockSource typerename) keeps working unchanged. Relocated per-mod like
 * every shim; ctor-retarget singleton shape (GETSTATIC INSTANCE; args;
 * INVOKEVIRTUAL of).
 */
public final class BlockSourceCompat {

    private BlockSourceCompat() {
    }

    public static final BlockSourceCompat INSTANCE = new BlockSourceCompat();

    /** 1.20.1 `new BlockSourceImpl(level, pos)`: same source; state + block
     *  entity resolved eagerly. The record's 4th slot is DispenserBlockEntity -
     *  a non-dispenser target passes null (1.20.1 cast lazily and would have
     *  CCE'd only when blockEntity() was read; this lane never reads it, and
     *  null beats a construction-time CCE on a transient source). */
    public BlockSource of(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        DispenserBlockEntity blockEntity =
                level.getBlockEntity(pos) instanceof DispenserBlockEntity dbe ? dbe : null;
        return new BlockSource(level, pos, state, blockEntity);
    }
}
