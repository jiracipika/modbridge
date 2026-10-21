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

# Phase 5.6: the loot Serializer wall falls — shimmed type holders land in the real 21.1 loot registries (2026-10-05, loot slice)

The phase-5.5 handoff's first next wall — `NoClassDefFoundError:
net/minecraft/world/level/storage/loot/Serializer` at moonlight commonInit:75 — is
gone by rule + shim alone: zero Rewriter.java changes. Both mods translate clean and
every previously-failing loot class now references only classes that exist.

## The measured 1.20.1 surface (javap, both raw jars)

- **Interface refs (6 classes):** moonlight `MoonlightRegistry`, `ModLootConditions`
  (+2 inner `ConditionSerializer` records, +`OptionalPropertyCondition$ConditionSerializer`),
  supplementaries `ModRegistry`.
- **Implementers (6 classes):** 4 moonlight (`OptionalPropertyCondition$ConditionSerializer`
  implements `Serializer<T>` directly; the 2 `ModLootConditions` serializer records;
  `OptionalItemPool$Serializer extends LootPoolSingletonContainer$Serializer`) and
  2 supplementaries (`RandomArrowFunction$Serializer` / `CurseLootFunction$Serializer`
  extend `LootItemConditionalFunction$Serializer`). Members are the classic Gson
  pair `serialize(JsonObject,T,JsonSerializationContext)` / `deserialize(...)`
  carried by SRG bridges `m_6170_`/`m_7561_` (→ `serialize`/`deserialize` after the
  SRG-declaration rename the rewriter already does).
- **Type-holder ctor sites (6):** `new LootItemConditionType(serializer)` ×3
  (MoonlightRegistry 1, ModLootConditions 2), `new LootPoolEntryType(serializer)` ×1
  (MoonlightRegistry), `new LootItemFunctionType(serializer)` ×2 (supp ModRegistry).
  All 6 nest a `new SomeSerializer()` INSIDE the ctor args — the exact shape the
  ctor-retarget pass conservatively refuses (its forward scan stops at any
  TypeInsnNode), which ruled the ctor-retarget mechanism out.
- **Registration:** everything flows through moonlight's `RegHelper.register` →
  `DeferredRegister.create(ResourceKey, modid)` into the REAL registries
  (`Registries.LOOT_CONDITION_TYPE` / `LOOT_POOL_ENTRY_TYPE` / `LOOT_FUNCTION_TYPE`
  — all three still exist on 21.1). supplementaries' GLM codecs ride
  `DeferredRegister` on `GLOBAL_LOOT_MODIFIER_SERIALIZERS` (21.1 value type is
  `MapCodec<? extends IGlobalLootModifier>`; the mods supply `Codec` — erased at
  runtime, loads fine, and `LootModifier`'s base-class surface is byte-identical
  across the fork: protected `(LootItemCondition[])` ctor + final `apply` +
  abstract `doApply`).
- **Everything else in the loot namespace already exists on 21.1** — a
  referenced-class inventory of both raw jars against the 1.21.1 server jar found
  exactly THREE absent classes: `loot/Serializer`,
  `entries/LootPoolSingletonContainer$Serializer`,
  `functions/LootItemConditionalFunction$Serializer` (plus the three type holders,
  which exist but changed SHAPE to final `MapCodec` records).

## The approach: shim the interface, the two superclasses, AND the three type holders

Option (a) — working Gson↔DFU codec adapters per implementer — is a per-implementer
semantic rebuild (JsonDeserializationContext plumbing, registry-backed sub-parsers)
whose only payoff is loot-JSON PARSING, a runtime surface no boot ever touches.
Option (c) (neutralization) is unmeasurable-by-design here since registration is
required for the mod's own `getType()` sites. So: the precedent-backed (b), with one
twist — the type holders themselves are shimmed instead of ctor-retargeted:

- `@SHIM@/LootSerializer` — the 1.20.1 `Serializer<T>` surface with the mojmap
  member names, so the translated implementers' renamed bridges
  (`serialize(JsonObject,Object,...)` / `deserialize(JsonObject,...):Object`) make
  them TRUE implementers again.
- `@SHIM@/LootPoolSingletonContainerSerializer` /
  `@SHIM@/LootItemConditionalFunctionSerializer` — superclass-chain carriers
  (implement LootSerializer; members deliberately NOT re-declared: the translated
  subclasses keep them as plain methods nothing dispatches through).
- `@SHIM@/LootItemConditionType` / `@SHIM@/LootPoolEntryType` /
  `@SHIM@/LootItemFunctionType` — 1.20.1 construction surface
  (`<init>(LootSerializer)` + `getSerializer()`), PLUS the erased 21.1 record
  accessor `codec(): ()Lcom/mojang/serialization/MapCodec;` returning an inert
  erroring MapCodec (fail-loud, per-table, at loot-JSON parse time).

Why shimming the holders works: the shim VALUES land in the REAL 21.1 registries
(erasure — no value class check anywhere in DeferredRegister/RegisterEvent/
Registry.register), the mods' own `getType()`/checkcast sites stay self-consistent
(they see the shim type everywhere after the blanket rename), and vanilla's
loot-JSON dispatch — the only code that reads registry values — resolves
`codec()` against the erased record shape and gets a loud per-table failure instead
of a foreign-object ClassCastException. Boot proceeds; loot serialization runtime is
explicitly not claimed (same acceptance as the inert capability events).

## Offline proofs (this slice ran NO server)

`bash scripts/translate.sh` on both raw jars; a 9-check proof script over the
outputs (per-class ASM ref inventory vs the full 21.1 platform universe — srg server
jar + neoforge universal + loader + bus — plus each jar's own classes + injected
shims): baseline/raw jars reference all 6 removed names (the wall) → translated jars
have ZERO references to them; the 15 previously-failing loot classes are present and
reference only existing classes; moonlight's unresolved-ref set gained NOTHING vs
the phase-5.5 baseline translation (the known pre-existing non-loot remainder —
client-side classes, networking/SimpleChannel, datagen FinishedRecipe,
IIngredientSerializer/IConditionSerializer, and absent other-mod soft-refs — is
unchanged); 8 loot-lane shim classes injected per mod. Bytecode spot-checks:
`lambda$static$0/2` now construct `com/modbridge/shim/<modid>/LootItemConditionType`
/ `.../LootItemFunctionType` with `.../LootSerializer`; the implementers implement
the shim interface with renamed bridges; `OptionalItemPool$Serializer` extends the
shim superclass.

Gates: `scripts/test-shims.sh` 3/3 (ShimFacadeTest 88 checks, shim manifest
15→23; loot descriptors: erased bridge shapes, 1.20.1 ctor surface, erased 21.1
`codec()` shape, generic bounds, shim-vs-record classpath guard), ctor-retarget
3/3, refmap 3/3, srg-resolver 4/4, fixture sanity (`ctor retargets=0`,
`method drops=0` — the new rules are inert outside the loot surface).

## Caveats (honest)

- Loot JSON SERIALIZATION of translated conditions/functions/entries is dead by
  design: any loot table actually referencing `moonlight:optional_item`,
  `moonlight:iconditions`, `supplementaries:curse_loot`, etc. logs a ModBridge-named
  parse failure for that table (loader continues). Making those WORK is the real
  (a)-ladder bridge (Gson serializer → MapCodec adapter), deferred until a boot
  proves it matters.
- The registry values are shim objects: any OTHER 1.21.1-side consumer that
  hard-casts `Registries.LOOT_*` values to the real records (none in vanilla's
  dispatch path, which reads `codec()` erased) would CCE. Measured consumers in the
  two mods read nothing.
- supplementaries' `ExplorationMapSerializerMixin` (target
  `ExplorationMapFunction$Serializer`) is neutralized by the existing phase-4
  machinery — its behavior (explorer-map structure tweak) rides the removed
  superclass and is not claimed.
- Expected next walls (pre-existing, unchanged by this slice, in fight order):
  1. `AbstractCriterionTriggerInstance` — moonlight `GrindItemTrigger` extends the
     21.1-present `SimpleCriterionTrigger` (clinit registration is safe), but its
     `Instance` inner class extends the 1.20.1 `AbstractCriterionTriggerInstance`,
     which 1.21.1 folded into `SimpleCriterionTrigger$SimpleInstance` — dies when
     the Instance is first constructed (advancement listener registration, world
     play), not at boot.
  2. eager config reads (`Cannot get config value before config is loaded`) —
     the phase-5.5 wall, untouched here (config lane).
  3. networking (`net/neoforged/network/simple/SimpleChannel` — the bare
     forge→neoforged package fallback dropped the `/neoforge/` segment; 21.1 lives
     at `net/neoforged/neoforge/network/...`) and `FinishedRecipe` datagen.
  4. moonlight `IIngredientSerializer` (BlockTypeSwapIngredientImpl) +
     `IConditionSerializer` (RegHelperImpl/OptionalRecipeCondition) — same
     serializer-to-codec semantic family as this wall.

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

# Phase 5.7: the crafting-condition serializer wall falls — IConditionSerializer + CraftingHelper shims (2026-10-05, conditions slice)

The conditions-lane wall from the merged boot — `NoClassDefFoundError:
net/neoforged/neoforge/common/crafting/conditions/IConditionSerializer` attributed to
`RegHelper.register ← MoonlightRegistry.<clinit>:33` — is gone with TWO shims and TWO
typerename rules: zero Rewriter.java changes (this lane), same design as the loot lane.

## Why it fired at RegHelper (the trace explained)

Phase 1 deliberately left `IConditionSerializer` unmapped (the typerenames comment),
so the bare forge→neoforged package fallback renamed every reference to a phantom
(`net/neoforged/neoforge/common/crafting/conditions/IConditionSerializer` — absent
from the 21.1 universal jar, javap-verified). The resolution site is NOT the
serializer's own code path: `RegHelper`/`RegHelperImpl` is the generic registration
helper EVERY mod registration goes through, and its `registerSimpleRecipeCondition`
body constructs `OptionalRecipeCondition` and passes it to
`CraftingHelper.register(IConditionSerializer)` — verifying that body forces the
assignability constraint `OptionalRecipeCondition <: IConditionSerializer`, which
resolves the phantom. `MoonlightRegistry.<clinit>:33` (source) is simply the FIRST
`RegHelper.register` call, i.e. the first event that links `RegHelper` — hence a CNFE
whose stack names a method that never runs.

## The measured 1.20.1 surface (javap, both raw jars)

- **`IConditionSerializer`**: 1 implementer total — moonlight
  `OptionalRecipeCondition implements IConditionSerializer<OptionalRecipeCondition$Instance>`
  with `write(JsonObject,T)` / `read(JsonObject):T` / `getID()` plus
  `ICondition`-erased bridges (exact erasure of the `<T extends ICondition>` bound).
  Registrations: `RegHelper(Impl).registerSimpleRecipeCondition` → forge
  `CraftingHelper.register(IConditionSerializer)` (forge's static facade over
  ForgeRegistries.CONDITION_SERIALIZERS — no direct registry-key references
  anywhere in the trial jars; supplementaries registers through the same
  moonlight helper with its "flag" condition).
- **`CraftingHelper`**: 4 members referenced — `register(IConditionSerializer)` (the
  boot wall), `getCondition(JsonObject)` (PlatHelperImpl, TemplateRecipeManagerImpl),
  and the ingredient pair `register(ResourceLocation, IIngredientSerializer)` /
  `getID(IIngredientSerializer)` (BlockTypeSwapIngredientImpl — the ingredient
  family, a DIFFERENT removed surface, deliberately NOT this lane; its
  `IIngredientSerializer` remains package-renamed phantom).
- **`ICondition` / `ICondition$IContext`**: already typerenamed to the real 21.1
  interface — which kept `test(IContext)` and `IContext.EMPTY`/`TAGS_INVALID` and
  dropped only `getID()` (no cross-type `getID` caller measured; the translated
  `OptionalRecipeCondition$Instance` implements the real 21.1 ICondition minus
  `codec()` — loads fine, AbstractMethodError only if condition dispatch ever reads
  it, i.e. recipe-parse time, not claimed).

## The 21.1 truth (javap, universal jar)

`net/neoforged/neoforge/common/conditions/ICondition` = codec-based
(`test(IContext)` + `MapCodec<? extends ICondition> codec()`; statics CODEC /
LIST_CODEC / writeConditions); `ICondition$IContext` alive; condition types register
`MapCodec`s into `NeoForgeRegistries.Keys.CONDITION_CODECS`
(`ResourceKey<Registry<MapCodec<? extends ICondition>>>`) and dispatch by "type" via
`Registry.byNameCodec().dispatch(...)`. `IConditionSerializer` gone.
`net/neoforged/neoforge/common/crafting/CraftingHelper` EXISTS but was reshaped to
ingredient-codec builders only — every member the mods call is gone.

## What was built (option (b), the round-1 design)

1. **`@SHIM@/IConditionSerializer`** (source
   `shims/src/net/neoforged/neoforge/common/conditions/IConditionSerializer.java`) —
   the exact 1.20.1 surface (`write`/`read`/`getID` + mirrored default `getJson`),
   generically bound `<T extends ICondition>` against the REAL 21.1 conditions
   interface, so the translated `OptionalRecipeCondition` is a true implementer
   (bridges included).
2. **`@SHIM@/CraftingHelper`** (source
   `shims/src/net/neoforged/neoforge/common/crafting/CraftingHelper.java`) —
   `register(IConditionSerializer)`: 1.20.1 return-value contract + inert per-mod
   registry (the serializers stay reachable for the mod's own flow; nothing on 21.1
   dispatches Gson serializers). `getCondition(JsonObject)`: the FAITHFUL half of
   the bridge — parses via 21.1 `ICondition.CODEC` (JsonOps), so known
   vanilla/NeoForge condition JSON parses for real at datapack load; unknown /
   translated types fail LOUDLY naming the condition id (converted from any
   failure, including the offline-unbootstrapped-registry case). The ingredient
   members are deliberately absent (deferred lane — see caveats).
3. **Rules** (appended section at the end of rules/typerenames.txt):
   `.../conditions/IConditionSerializer -> @SHIM@/IConditionSerializer` (exact-key
   beats the package fallback; no collision with the existing `ICondition`/`ICondition$`
   rules) and `.../crafting/CraftingHelper -> @SHIM@/CraftingHelper` (same; the real
   21.1 CraftingHelper at the platform name is untouched for platform code).

## Offline proofs (no server; scripts/proof outputs in /tmp/loot-lane)

Both raw jars translate clean through the MERGED pipeline. 10-check conditions
proof: zero bytecode references to all 4 removed/phantom condition names (raw-space
forge name + package-renamed phantom + both CraftingHelper spellings) in either jar;
12 focus classes (MoonlightRegistry, RegHelper, RegHelperImpl,
OptionalRecipeCondition+Instance, PlatHelperImpl, TemplateRecipeManagerImpl, the
ModLootConditions ICondition chain, ForgeHelperImpl, MoonlightForge,
ConditionHackMixin, Supplementaries) reference only existing platform/own/shim
classes; both condition shims injected per mod. Regression: the round-1 loot proof
re-run on the same fresh outputs — 9/9 (zero loot-name refs; both jars). And the
unresolved-ref sets REGRESSED BY NOTHING vs the round-1 translations in either jar,
with the condition phantoms gone from the sets. Bytecode spot-checks:
`RegHelperImpl.registerSimpleRecipeCondition` now calls
`com/modbridge/shim/<modid>/CraftingHelper.register:(Lcom/modbridge/shim/<modid>/IConditionSerializer;)...`;
`OptionalRecipeCondition implements com.modbridge.shim.<modid>.IConditionSerializer<...>`.

Gates: test-shims 3/3 (ShimFacadeTest 106 checks; manifest 24→26; condition checks:
erased bridge shapes vs the `<T extends ICondition>` bound, 1.20.1 ctor/call shapes,
inert-registry + return-contract runtime semantics, getCondition loud-failure with
the id, offline-safe class-init), ctor-retarget 3/3, refmap 3/3, srg-resolver 4/4,
fixture sanity (`ctor retargets=0`, `method drops=0`), agent builds.

## Caveats (honest)

- Recipe-JSON condition PARSING of translated condition types (e.g.
  `supplementaries:flag`, moonlight's own) fails loudly at datapack/recipe load
  (known vanilla/NeoForge types parse for real). The full (a)-bridge would
  synthesize a MapCodec per translated serializer — deferred until a boot proves a
  translated condition is actually load-bearing.
- Cross-mod condition JSON: supplementaries' "flag" condition is registered in the
  supplementaries shim module only; a moonlight recipe referencing it fails loudly
  (per-recipe parse error, loader continues). Same per-mod isolation as every shim.
- **Ingredient serializer family is the next wall** (measured, deferred):
  `net/neoforged/neoforge/common/crafting/IIngredientSerializer` still
  package-renamed phantom — moonlight BlockTypeSwapIngredientImpl(+1) references it
  and calls the two CraftingHelper ingredient members; that class loads lazily, so
  it does not affect boot, but any recipe using `moonlight:block_type_swap`
  ingredients dies at parse time. Same shim design applies (interface + the two
  CraftingHelper members + inert registry).
- Otherwise the unresolved-set remainder is unchanged from round 1 (client-side
  classes, networking/SimpleChannel package drift, FinishedRecipe datagen,
  AbstractCriterionTriggerInstance, eager-config already covered by the config
  lane's bridge).

# Round 2: the DFU interface-static ICCE wall falls (2026-10-05, post-merge slice)

Live boot after the lane merge: both round-1 walls PASSED (configs read at
construction; loot codecs registered). New wall, inside the eager config load:
`java.lang.IncompatibleClassChangeError: Method 'DataResult.success(Object)' must be
InterfaceMethodref constant` at moonlight databuddy `ConfigHelper$TomlConfigOps`.
1.20.1 shipped DFU 6 (DataResult = CLASS → javac emits Methodref for its statics);
21.1 ships DFU 8.0.16 (DataResult = INTERFACE → the JVM demands InterfaceMethodref).
The same drift shape fires for ANY 1.20.1 bytecode calling a class that became an
interface.

## Ground truth

- javap DFU 8.0.16: `DataResult` is an interface; the full static surface moonlight
  uses exists verbatim (`success(R)`, `error(Supplier)`, `error(Supplier,R)`, plus
  Lifecycle overloads, `unbox`, `partialGet`, `instance`); `Pair` stayed a CLASS;
  `DataResult$Error` stayed a class.
- JVM truth matrix, PROVEN on the sandbox's JDK 21 with an ASM-generated caller
  (Methodref vs InterfaceMethodref × opcode): invokestatic+Methodref on an interface
  static → ICCE "must be InterfaceMethodref" (the live wall); invokevirtual+Methodref
  on an interface member → ICCE "Found interface, but class was expected";
  invokevirtual+Methodref for an Object member named through the interface owner →
  ICCE as well. All three repaired shapes work: static itf=true;
  INVOKEINTERFACE+itf=true; INVOKEVIRTUAL with owner java/lang/Object (exactly what
  javac emits for interface-typed receivers).
- Drift census across BOTH raw jars (every `// Method` invokestatic/invokevirtual
  whose owner is a 21.1 interface): exactly ONE drifted type —
  `com/mojang/serialization/DataResult`: moonlight 47 sites (29 invokestatic: 15
  success + 14 error; 18 invokevirtual), supplementaries 36 sites (4 invokestatic:
  2 success + 2 error; 32 invokevirtual: 16 result + 15 getOrThrow + 1 map).
  Both counts equal the pass counters exactly (47 / 36).
  Sites already interface-shaped on 1.20.1 (DynamicOps, IEventBus, ...) carry
  itf=true and are left untouched — the `!m.itf` guard makes the repair fire only on
  genuinely drifted constants.

