# ModBridge — a Minecraft mod translation layer

Translates compiled mod jars between Minecraft mod-loader versions. First target:
**Forge/NeoForge 1.20.1 mods running on NeoForge 1.21.1**, Fabric later (see
[docs/ROADMAP.md](docs/ROADMAP.md)).

Every claim below is gated by a live-server test matrix (`scripts/run-matrix.sh`):
a real headless NeoForge 21.1.250 dedicated server runs four phases — bare smoke,
untranslated jar (must be skipped by the loader), the full **Connector-style drop-in
flow**, and a warm reboot proving the translation cache. **16/16 checks pass.**

## How it works

```
fixture mod (real 1.20.1 Forge idioms)
  compiled against stubs that mirror the 1.20.1 erased API surface
        │
        ▼
fixture-1.20.1.jar  ──►  ModBridge rewriter (ASM tree)  ──►  translated jar
  META-INF/mods.toml       1. surgery on raw bytecode:          META-INF/neoforge.mods.toml (regenerated)
  pack_format 15              - @Mod ctor: ()V -> (IEventBus),   pack.mcmeta bumped to 48
                              FMLJavaModLoadingContext chain -> ALOAD 1
                            - new ResourceLocation(a,b) -> ResourceLocation.fromNamespaceAndPath(a,b)
                          2. member renames (old-space owner|desc|name)
                          3. member descriptor rewrites (return-type changes),
                             stack-shape aware (consumes/inserts POP/POP2)
                          4. type renames: exact table, then package prefixes
                          5. verification gate: any net/minecraftforge ref left = hard fail
        │
        ▼
NeoForge 1.21.1 dedicated server (isolated testbed, RCON on 127.0.0.1:25586)
```

The rewriter never recompiles anything and never recomputes stack frames — every
rewrite preserves the operand stack shape, so the original StackMapTable stays valid.

## Connector-style usage (drop-in, like Sinytra)

Add one JVM arg (e.g. to `user_jvm_args.txt` on a server):

```
-javaagent:/path/to/modbridge-agent.jar
```

Then drop any Forge/NeoForge **1.20.1** mod jar into `mods/source-1201/` and start.
At boot — before the loader scans mods — the agent translates each jar, installs it
into `mods/`, and logs everything under a `[ModBridge]` prefix. Unchanged mods are
cached by SHA-256 in `modbridge-cache/` (warm reboots are free). Jars that already
carry `neoforge.mods.toml` are copied verbatim; jars without mod metadata are skipped
with a warning; a broken mod logs a failure and never blocks the server from starting.
It also works as a plain offline CLI: `java -cp modbridge-agent.jar
com.modbridge.agent.ModBridgeAgent <sourceDir> <modsDir>`.

## Quickstart (from source)

```bash
bash scripts/build-fixture.sh   # stubs + fixture -> out/fixture-1.20.1.jar
bash scripts/build-agent.sh     # -> out/modbridge-agent.jar (rewriter + ASM + rules)
bash scripts/translate.sh       # offline CLI: -> out/modbridgefixture-1.21.1-neoforge.jar
bash scripts/setup-server.sh    # one-time: clone NeoForge 21.1.250 testbed (176 MB)
bash scripts/run-matrix.sh      # smoke / negative / connector flow / warm reboot
```

Drive the server console directly (full command access, no client needed):

```bash
testenv/rcon.py 25586 modbridge "list" "fixtureping"
```

## Rule tables (rules/)

| File | Format | Example |
|---|---|---|
| `packagerenames.txt` | `from/ = to/` | `net/minecraftforge/eventbus/ = net/neoforged/bus/` |
| `typerenames.txt` | `old<TAB>new` (internal names) | `RegistryObject` → `DeferredHolder`, `MinecraftForge` → `NeoForge` |
| `memberrenames.txt` | `owner\|desc\|name = newName` | `isPresent()` → `isBound()` |
| `memberdescrenames.txt` | `owner\|name\|oldDesc = newDesc` | `sendSuccess` `()Z` → `()V` |

Key discovery encoded here: NeoForge's fork **splits** `net.minecraftforge.*` —
mod-API classes moved to `net.neoforged.neoforge.*` but framework libs re-rooted at
`net.neoforged.<lib>` (`bus`, `fml`, ...). A naive blanket rename produces wrong names.

## What the v0 rule set covers (fixture-verified)

- Forge→NeoForge package/class moves (registries, common, event, eventbus, fml)
- `RegistryObject` → `DeferredHolder` (`get`/`getId` line up erased; `isPresent`→`isBound`)
- `@Mod` constructor injection: the 1.20.1 no-arg ctor becomes `(IEventBus)`, with the
  `FMLJavaModLoadingContext.get().getModEventBus()` expression spliced to `ALOAD 1`
- `MinecraftForge.EVENT_BUS` → `NeoForge.EVENT_BUS` (game bus), listener method refs
  through `invokedynamic` (bsm args + handles remapped)
- `ResourceLocation` constructor removals (1.20.5+ made them private) → static factories,
  including the args-between-DUP-and-INVOKESPECIAL bytecode layout
- `CommandSourceStack.sendSuccess` boolean→void return change, with POP cleanup
- `mods.toml` → `neoforge.mods.toml` regeneration, pack_format 15 → 48

## Honest limitations (v0)

- Rule coverage is hand-authored and fixture-sized; real mods will surface thousands of
  deltas (the roadmap's mapping-diff tooling generates tables automatically).
- Descriptor rewrites only work when the caller discards the old return value; consumed
  returns need generated shims (roadmap phase 3).
- No Mixin support yet: refmaps and mixin targets are version-bound (roadmap phase 4).
- Vanilla-side (mojmap) signatures were stable for the fixture surface; cross-version
  vanilla renames (e.g. level/entity APIs) are roadmap phase 5.
- The fixture is compiled against stubs that mirror the real 1.20.1 erased signatures
  (cross-checked against the 21.1 jars); a real 1.20.1 server validation environment is
  roadmap phase 7.
