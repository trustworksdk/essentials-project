package com.acme.shop.orders.automations.fulfillment

import com.acme.shop.orders.events.OrderPlaced
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Automation slice — reacts to what happened and issues the next command.
 * `Event(s) -> [TodoList] -> Command`.
 *
 * An automation slice has **no external API** (rules/slice-design.md § The four slice kinds). It is
 * reached only by events; it never exposes an endpoint.
 *
 * `EventProcessor` (Inbox-backed) is the right base here: automations often call out or run long,
 * and they need redelivery. Use `ViewEventProcessor` only for read models.
 *
 * Every handler MUST be idempotent — the Inbox redelivers, and the same event will arrive twice.
 * Check whether the step already happened and return early.
 */
@Service
class FulfillmentProcessor(
    dependencies: EventProcessorDependencies,
    private val todos: FulfillmentRepository
) : EventProcessor(dependencies) {

    override fun getProcessorName(): String = "FulfillmentProcessor"

    /** Automations commonly span aggregates — list every type whose events drive this process. */
    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(AggregateType.of("Orders"))

    @MessageHandler
    fun on(event: OrderPlaced, message: OrderedMessage) {
        val todo = todos.findById(event.id.value) ?: FulfillmentTodoList(event.id.value)

        if (todo.started) return                       // idempotent — already handled
        todo.started = true

        if (todo.canProceed()) {
            // Fire-and-forget on the durable command bus; survives restarts.
            commandBus.sendAndDontWait(/* TODO: the next command */ Any())
            todo.dispatched = true
        }
        todos.save(todo)
    }

    // TODO: a handler per event that advances this process, including the failure paths.
    //       On terminal failure, issue the compensating command rather than leaving the process stuck.

    /**
     * Delayed commands: `commandBus.sendAndDontWait(command, Duration.ofMinutes(15))` persists the
     * command on the command bus's durable queue (the `DurableLocalCommandBus`'s `DurableQueues`
     * command queue, not this processor's Inbox) and delivers it after the delay. Handle it with
     * `@CmdHandler`.
     */

    override fun getInboxRedeliveryPolicy(): RedeliveryPolicy =
        RedeliveryPolicy.exponentialBackoff(
            Duration.ofMillis(200), // initialRedeliveryDelay
            Duration.ofMillis(200), // followupRedeliveryDelay
            1.1,                    // followupRedeliveryDelayMultiplier
            Duration.ofSeconds(3),  // maximumFollowupRedeliveryDelayThreshold
            20                      // maximumNumberOfRedeliveries
        )
}
