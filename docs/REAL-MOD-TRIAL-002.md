# Real-mod trial 002 — live re-validation of the closed SRG gap + first gap-id consumer attempt (2026-10-05)

## What was validated (live, server-121, NeoForge 21.1.250)
Commit 27a70aa's 48,063-pair SRG table had been validated offline only. This trial
re-proved it END TO END on the sandbox testbed:

- **16/16 matrix re-run** (`scripts/run-matrix.sh`, smoke/negative/connector/warm-reboot):
  **16 passed, 0 failed** (evidence: `testenv/evidence/matrix-run002.txt` — fixture RCON
  `pong modbridgefixture:magic_item` at 15:15).
- **Phase-1 trial set re-run (nanny-1.0.6 + deuf-1.20.1-1.3)**: translated at boot,
  discovered + listed, `Done (1.033s)`, zero errors. Handler exercise via RCON: pig
  summon (deuf's `EntityJoinLevelEvent` chain dispatched through
  `ServerLevel.getEntity(UUID)`/`getUUID` — no duplicate-fix path reachable via RCON,
  see caveats) and pig kill (nanny's remapped `LivingIncomingDamageEvent` /
  `LivingDamageEvent$Pre`/`Post` chain dispatched, 0 log errors). Residual SRG in all
  three output jars: **0**. Evidence: `testenv/evidence/phase1-nanny-deuf-boot-rcon.txt`.

## Harness fix
`scripts/run-matrix.sh` phases 3/4 grepped `testenv/console-out.log`, stale since the
per-instance log split; the agent's premain stdout lands in
`testenv/console-server-121.log`. Fixed (2 lines) — previously these two checks could
pass spuriously off stale content.

## Rewriter fixes found by the extension attempt (all minimal, all re-proven by the matrix)
The 3-trial-mod set never extends vanilla classes and never uses records, so four
mechanical gaps were invisible until a real content mod hit them:

1. **Single-arg `ResourceLocation` ctor** → `ResourceLocation.parse(String)` (same
   NEW+DUP surgery as the existing two-arg case). supplementaries uses it 77+141 times
   (supp+moonlight combined).
2. **Enum annotation values not remapped**: ASM tree models `@OnlyIn(Dist.CLIENT)` /
   `@EventBusSubscriber(bus = Bus.FORGE)` enum values as `String[]{desc, name}`;
   `remapAnnotationValue` left the forge-side descriptor in place (18 classes).
3. **SRG-named override declarations never renamed**: instructions were `mapSrg`'d but
   declarations were not, so every mod class overriding a vanilla member (e.g.
   `m_5654_` getAddEntityPacket, `m_6138_` defineSynchedData) silently stopped
   overriding under mojmap names — 4,529 residual refs in supplementaries before the
   fix, dominated by overrides in 513 non-mixin classes. Declarations are now renamed
   against the table (ids globally unique — same argument as call sites).
