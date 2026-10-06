package com.modbridge.shim;

/**
 * ModBridge shim carrier (removed-API ladder, phase 5.10): 1.20.1 vanilla's
 * {@code net.minecraft.world.item.Vanishable} marker interface is GONE on 1.21.1
 * (no renamed counterpart — verified absent from rules/classlist-1211.txt). Single
 * measured implementer: supplementaries SlingshotItem (implements it). Inert marker:
 * translated implementers keep their IS-A chain for linking; any platform dispatch
 * keyed on the interface cannot see the shim (behavior NOT claimed — the item would
 * have to be re-registered against the 21.1 mechanism, which no longer exists).
 */
public interface Vanishable {
}
