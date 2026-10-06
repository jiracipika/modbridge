package com.modbridge.shim;

/**
 * ModBridge shim carrier (networking lane, phase 5.10): 1.20.1 forge's
 * {@code NetworkDirection} enum (PLAY_TO_SERVER, PLAY_TO_CLIENT, LOGIN_TO_SERVER,
 * LOGIN_TO_CLIENT). Measured surface: values() + ordinal() (moonlight
 * ChannelHandlerImpl$1 builds a direction lookup in clinit) + the getDirection()
 * return type. Direction SEMANTICS are not claimed (inert facade).
 */
public enum NetworkDirection {
    PLAY_TO_SERVER,
    PLAY_TO_CLIENT,
    LOGIN_TO_SERVER,
    LOGIN_TO_CLIENT
}
