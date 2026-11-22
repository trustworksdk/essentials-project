# The slice-map data contract and the `html` page

What `scripts/slice-index.py map` emits, and the single-graph page `slice-index.py html` renders from it
with `references/slice/slice-map-template.html`. The template and this contract are one contract: change
either and update the other in the same commit, then run `uv run --script tests/scripts/test_slice_index.py`
and `python3 tests/fixtures/slice-map/render-check.py`. `/essentials:slice-map` and
`/essentials:slice-map-html` take the `map` output as it is (`commands/slice-map.md` Step 2); read this
file when a key's meaning is not plain from the JSON, or when changing a reader, the template or the script.

## The `map` JSON

`slice-index.py map` emits exactly this shape, and every reader takes it as it is: the
`/essentials:slice-map` report, the `html` page (§ The `html` page below), and `slice-index.py atlas`,
which adds its own keys on top (§ The Atlas keys). Every key is present, with `[]` or `null` rather than an omitted one. Slices may also carry `lane`,
`dependsOn` and, on a broken row, `error: true`; a reader ignores keys it does not read.

```json
{
  "meta": {
    "root": "backend/src/main/kotlin/com/example/shop",
    "sha": "a1b2c3d",
    "project": "shop",
    "scope": "full",
    "sliceCount": 12,
    "bcCount": 3,
    "skipped": []
  },
  "contexts": [
    { "id": "orders", "lane": "decider", "language": "kotlin", "tier": "cqrs-es",
      "owners": ["orders-team"], "sliceIds": ["orders.place_order", "orders.order_list"] }
  ],
  "slices": [
    { "id": "orders.place_order", "bc": "orders", "name": "place_order", "kind": "command",
      "status": "live", "owner": "orders-team", "summary": "Accept a new order",
      "language": "kotlin", "path": "…/orders/use_cases/place_order",
      "package": "com.example.shop.orders.use_cases.place_order",
      "files": ["PlaceOrder.kt", "PlaceOrderDecider.kt", "PlaceOrderAPI.kt", "OrderPlaced.kt"],
      "handles": ["PlaceOrder"], "serves": [], "publishes": ["OrderPlaced"], "consumes": [],
      "dispatches": [], "writes": ["Order"], "owns": [], "projections": [],
      "reads": [{ "name": "OrderList", "via": null, "external": false }],
      "endpoints": [{ "method": "POST", "path": "/api/orders", "auth": "user" }],
      "invariants": [{ "id": "INV-1", "text": "An order must have at least one line",
                       "enforcedBy": "PlaceOrderDecider" }],
      "readModels": [],
      "externalSystem": null, "direction": null, "supersedes": null,
      "tests": ["unit", "integration"],
      "flags": [{ "level": "warn", "text": "declared endpoint not found in source" }] }
  ],
  "messages": [
    { "name": "OrderPlaced", "type": "event", "identity": "orderId: OrderId",
      "fields": ["orderId: OrderId", "customerId: CustomerId", "placedAt: Instant"],
      "declaredIn": "orders/events/OrderPlaced.kt", "version": 1, "schema": null,
      "idempotencyKey": null, "summary": null }
  ],
  "flows": [
    { "event": "OrderPlaced", "bc": "orders", "from": ["orders.place_order"],
      "to": ["orders.order_list"], "crossContext": false, "dangling": false }
  ],
  "writers": [
    { "target": "Order", "kind": "aggregate", "bcs": ["orders"],
      "slices": ["orders.place_order", "orders.cancel_order"], "flag": null },
    { "target": "order_list_view", "kind": "read-model", "bcs": ["orders"],
      "slices": ["orders.order_list"], "flag": null }
  ],
  "crossReads": [
    { "from": "billing.invoice_list", "toBc": "orders", "model": "OrderSummary",
      "via": "OrderReader", "declared": true }
  ],
  "notes": ["orders/aggregates is on the aggregate lane — scaffolded in Java only"]
}
```

`messages[].type` is `command` or `event`, matched to a node by `name` + `type`. A view slice's
`readModels[]` entries are `{ name, store, columns: [{ name, type, note }] }` — a plain string column is
accepted and rendered with no type. Omit `readModels` on non-view slices rather than sending `[]` with a
made-up shape.

`kind` is one of `command | view | automation | translation`; `status` one of
`planned | live | deprecated | shadowed`; `flags[].level` one of `info | warn | error`. The template
colours by those values and falls back gracefully on an unknown one.

There is no `graph` key: the `html` page derives the graph from `slices[]` itself, and `atlas` derives
its layout's edges from `slices[]` in the same run. Two representations of the same edges would drift,
and the one in the JSON would win silently. `flows[]` stays because it carries the `crossContext` and
`dangling` results, which `slice-index.py` computes and the renderer does not.

## The Atlas keys

`map` also emits, after the keys above, what the Atlas checks of `/essentials:slice-map` Step 4 need
(computed by `scripts/slice_atlas.py`, whose docstring defines each shape):

