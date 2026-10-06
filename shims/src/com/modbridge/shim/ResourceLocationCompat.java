package com.modbridge.shim;

import net.minecraft.resources.ResourceLocation;

/**
 * ModBridge shim carrier (config lane, phase 5.9, live-boot wall from the phase-5.8
 * session): 1.20.1's static {@code ResourceLocation.isValidResourceLocation(String)}
 * is GONE on 1.21.1 (m_135830_; the 21.1 surface offers tryParse(String) returning a
 * nullable ResourceLocation, and read(String) returning a DataResult). The boolean
 * semantic ports 1:1 to {@code tryParse(s) != null}. Single measured call site:
 * moonlight ConfigBuilder$ResourceLocationConfigValue.lambda$new$0 x1 — the
 * ResourceLocation config-value CORRECTOR invoked during the eager COMMON config
 * load (supplementaries' boot died there transitively, config-correct pass).
 *
 * Routed by the memberretargets "static" kind (INVOKESTATIC -> INVOKESTATIC, desc
 * unchanged). Relocated per-mod to com/modbridge/shim/&lt;modid&gt;/ like every shim.
 */
public final class ResourceLocationCompat {

    private ResourceLocationCompat() {
    }

    /** 1.20.1 {@code isValidResourceLocation} surface via the 21.1 tryParse. */
    public static boolean isValidResourceLocation(String str) {
        return ResourceLocation.tryParse(str) != null;
    }
}
