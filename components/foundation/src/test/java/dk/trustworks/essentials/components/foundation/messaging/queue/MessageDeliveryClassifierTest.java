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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dk.trustworks.essentials.components.foundation.messaging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import static dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the classification rule that {@link DefaultDurableQueueConsumer} and {@link CentralizedMessageFetcher}
 * used to carry a copy of each.
 */
class MessageDeliveryClassifierTest {
    private static final QueueName QUEUE_NAME = QueueName.of("TestQueue");

    private static RedeliveryPolicy policyAllowing(int maximumNumberOfRedeliveries) {
        return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), maximumNumberOfRedeliveries);
    }

    @SuppressWarnings("removal")
    private static QueuedMessage messageWithDeliveryAttempts(int totalDeliveryAttempts) {
        return new DefaultQueuedMessage(QueueEntryId.random(),
                                        QUEUE_NAME,
                                        Message.of("a-payload"),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        null,
                                        totalDeliveryAttempts,
                                        totalDeliveryAttempts,
                                        false,
                                        false);
    }

    // ------------------------------------------------------------------------------------------------
    // The built-in permanent-error list
    // ------------------------------------------------------------------------------------------------

    static Stream<Throwable> builtInPermanentErrors() {
        return Stream.of(new DurableQueueDeserializationException("boom", QUEUE_NAME, QueueEntryId.random()),
                         new ClassCastException("boom"),
                         new NoClassDefFoundError("boom"),
                         new IllegalArgumentException("boom"),
                         new NumberFormatException("boom"));
    }

    @ParameterizedTest
    @MethodSource("builtInPermanentErrors")
    void a_built_in_permanent_error_is_dead_lettered_on_the_first_attempt(Throwable error) {
        var outcome = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1), error, policyAllowing(5));

        assertThat(outcome).isEqualTo(PERMANENT_ERROR);
        assertThat(outcome.isDeadLetter()).isTrue();
    }

    /**
     * The list is not symmetric. {@code DurableQueueDeserializationException} is matched on the thrown
     * exception only; {@code MismatchedInputException} on the root cause only; the rest on either.
     */
    static Stream<Throwable> builtInPermanentErrorsMatchedAsRootCause() {
        return Stream.of(new ClassCastException("boom"),
                         new NoClassDefFoundError("boom"),
                         new IllegalArgumentException("boom"),
                         new NumberFormatException("boom"));
    }

    @ParameterizedTest
    @MethodSource("builtInPermanentErrorsMatchedAsRootCause")
    void a_built_in_permanent_error_matches_as_the_root_cause_too(Throwable error) {
        var wrapped = new RuntimeException("outer", new IllegalStateException("middle", error));

        assertThat(MessageDeliveryClassifier.isBuiltInPermanentError(wrapped)).isTrue();
    }

    @Test
    void a_deserialization_exception_is_matched_only_when_it_is_the_thrown_exception() {
        var thrown  = new DurableQueueDeserializationException("boom", QUEUE_NAME, QueueEntryId.random());
        var wrapped = new RuntimeException("outer", thrown);

        assertThat(MessageDeliveryClassifier.isBuiltInPermanentError(thrown)).isTrue();
        assertThat(MessageDeliveryClassifier.isBuiltInPermanentError(wrapped))
                .as("DurableQueueDeserializationException is not on the root-cause half of the list")
                .isFalse();
    }

    @Test
    void mismatched_input_exception_is_permanent_as_a_root_cause() {
        var mismatchedInput = MismatchedInputException.from((com.fasterxml.jackson.core.JsonParser) null,
                                                            String.class,
                                                            "boom");

        assertThat(MessageDeliveryClassifier.isBuiltInPermanentError(new RuntimeException("outer", mismatchedInput)))
                .isTrue();
    }

    @Test
    void the_middle_of_the_cause_chain_is_not_examined() {
        // The built-in type sits between the outermost exception and the root cause, so it is invisible to the
        // classifier. Documented behaviour, not an accident — see MessageDeliveryClassifier's javadoc.
        var middleIsPermanent = new RuntimeException("outer",
                                                     new IllegalArgumentException("middle",
                                                                                  new IllegalStateException("root")));

        assertThat(MessageDeliveryClassifier.isBuiltInPermanentError(middleIsPermanent)).isFalse();
    }

    // ------------------------------------------------------------------------------------------------
    // The policy, and the fact that the built-in list beats it
    // ------------------------------------------------------------------------------------------------

    @Test
    void a_policy_that_classifies_the_error_as_permanent_dead_letters_it() {
        var policy = RedeliveryPolicy.builder()
                                     .setInitialRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelayMultiplier(1.0d)
                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMillis(10))
                                     .setMaximumNumberOfRedeliveries(5)
                                     .setDeliveryErrorHandler(MessageDeliveryErrorHandler.stopRedeliveryOn(IllegalStateException.class))
                                     .build();

        assertThat(MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1), new IllegalStateException("boom"), policy))
                .isEqualTo(PERMANENT_ERROR);
    }

    @Test
    void always_retry_on_does_not_override_the_built_in_list() {
        // The documented opt-out does not work for a type on the built-in list, because the list is OR-ed on
        // afterwards. This is the behaviour D1 changes; until then, pin it so the change is visible.
        var policy = RedeliveryPolicy.builder()
                                     .setInitialRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelayMultiplier(1.0d)
                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMillis(10))
                                     .setMaximumNumberOfRedeliveries(5)
                                     .setDeliveryErrorHandler(MessageDeliveryErrorHandler.builder()
                                                                                         .alwaysRetryOn(IllegalArgumentException.class)
                                                                                         .build())
                                     .build();

        assertThat(MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1), new IllegalArgumentException("boom"), policy))
                .isEqualTo(PERMANENT_ERROR);
    }

    // ------------------------------------------------------------------------------------------------
    // The attempt cap
    // ------------------------------------------------------------------------------------------------

    @Test
    void a_retryable_error_with_attempts_remaining_is_redelivered() {
        assertThat(MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(3), new IllegalStateException("boom"), policyAllowing(5)))
                .isEqualTo(RETRY);
    }

    @Test
    void a_retryable_error_is_dead_lettered_once_the_attempts_are_used_up() {
        // maximumNumberOfRedeliveries + 1 is the first delivery plus its redeliveries.
        var outcome = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(6), new IllegalStateException("boom"), policyAllowing(5));

        assertThat(outcome).isEqualTo(REDELIVERIES_EXHAUSTED);
        assertThat(outcome.isDeadLetter()).isTrue();
    }

    @Test
    void the_last_attempt_before_the_cap_still_retries() {
        assertThat(MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(5), new IllegalStateException("boom"), policyAllowing(5)))
                .isEqualTo(RETRY);
    }

    @Test
    void a_permanent_error_is_reported_as_permanent_even_when_the_attempts_are_also_used_up() {
        // Precedence matters for the operator-facing reason and, later, for D3's metric tag.
        assertThat(MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(99), new IllegalArgumentException("boom"), policyAllowing(5)))
                .isEqualTo(PERMANENT_ERROR);
    }
}