4. **Record component descriptors** (`cn.recordComponents`) never remapped: fields were
   mapped but the Record attribute kept forge-space types (moonlight's `Impl` records).

## Extension attempt: supplementaries-1.20-3.1.38 (+ moonlight-1.20-2.16.10 as required dep)
Chosen as the #1 gap-id consumer (34 distinct gap ids, 252 ref sites; moonlight 18/42).

Translation (offline CLI, then drop-in): **both jars translate clean** through the
production pipeline with the fixes above — 1,096 and 491 classes, forge-ref gate passed.
**Day-3 SRG data fully survived contact with reality: all 34 + 18 gap ids resolved,
zero still present in the output jars.** One manual pre-processing step was required
and is itself (b)-data: excising 2 capability mixins (`SelfOpenableContainerMixin`,
`SelfItemDisplayTileMixin`) that reference the removed Forge capability holder API, and
stripping nested jars (`META-INF/jars/`, `META-INF/jarjar/`). Evidence:
`testenv/evidence/extension-supp-moon.txt`.

Boot attempts on server-121 — three distinct walls, all (b):

| # | Wall | Signature |
|---|------|-----------|
| 1 | Module-layer collision | `ResolutionException: Modules MixinExtras and mixinextras.neoforge export package com.llamalad7.mixinextras.sugar.impl to module minecraft` — the nested forge-side MixinExtras cannot ride along next to the platform-bundled one |
| 2 | JarJar metadata ghost | `JarInJarDependencyLocator: Failed to load mod file META-INF/jars/mixinextras-forge-0.3.6.jar ... Caused by: java.io.IOException: Invalid paths argument, contained no existing paths: []` — `META-INF/jarjar/metadata.json` must be regenerated when nested jars are stripped |
| 3 | **The mixin wall (expected)** | `InjectionError: Critical injection failure: Callback method moonlight$makePOIMutable(...) in moonlight-common.mixins.json:PoiMixin from mod (unknown) failed injection check, (0/1) succeeded. Scanned 0 target(s). Using refmap moonlight-common-refmap.json` — refmaps bind 1.20.1 SRG member names against 1.21.1 mojmap targets. Plus cross-version class drift WARNs (`AbstractProjectileDispenseBehavior` and `ExplorationMapFunction$Serializer` no longer exist) |

FML discovered and listed both mod containers before the mixin wall; nothing constructed.

## Failure taxonomy
- **(a) Our data / pipeline bugs — found, fixed, re-proven (4):** the four rewriter
  fixes above. In addition, the table universe itself is sound: **0 server-present
  owners** among all residual ids (see (c)).
- **(b) Needs shims / Mixin support (4 walls):** capability-holder API removal
  (ForgeCapabilities/LazyOptional — 1 mixin class per mod), nested-jar/JarJar
  regeneration, module-layer dedup of platform-bundled libs, and the mixin refmap
  remap (phase 4; the hard stop, `Scanned 0 target(s)`).
- **(c) Coverage horizon, not a bug (deferred with evidence):** 636 distinct residual
  ids in supplementaries (2,685 refs) + 426 refs in moonlight are members of classes
  **absent from the 1.20.1 server jar** — the table's id universe came from the server
  srg jar, so client-only classes (models, particles, screens, renderers; sampled
  owners: 43 vanilla `net/minecraft/client/*` classes + the mod's own client particle
  subclasses) were never inventoried. All refs live in client-side code that never
  loads on a dedicated server → inert there, but they bound the trial: full-client
  translation needs the Mojang 1.20.1 **client** mappings (network dataset; this
  sandbox is offline) or a client srg jar (not present locally). Not a wrong entry:
  every id the table claims resolved exactly.

## Caveats (honest)
- deuf's duplicate-UUID *fix* path is not reachable via RCON: vanilla's summon command
  rejects duplicate UUIDs pre-join (and tracks entities even in unloaded chunks —
  verified). Reproducing it needs corrupt saved chunk data. What was proven today:
  handler registration + join-event dispatch through the full mapped member chain,
  zero errors (trial-001's original duplicate-path evidence stands, unchanged code
  path, unchanged rules coverage).
- nanny's handlers are silent by design unless damage is NaN; its regression gate is
  boot + registration + error-free dispatch of damage/death events (done) plus the
  bytecode check: `setCanceled` → `invokeinterface ICancellableEvent`, damage events →
  `LivingDamageEvent$Pre/Post.getNewDamage`.
- The extension mod fight was stopped at the mixin wall by design (~45 min in): the
  wall is phase-4 scope and the taxonomy below is complete.
- The 16/16 matrix was re-run **after** the four rewriter fixes (this file's commit
  includes both runs; the second run is the binding one).

## Next-step recommendation (priority order)
1. **Phase 4 (mixin refmap remap)** — the single hard stop. Mechanical core: rewrite
   each `*-refmap.json` (SRG → mojmap via the same table) + remap `method`/`target`
   strings in mixin annotations (plain string constants in class files — needs a
   string-constant pass) + handle the cross-version class drift seen above. Expected
   to unlock both mods past the current wall.
2. **Client-side SRG coverage**: extend the table universe with the 1.20.1 client
   inventory (client srg jar or Mojang client proguard) so client-class members
   resolve; server-side behavior is unaffected either way.
3. **Shim: capability holder facade** (ForgeCapabilities/LazyOptional → 1.21
   capability API) — 2 mixin classes across the two mods; small, well-scoped.
4. **JarJar/nested-jar policy** in the rewriter: drop-or-regenerate `META-INF/jarjar`,
   and skip nested jars that duplicate platform-provided libs (MixinExtras).
