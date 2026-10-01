---
name: slice-map
description: >
  Render the structure of a project that already follows the slice law — bounded contexts and their
  slices, the event flow that connects them, who writes what and who reads across a boundary, and an
  index from HTTP endpoint to the slice that serves it. Built from the slice.yaml manifests, so it is
  cheap and complete. Prints by default; --html emits a single self-contained page for a browser.
  Strictly read-only.
user-invocable: true
allowed-tools: [Read, Bash, Glob, Grep, Write]
argument-hint: "[<path>] [--bc <name>] [--html <file>] [--md <file>] [--view contexts|graph|flow|data|endpoints]"
---

# /essentials:slice-map

Answers **"what is here, where is it implemented, and how is it connected?"**

The other two slice commands answer different questions. `/essentials:slice-check` asks *does the code
obey the law* — it is an audit and its output is findings. `/essentials:slice-discover` asks *what
would this become* for a codebase that never adopted the law — it is inference and its output is
candidates. This one asks *what is here* for a project already on the law, and its output is a **map**.

The manifests already carry the whole graph — `handles`, `serves`, `publishes`, `consumes`, `writes`,
`reads`, `projections`, `endpoints`, `owns`, `dependsOn`, `supersedes`. Nothing here needs to read a
method body, which is what makes a whole-project map affordable. The manifest algebra runs in
`scripts/slice-index.py`, and the few facts a manifest does not carry (package, file list, payloads,
read-model columns, and the two source divergence checks) come from `scripts/slice-source.py`. This
command runs both and writes the report.

**Read-only. Writes no source, no manifests, and nothing the user did not name a path for.**

## Arguments

| Argument | Meaning |
|---|---|
| `<path>` | Module or source root. Default: the resolved package root |
| `--bc <name>` | Restrict to one bounded context |
| `--html <file>` | Also render a self-contained HTML page at a **user-supplied** path |
| `--md <file>` | Also write the text report as Markdown at a user-supplied path |
| `--view <name>` | Print one section only: `contexts` (default), `graph`, `flow`, `data`, `endpoints` |

`graph` is the message-flow view — **commands in → slice → events out → the slices that react**, plus
the commands an automation dispatches and the external systems a translation slice bridges. In the
terminal it prints as indented chains; in `--html` it is a pannable, zoomable, queryable node graph.
It is the same data as `flow` laid out as a graph rather than as rows, so a chain that crosses four
slices reads as one path instead of four lookups.

If `--html` is given with **no** path, propose `target/slice-map.html` (or `build/slice-map.html` on
Gradle) — a build directory is already git-ignored, which suits a one-off view you are going to throw
away — and **ask before writing**. Never choose the path silently: this plugin owns no directory, and
that is deliberate (`CLAUDE.md` design invariants).

## Step 1 — Terrain and the redirect

Cheap, always runs, and it decides whether the rest should.

1. Resolve the project root, source root and package path per
   `${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md` §2.
2. Glob for `slice.yaml`.
3. Language and §R5 lane per bounded context come with the map (Step 2): the value the manifests
   declare, else the lane `slice-source.py` detected, else `null` with a note.

**If no `slice.yaml` exists anywhere, stop.** There is nothing declared to map. Say so and redirect to
`/essentials:slice-discover`, which infers structure for exactly that case. This is the mirror image of
`slice-discover`'s pass-0 redirect, and the two must not compete over one codebase.

If `use_cases/` or `views/` directories exist but manifests do not, say that the structure is there and
the index is missing, and point at `/essentials:slice-check --fix-manifests` — that is the command that
creates them.

## Step 2 — Read the manifests and the source facts, by script

Three script runs produce everything Steps 2–4 need. Run them over the same `<root>` from the same
working directory. The two `slice-source.py` documents go to a temporary directory outside the project
(`mktemp -d`), never into the project, which this command does not write to:

