package com.modbridge.shim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): inert 1.20.1
 * {@code SimpleChannel} surface. Measured member surface (moonlight
 * ChannelHandlerImpl, the ONLY referrer in both trial jars):
 * {@code registerMessage(int,Class,BiConsumer,Function,BiConsumer,Optional)},
 * {@code send(PacketTarget,Object)}, {@code sendToServer(Object)}.
 *
 * registerMessage stores the (type, discriminator) pair so callers can assert
 * registration happened; the return is the {@link MessageHandler} marker (the
 * erased call-site return type rides the typerename — the mod discards it).
 * Both send paths log once and drop: a dedicated-server boot never exercises
 * them, and network play is explicitly NOT claimed. All real dispatch waits for
 * the payload-registrar bridge slice.
 */
public class SimpleChannelCompat {

    private static boolean warned = false;

    private final ResourceLocation name;
    public final List<String> registrations = new ArrayList<>();

    public SimpleChannelCompat(ResourceLocation name) {
        this.name = name;
    }

    /** 1.20.1 surface — stores the mapping, returns the marker the desc expects. */
    public MessageHandler registerMessage(int discriminator, Class<?> messageType,
            BiConsumer<?, ?> encoder, Function<?, ?> decoder, BiConsumer<?, ?> handler,
            Optional<?> direction) {
        registrations.add(messageType.getName() + "#" + discriminator);
        return new MessageHandler();
    }

    /** 1.20.1 surface — suppressed. */
    public void send(PacketDistributor$PacketTarget target, Object message) {
        warnOnce("send");
    }

    /** 1.20.1 surface — suppressed. */
    public void sendToServer(Object message) {
        warnOnce("sendToServer");
    }

    private static void warnOnce(String op) {
        if (!warned) {
            warned = true;
            System.out.println("[ModBridge shim] network " + op
                    + " suppressed (inert facade; network play NOT claimed)");
        }
    }
}
