# Speaker Notes — Module 6, Concepts And Answers

The concepts of *Simplifying with Event Modeling, Event Sourcing and CQRS*, each followed by the
Essentials code that implements it. 32 slides, 36 minutes, then questions.

Every slide carries its own note in the deck — press `N`. This file is the run of show, why the pairs are
the pairs, and what was left out.

## The shape

Fourteen pairs. A **grey** slide states the concept in the module's own terms, with its own diagrams where
they exist; the **orange** slide that follows shows the Essentials answer as real code from
`examples/essentials-webshop-demo`. The rail at the bottom of the deck shows `n/13`, so both you and the
room always know where you are in the sequence.

Why that structure: the concepts are teachable in a minute each, and what the room has not seen is the
code. The value of the hour is in the second slide of every pair, so never spend more than about a minute
on a grey one.

The format is **not** explained on a slide beyond one line on the roadmap. It explains itself the first
time a grey slide is followed by an orange one, and a slide spent describing a slide is a slide wasted.

## Deck controls

| Key | Does |
|---|---|
| `→` `↓` Space | next slide |
| `←` `↑` | previous |
| `Home` / `End` | first / last |
| `N` | speaker note for this slide |
| `L` | English / Dansk |
| `H` | handout mode — light palette, for print and bright rooms |
| `T` | start / reset the talk timer (counts against 36:00) |
| `?` | the key list |

The deck needs no server. It does need its `images/` directory beside it — six diagrams extracted from
the module's own pptx (see `images/README.md`). The two web fonts degrade to system fonts offline.

## Run of show

| # | Pair | Concept, from the module | The answer | Min |
|---|---|---|---|---|
| 1 | An event is a fact | slide 2 — non-prescriptive, past tense, publisher does not know its subscribers | `sealed interface ProductEvent`, `events/` as the exported contract | 2.5 |
| 2 | Discovering and modeling | slides 3–16 — storming finds them, modeling puts them on a timeline | one slice = the model's four boxes as four files | 2.75 |
| 3 | The three patterns | slide 12 — command, view, automation | three directory names, three framework base types | 2.5 |
| 4 | Slices and capabilities | slides 17–18 — units of value, and the swimlanes they live in | the three lanes as top-level directories; `events/` + `types/` are all that cross | 2.5 |
| 5 | Tests come from the model | slides 14, 20 — Given/When/Then, written before the code | `GivenWhenThenScenario`; 30 tests, 0.3 s, no Docker | 2.25 |
| 6 | Command + state = event | slides 24–25 — the formula, and "aggregates used less and less" | the formula *is* `handle(cmd, events)`; the whole decider | 2.75 |
| 7 | The decider | slide 26 — the pattern, defined, with the module's Kotlin | one bean per aggregate type, `@Service` on the decider, nothing else | 2.25 |
| 8 | Event store and replay | slides 27–33 — the basket, animated over six slides | `fetchStream` / `appendToStream`, and the two orderings | 2.5 |
| 9 | State inside a decision | slides 68–69 — the Evolver pattern, the module's own code | `Evolver.applyEvents`, one fold per question | 2.25 |
| 10 | Why view projections | slides 39, 61 — Greg Young, and the three advantages | `ViewEventProcessor` plus a JPA table | 2.25 |
| 11 | Order, delivery, idempotence | slide 61's three considerations, and the strict handler on 66 | two are the framework's, the third is yours | 2.25 |
| 12 | CQRS and stale data | slides 42–58 — CQS, CQRS, collaborative domains, the 120 ms | the query never touches the domain, and the screen polls | 2.5 |
| 13 | Composite UI and automations | slides 73–74 — one screen from many views, and a to-do list | one row from four streams; a policy that owns its state | 2.75 |
| — | Bonus: the dual write | slides 86–88 — the problem, and the module's own diagram | one local transaction, then a subscription publishes | 2.5 |

Plus the title, the roadmap ("four questions, in the order you hit them"), "left out on purpose", and the close: 2 minutes.

