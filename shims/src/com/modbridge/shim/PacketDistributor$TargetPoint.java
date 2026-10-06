package com.modbridge.shim;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): inert holder for 1.20.1
 * forge's {@code PacketDistributor$TargetPoint}. Measured surface: exactly one
 * constructor {@code (DDDD Lnet/minecraft/resources/ResourceKey;)V} — x, y, z,
 * radius, dimension — NEW'd directly by moonlight
 * ChannelHandlerImpl.lambda$sendToAllClientPlayersInRange$3. Fields stored, never
 * consumed (the NEAR send it feeds is suppressed).
 */
public class PacketDistributor$TargetPoint {

    public final double x;
    public final double y;
    public final double z;
    public final double r;
    public final ResourceKey<Level> dim;

    public PacketDistributor$TargetPoint(double x, double y, double z, double r,
            ResourceKey<Level> dim) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.dim = dim;
    }
}
