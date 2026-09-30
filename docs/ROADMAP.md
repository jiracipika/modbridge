# ModBridge roadmap

## Phase 0 — DONE (2026-09-29): proof pipeline
Hand-ruled rules translating a fixture mod (authentic 1.20.1 Forge API idioms) to
NeoForge 1.21.1, gated by a live dedicated-server test matrix (smoke / negative /
positive) driven entirely through RCON. 10/10.

## Phase 0.5 — DONE (2026-09-29): Connector-style drop-in runtime
`modbridge-agent.jar` (-javaagent, premain before FML discovery): drop 1.20.1 jars
into `mods/source-1201/`, boot, they are translated (SHA-256-keyed cache in
`modbridge-cache/`), installed into `mods/`, and loaded. Works as offline CLI too.
16/16 matrix. Next: a NeoForge carrier mod for runtime shims (phase 3) so translated
mods that need old-API facades get them from the classpath, not the jar.

## Phase 1 — DONE (2026-09-29): real 1.20.1 validation environment + first real-mod trial
`testenv/server-1201` = isolated Forge 1.20.1-47.4.8 server (Java 21 works) cloned from
the Mayview v1 install; validated with real v1 mods, then the same real mods were
translated onto the 21.1 testbed. See docs/REAL-MOD-TRIAL-001.md — nanny + deuf run on
NeoForge 1.21.1 (zero residual SRG, live event handlers exercised via console); BeeFix
correctly skipped as a coremod. Discovered and built the **SRG->mojmap layer**
(47,007 exact pairs via tsrg2 x proguard join) — production 1.20.1 mods reference
vanilla members by SRG name, which the fixture never exercised.

## Phase 2 — automated rule generation (the big lever) — SRG half DONE
DONE: the SRG->mojmap table generator (tsrg2 x proguard exact join, 47,007 pairs;
see docs/REAL-MOD-TRIAL-001.md). Remaining:
- Linkie (linkie.weirdgloop.org) API to resolve the 12,345 SRG ids the exact join
  misses (same-descriptor sibling overloads), and as a general cross-version oracle
- diff Forge 1.20.1 vs NeoForge 21.1 universal jars -> generated forge-side rule tables
  + an "unresolvable" report (removed APIs needing phase-3 shims)
- diff mojmap vanilla 1.20.1 vs 1.21.1 for cross-version vanilla member drift

## Phase 3 — shim library
For semantic (non-mechanical) changes, generate facade classes shipped inside the
translated jar: old-API-shaped classes delegating to new APIs. Needed for:
consumed return-type changes, NBT-on-ItemStack → data components bridges,
SimpleChannel → PayloadRegistrar, RegistryEvent.Register conversions.

## Phase 4 — Mixin support
Mixin refmaps bind to a specific version's names. Plan: remap mixin configs' targets +
refmap entries with the version delta, verify each mixin target exists post-translation;
MixinExtras compat. Probably the single biggest real-mod blocker.

## Phase 5 — wider vanilla deltas
Cross-version vanilla API renames/reshapes (entity/level/worldgen APIs changed a lot
1.20→1.21), event argument changes, enum constant moves, registry alias handling.

## Phase 6 — Fabric
Different remap stack: Fabric mods ship in **intermediary** names, so translation is
intermediary(1.20.1) → mojmap → apply version delta → intermediary(1.21.1)
(tiny-remapper + the existing rewriter in the middle), `fabric.mod.json` regeneration,
Fabric API module deltas. Note: Fabric's loader hard-fails on stale `depends`, no
skip-with-warning like NeoForge — metadata regen must be exact.

## Phase 7 — third-party mod trials
Translate small real 1.20.1 mods (start: single-features mods with no mixins), measure
unresolvable-report size, iterate phases 2–4 until one ships.
