#!/usr/bin/env python3
"""gen-scale — write a deterministic, many-context slice estate for the scale tests.

    gen-scale.py OUT_DIR [--bcs N]      (default N = 12: 48 slices, 100+ graph nodes)

Nothing random: the same N always writes the same files, so the map built from them can be a
committed golden (`tests/fixtures/slice-map/sample-data-scale.json`, N = 12). Each context `bcNN`:

    use_cases/create_thing    handles CreateThingNN, publishes ThingCreatedNN, writes ThingNN, POST endpoint
    use_cases/close_thing     handles CloseThingNN,  publishes ThingClosedNN,  writes ThingNN
    views/thing_list          projects ThingCreatedNN + ThingClosedNN (on projections[].from, not consumes)
    automations/follow_upstream   consumes ThingCreated of the PREVIOUS context and dispatches
                                  CreateThingNN, so one chain crosses five contexts; where a group of
                                  five starts (NN % 5 == 1) it is automations/close_on_create instead,
                                  consuming its own ThingCreatedNN and dispatching CloseThingNN

plus, every fourth context, an inbound translation `external_systems/partner_feed` that consumes the
partner's PartnerThingNN (published by nothing in scope — a dangling edge, as it is in a real estate)
and dispatches CreateThingNN, and every sixth context a reporting view with no message
edges at all (an isolated node the layout must still place).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

TESTS = "tests:\n  unit: { present: true }\n  integration: { present: false }\n"


def manifest(sid, kind, bc, body):
    return (
        f'schemaVersion: "1.3"\nslice: {sid}\nkind: {kind}\nbc: {bc}\nowner: {bc}-team\nstatus: live\n'
        f"language: java\ntier: cqrs-es\nlane: decider\n" + body + TESTS
    )


def estate(n):
    """{relative dir: slice.yaml text} for n contexts."""
    out = {}
    for i in range(1, n + 1):
        bc, k = f"bc{i:02d}", f"{i:02d}"

        def put(rel, kind, summary, body):
            name = rel.rsplit("/", 1)[1]
            out[f"{bc}/{rel}"] = manifest(f"{bc}.{name}", kind, bc, f"summary: {summary}\n" + body)

        put("use_cases/create_thing", "command", f"Create a thing in {bc}.",
            f"handles: [CreateThing{k}]\npublishes: [ThingCreated{k}]\nwrites: [Thing{k}]\n"
            f'endpoints:\n  - {{ method: POST, path: "/api/{bc}/things", auth: user }}\n')
        put("use_cases/close_thing", "command", f"Close a thing in {bc}.",
            f"handles: [CloseThing{k}]\npublishes: [ThingClosed{k}]\nwrites: [Thing{k}]\n")
        put("views/thing_list", "view", f"Things in {bc}.",
            f"serves: [ListThings{k}]\nowns: [thing_list_{k}]\nprojections:\n"
            f"  - {{ name: ThingList{k}, aggregateTypes: [Things{k}], from: [ThingCreated{k}, ThingClosed{k}] }}\n"
            f'endpoints:\n  - {{ method: GET, path: "/api/{bc}/things", auth: user }}\n')
        if i % 5 != 1:  # chain on from the previous context
            put("automations/follow_upstream", "automation", f"Create a {bc} thing when bc{i - 1:02d} creates one.",
                f"consumes: [ThingCreated{i - 1:02d}]\ndispatches: [CreateThing{k}]\n")
        else:           # a group starts here: close what was just created
            put("automations/close_on_create", "automation", f"Close a {bc} thing once it is created.",
                f"consumes: [ThingCreated{k}]\ndispatches: [CloseThing{k}]\n")
        if i % 4 == 0:
            put("external_systems/partner_feed", "translation", f"Things arriving from partner {k}.",
                f"externalSystem: partner_{k}\ndirection: inbound\nconsumes: [PartnerThing{k}]\n"
                f"maps:\n  - {{ from: PartnerThing{k}, to: CreateThing{k} }}\ndispatches: [CreateThing{k}]\n")
        if i % 6 == 0:
            put("views/thing_report", "view", f"Report over {bc} things.",
                f"serves: [ThingReport{k}]\nreads:\n  - {{ name: thing_list_{k} }}\n"
                f'endpoints:\n  - {{ method: GET, path: "/api/{bc}/report", auth: user }}\n')
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(prog="gen-scale", description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("out", help="directory to write into (created; must be empty or absent)")
    parser.add_argument("--bcs", type=int, default=12, help="number of bounded contexts (default 12)")
    args = parser.parse_args(argv)
    out = Path(args.out)
    if out.exists() and any(out.iterdir()):
        print(f"gen-scale: {out} is not empty", file=sys.stderr)
        return 2
    files = estate(args.bcs)
    for rel, text in files.items():
        path = out / rel / "slice.yaml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print(f"gen-scale: wrote {len(files)} manifests under {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
