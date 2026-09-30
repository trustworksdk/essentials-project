package com.acme.shop.orders.automations.fulfillment;

import com.acme.shop.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
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
 * Automation slice — reacts to what happened and issues the next command.
 * {@code Event(s) -> [TodoList] -> Command}.
 *
 * An automation slice has NO external API (rules/slice-design.md § The four slice kinds). It is
 * reached only by events; it never exposes an endpoint.
 *
 * {@code EventProcessor} (Inbox-backed) is the right base here: automations often call out or run
 * long, and they need redelivery. Use {@code ViewEventProcessor} only for read models.
 *
 * Every handler MUST be idempotent — the Inbox redelivers, and the same event will arrive twice.
 * Check whether the step already happened and return early.
 */
@Service
public class FulfillmentProcessor extends EventProcessor {

    private final DocumentDbRepository<FulfillmentTodoList, String> todos;

    public FulfillmentProcessor(EventProcessorDependencies dependencies,
                              DocumentDbRepository<FulfillmentTodoList, String> todos) {
        super(dependencies);
        this.todos = todos;
    }

    @Override
    public String getProcessorName() {
        return "FulfillmentProcessor";
    }

    /** Automations commonly span aggregates — list every type whose events drive this process. */
    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AggregateType.of("Orders"));
    }

    @MessageHandler
    void on(OrderPlaced event, OrderedMessage message) {
        var id = event.id().toString();
        var todo = todos.findById(id);
        if (todo == null) {
            todo = new FulfillmentTodoList(id);
        }

        if (todo.isStarted()) {
            return;                     // idempotent — already handled
        }
        todo.setStarted(true);

        if (todo.canProceed()) {
            // Fire-and-forget on the durable command bus; survives restarts.
            // getCommandBus().sendAndDontWait(new TodoNextCommand(event.id()));
            todo.setDispatched(true);
        }

        if (todo.getVersionValue() < 0) {
            todos.save(todo, message.getOrder());
        } else {
            todos.update(todo, message.getOrder());
        }
    }

    // TODO: a handler per event that advances this process, including the failure paths.
    //       On terminal failure, issue the compensating command rather than leaving the process stuck.

    /**
     * Delayed commands: {@code getCommandBus().sendAndDontWait(command, Duration.ofMinutes(15))}
     * persists the command on the command bus's durable queue (the {@code DurableLocalCommandBus}'s
     * {@code DurableQueues} command queue, not this processor's Inbox) and delivers it after the
     * delay. Handle it with {@code @CmdHandler}.
     */

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
