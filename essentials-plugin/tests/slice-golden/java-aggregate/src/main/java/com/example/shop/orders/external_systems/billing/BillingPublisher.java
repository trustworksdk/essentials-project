package com.example.shop.orders.external_systems.billing;

import com.example.shop.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * OUTBOUND half of the billing ACL (the Outbox pattern).
 *
 * Internal event in, external call out. {@code EventProcessor} is the correct base for anything
 * that leaves the process: it is Inbox-backed, so a failed external call is redelivered under the
 * redelivery policy rather than lost.
 *
 * Never {@code ViewEventProcessor} here — that is for read models and has no retry queue semantics
 * for outbound integration.
 *
 * Delete this file if the slice is {@code direction: inbound}.
 */
@Service
public class BillingPublisher extends EventProcessor {

    // The translator is pure (no Spring), so it is constructed here rather than injected.
    private final BillingTranslator translator = new BillingTranslator();
    private final BillingClient client;

    public BillingPublisher(EventProcessorDependencies dependencies,
                                       BillingClient client) {
        super(dependencies);
        this.client = client;
    }

    @Override
    public String getProcessorName() {
        return "BillingPublisher";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void on(OrderPlaced event, OrderedMessage message) {
        client.send(translator.toExternal(event));
    }

    @Override
    protected RedeliveryPolicy getInboxRedeliveryPolicy() {
        return RedeliveryPolicy.exponentialBackoff(
                Duration.ofMillis(200),  // initialRedeliveryDelay
                Duration.ofMillis(200),  // followupRedeliveryDelay
                1.1d,                    // followupRedeliveryDelayMultiplier
                Duration.ofSeconds(3),   // maximumFollowupRedeliveryDelayThreshold
                20);                     // maximumNumberOfRedeliveries
    }
}