## What was built

1. **`scripts/gen-interfaces-1211.sh`** (new): scans EVERY jar under the sandbox
   libraries tree with an embedded single-file Java class-file parser (constant-pool
   walk → access_flags, ACC_INTERFACE mask, annotations included) and emits
   **`rules/interfaces-1211.txt`** — 5,496 internal interface names (minecraft srg +
   neoforge + fml + bus + mojang DFU/brigadier/authlib/logging + night-config +
   netty/guava/gson/fastutil/...). Same regen conventions as gen-classlist-1211.sh.
2. **Interface call-site repair pass** (rewriter, runs LAST in transform — after
   remapClass, so call-site owners are final-space and match the inventory):
   - INVOKESTATIC on an inventoried interface → keep opcode, `itf = true`
     (InterfaceMethodref);
   - INVOKEVIRTUAL on an inventoried interface → INVOKEINTERFACE + itf=true, EXCEPT
     the java/lang/Object members (equals/hashCode/toString/...), which keep
     INVOKEVIRTUAL and move to the Object owner;
   - sites already itf=true are skipped; getstatic/putstatic need no repair (field
     refs have no itf bit); INVOKESPECIAL-on-interface has no 1.20.1-class-shaped
     producer here. Never guesses: only inventoried owners are touched. Counter in
     the translation summary: `interface repairs=N`.
   - Wired into BOTH rule loaders (CLI readLines — missing file = empty = pass
     disabled; agent resourceLinesOptional) + printRuleStats.

