# Speaker Notes — Module 6, Concepts And Answers

The concepts of *Simplifying with Event Modeling, Event Sourcing and CQRS*, each followed by the
Essentials code that implements it. 39 slides, 50 minutes, then questions.

Every slide carries its own note in the deck — press `N`. This file is the run of show, why the pairs are
the pairs, and what was left out.

## The shape

Fifteen pairs, then four going-deeper slides. A **grey** slide states the concept in the module's own terms, with its own diagrams where
they exist; the **orange** slide that follows shows the Essentials answer as real code from
`examples/essentials-webshop-demo`. The rail at the bottom of the deck shows `n/13`, so both you and the
room always know where you are in the sequence.

Why that structure: the concepts are teachable in a minute each, and what the room has not seen is the
code. The value of the hour is in the second slide of every pair, so never spend more than about a minute
on a grey one.

The format is **not** explained on a slide beyond one line on the roadmap. It explains itself the first
time a grey slide is followed by an orange one, and a slide spent describing a slide is a slide wasted.

Two slides sit outside the rhythm. **Slide 2, the question** — why is this monitor 1,999.50? — opens the
talk on a problem a normal table cannot answer, before any vocabulary. **Slide 11, the map of the
application**, closes the opening story: after pairs 2–4 every box on it means something, and every orange
slide after it is an excerpt cut from that one webshop, so the room stops asking "where does this bit
live?". The
diagram is the same picture as `examples/essentials-webshop-demo/docs/ui-flow.md` — that file is Mermaid,
which the deck cannot render, so the slide carries a hand-drawn SVG of it. **Keep the two in step** when
the demo's slices change.

## Deck controls

| Key | Does |
|---|---|
| `→` `↓` Space | next slide |
| `←` `↑` | previous |
| `Home` / `End` | first / last |
| `N` | speaker note for this slide |
| `L` | English / Dansk |
| `H` | handout mode — light palette, for print and bright rooms |
| `T` | start / reset the talk timer (counts against 48:30) |
| `?` | the key list |

The deck needs no server. It does need its `images/` directory beside it — six diagrams extracted from
the module's own pptx (see `images/README.md`). The two web fonts degrade to system fonts offline.

## Run of show

| # | Pair | Concept, from the module | The answer | Min |
|---|---|---|---|---|
| 1 | An event is a fact | slide 2 — non-prescriptive, past tense, publisher does not know its subscribers | `sealed interface ProductEvent`, `events/` as the exported contract | 2.5 |
| 2–4 | Discovering and modeling → the four patterns → slices and capabilities | slides 3–18 — told as one story, three grey slides back to back | one answer for 2–3 (one slice = one directory, pattern = base type), one for 4 (the lanes) | 5.75 |
| 5 | Command + state = event | slides 24–25 — the formula, and "aggregates used less and less" | the formula *is* `handle(cmd, events)`; the whole decider | 2.75 |
| 6 | The decider | slide 26 — the pattern, defined, with the module's Kotlin | one bean per aggregate type, `@Service` on the decider, nothing else | 2.25 |
| 7 | Tests come from the model | slides 14, 20 — Given/When/Then, written before the code | `GivenWhenThenScenario`; 43 tests, 0.3 s, no Docker | 2.25 |
| 8 | Event store and replay | slides 27–33 — the basket, animated over six slides | `fetchStream` / `appendToStream`, and the two orderings | 2.5 |
| 9 | State inside a decision | slides 68–69 — the Evolver pattern, the module's own code | `Evolver.applyEvents`, one fold per question | 2.25 |
| 10 | Why view projections | slides 39, 61 — Greg Young, and the three advantages | `ViewEventProcessor` plus a JPA table | 2.25 |
| 11 | Order, delivery, idempotence | slide 61's three considerations, and the strict handler on 66 | two are the framework's, the third is yours | 2.25 |
| 12 | CQRS and stale data | slides 42–58 — CQS, CQRS, collaborative domains, the 120 ms | the query never touches the domain, and the screen polls | 2.5 |
| 13 | Composite UI and automations | slides 73–74 — one screen from many views, and a to-do list | one row from four streams; a policy that owns its state | 2.75 |
| — | Bonus: the dual write | slides 86–88 — the problem, and the module's own diagram | one local transaction, then a subscription publishes | 2.5 |
| — | Bonus: a blocking call in a handler | not in the module — "record, then call", and what *committed* means | `UnitOfWorkMode.NONE` on the capture policy | 2.25 |
| D1 | Going deeper: snapshots | — | `@AggregateSnapshotPolicy`, three modes by what a crash costs | 2.5 |
| D2 | Going deeper: closing the books | — | generations `acct-1#1` → `acct-1#2`, rollover on access | 2.5 |
| D3 | Going deeper: change data capture | — | subscriptions told by the WAL, polling as fallback | 2.5 |
| D4 | Going deeper: the admin console | — | two dependencies, `/essentials/admin`, two security SPIs | 2.5 |

