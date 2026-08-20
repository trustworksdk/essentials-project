# Speaker Notes — The Event Is The Record

English. The Danish version is `NOTES.da.md`. The deck itself also carries a one-paragraph note per
slide, shown with `N` — this file is the fuller reasoning behind each one.

## Deck controls

| Key | Does |
|---|---|
| `→` `↓` `Space` | next slide |
| `←` `↑` | previous slide |
| `Home` / `End` | first / last |
| `N` | speaker note for the current slide |
| `L` | English / Dansk |
| `H` | handout (light) mode — for printing or a bright room |
| `T` | start / reset the talk timer, shown in the bottom rail against 36:00 |
| `A` | jump straight to the appendix — the four wrong answers to requirement three |
| `?` | key list |

**Where the appendix is.** One slide, sitting after the closing slide, **not** part of the timed flow.
Press `A`, or click `APPX` in the top-right corner. The rail labels it `A1 / A1` rather than continuing the
slide count, and the progress bar stays full, so walking into it mid-Q&A does not wreck your sense of
position. `←` walks back into the deck. `End` deliberately goes to the closing slide, not past it.

The deck is deliberately dark regardless of the viewing machine's theme, so it cannot change appearance
mid-talk because a laptop is set to light. `H` is the only way to get the light palette.

The URL carries the slide number (`#s14`), so you can hand someone a link to one slide.

## Run of show

30 slides in the flow plus one appendix slide, 36 minutes of content. The rail shows each slide's minute
budget, and the budgets sum to that figure — so if the rail says you are on a 2-minute slide, it means it.

| Slides | Act | Minutes | Cuttable |
|---|---|---|---|
| 1–3 | Title, hook, agenda | 1.5 | no |
| 4–10 | Act 1 — three requirements, three places they land | 11 | no — this is the act the audience needs most |
| 11–12 | Act 2 — one aggregate, up close | 4.5 | no |
| 13 | Act 3 — divider | 0 | yes, if you are already behind |
| 14–24 | Act 3 — eleven components | 10.5 | slides 19 (snapshots), 20 (closing books), 22 (inbox/outbox) |
| 25–27 | Act 4 — live demo, two segments plus the console | 4.5 | Demo C (the console), then Demo B |
| 28 | Act 5 — where it does not fit | 2.5 | no — this is what makes the rest credible |
| 29–30 | Act 6 — AI segment, how to start | 1.5 | the AI slide, if the demo overran |
| A1 | Appendix — four wrong answers to requirement three | 0 | on demand only |
| — | Questions | 8–10 | — |

**Act 1 is the spine of this talk.** Seven slides, 11 minutes, and deliberately the biggest act in the deck:
three requirements arrive in escalating difficulty, and each one lands somewhere different.

| Requirement | Where the rule lands | Slides |
|---|---|---|
| Register an instrument | inside the aggregate — one consistency boundary | 4–6 |
| No two instruments share a symbol | in the handler, against a read model — a guard, not a guarantee | 7–8 |
| An external service must approve it | in an automation, after the transaction commits | 9 |

That is the whole slice taxonomy — command slice, view slice, automation slice — taught by requirements
rather than by a diagram of the framework. If you only get one act right, get this one right.

**To land a 30-minute slot** cut slides 19, 20 and 22 (3 minutes) and Demo C (1 minute), and keep Act 1
and Act 2 whole. Do not cut slide 8 (the uniqueness race) or slide 9 (the external call) to save time:
slide 8 without its race teaches something untrue, and slide 9 is the question your colleagues actually
face this quarter.

**If you are at slide 24 and more than 29 minutes have elapsed**, skip the admin-console segment and go
straight from Demo B to Act 5. Never cut Act 5 to save the demo.

## Act 0 — the hook (slides 2–3)

Ask for a show of hands: who has been asked why a row in a database looks the way it does, and could not
answer? That question is the entire talk. Do not defend event sourcing yet — you have not earned it.

The four questions on slide 2 come back on slide 12 as the contrast, and again in the demo when the raw
event stream answers them. Use the same four, in the same order, all three times.

Slide 3 is a map, not content. One breath per line.

## Act 1 — modeling (slides 4–10)

**Slide 4, the event model.** Read the requirement aloud in business words first: *the market data team
registers instruments.* Then point at each box and name what it is — the command is a request, the
aggregate is the decision, the event is the fact, the view is the answer. The dashed return arrow is
replay: the same events that were written rebuild the aggregate. Nobody has opened an IDE yet, and say so.

