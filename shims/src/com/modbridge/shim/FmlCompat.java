package com.modbridge.shim;

/**
 * ModBridge shim carrier (config lane, phase 5.12): 1.20.1 FML's ModLoader state
 * machine ({@code get()}, {@code hasCompletedState(String)},
 * {@code isLoadingStateValid()}) is GONE on 21.1 (replaced by ModLoadingIssue).
 * Measured sites: moonlight's config-load listener guard
 * ("refuse if another mod force-loaded configs before the registry phase") and
 * PlatHelperImpl.isModLoadingValid. On the translated runtime the eager-config
 * bridge (trial 002, phase 5.555) INTENTIONALLY loads COMMON configs at
 * registration - before the registry phase - so the 1.20.1 guard must PASS
 * (documented eager-load semantic), and loading validity mirrors
 * {@code !ModLoader.hasErrors()}. Relocated per-mod like every shim.
 */
public final class FmlCompat {

    private FmlCompat() {
    }

    /** 1.20.1 {@code ModLoader.get()} - the only measured consumers feed the
     *  receiver-prepended hasCompletedState retarget, which ignores the receiver. */
    public static net.neoforged.fml.ModLoader get() {
        return null;
    }

    /** the guard passes on the translated runtime (see class javadoc) */
    public static boolean hasCompletedState(net.neoforged.fml.ModLoader receiver, String state) {
        return true;
    }

    /** 1.20.1 {@code ModLoader.isLoadingStateValid()} - no errors on 21.1 */
    public static boolean isLoadingStateValid() {
        return !net.neoforged.fml.ModLoader.hasErrors();
    }
}
