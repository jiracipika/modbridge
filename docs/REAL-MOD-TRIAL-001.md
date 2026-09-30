# Real-mod trial 001 — first genuine 1.20.1 mods on NeoForge 1.21.1 (2026-09-29)

## Setup
- Source mods: three real Forge 1.20.1 mods from the Mayview v1 pack (untouched), chosen
  small and dependency-free: `nanny-1.0.6` (4 KB, damage/death event filters + config),
  `deuf-1.20.1-1.3` (13 KB, duplicate-UUID entity guard), `BeeFix-1.20-1.0.7` (29 KB coremod).
- Source validation: all three boot and load on the Forge 1.20.1-47.4.8 testbed
  (`testenv/server-1201`, Java 21) — the environment is real, not stubs.
- Translation: drop-in connector flow on the NeoForge 21.1.250 testbed via
  `modbridge-agent.jar`.

## Result
- **nanny + deuf: load, initialize, and run.** Boot `Done (0.95s)`, zero construction
  failures, zero log errors. Live exercise via console: entity summon (deuf's
  EntityJoinLevelEvent handler fired, duplicate-UUID guard verified) + kill (nanny's
  attack/damage/death handler chain executed). Residual SRG refs in output jars: **0**.
- **BeeFix: correctly rejected by the agent** — it ships `coremods.json` (JS bytecode
  patches against `net.minecraftforge.coremod.api.ASMAPI`, which no longer exists).
  Coremod translation is a separate roadmap item; the agent detects and skips with a
  clear message instead of breaking the boot.
- Fixture regression matrix stayed **16/16** after all rule additions.

## Deltas discovered by the trial (all now encoded in rules/)
1. Real 1.20.1 production mods reference **vanilla members by SRG name** (`m_21223_`),
   while NeoForge 21.1 runs mojmap. Built the SRG layer: compose Forge's `joined.tsrg2`
   (obf->srg) with the official proguard mappings (obf->mojmap), joined exactly on
   (class, obf member, descriptor) -> **47,007 pairs** in `rules/srgmembers.txt`,
   applied by name (SRG ids are globally unique) to declarations, call sites, and
   lambda method handles.
2. `LivingAttackEvent` and `LivingHurtEvent` no longer exist (1.21 damage pipeline:
   `LivingIncomingDamageEvent` / `LivingDamageEvent$Pre`), with member remaps
   (getAmount stays / ->getNewDamage) and a **member retarget**: `setCanceled` only
   exists as an `ICancellableEvent` interface default, so `invokevirtual` call sites
   must become `invokeinterface` (JVM class resolution never searches interfaces).
3. `ForgeConfigSpec` -> `ModConfigSpec` (+ inner classes via `$`-prefix rules),
   `ForgeRegistries` -> `NeoForgeRegistries`, `IForgeRegistry` -> vanilla `Registry`.
4. The `@Mod` constructor: NeoForge also accepts plain no-arg constructors — the
   FMLJavaModLoadingContext surgery is applied only when the idiom is present
   (most real mods just use `MinecraftForge.EVENT_BUS.register(this)`).

## Known gaps (next)
- 12,345 SRG ids unresolved (same-desc sibling overloads in the tsrg/mappings join and
  server-side-only members) — resolvable via Linkie (roadmap phase 2). Mods hitting one
  get a precise NoSuchMethodError naming the id.
- Coremods (JS patches) unsupported — agent skips them explicitly.
- Mixins untouched (phase 4): a mixin-bearing mod will fail at mixin refmap remap.