Around the pairs: the title, **the question** (1 min), the roadmap ("thirteen concepts, in four groups" —
read the four group headings and nothing else), **the map of the app** after pair 4 (1.5 min, see below), and at
the end "left out on purpose" and the close. 4 minutes in total, 34.5 in the pairs, and 10 in the
going-deeper slides. 48:30 of content leaves about ten minutes of the 60-minute slot for questions.

**If you are behind**, the going-deeper slides are the elastic end: each stands alone, so cut from them
first — change data capture, then snapshots. Keep closing the books (it answers the replay question
everyone asks) and the admin console (it pays off the dead-letter warning). Only then drop pair 11
(order/delivery/idempotence) and pair 12's concept slide. Do not drop pair 13 or the dual write — they are where Essentials does the
most work for you.

**If you are ahead**, the slides that reward extra time are pair 5's answer (the decider), pair 13's answer
(the automation, and the mistake in its gloss), and the admin console — opened live on the webshop.

## Slide 2 — the question

Ask it and wait: *why is this monitor 1,999.50?* The row on the left cannot answer; the three events on
the right answer why, since when, and what the customer who ordered on 20 March paid. Do not name event
sourcing yet — the slide only has to make the room want what the next hour explains. Pair 1's concept
slide then gives the definition, and can point back to these three events.

## Slide 11 — the map of the app

It comes after pair 4 on purpose: the model, the patterns, the slices and the lanes have just been
introduced, so this is the payoff — all of it at once — rather than a wiring diagram of a system nobody
has a reason to care about yet. Do not read the boxes out. Four columns and twenty-odd labels read themselves faster than you can say
them, and a slide read aloud is a slide the room stops looking at.

Trace **one** path with a finger instead, and say it as a sentence: *press Package in the warehouse — that
is one command; it appends one event to one stream; a projection turns that stream into a table; a panel
renders the table.* Then stop and say the line the rest of the talk rests on: **there is no arrow pointing
back.** Nothing in the left-hand columns holds a reference to a screen. That is why the right-hand column
can be rebuilt, replaced or added to without touching the left, and it is the property every later pair
is a detail of.

Then the **orange arrows**, which are the only thing on the slide worth pointing at twice.
`order_summary` is one row folded from four streams across all three bounded contexts, and the warehouse's
work list from three. Say that you will come back to it — you do, at pair 13, and the room recognises the
picture rather than meeting it cold with ninety seconds left.

Two answers to have ready:

- *"What are the endpoints?"* — one `GET` per panel, and that is the whole of it. Deliberately not on the
  slide: a list of URLs teaches nothing that the column heading does not, and it invites a discussion
  about REST in the third minute of the talk.
- *"Why does Checkout have no read model?"* — it only writes. It shows the order id the browser minted
  and nothing else, so no projection points at it. It is the one exception on the slide and it is worth
  ten seconds, because it shows the rule is structural rather than a convention everyone followed.

Colour is the bounded context, and it stays the same colour on every later slide that has one: sales
amber, payment red, shipping green.

## The pairs, and what to say

**1 — An event is a fact.** The concept slide is two rules and no code — past tense, non-prescriptive —
and it can lean on slide 2: that is why those three events could answer what the row could not. Then the
answer slide's points: the sealed family makes an evolver's `when` exhaustive, `events/` is one of only
two packages another context may import, and — the one nobody warns you about — under Jackson 3 the
*constructor parameter name* is the JSON contract, so renaming a field breaks every stored event.

