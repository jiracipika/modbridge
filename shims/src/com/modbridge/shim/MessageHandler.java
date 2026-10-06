package com.modbridge.shim;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): marker standing in for
 * 1.20.1's {@code IndexedMessageCodec$MessageHandler} — the erased return type of
 * SimpleChannel.registerMessage at the translated call site. No members are
 * referenced by either trial jar (the value is discarded); the class exists so the
 * retyped descriptor resolves at link time.
 */
public class MessageHandler {
}
