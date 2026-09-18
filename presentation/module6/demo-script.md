# Live Demo Runbook — Module 6, Concepts And Answers

**Not part of the 36-minute deck.** Fourteen concept/answer pairs fill the slot, so there is no demo
segment on a slide. This is the runbook for a longer slot, for the room that asks to see it, and for
rehearsing the app before the talk.

Three beats, about 3 minutes in the browser, each with a fallback. If a beat fails, take the fallback and
keep moving — debugging in front of the room costs more than the beat is worth. Take it from pair 13
(composite UI and automations), which is where a live screen shows what a slide cannot.

## Before the talk

```bash
cd /workspace

# 1. cold start, so nothing from an earlier run is on screen
docker compose -f examples/essentials-webshop-demo/src/main/resources/compose.yml down -v

# 2. warm the images and the build, or the first run of the day takes minutes
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 \
  mvn -q -DskipTests -pl :essentials-webshop-demo install

# 3. start it, and leave it running
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 \
  mvn -pl :essentials-webshop-demo -Dspring-boot.run.profiles=compose spring-boot:run
```

Wait for `Started WebshopDemoApplicationKt`. It takes about 10 seconds once the containers are up, and
the containers take about 30 seconds from cold.

Open two tabs, in this order, and leave them open:

1. <http://localhost:8080/shop/index.html>
2. <http://localhost:8080/essentials/admin>

Zoom the browser to about 125%. The shop page is one screen at that size; the admin console needs
scrolling, which is fine.

**Start from an empty database if the machine has been used for other runs.** Either
`docker compose -p essentials-webshop-demo down -v` before starting, or run with
`-Dspring-boot.run.profiles=compose,compose-fresh`, which throws the data away when you stop. Leftover orders
from a previous run are harmless but they clutter the order-history panel on screen.

**Put a product in the catalogue before the talk.** The demo reads better when the first click is "add to
basket" rather than "add product". Press *Add product* once on the shop page and leave it there.

## Beat 1 — buy something (about 90 seconds)

On the shop page, left to right:

1. **Add to basket**, twice. The basket panel shows quantity 2 and a line total.
2. **Remove** once. Quantity 1. Say: *that removal recorded the price the unit went in at, which is why
   the total cannot drift.*
3. **Request checkout.** The order id appears, and the response carries the total the decider folded out
   of the basket's own events.
4. **Add shipping & payment details**, then **Place order**.
5. Watch the **Order summary** panel fill in: total first, then `placed = yes`, then `Payment: HELD`, then
   `Shipping` once the warehouse acts.

The line to say while it fills in: *nothing here is polling a service. Each of those fields arrives on its
own subscription, a moment after the fact was recorded — and `payment` decided to place that hold on its
own.*

The panels refresh themselves for a couple of seconds after every button, so there is no need to press
*Refresh* mid-sentence. The buttons are still there if a projection takes longer than the demo's patience.

**Fallback:** if the page does not respond, run the same flow with `curl` from
`presentation/module6/flow.sh` and read the JSON out loud. It is less pretty and just as
convincing.

## Beat 2 — be the warehouse (about 45 seconds)

Scroll to the **Warehouse** panel at the bottom.

1. The order is on the packaging list, in state `READY_TO_PACK`. Say why: *`shipping` never called
   `sales`. It projected `sales`' events into its own to-do list, and it would keep working if `sales`
   were down.*
2. **Package.** The row stays, now `PACKAGED`, and the only button on it is **Ship**. Worth a sentence:
   *the row is the unit of work, and it carries the order through both of its steps rather than
   disappearing after the first one.*
3. **Package.** The row does *not* become shippable. It goes to `AWAITING_PAYMENT`, and the **Captures
   awaiting outcome** panel gets a row. Say why: *a hold is a promise the bank can still break. Packing is
   what asks for the actual money, and the gateway answered `202 Accepted` — the outcome arrives on a webhook
   a second later. This is what every real card API does.*
4. The webhook lands, twice — the gateway duplicates every callback on purpose. The summary goes `CAPTURED`,
   the pending row clears, and only now does **Ship** appear. Worth a sentence: *the second delivery recorded
   nothing. At-least-once is what webhook delivery is, so the decider that records the outcome is idempotent —
   and it is exercised on every single order, not only in production.*
5. **Ship.** The row leaves the list — the work is finished — and the summary's shipping status becomes
   `SHIPPED: TRACK-…`. It is still in **Order history** below, which is the same read model queried without an
   id: an order leaves the warehouse's work list without leaving the business's record.