**If you are behind at pair 8**, drop pair 11 (order/delivery/idempotence) and pair 12's concept slide.
Both are supporting material. Do not drop pair 13 or the dual write — they are where Essentials does the
most work for you.

**If you are ahead**, the two slides that reward extra time are pair 6's answer (the decider) and pair
13's answer (the automation, and the mistake in its gloss).

## The pairs, and what to say

**1 — An event is a fact.** Read the module's quote. Then the answer slide's three points: the sealed
family makes an evolver's `when` exhaustive, `events/` is one of only two packages another context may
import, and — the one nobody warns you about — under Jackson 3 the *constructor parameter name* is the
JSON contract, so renaming a field breaks every stored event.

**2 — Discovering and modeling.** This is the module's own event model, legend and all. Walk the legend
left to right: UI/API/job, blue command, orange event, green view, then the four Given/When/Then patterns
at the bottom. Storming finds the orange stickies; modeling puts them in time. The answer slide turns
those four boxes into four files in one directory, and the number to say out loud is sixteen — sixteen
slices, no `services/`, no `repositories/`.

**3 — The three patterns.** Say "three" and mean it: everything in the system is one of these. The
automation pattern is the unfamiliar one. The answer slide's table is the point — each pattern has its own
framework base type, and the type brings exactly the machinery that pattern needs: a `Decider` is a pure
function, a `ViewEventProcessor` brings an ordered replayable subscription, an `EventProcessor` adds an
Inbox because an automation may call the outside world.

**4 — Slices and capabilities.** Two ideas at two scales. The three wireframes are the module's own Web
App lane. On the answer slide, say what crosses a boundary and what cannot, then the `shipping` example:
it learns that an order exists by subscribing, never calls `sales`, and would keep working if `sales` were
down for an hour.

**5 — Tests come from the model.** Read the module's Given/When/Then, then the test, and let the room
notice they are the same sentence. Numbers: 30 tests, 0.3 seconds, nothing started. The fourth test in the
gloss is the one that earns its keep — money compared with `equals` is scale-sensitive, so `100.00` and
`100.0` are different objects and the same price looks like a change.

**6 — Command + state = event.** The module's formula, then the method signature that *is* the formula.
Walk the three outcomes: an event, no event, an exception. Then say what is missing — no aggregate class,
no repository, no database, no mocks. That is what "aggregates used less and less" means in practice.

**7 — The decider.** The module defines the pattern; the answer slide shows the wiring it does not. One
`AggregateTypeConfiguration` bean per aggregate type, one configurator for the whole application, and
`@Service` on the decider. Then the honest half: `kotlin-eventsourcing` is experimental, and one decision
yields at most one event — mostly a gift, because it forces `CheckOutRequested` rather than three
technical events, but a decision that genuinely needs two must use the Java `EventStreamDecider`.

**8 — Event store and replay.** The module animates the basket over six slides; the concept slide
compresses that to one table with the resulting basket in the margin. Point at the two order columns and
name them precisely: `EventOrder` is position within one stream and is what a projection compares;
`GlobalEventOrder` is position across everything and is what a subscription resumes from. Then the rule
people break: **the timestamp is documentation — never order by it.**

**9 — State inside a decision.** This answers the question the room is holding: with no aggregate, where
does state live? In a fold, computed inside the decision and thrown away. The demo's fold tracks prices
per unit rather than quantities, and the reason is worth 20 seconds: the removal event has to carry the
price of the unit that left, or the basket view and the checkout total each guess differently when two
units went in at different prices.

**10 — Why view projections.** Read Greg Young's line. Then the answer: a processor and a table. Say what
`ViewEventProcessor` brings — in-order delivery per stream, a stored resume point, a fenced lock — and
that wiping the table is safe because replay rebuilds it.

**11 — Order, delivery, idempotence.** The module lists three considerations; the answer slide assigns
them. Two are the framework's. The third is yours, because only your code knows what applying an event
twice means to your table. The practical rule in the gloss removes most of the work: assignment is
idempotent, increment is not. Mention that the demo does *not* use `EventOutOfOrderException`, because two
of its projections read two contexts' streams where no order exists between them.

