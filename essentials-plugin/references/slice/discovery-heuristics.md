# Slice discovery heuristics

The inference rules behind `/essentials:slice-discover`. The command body carries the procedure;
this file carries the judgement, so neither grows past its budget.

**Everything here produces a *candidate*, never a fact.** `slice-check` audits code against a
declared structure and its findings are contradictions. This command has no declaration to check
against — it guesses, from evidence of varying strength, and the output is only as useful as its
honesty about that. A confident wrong boundary is worse than no boundary: it gets adopted.

## 1. The evidence ladder — bounded contexts

Strongest first. When two signals disagree, the higher one wins and the lower one becomes
*counter-evidence* to report, not noise to drop.

| # | Signal | How to read it | Strength |
|---|---|---|---|
| 1 | **Human declaration** | `.refac/workshops/bounded-contexts.yaml` in the optional `.refac/` graph an external modernization tool writes, or a domain model from a discovery workshop. Someone wrote this down on purpose | Decisive — adopt, do not re-derive |
| 2 | **`.refac/` BC nodes** (optional external graph) | `.refac/graph/**` frontmatter with `inferred-from`/`confidence`. **Module-granular** — see §2 | High, as a prior |
| 3 | **Entity write-ownership** | Which types are mutated by which components. A cluster of types written only by a cluster of components is the strongest code-level boundary there is, because it is the consistency boundary | High |
| 4 | **Transaction scopes** | `@Transactional` methods that consistently span the same type set. A transaction is a claim about what must stay consistent together | High |
| 5 | **Datastore ownership** | Tables/collections written from one cluster; FK clusters | Medium |
| 6 | **Type-reference graph** | Strongly-connected components over domain types, ignoring framework types | Medium |
| 7 | **Package names** | A hint, and the **weakest** signal available | Low — see below |

**Why package names rank last.** On the codebases this command exists for, packages *are* the
problem: `controller/`, `service/`, `repository/` collapse every domain into one apparent context,
and per-layer packages split one domain into several. Weighting them highly reproduces the layering
the report is supposed to see through.

**Every candidate context reports:** name, owned types, the signals that support it with their rung,
a confidence, and **what argues against it**. That last field is not optional. If it is empty,
either the evidence is genuinely unanimous — rare — or it was not looked for, and an unfalsifiable
candidate cannot be ranked against anything.

**Saying no is a valid result.** *"No discernible context structure; here is what was looked at and
why it was inconclusive"* is a legitimate outcome and must stay available. Manufacturing a boundary
to have something to report is the failure mode that discredits the whole command.

## 2. Reconciling with `.refac/` — different granularity, not disagreement

`.refac/` is an optional graph written by an external modernization tool. Its bounded-context nodes
carry a `realised-by` that lists **modules**. It answers
*"which modules form a context"* across an estate. This command works **inside** a module, over
types. They stop and start at different scales.

So a `.refac/` context and a slice-discover context are not competing answers, and **differing counts are
not a conflict**. The graph naming four contexts across an estate while this run finds two inside one
module is two correct answers to two questions. Report them as complementary; framing it as a
disagreement is a defect.

**Precedence** when more than one source names a context: human-declared → `.refac/`-inferred →
inferred here. Never overwrite a higher rung; compare against it and report the delta.

**The adapter is deliberately narrow.** Read `.refac/graph-index.json` and BC-node frontmatter only.
Never require them. Degrade silently to standalone inference when absent — `essentials` must work
without that graph. **Never write to `.refac/`.**

## 3. Classifying — the lane first, then the four kinds

### 3.1 Lane detection

The §R5 write style is a **per-BC** property, and it decides which findings in §4 are even meaningful.
Detect it first, per bounded context, never per repository.

| Evidence | Lane | Strength |
|---|---|---|
| `Decider` / `EventStreamDecider` implementations | decider | decisive |
| `AggregateRoot` / `FlexAggregate` / `StatefulAggregateRepository` | aggregate | decisive |
| Essentials command bus + `EventBus` + `@Entity`/`@Document` behind a Spring Data repository, and **no** `EventStore` / `AggregateType` reference | **service-entity** | decisive |
| An entity/repository pair **and** an event store in one BC | **conflict — report it, do not pick** | — |
| None of the above | unclassified — say so | — |

**On-lane and nearest are different claims, and conflating them is the failure mode this table exists
to prevent.** A BC is *on* the service-entity lane only when Essentials is actually there — the
command bus, the `EventBus` — because that is what `rules/slice-design.md` §R5 governs. Layered
Spring/JPA code with no Essentials at all is **not on any lane**; it is *nearest* to service-entity,
which is a statement about where it would migrate, not about what it already is. Report it as
`lane: none (nearest: service-entity)` and let the ladder's rung 4 offer it as a destination.