**Slide 5, `slice.yaml`.** This file is the bridge from whiteboard to repository. Point at `handles`,
`publishes`, `writes` — that is the whiteboard, typed. Then the invariants. INV-RI-2 is a genuine design
decision worth dwelling on: the *caller* supplies the `InstrumentId`, so a retried registration addresses
the same instrument rather than minting a second one. That is idempotency decided at the contract, not
patched in later. `forbidden` is a real constraint that a reviewer and a coding agent can both check.

**Slide 6, the slice on disk.** Four files, one folder. Say the numbers: 19 command slices and 6 view
slices in `brokerage`, 5 and 2 in `market_data`, and not one of them is a service layer. The command
record *is* the request body, so there is no DTO and no mapper to keep in step. The typed `InstrumentId`
and `Symbol` round-trip because the web `ObjectMapper` has the Essentials Jackson module registered — one
line of configuration, not per-field annotations.

**Slides 7 and 8, the uniqueness requirement.** A second requirement arrives: *no two instruments may
trade under the same symbol.* These two slides exist because this is the question the room will actually
ask, and because it is where the method earns its keep.

Open by saying this one is **not in the repository yet** — it is the next requirement, not a tour of
existing code. Say it once, clearly, so nobody goes looking for `SymbolAlreadyRegistered` afterwards and
concludes the slides were fiction.

Then slide 7, in three beats:

1. **The contract grows before the code does.** `INV-RI-3` gets an id like the other two, and `reads:`
   stops being empty. Anyone can now see, from the YAML alone, that this command slice depends on a view.
2. **Why the aggregate cannot enforce it.** This is the load-bearing sentence of the whole act: an
   aggregate is a consistency boundary, so it sees its own stream and nothing else. A rule about the whole
   *set* of instruments cannot live inside one of them. Checking it there would mean loading every
   instrument in a single transaction, which is not what a boundary is for.
3. **So the decision moves up.** The handler asks the `instrument_details` view — the same read model from
   Act 3's projection slide, met early. Point at the `filter`: it preserves INV-RI-2, so the *same*
   registration retried still succeeds silently, and only a *different* instrument claiming the symbol is
   rejected. Give them the textbook term, **set-based validation**, so they can read about it later.

Then slide 8, and do not skip it. If you show the check without the race you have taught them something
false, and the first senior in the room will find the hole in ten seconds. Walk the four timestamps
slowly: two instances, two transactions, the projection has not caught up, both pass, both write.

Say clearly that this cannot be fixed by trying harder — reading twice, or reading inside the transaction,
does not help, because the row it would need does not exist yet.

Then the three ways out, and commit to one: for a ticker symbol, **detect and repair**, because a duplicate
ticker is embarrassing rather than dangerous. If it were money, take option two and make the unique value
an aggregate id. Option three, a `FencedLock` around registration, is the cheap pragmatic answer and it
forward-references component 08 — worth flagging so Act 3 lands as a callback rather than new material.

The sentence to land: **choose per rule, not per system.**

One consequence worth naming if somebody asks: having the handler read a view makes that view slice's
query part of its public surface. That is a real coupling decision, not a free lunch.

**Slide 9, the external approval.** Requirement three: *an external risk service must approve the instrument
before it can be traded.* Same disclaimer as the last two slides — proposed, not in the repository. Say it
once.

This is the slide your colleagues will recognise from their own backlog, and the instinct in the room will
be right: a blocking HTTP call does not belong inside a database transaction. Validate that, then correct the
*reason*, because most people give the weak one.

The weak reason is resource holding — a connection and the aggregate's locks pinned for the whole network
round trip, so a risk service degrading to five-second latency becomes a connection-pool outage in your
service. True, and tunable.

The reason that actually settles it: **a rollback cannot un-call an external service.** Once the request has
left, it has happened. If the transaction then fails and retries, the risk service is called a second time.
There is no correct version of "inside the transaction", so the transaction has to end before the call.

Then the four blocks, in order:

1. **tx 1** — `InstrumentRegistrationRequested`, status `PENDING_RISK_APPROVAL`, and the same transaction
   queues `RiskCheckDeadlineReached` with a 15-minute delivery delay. The endpoint answers `202`, not `201`:
   the instrument exists but is not tradeable.
2. **the queue** — `InstrumentRiskCheck extends EventProcessor` takes the event off its `Inbox` with no
   `UnitOfWork` open and makes an ordinary HTTP call. Point at the idempotency key: at-least-once means the
   risk service *will* occasionally see the same check twice.
3. **tx 2** — the answer returns as a command, `RecordRiskDecision`, and the aggregate's state machine
   completes: `ACTIVE` or `REJECTED`.
