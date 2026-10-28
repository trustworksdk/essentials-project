package com.example.shop.orders.automations.fulfillment;

import com.example.shop.IntegrationTestBase;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CI-only behaviour check for the Java automation template (tests/slice-compile/README.md), on the decider and the
 * aggregate lane alike: each test asserts one `implemented` entry of references/slice/templates/behaviours.json
 * against the rendered, untouched skeleton, and the `// behaviour:` line above it names the entry.
 */
class FulfillmentBehavioursIT extends IntegrationTestBase {

    @Autowired
    private EventStore eventStore;

    @Autowired
    private DocumentDbRepository<FulfillmentTodoList, String> todos;

    @Autowired
    private FulfillmentProcessor processor;

    // behaviour: java/automation automation.records-progress
    @Test
    void the_triggering_event_starts_the_todo_list_keyed_by_the_aggregate_id() {
        var id = OrderId.random();

        eventStore.getUnitOfWorkFactory()
                  .usingUnitOfWork(() -> eventStore.appendToStream(AggregateType.of("Orders"), id, new OrderPlaced(id, "fulfil")));

        var todo = await().atMost(Duration.ofSeconds(30)).until(() -> todos.findById(id.toString()), Objects::nonNull);
        assertThat(todo.isStarted()).isTrue();
    }

    // behaviour: java/automation automation.idempotent-redelivery
    @Test
    void a_redelivered_event_returns_before_saving_again() {
        var id = OrderId.random();
        var event = new OrderPlaced(id, "redelivered");

        processor.on(event, OrderedMessage.of(event, id.toString(), 0));
        var first = todos.findById(id.toString());
        assertThat(first.isStarted()).isTrue();

        processor.on(event, OrderedMessage.of(event, id.toString(), 1));   // the Inbox delivers it again

        var after = todos.findById(id.toString());
        assertThat(after.getVersionValue()).isEqualTo(first.getVersionValue());
        assertThat(after.getLastUpdated()).isEqualTo(first.getLastUpdated());
        assertThat(after.isDispatched()).isEqualTo(first.isDispatched());
    }
}