## Gates

- unit: ctor-retarget 3/3 (52 checks: +8 interface-repair checks — inventoried
  static repaired, already-itf untouched, virtual→invokeinterface, Object-member
  owner move, non-inventoried Pair untouched, counter, serialize, empty-inventory
  no-op, missing-file tolerance), shims 3/3, refmap 3/3, srg-resolver 4/4.
- translation: moonlight `interface repairs=47` (= exactly its 47 drifted census
  sites), supplementaries `interface repairs=36` (12 DataResult + 24 sites whose
  owners remap into 21.1 interfaces — see census note below).
- offline proof 14/14 incl. round-1 regression: zero malformed Methodref-on-interface
  sites left in either output jar; TomlConfigOps carries InterfaceMethodref for
  DataResult.success(Object) specifically (testenv/evidence/
  phase555-r2-interfaces-offline-proof.txt).

## Honest caveats / next walls

- MEMBER drift is a different family and is NOT fixed by a kind repair:
  `DataResult.getOrThrow(Z, Consumer)` (20 call sites: 5 moonlight — TomlConfigOps
  lambda createMap$11 + ConfigHelper — and 15 supplementaries) and
  `DataResult.get()Either` (2 moonlight sites) do not exist on DFU 8 (which offers
  `getOrThrow()`, `getOrThrow(Function)`, `result()`). They are now kind-repaired
  (invokeinterface) and fail with NoSuchMethodError instead of ICCE IF executed —
  a semantic adapter shim is the fix (map (Z,Consumer) →
  resultOrPartial/getOrThrow(Function) semantics), scoped for the next slice.
  14 supplementaries + 6 moonlight classes carry getOrThrow refs; every other
  drifted member (success/error/map/flatMap/result/error()/resultOrPartial) exists
  on DFU 8 with a matching erased descriptor and is fully fixed by this slice.
- A mod shading its own copy of a drifted type under the same internal name would
  be wrongly repaired (its copy is a class in its own module) — none of the measured
  mods shade DFU; noted for the record.
- Cosmetic: Object-member dispatch through the Object owner reaches the implementing
  object's override even where DFU 6's class had its own override — same method
  resolution javac-on-interface produces; no crash path.

## Phase 5.9 — round-3 lanes merged; DFU member drift + criteria + rename family fall (2026-10-06)

Both round-3 walls from 5.8's boot evidence are BEHIND the mods (live boots,
evidence: testenv/evidence/phase59-round3-merged-boot.txt):

