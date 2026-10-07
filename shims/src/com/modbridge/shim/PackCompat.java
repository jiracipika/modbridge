package com.modbridge.shim;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * ModBridge shim (phase 5.13, moonlight's dynamic-resource-pack lane): 1.20.1's
 * {@code Pack.Info} record and its 9-arg {@code Pack.create} overload are GONE on
 * 1.21.1 - the 1.20.5/21 metadata rework split creation into
 * {@link PackLocationInfo} + {@link Pack.Metadata} + {@link PackSelectionConfig}
 * around the (now public) {@link Pack#Pack} ctor (javap'd on neoforge-21.1.250).
 * Measured site: moonlight DynamicResourcePack.lambda$registerPack$2 builds
 * {@code Pack.create(packId, title, true, this, new Pack.Info(desc, format, flags),
 * type, Position.TOP, fixed, PackSource.DEFAULT)} from its own metadata supplier.
 *
 * <p>This is the LIVE pack-construction path (the pack lands in the server's
 * PackRepository directly), so the semantics are preserved for real: the
 * description/format/flags the mod supplies travel into the 21.1 {@code Metadata}
 * record; the pack format int itself is decorative here because the 1.20.1
 * {@code Info.compatibility(PackType)} computation would run only in UI paths the
 * dedicated server never reaches - the metadata record is built with
 * {@link PackCompatibility#COMPATIBLE} (a 1.20.1-mod format pack is generated to
 * match; a warning-path divergence is not claimed). Overlays: 1.20.1 Info carried
 * none - {@code List.of()}.
 */
public final class PackCompat {

    /** ctor-retarget receiver (rules/ctorretargets.txt): GETSTATIC INSTANCE before
     *  the factory INVOKEVIRTUAL - the mechanism's fixed shape. */
    public static final PackCompat INSTANCE = new PackCompat();

    private PackCompat() {
    }

    /** 1.20.1 new Pack.Info(Component description, int packFormat, FeatureFlagSet
     *  flags) -> the 21.1 Pack.Metadata record (description, compatibility,
     *  requestedFeatures, overlays). */
    public Pack.Metadata info(Component description, int packFormat, FeatureFlagSet flags) {
        return new Pack.Metadata(description, PackCompatibility.COMPATIBLE, flags, List.of());
    }

    /** 1.20.1 Pack.create(String, Component, boolean, ResourcesSupplier, Info,
     *  PackType, Position, boolean, PackSource) -> 21.1's public Pack ctor with the
     *  split location/selection records. knownPackInfo = Optional.empty() (the 1.20.1
     *  Info had no such concept). */
    public static Pack create(String id, Component title, boolean required,
            Pack.ResourcesSupplier resources, Pack.Metadata metadata, PackType packType,
            Pack.Position position, boolean fixed, PackSource source) {
        return new Pack(new PackLocationInfo(id, title, source, Optional.empty()),
                resources, metadata, new PackSelectionConfig(required, position, fixed));
    }

    /** 1.20.1 new PackMetadataSection(Component, int) - 21.1 adds the
     *  supportedFormats option (moonlight's lambda$new$0 default supplier). */
    public PackMetadataSection metadataSection(Component description, int packFormat) {
        return new PackMetadataSection(description, packFormat, Optional.empty());
    }
}