4. **tx 2'** — nobody answers. The delayed command lands, checks whether the instrument is *still* pending,
   and times it out. That state re-check is what makes the delayed command safe to deliver more than once.

Worth saying plainly: **`EventProcessor` is built for this.** The framework docs list its role as external
integrations and long operations. Inbox-backed, exclusive via a `FencedLock`, retried on a policy you set,
dead-letters when the dependency stays down, and it hands you `getCommandBus()` — so the whole automation is
one class with two handlers. This is also where `FencedLock` stops being a bullet point and does real work.

Land on the domain sentence: **if a decision takes time, the waiting is part of the domain.** Model the
waiting; do not hide it in a thread pool.

If anybody pushes — "why not just call it and be done" — press `A` for the appendix.

**Slide 10, bounded contexts.** This is the DDD payoff, and the slide the seniors will push on. `brokerage`
needs a price to value a trade. It does *not* call `market_data`. It subscribes to two events and builds
its own read model. State the alternative plainly: injecting the other context's service — which is
exactly what this arrangement replaced in the demo's own history. The stream names live in `types/`
because a foreign context has to name the stream it subscribes to, and `aggregates/` is not importable.

## Act 2 — the aggregate (slides 11–12)

**Slide 11** is the most important slide in the deck. Slow down. Four beats, in this order:

1. Methods call `apply`. They never assign state.
2. The `@EventHandler` methods are the only place those four fields are ever written.
3. The same handlers run on rehydration, so replaying history and handling a new command are one code
   path — which is why behaviour and history cannot drift apart.
4. The two early returns are idempotency. Renaming to the name already held, or suspending an
   already-suspended instrument, applies nothing. A retried command leaves no trace instead of growing
   the stream.

Then the framing that makes it stick: `Instrument` is the demo's deliberate baseline. No snapshots, no
closing books, short streams by construction — an instrument is registered once, occasionally renamed,
and at most once suspended. Everything in Act 3 is measured against this class.

**Slide 12, the contrast.** Do not sneer at the JPA entity. It is shorter, faster to write, and most of our
code looks like it. Keep the claim narrow: it cannot answer the four questions from the opening, and
adding log lines does not fix that, because logs are not the source of truth and nobody replays them.

## Act 3 — the components (slides 13–24)

Every slide ends in a trade-off. Say the cost with the same energy as the benefit — a framework talk
without costs is a sales pitch, and the room can smell one.

**Component 01 — `types` (slide 14).** Two classes, three lines each. The point is what they make impossible. Note that
`Symbol` serializes as the same bare JSON string it replaced, so adopting it changed no persisted payload
— which is how you introduce semantic types into an existing system without a migration.

**Component 02 — `UnitOfWork` (slide 15).** Three different kinds of write, one commit. Then the demo fact: not one
`@CmdHandler` in the codebase carries `@Transactional`, because the command bus opens the unit of work.
That comment is in the real handler, not added for the slide.

**Component 03 — the event store (slide 16).** The one thing they must leave with: two orderings, and neither is a timestamp.
`EventOrder` is position within one stream; `GlobalEventOrder` is position across every stream of an
`AggregateType`, and that is what a durable subscription resumes from. Say plainly that clocks are never
used for ordering — it is the mistake everyone brings from a CRUD background.

**Component 04 — aggregate or decider (slide 17).** Two lanes, both supported. Left is what the demo uses; right is the
functional one, tested with no database and no Spring context. Then be explicit: the demo is entirely on
the aggregate lane on purpose, and we are not converting it. A codebase running both lanes for the same
kind of problem is worse than either lane chosen consistently.

**Component 05 — projections (slide 18).** The most practically useful slide in the act. The default is left: project the
events, serve reads from your own table, accept lag. Right is the documented exception, and read the
reason from the code — the bootstrap probe asks whether a price already exists, and an eventually
consistent "absent" would let it seed a second time on top of live data. One exception, written down,
with a named reason. `onSubscriptionsReset` is worth ten seconds: it is what makes rebuilding a read
model a button rather than a migration.

**Component 06 — snapshots (slide 19).** *One minute, and one of the first cuts.* Deliberately kept shallow: the concept is
the whole point. Loading an aggregate replays its events, so a long stream costs more to load, and a
snapshot is a saved fold of the stream up to event *n*. One annotation turns it on. Say why `Instrument`
declares none — a short stream is cheaper to replay than a snapshot is to maintain — and move on. Do not
get into threshold tuning; nobody in a first talk needs `everyNEvents` arithmetic.