**12 — CQRS and stale data.** Seventeen of the module's slides in one pair. Tell the Anna-and-Bo story,
read the 120 ms arithmetic, and ask why the user should be interrupted by a technical constraint. The
answer slide is a nine-line controller and both halves of the trade — and the cost is real: the demo's
shop page polls after placing an order rather than pretending.

**13 — Composite UI and automations.** The module's colour-boxed order confirmation is the best slide in
its deck; every box is a different view. The answer is one projection over four streams from three
contexts, plus the policy. Then tell the story in the gloss: the work-item row first lived in a separate
view slice — the module's drawing taken literally — and it dead-lettered under load because two
subscriptions have no order relative to each other. Letting the policy own its state removed the race.

**Bonus — the dual write.** Set the trap: two systems, no shared transaction, neither order safe. The
module's own hand-drawn diagram already names the Essentials components, so show it and then show the
publisher. Point at `stopRedeliveryOn` — some failures are permanent — and close on the operational
commitment: somebody has to watch the dead letter queue, because a dead letter is one log line and the
business outcome simply never happens.

## No live demo, deliberately

Fourteen pairs fill the 36 minutes, so there is no demo segment on the deck. The close tells the room how
to run it themselves, and `demo-script.md` is still the runbook if you get a longer slot or the room asks
to see it — three beats, each with a fallback.

If you do demo, take it from pair 13: place an order on the shop page and watch the summary fill in field
by field as each subscription catches up. That is the one thing a slide cannot show.

## Questions you should expect

**"How is this different from an audit log?"** An audit log is written next to the state, so the two can
disagree and nothing breaks when the log is wrong. Here the events *are* the state.

**"What about GDPR?"** Real tension. The usual answers are crypto-shredding — the event keeps a key,
deleting the key makes the payload unreadable — or keeping personal data outside the stream and
referencing it. Both are decisions to take before the first line of code.

**"Does replaying everything not get slow?"** Loading one stream is loading one small list of rows.
Streams that grow forever are the real problem, and that is what snapshots and closing books are for — see
`essentials-trading-demo`. Both are extra machinery, which is a cost worth naming.

**"How do we change an event's shape later?"** Additively, and carefully. Essentials stores the concrete
class name and provides no upcasting, so renaming an event type makes existing data unreadable. New
optional fields are free; renames are a migration.

**"Do we need Kafka?"** No. It is in the demo only to show the dual-write answer for events that must
leave the application. Commands, projections and automations run on PostgreSQL alone.

**"Why Kotlin?"** Because `kotlin-eventsourcing` is the module this code exercises, and the module's own
snippets were written against it. The Java equivalent is `EventStreamDecider`; `essentials-trading-demo`
shows the aggregate style instead.

**"Is `kotlin-eventsourcing` production-ready?"** It is marked work-in-progress and the API may move. Say
that plainly. The patterns are not experimental; the Kotlin wrapper around them is newer than the Java one.

**"Aggregates are used less and less — do we still need them?"** Sometimes. A decider is the right default
for a slice-shaped use case. An aggregate earns its place when many slices share one invariant-heavy
consistency boundary, and that is the style the trading demo shows.

## Rehearsal checklist

- [ ] the code panels still match the app — the deck quotes `change_product_price`,
      `remove_item_from_shopping_basket`, `products_for_sale`, `order_summary`,
      `hold_funds_on_order_placed`, `payment_gateway` and `order_management/outgoing`; skim those seven
      directories after any refactor of the demo
- [ ] `mvn verify -pl :essentials-webshop-demo` green, and once with `-Pjackson2 … -am`
- [ ] deck opened offline with `images/` beside it, both languages, handout mode checked on the projector
- [ ] the six extracted diagrams still match the pptx, if the module itself has been edited
- [ ] timer started with `T` on the title slide