The **conflict** row matters as much as the positive ones. A project may legitimately event-source one
BC and state-store another; that is two BCs on two lanes, not a conflict. A conflict is *one* BC
holding both, and the correct output is to name both signals and stop — picking one silently is how a
discovery report starts inventing architecture.

### 3.2 Classifying a path into one of the four kinds

Work from entry points inward. An entry point is an HTTP mapping, a message/queue handler, a
`@Scheduled` method, or an event listener.

| Kind | Signal | Boundary case |
|---|---|---|
| **command** | The path reaches a write — `save`, `persist`, `merge`, `@Modifying`, an event append | One slice per *intent*, not per method. Two methods enforcing the same invariant on the same type are one slice |
| **view** | Read-only path, no write anywhere downstream | **Group by returned shape, not per endpoint** — see below |
| **automation** | Triggered by schedule or event rather than by a caller, and writes or issues further work | A listener that only reads is a view with an odd trigger; a listener that writes another aggregate is the automation case |
| **translation** | Crosses a system boundary carrying a **foreign schema** — a snake_case DTO, a wire enum, an external id | Direction does not matter; an inbound adapter is as much a translation as an outbound client |

**Grouping views is where naive implementations fail.** R2 scopes a view slice by the read model it
owns, so `list()`, `getById()`, `filterBy…()` returning the same shape over the same model are **one
slice with several queries**. Emitting one slice per endpoint reproduces the god-controller as a fan
of slices. Conversely, an endpoint over the same entity returning a *different* shape — an
aggregation, a summary, a different grain — is a **different read model** and therefore a different
slice. Same entity is not same shape.

**Most brownfield code already has slices**, smeared across layer packages. Say this in the report.
It reframes the work from *rewrite* to *regroup*, which is most of the difference between a proposal
that gets adopted and one that gets filed.

## 4. Findings — ranked by payoff, not by the law's severities

Nothing can be **Blocking** in code that never opted into the law. Reporting it that way is preachy,
and it is why audit tools get switched off. Rank by payoff instead:

| Rank | Finding | Why it ranks here |
|---|---|---|
| 1 | **Sole writer** — one type written from K independent components | The only finding that maps to a real bug class (concurrent writes to one consistency boundary) rather than to taste. Mechanically derivable, so it is also the most defensible |
| 2 | **Cohesion** — an entry point or service spanning N slices | Report as *"splits into N slices"*, never as a line count. Ownership is the test; length is a symptom (§ File cohesion) |
| 3 | **Boundary** — a component reaching into another candidate context's types or repositories | The R4 shape, stated as a cost rather than a violation |
| 4 | **Lane** — which R5 lane the BC is on, or which it is nearest, and whether it is consistent | A BC already deciding through an `AggregateRoot`, or through the command bus onto a state-stored entity, is on a **sanctioned** lane (§R5). Say so. Telling either to adopt deciders is wrong. Report `lane: none (nearest: X)` where nothing is sanctioned yet (§3.1), and report a single BC holding two lanes as a conflict rather than picking one |

Within a rank, order by blast radius: how many slices the fix touches.

§7's lane findings and §8's primitive-id finding are reported **after** these four, never above them.

## 5. The ladder — every rung pays off alone

1. **Regroup** into per-feature packages. A pure move: no behaviour change, no framework adoption, no
   new dependency. Reviewable as a rename.
2. **Split** god entry points; one API file per slice. This is also where §8's semantic ids enter:
   each per-slice API file gets a signature written fresh, so it takes the BC's id types from
   `<bc>/types/` rather than a `String`. A plain Java record or final class, or a Kotlin value class,
   is enough — no Essentials. Adopting Essentials' `CharSequenceType` for them belongs to rung 4.
3. **Add manifests** — `slice.yaml` + per-slice `CLAUDE.md`.
4. **Adopt** a sanctioned §R5 lane. Three destinations, and the right one depends on whether the BC
   needs to reconstruct state from history:
   - **Service-entity** — formalise the existing entity into `entities/`, split the god handler, move
     the read side off the write repository. **No event store, no replay, no new persistence
     dependency.** This is the shortest rung for state-stored code and the one to offer first when
     §3.1 reported `nearest: service-entity`.
   - **Aggregate** — formalise an existing `AggregateRoot` into the `aggregates/` lane.
   - **Decider** — adopt Essentials deciders and the event store. The largest step; propose it only
     when the BC actually needs history.

