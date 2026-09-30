# Change Procedure — changing a project that is already on the law

Read by `skills/essentials-change/SKILL.md`. Its counterpart is `references/slice/slice-authoring.md`,
which covers **creating** a slice; this file covers **changing** what exists.

The structural law is `rules/slice-design.md`; the anatomy is `references/slice/slice-model.md`; the
manifest fields are `references/slice/manifest-guide.md`. Cite them by section — never restate them.

This procedure exists because `/essentials:add-slice` deliberately refuses to touch an existing slice
(`commands/add-slice.md` — *"an existing slice directory is an abort, never a merge"*), and most real
work is not a new slice. Without a written procedure the fallback is whatever Claude would have done
in an unfamiliar Spring codebase, which is a controller and a service.

---

## 1. The gate — is this project on the law?

Cheapest signal first. Stop at the first that answers.

| Signal | Conclusion |
|---|---|
| `.claude/rules/essentials-slices.md` exists | **On the law.** Proceed |
| Any `slice.yaml` under the source tree | **On the law.** Proceed |
| `<bc>/use_cases/` or `<bc>/views/` directories exist | **On the law**, manifests missing — note it, proceed, and offer `/essentials:slice-check --fix-manifests` at the end |
| `dk.trustworks.essentials` in the build file, none of the above | **Essentials, not on the law.** Say so once, offer `/essentials:slice-discover`, and make the change the way the surrounding code is written. Do **not** impose slice structure on one file of a layered codebase — a single sliced feature in a layered tree is worse than either consistently |
| None of the above | **Not this plugin's business.** Stop; add nothing to the conversation |

That last row is load-bearing. A change-request trigger that fires in unrelated repositories gets the
whole skill muted, which costs the projects that do need it.

## 2. Classify the request — five classes

Classification decides everything downstream, so do it explicitly and **say which class it is** before
touching a file. Getting this wrong in the safe direction (asking) costs a sentence; getting it wrong
in the unsafe direction produces a god class.

| Class | The request looks like | Route |
|---|---|---|
| **A — new capability** | An intent no existing slice handles: *"let support cancel an order"*, *"add a screen listing overdue invoices"*, *"react to X by doing Y"* | New slice — §3, then the kind skill by path |
| **B — extend one slice** | Another query over a model that exists, another field on a command, a changed invariant, a bug in one slice | In-slice change — §5 |
| **C — spans slices** | A rename crossing a boundary, a new field flowing from command to view, a policy affecting several slices | Decompose into per-slice B changes — §5.9 |
| **D — read-model shape change** | The view must project an event it does not project, or change shape | § Evolving a view slice — §5.3 |
| **E — not a slice change** | Wiring, config, build, the entity or its migration, a dependency bump, infrastructure | Do it plainly. No slice machinery, no manifest edit |

**When A and B are both arguable, ask.** The distinguishing question is never *"how big is the
change"* — it is §R1 for commands (is this a second command type?) and §R2 test 1 for views (does this
serve a different purpose needing a different read-model shape?). Put that question to the user in the
law's terms; do not resolve it silently.

## 3. Locate the owning slice from manifests, not from source search

The manifests are an index. Use them before grepping source — they are smaller, they carry intent, and
searching them is what keeps this procedure cheap on a large project.

```bash
q="uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py query ."

$q who-handles PlaceOrder             # which slice handles a command
$q who-serves ListOrders              # which view serves a query
$q who-owns-endpoint /api/orders/42/cancel --method POST   # exact route, then {var} template, then prefix
$q who-publishes OrderCancelled       # who publishes an event
$q who-reacts OrderCancelled          # who reacts to it: consumes AND projections[].from, so views too
$q who-dispatches CancelOrder         # which automation sends a command
$q who-writes Order                   # the sole-writer question (writes = aggregate, owns = read model)
$q who-reads OrderList                # readers of a read model, with their via: interface
$q slice orders.place_order           # one manifest, normalised
```

Names compare case-insensitively. Each hit names the slice, its kind, the field that matched and the
slice directory; `--json` gives the same as data. Exit 1 means no hit. A manifest that does not parse is
listed as "NOT SEARCHED — did not parse", never silently skipped, and a miss beside one is not proof of
absence. **`who-reacts` is the query to trust for "who reacts":**
a view declares its events in `projections[].from`, not `consumes`, so a search of `consumes` alone
misses every view.

Without `uv` (and without `pyyaml` for `python3`), the grep fallback for the same question must search
both fields:

```bash
grep -rn -A5 '^consumes:\|from:' --include=slice.yaml . | grep -i 'ordercancelled'
```

**If the manifests do not contain what the code contains, that is drift, and it is a finding.** Say so
in one line and offer `/essentials:slice-check`; then continue from the source. Do not quietly rely on
source search and leave the manifest wrong — the next session pays for it.

Read the located slice's `CLAUDE.md` before editing it. It carries the invariants, the boundaries, and
any deliberate divergence recorded as a decision — the three things most easily destroyed by an
outside edit that looks locally correct.

## 4. Establish the lane before changing write behaviour

