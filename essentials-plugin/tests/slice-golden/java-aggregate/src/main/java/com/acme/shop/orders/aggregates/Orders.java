package com.acme.shop.orders.aggregates;

import com.acme.shop.orders.events.OrderEvent;
import com.acme.shop.orders.types.OrderId;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Order} aggregates, and the owner of this BC's {@link AggregateType}
 * — the name its events are stored under, which every subscriber and projection refers back to.
 *
 * IT WRAPS {@link StatefulAggregateRepository} RATHER THAN EXPOSING IT. The wrapper exists so the
 * bounded context speaks its own language ({@code getOrder}, {@code hasOrder}) instead of a
 * generic {@code load}/{@code save}, and so the surface stays small enough to reason about. Do not
 * make the underlying repository a bean and inject it into slices directly — the whole BC's write
 * access should go through this one type.
 *
 * IT DOES NOT CONSTRUCT AGGREGATES. Constructing one is what emits the creation event, and that is a
 * *decision* — it belongs to the slice that took it, not to persistence. {@link #saveNew} persists an
 * instance the slice has already built.
 *
 * This class is the BC's only write path, so it is also the natural place for the cheap existence
 * check every creation slice needs for idempotency (§R5, aggregate style).
 */
@Component
public class Orders {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    private final StatefulAggregateRepository<OrderId, OrderEvent, Order> repository;

    public Orders(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        this.repository = StatefulAggregateRepository.from(eventStore,
                                                           AGGREGATE_TYPE,
                                                           reflectionBasedAggregateRootFactory(),
                                                           Order.class);
    }

    /**
     * Loads and rehydrates by replaying the stream. Throws when the aggregate does not exist.
     */
    public Order getOrder(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        return repository.load(orderId);
    }

    public Optional<Order> findOrder(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        return repository.tryLoad(orderId);
    }

    /**
     * Existence check for the idempotency guard in a creation slice.
     *
     * NOTE this rehydrates the aggregate. When a BC's streams grow long, replace it with a first-event
     * probe against the EventStore — {@code eventStore.fetchStream(AGGREGATE_TYPE, id,
     * LongRange.only(EventOrder.FIRST_EVENT_ORDER.longValue())).isPresent()} — which answers the same
     * question without replaying history. Keep the method name; only the body changes.
     */
    public boolean hasOrder(OrderId orderId) {
        return findOrder(orderId).isPresent();
    }

    public boolean isOrderMissing(OrderId orderId) {
        return !hasOrder(orderId);
    }

    /**
     * Persists an already-constructed aggregate. Constructing it — which is what emits the creation
     * event — is the owning slice's decision, so it happens there, not here.
     */
    public Order saveNew(Order order) {
        requireNonNull(order, "No order provided");
        return repository.save(order);
    }
}
