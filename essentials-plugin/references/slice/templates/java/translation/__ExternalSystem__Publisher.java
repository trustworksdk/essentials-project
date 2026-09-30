package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}};

import {{packagePath}}.{{bc}}.events.{{Event}};
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
 * OUTBOUND half of the {{externalSystem}} ACL (the Outbox pattern).
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
public class {{ExternalSystem}}Publisher extends EventProcessor {

    // The translator is pure (no Spring), so it is constructed here rather than injected.
    private final {{ExternalSystem}}Translator translator = new {{ExternalSystem}}Translator();
    private final {{ExternalSystem}}Client client;

    public {{ExternalSystem}}Publisher(EventProcessorDependencies dependencies,
                                       {{ExternalSystem}}Client client) {
        super(dependencies);
        this.client = client;
    }

    @Override
    public String getProcessorName() {
        return "{{ExternalSystem}}Publisher";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("{{AggregateType}}"));
    }

    @MessageHandler
    void on({{Event}} event, OrderedMessage message) {
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
