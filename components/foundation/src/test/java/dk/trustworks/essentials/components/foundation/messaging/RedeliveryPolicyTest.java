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

package dk.trustworks.essentials.components.foundation.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins {@link RedeliveryPolicy#calculateNextRedeliveryDelay(int)}. Before 0.60 it returned
 * {@code initialRedeliveryDelay + followupRedeliveryDelay × multiplier} for every attempt after the first,
 * so neither an "exponential" nor a "linear" backoff ever grew.
 */
class RedeliveryPolicyTest {
    private static Duration[] delaysForAttempts(RedeliveryPolicy policy, int numberOfAttempts) {
        return IntStream.range(0, numberOfAttempts)
                        .mapToObj(policy::calculateNextRedeliveryDelay)
                        .toArray(Duration[]::new);
    }

    @Test
    void exponentialBackoff_uses_the_initial_delay_then_grows_the_followup_delay_by_the_multiplier() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(500),
                                                         Duration.ofMillis(500),
                                                         2.0d,
                                                         Duration.ofMinutes(1),
                                                         20);

        assertThat(delaysForAttempts(policy, 6)).containsExactly(Duration.ofMillis(500),
                                                                 Duration.ofMillis(500),
                                                                 Duration.ofSeconds(1),
                                                                 Duration.ofSeconds(2),
                                                                 Duration.ofSeconds(4),
                                                                 Duration.ofSeconds(8));
    }

    @Test
    void the_initial_delay_is_used_for_the_first_redelivery_regardless_of_the_followup_settings() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(100),
                                                         Duration.ofSeconds(3),
                                                         5.0d,
                                                         Duration.ofSeconds(10),
                                                         20);

        assertThat(policy.calculateNextRedeliveryDelay(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateNextRedeliveryDelay(1)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void the_followup_delay_is_capped_at_the_maximum_threshold() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(500),
                                                         Duration.ofMillis(500),
                                                         2.0d,
                                                         Duration.ofMinutes(1),
                                                         20);

        // 500ms × 2^7 = 64s is the first to exceed the 1 minute cap
        assertThat(policy.calculateNextRedeliveryDelay(7)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.calculateNextRedeliveryDelay(8)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.calculateNextRedeliveryDelay(9)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void a_followup_delay_above_the_threshold_is_capped_from_the_first_followup() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(100),
                                                         Duration.ofSeconds(5),
                                                         2.0d,
                                                         Duration.ofSeconds(2),
                                                         20);

        assertThat(policy.calculateNextRedeliveryDelay(1)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void a_very_large_attempt_count_yields_the_threshold_without_overflowing() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(500),
                                                         Duration.ofMillis(500),
                                                         2.0d,
                                                         Duration.ofMinutes(1),
                                                         Integer.MAX_VALUE);

        // 2^(n-1) passes Long.MAX_VALUE at n = 65 and double's range at n = 1025
        assertThat(policy.calculateNextRedeliveryDelay(64)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.calculateNextRedeliveryDelay(1_100)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.calculateNextRedeliveryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void a_threshold_beyond_the_range_of_nanoseconds_in_a_long_does_not_overflow() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(500),
                                                         Duration.ofMillis(500),
                                                         2.0d,
                                                         Duration.ofDays(365 * 1_000),
                                                         Integer.MAX_VALUE);

        assertThat(policy.calculateNextRedeliveryDelay(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.calculateNextRedeliveryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofDays(365 * 1_000));
    }

    @Test
    void a_multiplier_of_one_gives_a_constant_followup_delay() {
        var policy = RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(100),
                                                         Duration.ofMillis(750),
                                                         1.0d,
                                                         Duration.ofMinutes(1),
                                                         20);

        assertThat(delaysForAttempts(policy, 5)).containsExactly(Duration.ofMillis(100),
                                                                 Duration.ofMillis(750),
                                                                 Duration.ofMillis(750),
                                                                 Duration.ofMillis(750),
                                                                 Duration.ofMillis(750));
        assertThat(policy.calculateNextRedeliveryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void a_multiplier_below_one_never_shrinks_the_followup_delay() {
        // RedeliveryPolicyBuilder leaves the multiplier at 0.0 when it isn't set
        var policy = RedeliveryPolicy.builder()
                                     .setInitialRedeliveryDelay(Duration.ofMillis(100))
                                     .setFollowupRedeliveryDelay(Duration.ofMillis(300))
                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMinutes(1))
                                     .setMaximumNumberOfRedeliveries(20)
                                     .build();

        assertThat(delaysForAttempts(policy, 4)).containsExactly(Duration.ofMillis(100),
                                                                 Duration.ofMillis(300),
                                                                 Duration.ofMillis(300),
                                                                 Duration.ofMillis(300));
    }

    @Test
    void fixedBackoff_uses_the_same_delay_for_every_redelivery() {
        var policy = RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5);

        assertThat(delaysForAttempts(policy, 6)).containsOnly(Duration.ofMillis(500));
        assertThat(policy.calculateNextRedeliveryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void fixedBackoff_builder_uses_the_same_delay_for_every_redelivery() {
        var policy = RedeliveryPolicy.fixedBackoff()
                                     .setRedeliveryDelay(Duration.ofMillis(250))
                                     .setMaximumNumberOfRedeliveries(5)
                                     .build();

        assertThat(delaysForAttempts(policy, 6)).containsOnly(Duration.ofMillis(250));
    }

    @Test
    void linearBackoff_adds_the_redelivery_delay_on_every_redelivery_up_to_the_threshold() {
        var policy = RedeliveryPolicy.linearBackoff(Duration.ofSeconds(1), Duration.ofMillis(3_500), 10);

        // Before 0.60 this was 1s, 2s, 2s, 2s, …
        assertThat(delaysForAttempts(policy, 6)).containsExactly(Duration.ofSeconds(1),
                                                                 Duration.ofSeconds(2),
                                                                 Duration.ofSeconds(3),
                                                                 Duration.ofMillis(3_500),
                                                                 Duration.ofMillis(3_500),
                                                                 Duration.ofMillis(3_500));
        assertThat(policy.calculateNextRedeliveryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(3_500));
    }

    @Test
    void linearBackoff_builder_adds_the_redelivery_delay_on_every_redelivery_and_keeps_its_error_handler() {
        var errorHandler = MessageDeliveryErrorHandler.stopRedeliveryOn(IllegalStateException.class);
        var policy = RedeliveryPolicy.linearBackoff()
                                     .setRedeliveryDelay(Duration.ofMillis(150))
                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(1))
                                     .setMaximumNumberOfRedeliveries(20)
                                     .setDeliveryErrorHandler(errorHandler)
                                     .build();

        assertThat(delaysForAttempts(policy, 4)).containsExactly(Duration.ofMillis(150),
                                                                 Duration.ofMillis(300),
                                                                 Duration.ofMillis(450),
                                                                 Duration.ofMillis(600));
        assertThat(policy.calculateNextRedeliveryDelay(7)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.deliveryErrorHandler).isSameAs(errorHandler);
        assertThat(policy).isEqualTo(RedeliveryPolicy.linearBackoff()
                                                     .setRedeliveryDelay(Duration.ofMillis(150))
                                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(1))
                                                     .setMaximumNumberOfRedeliveries(20)
                                                     .setDeliveryErrorHandler(errorHandler)
                                                     .build());
    }

    @Test
    void a_linear_policy_is_not_equal_to_an_exponential_policy_with_the_same_fields() {
        var linear      = RedeliveryPolicy.linearBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30), 10);
        var exponential = RedeliveryPolicy.exponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0d, Duration.ofSeconds(30), 10);

        assertThat(linear).isNotEqualTo(exponential);
        assertThat(linear.calculateNextRedeliveryDelay(2)).isNotEqualTo(exponential.calculateNextRedeliveryDelay(2));
    }

    @Test
    void a_negative_attempt_count_is_rejected() {
        var policy = RedeliveryPolicy.fixedBackoff(Duration.ofMillis(500), 5);

        assertThatThrownBy(() -> policy.calculateNextRedeliveryDelay(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
