# Speaker Notes — From Whiteboard To Event Store

Module 6 (*Simplifying with Event Modeling, Event Sourcing and CQRS*) rebuilt around a running
application. 28 slides, 36 minutes of content, then questions. Two appendix slides, on demand.

Every slide carries its own note in the deck — press `N` to show it on screen. This file is the run of
show, the reasoning behind the structure, and the material that was cut.

## Deck controls

| Key | Does |
|---|---|
| `→` `↓` Space | next slide |
| `←` `↑` | previous |
| `Home` / `End` | first / last (End stops at the closing slide, not the appendix) |
| `N` | speaker note for the current slide |
| `L` | English / Dansk |
| `H` | handout mode — light palette, for print and bright rooms |
| `T` | start / reset the talk timer (counts against 36:00) |
| `A` | jump to the appendix |
| `?` | the key list |

The deck is one self-contained HTML file. It needs no server and no network, except for the two web
fonts — on a machine with no connection it falls back to system fonts and still lays out correctly.

## Run of show

| Act | Slides | Min | Cumulative |
|---|---|---|---|
| 0 — the hook | 1–2 | 2 | 2 |
| 1 — from conversation to model | 3–7 | 7 | 9 |
| 2 — event sourcing, the write side | 8–13 | 9 | 18 |
| 3 — view projections, the read side | 14–17 | 5.5 | 23.5 |
| 4 — CQRS, briefly | 18–20 | 4 | 27.5 |
| 5 — automations, integrations, the dual write | 21–24 | 5.5 | 33 |
| 6 — demo, limits, how to start | 25–27 | 3 | 36 |

**If you are behind at slide 18**, cut slide 19 (collaborative domains) and slide 22 (the gateway
port). Both are supporting material; the demo and the limits slide are not.

**If you are ahead**, slow down on slide 9 (the decider) and slide 16 (the three hard parts). Those are
the two slides people ask about afterwards.

## Act 0 — the hook (slides 1–2)

Open with the question, not with a definition. *Why is this product 1,999.50?* — then the four
follow-ups, then the row on the right that answers none of them.

Ask for a show of hands: who has been asked something like this and could not answer? Most rooms give
you half the hands. Do not defend event sourcing yet; the whole talk is the answer to that question, and
saying so now spends the tension early.

Slide 2 is the map. One breath per line, and say out loud that every code panel is real code from an
application in this repository — it changes how the room reads the rest.

## Act 1 — from conversation to model (slides 3–7)

**Slide 3, event storming.** The workshop mechanics are in the appendix (`A`); this slide is only the
idea. The point to land: nobody guesses the events, you ask the people who know, and you write what *has
happened* in the past tense. The fourth sticky note — `CreditCardHoldRejected` — is the one to dwell on:
somebody in the room knew that cards get declined, and that a decline is a business fact rather than an
error. That single insight is what keeps it out of a log file later.

**Slide 4, the building blocks.** Point at each box in order and name the sticky colour. Trigger, command
(blue), event (orange), view (green). The order *is* the content: a request, a decision, a fact, an
answer. Then the dashed line: replay. The same events rebuild every view and every decision, which is
why a view can be thrown away.

The gloss defines the two words newcomers need — *event-sourced* and *stream*. Read it if the room is
mixed; skip it if they all build event-sourced systems already.

**Slide 5, the three patterns.** Say the number: three patterns, and every one of the sixteen slices in
the demo is one of them. Command, view, automation. The automation pattern is the one people have not met
— an event lands, it becomes a piece of work, something picks the work up — and the key observation is
that the shape is identical whether a machine or a human closes the loop. In the demo, payment drains its
list automatically and the warehouse screen is drained by a person.

**Slide 6, the model as directories.** This is the slide that makes the method concrete. The three
top-level folders are the swimlanes from the wall. Sixteen slices, and not one folder called `services`,
`repositories` or `controllers`. A new use case adds a directory rather than growing an existing class,
which is the practical difference people feel in month six.

**Slide 7, testing.** Read the Given/When/Then aloud from the boxes, then point at the Kotlin and say:
that is the same sentence. Emphasise what is *absent* — no database, no Spring, no mocks — and give the
number: 30 tests, 0.3 seconds.

## Act 2 — event sourcing, the write side (slides 8–13)

**Slide 8, naming.** Imperative for a request that can still be refused, past tense for a fact that
cannot. Then the gloss, which is the real lesson: an event must carry everything its readers need,
because a reader cannot ask a question of the past. The concrete case is worth telling — when
`ItemRemovedFromShoppingBasket` did not carry the price of the unit removed, the basket view and the
checkout total each had to guess which unit left, and both got it wrong when two units went in at
different prices.

**Slide 9, the decider.** The central slide. Walk the three outcomes: an event, no event, an exception.
Then say what is missing: no repository, no database, no aggregate class. It is a function from a command
and a list of events to at most one event.

Pause on the `compareTo` line. `Amount` wraps `BigDecimal`, and `BigDecimal.equals` is scale-sensitive —
`100.00` does not equal `100.0`. An idempotency check that compares representations instead of values
appends a change event that changes nothing. It is a two-line lesson that saves somebody a day.