**2–4 — One story, then the code.** Three grey slides back to back, told as one progression: storming finds
the events and modeling puts them on a timeline (walk the module's legend left to right); every box on
that model is one of four patterns; and a slice of the model, living in a capability's lane, is the unit
you build. Do not stop for code between them — that is what made the old version feel like a checklist.

Then two answers. **From the model to the code** (pairs 2 and 3): one slice is one directory whose files
are the model's boxes — say twenty-four, and no `services/`, no `repositories/` — and the pattern you drew
decides both the directory and the base type you extend.

Each base type brings exactly the machinery its pattern needs: a `Decider` is a pure function, a
`ViewEventProcessor` brings an ordered replayable subscription, an `EventProcessor` adds an Inbox because
an automation may call the outside world.

**The lanes** (pair 4) is a diagram rather than a directory listing, and it is worth working in this
order: the solid block in each card (`events/`, `types/` — the only two packages another lane may import),
then the dashed block (private, and the compiler is what enforces it), then **the two red crosses, which
are the whole slide.** There is no arrow between the cards. The only route from one lane to another goes
down into the store and back up, which is why `shipping` would keep working if `sales` were down for an
hour.

If somebody asks how `shipping` knows the event class at all, take it — it is the best question on this
slide. Two different crossings are happening: it *imports* the class at compile time, and *receives* the
value at runtime from the store. What it never does is **call** `sales`. The diagram draws the second
crossing and the dashed blocks imply the first.

**5 — Command + state = event.** The module's formula, then the method signature that *is* the formula.
Walk the three outcomes: an event, no event, an exception. Then say what is missing — no aggregate class,
no repository, no database, no mocks. That is what "aggregates used less and less" means in practice.

**6 — The decider.** The module defines the pattern; the answer slide shows the wiring it does not. One
`AggregateTypeConfiguration` bean per aggregate type, one configurator for the whole application, and
`@Service` on the decider. Then the honest half: `kotlin-eventsourcing` is experimental, and one decision
yields at most one event — mostly a gift, because it forces `CheckOutRequested` rather than three
technical events, but a decision that genuinely needs two must use the Java `EventStreamDecider`.

**7 — Tests come from the model.** Read the module's Given/When/Then, then the test, and let the room
notice they are the same sentence. Numbers: 43 tests, 0.3 seconds, nothing started.

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

**Bonus — a blocking call in a handler.** Not from the module; it is the dual write's sibling, and the one
the demo actually hit. Packing charges the card, and the payment context's rule is *record the request, then
call the gateway*. The concept slide sets the trap: a `@MessageHandler` runs in one transaction by default, so
the request is written first and committed last, after the gateway has answered — the rule holds in the
source and not in the database, and a pooled connection sits `idle in transaction` for the whole call. The
answer is one attribute, `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`, on the two handlers that can
trigger the capture; the handler commits its own short `withUnitOfWork { }` and then blocks with nothing held.
Name the two obligations the mode hands over: idempotent (the decider returns `null` on redelivery, so nothing
calls twice) and bounded (well inside the queue's 30 s handling timeout). If the room asks how we know it
works: `WebshopFlowIT` records what was true at the moment of the call and fails if a handler goes back to the
default.

**Going deeper — four features the webshop does not need, or does not show.** Change of rhythm: no grey
concept slide, one slide each, text left and real code right. Say at the start that these are the answers
to the questions people ask afterwards.

- *Snapshots.* Call back to pair 8: replay is cheap until the stream is long. A snapshot is folded state at
  event N, and it is a cache — the events stay the truth. Name the modes by what a crash costs. The
  protected no-arg constructor is the pair-1 Jackson 3 lesson again. Aggregate style only, so the code is
  the trading demo's.
- *Closing the books.* Snapshots make a long stream cheaper; closing the books stops it growing. Same logical
  id, a new generation per period (`acct-1#1`, `acct-1#2`); a closed generation never changes and can be
  archived. The trading demo rolls over *on access*, so no slice can forget. Of the two, consider this one
  first.
- *Change data capture.* Every subscription in the talk polls. Hybrid CDC tails the WAL and keeps polling as
  the fallback — which means a broken setup costs latency, not correctness, and nobody notices. Hence the
  health check. Be honest that the webshop does not switch it on; the trading demo does.
- *The admin console.* Pays off "somebody has to watch the dead letter queue": this is where. Two dependencies,
  one page, a 40-operation HTTP contract under it. Say the security point plainly — the admin API
  authenticates nobody itself, and the demo's all-access beans are labelled demo-only for a reason.

## No live demo, deliberately

Fifteen pairs, the map and the going-deeper slides fill the 50 minutes, so there is no demo segment on the deck. The close tells the room how
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
Streams that grow forever are the real problem, and that is what snapshots and closing books are for — the
first two going-deeper slides. Both are extra machinery, which is a cost worth naming.

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
      `hold_funds_on_order_placed`, `capture_funds_when_packaged`, `payment_gateway` and
      `order_management/outgoing`; skim those eight directories after any refactor of the demo. The
      going-deeper slides also quote `essentials-trading-demo`'s `TradingAccount.java`, `TradingAccounts.java`
      and `application-compose.yml`, and the webshop's `pom.xml` and `WebshopDemoApplication.kt`
- [ ] `mvn verify -pl :essentials-webshop-demo` green
- [ ] deck opened offline with `images/` beside it, both languages, handout mode checked on the projector
- [ ] the six extracted diagrams still match the pptx, if the module itself has been edited
- [ ] slide 11's map still matches `examples/essentials-webshop-demo/docs/ui-flow.md` — a slice added or
      moved in the demo changes both, and the deck's copy is hand-drawn SVG that nothing regenerates
- [ ] timer started with `T` on the title slide
