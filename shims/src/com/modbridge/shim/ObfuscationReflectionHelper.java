package com.modbridge.shim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * ModBridge shim carrier (phase 5): fail-soft stand-in for
 * net.neoforged.fml.util.ObfuscationReflectionHelper (the retarget of Forge 1.20.1's
 * net.minecraftforge.fml.util.ObfuscationReflectionHelper).
 *
 * Why a shim and not the real class: moonlight's config wrapper resolves private FML
 * members in static init (ModConfig.setConfigData(CommentedConfig),
 * ConfigFileTypeHandler.setupConfigFile). Both were refactored away in 21.1 (ModConfig
 * now takes a LoadedConfig; ConfigFileTypeHandler is gone — javap-verified), and the
 * real helper throws UnableToFindMethodException, killing the translated static init
 * (and with it the mod constructor). The shim is call-compatible and fail-soft:
 * resolution success behaves identically (setAccessible + return), a miss is logged
 * once and returns null. Downstream null-Method failures are LATE, logged data points
 * instead of boot-killing static-init errors.
 */
public final class ObfuscationReflectionHelper {

    private static final String PREFIX = "[ModBridge shim] ";

    private ObfuscationReflectionHelper() {
    }

    public static Method findMethod(Class<?> clazz, String methodName, Class<?>... methodTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName, methodTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        System.out.println(PREFIX + "method not found (fail-soft, callers may see null): "
                + clazz.getName() + "." + methodName + Arrays.toString(methodTypes));
        return null;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        System.out.println(PREFIX + "field not found (fail-soft, callers may see null): "
                + clazz.getName() + "." + fieldName);
        return null;
    }
}
