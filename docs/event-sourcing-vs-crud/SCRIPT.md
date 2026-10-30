# Event Sourcing vs CRUD: cinema script

The script behind `index.html`, an animated, side-by-side visualization (one page of vanilla HTML, SVG and
JavaScript) that explains event sourcing to developers who think in CRUD. It holds the argument, the frame, a
storyboard for each of the ten chapters, the implementation notes and the design decisions.

## Goal

A developer used to CRUD/ORM should leave able to say two things, in this order:

1. **"Event sourcing is CRUD plus a journal."** The view tables are the tables they already know; the events
   underneath are what produced them. Nothing scary.
2. **"A new requirement is a new subscriber or a new projection."** Things nobody thought of on day one (an email,
   a dashboard, a warehouse integration, a price history) are added by attaching a subscriber to business events,
   without editing every write path, without dual writes, and without a schema migration that cannot recover lost
   history.

History and audit are a **bonus**, not the headline. The headline is flexibility: reacting to business events.

## The build of the argument

The film follows the order most teams actually go through:

1. Plain CRUD works, until requirements start arriving.
2. Each requirement bolts a side effect onto every write path, inside the transaction: concerns conflate, side
   effects become the bottleneck, and talking to external systems becomes a dual-write problem.
3. Going event-driven fixes most of it: write the row and the event in one transaction (an outbox) and handle
   each side effect in its own transaction. New requirement = new subscriber.
4. But now every change is written twice, as a row and as an event, and the two can disagree. **Why not keep
   only the events?** That is event sourcing: one write gives you the state, the history and the events to
   publish. The audit trail is proven, because every decision is made by reading it.
5. Without a row, state comes from replaying the events, through the same mechanism that feeds every projection
   and subscriber. Symmetry.
6. There are two ways to hold that state: a classic aggregate (easy at first, tends to grow without limit) and
   decider/evolver per slice (small state per decision, slices thinly coupled through commands and events).
7. Business events (`ShippingAddressChanged`) say what happened; CRUD change records (`OrderUpdated{…}`) don't,
   so you can't tell which side effect should follow.
8. Bonus: history. A requirement nobody anticipated (price history) becomes a new projection replayed from the
   first event, instead of a Flyway migration whose backfill can't recover what was overwritten.
9. The honest ledger: more ceremony, consistency chosen per projection (read-your-own-writes, answered by
   in-transaction processors), and events are a contract you keep forever.
10. Built by the plugin: with an AI assistant on both sides, writing code is cheap either way. The essentials
    plugin generates and checks the ceremony, one slice per requirement, and what remains different is how far
    each change spreads.

## Frame

