package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}}

import {{packagePath}}.{{bc}}.events.{{Event}}
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * OUTBOUND half of the {{externalSystem}} ACL (the Outbox pattern).
 *
 * Internal event in, external call out. `EventProcessor` is the correct base for anything that
 * leaves the process: it is Inbox-backed, so a failed external call is redelivered under the
 * redelivery policy rather than lost.
 *
 * Never `ViewEventProcessor` here — that is for read models and has no retry queue semantics for
 * outbound integration.
 *
 * Delete this file if the slice is `direction: inbound`.
 */
@Service
class {{ExternalSystem}}Publisher(
    dependencies: EventProcessorDependencies,
    private val client: {{ExternalSystem}}Client
) : EventProcessor(dependencies) {

    // The translator is pure (no Spring), so it is constructed here rather than injected.
    private val translator = {{ExternalSystem}}Translator()

    override fun getProcessorName(): String = "{{ExternalSystem}}Publisher"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(AggregateType.of("{{AggregateType}}"))

    @MessageHandler
    fun on(event: {{Event}}, message: OrderedMessage) {
        client.send(translator.toExternal(event))
    }

    override fun getInboxRedeliveryPolicy(): RedeliveryPolicy =
        RedeliveryPolicy.exponentialBackoff(
            Duration.ofMillis(200), // initialRedeliveryDelay
            Duration.ofMillis(200), // followupRedeliveryDelay
            1.1,                    // followupRedeliveryDelayMultiplier
            Duration.ofSeconds(3),  // maximumFollowupRedeliveryDelayThreshold
            20                      // maximumNumberOfRedeliveries
        )
}
