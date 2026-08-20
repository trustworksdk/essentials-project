# Presentation Plan — Showcasing the Essentials Framework

**Audience:** colleagues, mixed experience, mostly new to event sourcing
**Slot:** 30–45 minutes (planned for 40: 32 minutes of content + 8 minutes Q&A)
**Deliverable:** one self-contained bilingual HTML deck, plus speaker notes and a demo runbook

## Revision — review round 1

Changes made after the first review of the deck:

| Feedback | Change |
|---|---|
| Encoding was wrong | The file was always valid UTF-8, but declared no charset, so browsers fell back to a legacy encoding and every `æ`/`ø`/`å` and `…` rendered as mojibake. `<meta charset="utf-8">` added as the first line. The three literal `…` characters are gone as well. |
| Slides needed more explaining text | Added a newcomer-gloss device — a plain-language restatement of the concept, used on 8 slides where the idea is genuinely new: what an aggregate is and what event-sourced means, why ordering is never a timestamp, what a projection is, what a snapshot actually saves, the accounting analogy for closing books, why a fence token beats a lock, and what the dual write is. Not applied to every slide, so it stays a signal rather than a pattern. |
| Too much focus on snapshots and closing books, one slide each | They were already one slide each in Act 3, but the demo also spent 3 minutes on them, which made two secondary mechanisms the centre of the talk. Both Act 3 slides are now trimmed to one minute and kept conceptual, and the demo segment is gone. |
| Skip slide 24 | Removed — that was the snapshots-and-generations demo. Its endpoints are preserved in `demo-script.md` under **Retired segment** for a longer session or a Q&A answer. Act 4 is now two slides and three segments, 4.5 minutes instead of 8. |
| Starter names on slide 20 were weird | They were abbreviated with a leading ellipsis, which is also where the mojibake showed. Now spelled out in full: `spring-boot-starter-postgresql`, `spring-boot-starter-postgresql-event-store`, `spring-boot-starter-mongodb`, `spring-boot-starter-admin-api`. |
| The cost box on slide 20 is not correct | Removed, along with the whole trade-off block on that slide. The space now explains what the declarations bean actually does. **Worth knowing:** the claim came from the repository's own `CLAUDE.md` and the trading demo's `CLAUDE.md`, both of which still state that an undeclared aggregate leaves the policy inert with nothing erroring. If that is no longer true, those two docs are stale and worth correcting at the source. |

Knock-on corrections found while making these changes: the per-slide minute budgets summed to 40, not the
32 the act table claimed. Budgets are now set so they sum to the stated 34.5 minutes, the title slide and
the on-screen timer say 35 rather than 40, and the agenda table matches.

## Revision — review round 4

Two fixes, both from the same observation: the appendix was hard to find, and it was in the wrong place.

**The external-approval answer moved into the flow, as requirement three (slide 9).** It was an appendix
slide; it is now the third beat of Act 1. That gives the act its real shape — three requirements arriving in
escalating difficulty, each landing somewhere different:

| Requirement | Where the rule lands | Slides |
|---|---|---|
| Register an instrument | inside the aggregate — one consistency boundary | 4–6 |
| No two instruments share a symbol | in the handler, against a read model — a guard, not a guarantee | 7–8 |
| An external service must approve it | in an automation, after the transaction commits | 9 |

That is the complete slice taxonomy — command slice, view slice, automation slice — taught through
requirements rather than through a diagram of the framework. The slide also gained a gain/cost box, because
the deck's own rule is that every mechanism gets a trade-off, and a flow slide without one broke it.

What stayed in the appendix is the part that is genuinely on-demand: the **four tempting wrong answers**, the
process-manager variant, the webhook variant, and the question to put back to the business. One slide now,
not two.

**Appendix discoverability.** It was reachable only by a keystroke nobody knew about. Now: an `APPX` button
in the top-right corner, a pointer on the closing slide, `A` still works, and `End` goes to the closing slide
rather than sailing past it into the appendix.

Rebalanced again to hold 36 minutes: Act 1 is 11 minutes over seven slides, Act 3 came down to 10.5 with the
component slides at a minute each, and the hook went from 1.5 to 1. 30 flow slides plus one appendix slide.

## Revision — review round 3

Added the prepared answer to a question the room will almost certainly ask: *an external risk service has to
approve the instrument first — how, without a blocking HTTP call inside a database transaction?*

