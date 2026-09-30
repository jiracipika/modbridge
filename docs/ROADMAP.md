# ModBridge roadmap

## Phase 0 — DONE (2026-09-29): proof pipeline
Hand-ruled rules translating a fixture mod (authentic 1.20.1 Forge API idioms) to
NeoForge 1.21.1, gated by a live dedicated-server test matrix (smoke / negative /
positive) driven entirely through RCON. 10/10.

## Phase 1 — real 1.20.1 validation environment
Stand up a NeoForge 20.1 server (Java 17) in the same testbed layout and run the
UNtranslated fixture there, closing the stubs-vs-real-1.20.1 authenticity gap.
Same matrix scripts, second server dir.

## Phase 2 — automated rule generation (the big lever)
Stop hand-writing tables:
- diff two mojmap-mapped vanilla jars (javap both sides; emit renamed/removed/changed
  members) across 1.20.1 → 1.21.1
- diff the Forge 1.20.1 vs NeoForge 21.1 API jars for the forge-side surface
- Linkie (linkie.weirdgloop.org) API as a cross-version name/mapping oracle
- output: candidate rule tables + an "unresolvable" report (removed APIs needing shims)

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