**Slide 10, the tests.** Three tests for the three outcomes, plus the scale test. The fourth is there
precisely because it is a trap somebody will otherwise hit in production.

**Slide 11, the event store.** Point at the two order columns and say which is which. `event_order` is
the position inside one basket — that is what a projection compares to stay idempotent. `global_order` is
the position across every stream — that is what a subscription resumes from. Then say the thing people get
wrong: **the timestamp is documentation; never order by it.**

Also worth noting out loud: there is no `UPDATE` and no `DELETE` anywhere on this slide. That is the
entire storage model.

**Slide 12, the evolver.** This answers the question every experienced developer is holding: what if the
decision needs state? You fold the stream. The fold lives for one decision and is then thrown away, so it
can be exactly the question this slice needs answered. The checkout slice folds the *same* events into a
running total — two small folds, neither knowing about the other, instead of one `ShoppingBasketState`
that grows a field per slice.

**Slide 13, the wiring.** The source module skips this, and it is where the framework earns its keep. One
bean per aggregate type, `@Service` on the decider, and one configurator bean for the whole application.
No handler registration to forget, and the command bus owns the transaction, so no `@Transactional` on a
handler either.

Then the honest half, on the slide as a trade-off box: `kotlin-eventsourcing` is marked experimental, and
a decision yields at most **one** event. That constraint is mostly a gift — it forces `CheckOutRequested`
instead of `BasketClosed` + `OrderCreated` + `TotalCalculated` — but a decision that genuinely needs two
events has to use the Java `EventStreamDecider` instead. Say both halves.

## Act 3 — view projections, the read side (slides 14–17)

**Slide 14, why project.** Read Greg Young's line out loud; it is the whole argument. Then the practical
version: the store appends and streams, and "all products for sale, by name" is neither of those things.
A view is a cache you can always rebuild, which is what makes it safe to have many of them.

**Slide 15, the projection.** Walk the handler, then the entity. Slow down on the version comparison:
"set the price to X" applied twice is still X, but "add one to the quantity" applied twice is wrong, so
this code has to be able to recognise an event it has already seen.

**Slide 16, the three hard parts.** The table is the argument. Order and delivery are the framework's job
— per-stream ordering, a stored resume point, a fenced lock so one instance projects. Idempotence is
*yours*, because only your code knows what applying an event twice means to your table. The gloss is the
practical advice: write projections as assignments where you can, and compare `EventOrder` only where you
genuinely cannot.

One caveat to state plainly, because two of the demo's projections depend on it: ordering is guaranteed
**per stream**, not across two aggregate types. A projection reading two contexts' events must tolerate
either arriving first.

**Slide 17, the loop.** Trace one price change with a finger, following the numbers. Then say the quiet
part: the write side and the read side are connected by the log, not by a call. Steps 1–3 are one
transaction; steps 4–5 happen milliseconds later on their own schedule, and can be replayed from scratch
whenever you like.

## Act 4 — CQRS, briefly (slides 18–20)

Seventeen slides of the source module are compressed into three here. If someone wants the full
treatment, the original Module 6 deck is still the reference.

**Slide 18, CQS to CQRS.** CQS is the property-level idea everyone already uses: setters change things,
getters answer things. CQRS is the same split one level up — "two objects where there was previously only
one", which is Greg Young's own definition. The payoff line: a query result is data, not behaviour, so
why route it through the domain layer at all? And with a read model, the eager-versus-lazy fetching
argument simply disappears.

**Slide 19, collaborative domains.** Tell it as a story: Anna opens the order, Bo opens the same order,
Anna goes for coffee, Bo saves, Anna saves and gets an optimistic locking error. Ask the room why the
*user* should be interrupted by a technical constraint.

Then the arithmetic on the right, which is the real point: the data on their screen was already 120
milliseconds old before they touched it, plus a second or two of thinking time. **Consistency was never
instantaneous.** The question is not whether to accept staleness — you already did — but whether to use it
deliberately.

**Slide 20, the trade.** Both halves, out loud. The gain is real: reads stop competing with writes, and a
new question costs a view rather than a schema migration. The cost is real too, and it lands in the UI —
which is better than landing in the infrastructure where it would be invisible. In the demo, the shop page
polls after placing an order. Somebody has to decide, with the business, which screens may lag. That
conversation *is* the work.

## Act 5 — automations, integrations, the dual write (slides 21–24)

**Slide 21, the automation.** The most important sentence: `sales` did not ask `payment` to do this. It
recorded a fact; `payment` decided on its own what that fact means for it. Delete the whole payment
context and `sales` does not change.

Then tell the story in the gloss, briefly, because it is the most useful thing in the talk for anybody
about to build one of these. The work-item row started out in a *separate* view slice that the policy read
on its own subscription. It worked most of the time — which is the problem. Two subscriptions have no
order relative to each other, so the policy kept running before the row existed and leaned on redelivery
to recover. On a slower machine the retries ran out, the message became a dead letter, and the order
silently never got charged. Giving the policy its own state removed the race instead of tuning it.