Two **appendix slides**, sitting after the closing slide and outside the timed flow. The rail numbers them
`A1 / A2` instead of continuing the slide count and the progress bar stays full, so walking into them
mid-Q&A does not skew the presenter's sense of position. `A` jumps there in one keystroke.

The answer is entirely framework-native, which is why it is worth having ready:

- The instinct is right, but the usual justification is the weak one. Resource holding — a connection and the
  aggregate's locks pinned for the whole round trip — is real but tunable. The load-bearing reason is that
  **a rollback cannot un-call an external service**, and a retried transaction calls it twice. So the
  transaction has to end before the call happens.
- `EventProcessor` is purpose-built for this: `LLM/LLM-postgresql-event-store.md` lists its role as
  *external integrations, long ops*. It is Inbox-backed, exclusive via a `FencedLock`, retried on a
  `RedeliveryPolicy` you set, dead-letters when the dependency stays down, and exposes `getCommandBus()`.
- The deadline is a **delayed command** — `getCommandBus().sendAndDontWait(cmd, Duration.ofMinutes(15))` —
  not a cron job or a lock-guarded sweeper. The handler re-checks state before acting, which is what makes
  redelivery safe.
- The result returns as a command in a second transaction, so the aggregate's state machine completes
  normally: `PENDING_RISK_APPROVAL` → `ACTIVE` or `REJECTED`.
- Slide A2 carries the four tempting wrong answers. The subtle one is `afterCommit` /
  `TransactionSynchronization`: correct side of the commit, still loses the call, which is exactly the hole
  an inbox fills — so it lands as a callback to component 09 rather than new material.
- The domain sentence: **if a decision takes time, the waiting is part of the domain.** And the question to
  put back to the business: does "approved before" mean before *tradeable*, or before the HTTP response?

Like slides 7–8, the code here is proposed rather than extracted, and labelled as such on the slide.

## Revision — review round 2

Extended the running example: `register_instrument` gains a second invariant, INV-RI-3 — *no two
instruments may trade under the same symbol* — enforced in the handler against the `instrument_details`
view. **Deliberately not implemented in code**; it exists only in the deck, as the next requirement.

Two new slides in Act 1, both labelled *proposed — not in the repository yet* on the slide and in the
speaker notes, so nobody goes looking for `SymbolAlreadyRegistered` afterwards:

- **Slide 7 — where the check lives.** The `slice.yaml` grows first (`INV-RI-3`, and `reads:` stops being
  empty), then the handler. The lesson is why the aggregate cannot enforce it: an aggregate is a
  consistency boundary, so it sees its own stream and nothing else, and a rule about the whole *set* cannot
  live inside one member of it. The `filter` on the check preserves INV-RI-2, so a retried registration is
  still idempotent. Names the textbook term, set-based validation.
- **Slide 8 — the guard is not a guarantee.** The four-timestamp race between two instances whose
  projection has not caught up, why reading harder cannot close it, and three honest ways out: detect and
  repair; make the symbol an aggregate id so uniqueness becomes a single-aggregate invariant again; or
  serialize registration behind a `FencedLock`. Closes on *choose per rule, not per system*.

Why this earns its 2.5 minutes: it is the question the room will actually ask, it introduces the read model
in Act 1 so Act 3's projection slide lands as a callback, and it forward-references `FencedLock`. It also
makes Act 1 the largest act in the deck, which is the right shape for an audience new to event sourcing.

Rebalanced to keep the total honest: Act 1 is now 9.5 minutes over six slides, the `Instrument` slide went
from 4 to 3.5 and the event-store slide from 1.5 to 1. 29 slides, 36 minutes, and the title slide and
on-screen timer both say 36.

Two things to note:

- `snippets/MANIFEST.md` now has a section recording that these two snippets are the single exception to
  "everything on a slide is extracted from real code". If the rule is ever implemented, add the real files
  to `extract.sh` and drop the *proposed* caption.
- Having the handler read a view makes that view slice's query part of its public surface. That is a real
  coupling decision rather than a free one, and it is in the speaker notes as an answer if somebody asks.

## Status

