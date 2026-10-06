package com.modbridge.shim;

import java.util.function.Supplier;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): 1.20.1 forge's
 * {@code PacketDistributor} statics. Measured surface (moonlight
 * ChannelHandlerImpl send* methods — the only referrer): fields ALL, NEAR, PLAYER,
 * TRACKING_ENTITY, TRACKING_ENTITY_AND_SELF (GETSTATIC) + instance methods
 * noArg() and with(Supplier) producing {@link PacketDistributor$PacketTarget}.
 * The distributors are inert markers; no packet ever leaves (network play NOT
 * claimed). Other 1.20.1 fields (DIRECT, VANILLA...) were NOT referenced by either
 * trial jar and are deliberately absent (measure, don't guess).
 */
public class PacketDistributor {

    public static final PacketDistributor ALL = new PacketDistributor();
    public static final PacketDistributor NEAR = new PacketDistributor();
    public static final PacketDistributor PLAYER = new PacketDistributor();
    public static final PacketDistributor TRACKING_ENTITY = new PacketDistributor();
    public static final PacketDistributor TRACKING_ENTITY_AND_SELF = new PacketDistributor();

    private PacketDistributor() {
    }

    /** 1.20.1 surface — inert target. */
    public PacketDistributor$PacketTarget noArg() {
        return new PacketDistributor$PacketTarget();
    }

    /** 1.20.1 surface — inert target. */
    public PacketDistributor$PacketTarget with(Supplier<?> input) {
        return new PacketDistributor$PacketTarget();
    }
}