**Slide 22, the gateway.** One place in the whole application makes a synchronous call. The reason is
worth saying plainly: an authorization is a question to a third party, and there is nothing to record
until they answer. It stays out of the decider so the decider stays replayable — replaying a decision must
never charge a card a second time. And a decline is recorded as a fact, not logged as an error: it is why
the order is stuck, and it is what customer service needs to see.

**Slide 23, the dual write.** Set the trap first. Two systems, no shared transaction, and neither order of
the two writes is safe: database-then-broker loses the message, broker-then-database announces something
that never happened, and a distributed transaction across both is not an answer. Let that sit for a beat.

The answer is almost anticlimactic: have only one write. The decider appends to the event store in one
local transaction, and a subscription publishes afterwards from the committed stream. The cost is stated
on the slide — at least once, and a moment later — which is why the external event carries the event
order so consumers can deduplicate.

**Slide 24, the publisher.** Two things to point at. The translation: internal types become plain strings
on the way out, in this one class and nowhere else, so the published contract can be stable while the
domain keeps moving. And `stopRedeliveryOn`: some failures are permanent, and retrying a malformed message
twenty times only delays everything behind it.

Close the act on the operational commitment: somebody has to watch the dead letter queue. A dead letter is
logged, nothing fails, and the business outcome simply never happens.

## Act 6 — demo, limits, close (slides 25–27)

**Slide 25, the demo.** Switch to the browser and follow `demo-script.md`. Three beats: buy something and
watch the summary fill in piece by piece; be the warehouse and pack the order; then look behind it in the
admin console. Every beat has a fallback in the runbook — use it rather than debugging in front of the
room.

**Slide 26, the limits.** Do not skip this slide, even when short of time. Credibility comes from the
limits, and the room contains people who will have to maintain whatever they choose. Say the last line
slowly: if the events are not facts the business recognises and names, you get the machinery without the
benefit.

**Slide 27, the close.** One concrete action, not a summary. Model the thing somebody keeps having to
explain — a price, a status, a balance, an entitlement. Draw it on a wall with the person who keeps asking.
Then one slice. Point at the two plugin commands and the repository paths, and stop talking.

## Appendix (press `A`)

**A1 — running a storming workshop.** Four practical rules. Use it if somebody asks how to actually run
one. The first rule is the one that matters: without the people who have answers in the room, you are
writing fiction.

**A2 — four things that bit us building this.** The `-java-parameters` flag, the silent dead letter, the
Testcontainers lifecycle, and `BigDecimal` scale. Good material for the "is this hard?" question: none of
them is conceptual, and all four are written down in the module's `CLAUDE.md` so the next person pays
once.

## Questions you should expect

**"How is this different from an audit log?"** An audit log is written *next to* the state, so the two can
disagree, and nothing breaks when the log is wrong. Here the events *are* the state — there is nothing else
to disagree with.

**"What about GDPR / the right to be forgotten?"** Real tension, and slide 26 says so. The usual answers
are crypto-shredding (the events keep a key, deleting the key makes the payload unreadable) or keeping
personal data outside the stream and referencing it. Both are design decisions to take before the first
line of code, not afterwards.

**"Does it not get slow, replaying everything?"** Loading one stream is loading one small list of rows, not
the whole store. Streams that grow forever are the real problem, and Essentials has snapshots and closing
books for that — see the trading demo. Both are extra machinery, which is a cost worth naming.

**"How do we change an event's shape later?"** Additively, and carefully: Essentials stores the concrete
class name and provides no upcasting, so renaming an event type makes existing data unreadable. The demo's
`CLAUDE.md` says this in as many words. New optional fields are free; renames are a migration.

**"Do we need Kafka?"** No. Kafka is in the demo only to show the dual-write answer for events that must
leave the application. Everything else — commands, projections, automations — runs on PostgreSQL alone.

**"Why Kotlin here and Java in the other demo?"** Because `kotlin-eventsourcing` is the module this code
exercises, and it is the module the original Module 6 snippets were written against. The Java equivalent
is `EventStreamDecider` in `eventsourced-aggregates`; the trading demo shows the aggregate style instead.

**"Is `kotlin-eventsourcing` production-ready?"** It is marked work-in-progress, and the API may move. Say
that plainly. The patterns are not experimental; the Kotlin wrapper around them is newer than the Java one.

## Rehearsal checklist

- [ ] the code panels still match the app — the deck quotes `change_product_price`, `remove_item_from_shopping_basket`,
      `products_for_sale`, `order_summary`, `hold_funds_on_order_placed`, `payment_gateway` and
      `order_management/outgoing`; skim those seven directories after any refactor of the demo
- [ ] `mvn verify -pl :essentials-webshop-demo` green, and once with `-Pjackson2 … -am`
- [ ] `docker compose -f examples/essentials-webshop-demo/src/main/resources/compose.yml down -v`, then
      run the demo from cold once, timing it
- [ ] deck opened offline, both languages, handout mode checked on the projector
- [ ] timer started with `T` on the title slide during the real talk
