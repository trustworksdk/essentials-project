/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.webshop.payment.views.captures_awaiting_outcome

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.payment.config.PaymentAggregateTypes
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureFailed
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Keeps [CaptureAwaitingOutcomeView] in step with the `CreditCardHolds` stream: one row while a capture is
 * outstanding, no row once it is settled either way.
 *
 * `requestedAt` is wall-clock time, and here that is not a violation of "never order by timestamps" - nothing
 * is *ordered* by it. It answers a different question: how long have we been waiting for an answer from a third
 * party? That is real elapsed time, and no event order can substitute for it.
 */
@Service
class CapturesAwaitingOutcomeProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: CaptureAwaitingOutcomeViewRepository
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "CapturesAwaitingOutcomeProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(PaymentAggregateTypes.CREDIT_CARD_HOLDS)

    @MessageHandler
    fun on(e: FundsCaptureRequested, message: OrderedMessage) {
        repository.save(
            CaptureAwaitingOutcomeView(
                id = e.id.toString(),
                idempotencyKey = e.idempotencyKey.toString(),
                amount = e.amount,
                requestedAt = OffsetDateTime.now()
            )
        )
    }

    @MessageHandler
    fun on(e: FundsCaptured, message: OrderedMessage) = repository.deleteById(e.id.toString())

    @MessageHandler
    fun on(e: FundsCaptureFailed, message: OrderedMessage) = repository.deleteById(e.id.toString())

    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        repository.deleteAll()
    }
}