Then, if you have the time, show the external event on the topic:

```bash
docker run --rm --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic shipping-events \
  --from-beginning --max-messages 1 --timeout-ms 10000
# {"orderId":"order-…","trackingNumber":"TRACK-…","eventOrder":1}
```

Say: *one local transaction wrote the event; a subscription published it afterwards. That is the dual
write, answered.*

**Fallback:** skip the consumer command and point at the dual-write pair instead (slides 29–30). The Kafka
image pull is the slowest thing in this runbook — pull it before the talk.

## Beat 3 — look behind it (about 45 seconds)

Switch to the admin console tab.

1. **Event streams** — the five aggregate types, with their events. Open `ShoppingBaskets` and show the
   basket you just used: add, add, remove, checkout. Say: *that is the whole history, and it is the record
   rather than a log of it.*
2. **Subscriptions** — each projection and its resume point. Say: *this is how a restart does not lose or
   re-run anything.*
3. **Queues** — the inboxes behind the automation and the Kafka publisher, and the dead letter count.
   Mention it is zero, and that watching it is a standing commitment.

If the room is engaged and the clock allows, **reset one projection's subscription** and watch the read
model rebuild itself from the events. It is the most persuasive thirty seconds in the demo.

**Fallback:** the streams page alone is enough. Skip subscriptions and queues.

## Optional, if asked

**A declined card, and what to do about it.** Set the price to `25000`, add to basket, and run the flow
again. Three things happen, and they are worth taking in order:

1. The summary shows `Payment: REJECTED` with the gateway's own words underneath — the reason travelled
   on `CreditCardHoldRejected` and was projected onto the row, rather than being left in a log file.
2. The warehouse row for that order turns `BLOCKED` and loses its button. Say why: *`PackageOrderDecider`
   cannot check this. It sees one stream, and the decline is in another context's. So the check lives in
   the read model, which is allowed to be a moment stale — and the screen is the guard.*
3. **Cancel order**, offered on the summary only while the payment is refused. `sales` records
   `OrderCancelled` with the reason; `shipping` drops the order off its work list on its own, because it
   subscribes to that event and decided for itself what it means. `OrderPlaced` stays in the stream:
   *cancelling does not erase what happened, it records that a later decision overrode it.*

**The broker down.** `docker stop essentials-webshop-demo-kafka`, then ship an order. It ships — the write
only needed the database. Start the broker again and the external event goes out. This is the best answer
to "what if Kafka is down?" there is, but it costs a minute.

**An invoice order.** Choose `INVOICE` at checkout: no hold is placed at all, and the order still reaches
the warehouse. It shows that the automation is making a business decision, not performing a step. Note it also
ships without waiting for a capture — there is nothing to capture, and that took one line rather than a special
case.

**The bank authorizes and then refuses to settle.** Price something at `6000` — above the capture limit, below
the authorization limit. It holds, it packs, and *then* the settlement fails: the work item goes `BLOCKED` and
the parcel never leaves. The line to say: *this is why the capture happens at packing and not at checkout.
Nothing has to be un-shipped, because nothing shipped. The only thing we skipped was the last step, which is
the only one that was still cheap to skip.*

**The callback that never arrives.** Price something ending in `.13`. The gateway settles the charge and drops
the webhook on purpose, so nothing will ever tell the application what happened — no retry, no redelivery, no
dead letter, because no message was ever sent. The row sits in **Captures awaiting outcome** until the
reconciler asks the gateway directly. *A timeout is not a failure, it is an unknown, and the only honest way to
resolve an unknown is to ask the system that knows.* Best thirty seconds in the payment part of the talk, and
the one thing most integrations leave out.

**What happened the first time this policy was deployed.** Worth telling as a war story if the room is senior:
a new subscription starts at the beginning of the stream by default, so the capture policy replayed history and
charged nine orders that had already shipped. Projections want that default; a policy with side effects does
not. `isStartSubscriptionFromLatestEvent()`, and the general rule — *ask what replaying a subscription costs.*

## After the talk

```bash
# Ctrl-C the application, then
docker compose -f examples/essentials-webshop-demo/src/main/resources/compose.yml down -v
```

The volume is this demo's own, so wiping it does not touch the trading demo's database. Both demos bind
port 5432, so only one can run at a time.

## Retired segment

**Snapshots and closing books** were considered for this demo and deliberately left out: they are
secondary mechanisms, and the trading demo already shows them properly. If somebody asks, answer from the
"left out on purpose" slide (31) and offer `examples/essentials-trading-demo` afterwards.