| Key | What it carries |
|---|---|
| `slices[].flows` | the declared `flow:` as a list; `[]` when absent |
| `slices[].scenarios` | `scenarios:` normalised — `{name, given: [Step], when: Step \| null, then, test, testResolved: {status: found \| missing \| not-checked, path, line}, note}`, a Step `{type: event \| command \| query \| state \| external \| schedule, name, with}`, `then` one of `{kind: steps, steps}`, `{kind: error, error, message}`, `{kind: nothing}`, `{kind: readModel, readModel, rows}`; `[]` when absent |
| `slices[].schedule` | the automation's `schedule` (`cron`, `fixedDelay`, `initialDelay`, `timeZone`, `note`); `null` when absent |
| `slices[].doc` | the slice's `CLAUDE.md` converted to a fixed safe HTML subset, every character escaped first; `null` when absent |
| `processes` | journeys: `{id, name, declared, color, bcs, sliceIds (causal order), entries, links: [{to, via}]}` — a declared `flow:` name, a derived `~<entry slice id>`, or `~unattached:<bc>` |
| `issues` | every `/essentials:slice-map` Step 4 result: `{id, code, level, text, target: {kind: slice \| message \| process \| context, id, part}, source: map \| source \| atlas, fix: {route: repair \| audit \| author, bc}}`, sorted by level then id. `fix` names who owns the repair — `slice_atlas.py` `FIX_ROUTES`; the page's Copy words it, the map repairs nothing. A context issue folded by the aggregation rule (`flow-undeclared`, `scenario-none`) also carries `members: [slice id]`, the slices it stands for — the diff compares per slice, and the page resolves a per-slice id `<code>:<slice id>` to it — and `memberText`, the per-slice wording the Diff lens names such an id with |
| `lineage` | `{fields: {normalised field name: ["msg:<type>:<Name>" \| "rm:<ReadModel>"]}, technicalColumns}` |

`atlas` alone adds `diff` (`null` without a baseline; added, removed and changed slices, added and
removed edges, new and fixed issues), `layout` (the coordinates of every lens, from
`scripts/slice_atlas_layout.py`) and, under `meta`, `atlasVersion`, `generatedBy`, `incomplete` (the
number of manifests that did not parse) and `initial` (the lens, focus and context the page opens on).

## The `html` page

`/essentials:slice-map-html` writes the Atlas; no command writes this page any more. `slice-index.py
html` still does — a single-graph page a script can publish — and `render-check.py` keeps it working:

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py html <root> --out <path> \
    --source-facts <(…slice-source.py <root> --json) --source-check <(…slice-source.py <root> --check --json) [--bc <name>]
```

The script substitutes the `map` JSON for the single line

```js
const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;
```

of `${CLAUDE_PLUGIN_ROOT}/references/slice/slice-map-template.html` and writes the result to `--out`,
changing nothing else in the template. The page is self-contained by design — no CDN, no fonts, no
fetch — so it opens from `file://` on a machine with no network. It exits 1 and **writes nothing** when a
manifest did not parse (see *Refuse to render an incomplete map* below).

**Its five views are Contexts, Graph, Flow, Data, Endpoints.** The graph:

- **pans and zooms** — drag, scroll wheel toward the cursor, `+`/`−`, and fit-to-window;
- **maximises** — `⛶` fills the screen through the Fullscreen API, falling back to a fixed-position
  layer where a `file://` page is refused fullscreen, and `Esc` leaves either;
- **explains on hover** — a card carrying the slice's badges, summary, package, class list, **read-model
  columns**, endpoints, messages in and out, invariants with what enforces each, tests, and any
  divergence flag. Hovering a command or event gives its **identity-carrying property, payload and
  declaring file**, plus the slices on both ends of it; a dangling event says so;
- **marks direction** — hovering or focusing a node colours its **inbound** edges apart from its
  **outbound** ones, which is the only practical way to see which events feed a particular view once the
  graph is wide enough that an edge crosses four columns;
- **isolates on click** — dims everything **not connected** to the clicked node, where connected means
  *transitively* connected: the node's whole component stays lit, so a chain crossing four slices reads
  end to end rather than fading two hops out. Edges touching the clicked node are highlighted. Released
  by clicking again, `✕`, or the background, which restores every node;
- **opens the full manifest on double-click**, with a view's read model rendered as a **table** of
  columns, types and notes;
- **filters by type** — one chip per slice kind and per message-node type. A chip removes its nodes
  from the drawing rather than dimming them, because the point of filtering to *automations only* is to
  see what is left, not to see it faintly. The query box dims instead, and the two compose.

**Refuse to render an incomplete map into HTML — on this page.** `slice-index.py html` exits 1 and
writes nothing when a manifest failed to parse. Say so, name the manifests, and offer the announced
`--repair-braced-paths` rerun when every failure is an unquoted braced path. A page that silently omits
a slice is the one output here that can mislead without being wrong. The Atlas does the opposite for
the same reason: it writes the page, draws each unparsed manifest as a red error card under a "map
incomplete" banner, and exits 1 — there the omission is the most visible thing on the page
(`/essentials:slice-map-html` Step 4).
