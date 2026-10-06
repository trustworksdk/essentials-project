package {{packagePath}}.orders.external_systems.warehouse

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * OUTBOUND half of the warehouse ACL: a placed order becomes a stock reservation.
 *
 * `EventProcessor` because the call leaves the process: it is Inbox-backed, so a failed reservation
 * is redelivered under [getInboxRedeliveryPolicy] instead of being lost. The warehouse
 * deduplicates on `reference`.
 */
@Service
class WarehousePublisher(
    dependencies: EventProcessorDependencies,
    private val client: WarehouseClient
) : EventProcessor(dependencies) {

    private val translator = WarehouseTranslator()

    override fun getProcessorName(): String = "WarehousePublisher"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(AggregateType.of("Orders"))

    @MessageHandler
    fun on(event: {{packagePath}}.orders.events.OrderPlaced) {
        client.reserve(translator.toReservation(event))
    }

    override fun getInboxRedeliveryPolicy(): RedeliveryPolicy =
        RedeliveryPolicy.exponentialBackoff(
            Duration.ofMillis(200), // initialRedeliveryDelay
            Duration.ofMillis(200), // followupRedeliveryDelay
            2.0,                    // followupRedeliveryDelayMultiplier
            Duration.ofSeconds(30), // maximumFollowupRedeliveryDelayThreshold
            20                      // maximumNumberOfRedeliveries
        )
}