| Step | State |
|---|---|
| 1. Lock scope and per-slide minute budget | done — budgets are in the deck's bottom rail and the run-of-show table in `NOTES.md` |
| 2. Extract snippets verbatim | done — `snippets/extract.sh` copies 15 files from the demo; framework excerpts are hand-maintained in `snippets/90-framework-snippets.md` |
| 3. Act 1 event-model strip | done — inline SVG on slide 4 |
| 4. Deck skeleton | done — 28 slides |
| 5. English content | done |
| 6. `demo-script.md` | written, **not yet executed** — running it end to end is what produces the recordings |
| 7. Rehearse against a clock | **outstanding** |
| 8. Danish strings and `NOTES.da.md` | done — brought forward, because the English content settled in one pass rather than over several edits |
| 9. Publish as an artifact | done — https://claude.ai/code/artifact/0fd9c556-3322-4417-a047-dec913c62e51 |

Three things remain, all of which need a machine with Docker and a screen recorder:

1. Execute `demo-script.md` end to end from a wiped database, and capture the four recordings.
2. Pre-bake the `delist_instrument` branch and record the AI segment.
3. Rehearse against a clock, then cut. Act 3 slides 16 and 19 are marked as the first cuts.

## Decisions Taken

| Decision | Choice | Why |
|---|---|---|
| Deck format | Single self-contained `deck.html`, no build step | The repository bans Node/JavaScript build dependencies. Vanilla JS and inline CSS keep the deck consistent with that rule, and the file can be published as a shareable artifact. |
| Languages | English and Danish in one file, toggled at runtime | One file and one link, so the two versions cannot drift apart. English is the edit source of truth; Danish translates prose only. |
| Untranslated | Code, identifiers, `slice.yaml` keys, module names, endpoint paths | These appear verbatim on screen and in the codebase. Translating them would make the slides disagree with the demo. |
| AI live-build segment | Pre-baked branch plus a short recording | Live code generation followed by a Maven rebuild is the highest-variance five minutes available. The recording delivers the same message with no risk. |
| Depth calibration | Act 1 gets generous time; a plain-JPA contrast slide is included | The audience is mostly new to event sourcing, so the modelling story has to land before any component detail is useful. |

## Files To Produce

```
presentation/
  PLAN.md            this file
  deck.html          bilingual deck, language toggle, no build step
  NOTES.md           English speaker notes, per-slide timing
  NOTES.da.md        Danish speaker notes
  demo-script.md     exact commands, expected output, reset procedure (English only)
  snippets/          the code excerpts, extracted verbatim from real sources
  recordings/        fallback screen captures for every live demo
```

The module-landscape slide reuses `images/essentials-modules.png`, which already exists in the repository.

## Narrative Spine

### Act 0 — The hook (2 minutes, 1 slide)

A business requirement arrives, is implemented as a layered CRUD application, and the reasoning behind
every row is gone. Only current state survives. Frame the rest of the talk as: Essentials keeps the
decision trail as the system of record.

### Act 1 — DDD, event modelling, and slicing (7 minutes, 4 slides)

The requirement: *the market data team registers instruments.* Follow that one requirement from a
whiteboard into files on disk.

1. **Event-model strip.** Swimlane showing `RegisterInstrument` (command) → `Instrument` (aggregate, and
   therefore the consistency boundary) → `InstrumentRegistered` (event) → `instrument_details` (view).
   This is the standard event-modelling shape, drawn as inline SVG or Mermaid.
2. **The slice contract.** `market_data/use_cases/register_instrument/slice.yaml`, shown as the real file.
   It declares `handles`, `publishes`, `writes`, `endpoints`, and the two invariants INV-RI-1 and
   INV-RI-2. The point: the requirement is machine-readable and lives beside the code that satisfies it.
3. **The slice on disk.** Four files — `RegisterInstrument`, `RegisterInstrumentHandler`,
   `RegisterInstrumentAPI`, `slice.yaml`. Packaged by slice, not by layer.
4. **The bounded-context boundary.** In `market_data`, only `events/` and `types/` are importable;
   `aggregates/` is private to the context. `brokerage` imports exactly two things: `InstrumentId`, and
   the price events that `brokerage.trade_valuation` projects into its own read model. Cross-context
   reads project the other context's events rather than calling into its write side.

### Act 2 — One aggregate, up close (5 minutes, 2 slides)

1. **`Instrument.java` in full.** The file is 108 lines and fits on a slide. Points to make:
   - methods call `apply(...)`; they never assign state
   - the `@EventHandler` methods are the only place fields are ever written
   - the same handlers run on rehydration, so replaying history and handling a new command follow one path
   - both mutating methods are idempotent no-ops: renaming to the current display name, or suspending an
     already-suspended instrument, applies nothing, so a retried command leaves no trace in the stream
   - `InstrumentId` and `Symbol` are semantic types, not `String`
   - it is the demo's deliberate baseline: no snapshots, no closing books, so Act 3 has something to
     compare against