**Rung 3 is the handoff.** Once manifests exist, `/essentials:slice-check` takes over permanently and
this command has no further role. Say that in the report — a discovery tool that does not name its
own exit is one that gets re-run forever.

**A ladder whose first rung mentions `Decider`, `AggregateRoot`, or the event store is inverted.**
Nobody adopts a proposal that opens with a rewrite. Rung 4 is where the framework enters, and reaching
it is optional — rungs 1–3 leave a strictly better codebase even if the team never adopts Essentials.

**Never present rung 4 as "adopt event sourcing".** It is false — the service-entity lane is not
event-sourced — and stating it costs the proposal exactly the audience that already decided against a
stream. For state-stored code the service-entity rung is a regroup plus a
split — no event store enters the picture at any rung.

## 6. Confidence rubric

| Level | Means |
|---|---|
| **high** | Rung 1–2 evidence, or rungs 3+4 agreeing with no counter-evidence |
| **medium** | Rung 3–4 evidence with counter-evidence present, or rungs 5–6 agreeing |
| **low** | Rung 5–7 only, or signals in conflict |

Report the level *and* the rung it came from. A bare "medium" is unauditable — a reader cannot tell
whether it means "two strong signals half-disagree" or "the package names looked plausible".

## 7. Service-entity findings

Only meaningful once §3.1 put the BC on — or nearest to — the service-entity lane. Each ranks under
§4's payoff scheme; none is Blocking, because this code never opted into the law.

| Finding | Signal | Rank |
|---|---|---|
| **Write-repository query drift** | Finders on the entity's repository (`findByStatus`, `findAllIds`, `findByIdIn`) whose callers are controllers or read paths, not the write path | 1 — it is the sole-writer finding's read-side twin: one interface serving two masters, and the finders grow without bound |
| **Command-type leakage** | A type in the entity/domain package or in an events package taking a command type as a constructor or factory parameter | 1 — mechanically detectable, and in an events package it puts a slice-private wire contract into the BC's importable surface |
| **Entity returned from an API** | A `@RestController` method whose return type is the `@Entity`/`@Document` | 2 — the whole write model becomes the wire contract, and the caller gets a managed mutable object |
| **God handler** | One `@Service`/`@Component` carrying handler methods for two or more command types | 2 — report as *"splits into N slices"*, per §4 rank 2 |
| **Bypassable invariant** | A public setter writing a field that an invariant method also guards | 2 — lane-specific: `AggregateRoot` has no setters, so this defect exists only where an ORM pushed them in |

**Two traps — do not report either.** A getter whose only callers are the ORM and `toString()` is
persistence machinery, not a query surface; distinguish by **caller**, never by shape. And a
repository finder used only by the write path (loading an entity to mutate it) is the repository doing
its job — the finding is a finder serving a *read* path.

## 8. Primitive ids at the domain edge

Lane-independent: it applies whatever §3.1 reported, `none` included. **Low payoff and unranked** — it
is reported after §4's four and §7's findings, never above them, and never with a severity.

| Signal | Report |
|---|---|
| An identifier of a type the BC owns or references — an entity's `@Id`, an `accountId`, a `shipmentId` — typed `String`, `Long`/`long`, `Integer` or `UUID` in a controller signature (`@PathVariable String accountId`), a service method (`transfer(String fromAccountId, String toAccountId, …)`) or an entity field (`Shipment.accountId : String`) | **Once per BC**: the ids involved and roughly how many sites carry each. Not one finding per parameter — a list of forty `String` parameters buries the sole-writer finding it ranks below |

The target is `rules/slice-design.md` § Directory vocabulary (`types/` holds the BC's ids) and
§R2's "the command and the view *are* the contract", where a semantic id as the `@PathVariable` is
the default shape to write. The payoff is compile-time: two adjacent `String` parameters can be
swapped at a call site and nothing notices. It is real but small next to a concurrent write, which is
why it ranks last.

The fix sits at §5 rung 2, not rung 1: retyping a signature is not a pure move. And a typed id must
keep the wire and the column as they are — a string stays a string on the path, in the JSON and in
the table — so check how the web binder, the JSON mapper and the ORM read the new type before calling
the rung behaviour-free.

**Two traps — do not report either.** A field in a translation's DTO typed by a **foreign** schema
(a snake_case `external_ref` or `transaction_id` the external system owns) is that system's contract, not this
BC's id: §3.2's translation slice maps it to the BC's type at the boundary, and typing the DTO itself
re-couples the BC to a schema it does not control. And a `String` that is not an identifier at all — a
free-text comment, a status, a token passed through to an external system — is outside this
finding; a status that wants to be an enum is a different observation.
