package com.modbridge.shim;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * ModBridge SHARED shim (NBT item-tag removal wall, phase 5.13): 1.20.1's
 * ItemStack tag family (getTag / getOrCreateTag / getTagElement / ... /
 * ItemStack.of) is GONE on 1.21.1 - 1.20.5 replaced per-item NBT with data
 * components and no renamed counterpart survives (javap'd on
 * neoforge-21.1.250). This is the TAG-BACKED COMPATIBILITY VIEW the phase-5.12
 * scoping banked: every view method is backed by
 * {@link DataComponents#CUSTOM_DATA} - vanilla 21.1's own "arbitrary NBT
 * payload" component - so translated call sites keep the exact 1.20.1 shapes
 * (static-kind memberretargets: the ItemStack receiver rides in as argument 1,
 * operand stack identical, frames untouched) and mod-INTERNAL tag round-trips
 * (write then read the same stack) behave exactly as on 1.20.1. The payload
 * also syncs to the client with the stack's components.
 *
 * <p>Known drift (documented in the trial doc, the phase-5.14 remainder): keys
 * whose 21.1 consumer is a DEDICATED component do not surface through vanilla
 * systems from this view - "BlockEntityTag" (vanilla copy-in became
 * BLOCK_ENTITY_DATA), "display" Name/Lore (tooltip components), "Fireworks",
 * banner "Patterns", map "map"/"generation". The view is live and mod-internal
 * round-trips work; only vanilla-owned semantics are unclaimed.
 *
 * <p>SHARED shim (phase-5.12 mechanism, rules/shims-shared.txt): injected ONLY
 * into moonlight (the dependency mod; supplementaries reads it cross-mod) at
 * the fixed com/modbridge/shim/shared/ package, so both mods' rules resolve the
 * same type and no per-mod relocation can diverge the retargeted descriptors.
 */
public final class ItemStackTagCompat {

    /** built-in-registry view for the save/of round-trips (call sites run at
     *  registration/common-setup time, when no server-side registry access
     *  exists yet; BuiltInRegistries.REGISTRY is populated by vanilla bootstrap) */
    private static final RegistryAccess.Frozen REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private ItemStackTagCompat() {
    }

    /** 1.20.1 getTag(): null when the stack carries no custom tag. The returned
     *  tag is the LIVE tag of the stored CUSTOM_DATA component (CustomData.getUnsafe)
     *  - 1.20.1 getTag returned the stack's live field the same way. */
    public static CompoundTag getTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.getUnsafe();
    }

    /** 1.20.1 getOrCreateTag(): creates the CUSTOM_DATA component when absent,
     *  then hands out the live internal tag - subsequent mutations land in the
     *  stored component (CustomData.of copies, so the component's OWN tag is
     *  fetched back rather than retained). */
    public static CompoundTag getOrCreateTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
            data = stack.get(DataComponents.CUSTOM_DATA);
        }
        return data.getUnsafe();
    }

    /** 1.20.1 hasTag(): !isEmpty() && tag != null && !tag.isEmpty() (bytecode
     *  ground truth of m_41782_; the isEmpty guard is part of the shape) */
    public static boolean hasTag(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty();
    }

    /** 1.20.1 setTag(): null clears. CustomData.of copies the argument (1.20.1
     *  kept the reference; the two stacks aliasing one tag is accepted drift -
     *  measured call sites copy-then-set anyway). */
    public static void setTag(ItemStack stack, CompoundTag tag) {
        if (tag == null) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    /** 1.20.1 getTagElement(): null unless a sub-COMPOUND (type 10) is present
     *  under the key - bytecode ground truth of m_41737_. */
    public static CompoundTag getTagElement(ItemStack stack, String key) {
        CompoundTag tag = getTag(stack);
        return tag != null && tag.contains(key, 10) ? tag.getCompound(key) : null;
    }

    /** 1.20.1 getOrCreateTagElement(): creates AND ATTACHES the sub-compound
     *  when missing (bytecode ground truth of m_41698_: it routes through
     *  addTagElement, unlike CompoundTag.getCompound which detaches). */
    public static CompoundTag getOrCreateTagElement(ItemStack stack, String key) {
        CompoundTag root = getOrCreateTag(stack);
        if (!root.contains(key, 10)) {
            CompoundTag created = new CompoundTag();
            root.put(key, created);
            return created;
        }
        return root.getCompound(key);
    }

    /** 1.20.1 addTagElement(): puts the tag under the key on the (created)
     *  custom tag. */
    public static void addTagElement(ItemStack stack, String key, Tag value) {
        getOrCreateTag(stack).put(key, value);
    }

    /** 1.20.1 save(CompoundTag): encodes the stack and returns the filled tag.
     *  Format note: the payload uses the 21.1 shape (id/count/components) -
     *  round-trips with {@link #of} below (the measured call sites save+of
     *  through THIS pair), NOT with 1.20.1-written Count/tag payloads
     *  (cross-format old data is the documented drift). Bytecode-drift fixes
     *  measured live: the 21.1 two-arg save RETURNS a new tag (DFU NbtOps
     *  immutability - it does NOT fill the passed prefix like the 1.20.1 field
     *  writes did), and it THROWS IllegalStateException on empty stacks where
     *  1.20.1 encoded {id:"minecraft:air", Count:0} - the empty case is
     *  re-encoded in the 1.20.1 minimal shape. */
    public static CompoundTag save(ItemStack stack, CompoundTag out) {
        if (stack.isEmpty()) {
            out.putString("id", "minecraft:air");
            out.putByte("count", (byte) 0);
            return out;
        }
        return (CompoundTag) stack.save(REGISTRIES, out);
    }

    /** 1.20.1 ItemStack.of(CompoundTag): EMPTY on any parse failure (the 1.20.1
     *  catch-all convention). */
    public static ItemStack of(CompoundTag tag) {
        return ItemStack.parse(REGISTRIES, tag).orElse(ItemStack.EMPTY);
    }
}