```bash
tmp=$(mktemp -d)
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --json          > "$tmp/facts.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --check --json  > "$tmp/check.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py map <root> \
    --source-facts "$tmp/facts.json" --source-check "$tmp/check.json" [--bc <name>]
```

All three carry their pinned `pyyaml` in inline script metadata, which `uv run --script` installs.
Without `uv`, `python3 <script>` works where `pyyaml` is installed. The output of `map` is the data
contract of Step 6, complete. **Take it as it is.** Do not re-read the manifests to re-derive a field,
and do not add a node, edge or flag the script did not emit.

| Script | Exit | What it means here |
|---|---|---|
| `slice-index.py map` | 0 | the map is complete |
| | 1 | at least one manifest did not parse. It is still in the map, as a broken row with an `error` flag. Report it (below) |
| | 2 | could not run (no `pyyaml`, bad root, unreadable facts file). Stop and say why. Do not build the map by hand |
| | 3 | no `slice.yaml` under the root: the Step 1 redirect to `/essentials:slice-discover` |
| `slice-source.py` | 0, 1, 3 | JSON was written: pass it to `map` (1 and 3 are the audit's findings and gaps, which `map` turns into flags) |
| | 2 | no JSON (no `pyyaml` for `--check`, or a bad root). Run `map` without that `--source-…` flag and say so. `package`, `files`, payloads and read-model columns are then `null`/`[]`, and the two source divergence checks read **not checked** |

What the scripts read, so you can explain the map:

- **From the manifests** (`slice-index.py`): `slice`, `bc`, `kind`, `status`, `owner`, `summary`,
  `language`, `tier`, `lane`, `handles`, `serves`, `publishes`, `consumes`, `dispatches`, `writes`, `reads`
  (with `via`), `owns`, `projections`, `endpoints`, `invariants`, `externalSystem`, `direction`,
  `supersedes`, `dependsOn`, and which `tests` keys are present. The two-form fields (a bare string or an
  object with `name`; `reads` as a string or `{name, via}`) are normalised.
- **From the source** (`slice-source.py`), because they are not manifest fields: `package` (from the
  `package` declaration, never reconstructed from the path, which is only correct until a module moves),
  `files` (the source file names in the slice directory, minus `slice.yaml` and `CLAUDE.md`; on the JVM
  the file name *is* the type name), the message payloads and the read-model columns (Step 3).

`dispatches` matters more here than its rare use in the manifests suggests: it is the automation's
other half. Without it a policy slice appears to consume an event and do nothing, and the chain
*event → automation → command → command slice → event* — the shape most worth seeing on a map —
breaks in the middle.

**A manifest that fails to parse is a broken row with an `error` flag — never silently dropped.** A map
missing a slice is worse than a map with a hole in it: the hole is visible, the omission is not, and
this is the one output here that can mislead without being wrong.

The dominant cause is not exotic, and it is worth checking for by name before concluding anything about
the project: an **unquoted path containing a brace** inside a flow mapping.

```yaml
- { method: POST, path: /api/orders/{orderId}/cancel, auth: user }   # the `{` opens a nested mapping
```

The parser dies at the brace and the whole file fails to load. Every path variable produces one, so a
REST-shaped project tends to have either none of these or a dozen. Find them in one pass:

```bash
grep -rn 'path: [^"'"'"']*{' --include=slice.yaml .
```

Report them together as one finding — *"N manifests are not valid YAML; all N are unquoted paths
containing a path variable"* — name `manifest-guide.md` §3 as the rule and
`/essentials:slice-check --fix-manifests` as the repair. Do **not** repair them here: this command
writes no manifests. To still produce a complete map, rerun `map` with `--repair-braced-paths`, which
quotes those values in an in-memory copy and puts a `warn` flag and a note on every slice it repaired.
**Say so in the report** — a map built from text that is not what is on disk must announce that, or the
next run looks like a regression.

## Step 3 — The graph, as the script derived it

Everything below is in the `map` output. This section says what each part means, so the report can
explain it. None of it is yours to recompute.

- **Flow** (`flows[]`) — for each event name, the slices that `publishes` it and the slices that react
  to it. **A slice's inbound events are the union of `consumes` and `projections[].from`**, because a
  view declares the events its projector handles on the projection, not on `consumes`
  (`manifest-guide.md` §3). Reading `consumes` alone shows every correctly-generated view as reacting
  to nothing — the single easiest way to produce a confidently wrong map. An event with producers and no
  consumers is normal (someone may consume it later). An event **consumed but never published** is a real
  dangling edge: `dangling: true`. `crossContext` is true when more than one bounded context sits among
  its publishers and consumers.
- **Message payloads** (`messages[]`) — for every command and event a manifest names: the
  **identity-carrying property** first, then the rest of the payload, then where it is declared, read by
  `slice-source.py` from the `record` or `data class` constructor. The identity property is the component
  typed with the id of an aggregate the BC `writes`, else the first component whose name ends in `Id`.
  It is the field worth showing first: on an event it is what the message is *about*, and on a command it
  is what makes a retry idempotent rather than a second write — which is also what the manifest's
  `idempotencyKey` names, when it is set. A type with no declaration in scope (an event a translation
  `consumes` from a system you do not own) carries `found: false` and a summary saying the declaration
  was not found, never empty fields that read as "this message carries nothing".
- **Read-model shape** (a view's `readModels[]`) — the columns of the model it owns, with types, and the
  store. A view slice's identity **is** its read model (§R2), so its shape is the single most useful thing
  to show about it.
- **Message graph** — `handles` gives command → slice, `consumes` and `projections[].from` give
  event → slice, `publishes` gives slice → event, `dispatches` gives slice → command, and a translation's
  `externalSystem` + `direction` gives the inbound and/or outbound edge. Every node and every edge comes
  from a declared field; nothing is inferred, or the map stops being a map. The page builds this graph
  from `slices[]` itself (Step 6), and `slice-index.py graph` builds the same one for the terminal. Nodes
  are ranked by longest path from a source, so a chain reads left to right, and the walk is guarded
  against cycles: a saga that loops back is legal and must not hang the layout.

  **The rank has a floor, set by the node's role**: command type 0, command slice 1, event and external
  system 2, reactor slice (view / automation / translation) 3. Longest-path ranking alone puts
  everything with no inbound edge in column 0. A view that declares no `consumes` (legal, and normal on
  the service-entity lane), a reactor whose trigger has no declared publisher, and a dangling event would
  all end up *left of the commands*. That reads as "this is an entry point" and makes the events feeding
  a reactor impossible to follow. The floor only ever lifts, so every real chain stays exactly where it
  was. It is a floor rather than a fixed column because the grammar is not a straight line: an
  automation **dispatches a command**, so its chain loops back into the command column further right, and
  a fixed role-to-column mapping would have to break that edge or fold it backwards.
- **Writers** (`writers[]`) — grouped by target, with **the two sources kept apart**: a target from
  `writes` is an **aggregate**, one from `owns` is a **read model**. They have opposite normal cases, so a
  single "more than one writer" rule is wrong for one of them whichever way it is written:

  | Target | Several writing slices in **one** BC | Written from **two or more** BCs |
  |---|---|---|
  | aggregate (`writes`) | **Normal on every lane** — `place_order` and `cancel_order` both write `Order`, and adding a command adds a slice. Listed; `flag: null` | `crossBc` — an aggregate is one consistency boundary. **This is the finding**, and it sorts first |
  | read model (`owns`) | `sharedReadModel` — §R4 ownership, unless the owners are a declared `supersedes` twin, which is suppressed | `crossBc`, same as above |

  **Arity is not the finding.** Flagging every target with more than one writing slice would put a
  red badge on the correct design of every decider-, aggregate- and service-entity-lane BC in
  existence, including the one in this plugin's own fixture. The property worth surfacing is
  ownership crossing a boundary, not arity.
  `/essentials:slice-check` gate 4 makes the same three distinctions; the map and the audit must not
  disagree about what is normal.
- **Cross-context reads** (`crossReads[]`) — every `reads[]` whose model belongs to another bounded
  context. Under §R4 these must carry `via:`; `declared: false` is one without it.

## Step 4 — Divergence check (light, and honest about being light)

This map renders what the manifests **declare**. Manifests drift. A handful of cheap checks separate a
map from a fiction. The scripts run these, and only these, and put the result on the slice as a flag:

| Check | Flag when | From |
|---|---|---|
| Manifest parses | The file is not valid YAML (Step 2) — an `error` flag on a broken row, never an omission | `slice-index.py` |
| Handled events declared | The slice's `@MessageHandler` / `@Handler` / `@EventListener` methods handle event types (resolved through imports, aliases and FQNs) that are not all in `projections[].from` (view) or `consumes` (automation, translation). The graph then draws fewer inbound edges than the code has, which is the one drift that makes the map quietly *understate* the system. The flag names the missing events. Point at `/essentials:slice-check --fix-manifests`, which repairs it | `slice-source.py`, gate `11(b)` |
| Directory exists | The slice's declared directory is absent from disk. True by construction: the manifest is in it | `slice-index.py` |
| Kind matches location | A `command` outside `use_cases/`, a `view` outside `views/`, an `automation` outside `automations/`, a `translation` outside `external_systems/` | `slice-index.py` |
| Endpoint appears in source | The **route** — the declared `endpoints[].path` up to any `?` — matches no mapping in the slice, or no single handler on that route binds every discriminator named after the `?`. Never match the whole string: `path: "/api/shipping/order-status?status="` describes `@GetMapping(params = "status")`, and the query part appears nowhere in the source by construction. Matching verbatim reports a false positive on every run, on a slice that is doing exactly what §R2 allows. See `manifest-guide.md` §3 | `slice-source.py`, gates `6 endpoint route` and `6 discriminator` |
| Id uniqueness | Two manifests declare the same `slice` id | `slice-index.py` |
| Twin pairing | A `supersedes:` naming a slice that does not exist, or a superseded slice not at `status: deprecated` | `slice-index.py` |
| Dangling consume | An event in `consumes` **or `projections[].from`** that nothing in scope `publishes` | `slice-index.py` |

**A "not checked" flag is not a pass.** When `slice-source.py` could not verify a check (a handler in an
anonymous class, a route served by a WebFlux `RouterFunction`, a file it could not read), the slice
carries an `info` flag `not checked — <gate>: <reason>`. Report it as not checked, never fold it into
"no divergence". The same applies to both source checks when `slice-source.py` did not run at all.

**Stop there.** This is not `/essentials:slice-check` and must not grow into it: no R1–R5 gates, no
wiring check, no handler-shape check, no repository-surface check. `slice-source.py --check` also
reports gate 14 and the other gate-6 findings. They are the audit's, `map` ignores them, and so does
this report. Anything beyond the table above belongs to the audit, and the report says so — name the
command rather than half-doing its job.

Flags are rendered as annotations on the map, never as severities. This command grades nothing.

## Step 5 — Print the report

Provenance header, always:

```
slice-map — <path> @ <git HEAD sha, short>   <N> slices · <M> contexts
```

`meta.sha` carries the sha (`git rev-parse --short HEAD`), or `not-versioned` when the path is not in a
git repository. A structure map without provenance is indistinguishable from a current one, and two runs
cannot be diffed.

Then the five sections (or the one named by `--view`), each printed from the `map` JSON:

**Contexts** — per bounded context: lane, language, and its slices grouped by kind, each with status
and one-line summary.

**Graph** — the message chains. Print the output of

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py graph <root> [--bc <name>]
```

verbatim. It prints one indented chain per entry point, longest first, from each command or
externally-triggered entry point through to the slices that react. A repeat on the current path is
marked `↺ cycle` and stops there, a node already expanded elsewhere is marked `↑ continued under …`, a
chain that crosses a bounded context is marked `⇢ crosses`, and the slices that appear in no chain at all
are listed separately under "In no chain". Such a slice is either a pure view served only over HTTP or a
slice nothing reaches.

**Flow** — per event: who publishes it, who reacts to it. Group by bounded context; put cross-context
events first, because they are the ones nobody remembers. Same data as **Graph**, indexed by event
instead of by path — keep both: one answers *"who reacts to this event"*, the other *"what happens
when this command arrives"*.

**Data** — write targets with their writing slices (flagged targets first — an aggregate written
from two contexts, or a read model with two owners; several command slices on one aggregate inside one
context is normal and is not flagged), then
cross-context reads with their `via:` reader interface.

**Endpoints** — method, path, slice id, kind, auth, and the slice directory. This is the *"where is X
implemented"* answer and it is worth printing even when the rest is skipped.

Close with what was in scope and what was skipped (`notes[]`), and with every divergence flag.

## Step 6 — `--html`

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py html <root> --out <path> \
    --source-facts "$tmp/facts.json" --source-check "$tmp/check.json" [--bc <name>]
```

The script substitutes the `map` JSON for the single line

```js
const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;
```

of `${CLAUDE_PLUGIN_ROOT}/references/slice/slice-map-template.html` and writes the result to `--out`,
changing nothing else in the template. The page is self-contained by design — no CDN, no fonts, no
fetch — so it opens from `file://` on a machine with no network. It exits 1 and **writes nothing** when a
manifest did not parse (see *Refuse to render an incomplete map* below).

### The data contract

The template consumes exactly this shape, and `slice-index.py map` emits it: every key, with `[]` or
`null` rather than an omitted one. Slices may also carry `lane`, `dependsOn` and, on a broken row,
`error: true`; the template ignores keys it does not read.

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

The page derives the graph from `slices[]` itself — there is no separate `graph` key to emit. That is
deliberate: two representations of the same edges would drift, and the one in the JSON would win
silently. `flows[]` stays because it carries the `crossContext` and `dangling` results, which
`slice-index.py` computes and the renderer does not.

**The HTML page's five views are Contexts, Graph, Flow, Data, Endpoints.** The graph:

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

**Refuse to render an incomplete map into HTML.** `slice-index.py html` exits 1 and writes nothing
when a manifest failed to parse. Say so in the terminal report, name the manifests, and offer the
announced `--repair-braced-paths` rerun when every failure is an unquoted braced path. A page that
silently omits a slice is the one output here that can mislead without being wrong.

## Out of scope — state these if asked

- **Audits nothing.** No R1–R5 gates and no severities — `/essentials:slice-check` owns that.
- **Infers nothing.** Every node comes from a manifest — `/essentials:slice-discover` owns inference.
- **Writes no source and no manifests.** Drift is reported, never repaired.
- **Owns no directory.** `--html` / `--md` take a path from the user; the command may suggest one and
  must ask before writing it.
- Not a dependency or architecture report.

## Verification fixture

The scripts carry the oracle. `tests/scripts/test_slice_index.py` pins the map, the terminal graph and
the locate queries against goldens in `tests/slice-index/golden/`, over a saga cycle, a `supersedes`
twin, a generated 60-context estate and the real fixtures. `tests/scripts/test_slice_source.py` pins the
source facts, and `tests/fixtures/slice-map/render-check.py` renders the page in headless Chrome. By
fixture: `tests/fixtures/service-entity/` should reproduce its declared slices with no divergence flags
except the ones its `TEST-GUIDE.md` records as deliberate. `tests/fixtures/worked-example/`'s `orders`
context is the full chain: `PlaceOrder → place_order → OrderPlaced → {order_list, screen_order,
warehouse}`, and `screen_order` dispatches `CancelOrder → cancel_order → OrderCancelled`, which closes
a cycle back into `screen_order`.
