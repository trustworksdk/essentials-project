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

package dk.trustworks.essentials.components.queue.springdata.mongodb;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage.DeliveryMode;
import dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues.DurableQueuedMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sixteen-parameter {@link DurableQueuedMessage} constructor is the worst case the parameter ceiling exists to
 * catch: two adjacent {@code boolean}s ({@code isBeingDelivered}/{@code isDeadLetterMessage} are not adjacent, but
 * both are bare booleans), two adjacent {@code int}s ({@code totalDeliveryAttempts}/{@code redeliveryAttempts}), three
 * {@link Instant}s and four {@link String}s. Every one of those transposes silently, in the builder just as easily as
 * at a call site.
 * <p>
 * These tests therefore give every field a <em>distinct</em> value and compare the builder's output against the
 * deprecated constructor's <strong>field by field via reflection</strong> — not through the accessors, which do not
 * cover all sixteen fields, and not through {@code equals}, which the class does not define.
 */
class DurableQueuedMessageBuilderTest {

    private static final QueueEntryId    ID                      = QueueEntryId.of("entry-1");
    private static final QueueName       QUEUE_NAME              = QueueName.of("TestQueue");
    private static final boolean         IS_BEING_DELIVERED      = true;
    private static final byte[]          MESSAGE_PAYLOAD         = "payload".getBytes();
    private static final String          MESSAGE_PAYLOAD_TYPE    = "com.example.PayloadType";
    private static final Instant         ADDED_TIMESTAMP         = Instant.ofEpochMilli(1_000);
    private static final Instant         NEXT_DELIVERY_TIMESTAMP = Instant.ofEpochMilli(2_000);
    private static final Instant         DELIVERY_TIMESTAMP      = Instant.ofEpochMilli(3_000);
    private static final int             TOTAL_DELIVERY_ATTEMPTS = 7;
    private static final int             REDELIVERY_ATTEMPTS     = 3;
    private static final String          LAST_DELIVERY_ERROR     = "boom";
    // Deliberately the opposite of IS_BEING_DELIVERED: two booleans set to the same value would make transposing
    // exactly those two invisible to the field-by-field comparison.
    private static final boolean         IS_DEAD_LETTER_MESSAGE  = false;
    private static final MessageMetaData META_DATA               = new MessageMetaData(Map.of("k", "v"));
    private static final DeliveryMode    DELIVERY_MODE           = DeliveryMode.IN_ORDER;
    private static final String          KEY                     = "order-key";
    private static final long            KEY_ORDER               = 42L;

    @SuppressWarnings("removal")
    private static DurableQueuedMessage viaConstructor() {
        return new DurableQueuedMessage(ID,
                                        QUEUE_NAME,
                                        IS_BEING_DELIVERED,
                                        MESSAGE_PAYLOAD,
                                        MESSAGE_PAYLOAD_TYPE,
                                        ADDED_TIMESTAMP,
                                        NEXT_DELIVERY_TIMESTAMP,
                                        DELIVERY_TIMESTAMP,
                                        TOTAL_DELIVERY_ATTEMPTS,
                                        REDELIVERY_ATTEMPTS,
                                        LAST_DELIVERY_ERROR,
                                        IS_DEAD_LETTER_MESSAGE,
                                        META_DATA,
                                        DELIVERY_MODE,
                                        KEY,
                                        KEY_ORDER);
    }

    private static DurableQueuedMessage viaBuilder() {
        return DurableQueuedMessage.builder()
                                   .setId(ID)
                                   .setQueueName(QUEUE_NAME)
                                   .setBeingDelivered(IS_BEING_DELIVERED)
                                   .setMessagePayload(MESSAGE_PAYLOAD)
                                   .setMessagePayloadType(MESSAGE_PAYLOAD_TYPE)
                                   .setAddedTimestamp(ADDED_TIMESTAMP)
                                   .setNextDeliveryTimestamp(NEXT_DELIVERY_TIMESTAMP)
                                   .setDeliveryTimestamp(DELIVERY_TIMESTAMP)
                                   .setTotalDeliveryAttempts(TOTAL_DELIVERY_ATTEMPTS)
                                   .setRedeliveryAttempts(REDELIVERY_ATTEMPTS)
                                   .setLastDeliveryError(LAST_DELIVERY_ERROR)
                                   .setDeadLetterMessage(IS_DEAD_LETTER_MESSAGE)
                                   .setMetaData(META_DATA)
                                   .setDeliveryMode(DELIVERY_MODE)
                                   .setKey(KEY)
                                   .setKeyOrder(KEY_ORDER)
                                   .build();
    }

    private static Map<String, Object> fieldsOf(DurableQueuedMessage message) {
        var values = new LinkedHashMap<String, Object>();
        for (var field : DurableQueuedMessage.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                values.put(field.getName(), field.get(message));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not read field " + field.getName(), e);
            }
        }
        return values;
    }

    @Test
    void test_the_builder_populates_every_field_exactly_as_the_deprecated_constructor_does() {
        var fromConstructor = fieldsOf(viaConstructor());
        var fromBuilder     = fieldsOf(viaBuilder());

        assertThat(fromBuilder.keySet()).isEqualTo(fromConstructor.keySet());
        fromConstructor.forEach((name, expected) ->
                                        assertThat(Objects.deepEquals(fromBuilder.get(name), expected))
                                                .as("field '%s': builder produced <%s>, constructor produced <%s>",
                                                    name, fromBuilder.get(name), expected)
                                                .isTrue());
    }

    @Test
    void test_every_field_carries_a_distinct_value_so_a_transposition_cannot_pass_unnoticed() {
        // Guards the test above rather than the production code: if two fields shared a value, swapping them in the
        // builder would still compare equal and the comparison would prove nothing about those two.
        var interestingValues = fieldsOf(viaConstructor()).entrySet().stream()
                                                          .filter(entry -> entry.getValue() != null)
                                                          .filter(entry -> !(entry.getValue() instanceof byte[]))
                                                          .map(Map.Entry::getValue)
                                                          .toList();

        assertThat(interestingValues)
                .as("two fields sharing a value would make a transposition of those two invisible")
                .doesNotHaveDuplicates();
    }

    @Test
    void test_unset_builder_values_match_the_field_initialisers_on_the_class() {
        var defaults = DurableQueuedMessage.builder().build();

        assertThat(defaults.getDeliveryMode()).isEqualTo(DeliveryMode.NORMAL);
        assertThat(defaults.getKeyOrder()).isEqualTo(-1L);
        assertThat(defaults.isBeingDelivered()).isFalse();
        assertThat(defaults.isDeadLetterMessage()).isFalse();
        assertThat(defaults.getTotalDeliveryAttempts()).isZero();
        assertThat(defaults.getRedeliveryAttempts()).isZero();
    }
}
