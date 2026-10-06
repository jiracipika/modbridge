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

---

# Phase 5: capability facade + tool-action retarget — the constructor walls fall, boot converges on ONE config drift (2026-10-05, evening slice)

Time-boxed capability-facade slice. Both phase-4 construction walls are GONE and both
mods' constructors now run far enough to converge on a single remaining drift point
(moonlight's config wrapper). The capability chain — this phase's #1 target — does not
crash at all anymore: it lives behind an injected shim carrier.

## The exact API surface (javap/ASM inventoried, both 1.20.1 source jars)

Constructor-path surface actually touched (classes and member sites counted across all
1,589 mod classes):

| removed 1.20.1 API | supp | ml | measured member surface |
|---|---|---|---|
| `ToolAction` / `ToolActions` | 4 / 0 classes | 0 / 0 | `ToolAction.get(String)` (clinit of SupplementariesForge, SOAP_CLEAN), `ToolActions.{HOE_TILL,AXE_SCRAPE,AXE_WAX_OFF}` fields, `Block.getToolModifiedState(...,ToolAction,Z)`, `BlockToolModificationEvent.getToolAction()`, SOAP_CLEAN field |
| `LazyOptional` | 15 | 1 | `of(NonNullSupplier)` x8, `isPresent` x8, `orElse` x5, `ifPresent(NonNullConsumer)` x5, `cast` x5, `invalidate` x4, `resolve()Optional` x2, `empty` x2, `orElseThrow(NonNullSupplier)`, `orElseGet(NonNullSupplier)` |
| `Capability` (+Token/Manager) | 27 | 1 | `CapabilityManager.get(CapabilityToken)` x6 (anonymous tokens CapabilityHandler$1..6), `Capability.getName()`, static `Capability.orEmpty(Capability,LazyOptional)` |
| `ICapabilityProvider` | 27 | 1 | `getCapability(Cap,Direction)` + 1-arg default |
| `ForgeCapabilities` | 10 | 1 | `ITEM_HANDLER` x14, `FLUID_HANDLER` x2 |
| `AttachCapabilitiesEvent` | 2 | 0 | `getObject()` x2, `addCapability(ResourceLocation,ICapabilityProvider)` |
| `RegisterCapabilitiesEvent` | 2 | 0 | `register(Class)` x6 (CapabilityHandler.register, mod-bus @SubscribeEvent — fired DURING construction) |
| `NonNullSupplier`/`NonNullConsumer` | 7 / 4 | 1 / 0 | functional-interface descriptors |
| `ObfuscationReflectionHelper` | 0 | 4 | `findMethod(Class,String,Class...)` x4, `findField(Class,String)` x3 (moonlight config static init) |
| `ForgeSpawnEggItem` | (via ml) | 1 | `<init>(Supplier,int,int,Item$Properties)` (PlatHelperImpl spawn-egg factory) |
| FML `ModConfig` (drift, not removal) | (via ml) | 1 | `new ModConfig(Type,IConfigSpec,ModContainer,String)` — 4-arg ctor removed in 21.1 |

Not referenced by either mod: `INBTSerializable`, `Capability.orEmpty`'s register-side
relatives, `ToolActions` as a type.

## Facade design (two mechanisms, both established in this repo)

1. **(a) Retarget rules** — used where 21.1 kept a semantic equivalent:
   - `ToolAction` → `ItemAbility`, `ToolActions` → `ItemAbilities`. javap-verified
     EXACT: identical members (`actions` map, `get(String)` is the same
     `computeIfAbsent` create-or-get bytecode, `name()`), constants keep their names.
     Not a blind rename — proven first.
   - `ForgeRegistries$` → `NeoForgeRegistries$` (inner-class form; the exact-name rule
     from phase 1 never covered `$Keys`).
   - `ForgeSpawnEggItem` → `DeferredSpawnEggItem` (ctor shape identical).
   - `BlockToolModificationEvent.getToolAction()` → `getItemAbility()` (memberrename).
   - `Block.getToolModifiedState(...,ToolAction,Z)` needed NO rule: 21.1 declares it on
     `BlockBehaviour` with the same erased shape; invokevirtual resolves up-hierarchy.
2. **(b) Shim carrier jar classes** — injected into every translated jar, for removed
   APIs with no equivalent. 12 classes (built from `shims/src` by
   `scripts/build-shims.sh`, bundled under `rules/shims/` in the agent jar):
   `Capability`, `CapabilityToken`, `CapabilityManager`, `ICapabilityProvider`,
   `ForgeCapabilities`, `LazyOptional`, `NonNullSupplier`, `NonNullConsumer`,
   `AttachCapabilitiesEvent`, `RegisterCapabilitiesEvent`, `ConfigFileTypeHandler`,
   `ObfuscationReflectionHelper` (at `com/modbridge/shim/ObfuscationReflectionHelper`).
   Semantics preserved for the measured surface only; `LazyOptional` is a faithful
   lazy-cached/invalidate implementation, the two attach/register EVENTS are inert
   (never fired on 21.1 — attach/registration functionality is structurally present
   but dead, same treatment as client-only code; the 21.1 capability QUERY story is a
   different API and stays out of scope).

**Per-module shim packaging (the run005 lesson):** the first draft parked shims at the
retargeted platform names — FML exports every package of a mod module and JPMS kills
resolution when two layer modules export one package
(`Modules modbridgefixture and neoforge export package net.neoforged.neoforge.event`).
Shims now relocate to `com/modbridge/shim/<modid>/...` per jar (tree-API rename, no
asm-commons), rules reference them via an `@SHIM@/` placeholder resolved per mod.
`RegisterCapabilitiesEvent` additionally implements `IModBusEvent` (the mod bus
rejects listener types without the marker).

**Rewriter fix found by the slice:** StackMapTable frame entries carry ARRAY
descriptors (`[Lnet/...;`) — `remapType` handled only bare internal names. The
forge-ref gate caught the survivor (`SelfOpenableContainerMixin`, first un-neutralized
by the shim-aware `classKnown`), now fixed (dims strip + element remap).

**Cache-key fix:** the agent's translation cache now includes the agent jar's own
hash (it bundles rules + shims), so a rebuilt agent can never serve stale
translations.

## Boot progression (server-121, 5 fix cycles, evidence: testenv/evidence/phase5-facade-boot.txt)

| cycle | wall hit | fixed by |
|---|---|---|
| start | supp `NoClassDefFoundError ToolAction`; ml `UnableToFindMethodException setConfigData` (both phase-4 walls) | ItemAbility retarget; ObfRefl shim |
| 1 | `ClassNotFoundException ForgeRegistries$Keys` | `ForgeRegistries$` rule |
| 2 | `NoClassDefFoundError ...common/capabilities/RegisterCapabilitiesEvent` | shim + `@SHIM@` rule |
| 3 | `IllegalArgumentException: This bus only accepts subclasses of ...IModBusEvent` | shim implements the marker |
| 4 | `ClassNotFoundException ForgeSpawnEggItem` | DeferredSpawnEggItem rule |
| 5 | **converged** | — |

Final state: **both ctors enter and run; supplementaries' registry clinit passes in
full** (ItemAbility.get, DeferredRegister.create(ResourceKey,...),
NeoForgeRegistries$Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS) **and dies inside its own
config registration, which routes through moonlight**:

```
java.lang.NoSuchMethodError: 'void net.neoforged.fml.config.ModConfig.<init>(
  net.neoforged.fml.config.ModConfig$Type, net.neoforged.fml.config.IConfigSpec,
  net.neoforged.fml.ModContainer, java.lang.String)'
  at ...moonlight.api.platform.configs.forge.ConfigSpecWrapper.<init>(ConfigSpecWrapper.java:71)
  at ...moonlight.core.CommonConfigs.<clinit>(CommonConfigs.java:29)   [supp: configs.CommonConfigs.<clinit>:67]
```

Zero capability-chain signatures anywhere in the final boot log; the 12 per-mod shims
verify present in both output jars with 0 residual forge refs (forge-ref gate).

## Failure taxonomy update

- (a) fixed this phase: `@SHIM@` mechanism, JPMS per-module packaging, IModBusEvent
  marker, frame-array remap, cache fingerprint. All re-proven by matrix 16/16.
- (b) remaining shim/rewrite walls (all behind ONE gate now):
  1. **ModConfig 4-arg ctor** (the single current blocker, both mods). Not
     rename/shim-able: the class is `final`, the 21.1 ctor is 5-arg package-private
     (`..., ReentrantLock`), and the native 1.21.1 moonlight rewired this whole class
     to `ConfigTracker.registerConfig(...)` (javap'd its ForgeConfigHolder). Next
     session: a ctor-call retarget (NEW+INVOKESPECIAL surgery like the existing
     ResourceLocation ctor rewrite) to a factory that reflectively builds the real
     ModConfig — needs the agent to `redefineModule`-open `net.neoforged.fml.config`
     for `setAccessible`.
  2. Config LOAD path (behind #1): `ConfigFileTypeHandler.setupConfigFile` /
     `setConfigData(CommentedConfig)` semantics are gone in 21.1 (LoadedConfig rework);
     the fail-soft shim turns them into logged no-ops — config persistence will need
     real work after boot passes.
  3. `IPlantable`-era extension surface (SelfPlanterMixin stays neutralized), real
     capability QUERY bridging (IItemExtension.canPerformAction(ItemAbility) works,
     but stack.getCapability(ForgeCapabilities.ITEM_HANDLER) has no 21.1 dispatch),
     networking (SimpleChannel), FinishedRecipe/loot-serializer datagen — unchanged
     from the phase-4 (b)-set.
- (c) unchanged: client-side SRG coverage.

## Gates

- matrix: **16/16** — run005 (10/16) caught the JPMS split-package failure
  (testenv/evidence/matrix-run005-phase5-jpms-discovery.txt); run007 (final rules) and
  run008 (after the inner-class manifest fix) both 16 passed, 0 failed
  (matrix-run007-phase5.txt / matrix-run008-phase5-final.txt — run008 is binding).
- unit: `scripts/test-shims.sh` (NEW, 63 checks: shim runtime semantics, per-member
  descriptor exactness against the measured call sites, rules + injection),
  `scripts/test-refmap-remapper.sh` 3/3, `scripts/test-srg-resolver.sh` 4/4.

## Caveats (honest)

- The attach/register capability EVENTS are deliberately inert: the mods' capability
  ATTACHMENT and REGISTRATION code paths survive boot but never execute. Runtime
  capability BEHAVIOR (quiver/antique-ink on 1.21) is NOT claimed by this phase.
- `ConfigFileTypeHandler` + fail-soft `ObfuscationReflectionHelper` mean moonlight's
  config load/save is a logged no-op on 21.1 — configs load as defaults; this is
  visible, not silent, by design.
- `LazyOptional.of` resolution is cached-once; `addListener`/`getValueUnbiased`
  (unmeasured members) are not implemented.
- Server was stopped after the fight; the matrix re-run after it rotated the instance
  logs — final-state stacks are preserved in
  `testenv/server-121/crash-reports/crash-2026-10-05_18.58.31-fml.txt` (grep excerpts
  live in testenv/evidence/phase5-facade-boot.txt).

---

# Phase 5.5: the ModConfig ctor wall falls — configs clinit passes in full on both mods (2026-10-05, night slice)

Phase 5's single remaining blocker is GONE, and it took exactly one rule + a general
mechanism. Both mods now sail through their entire config static-init (the phase-5
death point) and die one layer further out, in their own `commonInit`, on the next
documented (b)-set — precisely classified below with ground truth.

## The wall was cheaper than the phase-5 plan assumed

The phase-5 notes planned "a ctor-call retarget to a factory that REFLECTIVELY builds
the real ModConfig, with the agent redefineModule-opening net.neoforged.fml.config".
Ground truth (javap on the sandbox's loader 4.0.44 + fmlcore 47.4.8) made reflection
unnecessary:

- NeoForge 21.1 `ModConfig` is final with one package-private 5-arg ctor
  `(Type, IConfigSpec, ModContainer, String, ReentrantLock)` — pure field-sets.
- `ConfigTracker.registerConfig(Type, IConfigSpec, ModContainer, String)` is PUBLIC,
  takes exactly the 4 args moonlight passes to `new ModConfig(...)`, and does
  ctor + `IConfigSpec.validateSpec` + `trackConfig` (+ eager open for STARTUP),
  returning the instance.
- Forge 1.20.1's 4-arg ctor field-set AND self-tracked via
  `ConfigTracker.trackConfig(this)` — so `registerConfig` is EXACT semantic parity
  (plus spec validation 1.20.1 didn't do at that site), and the receiver-lock
  bookkeeping (locksByMod) comes along for free. No shim, no reflection, no
  module-opening.

## What was built

1. **`rules/ctorretargets.txt` + the ctor-retarget pass** (rewriter): general
   NEW+DUP+INVOKESPECIAL surgery — forward-scan like the ResourceLocation rewrite,
   drop NEW+DUP, insert `GETSTATIC <newOwner>.<field>` under the args, swap
   INVOKESPECIAL for INVOKEVIRTUAL. Operand-stack shape identical (the returned
   instance replaces the uninitialized ref 1:1; maxStack only shrinks). Frames INSIDE
   the ctor-arg region (a branch among the args) name the uninitialized ref in TWO
   slots (NEW pushes it, DUP copies it): the first becomes the receiver type, the DUP
   copy is removed — a plain entry-drop would leave frames one slot short.
   FrameNode counts are private in ASM 9.x, so mutated frames are REBUILT (the
   `nStack` field does not exist to assign). Applied at raw-space call sites; inserted
   names are final-space and pass through the type remap untouched.
2. **`FMLJavaModLoadingContext` shim** (15th shim class): the retarget surfaced the
   NEXT wall inside the same constructor — `FMLJavaModLoadingContext.get()
   .getModEventBus().addListener(...)` at ConfigSpecWrapper.java:73, a forge-only
   class outside a @Mod class (the @Mod ctor splice never touches it). The shim
   routes `getModEventBus()` through `ModLoadingContext.get().getActiveContainer()
   .getEventBus()` — sound because the SAME constructor had already resolved
   `getActiveContainer()` successfully on the modloading-worker thread, and during
   construction the active container IS the owning mod (same bus the @Mod splice
   injects). Wired via typerename → `@SHIM@/FMLJavaModLoadingContext`.
3. **`rules/methoddrops.txt` + the method-drop pass**: whole-body replacement with a
   typed default return (`<init>` rejected at load time — the super-call invariant).
   This killed the entire remaining config chain in two rules, without new shims:
   - `register()`: its entire body is `activeContainer.addConfig(modConfig)` —
     1.20.1-only bookkeeping already covered by the ctor's registerConfig tracking
     (21.1 FML consults ConfigTracker's own maps, never a container config list).
   - `loadFromFile()`: moonlight's early direct-load path — reflects into
     `ModConfig.setConfigData` (a method the FINAL 21.1 ModConfig can never carry:
     `findMethod` can only return null and the caller does not guard it), then
     `IConfigEvent.loading` / `dispatchConfigEvent` / `ModConfig.save()` — all
     removed in the 21.1 config rework, and the method's own try/catch converts every
     failure into a fatal ConfigLoadingException. No shim can make this path succeed;
     the no-op body routes config loading through FML's OWN 21.1 lifecycle
     (`ConfigTracker.loadConfigs` builds the toml from spec defaults — the ctor
     registration enrolled both configs into it).

## Boot progression (server-121, agent drop-in, evidence: testenv/evidence/phase55-config-ctor-boot.txt)

| cycle | wall hit | fixed by |
|---|---|---|
| start | `NoSuchMethodError ModConfig.<init>` (phase-5 stop) | ctorretargets.txt rule |
| 1 | `NoClassDefFoundError FMLJavaModLoadingContext` (ConfigSpecWrapper.<init>:73) | FMLJavaModLoadingContext shim |
| 2 | `NoSuchMethodError ModContainer.addConfig` + `NoSuchMethodError ModConfig.getHandler` | methoddrops (register, loadFromFile) |
| 3 | **configs clinit passes on BOTH mods** | — |

Final walls — the next (b)-layer, both in the mods' own commonInit:

| wall | signature | scope |
|---|---|---|
| loot Serializer world | `NoClassDefFoundError: net/minecraft/world/level/storage/loot/Serializer` (moonlight commonInit:75, refs in MoonlightRegistry + ModLootConditions) | 1.21.1 removed the interface entirely (javap-verified): loot conditions/functions are `LootItemConditionType`/codec, GLM serializers are MapCodec — a semantic per-implementer bridge, not a rename |
| eager config reads | `IllegalStateException: Cannot get config value before config is loaded` from real `ModConfigSpec$ConfigValue.getRaw` (supp commonInit:88 via its configs clinit) | legal on 1.20.1 BECAUSE moonlight eagerly loaded configs during registration (the dropped loadFromFile path); 21.1 FML loads COMMON configs later. Fix is a design decision (eager-load bridge vs deferred reads), not mechanical |

## Gates

- matrix: **16/16** after all changes (final rules).
- unit: `scripts/test-ctor-retarget.sh` (NEW, 35 checks: surgery shape, frame-slot
  collapse, scan-stop, rule parsing incl. both real rule files, ctor-drop rejection),
  `scripts/test-shims.sh` 3/3 (shim count 14→15), refmap 3/3, srg-resolver 4/4.
- fixture sanity: `ctor retargets=0`, `method drops=0` on the fixture jar (rules inert
  where they should be).
- phase-1 real-mod regression: nanny-1.0.6 + deuf-1.3 + fixture boot clean
  (`Done (0.586s)`, both listed, 0 ERROR/FATAL lines).

## Caveats (honest)

- Config PERSISTENCE is still the documented no-op: values load from spec defaults
  via FML's pipeline; moonlight's early-direct-load (and its setConfigData write-back)
  is dead by rule. Editing a config file will take effect only where FML re-reads it.
- The IConfigEvent-typed listeners registered via the shim bus (ctor offsets 128-168)
  are so far inert-by-erasure (javac compiles Consumer lambdas with erased Object
  descriptors; the checkcast into the removed IConfigEvent sits inside the lambda
  body and only resolves if the listener fires). If a real 21.1 ModConfigEvent ever
  dispatches into one, the next wall is a CNFE there — candidates: shim the interface
  or methoddrop the lambda bodies. Not hit on this boot (construction died before
  server config load).
- The two new walls are (b)-layer: both need semantic bridges (codec adapters /
  eager-load design), not mechanical rules. They are the scoped opener for the next
  slice, ahead of networking (SimpleChannel) and the remaining datagen surfaces.

# Eager-config slice: construction-time config reads return real values (2026-10-05)

The phase-5.5 "next wall" — `IllegalStateException: Cannot get config value before
config is loaded.` from the REAL 21.1 `ModConfigSpec$ConfigValue.getRaw` — is fixed.
Both configs clinit and the mod-ctor-chain reads now see file values (spec defaults on
first run), through the 21.1 platform's own loading pipeline.

## Ground truth (all javap'd, loader 4.0.44 + universal 21.1.250 + bus 8.0.5)

- 21.1 order: mods CONSTRUCT first (where `new ModConfig` sites register configs), the
  "Config loading" init task (`CommonModLoader.begin` → `ConfigTracker.loadConfigs`)
  runs AFTER all constructions, common setup later still. 1.20.1 order: moonlight's
  `ConfigSpecWrapper.<init>` eagerly loaded files during registration (loadFromFile),
  so reads in mod construction worked.
- The measured death is INSIDE `CommonConfigs` clinit, not a lazy read:
  `ConfigSpecWrapper`'s own ctor unconditionally builds `requireRestartValues` via
  `stream().collect(toMap(identity, ConfigValue::get))` (BootstrapMethods #4/#5) —
  `ConfigValue.get()` → `getRaw` → `Preconditions.checkState(loadedConfig != null)`.
  The phase-5.5 boot stack's `Collectors.lambda$uniqKeysMapAccumulator` frame is
  exactly this toMap. `getRaw` throws until `ModConfig.setConfig` runs
  `IConfigSpec.acceptConfig` — i.e. until loadConfigs opens the config.
- Read surface: supplementaries defines 245 config values (12 config classes; 152
  classes read `CommonConfigs`) through moonlight Supplier wrappers over
  `ForgeConfigSpec$ConfigValue`. There is exactly ONE translated `new ModConfig` site
  in both jars (ConfigSpecWrapper.<init>:75) — the single choke point.

## What was built

1. **`ModConfigBridge` shim** (16th carrier class, per-mod package): the
   ctor-retarget rule's new target. `registerAndLoad(Type, IConfigSpec, ModContainer,
   String)` = `ConfigTracker.INSTANCE.registerConfig(...)` (phase-5.5 semantics kept:
   ctor + validateSpec + trackConfig + eager open for STARTUP), then — COMMON type
   only — `ConfigTracker.INSTANCE.loadConfigs(COMMON, FMLPaths.CONFIGDIR.get())`.
   CLIENT configs are never eager-loaded (a dedicated server does not load them —
   same on both platforms); SERVER stays on FML's lifecycle.
2. **`@SHIM@` owners in ctorretargets.txt** (rewriter): `rewriteCtorRetargets`
   resolves rule owner `@SHIM@/<SimpleName>` to the per-mod shim package via the
   existing `resolveShimTarget` (manifest-missing = translation fails fast). Rule
   format unchanged (GETSTATIC+INVOKEVIRTUAL shape — the shim exposes a public
   `INSTANCE` receiver field).

## Trade-offs accepted (documented in the shim's javadoc + rule comments)

- loadConfigs is per-TYPE: it opens EVERY tracked COMMON config, not just this mod's.
  Native mods' earlier-registered configs load early; when FML runs its own
  loadConfigs pass, `openConfig` re-opens them (WARN log line + a SECOND
  `ModConfigEvent$Loading` per config). Reload semantics listeners must already
  tolerate (FileWatcher does the same); the FileWatcher re-watch is a `Map.put`
  replace in nightconfig 3.8.3 (no exception).
- The early ModConfigEvent fires before the owning ctor's own listeners register
  (the retarget sits a few instructions before `addListener` in ConfigSpecWrapper):
  absorbed; FML's real pass delivers it. Derived eager fields (supp's
  `stasisEnabled`) update at FML's pass, so ctor-chain reads of PLAIN fields keep
  their clinit defaults — the accepted "values = defaults until FML's real load"
  semantic for non-Supplier state.
- Concurrent translated-mod constructions may call loadConfigs concurrently:
  per-config locks (registerConfig's locksByMod ReentrantLock) serialize setConfig;
  ConfigTracker structures are concurrent; last writer wins.

## The phase-5.5 IConfigEvent landmine — already defused, proven

javap of the RAW jar shows the ctor's addListener consumers are METHOD REFS, not
IConfigEvent lambdas: `onConfigChange(ModConfigEvent)`, `onPlayerLoggedIn/Out`. The
remapped BootstrapMethods targets are the REAL 21.1 classes
(`net/neoforged/fml/event/config/ModConfigEvent` has `getConfig()`;
`net/neoforged/neoforge/event/entity/player/PlayerEvent$*`), so the runtime lambda
checkcast + the bus's TypeResolver inference (`net.jodah.typetools` on the serialized
lambda — EventBus.getEventClass) resolve. The ONLY `IConfigEvent` constant-pool
reference in either raw jar is inside the already method-dropped `loadFromFile` —
zero live references in both translated jars (proven offline). No shim, no extra
methoddrops needed.

## Gates (offline only — live boot owned by the main session)

- unit: test-ctor-retarget.sh 3/3 (42 checks: +@SHIM@ owner resolution, +fail-fast on
  unknown manifest entry, rule-file assertions updated; the test's NEW_DESC constant
  was raw-space-typed and is now the true final-space desc), test-shims.sh 3/3
  (66 checks; shim count 15→16), refmap 3/3, srg-resolver 4/4.
- fixture sanity: ctor retargets=0, method drops=0 (rules inert where they should be).
- offline proof (scripts in the evidence file): ctor sites route through
  `com/modbridge/shim/moonlight/ModConfigBridge.registerAndLoad`; bridge body =
  registerConfig + conditional loadConfigs(COMMON, CONFIGDIR); zero IConfigEvent refs
  in both output jars (testenv/evidence/phase555-eager-config-offline-proof.txt).

## Honest caveats / next walls

- Config PERSISTENCE semantics unchanged (documented no-op write-back); values now
  load from the existing toml or spec defaults at registration, and FML re-loads
  (correcting) at its own pass.
- `ModContainer.acceptEvent` null-guards the bus, so the early event is safe even for
  containers without a bus yet.
- moonlight's databuddy API `ConfigHelper.register(...)` calls the REMOVED
  `ModLoadingContext.registerConfig` (javap-verified gone on 21.1) — unused by
  moonlight/supplementaries construction, but any future mod calling it needs a
  memberretarget to `ModContainer.registerConfig` + this bridge. Out of measured scope.
- Expected next wall (unchanged from phase 5.5): moonlight commonInit's loot
  `Serializer` NCDFE — the codec-adapter bridge, and then networking
  (SimpleChannel→PayloadRegistrar). The early ModConfigEvent now arms moonlight's
  synced-config path (`sendSyncedConfigsToAllPlayers` → networking classes) at FML's
  real load pass, but it iterates an empty player list at boot time, so no networking
  class resolves there yet.
