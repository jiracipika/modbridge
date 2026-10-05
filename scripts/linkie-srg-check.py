#!/usr/bin/env python3
"""Cross-check SrgExactJoinResolver additions against Linkie's mojang_srg namespace.

Linkie (https://linkie.shedaniel.dev) exposes a per-id search API at
https://linkieapi.shedaniel.me/api/search (namespace=mojang_srg = "Mojang (via SRG)",
which Linkie builds from the same inputs we use directly: Mojang official mappings +
MCPConfig tsrg2 — see linkie/linkie-core MojangSrgNamespace.kt).

For every (srgId, owner, mojName) row this queries Linkie for the id at a given MC
version and verifies that Linkie's (class, name) equals our (owner, mojName).
Responses are cached verbatim under <cache-dir>/<srgId>.json and never re-fetched;
requests are paced to stay polite.

usage: linkie-srg-check.py <additions.txt> <missing.txt> <version> <cache-dir> [--sample N]
  additions.txt  srgId=mojName lines (from SrgExactJoinResolver)
  missing.txt    srgId|owner|static|desc|outcome rows (owner context)
"""
import json
import random
import sys
import time
import urllib.request
from pathlib import Path


def load(additions_path: Path, missing_path: Path):
    names = {}
    for line in additions_path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        sid, moj = line.split("=", 1)
        names[sid] = moj
    owners = {}
    for line in missing_path.read_text(encoding="utf-8").splitlines():
        parts = line.split("|")
        if len(parts) >= 5 and parts[0] not in owners:
            owners[parts[0]] = parts[1]
    return names, owners


def linkie_lookup(sid: str, version: str, cache_dir: Path):
    cache = cache_dir / f"{sid}.json"
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    url = (f"https://linkieapi.shedaniel.me/api/search?namespace=mojang_srg"
           f"&query={sid}&version={version}&limit=10")
    req = urllib.request.Request(url, headers={"Accept": "application/json",
                                               "User-Agent": "modbridge-srg-resolver/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    cache.write_text(json.dumps(data, indent=1), encoding="utf-8")
    time.sleep(0.5)  # pace: only on actual network hits
    return data


def main() -> int:
    additions = Path(sys.argv[1])
    missing = Path(sys.argv[2])
    version = sys.argv[3]
    cache_dir = Path(sys.argv[4])
    sample = None
    if "--sample" in sys.argv:
        sample = int(sys.argv[sys.argv.index("--sample") + 1])
    cache_dir.mkdir(parents=True, exist_ok=True)

    names, owners = load(additions, missing)
    ids = sorted(names, key=lambda s: int(s[2:-2]))
    if "--ids" in sys.argv:  # check an explicit subset (e.g. ids actually used by real mods)
        wanted = set(Path(sys.argv[sys.argv.index("--ids") + 1]).read_text().split())
        ids = [i for i in ids if i in wanted]
    elif sample is not None and sample < len(ids):
        used_hint = [i for i in ids]  # deterministic spread: take every kth id
        k = max(1, len(ids) // sample)
        ids = used_hint[::k][:sample]

    agree = disagree = no_entry = no_class = 0
    rows = []
    for sid in ids:
        data = linkie_lookup(sid, version, cache_dir)
        entries = [e for e in data.get("entries", []) if e.get("i") == sid]
        if not entries:
            no_entry += 1
            rows.append((sid, "linkie-no-entry", names[sid], ""))
            continue
        e = entries[0]
        lclass = (e.get("c") or "").replace(".", "/")
        lname = e.get("n")
        if lclass and lclass != owners.get(sid, "?"):
            no_class += 1
            rows.append((sid, f"linkie-class-diff:{lclass}", names[sid], lname or ""))
        if lname == names[sid]:
            agree += 1
        else:
            disagree += 1
            rows.append((sid, "linkie-name-diff", names[sid], lname or ""))
    print(f"checked {len(ids)} ids against Linkie mojang_srg@{version}: "
          f"{agree} agree, {disagree} name-disagree, {no_class} class-disagree, {no_entry} no-entry")
    for sid, kind, mine, theirs in rows:
        print(f"  {kind}: {sid} mine={mine} linkie={theirs}")
    return 1 if disagree else 0


if __name__ == "__main__":
    sys.exit(main())