1. **DFU 6->8 DataResult member drift** (config lane, 2adc671): new memberretargets
   kind **`static`** — INVOKEVIRTUAL -> INVOKESTATIC on a per-mod shim whose first
   parameter IS the receiver (stack shape identical, frames untouched; owner through
   resolveShimTarget). `DataResultCompat` (semantics PORTED FROM THE DFU 6.0.8
   BYTECODE: `addMessage` = "return the partial result instead of throwing when one
   exists"; onError.accept fires on BOTH flag values before any throw; get() =
   Either left(value)/right(Error)). 20 getOrThrow sites + 2 get() sites land on the
   shim; the third shape (`PartialResult.message`, class GONE on DFU 8) rides a
   blanket **PartialResult -> DataResult$Error typerename** (member-identical
   surface: message/map/flatMap/equals/hashCode/toString) — which ALSO fixed the
   DESCRIPTOR-level drift: moonlight's ConfigHelper lambda carries PartialResult in
   its erased method descriptor + local-variable table, and linking resolves
   descriptors (CNFE at boot, surfaced transitively from supp's construction).
2. **AbstractCriterionTriggerInstance fold** (loot lane, salvaged from the
   round-3 agent's partial work, e22883a): pure shim implementing the real 21.1
   `SimpleCriterionTrigger$SimpleInstance` — translated subclasses are transitively
   IS-A for the generic-bound link check. moonlight's GrindItemTrigger now LINKS
   and its clinit advanced to registration.
3. **CriteriaTriggers.register removed** (live-boot wall): 21.1 registers custom
   triggers via BuiltInRegistries.TRIGGER_TYPES + explicit ResourceKey; the 21.1
   CriterionTrigger interface exposes NO id accessor -> inert pass-through
   `CriteriaTriggersCompat` (returns the trigger; single site ml MoonlightRegistry
   clinit m_10595_).
4. **ResourceLocation.isValidResourceLocation removed** (config-correct boot wall,
   fired during the eager COMMON config load): ports 1:1 to
   `tryParse(s) != null`; single site (ml ResourceLocationConfigValue corrector).
5. **Vanilla class renames, systematic** (f72e169): 1.20.1 srg classlist minus
   classlist-1211 = 129 gone classes, intersected with both jars' constant pools,
   targets verified -> 8 typerename rules (EnchantingTableBlock, BlockSource ->
   core/dispenser, BlockPathTypes -> PathType, ChunkStatus -> chunk/status,
   CommandFunction -> commands/functions, LootTableReference -> NestedLootTable,
   LiteralContents -> PlainTextContents$LiteralContents, PotionUtils ->
   PotionContents). Removed-no-counterpart classes deliberately unmapped + listed
   in the rules file.

**Agent-caught relocator bug** (phase-5.9 boot): shim-class relocation rewrote
invokedynamic desc/bsm/Type-bsmArgs but NOT **Handle** bsmArgs — the first
lambda-bearing shim (DataResultCompat) boot-failed with CNFE at its own BASE name
(LambdaMetafactory's implMethod Handle). Fixed + pinned by a byte-level
injectShims regression check. Also: remapClass never remapped the InnerClasses
entry's SIMPLE NAME (reflection-level residue) — now derived from the renamed
internal name.

**Both mods converge on ONE wall: networking.** `ClassNotFoundException
net.neoforged.network.NetworkRegistry` (moonlight ModMessages clinit ->
ChannelHandlerImpl; supp constructs channels through moonlight's ChannelHandler
API only). Surface inventoried (evidence file): newSimpleChannel,
SimpleChannel.registerMessage/send/sendToServer, NetworkEvent$Context (5 members),
NetworkDirection (ordinal/values), PacketDistributor$PacketTarget. Next slice =
~5 inert facade shims (boot survival; network play NOT claimed), faithful
payload-registrar bridge after.

Gates: ctor-retarget 70 checks, shims 169 checks (30 shims), srg-resolver 4/4,
refmap 3/3, matrix 16/16.

## Phase 5.10 — networking facades + the removed-API ladder (2026-10-06, overnight)

~20 boot iterations, all walls measured-then-fixed (full ladder + runbook lessons:
testenv/evidence/phase510-removed-api-ladder-boot.txt). Highlights:

1. **NetworkRegistry fell** (the converged wall): 8 typerenames to 8 inert per-mod
   facade shims (SimpleChannel world + PacketDistributor family + NetworkEvent$
   Context). NetworkHooks deliberately unmapped (lazy resolution, runtime walls).
2. **Three shim-relocator bugs** (enum synthesis): array-form MethodInsn/FieldInsn
   owners, TypeInsn array descs, LDC class literals — plus the InnerClasses outer-
   name DOTS ClassFormatError. All pinned by the byte-level relocation scan.
3. **Removed-API ladder**: Vanishable, AbstractIngredient (21.1 Ingredient FINAL),
   RecordItem (extends the real Item), IIngredientSerializer, GravelBlock (extends
   the real FallingBlock), block_type_swap init() method-drop, isValidResource-
   Location→tryParse, Properties.copy→ofLegacyCopy.
4. **SUPER-SWAP mechanism** (rules/superswaps.txt): guarded superclass swap for
   subclasses of now-FINAL platform classes (Enchantment) — the swap carries the
   super plumbing (invokespecial oldSuper.<init> + member super-calls). Plus the
   INTERFACE-STRIP form for interfaces renamed onto 21.1 CLASSES (BlockSource→the
   dispense-context record: right for params, ICCE-fatal on implements lists).
5. **Registry lane**: memberretargets now covers FieldInsnNode (owner+desc+new-name);
   ForgeRegistries vanilla keys → BuiltInRegistries singular names; IForgeRegistry →
   vanilla Registry; RegisterEvent accessors. **The ordering lesson: ALL
   memberretarget keys are ORIGINAL-space — applyMemberRenames runs BEFORE
   remapClass in transform() — and memberretargets can never RENAME (name changes
   ride memberrenames.txt, applied first).**
6. **Event lane**: LivingTickEvent + EntityItemPickupEvent shims (register-able,
   firing documented dead — no 21.1 events); TickEvent$PlayerTickEvent plain
   typerename to PlayerTickEvent$Post (fires, post-phase).

State at close: moonlight constructs THROUGH registry dispatch; supplementaries
reaches registry dispatch and dies on the Shapes$DoubleLineConsumer lambda SAM
drift (next slice: indy bsmArgs SAM rewrite) + a following VerifyError. Gates:
ctor-retarget 186 checks, shims 232 (52 shims), matrix 16/16.

## Phase 5.11 — indy SAM remap + the blocks/armor/effects ladder (2026-10-06, early morning)

~20 boot iterations (full ladder: testenv/evidence/phase511-lambda-sam-and-blocks-boot.txt):

1. **indy SAM-name remap** (2fddd24): the indy NAME is the implemented functional
   method — vanilla-interface lambdas carry 1.20.1 SRG ids there (Shapes$
   DoubleLineConsumer.m_83161_ → AbstractMethodError at dispatch). mapSrg on id.name;
   java-interface lambdas unaffected. 12 remaining sites are CLIENT-only SAMs.
2. **Block-ctor drift family**: DoorBlock/TrapDoorBlock/FenceGateBlock param-order
   swaps (compat shims, pure forwards). CENSUS LESSON: superswap only when the
   SUPER-CALL desc differs — skulls/stairs/liquids/sound-types carry 21.1-shaped
   super-calls (moonlight resolves its own suppliers) — 3 swaps removed.
3. **ArmorMaterial final-record**: interface-SWAP superswap form + ArmorItemCompat
   (extends plain Item) + the desc-widen lesson (the verifier checks ARGS against
   the DESC — an owner swap alone is insufficient).
4. **retargetHandle**: method-ref lambdas' implMethod Handles get the same
   memberretarget/memberrename treatment (Registry::register NSME'd inside the indy
   bootstrap — invisible to instruction scans).
5. **RegistryObject.get() → direct-lookup shim**: the DeferredHolder typerename made
   every get() throw "unbound value" for direct-registered entries (holders bind only
   via the event helper); EntryWrapperCompat queries via the holder's key. Also:
   RegistryCompat.register real + duplicate-tolerant (1.20.1-legal vanilla-id
   overrides throw on 21.1; existing entry kept).
6. **The effects holder cascade**: 7 MobEffects field retargets (Holder descs),
   MobEffectInstance ctor/getEffect desc rewrites, LivingEntity/Player
   hasEffect/getEffect/removeEffect desc rewrites — PLUS the desc-rewrite "*" owner
   fallback (the receiver's static type is the owner: mixin handlers and subclasses
   call this.hasEffect with their own class as owner).
7. IContainerFactory exact typerename (the bare-fallback segment bug, first live hit),
   FoodProperties Builder renames, afterItemReg drop (duck-typing surface unclaimed).

State at close: moonlight constructs and runs its full registry dispatch; supp
constructs through the block/item registration and dies at the LAST measured wall —
`registry value not present: mob_effect/supplementaries:flammable` (moonlight's lazy
DeferredRegister creation vs 21.1 registry event order — next slice: eager
DeferredRegister creation or supplier-side bridging). Gates: all 4 unit scripts
green, matrix 16/16, 59 shims.

## Phase 5.12 — the mob_effect registry wall falls; the ladder reaches CommonSetup (2026-10-06, afternoon)

The 5.11 handoff's wall is GONE - and its diagnosis was WRONG. Ground truth (raw
bytecode): moonlight's mob_effect DeferredRegister IS created eagerly at
construction (RegHelper.register -> RegHelperImpl.computeIfAbsent ->
DeferredRegister.create + register(bus)); the real failure was INSIDE the
mob_effect RegisterEvent dispatch - supp's OverencumberedEffect ctor called the
removed MobEffect.addAttributeModifier(Attribute, String, double, Operation)
(Attributes field desc drift + the removed ctor), the addEntries loop aborted,
and supplementaries:flammable never registered. The 5.11-documented
"registry value not present: mob_effect/supplementaries:flammable" was the
downstream symptom (the item-time wrapper lookup finding what the aborted event
never wrote). THE CHOICE: neither eager DeferredRegister creation nor
supplier-side bridging - no registry-lane change at all (moonlight's constructs
untouched); the minimal bridge was the effects/attributes desc-drift family.

What fell (in boot order, each measured-then-fixed; full ladder +
runbook lessons: testenv/evidence/phase512-mob-effect-wall-and-commonsetup.txt):

1. Effects/attributes lane: MobEffect.addAttributeModifier "*" retarget to the
   MobEffectCompat shim (uuid -> "modbridge/<uuid>" ResourceLocation key);
   Attributes field desc retargets (Holder); the "*" owner fallback extended to
   FIELD retargets (javac emits the subclass as owner for inherited statics).
2. VerifyError family: NEW PASS repairHolderUnwraps (rules/methodunwraps.txt) -
   erased Holder.value() + bare CHECKCAST at consumer-local sites after the 5.11
   getEffect desc rewrite (receiver calls, bare-typed landing args, locals,
   compare-jumps); the field-producer form for retargeted statics stored into
   locals the original frames typed bare.
3. Bare-wrap pass: NEW PASS repairBareWraps (rules/methodwraps.txt) - the
   INVERSE: wrapper-get values (checkcast bare) landing in Holder-typed argument
   slots get Registry.wrapAsHolder inserted (ICCE "FlammableEffect does not
   implement Holder" at the fluid event). Bounded forward stack-scan; aborts on
   branches/DUPs/unknown shapes (never guesses).
4. Cross-mod shim-type divergence: the per-mod RecordItem shim broke moonlight's
   PlatHelper.newMusicDisc signature (each jar resolved its OWN shim type in the
   descriptor). NEW MECHANISM: SHARED SHIMS (rules/shims-shared.txt) - injected
   ONLY into the owner jar at the fixed com/modbridge/shim/shared/ package;
   every jar's rules resolve the same name (JPMS-safe: the owner module exports
   the package, consumer mods already read it).
5. Setup-lane ladder: Operation enum constant renames, Item attack-UUID shims +
   AttributeModifier ctor-retarget (dead-data clinit), BlockReachCompat
   (BLOCK_REACH -> BLOCK_INTERACTION_RANGE), Potions.* -> Holder + the EMPTY
   sentinel shim (the empty potion left the registry; Holder.direct sentinel),
   POTION registry desc demotion + DefaultedRegistry-interface owner swaps,
   PotionContentsCompat.setPotion (data components), moonlight food-mutation
   helpers method-dropped (Chicken/Horse/Parrot FOOD_ITEMS removed in 1.21),
   IForgeMenuType -> IMenuTypeExtension typerename, ModLoader state-machine
   retargets (FmlCompat shim; the config guard passes by the documented
   eager-config semantic).
6. PRE-EXISTING frame gap, first hit: the ResourceLocation ctor rewrite never
   rebuilt frames inside the ctor-arg region ("bad offset for Uninitialized",
   moonlight makeHolderSet). fixArgRegionFrames now runs for the RL rewrite -
   factory-style rewrites DROP both uninit entries at mid-arg merges (replacing
   instead-of-dropping leaves a phantom slot = VerifyError), and frame stack
   slots carry INTERNAL names (the L-form is a ClassFormatError).

FINAL STATE: both mods construct, ALL registry events dispatch, configs load
through FML's own pipeline, and CommonSetup runs until the next wall:

  NoSuchMethodError: 'net.minecraft.nbt.CompoundTag
  ItemStack.getOrCreateTag()' at supp BuntingItem.setColor:83 <- 
  ModCreativeTabs.registerItemsToTabs (Common setup deferred queue)

THE NEXT WALL: the 1.20.5 NBT item-tag removal - the ItemStack tag family
(getOrCreateTag/getTag, 35 census sites across ~30 supp/ml classes: item
color/storage NBT). A semantic data-components migration lane (a component type
per tag key), not a boot wall. Done was NOT reached; everything up to and
including most of CommonSetup was.

Gates: ctor-retarget 3/3 (253 checks), shims 3/3 (239 checks; 68 shims + 1
shared), refmap 3/3, srg-resolver 4/4, matrix 16/16. Counters (final rules):
supp = 1089 classes / ctor retargets=2 / method drops=1 / interface repairs=22 /
holder unwraps=22 / bare wraps=6 / member renames=207; ml = 489 classes / ctor
retargets=1 / method drops=8 / interface repairs=40 / holder unwraps=5.

Caveats (honest): the stringResolverCodec and defaultDurability SRG entries
carry stale 1.20.1 mojmap names (the mojmemberrenames mechanism now covers
call-site renames for them); the record-shim cross-mod readability relies on FML
auto-exports + cross-mod reads (proven by the boot); cauldron EMPTY/WATER puts
land on the real 21.1 maps but the 1.20.1 key/value erasure semantics are
unclaimed; the PotionCompat.EMPTY sentinel only guarantees never-equal-to-real.
RUNBOOK: memberretargets keys are owner|DESC|name (two rules landed name-first
and silently never fired - pinned by realPhase512RulesLoad); build-shims rm -rf's
its output dir, so a failed javac leaves rules/shims without shims.txt and every
following agent build then fails ALL translations ("@SHIM@ target ... is not in
the shim manifest") - verify the manifest after every shim build.

## Phase 5.13 — the NBT item-tag removal wall falls; the ladder crosses the pack lane into runtime resource generation (2026-10-07, FINAL campaign day)

The 5.12 honest-stop wall is GONE. The 1.20.5 NBT item-tag removal —
ItemStack.getTag/getOrCreateTag/getTagElement/.../save/of — fell to a TAG-BACKED
COMPATIBILITY VIEW: the SHARED shim ItemStackTagCompat
(rules/shims-shared.txt, owner moonlight at
com/modbridge/shim/shared/), every view method backed by
DataComponents.CUSTOM_DATA (vanilla 21.1's own arbitrary-payload component, which
syncs with the stack). 9 static-kind memberretargets (keys POST-mapSrg, descs the
1.20.1 shapes; the ItemStack receiver rides in as argument 1 — operand stack
identical, frames untouched). Census re-measured instruction-level over the raw
jars: **160 call sites / 74 classes (supp 145/67, ml 15/7), 0 declared
overrides, every site owner=ItemStack** — the family is fully rule-covered.
Runtime proof: a 22-check own-JVM test against the REAL 21.1 component store
(live CUSTOM_DATA round-trips; save re-encodes the empty stack in the 1.20.1 air
shape because 21.1 save THROWS on empty; 21.1 save RETURNS a new tag — documented
drift). The semantic remainder is in the shim's javadoc: vanilla keys that moved
to DEDICATED components ("display", "BlockEntityTag" copy-in, "Fireworks", banner
"Patterns", map "map"/"generation") don't surface from the view through vanilla
systems; mod-INTERNAL tag round-trips behave exactly as on 1.20.1.

What fell behind it (each measured-then-fixed; full ladder + runbook:
testenv/evidence/phase513-nbt-removal.txt):

1. Fireworks: FireworkRocketItem$Shape (CNFE, supp ModSetup creeper-shape
   registration) -> pure typerename to component/FireworkExplosion$Shape (same
   constant names; vanilla SHAPE_BY_ITEM survives). 5 classes remap consistently
   including moonlight's cross-mod registerFireworkRecipe signature.
2. Pack lane (CNFE Pack$Info): 21.1 split Pack.Info + 9-arg Pack.create into
   PackLocationInfo/Pack.Metadata/PackSelectionConfig. PackCompat shim + 2
   ctorretargets (Info -> Metadata record; 2-arg PackMetadataSection ->
   +Optional) + 1 memberretarget (create) + 2 accessor renames. The pack builds
   and the repository accepts it.
3. Indy SAM shape: NEW MECHANISM rules/indysams.txt — 1.20.1's
   ResourcesSupplier.open(String) is gone (openPrimary/openFull(PackLocationInfo,
   Pack$Metadata)); the lambda implemented neither (AbstractMethodError at the
   repository reload). The rule rewrites the indy name + both bsm MethodTypes and
   DERIVES the impl desc (captured prefix kept, old-desc-must-end-with-old-SAM
   validated); fail-fast if the impl body loads a SAM-parameter slot; maxLocals
   recomputed. Two live sites.
4. getMetadataSection override body (CCE): always-true serializer instanceof +
   the metadata SUPPLIER returned unwrapped -> pinned single-site pass
   (fixMetadataSectionOverride): reference compare against
   PackMetadataSection.TYPE (stack-depth-neutral swap, maxStack + merge-frame
   retypes) + Supplier.get unwrap. 21.1 vanilla queries pack AND filter sections.
5. NEW-REWRITE PASS FIX (pre-existing since phase 4, second live hit): the
   NEW+DUP forward scans aborted at ANY TypeInsnNode — ResType's ANEWARRAY in the
   arg region left a `new ResourceLocation(ns, path)` (PRIVATE ctor on 21.1,
   IllegalAccessError), and a checkcast had hidden the Pack$Info ctorretarget.
   Both scans now stop only at nested NEW; regression tests added.

LADDER RESULT: both containers construct, ALL registry events dispatch, configs
load, CommonSetup completes, the pack lane runs end to end, and moonlight's
runtime resource generation SUCCEEDS ("Finished runtime resources generation for
1 packs in a total of 10 ms"). The ladder stops in the datapack parse that
follows, at the next wall, precisely scoped:

  AbstractMethodError: moonlight.core.misc.CaveFilter$Type does not implement
  'abstract MapCodec codec()' of PlacementModifierType (21.1 changed the RETURN
  TYPE Codec -> MapCodec; the override no longer overrides; fires inside vanilla
  KeyDispatchCodec.decode during worldgen JSON parsing)

Census: 3 moonlight classes reference PlacementModifierType, 0 supp. The fix is a
semantic codec conversion (MapCodec-building body, or an override-desc-rewrite
mechanism that does not exist yet) — phase 5.14 head-of-list. Done was NOT
reached (rcon not reached — honest partial); everything up to and including the
resource-pack lane was, three walls past the 5.12 close.

Gates: ctor-retarget 3/3 (278 checks), shims 3/3 (265 + 22; 70 shims + 2
shared), refmap 3/3, srg-resolver 4/4, linkie clean, matrix 16/16. Counters
(final rules): supp = 1089 classes, ctor retargets=2, method drops=1, interface
repairs=22, holder unwraps=22, bare wraps=6, member renames=352, super swaps=10;
ml = 489 classes, ctor retargets=4, method drops=8, interface repairs=40, holder
unwraps=5, member renames=81.

## Phase 5.14 — the CaveFilter codec wall falls; the ladder crosses worldgen parse,
## loot, conditions and trades (2026-10-07, FINAL campaign day, honest partial)

The 5.13 head-of-list wall is GONE — and it was the head of a FAMILY. javap diff
over the two vanilla jars: exactly 16 worldgen "type" interfaces changed their
abstract `codec()` return kind between 1.20.1 and 21.1 (Codec m_xxx_() ->
MapCodec codec(); ParticleType, Float/IntProviderType, BiomeSource,
ChunkGenerator, PositionSourceType, BlockPredicateType, HeightProviderType,
PlacementModifierType, StructureType, StructurePlacementType,
StructurePoolElementType, PosRuleTestType, RuleTestType, StructureProcessorType,
RuleBlockEntityModifierType). Mod impls measured: 2 (ml CaveFilter$Type, supp
WaySignStructure$Type — both "getstatic CODEC; areturn"), 0 call sites.

What fell (each measured-then-fixed; full ladder: testenv/evidence/
phase514-cavefilter-codec.txt):

1. THE OVERRIDE-RETURN REWRITE MECHANISM (the slice's deliverable; rules/
   overridereturns.txt, all 16 family entries): the override's declared desc
   follows the 21.1 shape and every ARETURN wraps the produced Codec in
   MapCodec.assumeMapUnsafe — DFU 8's own Codec->MapCodec bridge (its decode
   rebuilds the whole map from the MapLike: same-level fields, exactly the 1.20.1
   plain-Codec dispatch; its encode merges same-level entries). Stack-neutral;
   the generic signature follows the descriptor; fail-fast on branch frames and
   statics; transitive implements through the jar-local hierarchy only. SEMANTIC
   PROOF: a gate test defines rewritten bytes and drives the REAL DFU 8 through
   them (codec() returns a MapCodec; decode reads same-level fields with the
   "type" key present; encode merges without a value key).
2. from_mod conditioned-data strip (new agent wiring + Rewriter resource pass):
   vanilla 21.1 loads moonlight's CUSTOM datapack registries (soft_fluids,
   map_markers) through the strict RegistryDataLoader — 1.20.1 moonlight loaded
   them through its own from_mod-aware lenient loader; supp's 80+ conditioned
   compat files (~30 absent mods) were FATAL ("Failed to load datapacks"). The
   agent collects the instance's present mod ids (first modId per jar —
   [[dependencies]] modId lines poisoned the first attempt) and the rewriter
   drops data .json files with (1) a top-level from_mod naming an absent mod or
   (2) a body referencing any absent-mod id inside data/<ns>/moonlight/** (supp's
   own teas carry no from_mod but their ids name farmersrespite items).
3. Loot-table field drift (NEW PASS rules/fieldunwraps.txt): ALL 113
   BuiltInLootTables fields moved ResourceLocation -> ResourceKey<LootTable>
   (names survive). 12 measured sites in supp. The getstatic's constant-pool desc
   is rewritten to ResourceKey (the JVM verifier types the pushed value from the
   CP desc — the unwrap alone was a live VerifyError, boot 5) and
   ResourceKey.location() is inserted immediately after (stack- and frame-
   neutral). Link-time proof: the gate defines the rewritten class against the
   real 21.1 field.
4. LootTableReference -> NestedLootTable + lootTableReference(ResourceLocation)
   -> lootTableReference(ResourceKey<LootTable>): shared shim LootCompat (the
   resource key is reconstructed over Registries.LOOT_TABLE); one measured call
   site (ml RegHelperImpl$1).
5. CraftingHelper shim completed (phase 5.7's shim was two members short of the
   family): processConditions(JsonObject,String,IContext) (moonlight's resource-
   conditions mixin injected into vanilla's SimplePreparableReloadListener calls
   it at datapack reload) and the getCondition fallback to the inert serializer
   registry — translated condition serializers (supp "supplementaries:flag")
   read through register() produce REAL 21.1 IConditions (the forge interface was
   typerenamed onto neoforge's, same test(IContext)) so flags evaluate for real.
6. MapRegistry.dispatch CCE (NEW PASS fixMapRegistryDispatch, pinned): DFU 8
   KEPT the erased Codec.dispatch descriptor (the MapCodec bound lives only in
   generics) but KeyDispatchCodec's decode casts the produced value codec to
   MapCodec — moonlight's registered RecordCodecBuilder products are
   MapCodecCodec wrappers (CCE at the villager-trades reload). Fix: the value
   lambda returns the REAL MapCodec via assumeMapUnsafe (DFU unwraps its own
   wrapper), Codec.dispatch -> dispatchMap, and the result wraps back through
   MapCodec.codec() so dispatch's declared Codec return and every consumer stay
   byte-identical to 1.20.1. Two live-boot corrections are pinned in the gate:
   the indy's raw samMethodType is Function.apply(Object)Object (only the
   instantiated type carries the Codec bound), and the call keeps its
   INVOKESPECIAL(itf) shape / the wrap is INVOKEVIRTUAL (MapCodec is a class —
   an INVOKEINTERFACE there is an ICCE, live boot 10).
7. Accessor-rename scoping fix (phase-4 regression, second live hit): the
   derived-name accessor METHOD rename now applies ONLY to accessors WITHOUT an
   explicit @Accessor("name") value — explicit-value accessors resolve their
   target by the value at runtime, and renaming the method orphaned every
   consumer call site (moonlight DispenserBlockAccessor.getDispenserRegistry
   NSME at the dispenser lane; live boots 11-12).

LADDER RESULT: both containers construct; ALL registry events dispatch; CommonSetup
completes; the pack lane runs; runtime resource generation succeeds; the worldgen
datapack parse CROSSES the placement-modifier dispatch (0 AbstractMethodError
anywhere), structure types, and the moonlight custom registries; loot injects,
resource conditions and villager trades evaluate. The stop, at the reload event
that follows, is precisely scoped:

  ClassNotFoundException: net.minecraft.core.dispenser.AbstractProjectileDispenseBehavior
  (supp BombsBehavior extends it; 21.1 replaced the abstract extend-and-override
  class with the data-driven ProjectileDispenseBehavior(ProjectileItem,
  DispenseConfig) — the template-method pattern has no mechanical successor; the
  bridge is a semantic shim reimplementing the 1.20.1 template over the 21.1
  surface, plus supp's AbstractProjectileBehaviorAccessor retarget)

Non-fatal, documented: moonlight's dynamic-asset recipe generation hits the
1.20.5 data-recipe API drift (NoClassDefFoundError net/minecraft/data/recipes/
FinishedRecipe, caught by moonlight — the pack still builds; "Finished runtime
resources generation").

Done was NOT reached (rcon not reached — honest partial); the ladder stops six
walls past the 5.13 close.

Gates: ctor-retarget 3/3 (357 checks: +52 override-return incl. the DFU-8
semantic proof, +11 from_mod strip, +8 field-unwraps incl. link-time proof, +7
mapRegistryDispatch), shims 3/3 (277 + 22; 71 classes + 3 shared: LootCompat
added, CraftingHelper extended), refmap 3/3, srg-resolver 4/4, matrix 16/16.
RUNBOOK additions: (1) translate.sh reuses build/rewriter when present — rm -rf
it after editing Rewriter.java or the translation silently uses stale classes
(bit twice this slice); (2) the JVM verifier types a getstatic's pushed value
from the CONSTANT-POOL descriptor, not the resolved field — descriptor-drifted
field reads need BOTH the desc rewrite and the unwrap call; (3) an accessor
method rename must be scoped to derived-name accessors (explicit-value accessors
resolve by value; renaming their method orphans consumer call sites); (4) when
reproducing 1.20.1 leniency that 21.1 made strict, prefer reproducing the
1.20.1 SEMANTIC (skip absent-mod content) at translation time over patching the
21.1 loader.

## Phase 5.15 — the dispenser template wall falls; the reload event crosses the
## bombs-behavior lane (2026-10-07, FINAL campaign day, honest partial)

The 5.14 head-of-list wall is GONE. javap over the two vanilla jars:

  1.20.1 net/minecraft/core/dispenser/AbstractProjectileDispenseBehavior
    (the abstract extend-and-override template; ABSENT on 21.1):
    m_7498_ execute (public template), m_6895_ getProjectile (abstract hook),
    m_7104_ getPower (1.1F), m_7101_ getUncertainty (6.0F), m_6823_ playSound
    (levelEvent 1002); the body uses the STATIC DispenserBlock.getDispensePosition
    (NOT source.center()) and shoots with the 1.20.1 +0.1F Y-bias.
  21.1 net/minecraft/core/dispenser/ProjectileDispenseBehavior (SAME package in
    1.21.1 — the world/level/dispenser move is later; 5.14's census misnamed it):
    DATA-DRIVEN — ctor(Item) demands the ITEM implement ProjectileItem and
    carries a DispenseConfig record; no hooks, no custom-entity escape hatch.
    A behavior overriding getProjectile to build a mod-owned projectile has no
    mechanical successor.

Census (constant-pool, both raw jars): exactly three supplementaries classes,
moonlight ZERO — BombsBehavior (overrides getProjectile/getUncertainty/
getPower), DispenserBehaviorsManager$2 (getProjectile only: RopeArrowEntity),
mixins/AbstractProjectileBehaviorAccessor (@Mixin class literal + 4
EXPLICIT-value @Invoker proxies; sole consumer the 5.11 minecart path).

What fell (each measured-then-fixed; full ladder + runbook:
testenv/evidence/phase515-dispenser.txt):

1. THE SEMANTIC BASE-CLASS SHIM (the 5.14-banked design;
   shims/src/net/minecraft/core/dispenser/AbstractProjectileDispenseBehavior,
   the AbstractCriterionTriggerInstance vanilla-package-carrier pattern): it
   extends the REAL 21.1 DefaultDispenseItemBehavior (translated instances stay
   true DispenseItemBehaviors for vanilla's registry dispatch) and reimplements
   the 1.20.1 template BYTECODE-EXACT over the 21.1 surface — execute (public,
   widening the 21.1 protected hook), DispenserBlock.getDispensePosition static
   (still present — used, exactly as 1.20.1 did), FACING getValue,
   Projectile.shoot(DDDFF) with the +0.1F Y-bias, addFreshEntity, shrink(1),
   playSound levelEvent-1002 — carrying the 1.20.1 hook surface under the
   21.1 mojmap member names, which are EXACTLY the names the global srg table
   already renames the subclass overrides to: the descs do not drift
   (identical internal types both sides), so the subclasses remain true
   overriders with ZERO member rules. Documented non-bridge: the 21.1
   data-driven positionFunction/DispenseConfig per-item semantics — the shim
   reproduces the 1.20.1 SHARED template the behaviors compiled against.
2. THE WIRING is one typerenames entry (AbstractProjectileDispenseBehavior ->
   @SHIM@/AbstractProjectileDispenseBehavior), which retargets in one pass the
   superclass refs + super ctor invokespecials, the @Mixin annotation's class
   literal, and every frame/local desc; the rewriter relocates the carrier
   per-mod to com/modbridge/shim/supplementaries/. The mixin accessor's 4
   explicit @Invoker values resolve against the shim's members BY VALUE at
   apply (5.14's explicit-value lesson holds); its refmap entries rewrite
   (srg value -> mojmap name, dotted target -> the shim path); mixinDriftReasons
   stays EMPTY (shim targets are non-platform, the member inventory is not
   consulted).

Offline proofs (gates): ShimFacadeTest.dispenserShimExactness (+30 checks) —
structural exactness, a LIVE verifier pass of both translated subclass shapes
(define/link/dispatch; inherited defaults 6.0F/1.1F; overridden hooks dispatch
virtually), the 4 @Invoker values resolving by name, and a bytecode walk of
the compiled carrier proving every execute/playSound call target is the exact
21.1 member; CtorRetargetTest.dispenserShimRewrite (+16 checks) — the REAL
shipped rules driving a BombsBehavior-shaped srg-space class and an
accessor-shaped mixin through the full transform(), with the subclass itself
never renamed and no srg ids left.

LADDER RESULT: the reload-event CNFE is gone (0 mentions in the whole log).
registerBehaviors constructs BOTH translated subclasses through the shim
(verified in the translated jar: superName = com/modbridge/shim/
supplementaries/AbstractProjectileDispenseBehavior) and execution reaches
BucketHelper.isFishBucket — two frames deeper than the 5.14 stop, same event.
The stop is precisely scoped (5.16 head):

  IllegalClassLoadError: net.mehvahdjukaar.supplementaries.mixins.forge.
  MobBucketItemAccessor is in a defined mixin package ... and cannot be
  referenced directly
    (supp SuppPlatformStuffImpl.getFishType:51 — a THREE-instruction site,
    checkcast + invokeinterface against the accessor — via BucketHelper.
    isFishBucket:134 <- DispenserBehaviorsManager.registerBehaviors:162)

The accessor's @Invoker("getFishType") targets 1.20.1 MobBucketItem.getFishType,
REMOVED on 21.1 (the entity type lives in the private final field `type`).
This is the head of a FAMILY: 9 direct mod-code references to mixin-package
classes across the two jars (supp: LivingEntityAccessor, AgeableListAccessor,
AbstractProjectileBehaviorAccessor, FireBlockAccessor, MobBucketItemAccessor;
ml: DispenserBlockEntityAccessor, DispenserBlockAccessor, BrainAccessor,
ButtonAccessor). EMPIRICAL ASYMMETRY to resolve first: ml's DispenserBlock-
Accessor direct reference loaded FINE in the 5.14 boots (NSME — member
resolution, not the classload guard) and ml's DispenserHelper$
AdditionalDispenserBehavior (two direct accessor refs) sat on this boot's
executed path ABOVE the stop with no IllegalClassLoadError — while supp's
MobBucketItemAccessor direct load is guard-fatal; working hypothesis: an
accessor whose APPLY succeeded releases its class for direct loads, one whose
target member is gone stays pending/errored and loads fatal — which would make
"bridge every accessor's target member so every accessor applies" (field reads
via an accesstransformer.cfg line or a member-level bridge) the systematic fix,
with the pinned-single-site pattern (AT line `public net.minecraft.world.item.
MobBucketItem type` + getfield rewrite) as the fallback.

Non-fatal, unchanged from 5.14: moonlight's dynamic-asset FinishedRecipe NCDFE
(caught; pack still builds) — documented, not fixed (not cheap; the pack lane
works).

Done was NOT reached (rcon not reached — honest partial); the ladder stops
one wall past the 5.14 close.

Gates: ctor-retarget 3/3 (373 checks: +16 dispenser shim rewrite), shims 5/5
(307 + 22; 72 classes + 3 shared: the dispenser carrier added), refmap 3/3,
srg-resolver 4/4, matrix 16/16 with the final agent. Counters (final rules):
supp = 1090 classes, ctor retargets=2, method drops=1, interface repairs=22,
holder unwraps=22, bare wraps=6, member renames=352, super swaps=10; ml = 489
classes, ctor retargets=4, method drops=8, interface repairs=40, holder
unwraps=5, member renames=82.

## Phase 5.16 — the mixin-accessor direct-load wall falls; the reload event crosses
## the fish-bucket lane (2026-10-07, FINAL campaign day, honest partial)

The 5.15 head-of-list wall is GONE, and the family hypothesis behind it is
DECODED, not just worked around. javap over the LIVE 21.1 mixin jar
(sponge-mixin 0.15.2+mixin.0.8.7), MixinProcessor.applyMixins(MixinEnvironment,
String, ClassNode): the subsystem keeps a PACKAGE GUARD — any class whose name
falls inside a REGISTERED config's mixin package (longest packageMatch wins)
throws IllegalClassLoadError on direct load, and the ONLY passthrough in the
whole method is a superclass check against InjectionPoint /
ITargetSelectorDynamic. There is NO already-applied release. So 5.15's working
hypothesis ("an accessor whose APPLY succeeded releases its class for direct
loads; bridge every accessor's target member so every accessor applies") is
DEAD as the systematic fix — application state never enters the guard — and
the 5.14 asymmetry (ml's DispenserBlockAccessor "loaded fine, NSME") was luck
of LAZY RESOLUTION: classload of the referring class never resolves the
accessor constant-pool refs until the checkcast/invokeinterface EXECUTES. The
5.15 "defined mixin package" variant (#3) fired because the refmap lane had
already neutralized MobBucketItemAccessor out of supplementaries.mixins.json
(its 1.20.1 target getFishType does not exist on 21.1): the guard's variant #3
is exactly "in the package, not in the list".

The census (grep + javap over both raw jars) puts the family at NINE direct
references, every one pointing INTO a registered mixin package and therefore a
latent guard trip the moment its site executes: supp
forge/SuppPlatformStuffImpl (MobBucketItemAccessor = the 5.15 stop; SAME CLASS
also references FireBlockAccessor), supp LunchBoxItem + CannonBallEntity
(LivingEntityAccessor), SlimedLayer (AgeableListAccessor),
DispenserMinecartEntity (AbstractProjectileBehaviorAccessor — target resolves
against the 5.15 shim carrier); ml DispenserHelper$AdditionalDispenserBehavior
(DispenserBlockEntityAccessor + DispenserBlockAccessor),
VillagerBrainEventInternal (BrainAccessor), CustomConfigScreen (ButtonAccessor,
client-only).

What fell (each measured-then-fixed; full table + runbook + the guard bytecode
walk: testenv/evidence/phase516-mixin-accessor.txt):

1. THE ACCESSOR CALL-SITE BRIDGE (the 5.15-banked fallback, now the landed
   mechanism): one memberretargets line — owner
   net/mehvahdjukaar/supplementaries/mixins/forge/MobBucketItemAccessor, name
   invokeGetFishType (POST-mapSrg; mixin-package classes keep their names),
   desc ()Lnet/minecraft/world/entity/EntityType; — retargeted |static to the
   per-mod shim carrier MobBucketItemBridge, whose first parameter IS the
   receiver (the proven DataResultCompat shape; 21.1 MobBucketItem carries the
   fish type as `private final EntityType<?> type`, javap'd the runtime jar —
   erases to EXACTLY the accessor method's return desc, so the swap is
   stack-identical). Access inside the bridge is the reflective setAccessible
   read (the RegistryCompat idiom).
2. THE CHECKCAST DROP (new rewriter behavior, one scoped branch): the |static
   retarget now removes an ADJACENT preceding `checkcast` whose desc is the
   rule's key owner — without it the cast would still classload the accessor
   and the guard would fire anyway. Adjacent-only means no StackMapTable frame
   can fall between; checkcast is 1->1, so the drop is stack- and frame-neutral.
   Translate summary prints "mixin phase-5.16: accessor checkcasts dropped=N".
3. THE SHIM-NAME LESSON (boot 1 of this phase, live NSME): the |static
   retarget keeps the call site's METHOD NAME — owner+desc swap only. The
   bridge method must be named invokeGetFishType (the accessor's own name),
   not getFishType; documented in the shim javadoc for every future family
   member.

Offline proofs: gates green with the final agent — ctor-retarget 3/3 (375
checks: 5.15's 373 + the 2 relocation auto-checks for the new carrier), shims
5/5 (73 manifest entries: 72 + MobBucketItemBridge), refmap 3/3, srg-resolver
4/4. The translated installed jar shows the site EXACTLY: aload_0 /
invokestatic MobBucketItemBridge.invokeGetFishType / areturn — the checkcast
is gone, the accessor class can never be referenced at the site.

LADDER RESULT: "cannot be referenced directly" = 0 mentions (5.15: 4). The
reload event CROSSED the 5.15 stop (BucketHelper.isFishBucket:134 <-
DispenserBehaviorsManager.registerBehaviors:162 — the getFishType site is
THREE instructions that can no longer trip) and died three lines deeper, same
event, at the 5.17 head:

  NoSuchMethodError: 'net.minecraft.world.item.alchemy.Potion
  net.minecraft.world.item.alchemy.PotionContents.getPotion(
  net.minecraft.world.item.ItemStack)'
    (moonlight SoftFluidStack.fromItem:376 <- supp ModBlockProperties$Topping.
    fromFluidItem:200 <- Topping.fromItem:232 <-
    DispenserBehaviorsManager.registerBehaviors:165 <- DispenserHelper.reload:
    107 <- MoonlightForge.onTagUpdated:66) — the potion member drift family,
    then the known "Failed to load datapacks" fatal.

Honest partials, named: the accessor family remainder (FireBlockAccessor —
same supp class as the landed site — LivingEntityAccessor, the two ml
Dispenser accessors on the registerBehaviors-adjacent path) needs per-site
target pinning + bridge lines over the PROVEN mechanism; the matrix 16/16
re-run with the final agent (time-box went to the decisive boot experiment);
the potion wall above is the next ladder head.

Gates: as above (matrix not run this phase). Counters: supp translate shows
"accessor checkcasts dropped=2" (the pass visits the site class twice; the
site census is 1 — counter reported as-is).

## Phase 5.17 — the potion member-drift wall falls (bare-universe PotionUtils bridges)

Oct 8 2026. First action: the 5.16 matrix debt retired — run-matrix.sh with the
5.16 agent, **16/16** (matrix-run-517debt.txt).

Census (constant-pool scan + javap, raw jars): 8 PotionUtils members across 14
classes (ml 3, supp 11), every SRG id resolved through srgmembers.txt. The wall
site's raw shape is the whole design problem in three lines:
`Potion potion = PotionUtils.getPotion(stack); ... if (potion == Potions.WATER)`
`... else if (potion == Potions.EMPTY)` — the potion flows into identity compares,
a raw-typed tile field (`public Potion potion`, BambooSpikesBlockTile) and
setPotion. A Holder-shaped bridge return would VerifyError at those slots.

21.1 truth (javap server-1.21.1-srg.jar): PotionUtils is not a class; the 5.9
typerename parked its sites on PotionContents, a RECORD with a different surface
— no getPotion in ANY shape (item potion access = the POTION_CONTENTS component),
getColor only in Holder/Iterable forms, Potions.* constants are Holder<Potion>.

Design — the bare universe: bridges return/accept BARE registered Potion
instances; site descs stay the raw shapes (only the owner moves to the per-mod
shim). Identity coherence: "no potion" = PotionCompat.EMPTY.value() (the bare
sentinel inside the existing synthetic Holder — the exact instance the EMPTY
field sites compare against, because the EXISTING repairHolderUnwraps field pass
already unwraps Holder-kind constants to bare at ASTOREs, if_acmp compares and
bare-arg consumers — measured in the 5.16-translated jars before this phase).
Bare-vs-bare is the one universe where `getPotion(stack) == Potions.EMPTY` keeps
its 1.20.1 meaning. Verifier mystery closed in passing: `getstatic POISON;
invokestatic makeSpikeItem(Potion)` looked like a Holder-into-Potion verify error
that somehow booted since 5.12 — it doesn't because the field pass inserts
`value(); checkcast Potion;` before the bare-arg consumer (verified in the
translated jar). Method producers need nothing: the bridge desc IS the raw shape.

Rules (+5, memberretargets.txt, keyed on raw owner PotionUtils / mojmap names /
raw descs — the value side is owner|desc|static, NO method name: the |static
retarget keeps the site's name, which is why the bridges are named
getPotion/getColor/getMobEffects; a spurious name token = "bad memberretarget
line"): getPotion(ItemStack), getPotion(CompoundTag) (tag "Potion" -> registry
lookup -> sentinel; 1.20.1 Potion.byName semantics), getColor(Potion) (sentinel
-> -1, else PotionContents.getColor(wrapAsHolder)), getColor(ItemStack) (record
color, -1 when empty), getMobEffects(ItemStack) (record effects, mutable List).
setPotion untouched — its Object-normalizer + wrapAsHolder's direct-holder
fallback already accept the bare sentinel.

Deferred with census evidence (client lanes; need the legacy "CustomPotionEffects"
/ "Id"-int MobEffectInstance NBT decode — 1.20.1 save keys are PASCAL case):
getAllEffects(CompoundTag) (sole caller: ml PotionNBTHelper.addPotionTooltip) and
the addPotionTooltip(ItemStack,List,float) family (sole caller:
BambooSpikesTippedItem.appendHoverText).

Gates (final agent): shims 307+22, ctor-retarget 3/0 (375 checks), refmap 3/0,
srg 4/0, **matrix 16/16** (matrix-run-517final.txt). Offline translate clean;
the translated SoftFluidStack.fromItem calls
`invokestatic com/modbridge/shim/moonlight/PotionContentsCompat.getPotion` —
the NSME is structurally impossible at the site.

Boot 1: "cannot be referenced directly" 0; the potion wall GONE; the ladder
crossed the whole moonlight lane (Topping.fromFluidItem -> registerBehaviors ->
DispenserHelper.reload, three frames past the 5.16 stop) and died in supp's
slice of the same tags-updated event at the NEW head:
`NoSuchMethodError: 'boolean net.minecraft.world.item.Item.isEdible()'`
(ThrowableSlimeballBehavior.appliesToItem:35 <- InteractEventsHandler
.registerOverrides <- ModSetup.tagDependantSetup <- ServerEvents.onCommonTagUpdate)
then the known "Failed to load datapacks" fatal.

Staging note (b-data): the rewriter now NEUTRALIZES drifted mixins itself
(dropped entries incl. MobBucketItemAccessor — the config entry leaves, the
package guard releases the class); the two capability mixins are NOT
auto-neutralized (removed-Forge-API refs under surviving owner names) and were
excised by hand as in 5.12 (class + config entry) before staging into
source-1201.

5.18 head (census done): the FOOD DRIFT family — Item.isEdible() (m_41472_) and
Item.getFoodProperties() (m_41473_) are BOTH gone from 21.1 Item (food is the
FOOD/CONSUMABLE data components). 5 classes: supp ThrowableSlimeballBehavior +
LunchBoxItem, ml FoodProvider + FoodProvider$3 + BlockPlacerItem. Same bare
mechanism, one bridge each; 21.1 truth javap pending (Item.components() shape;
CONSUMABLE-vs-FOOD for "edible" semantics).

Evidence: testenv/evidence/phase517-potion-drift.txt.

## Phase 5.18 + 5.19 — the food wall and the dispense-source wall fall (and the frame-repair rewriter fix)

Oct 8 2026, one session over HEAD 8343d75 (5.17). Two families crossed; the
session's real find is a REWRITER mechanism fix.

**5.18 food drift** (wall: ThrowableSlimeballBehavior.appliesToItem:35 NSME
Item.isEdible): Item.isEdible()/getFoodProperties() are gone on 21.1 (food =
the DataComponents.FOOD component; FOOD only — the FOOD/CONSUMABLE split is
1.21.2+). New ItemFoodCompat carrier, 2 receiver-as-first-param |static rules,
DISPATCH-PRESERVING: the shim duck-types the receiver's class for a RENAMED
mod override first (cached public getMethod probe — 21.1 Item has neither
member, so a hit is always a mod override; measured: ml BlockPlacerItem
mimics food through exactly these) before falling back to the component map.
A plain static bridge would have silently bypassed mod food mimicry.

**5.19 dispense-source** (wall: FireBehaviorsManager.registerBehaviors:27 NCDFE
BlockSourceImpl, verification-time via EnderPearlBehavior): 21.1 turned
core.BlockSource into the final core.dispenser.BlockSource RECORD (4-arg ctor
level/pos/state/blockEntity; accessors level/pos/state). Three pieces: a
ctorretarget to a BlockSourceCompat factory (resolves state + block entity
eagerly — the lane uses the source transiently; null block entity when the
target isn't a dispenser, where 1.20.1 cast lazily), + 3 memberrenames
(getLevel->level, getPos->pos, getBlockState->state — 12/5/4 supp classes call
them on the source from vanilla-dispatched behaviors), + the frame fix below.
LATENT named: MovingBlockSource IMPLEMENTS core.BlockSource — a final record
cannot be implemented (lazy-loaded minecart lane, 5.20+ design work).

**THE FRAME-REPAIR REWRITER FIX**: boot 4 died NCDFE BlockSourceImpl with the
site verifiably bridged — javap -v showed the StackMapTable `append` frame in
fireInner still typing local 8 as `class net/minecraft/core/BlockSourceImpl`.
The rewriter reads frames verbatim (no COMPUTE_FRAMES): every type-changing
rewrite must mirror into the frames, or the verifier resolves the dead class
at verify time (frame types load even on never-executed paths). New
repairOwnerTypeFrames mechanism: ctorretarget sites sweep their method's
frames (owner -> rule return type); |static memberretargets sweep for key
owners ABSENT from the 21.1 classlist (owner -> shim first-param type — the
receiver's own class; present owners like Item keep frames). Counter:
"phase-5.19: removed-owner frame locals repaired=N" (supp: 5). This also
defuses the latent same-shaped hazard for the 5.16 accessor-family remainder
(accessor-typed frame locals would trip the mixin guard identically).

Boots: boot 4 = the frame find; boot 5 = BlockSourceImpl gone, the whole
tags-updated wave crossed, new head:
`NoSuchFieldError: BuiltInRegistries.ENCHANTMENT` (CurseLootFunction.rebuild:30
<- ServerEvents.onCommonTagUpdate:187 — same reload wave).

**5.20 head = the data-driven-enchantment family (a whale)**: 1.21 removed
enchantments from the static registry entirely (javap BuiltInRegistries: no
plain ENCHANTMENT field; only the six ENCHANTMENT_*_TYPE codec registries
remain; enchantments are datapack-driven). CurseLootFunction's burn-curse
enumeration needs a real replacement strategy, not a one-line bridge.

Gates (final agent): ctor 379 (+2 BlockSourceCompat relocation), shims
307+22 (manifest 75), refmap 3/0, srg 4/0, **matrix 16/16**
(matrix-run-519final.txt). Stale-cache gotcha (b-data): boot 3 reused a warm
cache and replayed the previous wall — purge modbridge-cache/1.21.1 between
agent rebuilds.

Evidence: testenv/evidence/phase518-519-food-dispense.txt.

## Phase 5.20 (first layer) — the data-driven-enchantment wall falls (2026-10-21, CampaignGoal)

The 5.20 head (`NoSuchFieldError BuiltInRegistries.ENCHANTMENT` in supp
`CurseLootFunction.rebuild` during the tag-update wave) fell with ONE new
rewriter mechanism + one shim + two rules. Full census, boot ladder and gates:
`testenv/evidence/phase520-enchantment-registry.txt`.

- **Census**: exactly 2 GETSTATIC sites / 1 class (the `f_256876_` field); the
  `Registries.ENCHANTMENT` ResourceKey refs are a different, still-existing field;
  `isCurse` (m_6589_) has exactly 1 call site; `wrapAsHolder` (6 sites) survives
  on 21.1 untouched. Upstream supplementaries 1.21.1 **deleted** the feature
  rather than port it — no strategy to copy.
- **NEW pass** `rewriteStaticFieldBridges` (rules/staticfieldbridges.txt,
  optional file): GETSTATIC of a removed static-registry field → INVOKESTATIC on
  a shim returning the LIVE instance. The erased return equals the field desc ⇒
  stack + frames byte-identical. Keys are FINAL-space (SRG renames first).
- **Shim** `EnchantmentRegistryCompat`: live registry
  (`ServerLifecycleHooks` → `registryOrThrow(Registries.ENCHANTMENT)`); pre-server
  (the tag wave runs in Main before any server instance — measured: the first
  boot died on the bridge's own fail-loud guard) it serves an EMPTY
  `MappedRegistry` + one-time loud warn = documented inert (upstream deleted it;
  the loot function's own `CURSES.isEmpty()` branch no-ops cleanly). Plus the
  `isCurse` bridge: Enchantment is a final record on 21.1 with no curse member —
  the semantic is the vanilla `minecraft:curse` tag through the same registry.
- **Link-time test catch**: the pass initially wrote the call desc as the bare
  field desc — illegal as a method descriptor; the in-JVM run proof caught it
  (ClassFormatError at define). The gate now pins `"()" + fieldDesc`.
- **Gate**: fieldunwraps LOOKUP key order had been silently flipped by the 5.20
  insertion (desc/name) — test-ctor-retarget's 381-check gate caught it.
- **Result**: tag wave crosses, "Failed to load datapacks" gone; matrix 16/16;
  unit gates 381 + 24 + shims/refmap/srg green; shim manifest 76.
- **NEW HEAD (2+ frames deeper)**: `DedicatedServer.initServer` →
  `ServerAboutToStartEvent` → moonlight
  `SoftFluidInternalImpl.registerExistingVanillaFluids` →
  **ForgeFlowingFluid$Flowing** (the removed forge-fluid family — next session's
  whale: moonlight's SoftFluid lane).
