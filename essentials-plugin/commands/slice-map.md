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
method body, which is what makes a whole-project map affordable.

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
3. Per bounded context, detect language (§1) and §R5 lane (§1b).

**If no `slice.yaml` exists anywhere, stop.** There is nothing declared to map. Say so and redirect to
`/essentials:slice-discover`, which infers structure for exactly that case. This is the mirror image of
`slice-discover`'s pass-0 redirect, and the two must not compete over one codebase.

If `use_cases/` or `views/` directories exist but manifests do not, say that the structure is there and
the index is missing, and point at `/essentials:slice-check --fix-manifests` — that is the command that
creates them.

## Step 2 — Read the manifests

Read every `slice.yaml` in scope. Read no source yet.

For each, collect: `slice`, `bc`, `kind`, `status`, `owner`, `summary`, `language`, `tier`,
`handles`, `serves`, `publishes`, `consumes`, `dispatches`, `writes`, `reads` (with `via`), `owns`,
`projections`, `endpoints`, `invariants`, `externalSystem`, `direction`, `supersedes`, `dependsOn`,
and which `tests` keys are present. Record the slice's directory path.

Two fields are **not** in the manifest and come from the directory you are already listing for the
divergence check in Step 4 — they cost one `ls` each and they are what turns a node into something
you can act on:

- `package` — the slice's JVM package. Read it from the `package` declaration of any source file in
  the directory; do not reconstruct it from the path, which is only correct until a module moves.
- `files` — the source file names in the slice directory, excluding `slice.yaml` and `CLAUDE.md`. On
  the JVM the file name *is* the type name, so this is the class list without parsing anything.

Emit them as `null` / `[]` where the directory is missing rather than guessing.

`dispatches` matters more here than its rare use in the manifests suggests: it is the automation's
other half. Without it a policy slice appears to consume an event and do nothing, and the chain
*event → automation → command → command slice → event* — the shape most worth seeing on a map —
breaks in the middle.

Normalise the two-form fields as the schema allows them: `handles`, `serves`, `consumes`, `dispatches`,
`writes` and `publishes` may each be a bare string or an object with `name`; `reads` may be a string or
`{name, via}`. Treat both forms as the same thing.

**Parse each one, and treat a parse failure as a finding.** A manifest that fails to parse is reported
as a broken row and carried into the output with an `error` flag — **never silently dropped**. A map
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
writes no manifests. You may parse a locally-corrected copy in memory to produce a complete map, and if
you do, **say so in the report** — a map built from text that is not what is on disk must announce that,
or the next run looks like a regression.

## Step 3 — Derive the graph

Four derivations, all pure manifest algebra:

- **Flow** — for each event name, the slices that `publishes` it and the slices that consume it. **A
  slice's inbound events are the union of `consumes` and `projections[].from`**, because a view declares
  the events its projector handles on the projection, not on `consumes` (`manifest-guide.md` §3).
  Reading `consumes` alone shows every correctly-generated view as reacting to nothing — the single
  easiest way to produce a confidently wrong map. An event with producers and no consumers is normal
  (someone may consume it later); an event **consumed but never published** is a real dangling edge and
  is flagged.