**Component 07 — closing the books (slide 20).** *One minute, also a candidate cut.* This answers the objection you will get:
streams grow forever. They do not. The accounting analogy does the work — nothing is rewritten and nothing
is deleted, the old stream is sealed with a closing balance and a new one opens carrying it forward. Then
the honest cost: two ids per account, which is real added complexity you should only take on when streams
genuinely grow without bound. Stop there.

Between these two slides you have two minutes total. If you find yourself explaining generation-id parsing
you have overrun both.

**Component 08 — queues and locks (slide 21).** Dwell on `getCurrentToken()`, because that is the difference from a plain
mutex. The token is monotonic, so a holder that stalled, lost the lock, and woke up still carrying the old
token can be rejected by whatever it writes to. A mutex cannot express that. Then the hard constraint,
twice if needed: this coordinates instances of *one* service against *one* database. Not cross-service.

**Component 09 — inbox/outbox (slide 22).** *Second cut if you are behind.* Name the dual-write problem in one sentence: save
to the database, publish to Kafka, and the process dies between the two. Then be blunt that this buys
at-least-once, not exactly-once, so every downstream consumer must be idempotent — and that is now their
design constraint too, which is a conversation you owe them before you ship.

**Component 10 — starters (slide 23).** Full artifact names are on the slide, so read one out and let the table carry the rest.
The message is that these are ordinary Spring Boot starters: one dependency, a handful of properties, and
the beans exist. There is no Essentials bootstrap class and no XML. The strongest concrete example is that
`@Service extends AnnotatedCommandHandler` is the *entire* registration of a command handler.

Then the declarations bean: one per bounded context, merged across contexts, pairing an `AggregateType` —
the stream name — with the class whose events go into it. It replaced two hand-written `InitializingBean`s,
which is worth a sentence because it shows the direction the ergonomics have been moving.

There is no trade-off box on this slide, deliberately.

**Component 11 — admin console (slide 24).** Keep it short; it is a bridge into the demo. The console is not something we
maintain — it arrives with the starter.

## Act 4 — the demo (slides 25–27)

Switch windows at slide 25. See `demo-script.md` for the exact commands and the reset procedure.

The app must be started and warm before the room fills. Do not build anything on stage.

Two slides and three segments, 4.5 minutes. The snapshots-and-generations demo was deliberately dropped:
snapshots and closing books already have a slide each in Act 3, and demoing them as well made two
secondary mechanisms the centre of the talk.

**Demo A — a slice, round trip (slide 26).** Run the first four calls quickly, they are setup. Stop on the
last one and let the room read it. Point out that the rename is a second event, not an overwrite. If the
first `GET` lags a beat, that is slide 18 happening in the room, not a bug — say so before anyone wonders.

**Demo B — the cost, measured (slide 27).** This is where you earn the room. Run the comparison, then state
the caveat before anyone else can: the aggregate path is measured slightly heavier than it needs to be,
because sending on the command bus puts dispatch, handler lookup and the `UnitOfWork` interceptor inside
the timed window, while the direct-write path is unchanged. The comparison is biased against event sourcing
and we are showing it anyway. Then the rule: event-source what you must be able to explain; do not
event-source a tick feed just because you can.

**Demo C — the admin console (no slide).** One minute, driven straight from the browser off the back of
slide 24. Aggregate lookup, then queues and locks. **Cut this first** if you are behind.

If any segment misbehaves, open the recording from `recordings/` and keep talking. Do not debug on stage.

## Act 5 — the limits (slide 28)

Do not rush this and do not apologise through it. This slide is why the rest of the talk should be
believed.

If you only have time for two lines, take **intra-service only** and **an event's persisted type is its
class name**. Those are the two that actually bite in production. The second one has a war story attached:
this demo's own README tells you to wipe the database, because the slice refactor renamed every event
class and no upcasting was provided. The JSON payloads were unchanged — it was purely a type rename — and
it still made every existing event unreadable.

## Act 6 — close (slides 29–30)

**Slide 29, the AI segment.** Show the diff, play the recording, and make only the narrow claim: the
structure is what let the agent land the change in the right place, because the slice folder, its
`CLAUDE.md` and its `slice.yaml` say what belongs there. Do not claim the agent understood the domain.

**Slide 30.** Land on the one sentence — event-source what you must be able to explain, project everything
you must be able to query, keep the rest boring — then stop talking and take questions.

If the room is quiet, ask it yourself: what in our systems has an audit conversation attached to it? That
is the candidate. Not the whole platform.

## Appendix — four wrong answers to requirement three (slide A1, press `A`)

The mechanism itself is now slide 9, in the flow. This appendix slide is what you reach for when somebody
pushes back, or asks *why not just do X* — and X is always one of these four. At least one person in the
room has shipped each of them.

