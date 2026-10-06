package com.modbridge.shim;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): inert marker for 1.20.1
 * forge's {@code PacketDistributor$PacketTarget} — flows through
 * SimpleChannel.send(PacketTarget, Object) call-site descriptors and the
 * PacketDistributor.noArg()/with(Supplier) return types. Constructed only by the
 * PacketDistributor shim; carries nothing (network play NOT claimed).
 */
public final class PacketDistributor$PacketTarget {

    public PacketDistributor$PacketTarget() {
    }
}