- **Message payloads** — for every command and event type in scope, read its declaring file (it is in
  the slice directory, or the BC's `events/`) and record: the **identity-carrying property** first, then
  the rest of the payload, then where it is declared. On the JVM these are `record`s and `data class`es,
  so the constructor signature *is* the payload — no parsing beyond it is needed. The identity property
  is the field whose type is the aggregate's id type (`writes:` names the aggregate), falling back to the
  first field whose name ends in `Id`. It is the field worth showing first: on an event it is what the
  message is *about*, and on a command it is what makes a retry idempotent rather than a second write —
  which is also what the manifest's `idempotencyKey` names, when it is set.

  Where the type is not in scope — an event a translation slice `consumes` from a system you do not own
  — record the name and say the declaration was not found. Do not emit empty fields that read as "this
  message carries nothing".
- **Read-model shape** — for a view slice, the columns of the model it owns, with types. Take them from
  the view entity or projection type in the slice directory (again a `record` / `data class` / `@Entity`),
  and name the store when the slice declares one. A view slice's identity **is** its read model (§R2), so
  its shape is the single most useful thing to show about it.
- **Message graph** — the same edges plus command and external-system nodes, as a directed graph:
  `handles` gives command → slice, `consumes` gives event → slice, `publishes` gives slice → event,
  `dispatches` gives slice → command, and a translation slice's `externalSystem` + `direction` gives
  the inbound and/or outbound edge. `consumes` **and `projections[].from`** both give event → slice; a
  view uses the second, so union them. Every node and every edge comes from a declared field — **infer
  nothing here**, or the map stops being a map. Rank nodes by longest path from a source so the chain
  reads left to right, and guard the walk against cycles: a saga that loops back is legal and must not
  hang the layout.

  **Put a floor under the rank, from the node's role**: command type 0, command slice 1, event and
  external system 2, reactor slice (view / automation / translation) 3. Longest-path ranking alone puts
  everything with no inbound edge in column 0, so a view that declares no `consumes` — legal, and normal
  on the service-entity lane — a reactor whose trigger has no declared publisher, and a dangling event
  all end up *left of the commands*, which reads as "this is an entry point" and makes the events feeding
  a reactor impossible to follow. The floor only ever lifts, so every real chain stays exactly where it
  was. It is a floor rather than a fixed column precisely because the grammar is not a straight line:
  an automation **dispatches a command**, so its chain loops back into the command column further right,
  and a fixed role-to-column mapping would have to break that edge or fold it backwards.
- **Writers** — group by target, and **keep the two sources apart**: a target from `writes` is an
  **aggregate**, one from `owns` is a **read model**. They have opposite normal cases, so a single
  "more than one writer" rule is wrong for one of them whichever way it is written:

  | Target | Several writing slices in **one** BC | Written from **two or more** BCs |
  |---|---|---|
  | aggregate (`writes`) | **Normal on every lane** — `place_order` and `cancel_order` both write `Order`, and adding a command adds a slice. List them; flag nothing | `crossBc` — an aggregate is one consistency boundary. **This is the finding**, and it sorts first |
  | read model (`owns`) | `sharedReadModel` — §R4 ownership, unless the pair is a declared `supersedes` twin, which is suppressed | `crossBc`, same as above |

  **Arity is not the finding.** Flagging every target with more than one writing slice would put a
  red badge on the correct design of every decider-, aggregate- and service-entity-lane BC in
  existence, including the one in this plugin's own fixture. The property worth surfacing is
  ownership crossing a boundary, not arity.
  `/essentials:slice-check` gate 4 makes the same three distinctions; the map and the audit must not
  disagree about what is normal.
- **Cross-context reads** — every `reads[]` whose model belongs to another bounded context. Under §R4
  these must carry `via:`; one without it is flagged.

## Step 4 — Divergence check (light, and honest about being light)

This map renders what the manifests **declare**. Manifests drift. A handful of cheap checks separate a
map from a fiction — run these, and only these:

| Check | Flag when |
|---|---|
| Manifest parses | The file is not valid YAML (Step 2) — an `error` flag on a broken row, never an omission |
| Handled events declared | The slice's source has `@MessageHandler` / `@Handler` methods whose event parameter types do not all appear in `projections[].from` (view) or `consumes` (automation, translation). Flag as `warn` on the slice and name the missing events: the graph is drawing fewer inbound edges than the code has, which is the one drift that makes the map quietly *understate* the system. Point at `/essentials:slice-check --fix-manifests`, which repairs it |
| Directory exists | The slice's declared directory is absent from disk |
| Kind matches location | A `command` outside `use_cases/`, a `view` outside `views/`, an `automation` outside `automations/`, a `translation` outside `external_systems/` |
| Endpoint appears in source | The **route** — the declared `endpoints[].path` up to any `?` — is not found in the slice's directory, or a discriminator named after the `?` is not bound in that handler. Never match the whole string: `path: "/api/shipping/order-status?status="` describes `@GetMapping(params = "status")`, and the query part appears nowhere in the source by construction. Matching verbatim reports a false positive on every run, on a slice that is doing exactly what §R2 allows. See `manifest-guide.md` §3 |
| Id uniqueness | Two manifests declare the same `slice` id |
| Twin pairing | A `supersedes:` naming a slice that does not exist, or a superseded slice not at `status: deprecated` |
| Dangling consume | An event in `consumes` that nothing in scope `publishes` |

**Stop there.** This is not `/essentials:slice-check` and must not grow into it: no R1–R5 gates, no
wiring check, no handler-shape check, no repository-surface check. Anything beyond the table above
belongs to the audit, and the report says so — name the command rather than half-doing its job.

Flags are rendered as annotations on the map, never as severities. This command grades nothing.

## Step 5 — Print the report

Provenance header, always:

```
slice-map — <path> @ <git HEAD sha, short>   <N> slices · <M> contexts
```

Run `git rev-parse --short HEAD`. A structure map without provenance is indistinguishable from a
current one, and two runs cannot be diffed. If the path is not in a git repository, say
`@ not-versioned`.

Then the five sections (or the one named by `--view`):

**Contexts** — per bounded context: lane, language, and its slices grouped by kind, each with status
and one-line summary.

**Graph** — the message chains, printed as indented paths from each command or externally-triggered
entry point through to the slices that react. In the terminal, print the longest chains first and stop
at a repeated node rather than looping. A chain that crosses a bounded context is worth marking; a
slice that appears in no chain at all is worth listing separately, because it is either a pure view
served only over HTTP or a slice nothing reaches.

**Flow** — per event: who publishes it, who consumes it. Group by bounded context; put cross-context
events first, because they are the ones nobody remembers. Same data as **Graph**, indexed by event
instead of by path — keep both: one answers *"who reacts to this event"*, the other *"what happens
when this command arrives"*.

**Data** — write targets with their writing slices (flagged targets first — an aggregate written
from two contexts, or a read model with two owners; several command slices on one aggregate inside one
context is normal and is not flagged), then
cross-context reads with their `via:` reader interface.

**Endpoints** — method, path, slice id, kind, auth, and the slice directory. This is the *"where is X
implemented"* answer and it is worth printing even when the rest is skipped.

Close with what was in scope and what was skipped, and with any divergence flags.

## Step 6 — `--html`

```
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-map-template.html
```

Replace the single line

```js
const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;
```

with `const SLICE_MAP = <the JSON>;` and write the result to the requested path. Change nothing else in
the template. The page is self-contained by design — no CDN, no fonts, no fetch — so it opens from
`file://` on a machine with no network.

### The data contract

The template consumes exactly this shape. Emit every key; use `[]` or `null` rather than omitting one.

```json
{
  "meta": {
    "root": "backend/src/main/kotlin/com/acme/shop",
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
      "package": "com.acme.shop.orders.use_cases.place_order",
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
silently. `flows[]` stays because it carries the `crossContext` and `dangling` judgements, which are
the command's to make and not the renderer's.

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

**Refuse to render an incomplete map into HTML.** If manifests failed to parse, say so in the terminal
report and put them in `notes` — a page that silently omits a slice is the one output here that can
mislead without being wrong.

## Out of scope — state these if asked

- **Audits nothing.** No R1–R5 gates and no severities — `/essentials:slice-check` owns that.
- **Infers nothing.** Every node comes from a manifest — `/essentials:slice-discover` owns inference.
- **Writes no source and no manifests.** Drift is reported, never repaired.
- **Owns no directory.** `--html` / `--md` take a path from the user; the command may suggest one and
  must ask before writing it.
- Not a dependency or architecture report.

## Verification fixture

`tests/fixtures/service-entity/` is the repo's `slice-check` oracle and carries real manifests; running
`slice-map` against it should reproduce its declared slices with no divergence flags except the ones its
`TEST-GUIDE.md` records as deliberate. `tests/fixtures/worked-example/`'s `orders` context is the second
check — two command slices, one view slice, one event flowing between them.
