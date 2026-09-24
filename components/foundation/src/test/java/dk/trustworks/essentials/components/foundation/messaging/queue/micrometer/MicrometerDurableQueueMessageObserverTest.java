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

package dk.trustworks.essentials.components.foundation.messaging.queue.micrometer;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.time.*;

import static dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryOutcome.*;
import static dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.MicrometerDurableQueueMessageObserver.*;
import static org.assertj.core.api.Assertions.assertThat;

class MicrometerDurableQueueMessageObserverTest {
    private static final QueueName QUEUE = QueueName.of("TestQueue");

    private SimpleMeterRegistry                    meterRegistry;
    private MicrometerDurableQueueMessageObserver observer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        observer = new MicrometerDurableQueueMessageObserver(meterRegistry, "TestModule");
    }

    private static QueuedMessage message(Object payload) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QUEUE)
                                   .setMessage(Message.of(payload))
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(1)
                                   .setRedeliveryAttempts(0)
                                   .build();
    }

    private double countFor(String reason) {
        var counter = meterRegistry.find(DEAD_LETTERED_COUNTER_NAME)
                                   .tag(REASON_TAG, reason)
                                   .counter();
        return counter != null ? counter.count() : 0d;
    }

    @Test
    void a_dead_letter_increments_the_counter_tagged_with_its_reason() {
        observer.messageDeadLettered(message("payload"), new IllegalArgumentException("boom"), PERMANENT_ERROR);

        assertThat(countFor("permanent_error")).isEqualTo(1d);
        assertThat(countFor("redeliveries_exhausted")).isZero();
    }

    @Test
    void the_two_dead_letter_reasons_are_counted_separately() {
        observer.messageDeadLettered(message("payload"), new IllegalArgumentException("boom"), PERMANENT_ERROR);
        observer.messageDeadLettered(message("payload"), new IllegalStateException("boom"), REDELIVERIES_EXHAUSTED);
        observer.messageDeadLettered(message("payload"), new IllegalStateException("boom"), REDELIVERIES_EXHAUSTED);

        assertThat(countFor("permanent_error")).isEqualTo(1d);
        assertThat(countFor("redeliveries_exhausted")).isEqualTo(2d);
    }

    @Test
    void the_counter_carries_the_queue_the_payload_type_and_the_module() {
        observer.messageDeadLettered(message("a-string-payload"), new IllegalStateException("boom"), PERMANENT_ERROR);

        var counter = meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).counter();

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTag(QUEUE_NAME_TAG)).isEqualTo(QUEUE.toString());
        assertThat(counter.getId().getTag(MESSAGE_PAYLOAD_TYPE_TAG)).isEqualTo(String.class.getName());
        assertThat(counter.getId().getTag(MODULE_TAG)).isEqualTo("TestModule");
    }

    @Test
    void successful_and_retried_deliveries_do_not_touch_the_dead_letter_counter() {
        observer.messageHandled(message("payload"), Duration.ofMillis(5));
        observer.messageRetried(message("payload"), new IllegalStateException("boom"), Duration.ofSeconds(1));
        observer.messageRedeliveryRequested(message("payload"));

        assertThat(meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).counter()).isNull();
    }

    @Test
    void the_module_tag_is_omitted_when_none_is_configured() {
        new MicrometerDurableQueueMessageObserver(meterRegistry, null)
                .messageDeadLettered(message("payload"), new IllegalStateException("boom"), PERMANENT_ERROR);

        assertThat(meterRegistry.find(DEAD_LETTERED_COUNTER_NAME).counter().getId().getTag(MODULE_TAG)).isNull();
    }
}
