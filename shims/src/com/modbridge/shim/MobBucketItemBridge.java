package com.modbridge.shim;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.MobBucketItem;

/**
 * phase 5.16: mixin-accessor direct-load bridge (the 5.15 head-of-list wall).
 *
 * 1.20.1 supp code reaches MobBucketItem's fish type through the mixin ACCESSOR
 * interface net.mehvahdjukaar.supplementaries.mixins.forge.MobBucketItemAccessor
 * (a three-instruction site: checkcast + invokeinterface invokeGetFishType + areturn
 * at supp forge/SuppPlatformStuffImpl.getFishType). Direct loads of classes inside a
 * REGISTERED mixin package are fatal on the 21.1 mixin subsystem regardless of whether
 * the accessor ever applied: MixinProcessor.applyMixins keeps a package guard with no
 * already-applied passthrough (the only exempt superclass is InjectionPoint /
 * ITargetSelectorDynamic), and this project's refmap lane had already NEUTRALIZED the
 * accessor out of the config (its 1.20.1 target getFishType does not exist on 21.1),
 * so the load hit the "in a defined mixin package ... cannot be referenced directly"
 * IllegalClassLoadError (live boot 5.15, tag-update dispatch via
 * BucketHelper.isFishBucket <- DispenserBehaviorsManager.registerBehaviors).
 *
 * The rewrite (rules/memberretargets.txt) turns the accessor call into this static
 * bridge whose first parameter IS the receiver — same pops/pushes, frames untouched —
 * and drops the preceding checkcast (new rewriter behavior, same pass, adjacent-only).
 *
 * Access: the 21.1 field is `private final EntityType<?> type` (the 1.20.1
 * getFishType method is gone; vanilla has no public accessor). Vanilla classes are
 * never rewritten by ModBridge, so the read is reflective with setAccessible — the
 * proven shim idiom (RegistryCompat/ObfuscationReflectionHelper). The field desc
 * erases to exactly the accessor method's return shape, so the stack is identical.
 */
public final class MobBucketItemBridge {

    private static final java.lang.reflect.Field TYPE;

    static {
        java.lang.reflect.Field f = null;
        try {
            f = MobBucketItem.class.getDeclaredField("type");
            f.setAccessible(true);
        } catch (Throwable t) {
            System.err.println("[ModBridge] MobBucketItemBridge: cannot reach MobBucketItem.type: " + t);
        }
        TYPE = f;
    }

    /** the @Invoker("getFishType") semantic: the bucket's fish entity type. NOTE the
     *  name: the memberretarget |static kind keeps the call site's method name (only
     *  owner+desc swap), so the bridge method MUST be invokeGetFishType — the accessor
     *  interface's own method name. */
    public static EntityType<?> invokeGetFishType(MobBucketItem item) {
        if (TYPE == null) {
            throw new IllegalStateException("MobBucketItemBridge: MobBucketItem.type unresolved");
        }
        try {
            return (EntityType<?>) TYPE.get(item);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("MobBucketItemBridge.getFishType failed", e);
        }
    }

    private MobBucketItemBridge() {
    }
}