2. **Contrast slide.** The same domain as a JPA entity with setters. List the questions you can no longer
   answer afterwards: when was it suspended, who renamed it, what did it look like last quarter.

### Act 3 — The components, with trade-offs (12 minutes, 11 slides)

One slide each: six to ten lines of real code, and one honest trade-off line. This act is the pressure
valve if the clock slips — drop slides 6 and 9 first.

| # | Component | Micro-example | Trade-off to state out loud |
|---|---|---|---|
| 1 | `types` — `SingleValueType` | `InstrumentId`, `Symbol` | Compile-time safety against boilerplate, plus one integration module per framework (Jackson, JDBI, Spring Web, Mongo). |
| 2 | `UnitOfWork` | The command bus owns the transaction; handlers carry no `@Transactional` | Less ceremony, but the boundary is implicit and you have to know who owns it. |
| 3 | `postgresql-event-store` | `EventOrder` versus `GlobalEventOrder`; ordering never comes from timestamps | Strong ordering guarantees, but no ad-hoc SQL over current state. |
| 4 | `eventsourced-aggregates` | `StatefulAggregate` versus `Decider` | Object-oriented ergonomics against pure-function testability. The demo is entirely on the aggregate lane, deliberately. |
| 5 | Projections and `EventProcessor` | `instrument_details` (eventually consistent) beside `latest_price` (aggregate-backed, strongly consistent) | Read scaling against eventual consistency. The demo has both, and the reason `latest_price` is the exception is a good story: the bootstrap's idempotency probe cannot tolerate a stale "absent". |
| 6 | Snapshots | `@AggregateSnapshotPolicy`, `ASYNC_DURABLE`, every 100 events on `InstrumentPrice` | Faster loads against serialization cost and a snapshot format you now have to keep readable. |
| 7 | Closing the books | `TradingAccount` and its `<logicalId>#<generation>` stream ids | Bounded stream growth against a two-id model and archival plumbing. |
| 8 | `DurableQueues` and `FencedLock` | A short consumer; a lock with its fence token | One datastore, transactional with your own writes, no extra infrastructure to operate — against a throughput ceiling, load on the database, and the hard constraint that this is intra-service coordination only. |
| 9 | Inbox and Outbox | An outbox around an outgoing message | At-least-once delivery, so downstream idempotency is mandatory, not optional. |
| 10 | Spring Boot starters | `spring-boot-starter-postgresql-event-store` | Wiring for free against strong opinions. Name the real gotcha: a snapshot or closing-books policy annotation is inert unless the aggregate is declared in an `EssentialsAggregateDeclarations` bean — nothing errors, the policy simply never runs. |
| 11 | Admin API and console | Screenshot, then the live console in Act 4 | Operational visibility, essentially free once the starter is on the classpath. |

### Act 4 — Live demo (8 minutes)

Detailed in `demo-script.md`. Four segments, described below.

### Act 5 — Where it does not fit (3 minutes, 1 slide)

The credibility slide. Say all of it:

- Fenced locks, queues, inbox and outbox coordinate instances of *one* service against a shared database.
  They are not cross-service infrastructure.
- Integrations are `provided` scope. Consumers declare their own third-party dependencies.
- An event's persisted type is its concrete class name. The demo's own README carries the war story: the
  slice refactor renamed every event class, so an existing demo database has to be wiped. Renames cost
  either an upcaster or a migration.
- Table and collection names are concatenated into queries. Validate them, or hard-code them.
- Two Jackson flavours share one wire format. Upgrades require running both build profiles.
- High-frequency writes are genuinely more expensive through an aggregate. Show the price-path comparison
  and note that the measurement is biased *against* event sourcing, because command dispatch sits inside
  the timed window on the aggregate path and the direct-write path is unchanged.

### Act 6 — How to start (2 minutes, 1 slide)

`/essentials:init` scaffolds a project. `LLM/LLM.md` is the documentation entry point. Per-slice
`CLAUDE.md` and `slice.yaml` files are what make the codebase navigable by both new colleagues and AI
tooling — which is the bridge into the recorded AI segment.

## Live Demo Runbook