**HTTP inside the `@CmdHandler`.** The naive version. It is inside the `UnitOfWork`, so connection and
aggregate locks are held for the round trip, and the rollback problem from slide 9 applies in full: a retried
transaction calls the service twice.

**`afterCommit` / `TransactionSynchronization`.** This is the subtle one and the one worth dwelling on,
because it looks right — it *is* on the correct side of the commit. But it is not durable. The process dies
between the commit and the callback and the call is simply gone, with nothing anywhere recording that it was
owed. That is precisely the hole an inbox or an outbox exists to fill, which makes this a callback to
component 09 rather than new material. If you only make one point off this slide, make this one.

**In-memory `EventBus` listener.** Same loss, and it dies with the JVM. Fine for cache invalidation, not for
an obligation to a third party.

**"Make it `@Async`."** The obligation now lives in a thread pool in RAM. A restart forgets it, there is no
redelivery, no dead letter queue, and nothing to look at when someone asks why an instrument has been
pending for two days.

**Then the question to put back to the business.** Does "approved before" mean before the instrument is
*tradeable*, or before the HTTP response returns? It is almost always the former, and that is the difference
between this being a correct model and being a workaround. If they genuinely need a synchronous yes/no in the
response, you are building a synchronous facade over an asynchronous process — say so out loud and price it.

**If somebody objects** to `Instrument` carrying a state that exists only because of an external dependency —
a fair objection — the answer is to give the process its own aggregate, a `RiskCheck` stream, and keep the
instrument clean. That is the process-manager variant, and it is the right call once the approval flow grows
more steps.

**Webhook variant.** If the risk service calls you back rather than answering in the response, the webhook
endpoint writes to an **Inbox**: stored and de-duplicated first, handled in its own transaction after. Same
principle, mirrored, and it costs nothing extra because the inbox is already there.

**Generalise it before you sit down.** The same shape covers every outbound call — payment authorisation, KYC
lookup, credit decision, an email, a PDF to generate. Anything where the answer is not yours to compute and
the work must not be lost.

## Questions you should expect

| Question | Short answer |
|---|---|
| "Isn't this just Axon / EventStoreDB?" | Different scope. This is building blocks on the PostgreSQL you already run, with no broker and no separate cluster. Intra-service by design, and it says so. |
| "What about GDPR erasure?" | The honest answer is that it is real work: crypto-shredding or a rewrite of the affected streams. Do not hand-wave it. |
| "How do we migrate an existing table?" | You do not, wholesale. Pick one aggregate whose history people keep asking about, and run it alongside. |
| "What does replay cost on a big stream?" | That is exactly what snapshots and closing books are for — and the demo ships a benchmark for both, which you just saw. |
| "Two Jackson versions sounds fragile." | One is on the classpath at a time; they share class names and write byte-identical JSON. The build runs both profiles. |
| "Can we event-source everything?" | No, and Demo B is the reason. Event-source decisions, not tick feeds. |
| "How do you do a unique constraint, then?" | Slides 7–8 answer this, so you can send them back to it. Short version: not inside one aggregate. Either accept the window and detect collisions, or make the unique value an aggregate id. |
| "Couldn't you just put a unique index on the projection table?" | It moves the failure, it does not prevent it. The event is already written and committed by then, so the projector fails instead of the command — you have swapped a rejected registration for a stuck projection. Fine as a *detector*, not as the enforcement. |
| "We have to call an external service and it must approve first." | Press `A`. Short version: not inside the transaction, because a rollback cannot un-call it. An `EventProcessor` does the call after the commit, the answer comes back as a command, and the deadline is a delayed command. |
| "Doesn't at-least-once mean we might call the risk service twice?" | Yes, and that is not hidden — it is why the call carries an idempotency key. Any external call from a durable consumer needs one; this is the normal cost of not losing the call instead. |

## Rehearsal checklist

- [ ] `presentation/snippets/extract.sh` re-run, no `MISSING SOURCE`
- [ ] Full run against a clock, at least once, out loud
- [ ] Demo executed end to end from a wiped database
- [ ] All four demo segments recorded into `recordings/`
- [ ] Deck opened on the actual presenting machine, at the actual resolution
- [ ] `H` handout mode checked, in case the room is bright
- [ ] `L` checked on two or three slides in Danish, so a mid-talk switch is not a surprise
- [ ] Timer (`T`) started at slide 1 during the rehearsal, to find the real overrun point
- [ ] Deck checked for mojibake — every `æ`, `ø`, `å` in the Danish text renders as itself. The file
      declares `<meta charset="utf-8">`; if that line is ever lost, every Danish slide breaks at once
