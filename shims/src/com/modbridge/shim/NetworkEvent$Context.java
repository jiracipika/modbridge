package com.modbridge.shim;

import java.util.concurrent.CompletableFuture;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): 1.20.1 forge's
 * {@code NetworkEvent$Context} — the packet-handler callback context. The TYPE must
 * exist for linking even on an inert path: moonlight's handler wrapper carries it in
 * an erased ctor descriptor + a CHECKCAST (op 192) + a lambda descriptor, and the
 * verifier resolves descriptor types at class-load time.
 *
 * Measured member surface (moonlight ChannelHandlerImpl + Wrapper): enqueueWork,
 * getDirection, getNetworkManager, getSender, setPacketHandled. Instances are only
 * produced by real packet dispatch, which the inert facade never performs — the
 * members exist for verification/linking; enqueueWork runs inline if ever reached.
 */
public class NetworkEvent$Context {

    private boolean packetHandled;

    public NetworkEvent$Context() {
    }

    /** 1.20.1 surface — runs inline (no main-thread queue exists on the shim path). */
    public CompletableFuture<Void> enqueueWork(Runnable runnable) {
        runnable.run();
        return CompletableFuture.completedFuture(null);
    }

    /** 1.20.1 surface. */
    public NetworkDirection getDirection() {
        return NetworkDirection.PLAY_TO_SERVER;
    }

    /** 1.20.1 surface — null on the inert path (no connection backs a shim). */
    public Connection getNetworkManager() {
        return null;
    }

    /** 1.20.1 surface — null on the inert path (no player backs a shim). */
    public ServerPlayer getSender() {
        return null;
    }

    /** 1.20.1 surface. */
    public void setPacketHandled(boolean packetHandled) {
        this.packetHandled = packetHandled;
    }
}