| Element | Behaviour |
|---|---|
| **Left pane** | CRUD/ORM, the whole film. It never changes stage. |
| **Right pane** | Changes stage as the story moves: CRUD → Event-driven → Event sourced. |
| **Progress track** | Three stops under the panes: `CRUD → Event-driven → Event sourced`. Shows the right pane's current stage, and is also the chapter navigation. Chapter 10 adds a fourth marker, `Built by the plugin`. |
| **Requirements inbox** | Pinned top-right with a day counter. Business requests arrive as cards; each card plays out on both panes, the left pane first and then the right. |
| **Caption bar** | Directly under the inbox, above the panes, in the brand body type, on a light *paper* strip in both palettes so the eye goes there first. A step's caption is a short list of *beats*: a general sentence spans the full width, and a sentence about one pane sits in the column directly above that pane. While a pane plays, its beat lights up, that pane is spotlighted and the other pane dims. |
| **Pane flash** | When a pane is about to play, its border flashes once in the accent colour before the spotlight settles, so the eye goes there first. |
| **Pause between panes** | When a step has played one pane, it waits before the other: the waiting pane says "Press → to play this side" and its caption beat is marked *plays next*. The next →, click or Space plays it; only then does → go to the next step. Play buttons and the sandbox run straight through. |
| **Chapter intro card** | Opens chapters 2–10 (step `N.intro`): *So far*, *Now*, *Watch for*, so the story line between chapters is explicit. Chapter 1's title card also explains how to read the screen. On paper, like the caption. |
| **Concept card** | A new idea gets its own step, right after the step where it first appears: what it is, an annotated example of what it is made of, and the few points worth remembering. On paper. See [Concept cards](#concept-cards). |
| **Chaos toggles** | ⚡ SMTP down, ⚡ gateway slow, ⚡ Kafka down. Each is greyed out ("not yet") until the story introduces its side effect: SMTP from Day 2, gateway from Day 9, Kafka from Day 30. Once live they can be flipped at any step, and both panes react. |
| **Diff beats (◆)** | The only places code appears, as git-style diffs. Five main beats plus one supporting beat (◆2b). Code uses the real Essentials Kotlin API where it exists. |
| **Terminal strip** | Chapter 10 only: a Claude Code-style terminal docked at the bottom of each pane, with a directory tree and *files added* / *existing files edited* counters beside it. |
| **Illustrative tag** | Anything not in `examples/essentials-webshop-demo` (the email, the dashboard, the classic aggregate, the CSV import, the admin edit path) carries a small *illustrative* tag. |

Navigation: step through with ←/→ or click; chapters are reachable from the progress track; each chapter opens
with an intro card and a **re-establishing shot** so it stands on its own. A ☀ button (or `H`) switches to the light
palette, which reads better on a projector.

### Concept cards

Ten steps that explain a concept the first time the film uses it. Each card has a one-sentence definition, an
annotated example built from real webshop types where they exist, and three or four points. The step id is
`<chapter>.<concept>`.

| Step | After | Explains | Example on the card |
|---|---|---|---|
| `2.event` | 2.0 | What an event is: a past-tense fact; its type, the id of the thing it happened to, and the details of the change | `ProductPriceChanged(id, price)` annotated; an outline of `sales/events/ProductEvent.kt` (the sealed interface and its two data classes); the same event as stored or sent, `event_type` plus a JSON `event_payload`; `OrderPlaced(id)` and `OrderCancelled(id, reason)` as a point |
| `2.outbox` | 2.1 | A handler, and why it writes to its own outbox in the order's transaction | A flow diagram of the two transactions, each in its own frame: `placeOrder()` → `UPDATE orders` + `INSERT INTO outbox` → `COMMIT`, then 200 OK; the outbox row hands over to the second, where its consumer reads the row → `mailer.send(…)` → sent, or up to 10 retries and then a dead letter |
| `2.eventual` | 2.4 | Eventual consistency | A dashboard one order behind for a few milliseconds |
| `4.event-store` | 4.4 | Event store, aggregate, stream, aggregate type | One `orders_events` row, column by column |
| `4.processor` | 4.5 | Event processor: subscribes to the store, keeps a resume point | The `▸ Orders 3` badge; the three processor kinds |
| `5.command` | 5.1 | Command vs event: a wish that can be refused vs a fact | `ChangeProductPrice` next to `ProductPriceChanged` |
| `5.decider` | 5.3 | Decider: command + past events → new events, none, or a refusal | The three outcomes of `ChangeProductPriceDecider`, and its given/when/then test |
| `5.projection` | 5.5 | Projection and view | `ProductAdded` and `ProductPriceChanged` becoming one `products_for_sale_view` row |
| `6.aggregate` | 6.1 | The classic aggregate, rebuilt from all its events before every command (*illustrative*) | `class Order : AggregateRoot` with fields, a command method and an event handler |
| `6.slice` | 6.4 | Slice, and its four kinds | `sales/use_cases/place_order/`; command, view, automation and translation slices from the demo |

### Visual grammar

Colours follow the event-modeling convention and the module6 deck palette.

| Thing | Shape | Colour |
|---|---|---|
| Command | rounded box, label in imperative (`PlaceOrder`) | blue `--code-type` `#7FB6D6` |
| Event | sticky-note card, past tense (`OrderPlaced`) | orange `--accent` `#F2A33C` |
| Read model / view table | table card | green `--gain` `#4FA870` |
| Automation / subscriber | box with a gear glyph | purple `--code-kw` `#C9A2E0` |
| External system (SMTP, gateway, Kafka) | box with a dashed outline at the pane edge | `--ink-dim` |
| Transaction | dashed bracket enclosing everything that commits together. **Its width is its duration**, so a long transaction is visibly long | `--rule` with `--accent` while open |
| Data in flight | small token travelling along an arrow | colour of what it carries |
| Overwritten value | old value flashes `--cost` red, gets struck through, fades | `--cost` `#C9565A` |
| Stale value | dimmed with a small clock glyph | `--ink-faint` |
| Failure / dead letter | red outline plus a short label | `--cost` |
| Response-time meter | thin bar under each pane's UI box, grows with the time the user waits | `--ink-dim`, turns `--cost` past 1 s |

Fonts: the Trustworks brand grotesk (tw-brand `visual-style`) for all non-code text, IBM Plex Mono for code and tables.

## Requirements inbox

| Day | Card | First appears |
|---|---|---|
| 0 | Customers can place orders | ch 1 |
| 2 | Email the customer when the order is accepted | ch 2 |
| 9 | Hold funds on the customer's card when the order is placed | ch 2 |
| 21 | Live sales dashboard for the ops team | ch 2 |
| 30 | Tell the warehouse system (Kafka) when an order is placed | ch 2 |
| 35 | **Incident:** customers with declined cards got "order accepted" emails | ch 3 |
| 60 | Show each product's price history; marketing: which items did customers remove from their basket after a price rise? | ch 8 |
| 62 | Email customers when an item in their basket drops in price | ch 8 |

## Chapters

| # | Chapter | Left: CRUD | Right pane (stage) | Lands |
|---|---|---|---|---|
| 1 | **Day 0: Same start** | `placeOrder()` → `@Entity Order` → `UPDATE orders`. Three write paths into `orders`: checkout, admin edit, CSV import | identical (CRUD) | Familiar ground; both sides equal |
| 2 | **Days 2–30: Requirements arrive** | each card adds an inline call to `placeOrder()`; the transaction stretches; the other write paths silently miss the new behaviour. ◆1 | **Event-driven**: same `UPDATE` plus `eventBus.publish(...)`; each card adds one handler with its own outbox, committed with the row. ◆2 | Add a subscriber; don't edit every path |
| 3 | **Day 35: The twist, and things break** | hunt the email call through three paths; slow gateway holds the transaction open; Kafka down means a dual write | rewire one subscriber; failures retry in their own transaction or dead-letter | Side effects belong outside the write transaction |
| 4 | **Two truths** | gains an audit table nobody reads | row and event written every time, and they drift → drop the row; the event store is the outbox. ◆2b | One write gives state, history and publishing; the audit trail is proven by use |
| 5 | **Replay: where is the state?** (a price change, slowed down) | load the `products` row, overwrite the price | `ChangeProductPrice` → load the stream → decide → append `ProductPriceChanged` → `products_for_sale_view` updated; mirrored symmetry; the view wiped and replayed. ◆3 | Symmetry. "CRUD table + journal" |
| 6 | **Aggregate vs decider/slices** | `OrderService` and the entity grow with every card | top: a classic `Order` aggregate grows fields and methods per card; bottom: the event model, slices whose deciders need little or no state; "cancel a placed order" added as one new slice. ◆4 | Two approaches, and why slices scale |
| 7 | **Business events vs CRUD events** | admin edits an order → an audit row with a column diff: what happened, and what should react? | `ShippingAddressChanged`, `ItemRemovedFromShoppingBasket`, `OrderCancelled`, each with an obvious reaction | The intent is in the name |
| 8 | **Day 60: History, the bonus** | price `1056.00 → 1225.00 → 1999.50` overwritten; Flyway `V7` + backfill recovers only the current price, and deleted basket rows are gone. ◆5 | new projections replay from `global_order 1` and answer both questions as if they had always existed; a new automation starts from the latest event instead. ◆5 | New requirement = new projection, not schema surgery |
| 9 | **The honest ledger** | commit → reload → fresh | async view shows a stale value → switch that view to an in-transaction processor → fresh. Costs and gains boxes | Simple and flexible; the costs named out loud |
| 10 | **Built by the plugin** | an AI assistant edits `OrderService` and its three write paths for every card | an AI assistant with the essentials plugin: `/essentials:init`, one new slice directory per card, a spoken change classified by `essentials-change`, `/essentials:slice-map --html` drawing the design, `/essentials:review` catching a boundary violation; then the sandbox | AI makes code cheap on both sides; slices keep each change local. Ceremony generated, and checked |

Non-goals: snapshots, change data capture, event upcasting (named as a cost only), cross-service messaging,
multi-tenancy, PDF or print output.

The storyboards below describe what each step shows. Their captions give the gist; the page holds the final
wording, split into beats: a general sentence, then one sentence per pane.

---

## Storyboard: chapter 1, "Same start" (Day 0)

**Track:** `CRUD` on both panes. The right pane is labelled with the track so the reader learns early that it
will move.
**Toggles:** all three greyed out, each labelled with the day it becomes live.

### 1.0 Title card

**"Requirements keep arriving."** Subtitle: *The same webshop, built twice. Watch what each new requirement costs.*
A one-line hint underneath: "→ to step, or pick a chapter on the track."

### 1.1 Two identical apps

Both panes draw the same lane: `Checkout UI → OrderService.placeOrder() → @Entity Order → orders`.

- **Caption:** "Two copies of the same shop. For now they are identical, and that is the point: event sourcing
  starts from what you already know."

### 1.2 Place an order

The requirements inbox drops its first card, **Day 0: "Customers can place orders."** A token runs the lane on
both panes at once: `UPDATE orders SET status = 'PLACED'`, one transaction bracket, short. The row
`ORD-1042 · PLACED · 1999.50` lands in `orders`. The response meters stay tiny.

- **Caption:** "One request, one transaction, one row. Simple, and it works."

### 1.3 The three ways in

The camera pulls back. Two more entry points draw in on both panes, each with an arrow into `orders`: **admin
edit** and **CSV import** (both *illustrative*).

- **Caption:** "Real systems have more than one way to change an order. Remember these three: every new
  requirement has to reach all of them."

### 1.4 The inbox and the track

The inbox pulses, with its next card face down. The progress track highlights its three stops in turn.

- **Caption:** "Requirements will arrive. The left side stays CRUD. The right side will change how it is built,
  one step at a time."

---

## Storyboard: chapter 2, "Requirements arrive" (Days 2–30)

**Track:** right pane moves from `CRUD` to `Event-driven`.
**Toggles:** SMTP goes live at 2.1, gateway at 2.3; Kafka stays greyed until 2.5 and is not exercised until
chapter 3.

### 2.0 Re-establishing shot

- **Left:** `Checkout UI → OrderService.placeOrder() → @Entity Order → orders` with one row
  `ORD-1042 · PLACED · 1999.50`. `placeOrder()` and the row light up, and that is all.
- **Right:** identical, then morphs. One line glows in `placeOrder()`: `eventBus.publish(OrderPlaced(order.id))`.
  An **event bus** strip (the Essentials `EventBus`) appears beside the service, inside the same transaction
  bracket. It is empty: nothing listens yet. The track moves to `Event-driven`.
- **Caption:** "Same app, same database." Left: "`placeOrder()` updates the row, and that is all: nothing else
  learns that an order was placed." Right: "The order code also announces *what happened* on an event bus, and
  doesn't care who is listening."

How fan-out works in this stage, without a broker: each side effect is an `AnnotatedEventHandler` registered as
a **synchronous** subscriber on the bus, so it runs inside the caller's transaction, and all it does there is put
the event into **its own `Outbox`**. That outbox row commits with the `orders` row; the outbox's consumer then
performs the side effect in its own transaction, with retries and a dead-letter queue. One handler and one
outbox per side effect.

### 2.1 Card, Day 2: "Email the customer when the order is accepted"

- **Left:** a new line glows inside `placeOrder()`: `mailer.sendOrderConfirmation(order)`. An SMTP box appears at
  the pane edge, and the transaction bracket stretches to include it.
- **Right:** a handler box `Accepted email` (gear, purple, *illustrative*) plugs into the event bus,
  with its own small `outbox` table under it and an arrow from the outbox out to SMTP. A small badge on
  `placeOrder()`: "0 lines changed".
- **Caption:** "Left: the new behaviour lives inside the order code. Right: it is a new listener; the order code
  never heard about it."

### 2.2 Play one order

- **Left:** token: UI → service → `UPDATE orders` → SMTP → commit. The response-time meter grows while SMTP
  answers; the bracket stays open the whole time.
- **Right:** token: UI → service → `UPDATE orders` → event bus → email handler → `INSERT` into its outbox (all
  in one bracket) → commit → response returned. *Then* a second token: outbox consumer → SMTP, in its own small
  bracket.
- **Caption:** "Same email. On the right, the customer gets their answer before the email is even sent."

### 2.3 Card, Day 9: "Hold funds on the card when the order is placed"

- **Left:** `paymentGateway.hold(order.total)` joins `placeOrder()`, a gateway box appears, and the bracket and
  response meter grow again.
- **Right:** a second handler, `Hold funds`, plugs into the bus with its own outbox. Its name matches the real
  automation in the demo, `HoldFundsOnOrderPlacedPolicy`.

### 2.4 Card, Day 21: "Live sales dashboard"

- **Left:** `dashboard.increment(order.total)` inside the transaction: one hot `sales_dashboard` row that every
  order now updates (a lock glyph pulses on it when two orders overlap).
- **Right:** an **async projection**, `Sales dashboard`, plugs into the bus with its own outbox, and the
  outbox's consumer owns the `sales_dashboard` table. Play an order: the `orders` row commits, and the dashboard counter ticks a beat later,
  with a label "eventually consistent: a few ms behind".
- **Caption:** "A view that is allowed to be a moment behind costs the order nothing."

### 2.5 Card, Day 30: "Tell the warehouse system"

- **Left:** `kafka.send("order-events", …)` joins `placeOrder()`. A Kafka box appears. ⚡ Kafka becomes live.
- **Right:** a fourth handler, `Order management`, with its own outbox; its consumer sends to
  Kafka. The pane now shows four handler-plus-outbox pairs hanging off one bus.

### 2.6 ◆1 vs ◆2, the diff beat

Both panes dim and two diff panels rise.

- **◆1 (left), `OrderService.placeOrder()` after four cards** (*illustrative*):

  ```kotlin
   @Transactional
   fun placeOrder(cmd: PlaceOrderRequest) {
       val order = orders.findById(cmd.orderId)
       order.place()
  +    mailer.sendOrderConfirmation(order)
  +    paymentGateway.hold(order.id, order.total)
  +    dashboard.increment(order.total)
  +    kafka.send("order-events", OrderPlacedMessage(order.id))
   }
  ```

- **◆2 (right):** `placeOrder()` with **0 changed lines** since the one `eventBus.publish(...)`, next to four
  *new* files of a dozen lines each, e.g.:

  ```kotlin
  @Component
  class SendConfirmationEmail(outboxes: Outboxes, private val mailer: Mailer) : AnnotatedEventHandler() {
      private val outbox = outboxes.getOrCreateOutbox(
          OutboxConfig.builder()
              .setOutboxName(OutboxName.of("SendConfirmationEmail"))
              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofSeconds(1), 10))
              .build()
      ) { message -> mailer.sendOrderConfirmation((message.payload as OrderPlaced).orderId) }

      @Handler
      fun on(e: OrderPlaced) = outbox.sendMessage(e)   // same transaction as the orders row
  }
  // registered with eventBus.addSyncSubscriber(sendConfirmationEmail)
  ```

- **Caption:** "Left: one method that knows about four other systems. Right: four small things that each know one."

### 2.7 The other write paths

Zoom out on both panes: three entry points write to `orders` (checkout, admin edit, CSV import; the last two
*illustrative*).

- **Left:** only checkout has the four calls. Admin edit and CSV import each get four amber "?" markers: did
  anyone add the email? The dashboard? The warehouse message?
- **Right:** each path must still publish its event on the bus: **one** amber "?" per path instead of four.
- **Caption:** "Event-driven shrinks the checklist from one line per side effect to one event per path. Chapter 4
  makes even that one impossible to forget."

---

## Storyboard: chapter 3, "The twist, and things break" (Day 35)

**Track:** right pane stays `Event-driven`.
**Toggles:** all three live from 3.3 onwards.

### 3.0 Re-establishing shot

Both panes as at the end of chapter 2: left `placeOrder()` with four inline calls; right, four handler-plus-outbox
pairs on the event bus.

### 3.1 Card, Day 35 (red): "Customers with declined cards got 'order accepted' emails"

Play an order whose card is declined.

- **Left:** `sendOrderConfirmation` runs *before* `paymentGateway.hold` (line order in the method); the email
  leaves, then the hold is declined.
- **Right:** `Accepted email` listens to `OrderPlaced`, so it fires in parallel with `Hold funds`; the
  email leaves before the decline.
- **Caption:** "Same bug on both sides. The difference is the fix."

### 3.2 The fix

- **Left:** the method is rewritten: hold first, branch on the result, send "accepted" or "declined". Then the
  camera pans to admin edit and CSV import, and the same rewrite is needed there (each flashes amber, then
  green). A counter reads "3 places changed".
- **Right:** the email handler's input arrow is unplugged from `OrderPlaced` and re-plugged into
  `CreditCardHoldPlaced`; a new handler, `Declined email`, with its own outbox, attaches to
  `CreditCardHoldRejected`. A chain becomes visible: `OrderPlaced` → `Hold funds` (its outbox consumer calls the
  gateway and publishes the outcome on the bus) → `CreditCardHoldPlaced` / `CreditCardHoldRejected` → email.
  Counter: "1 handler moved, 1 added, 0 write paths touched".
- **Caption:** "Subscribers can react to other subscribers' events. Fixing *when* something happens is choosing a
  different event."

### 3.3 The chaos toggles go live

The toggle strip lights up, with a hint: "Try breaking it, or press → to see each one." The next three steps
flip each toggle in turn; afterwards the reader can flip them freely.

### 3.4 ⚡ Gateway slow (5 s)

- **Left:** the transaction bracket stays open for 5 s; the `orders` row and the hot `sales_dashboard` row show
  lock glyphs; a second order queues behind the first; the response meter turns red.
- **Right:** the order commits in milliseconds and the customer sees "order received"; `Hold funds` waits on the
  gateway in its own transaction; nothing else waits.

### 3.5 ⚡ SMTP down

- **Left:** two possible outcomes, shown as a fork:
  - the exception rolls back the whole transaction, so the order is lost and the customer sees an error;
  - or the exception is caught and swallowed, so the email is lost silently.
- **Right:** the email subscriber fails, retries on its redelivery policy (a small retry counter ticks), and
  after the last attempt the message goes to a **dead letter**, outlined in red, where it waits to be resent.
  The order is untouched.
- **Caption:** "Failure doesn't disappear on the right. It is contained, visible and retryable."

### 3.6 ⚡ Kafka down: the dual write

- **Left:** two mini-timelines side by side:
  - commit, then `kafka.send` fails: the warehouse never hears about the order;
  - send, then the commit fails: the warehouse ships an order that does not exist.
- **Right:** the event sits in the `Order management` outbox; its consumer's arrow to Kafka is red
  and retrying, while the other three outboxes carry on unaffected. Toggle off: the outbox drains and the
  warehouse receives it.
- **Caption:** "Two systems can't commit together. The outbox makes the second write a delivery, not a gamble."

### 3.7 Landing

**Caption:** "Side effects belong outside the write transaction, each in its own."

---

## Storyboard: chapter 4, "Two truths"

**Track:** right pane moves from `Event-driven` to `Event sourced` at 4.4.
**Left pane:** static CRUD from chapter 3, except for the audit table at 4.7.

### 4.0 Re-establishing shot

The right pane as it stands: `orders`, the event bus, and five handler-plus-outbox pairs (four from chapter 2,
plus the declined email from chapter 3), all writing inside one transaction bracket.

### 4.1 Freeze

Play one order and freeze at the commit. The `orders` row and the `OrderPlaced` rows in every outbox pulse
together.

- **Caption:** "Every change is written as the new state, and again as what happened, once per outbox."

### 4.2 Drift

The CSV import sets `ORD-1042` to `CANCELLED` and forgets to publish `OrderCancelled`. The row says cancelled; the
warehouse subscriber never hears; a truck icon leaves with the order. Both rows get a red outline.

- **Caption:** "Two truths can disagree, and nothing tells you which one is right."

### 4.3 The question

A card drops over the right pane: **"If the events record everything that happened, why keep the row?"**

### 4.4 The morph

The track moves to `Event sourced`.

- The `orders` table fades to a ghost outline.
- The five outboxes slide together and merge into the **event store**: one table per aggregate type. On screen,
  `orders_events` for the `Orders` type, with `products_events` and the other types' tables stacked behind it.
  Real columns: `global_order`, `aggregate_id`, `event_order`, `event_type`, `event_payload`, `timestamp`.
  Two orderings, labelled once: `event_order` counts from **0** within one aggregate's stream; `global_order`
  counts from **1** across every stream of the aggregate type.
- The event bus strip retracts. The five handlers re-attach **directly** to the event store as event
  processors, each with a small marker showing its own resume point: the `global_order` it has reached, per
  aggregate type it reacts to.
- Each processor gets a small badge for the kind it is, because the kind is a choice with different
  characteristics:

  | Handler | Processor | Why |
  |---|---|---|
  | Accepted email, Declined email, Hold funds, Order management | `EventProcessor` | Durable inbox per processor: retries, dead letters, and room for slow external calls |
  | Sales dashboard | `ViewEventProcessor` | Low latency: handled directly, falling back to a durable queue only on failure |
  | (chapter 9) a view the UI reads right after the command | `InTransactionEventProcessor` | Updated in the same transaction as the append, so the next read sees it |

- **Caption:** "The event store *is* the outbox, and every processor comes with its own inbox: retries and dead
  letters built in."

### 4.5 One write

Play an order: `PlaceOrder` command → a closed box labelled "decide" (chapter 5 opens it) → append `OrderPlaced`
→ the processors pick it up.

- **Caption:** "You can't forget to publish: publishing *is* the write."

### 4.6 ◆2b: the subscriber barely changes

The ◆2 email subscriber, re-homed onto the event store, using the same shape as the demo's
`HoldFundsOnOrderPlacedPolicy`:

```kotlin
-@Component
-class SendConfirmationEmail(outboxes: Outboxes, private val mailer: Mailer) : AnnotatedEventHandler() {
-    private val outbox = outboxes.getOrCreateOutbox(
-        OutboxConfig.builder()
-            .setOutboxName(OutboxName.of("SendConfirmationEmail"))
-            .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofSeconds(1), 10))
-            .build()
-    ) { message -> mailer.sendOrderConfirmation((message.payload as CreditCardHoldPlaced).id) }
-
-    @Handler
-    fun on(e: CreditCardHoldPlaced) = outbox.sendMessage(e)
-}
+@Service
+class SendConfirmationEmail(
+    dependencies: EventProcessorDependencies,
+    private val mailer: Mailer
+) : EventProcessor(dependencies) {
+    override fun getProcessorName() = "SendConfirmationEmail"
+    override fun reactsToEventsRelatedToAggregateTypes() = listOf(PaymentAggregateTypes.CREDIT_CARD_HOLDS)
+
+    @MessageHandler
+    fun on(e: CreditCardHoldPlaced, message: OrderedMessage) = mailer.sendOrderConfirmation(e.id)
+}
```

- **Caption:** "Same reaction, less plumbing: the processor brings its own inbox, retries and dead letters, and
  nothing has to be registered on a bus."

### 4.7 The audit trail, proven

- **Left:** an `orders_audit` table, filled by a trigger with column diffs, scrolls by. A bug writes a wrong audit
  row; nothing notices.
- **Right:** the event stream gets a check mark: "Every decision reads these events. A wrong event breaks the
  next decision, the tests and the projections, immediately."
- **Caption:** "A CRUD audit log is hoped to be right. An event store is proven right, because the system runs
  on it."

### 4.8 Teaser

**Caption:** "But with no row, how does a command know the current state?" → chapter 5.

---

## Storyboard: chapter 5, "Replay: where is the state?"

One small change, slowed right down: **a price change**. Everything on the right is real webshop code:
`ChangeProductPriceDecider`, `ProductPriceChanged`, `ProductsForSaleViewProjection` and its JPA entity
`ProductForSaleView` (table `products_for_sale_view`).

**Track:** `Event sourced`.
**Toggles:** unchanged from chapter 4; none are exercised here.

### 5.0 Re-establishing shot

The panes clear to a new scene: a product admin screen on both sides showing *LG Superview, 1056.00*.

- **Left:** the `products` table, row `PRD-7f3a · LG Superview · 1056.00 · version 0`.
- **Right:** the `products_events` table (the `Products` aggregate type) with
  `global_order 17 · PRD-7f3a · event_order 0 · ProductAdded · {name, price 1056.00}` among other products' rows,
  and beneath it
  `products_for_sale_view` with row `PRD-7f3a · LG Superview · 1056.00 · version 0`.
- The left `products` row and the right `products_for_sale_view` row get a matching outline.
- **Caption:** "Look familiar? The table you query on the right is the same table as on the left. It just isn't
  where the truth is kept."

### 5.1 The command

The admin types *1225.00* and saves (Day 14 March, *spring campaign*).

- **Left:** `ProductService.changePrice(id, 1225.00)`.
- **Right:** a blue `ChangeProductPrice(PRD-7f3a, 1225.00)` card flies from the UI to the decider box.
- **Caption:** "Right: the request is a command, a wish that may still be refused."

### 5.2 Load

- **Left:** `SELECT … FROM products WHERE id = 'PRD-7f3a'`; the row slides into an `@Entity Product` box.
- **Right:** in `products_events`, the rows for `PRD-7f3a` light up, sort by `event_order`, and slide into the decider
  as a list.
- **Caption:** "Left loads the latest state. Right loads what happened, in order."

### 5.3 Decide

- **Left:** `product.price = 1225.00`.
- **Right:** the decider box opens. It reads the last event that carries a price (*1056.00*), compares it with
  the command (*1225.00*), and emits an orange `ProductPriceChanged(PRD-7f3a, 1225.00)`. A small callout plays
  the alternative: "same price again → no event", because the decider is idempotent.
- **Caption:** "A decision is a function: command + past events → new events, or none."

### 5.4 Write

- **Left:** `UPDATE products SET price = 1225.00, version = 1`. The old *1056.00* flashes red, is struck through,
  and fades.
- **Right:** `ProductPriceChanged` appends as
  `global_order 23 · PRD-7f3a · event_order 1 · ProductPriceChanged · {price 1225.00}` (`event_order` is the next
  in this product's stream; `global_order` is the next across all products). *1056.00* is still there
  in the row above.
- A shared callout spans both panes: "Both sides guard against a concurrent edit: `@Version` on the left; on the
  right, each `event_order` can exist only once per stream, so a racing append fails with
  `OptimisticAppendToStreamException`."
- **Caption:** "Left overwrites. Right appends."

### 5.5 Project

- **Right:** `ProductsForSaleViewProjection` (badge: `ViewEventProcessor`) picks up the event:
  `UPDATE products_for_sale_view SET price = 1225.00, version = 1`. In the view, *1056.00* flashes red and fades,
  exactly like on the left.
- **Caption:** "The view is allowed to forget, because the event store never does."

### 5.6 ◆3: the ceremony, counted honestly

Two code panels rise.

- **Left:** the CRUD method, about five lines (*illustrative*):

  ```kotlin
  @Transactional
  fun changePrice(id: ProductId, price: Amount) {
      val product = products.findById(id).orElseThrow()
      product.price = price
  }
  ```

- **Right:** the real decider and the real projection handler, trimmed only of comments:

  ```kotlin
  class ChangeProductPriceDecider : Decider<ChangeProductPrice, ProductEvent> {
      override fun handle(cmd: ChangeProductPrice, events: List<ProductEvent>): ProductPriceChanged? {
          if (events.isEmpty()) throw ProductHasNotBeenAddedException(cmd.id)
          val currentPrice = events.last { it is HasProductPrice } as HasProductPrice
          return if (currentPrice.price.compareTo(cmd.price) == 0) null
                 else ProductPriceChanged(cmd.id, cmd.price)
      }
  }

  // ProductsForSaleViewProjection : ViewEventProcessor
  @MessageHandler
  fun on(e: ProductPriceChanged, message: OrderedMessage) {
      val existing = repository.findById(e.id.toString()).orElse(null) ?: return
      if (existing.version >= message.order) return   // already applied
      existing.price = e.price
      existing.version = message.order
      repository.save(existing)
  }
  ```

- **Caption:** "Yes, more lines. In return you get a named fact, a decision you can test without a database,
  and a view you can throw away and rebuild."

### 5.7 Symmetry

The right pane re-arranges into a mirror: the `products_events` list in the middle, the decider on its left, the
projection and the chapter 2–4 processors on its right. One event slides out in both directions at once.

- **Caption:** "The write side reads the events to *decide*. The read side reads the same events, in the same
  order, to *show* and to *react*. One mechanism."

### 5.8 Replay

- **Right:** `products_for_sale_view` is wiped (red). The projection's subscription is reset; its handler for the
  reset deletes the rows (the real `onSubscriptionsReset` does exactly that), the resume marker jumps back to
  `global_order 1`, the first event of the `Products` type, and every product's events stream through again. The row comes back as
  `1225.00 · version 1`, identical.
- **Left:** the `products` row is wiped. A "restore from backup?" prompt appears, and stays.
- **Caption:** "On the right, the tables you query are disposable. Chapter 8 uses that to build one nobody asked
  for on day one."

### 5.9 Landing

**Caption:** "Event sourcing is the table you already know, plus the journal that produced it."

---

## Storyboard: chapter 6, "Aggregate vs decider/slices"

Two legitimate ways to turn events into the state a decision needs. Both are event sourcing and both ship in
Essentials. The chapter shows why one stays small and the other tends to grow.

**Track:** `Event sourced`. The right pane splits horizontally: **top** a classic aggregate, **bottom** deciders
per slice.
**Left:** the CRUD `OrderService` and `@Entity Order`.

### 6.0 Re-establishing shot

The `ORD-1042` stream in `orders_events`: `ShippingDetailsAdded`, `PaymentDetailsAdded`, `OrderPlaced`.

- **Caption:** "Every command needs some state to decide with. How much, and where does it live?"

### 6.1 The classic aggregate

- **Right top:** a class box `Order : AggregateRoot` (*illustrative*; the webshop uses deciders) with three
  sections: *fields*, *command methods*, *event handlers*. To handle `PlaceOrder`, **all** events of the stream
  run through **all** event handlers, filling **all** fields, and then `place()` runs.
- **Left:** `@Entity Order` is loaded with every column for the same command.
- **Caption:** "An aggregate is the entity you know, rebuilt from events instead of loaded from a row. Familiar,
  and easy to start with."

### 6.2 It grows

The requirements inbox replays its cards (shipping and payment details, place, hold outcome, shipped).
Each card adds to the aggregate a field or two, a command method, and an event handler. A line-count meter on the
class climbs. The CRUD entity and `OrderService` on the left climb at the same rate.

- **Caption:** "Every requirement about orders lands in the same class. To place an order you now rebuild
  everything, including fields only shipping cares about."

### 6.3 ◆4, part 1: the aggregate after the cards

```kotlin
 class Order : AggregateRoot<OrderId, OrderEvent, Order> {
     private var shippingAddress: Address? = null
     private var paymentMethod: PaymentMethod? = null
     private var placed = false
+    private var holdOutcome: HoldOutcome? = null
+    private var shipped = false
     …
+    fun recordHoldOutcome(outcome: HoldOutcome) { … }
+    fun markShipped() { … }
     …
+    @EventHandler private fun on(e: CreditCardHoldPlaced) { … }
+    @EventHandler private fun on(e: OrderShipped) { … }
 }
```

(*illustrative*, in the shape of the Essentials `AggregateRoot`.)

### 6.4 The event model

The right bottom expands into an **event model**, in Event Modeling layout: a timeline running left to right,
with the UI and automation lane on top, commands (blue) and views (green) in the middle, and events (orange) in
per-stream lanes at the bottom. It is cut into vertical **slices**, one per command or view, labelled with their
real directory names: `add_shipping_details_to_order`, `add_payment_details_to_order`, `place_order`,
`hold_funds_on_order_placed`, `place_hold_on_credit_card`, and the chapter 3 email automation. `cancel_order`
joins at 6.7, as the new requirement.

Each slice's decider box has a **state gauge** showing how much state it needs. These are all real:

| Slice | State it builds | How |
|---|---|---|
| `place_order` | none | asks the event list directly: any `OrderPlaced`? any `ShippingDetailsAdded`? any `PaymentDetailsAdded`? |
| `cancel_order` | none | any `OrderCancelled`? any `OrderPlaced`? |
| `change_product_price` | one value | the last event that carries a price |
| `remove_item_from_shopping_basket` | a small map | `BasketLinesEvolver` folds the basket events into `Map<ProductId, List<Amount>>` |

Next to them, the aggregate's gauge is full.

- **Caption:** "A decider builds only the state its own decision needs, and often that is no state at all."

### 6.5 ◆4, part 2: one slice, whole

The real `PlaceOrderDecider` (comments trimmed), beside the aggregate's `place()` and the eight fields it sits
among:

```kotlin
class PlaceOrderDecider : Decider<PlaceOrder, OrderEvent> {
    override fun handle(cmd: PlaceOrder, events: List<OrderEvent>): OrderPlaced? {
        if (events.any { it is OrderPlaced }) return null   // a double click, not a second order
        if (events.none { it is ShippingDetailsAdded })
            throw OrderIsNotReadyToBePlacedException(cmd.id, "no shipping details have been added")
        if (events.none { it is PaymentDetailsAdded })
            throw OrderIsNotReadyToBePlacedException(cmd.id, "no payment details have been added")
        return OrderPlaced(cmd.id)
    }
}
```

When the evolver earns its place, it is just as small: show `BasketLinesEvolver` (a `when` over three events)
beside `RemoveItemFromShoppingBasketDecider`, which calls `Evolver.applyEvents(basketLinesEvolver(), emptyMap(), events)`.

### 6.6 Thin coupling

Arrows animate across the event model: `OrderPlaced` → the `hold_funds_on_order_placed` automation →
`PlaceHoldOnCreditCard` → `CreditCardHoldPlaced` → the chapter 3 email processor. Slices touch each other **only**
through commands and events; a slice never reads another slice's state. The directory tree fades in beside it:
`sales/use_cases/place_order/`, `payment/automations/hold_funds_on_order_placed/`, … with only `events/` and
`types/` shared between bounded contexts.

- **Caption:** "One slice, one directory, one decision. The events are the contract between them."

### 6.7 A new requirement: "Customers can cancel a placed order"

- **Right top:** the aggregate class opens again: a field, a method and a handler are added to the shared class.
  Every other command method sits in the blast radius (an amber wash over the whole class).
- **Right bottom:** a new slice column, `cancel_order`, slides into the event model with its 8-line
  `CancelOrderDecider`. Nothing else changes colour.
- **Left:** a `status` column change on `orders`, and `OrderService` grows a `cancel()` method.
- **Counters:** "aggregate: 1 class edited" vs "slices: 1 directory added, 0 files edited".

### 6.8 The honest note

A gain/cost box:

- **Aggregate.** Gain: one place to look, natural for rich invariants that span many fields, and quick to start.
  Cost: grows with every requirement, rebuilds all state for every command, and becomes a merge hotspot.
- **Decider per slice.** Gain: small, independently testable, and added without touching neighbours. Cost: more
  files, and it pays off most when you model the events first.

- **Caption:** "Both are event sourcing. Choose by how much the model will grow."

---

## Storyboard: chapter 7, "Business events vs CRUD events"

The argument: an event is only useful if it says *what happened in the business*. A record of which columns
changed does not, so nobody can tell which reaction should follow.

**Track:** `Event sourced`.
**Left:** CRUD with an audit or change-data trail (*illustrative*, standing in for Hibernate Envers, triggers or
change data capture).

### 7.0 Re-establishing shot

`ORD-1042`, placed and held. The chapter 2–4 processors sit on the right; the warehouse and email boxes sit on
both panes' edges.

### 7.1 One form, one save

The support agent opens the admin "edit order" form on both panes, changes the shipping address, removes one
item, and presses **Save**.

- **Left:** one `UPDATE orders SET shipping_address = …` and one `DELETE FROM order_lines …`, in one transaction.
  The audit trail records
  `OrderUpdated { shipping_address: "Vesterbro 12" → "Nørrebro 7", lines: 3 → 2 }`.
- **Right:** the same form sends **two commands**, one per intent: `ChangeShippingAddress` and `RemoveOrderLine`
  (*illustrative*; the webshop has neither). Two decisions, two events: `ShippingAddressChanged` and
  `OrderLineRemoved`.
- **Caption:** "Same screen, same save. Left records *what changed*. Right records *what happened*."

### 7.2 Who should react?

Downstream boxes light up with question marks on the left: warehouse? payment? email? A thought bubble over the
audit row reads: "address changed *and* a line removed: re-route the parcel? Refund? Release part of the card
hold? Is this a correction or a new address?" Every consumer has to reverse-engineer the intent from a diff,
and each does it differently.

On the right, each event has an obvious reaction, drawn as one arrow each:

| Event | Reaction |
|---|---|
| `ShippingAddressChanged` | warehouse re-labels the parcel |
| `OrderLineRemoved` | payment reduces the hold; email confirms the change |

- **Caption:** "A business event names the intent, so the right reaction is obvious, and adding the next one
  is easy."

### 7.3 The same trap inside event sourcing

The right pane briefly shows an anti-example, outlined in `--cost` red: `OrderUpdated { address, lines,
status }` appended to `orders_events`. The same question marks appear on the right.

- **Caption:** "Event sourcing doesn't save you from this by itself. CRUD-shaped events bring CRUD's problem
  with them. Name events after what the business says happened."

### 7.4 Real names from the webshop

A strip of real event names from the demo slides across: `ProductPriceChanged`, `ItemAddedToShoppingBasket`,
`ItemRemovedFromShoppingBasket`, `CheckOutRequested`, `OrderPlaced`, `OrderCancelled`, `CreditCardHoldPlaced`,
`CreditCardHoldRejected`.

- **Caption:** "Read them aloud. That is the business, in the past tense."

---

## Storyboard: chapter 8, "History, the bonus" (Day 60)

Back to the product from chapter 5. Its price has changed twice more since; on the left only the last value
survives.

**Track:** `Event sourced`.

### 8.0 Re-establishing shot

- **Left:** `products` row `PRD-7f3a · LG Superview · 1999.50`.
- **Right:** `products_for_sale_view` shows the same row. Above it, `products_events` holds the three events:
  `ProductAdded 1056.00` (1 Mar), `ProductPriceChanged 1225.00` (14 Mar, spring campaign),
  `ProductPriceChanged 1999.50` (2 Apr, supplier raised it).
- **Caption:** "Both shops show the same price today. Only one remembers how it got there."

### 8.1 Card, Day 60: the questions nobody asked on day one

Two cards arrive together:

- **"Show each product's price history on its page."**
- **"Marketing: which items did customers take out of their basket after a price rise?"**

### 8.2 CRUD: a migration

- **Left:** the migration rises as a diff panel.

  ```sql
  -- V7__product_price_history.sql
  CREATE TABLE product_price_history (
      product_id  VARCHAR(64)   NOT NULL,
      price       NUMERIC(19,2) NOT NULL,
      valid_from  TIMESTAMPTZ   NOT NULL
  );
  INSERT INTO product_price_history (product_id, price, valid_from)
  SELECT id, price, now() FROM products;   -- the only price we still have
  ```

  `changePrice()` (and the CSV import, and the admin edit) gain a line to write a history row from now on.
- A timeline under the left pane fills from **Day 60 onwards only**; 1 Mar to 2 Apr is a red, hatched gap
  labelled "overwritten".
- The basket question: the left pane looks for removed basket items and finds nothing, because `DELETE FROM
  basket_items` left no trace. A red "not recoverable" stamp lands on it.

### 8.3 Event sourcing: a new projection

- **Right:** a new box, `ProductPriceHistoryProjection` (*illustrative*), with an empty
  `product_price_history_view` table under it. Its resume marker starts at `global_order 1` of the `Products`
  aggregate type, because a new processor replays from the beginning by default
  (`isStartSubscriptionFromLatestEvent()` is `false`).
- The three events stream in, and the table fills: `1056.00 from 1 Mar`, `1225.00 from 14 Mar`,
  `1999.50 from 2 Apr`. The timeline under the right pane fills **from 1 Mar**, with no gap.
- A second new projection, `PriceRiseRemovals` (*illustrative*), subscribes to both `Products` and
  `ShoppingBaskets`. Replay finds every `ItemRemovedFromShoppingBasket` that followed a `ProductPriceChanged`
  upwards; the real event carries the price the item went in at, so the answer is exact.
- **Caption:** "The data was never lost, only never looked at in this shape. A new question is a new projection,
  filled from history as if it had always existed."

### 8.4 ◆5: migration vs projection

Side by side: the left `V7` migration plus the three write-path edits, against a single new class on the right
(*illustrative*, the shape of `ProductsForSaleViewProjection`):

```kotlin
@Service
class ProductPriceHistoryProjection(
    dependencies: ViewEventProcessorDependencies,
    private val history: ProductPriceHistoryRepository
) : ViewEventProcessor(dependencies) {
    override fun getProcessorName() = "ProductPriceHistoryProjection"
    override fun reactsToEventsRelatedToAggregateTypes() = listOf(SalesAggregateTypes.PRODUCTS)

    @MessageHandler
    fun on(e: ProductAdded, message: OrderedMessage) = record(e.id, e.price, message)

    @MessageHandler
    fun on(e: ProductPriceChanged, message: OrderedMessage) = record(e.id, e.price, message)
}
```

- **Caption:** "No write path changes. The new view gets its own table, created empty and filled by replay.
  It is disposable: get it wrong, fix the class, replay again."

### 8.5 The counterpart: automations start from now

A third card: **"Email customers when an item in their basket drops in price."** A new `EventProcessor`
attaches, and this time its marker jumps to the **end** of the stream: the webshop's own automations override
`isStartSubscriptionFromLatestEvent()` to `true`. A ghost of the alternative plays for a second: two years of
customers receiving price-drop emails at once, struck through in red.

- **Caption:** "Projections replay history. Automations usually shouldn't. It is one setting, and you choose it
  per processor."

---

## Storyboard: chapter 9, "The honest ledger"

**Track:** `Event sourced`.
**Toggles:** all live.

### 9.0 Re-establishing shot

The admin screen from chapter 5, and the `products_for_sale_view` fed by a `ViewEventProcessor`.

### 9.1 Read your own writes

The admin changes the price to *1999.50* and is redirected straight to the product list.

- **Left:** the list reads `products`, which committed with the save, so it shows *1999.50*.
- **Right:** the command commits; the projection runs a moment later on its own. The redirect wins the race: the
  list shows **1225.00** with the stale clock glyph, then flips to *1999.50*. The admin frowns (a small face icon)
  and presses save again; the decider sees the same price and appends nothing.
- **Caption:** "An async view can be a moment behind the write. Usually fine, sometimes confusing, never silent
  data loss."

### 9.2 The answer, per view

The same step replays with the projection's badge switched to `InTransactionEventProcessor`. The view update now
sits **inside** the command's transaction bracket, so the event and the view row commit together, and the
redirect shows *1999.50* at once.

The cost is drawn into the bracket: it grows a little wider (the append now waits for the view), and a ghost run
shows a failing projection rolling back the whole command, event included.

A small three-row card sums up the choice:

| Processor | Consistency | Use for |
|---|---|---|
| `InTransactionEventProcessor` | strong: same transaction as the append; a failure rolls the command back | views the user reads straight after their own write |
| `ViewEventProcessor` | eventual, low latency; queued for retry on failure | most views, dashboards |
| `EventProcessor` | eventual, durable inbox per processor; retries and dead letters | automations and external calls |

Another option, mentioned in one line: keep the view async and let the UI wait until the view's `version` has
caught up with the write.

- **Caption:** "Consistency isn't a property of event sourcing. You choose it per view."

### 9.3 The ledger

Two boxes, `--gain` green and `--cost` red, filled one line per step.

**Costs:**
- **More ceremony.** Commands, events, deciders and projections: more types and more lines than a setter and a
  save (chapter 5 counted them).
- **Consistency is a choice.** Every view needs a deliberate in-transaction or async decision (9.2).
- **Events are a contract, forever.** Persisted events are read back for the life of the system: renaming one is
  a data change, not a refactor, and with Jackson 3 even a constructor parameter's name is part of the stored
  JSON. Essentials stores the concrete class name and does no upcasting.
- **A different way to model.** Thinking in events and slices takes practice; it pays off most when the events
  are modelled first.

**Gains:**
- A new reaction is a new processor, and no write path changes (chapters 2–3).
- Side effects run in their own transaction, with retries and dead letters built in (chapters 3–4).
- One write gives the state, the history and the events to publish, with nothing to drift (chapter 4).
- The audit trail is proven, because every decision is made by reading it (chapter 4).
- Decisions are small, pure and testable without a database (chapters 5–6).
- New questions are new projections, replayed from history (chapter 8).

- **Caption:** "A little more ceremony. In return, change becomes additive."

### 9.4 Teaser

The "More ceremony" line pulses once more.

- **Caption:** "The biggest cost on this list is the one a machine is best at." → chapter 10.

---

## Storyboard: chapter 10, "Built by the plugin"

Chapter 9 named the costs honestly. This chapter answers the biggest one, the ceremony, by replaying the whole
film's design as an AI-assisted build. It is deliberately fair: **both** panes get an AI assistant, because an
LLM writes CRUD quickly too. The difference that survives is not how fast code gets written, but **how far each
change spreads**, and how much a reviewer has to read to trust it.

Everything the right-hand terminal does is something the essentials plugin really does: `/essentials:init`,
`/essentials:add-slice` and its per-kind variants, the `essentials-change` skill (a change described in prose,
no command), `/essentials:slice-map --html` and `/essentials:review`. The prompts on the left are *illustrative*.
The slice files shown follow the real layout and manifest shape of `examples/essentials-spring-examples/postgresql-cqrs`
and the plugin's worked example (`slice.yaml` plus a per-slice `CLAUDE.md` beside the source).

**Track:** `Event sourced`, plus a fourth marker, `Built by the plugin`, that appears for this chapter only.
**Frame addition:** a **terminal strip** docks at the bottom of each pane (Claude Code look: prompt line, short
replies, tool lines). The lane diagram shrinks above it, and a **directory tree** opens at the pane's side.
**Toggles:** hidden until 10.10.

### 10.0 Re-establishing shot

Chapter 9's ledger, with the cost line **"More ceremony"** pulsing.

- **Caption:** "The ceremony is real. Now look at who writes it."

### 10.1 Both teams get an assistant

The terminal strips slide up on both panes. Both panes reset to an empty project.

- **Caption:** "Both teams use an AI assistant. Writing code is cheap on both sides now. Watch where each
  change lands."

### 10.2 Day 0: the project

- **Right:** `/essentials:init`. Its questions appear as chips being answered: *Kotlin · WebFlux · PostgreSQL
  event-sourced · Docker Compose · slice-manifest lint gate*. Then the tool lines tick: *project rendered* →
  *stack lint ✓* → *build ✓* → *Spring context started ✓*. The tree shows the skeleton with an empty bounded-context
  package and a project `CLAUDE.md`.
- **Left:** *"Create a Spring Boot app with an Order entity and an order service."* A conventional skeleton
  appears: `OrderController`, `OrderService`, `Order`, `OrderRepository`.
- **Caption:** "Both start in seconds. The right one was also built, linted and started before it was handed
  over."

### 10.3 Card, Day 0: "Customers can place orders"

- **Right:** *"Customers can place orders."* The assistant answers with one line, *new capability → command
  slice `sales.place_order`*, and waits for a yes (the `essentials-change` behaviour). On yes, the tree grows
  one directory, file by file:

  ```text
  sales/use_cases/place_order/
    PlaceOrder.kt
    PlaceOrderDecider.kt
    PlaceOrderAPI.kt
    slice.yaml
    CLAUDE.md
  test/…/place_order/PlaceOrderDeciderTest.kt
  ```

  `slice.yaml` opens in a side panel, trimmed:

  ```yaml
  slice: sales.place_order
  kind: command
  bc: sales
  summary: Place an order whose shipping and payment details are complete.
  handles: [PlaceOrder]
  publishes: [OrderPlaced]
  endpoints:
    - { method: POST, path: "/api/orders/{id}/place", auth: user }
  invariants:
    - { id: INV-PO-1, text: "An order needs shipping and payment details", enforcedBy: PlaceOrderDecider }
  forbidden:
    - cross-slice-internal:sales
  ```

  In the lane diagram above, a slice column drops into the event model from chapter 6.
- **Left:** `placeOrder()` appears in `OrderService`, with the controller endpoint.
- **Counters** start under each tree: **files added**, **existing files edited**.

### 10.4 Fast-forward: chapter 2's cards as prompts

The day counter spins from Day 2 to Day 30. Each card becomes one prompt in **both** terminals, a beat apart.

| Card | Right: what the assistant creates | Left: what the assistant edits |
|---|---|---|
| Email when accepted | automation slice `sales/automations/send_order_confirmation/` | `OrderService.placeOrder()`, admin edit path, CSV import |
| Hold funds | automation slice `payment/automations/hold_funds_on_order_placed/`, translation slice `payment/external_systems/payment_gateway/` | the same three paths, plus a gateway client |
| Sales dashboard | view slice `sales/views/sales_dashboard/` (async projection) | the same three paths, plus a dashboard table |
| Tell the warehouse | translation slice `shipping/external_systems/order_management/` (the same shape as the `postgresql-cqrs` example's) | the same three paths, plus a Kafka producer |

- **Right:** each prompt adds **one new directory**, each with its own `slice.yaml` and `CLAUDE.md`. The
  *existing files edited* counter stays at **0**. Each new slice column clicks into the event model.
- **Left:** each prompt produces a diff that touches the same three files again. The *existing files edited*
  counter climbs. On the dashboard prompt, the diff covers checkout and admin edit; the CSV path gets the
  chapter 2 amber "?". Did the assistant find every path? The only way to know is to read all three.
- **Caption:** "The assistant is just as fast on both sides. On the left, every change lands in code that
  already works. On the right, it lands next to it."

### 10.5 Day 35: the twist, as a spoken change

- **Right:** *"The accepted email must only go out once the card hold succeeds, and customers whose card is
  declined should get their own email."* The assistant classifies before writing anything:

  > *Spans several slices: extend `sales.send_order_confirmation` (it consumes `CreditCardHoldPlaced` instead of
  > `OrderPlaced`), and a new automation slice `sales.send_order_declined` consuming `CreditCardHoldRejected`.
  > Proceed?*

  On yes: in `send_order_confirmation/slice.yaml` one line changes, `consumes: [OrderPlaced]` →
  `consumes: [CreditCardHoldPlaced]`, along with its handler; one new directory appears. The event model rewires
  one arrow and gains one column.
- **Left:** the reorder-and-branch diff from chapter 3, across all three paths.
- **Caption:** "The change is described once, in business words. The manifest says exactly which slice owns it."

### 10.6 Chapters 6 and 8, fast-forwarded

- *"Customers can cancel a placed order."* Right: *new capability → command slice `sales.cancel_order`* (a
  second intent is a second slice, never a second method on an existing decider). Left: a `cancel()` method
  and a `status` change in `OrderService`.
- *"Show each product's price history."* Right: view slice `sales/views/product_price_history/`; on start it
  replays from `global_order 1` and its table fills with full history (the chapter 8 animation, at speed).
  Left: the `V7` migration, the backfill gap, and an edit to every price-changing path.

### 10.7 The design, drawn from the manifests

- **Right:** `/essentials:slice-map --html`. The lane diagram dissolves and a **message-flow graph** assembles
  from the `slice.yaml` files: commands into slices, events out, the slices that react, the commands an automation
  dispatches, and the external systems the translation slices bridge. It is the event model from chapter 6, now
  covering every requirement in the film. One node is clicked to isolate its neighbourhood: `OrderPlaced` and
  everything that reacts to it.
- **Left:** a call graph of `OrderService` draws itself (*illustrative*): one node with arrows to SMTP, gateway,
  dashboard and Kafka, reached from three entry points.
- **Caption:** "The design you saw in chapters 2–8, generated from the slices themselves, so it can't drift from
  the code."

### 10.8 The review gate

A mistake is planted on the right: the dashboard view imports a class from inside `place_order/` instead of
reacting to its event.

- **Right:** `/essentials:review` runs. Script lines tick (*review-scan*, *stack-lint*, *slice-lint*,
  *slice-source*), and one finding appears citing the rule, slice-design §R4: the import crosses a slice
  boundary that `place_order/slice.yaml` forbids (`cross-slice-internal:sales`).
- **Left:** a review of the latest three-file diff. There is no declared boundary to check it against, so the
  reviewer reads all of it.
- **Caption:** "Generated code is checked against rules the project declared, not against a reviewer's memory."

### 10.9 Scoreboard

The terminals fold away and the two counters grow into a scoreboard, totalled over the whole fast-forward:

| | CRUD + assistant | Slices + plugin |
|---|---|---|
| Files added | few | many, all small |
| Existing files edited per requirement | the same three, every time | none, except the one deliberate extension in 10.5 |
| Where a reviewer must look | everything the diff touched, plus the paths it didn't | one directory and its `slice.yaml` |
| Design diagram | drawn by hand, if at all | generated from manifests |

The numbers in the first two rows are computed by the page from the steps it just played, not typed in.

- **Caption:** "AI makes writing code cheap on both sides. What it can't make cheap is a change that spreads."

### 10.10 The ledger, revised

Chapter 9's ledger returns.

- **"More ceremony"** is struck through and rewritten: **"Ceremony generated, and checked."**
- **"A different way to model"** stays, in full colour, with a new note: *"You still decide what happened. The
  plugin writes how."*
- **"Consistency is a choice"** and **"Events are a contract"** stay as they are; the review gate catches some of
  the second's traps, and the ledger says *some*, not all.

- **Caption:** "Event sourcing's ceremony was its price of admission. With the plugin, you pay it in prompts."

### 10.11 Sandbox

The sandbox, as the finale: a running shop with every processor from the film, the three
chaos toggles, a control strip, and a **Replay** button on each projection.

- **Caption:** "Try to break it."

---

## Implementation

What the page is built from, beyond what the storyboards show.

### Deliverable and constraints

- **Output:** `docs/event-sourcing-vs-crud/index.html`, a single self-contained page next to this script. Open it
  in a browser; nothing needs building.
- **Tech:** vanilla HTML, inline SVG and vanilla JavaScript only. No Node, npm, bundler or framework (repository
  rule), and no PDF or print rendering. The only external resource is the Google Fonts stylesheet below, with
  real fallback stacks.
- **Language:** English only.

### Look and feel, from `presentation/module6/deck.html`

- **Canvas:** `#canvas` is `width: min(100%, 1600px); aspect-ratio: 16 / 9; max-height: calc(100vh - 48px);
  container-type: size; overflow: hidden`, centred in a grid body with 24 px padding. Every size is in `cqh`, so
  the whole canvas scales as one.
- **Type scale (`cqh`):** label 1.6 · small 2.3 · body 2.8 · lede 3.4 · h2 4.6 · h1 8.4 · code-sm 1.65 · code 1.85 ·
  code-lg 2.1.
- **Fonts:** non-code text follows the tw-brand `visual-style` skill: display
  `"Neue Haas Grotesk Display Pro", "Neue Haas Grotesk Text Pro", "Helvetica Neue", Helvetica, Arial, sans-serif`;
  body the same with Text Pro first. Neue Haas Grotesk is not a free web font, so it is used where installed;
  Helvetica Neue (the same design family) sits before the brand's Arial fallback. Mono is unchanged and the only
  web font loaded: `https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&display=swap`, stack
  `"IBM Plex Mono", "SFMono-Regular", Consolas, monospace`.
- **Palette:** dark by default, painted explicitly so it looks the same on any host theme, with a light palette
  toggled by `H` or the ☀ button (`:root[data-mode="light"]`) and remembered per browser. Rules, faint ink and
  wire strokes in both palettes are strong enough to survive a projector.

  | Token | Dark (default) | Light |
  |---|---|---|
  | `--ground` | `#0B0F17` | `#FFFFFF` |
  | `--surface` | `#131A26` | `#FFFFFF` |
  | `--surface-2` | `#0E141F` | `#F1EFEA` |
  | `--rule` | `#34425A` | `#9C9586` |
  | `--rule-soft` | `#232D3D` | `#CBC5B8` |
  | `--ink` | `#E6EBF2` | `#111A26` |
  | `--ink-dim` | `#9AA6B8` | `#3F4A58` |
  | `--ink-faint` | `#75839A` | `#646E7B` |
  | `--accent` | `#F2A33C` | `#B06A11` |
  | `--accent-dim` | `#8A6427` | `#C99A55` |
  | `--gain` | `#4FA870` | `#2B6A43` |
  | `--cost` | `#C9565A` | `#A3282E` |
  | `--code-str` | `#9FC6A6` | `#2B6A43` |
  | `--code-kw` | `#C9A2E0` | `#6D3C93` |
  | `--code-type` | `#7FB6D6` | `#1C5D85` |
  | `--code-ann` | `#F2A33C` | `#B06A11` |
  | `--code-com` | `#5C6879` | `#646E7B` |
  | `--wire-w` (wire stroke) | `1.8` | `2.2` |

  **Paper:** the caption strip, intro cards and concept cards use `.paper`, which redefines the tokens locally to
  light values on a cream ground (`#FBF8F1`; `#FFF4DF` in the light palette, so it still stands out).

- **Keys, matching the deck:** →, ↓, Space and PageDown step forward (or, when a step is paused between panes, play
  the waiting pane); ←, ↑ and PageUp step back; Home goes to the start, End to the last step; `H` toggles the light
  palette; `?` toggles a help overlay and Escape closes it. A small hint under the bottom-right controls shows
  `H light / dark · ? keys`.
  Clicking a progress-track stop jumps to that chapter.
- **Components** echoed from the deck: `.panel` with `figcaption` and a `.path` label, `.eyebrow`, `.lede`, and
  `tok-*` spans for code colouring.

### Real code the panels quote

All paths are under `examples/essentials-webshop-demo/src/main/kotlin/dk/trustworks/essentials/examples/webshop/`
on this branch. Trim comments only; change nothing else.

| Used in | File |
|---|---|
| 2.3, 6.6 | `payment/automations/hold_funds_on_order_placed/HoldFundsOnOrderPlacedPolicy.kt` (the `EventProcessor` shape for ◆2b) |
| 5.0–5.8 | `sales/events/ProductEvent.kt`, `sales/use_cases/change_product_price/ChangeProductPriceDecider.kt`, `sales/views/products_for_sale/ProductsForSaleViewProjection.kt`, `ProductForSaleView.kt` |
| 6.4–6.7 | `sales/use_cases/place_order/PlaceOrderDecider.kt`, `sales/use_cases/cancel_order/CancelOrderDecider.kt`, `sales/use_cases/remove_item_from_shopping_basket/RemoveItemFromShoppingBasketDecider.kt`, `BasketLinesEvolver.kt` |
| 7.4 | `sales/events/*.kt`, `payment/events/CreditCardHoldEvent.kt` (event names) |
| 10.3–10.4 | `slice.yaml` / `CLAUDE.md` shape: `examples/essentials-spring-examples/postgresql-cqrs/**/slice.yaml` and the plugin's `essentials-plugin/tests/fixtures/worked-example/` (the plugin is on `main`, not on this branch) |

Framework facts the script relies on, with their source:

- `EventBus.publish`, `addSyncSubscriber`, `AnnotatedEventHandler` + `@Handler`: `reactive/src/main/java/dk/trustworks/essentials/reactive/`.
- `Outbox` is point-to-point (one outbox, one consumer handler): `LLM/LLM-foundation.md`, "Outbox Pattern".
- The three processors and their trade-offs, and `InTransactionEventProcessor` rolling back the append on
  failure: `LLM/LLM-postgresql-event-store.md`, the processor comparison table and the "InTransactionEventProcessor"
  section.
- One events table per aggregate type (`"Orders"` → `orders_events`); `EventOrder` starts at 0 per aggregate,
  `GlobalEventOrder` at 1 per aggregate type; `OptimisticAppendToStreamException` on a racing append.
- A new processor starts at `GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER` unless `isStartSubscriptionFromLatestEvent()`
  returns `true` (default `false`): `AbstractEventProcessor.java`.

## Open questions

- **Phone layout.** Stack the panes vertically below about 600 px?
- **Hosting.** GitHub does not render `.html` from `docs/`. Options: GitHub Pages, open the file locally, or link
  it from the module6 deck.

## Design decisions

| Decision | Choice | Why |
|---|---|---|
| Viewer outcome | Sequenced: "CRUD plus a journal", then "a new requirement is a new subscriber or projection" | Trust comes first; the payoff lands harder once event sourcing no longer looks strange |
| Headline | Flexibility and reacting to business events; history is a bonus | Reacting to events is what changes everyday work; history follows from it |
| Opening | A requirements inbox drives the chapters | Tension from the first step, without giving the ending away |
| Layout | Two panes plus a three-stop progress track | Keeps the side-by-side comparison while showing which stage the right pane is in |
| Playback | Chapters plus step-through | The presenter sets the pace; a reader can jump to any chapter |
| Aggregate vs decider | A chapter of its own (6) | Both are event sourcing, and why one stays small needs room |
| Fidelity | Real webshop names where they exist; invented parts tagged *illustrative* | Every box traces to running code or says that it doesn't |
| "Order accepted" | The Day 35 incident moves the email from `OrderPlaced` to `CreditCardHoldPlaced` and adds a declined email | A believable bug both sides share, so the difference is in the fix |
| Code | Diffs at a few key beats only | Code appears where it proves a point |
| Vocabulary | Generic box labels; the Essentials API appears in the diffs | Readable for newcomers, still tied to the framework |
| Failures | Chaos toggles, each live once its side effect exists in the story | The viewer can break things, at the point where it means something |
| Chapters | Ten: business vs CRUD events kept as a chapter of its own, and chapter 10 answering the ledger | The costs are named on screen, not in an appendix |
| Twist day | Day 35 | After chapter 2's Days 2–30 |
| Event-driven plumbing (chapter 2) | Essentials as it is: `eventBus.publish(...)` in the write path, one `AnnotatedEventHandler` per side effect registered as a sync subscriber, each forwarding to its own `Outbox` in the same transaction | What Essentials offers without event sourcing; one handler per side effect keeps chapter 2's win visible |
| Chapter 5 scene | A price change (`ChangeProductPrice` → `ProductPriceChanged` → `products_for_sale_view`) | All real webshop code, and the thread runs on to chapter 8's price history |
| Chapter 6 state examples | The real deciders: `place_order` and `cancel_order` need no state, `change_product_price` one value, `remove_item_from_shopping_basket` a small map via `BasketLinesEvolver`; the aggregate is *illustrative* | The real deciders already span the full range |
| Chapter 6 new requirement | "Cancel a placed order", the real `cancel_order` slice | It needs no other bounded context's state, like the real slice |
| Day 60 questions | Price history, and basket removals after a price rise | Questions a CRUD shop really can't answer after the fact |
| Projection vs automation start | Shown explicitly in 8.5: projections replay from `global_order 1` (the default), automations start from the latest event (`isStartSubscriptionFromLatestEvent()` = `true`, as the webshop's automations do) | Otherwise a reader would assume a new email processor replays years of history |
| Event store on screen | One table per aggregate type (`orders_events`, `products_events`); `event_order` from 0 per stream, `global_order` from 1 per aggregate type | How Essentials stores events |
| AI-assisted build | Chapter 10, after the ledger, with an AI assistant on **both** panes; the right uses the real plugin commands and the `essentials-change` skill | A fair comparison: an assistant writes CRUD quickly too |
| What AI does not remove | "A different way to model" stays on the ledger | The human still decides which events happened |
| Sandbox | The finale, at the end of chapter 10 | It comes after the chapter that answers the ledger |
| Slice files in chapter 10 | Layout and `slice.yaml` shape of `examples/essentials-spring-examples/postgresql-cqrs` and the plugin's worked example | Those have per-slice manifests |
| In-transaction processor | Introduced in chapter 9 as the answer to read-your-own-writes; chapter 4's table only names it | It answers a problem the reader has just seen |
| Processors in chapter 4 | Each handler becomes an `EventProcessor`, `ViewEventProcessor` or `InTransactionEventProcessor`, badged by kind | The kind is a choice that trades latency against consistency |
| Pacing within a step | Narrated beats: the left pane plays, then the step waits for → before the right pane plays; the playing pane's caption sentence lights up, that pane is spotlighted and the other dims | One thing moves at a time, and the presenter can talk between the two |
| Pane order | A step that plays both panes plays the left first; a step where only the right pane changes plays it alone | The same rhythm throughout: CRUD first, then the alternative |
| Caption placement | Under the requirements inbox, above the panes, in two columns aligned with the panes | Read before the animation, next to the pane it describes |
| Chapter transitions | An intro card per chapter: *So far*, *Now*, *Watch for* | The reason for each stage change is explicit |
| Results on screen | Written inside the box they belong to (an external system's status line, a handler's status line); the remaining callouts place themselves clear of boxes | Nothing on screen covers anything else |
| Review finding id (10.8) | Cites the plugin's slice-design rule §R4 | No verifiable `ESS-…` id exists for a cross-slice import |
| When "cancel" arrives in chapter 6 | Only at 6.7, as the new requirement; the aggregate grows through *hold outcome* and *shipped* before it | Cancel is the new requirement there, so it can't already be built |
| Handler labels | Short: *Accepted email*, *Hold funds*, *Sales dashboard*, *Order management*, *Declined email* | They fit the handler column |
| Projector legibility | A light palette behind a visible ☀ button as well as `H`; strong rules and ink, and thicker wires, in both palettes | Grey and dark-blue lines disappear on a projector; the dark palette still suits a dark room |
| Introducing text | The caption strip, intro cards and concept cards sit on light paper in both palettes | The eye goes to the text first |
| Explaining new concepts | A concept card per new idea (event, outbox, eventual consistency, event store, event processor, command, decider, projection, aggregate, slice), as its own step right after its first appearance, with an annotated example | The audience is new to these ideas, and a card is visible while presenting |
| Fonts | The Trustworks brand grotesk (tw-brand) for all non-code text; IBM Plex Mono for code and tables | Matches the Trustworks brand |
