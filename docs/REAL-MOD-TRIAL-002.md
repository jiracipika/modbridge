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

---

# Phase 4: mixin refmap remap + drift neutralization — DONE (2026-10-05, same day)

The wall from this morning is gone. supplementaries + moonlight now sail through the
entire mixin subsystem with **zero injection/apply errors**; both mod constructors run;
the death moved from "mixin apply" to construction-time calls into **removed 1.20.1
forge API** (shim territory — new data, precisely classified below).

## What was built
- **`Json` + `RefmapRemapper`** (rewriter/src, dependency-free, order-preserving): parses
  `*-refmap.json`, remaps every key and value (SRG ids → mojmap, L-references, dotted
  `@Mixin(targets)`, slash-form class values, field `name:Ldesc;` targets) with the SAME
  tables the class pipeline uses, prunes neutralized mixins from `mappings`/`data`, and
  strips them from the configs' server-side arrays. 32-check pure-logic unit test
  (`RefmapRemapperTest`, `scripts/test-refmap-remapper.sh`).
- **Mixin-class annotation string pass**: `method`/`target`/`targets` strings inside
  `@Inject`/`@At`/`@Accessor`/`@Invoker`/`@Shadow`-family annotations are plain string
  constants — remapped with the same rules (classes, descriptors, SRG ids, plus a new
  tiny `mojmemberrenames.txt` for mojmap→mojmap renames SRG ids can't cover).
- **Drift neutralization** (translation-time, class granularity): a mixin is dropped
  (class removed, config entry removed, refmap entry dropped, drift reported to stdout)
  when (a) its `@Mixin` target is absent from the 1.21.1 platform classlist
  (`rules/classlist-1211.txt`, generated from the sandbox server's jars by
  `scripts/gen-classlist-1211.sh`), (b) it eagerly references (super/interface/field/
  method descriptors) a platform class that no longer exists, (c) an
  `@Accessor`/`@Invoker` target member is absent from the 1.21.1 member inventory
  (`rules/membertargets-1211.txt`, name+descriptor, via `MemberDump`), or (d) an
  `@Shadow` member's name+descriptor is absent (descriptor drift is not mechanically
  fixable — argument-level shims would be needed). `client`-array mixins are exempt
  (never load on a dedicated server). Recoverable drift is RECOVERED instead:
  `tryCatchFire→checkBurnOut` and `dispenserRegistry→DISPENSER_REGISTRY` renames keep
  their mixins alive (FireBlockAccessor, DispenserBlockAccessor).
- **Injector strictness downgrade**: `defaultRequire 1→0` in translated configs plus
  `require=0` on every MixinExtras injector annotation (MixinExtras does not inherit
  `defaultRequire`) — call-site drift discovered only at apply time (e.g. NeoForge
  patched `PoiType.<init>` to stop calling `Set.copyOf`) now logs instead of killing boot.
- **Nested jars dropped**: `META-INF/jars/` + `META-INF/jarjar/` (walls 1+2 from the
  morning trial) — no more module-layer MixinExtras collision or JarJar ghost metadata.
- **Frame-safe `@Mod` constructor rewrite**: the old in-place splice (insert `ALOAD 1`,
  change the descriptor) silently shifted every local slot under the ctor's copied
  stack frames — `MoonlightForge` died at class load with `ArrayIndexOutOfBounds` in
  ASM's frame expander. The constructor body now MOVES verbatim into a synthetic
  `mb$init()V` (identical local layout → frames stay valid), and the real
  `<init>(IEventBus)V` becomes a short frame-free stub (super call, stash the bus in a
  synthetic static `mb$bus`, delegate).
- **Package/type rule fixes proven by a classlist audit**: `net/minecraftforge/fluids|items|client|server/` → the `neoforge` product root (the bare fallback silently
  produced class paths that don't exist), and `ICondition`'s "crafting" segment drop.

## Boot evidence (server-121, agent drop-in, trimmed excerpts in testenv/evidence/phase4-mixin-refmap-boot.txt)
- fixture 16/16 matrix re-run AFTER all phase-4 changes (regression clean).
- Boot: both jars translated at boot (moonlight 2.6s, supplementaries 10.2s);
  FML **discovered and listed both containers**; **0** occurrences of
  `InjectionError` / `injection failure` / `MixinApplyError` / `InvalidAccessorException`
  / `@Mixin target ... was not found` (morning boot: fatal on the first).
- `PoiMixin` (morning's killer) is a demonstrated success of the mechanism: its
  `@WrapOperation` call site no longer exists in NeoForge's patched `PoiType` ctor —
  handled by `require=0` fail-soft rather than a crash.
- Both constructors ran; the death is now construction-time:

| # | New wall (shim-level) | Signature |
|---|----------------------|-----------|
| 1 | Removed forge-era API in non-mixin code | `NoClassDefFoundError: net/neoforged/neoforge/common/ToolAction` in `SupplementariesForge` construction (1.21 removed `ToolAction`/`IPlantable`-era extension surface; needs an API facade, not a rename) |
| 2 | fml-internal reflection rename | `ObfuscationReflectionHelper: NoSuchMethodException net.neoforged.fml.config.ModConfig.setConfigData(CommentedConfig)` in moonlight's config static-init (method moved/renamed in 21.1) |
| 3 | (observed in audit, not yet hit at boot) | `FinishedRecipe`, `loot Serializer`, `SimpleChannel`/networking payload rework, capability-holder API (`Capability`/`LazyOptional`/`AttachCapabilitiesEvent`) — all removed-API surfaces referenced by translated non-mixin classes |

## Failure taxonomy update
- (a) found+fixed+re-proven this phase: package-rule product-root gaps (fluids/items/
  client/server), ICondition path, `@Mod` ctor frame corruption, ASM node-mutation bug
  (annotation values are [name,value,...] pairs — the first `mixinTargets` draft read
  the pairs as key→value), LabelNode-transparent prefix scan, ASM `InsnList.remove`
  link-nulling (must capture `next` before removals).
- (b) shim-needed (the new (b)-set, all post-mixin): capability holder facade, networking
  (SimpleChannel→payloads), ToolAction/IPlantable facade, config-reflection hook,
  `FinishedRecipe`/loot-serializer datagen surfaces.
- (c) unchanged: client-side SRG coverage (client mixins and client classes stay inert on
  the dedicated server).

## Caveats (honest)
- `Done` was NOT reached: the server aborts during mod construction (walls 1–2 above).
  The phase-4 goal was the mixin wall — it is fully beaten (0 mixin errors, both
  constructors entered their bodies); everything after is (b).
- `defaultRequire=0`/`require=0` trades hard failure for logged soft failure: a future
  refmap remap bug would surface as a logged injection error, not a crash. The residual
  SRG scan (0) plus the unit tests remain the correctness gate.
- The member inventory is name+descriptor, declared-members-only: accessors targeting
  inherited members could false-positive (none observed); same-name/different-desc
  accessors would false-negative (also not observed).
- The `@Mod` ctor rewrite handles the standard javac shape (pure-push super prefix,
  `this(...)` chaining is not supported and falls back to no rewrite).
- Rules files now carry generated platform inventories (classlist 8.4k classes,
  membertargets 109k members / ~12.8MB) regenerated by script against the sandbox's
  NeoForge install; they pin phase-4 drift checks to 1.21.1 server truth.
