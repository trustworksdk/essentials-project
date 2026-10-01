# Slice: orders.fulfillment

**Kind:** automation
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- Every handler is idempotent — the Inbox redelivers, so the same event will arrive twice.
- Retries are bounded (`canProceed()` caps attempts); terminal failure issues a compensating command.

## Boundaries
**Reacts to:** `OrderPlaced` (TODO list every event that advances this process)
**Dispatches:** TODO the commands this automation issues, on the CommandBus
**External API:** none — an automation slice never exposes an endpoint
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`.
  - Never write to the event store directly; issue a command and let that slice's Decider decide.

## Data
**Owns (writes):** the `orders_fulfillment_todo` process state — no other slice reads it
**Reads:** its own process state

## Files
- `FulfillmentTodoList.java` — explicit process state with its guards (delete if stateless)
- `FulfillmentRepository.java` — persistence for the process state
- `FulfillmentProcessor.java` — `EventProcessor`; `@MessageHandler` methods take `OrderedMessage`
- test: `FulfillmentIT.java`

## Delayed commands
`commandBus.sendAndDontWait(command, Duration.ofMinutes(15))` persists the command and delivers it
after the delay; it survives restarts. Handle it with `@CmdHandler`.
