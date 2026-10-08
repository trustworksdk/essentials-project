package com.example.library.lending.views.loans_on_shelf;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.InTransactionEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import com.example.library.lending.config.LendingConfiguration;
import com.example.library.lending.events.BookReturned;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "library.shelf", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShelfCountProjection extends InTransactionEventProcessor {
    public ShelfCountProjection(EventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String getProcessorName() {
        return "ShelfCountProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(LendingConfiguration.COPIES);
    }

    @MessageHandler
    void on(BookReturned event, OrderedMessage message) {
    }
}
