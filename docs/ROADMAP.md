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

## Phase 2 — automated rule generation (the big lever) — SRG layer DONE
DONE: the SRG->mojmap table (47,007 exact pairs, tsrg2 x proguard join; see
docs/REAL-MOD-TRIAL-001.md), and (2026-10-05) the sibling-overload gap closed in full:
`SrgExactJoinResolver` (tools/src/com/modbridge/pairer/) resolves every id via the exact
identity chain srgId -> obf member (MCPConfig joined.tsrg2) -> mojmap member (Mojang
official client.txt+server.txt), disambiguated by descriptor from Forge's srg jar — the
same data chain Linkie's "Mojang (via SRG)" namespace builds internally. Result: +1,056
pairs (the "12,345" was occurrence count — overridden methods re-declare ids; the real
gap was 1,056 distinct ids), 0 conflicts, all 47,007 Phase-1 pairs re-derived unchanged,
table now 48,063 (`rules/srgmembers.txt`, additive-only diff). Usage-ranked first: 0 of
the 3 trial mods referenced the gap; across the full 240-jar Mayview v1 pack 131 of the
1,056 were referenced (top users: supplementaries 34, ImmersiveEngineering 31, hexerei
25, Quark 24 distinct ids) — all 131 resolved. 251 of the additions were independently
confirmed against the Linkie API (linkieapi.shedaniel.me, namespace mojang_srg@1.20.1):
0 disagreements. Tests: scripts/test-srg-resolver.sh (pure fixture logic, 20 checks).
Linkie remains listed as a cross-version oracle for the items below (its per-id search
API works; there is no bulk endpoint — bulk resolution goes through the raw datasets).
Remaining:
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
