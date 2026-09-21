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
import dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryDecision.MessageDeliveryRule;
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

    private static RedeliveryPolicy policyWith(MessageDeliveryErrorHandler errorHandler) {
        return RedeliveryPolicy.builder()
                               .setInitialRedeliveryDelay(Duration.ofMillis(10))
                               .setFollowupRedeliveryDelay(Duration.ofMillis(10))
                               .setFollowupRedeliveryDelayMultiplier(1.0d)
                               .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMillis(10))
                               .setMaximumNumberOfRedeliveries(5)
                               .setDeliveryErrorHandler(errorHandler)
                               .build();
    }

    private static RedeliveryPolicy policyRetrying(Class<? extends Exception> type) {
        return policyWith(MessageDeliveryErrorHandler.builder().alwaysRetryOn(type).build());
    }

    private static QueuedMessage messageWithDeliveryAttempts(int totalDeliveryAttempts) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QUEUE_NAME)
                                   .setMessage(Message.of("a-payload"))
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(totalDeliveryAttempts)
                                   .setRedeliveryAttempts(totalDeliveryAttempts)
                                   .build();
    }

    private static MessageDeliveryOutcome outcomeOf(Throwable error, RedeliveryPolicy policy, int attempts) {
        return MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(attempts), error, policy).outcome();
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
        assertThat(outcomeOf(error, policyAllowing(5), 1)).isEqualTo(PERMANENT_ERROR);
    }

    @ParameterizedTest
    @MethodSource("builtInPermanentErrors")
    void a_built_in_permanent_error_is_found_anywhere_in_the_cause_chain(Throwable error) {
        var wrapped = new RuntimeException("outer", new IllegalStateException("middle", error));

        assertThat(outcomeOf(wrapped, policyAllowing(5), 1)).isEqualTo(PERMANENT_ERROR);
    }

    @Test
    void the_middle_of_the_cause_chain_is_examined_too() {
        // Before 0.60 only the thrown exception and the deepest root cause were tested, so this retried.
        var middleIsPermanent = new RuntimeException("outer",
                                                     new IllegalArgumentException("middle",
                                                                                  new IllegalStateException("root")));

        var decision = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1), middleIsPermanent, policyAllowing(5));

        assertThat(decision.outcome()).isEqualTo(PERMANENT_ERROR);
        assertThat(decision.rule()).isEqualTo(MessageDeliveryRule.BUILT_IN_PERMANENT_LIST);
        assertThat(decision.matchedType()).isEqualTo("IllegalArgumentException");
        assertThat(decision.causeChainDepth()).isEqualTo(1);
    }

    @Test
    void the_outermost_match_wins_when_the_chain_holds_several() {
        var chain = new IllegalArgumentException("outer", new NoClassDefFoundError("root"));

        var decision = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1), chain, policyAllowing(5));

        assertThat(decision.matchedType()).isEqualTo("IllegalArgumentException");
        assertThat(decision.causeChainDepth()).isZero();
    }

    @Test
    void a_cyclic_cause_chain_terminates() {
        // Throwable.initCause rejects self-causation but not a longer cycle, so the walk needs its own guard.
        var first  = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(outcomeOf(first, policyAllowing(5), 1)).isEqualTo(RETRY);
    }

    @Test
    void jackson_2_mismatched_input_is_matched_by_name() {
        var mismatchedInput = MismatchedInputException.from((com.fasterxml.jackson.core.JsonParser) null,
                                                            String.class,
                                                            "boom");

        var decision = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1),
                                                          new RuntimeException("outer", mismatchedInput),
                                                          policyAllowing(5));

        assertThat(decision.outcome()).isEqualTo(PERMANENT_ERROR);
        assertThat(decision.matchedType()).isEqualTo("MismatchedInputException");
    }

    // ------------------------------------------------------------------------------------------------
    // D1 — what a RETRY verdict can and cannot override
    // ------------------------------------------------------------------------------------------------

    @Test
    void always_retry_on_now_overrides_illegal_argument_exception() {
        // The headline fix: FailFast.requireNonNull and Kotlin require(...) both throw IllegalArgumentException,
        // and before 0.60 the documented opt-out could not win against the built-in list.
        var decision = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1),
                                                          new IllegalArgumentException("boom"),
                                                          policyRetrying(IllegalArgumentException.class));

        assertThat(decision.outcome()).isEqualTo(RETRY);
        assertThat(decision.rule()).isEqualTo(MessageDeliveryRule.POLICY_RETRY_OVERRIDE);
    }

    @Test
    void always_retry_on_overrides_class_cast_exception() {
        assertThat(outcomeOf(new ClassCastException("boom"), policyRetrying(ClassCastException.class), 1))
                .isEqualTo(RETRY);
    }

    @Test
    void always_retry_on_cannot_override_a_deserialization_failure() {
        var error = new DurableQueueDeserializationException("boom", QUEUE_NAME, QueueEntryId.random());

        assertThat(outcomeOf(error, policyRetrying(DurableQueueDeserializationException.class), 1))
                .isEqualTo(PERMANENT_ERROR);
    }

    @Test
    void always_retry_on_cannot_override_a_missing_class() {
        // NoClassDefFoundError is an Error, so it cannot even be named to alwaysRetryOn; retrying every
        // RuntimeException still must not resurrect it.
        assertThat(outcomeOf(new NoClassDefFoundError("boom"), policyRetrying(RuntimeException.class), 1))
                .isEqualTo(PERMANENT_ERROR);
    }

    @Test
    void a_retry_verdict_does_not_lift_the_redelivery_cap() {
        assertThat(outcomeOf(new IllegalArgumentException("boom"), policyRetrying(IllegalArgumentException.class), 6))
                .isEqualTo(REDELIVERIES_EXHAUSTED);
    }

    @Test
    void always_retry_keeps_meaning_no_opinion() {
        // alwaysRetry() is the builder default, so promoting it to RETRY would make deserialization failures
        // retry forever in every existing application.
        var error = new DurableQueueDeserializationException("boom", QUEUE_NAME, QueueEntryId.random());

        assertThat(outcomeOf(error, policyWith(MessageDeliveryErrorHandler.alwaysRetry()), 1))
                .isEqualTo(PERMANENT_ERROR);
    }

    @Test
    void a_handler_that_only_implements_is_permanent_error_still_works() {
        // The default verdict mapping: true -> PERMANENT_ERROR, false -> NO_OPINION.
        MessageDeliveryErrorHandler legacyHandler = (queuedMessage, error) -> error instanceof IllegalStateException;

        assertThat(outcomeOf(new IllegalStateException("boom"), policyWith(legacyHandler), 1)).isEqualTo(PERMANENT_ERROR);
        assertThat(outcomeOf(new RuntimeException("boom"), policyWith(legacyHandler), 1)).isEqualTo(RETRY);
        assertThat(outcomeOf(new IllegalArgumentException("boom"), policyWith(legacyHandler), 1))
                .as("false must keep meaning 'no opinion', so the built-in list still applies")
                .isEqualTo(PERMANENT_ERROR);
    }

    // ------------------------------------------------------------------------------------------------
    // The policy verdict and the attempt cap
    // ------------------------------------------------------------------------------------------------

    @Test
    void a_policy_that_classifies_the_error_as_permanent_dead_letters_it() {
        var decision = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1),
                                                          new IllegalStateException("boom"),
                                                          policyWith(MessageDeliveryErrorHandler.stopRedeliveryOn(IllegalStateException.class)));

        assertThat(decision.outcome()).isEqualTo(PERMANENT_ERROR);
        assertThat(decision.rule()).isEqualTo(MessageDeliveryRule.POLICY_VERDICT);
    }

    @Test
    void a_retryable_error_with_attempts_remaining_is_redelivered() {
        assertThat(outcomeOf(new IllegalStateException("boom"), policyAllowing(5), 3)).isEqualTo(RETRY);
    }

    @Test
    void a_retryable_error_is_dead_lettered_once_the_attempts_are_used_up() {
        assertThat(outcomeOf(new IllegalStateException("boom"), policyAllowing(5), 6)).isEqualTo(REDELIVERIES_EXHAUSTED);
    }

    @Test
    void the_last_attempt_before_the_cap_still_retries() {
        assertThat(outcomeOf(new IllegalStateException("boom"), policyAllowing(5), 5)).isEqualTo(RETRY);
    }

    @Test
    void a_permanent_error_is_reported_as_permanent_even_when_the_attempts_are_also_used_up() {
        assertThat(outcomeOf(new IllegalArgumentException("boom"), policyAllowing(5), 99)).isEqualTo(PERMANENT_ERROR);
    }

    // ------------------------------------------------------------------------------------------------
    // D3 piece 2 — the explanation that goes in the dead-letter log line
    // ------------------------------------------------------------------------------------------------

    @Test
    void the_explanation_names_the_rule_the_type_the_depth_and_the_attempt() {
        var wrapped = new RuntimeException("outer", new IllegalArgumentException("inner"));

        var describe = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(2), wrapped, policyAllowing(5))
                                                .describe();

        assertThat(describe).contains("PERMANENT_ERROR")
                            .contains("built-in permanent list matched IllegalArgumentException")
                            .contains("cause-chain depth 1")
                            .contains("attempt 2 of 6");
    }

    @Test
    void the_explanation_distinguishes_a_retry_override_from_an_ordinary_retry() {
        var overridden = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1),
                                                            new IllegalArgumentException("boom"),
                                                            policyRetrying(IllegalArgumentException.class));
        var ordinary = MessageDeliveryClassifier.classify(messageWithDeliveryAttempts(1),
                                                          new IllegalStateException("boom"),
                                                          policyAllowing(5));

        assertThat(overridden.describe()).contains("overrode the built-in permanent list");
        assertThat(ordinary.describe()).contains("no rule classified the error as permanent");
    }
}