The §R5 write style is a per-bounded-context property and it changes what a correct edit looks like.
Detect it per `slice-authoring.md` §1b — decider, aggregate, or service-entity — and **never migrate
between lanes as part of an unrelated change.** A lane migration is a design decision with a test
suite and a data migration attached; it is never a side effect of adding a field. `uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py . --bc <bc> --json` lists the
signals with `path:line` (`bcs[].lane`); its `service-entity?` still needs the "state loaded, mutated and
saved in place" check by hand.

If the BC shows two lanes, stop and report it. That is Blocking under §R5, and changing code inside it
deepens the violation.

## 5. The decision points

These are the judgements a template cannot carry. Each names the law section that binds; read it there.

### 5.1 A second intent is a second slice — never a second method

§R1 and §R2. *"Also let them amend the order"* is a new slice (class A), even when the code would be
three lines inside the existing decider. A `when (cmd)` / `switch (cmd)` over two command types is the
exact shape R1 forbids, and it arrives one innocuous request at a time.

On the service-entity lane the same rule applies to the handler: one `@CmdHandler` class carrying
methods for two command types is a router whatever it is called.

### 5.2 Another way to interrogate the same read model is the same slice

§R2's three-part test. Filtering, sorting, paging, lookup-by-id and field projections over one model
belong in that view's existing API file. Forcing each into its own slice produces either several
slices sharing one read model (an §R4 violation) or several read models projecting the same events.

Apply the cohesion smell as an advisory, not a veto: a view API accumulating methods with divergent
shapes is usually a read model doing two jobs — run test 1 before adding the next method.

A query that needs its own mapping on the **same route** — `@GetMapping(params = "status")` beside the
plain `@GetMapping` — is still this slice. Declare it as its own `endpoints` entry with the
`"<route>?status="` spelling and its own `serves` name (`manifest-guide.md` §3). Do not fork a view
slice over a filter.

### 5.3 The same model needing data it does not project is this slice, extended

§ Evolving a view slice, and this is the case most often mishandled. Needing one more event is
**ordinary evolution**, not a new slice. What is non-trivial is the rollout, not the structure:

- **In-place rebuild is the default** — add the handler, reset the subscription, replay.
- **A versioned twin is the exception** — only when the rebuild window is unacceptable. Then it is
  *one slice in two versions*: `_v2` declares `supersedes:` and starts `status: planned`, `v1` moves to
  `status: deprecated`, and **deleting `v1` is part of this change, not a follow-up ticket**.

**Lane check first:** on the service-entity lane none of this applies — there is no projection to
rebuild and no stream to replay, so a shape change is an ordinary schema migration (§ The read side on
this lane). Offering twin machinery there is noise.

### 5.4 A new event variant is a file, plus one sanctioned append

§R3. One variant per file in `<bc>/events/`. In Java, appending the name to the sealed parent's
`permits` clause is **the single sanctioned cross-slice edit** in this law — a declaration-list append,
not a change to another slice's decision-making. Kotlin needs no such edit.

Persisted events deserialize by the class name the event store records with each one, so a variant
needs no Jackson type metadata to be read back — and that class name is part of the stored data:
renaming or moving an event class leaves its persisted rows unreadable. A sealed or abstract type used
as a *field* inside an event is the case that does need `@JsonTypeInfo`
(`references/llm/LLM-kotlin-eventsourcing.md` § A sealed type used as a *field* inside an event needs
type info).

**Adding an event has consumers.** Before finishing, check `consumes:` across the manifests: an
automation or view that must react to the new event is a separate, declared change — not something to
discover in production.

### 5.5 Shared-logic pressure has one sanctioned outlet

§ Sanctioned sharing and § The `_shared/` promotion bar. The pressure shows up as *"both slices need
this"*. The answers, in order of preference: duplicate it (two similar deciders are not a design
problem), or — for `State` + `Evolver` **only**, and only at **three or more** consumers — promote to
`use_cases/_shared/`.

What is never the answer: a `…Helper`, `…Utils`, `…Service`, or a base class shared between slices.
That is the god class arriving under a different name.

### 5.6 Cross-context data goes through the public surface

§R4. A slice may import only another slice's or BC's `events/` and `types/` — never its decider,
evolver, state, handler, repository, or endpoint. A cross-BC read is declared in the manifest with
`reads[].via` naming a reader interface, and that is mandatory rather than stylistic.

The one exception is naming a command type in order to dispatch it on the command bus. Nothing in
`events/`, `entities/`, or `aggregates/` may name a command type at all.

### 5.7 Do not grow an adapter layer under a change request

§ The command and the view *are* the contract. Adding a field is a change to the command type or the
read model — not an opportunity to introduce a `…Request`, `…Response`, `…Mapper`, or `toDto()`. If one
already exists, the change does not have to delete it, but it must not extend it: say so in one line
and leave the removal as an explicit offer.

### 5.8 Repository surfaces do not widen quietly

