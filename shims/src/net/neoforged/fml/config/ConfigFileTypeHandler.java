package net.neoforged.fml.config;

/**
 * ModBridge shim carrier (phase 5): Forge 1.20.1's ConfigFileTypeHandler (the
 * nightconfig loader FML wired into ModConfig) was removed in the 21.1 config
 * rework — net.neoforged.fml.config.ConfigFileTypeHandler does not exist (javap
 * -verified against the sandbox loader). moonlight's ConfigSpecWrapper static init
 * class-constants it (ldc) and reflects into it via
 * ObfuscationReflectionHelper.findMethod; without this class the ldc itself throws
 * NoClassDefFoundError before the fail-soft reflection shim can answer.
 *
 * Deliberately EMPTY: the class only needs to resolve; every lookup against it
 * ("setupConfigFile") misses and the reflection shim returns null (fail-soft),
 * turning the 1.20.1 config-load path into late, logged no-op data instead of a
 * boot-killing static-init error.
 */
public class ConfigFileTypeHandler {
}
