package com.acme.shop.orders.automations.cancel_unpaid;

import com.acme.shop.orders.events.OrderPlaced;
import com.acme.shop.orders.use_cases.cancel_order.CancelOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CancelUnpaidProcessor extends EventProcessor {
    private final CancelUnpaidRepository todos;

    public CancelUnpaidProcessor(EventProcessorDependencies dependencies, CancelUnpaidRepository todos) {
        super(dependencies);
        this.todos = todos;
    }

    @Override
    public String getProcessorName() {
        return "CancelUnpaidProcessor";
    }

    /** RULE (subscriptions): a literal stream name. */
    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void on(OrderPlaced event) {
        todos.remember(event.id());
    }

    /** RULE (schedule, dispatches): milliseconds become ISO-8601; a constructed command is dispatched. */
    @Scheduled(fixedDelay = 900000, initialDelay = 60000)
    void sweep() {
        for (var id : todos.overdue()) {
            getCommandBus().sendAndDontWait(new CancelOrder(id, "unpaid"));
        }
    }
}
