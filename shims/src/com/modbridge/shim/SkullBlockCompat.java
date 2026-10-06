package com.modbridge.shim;

import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * ModBridge shim carrier (blocks lane, phase 5.11): 1.21 swapped SkullBlock's ctor
 * to {@code (SkullBlock$Type, Properties)} PROTECTED (1.20.1:
 * {@code (Properties, SkullBlock$Type)}). Measured subclass: supplementaries
 * EndermanSkullBlock. The caller's explicit Type passes through. codec() concrete
 * on the 21.1 parent. Relocated per-mod.
 */
public class SkullBlockCompat extends SkullBlock {

    public SkullBlockCompat(BlockBehaviour.Properties properties, SkullBlock.Type type) {
        super(type, properties);
    }
}
