package com.modbridge.rewriter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure-logic tests for the phase-4 mixin resource remap (RefmapRemapper + Json):
 * a fixture Forge "searge" refmap is rewritten SRG→mojmap, mixin configs are pruned of
 * neutralized mixins, and JSON round-trips. No network, no jars, no filesystem —
 * run with scripts/test-refmap-remapper.sh.
 */
public final class RefmapRemapperTest {

    int passed = 0;

    void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("  [PASS] " + name);
    }

    public static void main(String[] args) {
        new RefmapRemapperTest().run();
    }

    void run() {
        // ------------------------------------------------ synthetic mapping tables
        // srg lookup: m_46796_ -> levelEvent, m_5965_ -> getPlacementState, f_52661_ -> dispenseItemBehavior? no:
        // f_52661_ -> "dispenser"? keep it deterministic: field id -> "fieldMojmap".
        Function<String, String> srg = id -> switch (id) {
            case "m_46796_" -> "levelEvent";
            case "m_5965_" -> "getPlacementState";
            case "f_52661_" -> "mojField";
            default -> null;
        };
        // type remap: forge API moved packages; one vanilla class renamed (incl. inner classes,
        // mirroring the real table's package-prefix and '$'-suffix rules)
        Function<String, String> types = t -> {
            if (t.startsWith("net/minecraftforge/common/capabilities/")) {
                return "net/neoforged/neoforge/capabilities/" + t.substring("net/minecraftforge/common/capabilities/".length());
            }
            if (t.startsWith("net/minecraftforge/common/")) {
                return "net/neoforged/neoforge/common/" + t.substring("net/minecraftforge/common/".length());
            }
            if (t.startsWith("net/minecraft/world/entity/oldAnimal")) {
                return "net/minecraft/world/entity/Animal" + t.substring("net/minecraft/world/entity/oldAnimal".length());
            }
            return t;
        };

        // ------------------------------------------------ fixture refmap (moonlight shape)
        String refmapJson = """
                {
                  "mappings": {
                    "net/meh/core/mixins/PoiMixin": {
                      "Lnet/minecraft/world/level/Level;levelEvent(ILnet/minecraft/core/BlockPos;I)V": "Lnet/minecraft/world/level/Level;m_46796_(ILnet/minecraft/core/BlockPos;I)V",
                      "net.minecraft.world.entity.oldAnimal$Inner": "net/minecraft/world/entity/oldAnimal$Inner",
                      "getPlacementState": "Lnet/minecraft/world/item/BlockItem;m_5965_(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"
                    },
                    "net/meh/core/mixins/ClientOnlyMixin": {
                      "loadFrom": "Lnet/minecraft/client/lang.ClientLanguage;m_264420_(Z)Lnet/minecraft/client/lang.ClientLanguage;"
                    }
                  },
                  "data": {
                    "searge": {
                      "net/meh/core/mixins/PoiMixin": {
                        "Lnet/minecraft/world/level/Level;levelEvent(ILnet/minecraft/core/BlockPos;I)V": "Lnet/minecraft/world/level/Level;m_46796_(ILnet/minecraft/core/BlockPos;I)V"
                      }
                    }
                  }
                }
                """;
        Object refmap = Json.parse(refmapJson);
        check(RefmapRemapper.isRefmap(refmap), "isRefmap: mappings+data shape detected");
        check(!RefmapRemapper.isRefmap(Json.parse("{\"a\":1,\"data\":{}}")), "isRefmap: non-refmap JSON not matched");

        refmap = RefmapRemapper.remapTreeStrings(refmap, s -> RefmapRemapper.remapRefString(s, srg, types));
        String out = Json.write(refmap);
        check(out.contains("Lnet/minecraft/world/level/Level;levelEvent(ILnet/minecraft/core/BlockPos;I)V\": \"Lnet/minecraft/world/level/Level;levelEvent(ILnet/minecraft/core/BlockPos;I)V\""),
                "refmap: searge value m_46796_ -> mojmap name, desc classes kept");
        check(!out.contains("m_46796_") && !out.contains("m_5965_"),
                "refmap: no SRG ids survive in mappings or data namespaces");
        check(out.contains("\"net/meh/core/mixins/PoiMixin\""),
                "refmap: mixin class keys (mod-space names) untouched");        check(out.contains("Lnet/minecraft/world/item/BlockItem;getPlacementState(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"),
                "refmap: dev-space value remapped by bare SRG id");
        check(out.contains("net.minecraft.world.entity.Animal$Inner")
                        && out.contains("\"net/minecraft/world/entity/Animal$Inner\""),
                "refmap: class target remapped both dotted (key) and slash (value) forms");

        // data namespace got the same treatment as mappings
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) refmap).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> searge = (Map<String, Object>) data.get("searge");
        check(String.valueOf(searge.get("net/meh/core/mixins/PoiMixin")).contains("levelEvent"),
                "refmap: data.searge namespace remapped identically to mappings");

        // JSON round-trip fidelity
        Object reparsed = Json.parse(out);
        check(out.equals(Json.write(reparsed)), "json: parse->write round-trip is stable");

        // ------------------------------------------------ method/field reference shapes
        check(RefmapRemapper.remapRefString("m_46796_", srg, types).equals("levelEvent"),
                "refString: bare SRG method id -> mojmap name");
        check(RefmapRemapper.remapRefString("m_28626_(Z)V", srg, types).equals("m_28626_(Z)V"),
                "refString: untabled SRG id left intact (client-owner data, never guessed)");
        check(RefmapRemapper.remapRefString("Lnet/minecraftforge/common/capabilities/Capability;m_5965_()Lnet/minecraftforge/common/capabilities/Capability;", srg, types)
                        .equals("Lnet/neoforged/neoforge/capabilities/Capability;getPlacementState()Lnet/neoforged/neoforge/capabilities/Capability;"),
                "refString: owner+desc classes and SRG name all remapped together");
        check(RefmapRemapper.remapRefString("Lnet/minecraftforge/common/Capability;f_52661_:Lnet/minecraftforge/common/capabilities/Capability;", srg, types)
                        .equals("Lnet/neoforged/neoforge/common/Capability;mojField:Lnet/neoforged/neoforge/capabilities/Capability;"),
                "refString: field reference form name:Ldesc; with owner");
        check(RefmapRemapper.remapRefString("f_52661_:Lnet/minecraft/world/item/Item;", srg, types)
                        .equals("mojField:Lnet/minecraft/world/item/Item;"),
                "refString: field reference form without owner");
        check(RefmapRemapper.remapRefString("net.minecraftforge.common.ForgeInternalHandler", srg, types)
                        .equals("net.neoforged.neoforge.common.ForgeInternalHandler"),
                "refString: dotted @Mixin(targets) class follows the package table");
        check(RefmapRemapper.remapRefString("net.minecraft.world.entity.oldAnimal$Inner", srg, types)
                        .equals("net.minecraft.world.entity.Animal$Inner"),
                "refString: dotted inner-class target remapped through the type table");
        check(RefmapRemapper.remapRefString("net/minecraft/world/entity/oldAnimal$Inner", srg, types)
                        .equals("net/minecraft/world/entity/Animal$Inner"),
                "refString: slash-form internal class target (refmap value style) remapped");
        check(RefmapRemapper.remapRefString("data/meh/flags/water_alt", srg, types)
                        .equals("data/meh/flags/water_alt"),
                "refString: non-class slash path passes through the type table unchanged");

        // mojmap->mojmap member renames (dev-space names the SRG table cannot cover)
        Function<String, String> moj = n -> n.equals("tryCatchFire") ? "checkBurnOut" : null;
        check(RefmapRemapper.remapRefString("Lnet/minecraft/world/level/block/FireBlock;tryCatchFire(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ILnet/minecraft/util/RandomSource;ILnet/minecraft/core/Direction;)V", srg, types, moj)
                        .contains(";checkBurnOut("),
                "refString: mojmap rename applied to dev-space method reference");
        check(RefmapRemapper.remapRefString("tryCatchFire", srg, types, moj).equals("checkBurnOut"),
                "refString: mojmap rename applied to bare member name");
        check(RefmapRemapper.remapRefString("moonlight$tryCatchFire", srg, types, moj).equals("moonlight$tryCatchFire"),
                "refString: $-joined callback names never mojmap-renamed");
        check(RefmapRemapper.remapRefString("m_53444_(Lnet/minecraft/world/level/Level;)V", srg, types, moj)
                        .endsWith("(Lnet/minecraft/world/level/Level;)V"),
                "refString: untabled SRG id left intact even with mojmap table loaded");
        check(RefmapRemapper.remapRefString("moonlight$makePOIMutable", srg, types).equals("moonlight$makePOIMutable"),
                "refString: callback names (mod-space, $-joined) untouched");
        check(RefmapRemapper.remapRefString("tick", srg, types).equals("tick"),
                "refString: plain dev-space name untouched");
        check(RefmapRemapper.remapRefString("some.mod.String", srg, types).equals("some.mod.String"),
                "refString: unmapped dotted name passes through unchanged");

        // ------------------------------------------------ mixin config pruning
        String configJson = """
                {
                  "required": true,
                  "minVersion": "0.8",
                  "package": "net.meh.core.mixins",
                  "plugin": "net.meh.core.mixins.MixinPlugin",
                  "client": ["ClientOnlyMixin", "GameRendererMixin"],
                  "mixins": [
                    "PoiMixin",
                    "EntityMixin",
                    "accessor.BrainAccessor"
                  ],
                  "injectors": {"defaultRequire": 1},
                  "refmap": "meh-common-refmap.json"
                }
                """;
        Object config = Json.parse(configJson);
        check(RefmapRemapper.isMixinConfig(config), "isMixinConfig: package + arrays detected");
        check(!RefmapRemapper.isMixinConfig(Json.parse("{\"package\":\"x\",\"mappings\":{}}")),
                "isMixinConfig: refmap shape not misdetected as config");

        List<String> removed = RefmapRemapper.stripMixins(config,
                Set.of("net/meh/core/mixins/PoiMixin", "net/meh/core/mixins/NotListed", "net/meh/core/mixins/ClientOnlyMixin"));
        check(removed.size() == 1 && removed.contains("PoiMixin"),
                "stripMixins: only the listed common/server entry removed");
        @SuppressWarnings("unchecked")
        List<String> mixins = (List<String>) ((Map<?, ?>) config).get("mixins");
        @SuppressWarnings("unchecked")
        List<String> client = (List<String>) ((Map<?, ?>) config).get("client");
        check(mixins.size() == 2 && mixins.contains("EntityMixin") && mixins.contains("accessor.BrainAccessor"),
                "stripMixins: surviving entries (incl. subpackage form) kept, order stable");
        check(client.size() == 2 && client.contains("ClientOnlyMixin"),
                "stripMixins: client array never touched (inert on dedicated server)");
        check("meh-common-refmap.json".equals(((Map<?, ?>) config).get("refmap")),
                "stripMixins: refmap pointer preserved");

        // ------------------------------------------------ refmap class pruning
        RefmapRemapper.stripRefmapClasses(refmap, Set.of("net/meh/core/mixins/ClientOnlyMixin"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) ((Map<?, ?>) refmap).get("mappings");
        check(!mappings.containsKey("net/meh/core/mixins/ClientOnlyMixin")
                        && mappings.containsKey("net/meh/core/mixins/PoiMixin"),
                "stripRefmapClasses: neutralized mixin entries dropped, others kept");

        System.out.println("RefmapRemapperTest: " + passed + " checks passed");
    }
}
