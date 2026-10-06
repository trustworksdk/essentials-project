package com.example.shop.orders.views.order_list;

import com.example.shop.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Projector for the order_list view slice — events in, read model out. A view slice NEVER produces
 * events (rules/slice-design.md § The four slice kinds).
 *
 * PROCESSOR CHOICE — the load-bearing decision for a view slice:
 *   - {@code ViewEventProcessor} (this template): async, low latency, eventually consistent,
 *     replayable. Correct for almost every read model.
 *   - {@code InTransactionEventProcessor}: synchronous, strongly consistent with the event append.
 *     Use ONLY when the read model must be current the moment the command API returns (and for
 *     transaction-time uniqueness enforcement).
 *   - plain {@code EventProcessor}: never for a view — that is for external integrations.
 *
 * WHY EVERY HANDLER HERE TAKES {@link OrderedMessage}: the second parameter is optional to the
 * dispatcher — a single-argument {@code @MessageHandler} is invoked perfectly well. It is a
 * <em>correctness</em> requirement for a projection specifically: {@code message.getOrder()} is the
 * {@code EventOrder} compared against the row's stored {@code version}, and that comparison is what
 * makes the projection idempotent under redelivery. Drop the parameter here and you have no way to
 * detect a replay. Handlers that need no ordering (a fire-and-forget publisher, say) may omit it.
 */
@Service
public class OrderListProjection extends ViewEventProcessor {

    private final DocumentDbRepository<OrderListView, String> repository;

    public OrderListProjection(ViewEventProcessorDependencies dependencies,
                              DocumentDbRepository<OrderListView, String> repository) {
        super(dependencies);
        this.repository = repository;
    }

    @Override
    public String getProcessorName() {
        return "OrderListProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void on(OrderPlaced event, OrderedMessage message) {
        // version = EventOrder. The `long` overloads of save/update exist precisely so Java never
        // constructs the Kotlin Version value class.
        var id = event.id().toString();
        var existing = repository.findById(id);

        if (existing == null) {
            repository.save(new OrderListView(id, "TODO"), message.getOrder());
        } else {
            if (existing.getVersionValue() >= message.getOrder()) {
                return;   // replay — already applied
            }
            existing.setStatus("TODO");
            repository.update(existing, message.getOrder());
        }
    }

    // TODO: one handler per event this view projects. Add each to slice.yaml `projections.from`.

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly.
     *
     * Called <strong>once per subscribed {@link AggregateType}</strong>, not once per reset. The
     * blanket {@code deleteAll()} below is only correct because this projection subscribes to a
     * single aggregate type. If you add a second type to
     * {@code reactsToEventsRelatedToAggregateTypes()}, delete only the rows belonging to
     * {@code aggregateType} here — otherwise resetting one subscription wipes the other's rows and
     * they are never replayed.
     *
     * {@code resubscribeFromAndIncluding} is the {@link GlobalEventOrder} the replay restarts from;
     * a partial reset should delete only from that point forward rather than everything.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType,
                                        GlobalEventOrder resubscribeFromAndIncluding) {
        repository.deleteAll();
    }
}