§ Spring Data repository surface. A change that needs one more query adds one more declared method to
an interface extending the bare `Repository` marker — it does not switch the interface to
`JpaRepository` for convenience, does not return the mapped entity where a closed projection belongs,
and does not name the new method after a CRUD base method (`findById` is captured by the base, silently
discards the declared projection type, and fails as a `ClassCastException` at the call site).

### 5.9 A change spanning slices is several changes, in order

Decompose it and state the order before starting: producer first (the event), then reactors (views,
automations), then the API surface. Do the pieces as separate edits with separate manifest updates. A
cross-slice change that produces one shared abstraction to "keep it DRY" has re-created the god class
this law exists to prevent.

### 5.10 Retirement is a state transition, not a deletion

A slice that is going away moves to `status: deprecated` while consumers migrate, and is deleted — with
its directory, its manifest, its tests and its `@Bean` — as part of a change that names the deletion.
A `_v2` outliving its `v1` by more than a release is the failure mode § Evolving a view slice exists to
prevent.

## 6. Keep the manifest true — mandatory, not tidy-up

Every class-A–D change has a manifest consequence, and an undeclared one is drift by definition (§R2:
*"an endpoint absent from the manifest is drift"*). Update `slice.yaml` in the **same change** as the
code:

| What changed in code | Field to update |
|---|---|
| New/changed endpoint | `endpoints[]` — **with the path quoted** — and `serves[]` for a view query |
| New command type handled | `handles[]` |
| New event emitted | `publishes[]` on the producer |
| A slice now reacts to an event | `consumes[]` — **but on a view it is `projections[].from`**, which is where a projector's events are declared (`manifest-guide.md` §3). Adding one `@MessageHandler` and forgetting this is the most common manifest drift there is |
| New write target | `writes[]` / `owns[]` |
| New read, especially cross-BC | `reads[]` — with `via:` when it crosses a context |
| Projection added or reshaped | `projections[]` |
| New/changed invariant | `invariants[]` |
| Test added | `tests.{unit,integration,contract,e2e}` |
| Retirement or twin | `status`, `supersedes` |

**Adding a path variable to an existing endpoint is the moment this breaks.** `path: /api/orders`
parses unquoted; edit it to `path: /api/orders/{orderId}/cancel` and the flow mapping's `{` opens a
nested mapping and the manifest stops being YAML — silently, because no JVM build reads it
(`manifest-guide.md` §3). Quote every path, every time. After editing any `slice.yaml`, confirm it
still parses.

Then the slice's `CLAUDE.md`, when the change touched an invariant, a boundary, or introduced a
deliberate divergence — that file is where a divergence reads as a decision instead of as habit.

Do **not** stamp `lastSyncedAt`: that field belongs to an external generator's sync loop, and manifests with
`generator: essentials` have no periodic sync to stamp it (`manifest-guide.md` §1).

## 7. Verify what the change touched — not the whole project

Re-read `rules/slice-design.md` § Red flags and confirm no structural entry now applies. Then run only
the gates the change class implicates:

| Class | Gates worth checking, by name |
|---|---|
| A — new slice | Layout, id uniqueness, manifest schema, wiring, handler shape |
| B — extend | Manifest-to-code agreement, R1/R2 shape, repository surface |
| C — spans slices | R4 boundary, sole-writer, manifest agreement on every slice touched |
| D — read-model change | Duplicate read model, `supersedes` pairing, projection idempotency |

Offer the full `/essentials:slice-check` rather than running it: eighteen gates over a whole project is
a different task from verifying one edit, and running it uninvited buries the change's own report.

Where the project has tests, run the touched slice's test — the plugin's own bar is that wiring is part
of done, and an unrun test is not evidence.

## 8. Hand off rather than improvise

| Situation | Command |
|---|---|
| Whole-BC restructure, or many drifted manifests | `/essentials:slice-check --fix-source` / `--fix-manifests`, following `references/slice/manifest-reconciliation.md` — which also covers reconciling a whole project kind-by-kind, and what must never be regenerated |
| The project is not on the law | `/essentials:slice-discover` |
| *"What is here / how does this connect / where is X?"* | `/essentials:slice-map` |
| A new project | `/essentials:init` |
| An architectural decision (event sourcing? which lane?) | Not a change request — stop and raise it with the user. The lane is a per-BC design decision (`rules/slice-design.md` § R5 — use a standard Essentials design, per language); record it as an ADR before any code moves |

## 9. Guard rails

- **Confirm before scaffolding.** The user described a change in prose; they did not ask for six files.
  State the classification, name the slice(s), and get a yes. This is the whole difference between a
  helpful default and an unwanted one.
- **Never merge a new capability into an existing slice directory.** Class A goes through the kind
  skill, which aborts on an existing directory by design.
- **Never re-elicit inside a kind skill.** Resolve and pass its inputs, per `slice-authoring.md` §2–§4.
- **Never invent an Essentials API.** Verify against `references/llm/` — a fabricated signature looks
  authoritative and fails at the user's compiler.
- **Never widen `.claude/rules/essentials-slices.md`.** It is a 35-line pointer by design; the law lives
  in the plugin so updates propagate.
- **Never write outside the project**, and never create a plugin-owned runtime directory.
