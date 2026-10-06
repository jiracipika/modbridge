package net.neoforged.fml.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * ModBridge shim carrier (eager-config slice): the target of the ModConfig ctor
 * retarget (rules/ctorretargets.txt). Phase 5.5 routed the Forge 1.20.1 4-arg
 * {@code new ModConfig(...)} straight at the real 21.1
 * {@code ConfigTracker.INSTANCE.registerConfig(...)} — exact registration parity,
 * but on 21.1 FML loads COMMON configs LATER in its own lifecycle ("Config loading"
 * init task, after every mod is constructed), so construction-time reads crashed
 * with {@code IllegalStateException: Cannot get config value before config is
 * loaded.} from the real {@code ModConfigSpec$ConfigValue.getRaw}. On 1.20.1 those
 * reads worked because moonlight's ConfigSpecWrapper eagerly loaded the files during
 * registration (its loadFromFile path — method-dropped, see rules/methoddrops.txt).
 *
 * <p>This bridge restores that 1.20.1-visible timing through the 21.1 platform's own
 * pipeline: registerConfig (ctor + validateSpec + trackConfig + eager open for
 * STARTUP, exactly phase 5.5), then — for COMMON configs only —
 * {@link ConfigTracker#loadConfigs loadConfigs(COMMON, FMLPaths.CONFIGDIR.get())}.
 * Reads made during mod construction now return the TOML's values when a config
 * file is present (the normal case on a migrated server; the spec corrects missing
 * entries), or the spec defaults on first run. Documented semantic: "values are
 * available from construction time on; FML's own loadConfigs pass re-runs later".
 *
 * <p><b>Trade-offs accepted (javap-proven against loader 4.0.44, see slice notes):</b>
 * <ul>
 * <li>loadConfigs is per-TYPE, not per-config: it opens EVERY tracked COMMON config,
 *     not just this mod's. Native mods' configs registered earlier get loaded early;
 *     when FML later runs its own loadConfigs, openConfig re-opens already-loaded
 *     configs (WARN log line + a second ModConfigEvent$Loading per config — reload
 *     semantics listeners must already tolerate; the FileWatcher re-watch is a
 *     Map.put replace, nightconfig 3.8.3).</li>
 * <li>The early ModConfigEvent fires before THIS mod's own listeners are registered
 *     (the retargeted ctor call sits a few instructions before the addListener calls
 *     in moonlight's ConfigSpecWrapper) — the event is absorbed, and FML's real pass
 *     delivers it later to the registered listeners.</li>
 * <li>CLIENT-type configs are NOT eager-loaded: a dedicated server never loads them
 *     (matching both 1.20.1 and 21.1 FML semantics); STARTUP configs are already
 *     eagerly opened inside registerConfig; SERVER-type stays on FML's own
 *     lifecycle.</li>
 * <li>Fail behavior is platform-faithful: a config file that makes loadConfigs throw
 *     would equally kill FML's own later pass — the boot fails either way.</li>
 * </ul>
 */
public final class ModConfigBridge {

    /** receiver field for the ctor-retarget rewrite (GETSTATIC ... INSTANCE) */
    public static final ModConfigBridge INSTANCE = new ModConfigBridge();

    private ModConfigBridge() {
    }

    /**
     * Registers the config through the real 21.1 tracker and eagerly loads COMMON
     * configs so construction-time reads see file/default values (the 1.20.1-visible
     * timing moonlight's eager loadFromFile used to provide).
     */
    public ModConfig registerAndLoad(ModConfig.Type type, IConfigSpec spec,
                                     ModContainer container, String fileName) {
        ModConfig config = ConfigTracker.INSTANCE.registerConfig(type, spec, container, fileName);
        if (config.getType() == ModConfig.Type.COMMON) {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
        }
        return config;
    }
}