Pre-flight, well before the room fills:

```bash
docker compose down -v && docker compose up -d
mvn -q -pl examples/essentials-trading-demo -Dspring-boot.run.profiles=compose spring-boot:run
```

Wait for bootstrap to finish seeding. Never build during the presentation. The `run-trading-demo` skill
already automates launching and driving the app.

**Segment A — a slice round trip (2 minutes).**

```
POST /api/admin/instruments                       # the RegisterInstrument command is the request body
GET  /api/admin/instruments/{instrumentId}        # projection-backed, so eventually consistent
POST /api/admin/instruments/{instrumentId}/name?displayName=…
GET  /api/admin/instruments/{instrumentId}        # renamed
GET  /api/admin/trading-accounts/ACC-DEMO-001/generations/1/events
```

The last call is the one that lands: the audience sees the raw event trail, which is the whole argument of
Act 0 made concrete.

**Segment B — snapshots and closing books (3 minutes).**

Open `/admin`, note the initial generation counts and snapshot counters, then:

```
POST /api/admin/load-generator/burst/trade-lifecycles?count=500
```

Refresh and compare. Bootstrap has already left three accounts in three different states, one per
mechanism: `ACC-DEMO-001` was rolled by the closing-books policy with no application involvement,
`ACC-DEMO-002` by an explicit command on the command bus, and `ACC-DEMO-003` was left alone as the
baseline. Three mechanisms side by side, no setup required.

**Segment C — the cost of event sourcing (2 minutes).**

```
POST /api/admin/load-generator/comparisons/price-path?count=100
```

Show the Price Path Comparison panel on `/admin`. Aggregate-event-sourced against direct-write, measured
in the app they are looking at. Repeat the point from Act 5 that the comparison is biased against event
sourcing.

**Segment D — the admin console (1 minute).**

Aggregates → Aggregate lookup, showing the three demo accounts and their policies. Then the queues and
locks pages.

**Fallback.** Record all four segments as short screen captures into `presentation/recordings/`. If
anything is red, play the video and keep talking. In a 40-minute slot this is not optional.

## AI Segment (recorded, ~2 minutes inside Act 6)

Pre-bake a branch that adds a `market_data/use_cases/delist_instrument` command slice: a new
`InstrumentDelisted` event, an aggregate method, a handler, an API file, and a `slice.yaml`. It mirrors
`suspend_instrument` closely, so it is small, self-contained, and obviously correct on screen.

Show the diff, then a short recording of the prompt that produced it. The message is that the slice
structure and the per-slice `CLAUDE.md` files are what let a coding agent land a change in the right place
on the first attempt.

## Build Sequence

1. Lock the scope and the per-slide minute budget. Decide up front whether you are giving the 30-minute or
   the 40-minute version, and mark the cuttable slides in `NOTES.md`.
2. Extract all snippets into `presentation/snippets/`, verbatim from the real sources. Verify every file
   path and identifier still exists — nothing on a slide should be hand-written approximation.
3. Draw the Act 1 event-model strip.
4. Build the deck skeleton: every slide titled, in order, with its timing. Rehearse against the skeleton
   before writing any prose. This is where a 45-minute deck gets discovered and cut.
5. Write the English content act by act.
6. Write `demo-script.md`, then execute it end to end against a freshly wiped database. Capture the
   recordings during that run.
7. Rehearse against a clock. Cut to fit.
8. Only then add the Danish strings and write `NOTES.da.md`. Translating last means translating once.
9. Publish `deck.html` as an artifact and share the link.

## Risks

| Risk | Mitigation |
|---|---|
| Overruns — the material comfortably fills 60 minutes | Slide-level timing in `NOTES.md`; Act 3 slides 6 and 9 marked as the first cuts; rehearse against a clock at step 7, not the night before. |
| A live demo fails | Every segment pre-recorded. The app is started and warm before the room fills, never built live. |
| Audience new to event sourcing gets lost in Act 3 | Act 1 and Act 2 come first and are not compressed. Every Act 3 slide carries exactly one trade-off sentence rather than a feature list. |
| Slides drift from the code | Snippets extracted verbatim in step 2 and re-verified after any code change before the talk. |
| Danish and English versions diverge | Single file, translation applied once after the English content is final. |
| Demo database in a stale state | `docker compose down -v` is part of pre-flight. Event-type FQCNs changed in the slice refactor, so an old volume yields a half-empty demo. |
