package com.example.shop.orders.aggregates;

import com.example.shop.orders.events.OrderEvent;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The Order aggregate — and the consistency boundary for every change in the Orders bounded
 * context (rules/slice-design.md §R5, aggregate style).
 *
 * THIS IS THE BC'S SINGLE WRITE MODEL. §R5 sanctions exactly one write style per bounded context,
 * and on this lane that means one aggregate type: every command slice loads THIS class, calls one
 * method on it, and saves. A second aggregate type in this package is a second consistency boundary
 * and a different bounded context — {@code /essentials:slice-check} gate 14 reports a BC holding two
 * write designs as Blocking.
 *
 * STATE IS NEVER ASSIGNED BY A COMMAND METHOD. A command method validates, then calls
 * {@code apply(event)}; the {@code @EventHandler} methods at the bottom are the ONLY place a field is
 * written. The same handlers run when the aggregate is rehydrated from its stream, so replaying
 * history and handling a new command follow the identical path — which is what makes the stored
 * events the source of truth rather than a side effect of the state. If you assign a field outside an
 * {@code @EventHandler}, that change survives in memory and vanishes on reload.
 *
 * INVARIANTS LIVE HERE, NOT IN THE HANDLER. This is the whole point of the lane: the guard runs
 * *before* {@code apply}, so a rejected command leaves no trace in the stream. A slice handler that
 * contains an {@code if} about domain state has taken a decision away from the aggregate — move it
 * here (§ The aggregate's own bar).
 *
 * IT MUST NEVER NAME A COMMAND TYPE (§R4). Commands are unpacked by the slice that handles them and
 * arrive as plain parameters. {@code aggregates/} is checked for command-type leakage by gate 8(d),
 * exactly as {@code events/} is.
 *
 * THE TWO CONSTRUCTORS ARE NOT INTERCHANGEABLE. The single-argument one is used by Essentials to
 * rehydrate an existing instance before replaying its events, and must apply nothing. The other is
 * the creation path and emits the BC's first event.
 *
 * Reached only through {@link Orders}.
 */
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {

    // TODO: replace with this aggregate's real state. Only @EventHandler methods may write it.
    private String placeholder;

    /**
     * Rehydration constructor — used by Essentials before replaying the stream. Applies nothing.
     */
    public Order(OrderId aggregateId) {
        super(aggregateId);
    }

    /**
     * Creation constructor — the only place the aggregate comes into existence. Emits the first event.
     */
    public Order(OrderId orderId, String placeholder) {
        super(orderId);
        requireNonNull(placeholder, "No placeholder provided");
        apply(new OrderPlaced(orderId, placeholder));
    }

    /**
     * TODO: the ONE invariant method this bounded context's first command slice maps to.
     *
     * The boolean-returning shape is the idempotent form: it returns {@code false} when the state was
     * already reached, so a redelivered command is a no-op rather than a duplicate event. The command
     * bus delivers at least once — write every method here so a repeat is harmless.
     */
    public boolean applyPlaceholder(String newPlaceholder) {
        requireNonNull(newPlaceholder, "No newPlaceholder provided");
        if (newPlaceholder.equals(placeholder)) {
            return false;                      // already in this state — emit nothing
        }
        // TODO: guard the real invariant HERE, before apply(), so a rejection leaves no event behind.
        apply(new OrderPlaced(aggregateId(), newPlaceholder));
        return true;
    }

    public String placeholder() {
        return placeholder;
    }

    // ---- Event handlers: the ONLY place state is written. Also run during rehydration. ----

    @EventHandler
    private void on(OrderPlaced e) {
        placeholder = e.placeholder();
    }
}
