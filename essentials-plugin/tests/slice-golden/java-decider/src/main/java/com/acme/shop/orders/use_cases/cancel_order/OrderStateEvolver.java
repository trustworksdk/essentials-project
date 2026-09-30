package com.acme.shop.orders.use_cases.cancel_order;

import com.acme.shop.orders.events.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver;

import java.util.Optional;

/**
 * Pure left-fold {@code (event, state) -> state} rebuilding {@link OrderState} from the
 * stream.
 *
 * Owned by THIS slice — used by its Decider via
 * {@code EventStreamEvolver.applyEvents(evolver, events)}. Pure: no I/O, no validation, no side
 * effects. Validation is the Decider's job; the Evolver only says what is currently true.
 *
 * Moves to {@code use_cases/_shared/} only under the three-consumer promotion bar — see
 * {@link OrderState}.
 *
 * The {@code switch} is exhaustive over the sealed {@link OrderEvent} hierarchy, so adding a
 * variant makes the compiler point at every evolver that must consider it — which is the whole
 * reason the event parent is sealed.
 */
public class OrderStateEvolver implements EventStreamEvolver<OrderEvent, OrderState> {

    @Override
    public Optional<OrderState> applyEvent(OrderEvent event, Optional<OrderState> current) {
        return switch (event) {
            // TODO: one branch per event variant this BC emits, e.g.
            // case OrderCancelled e -> Optional.of(new OrderState(e.id(), "TODO"));
            default -> current;
        };
    }
}
