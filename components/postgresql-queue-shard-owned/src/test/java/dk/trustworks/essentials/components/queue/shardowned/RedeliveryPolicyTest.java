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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class RedeliveryPolicyTest {

    @Test
    void exponential_grows_from_the_initial_delay_up_to_the_cap() {
        var policy = RedeliveryPolicy.exponential(Duration.ofMillis(100), 2.0d, Duration.ofMillis(500), 10);

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void fixed_waits_the_same_every_time() {
        var policy = RedeliveryPolicy.fixed(Duration.ofMillis(50), 3);

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
    }

    @Test
    void a_subscription_runs_under_the_backoff_its_options_carry() {
        RetryBackoff linear  = attemptsSoFar -> Duration.ofMillis(10L * attemptsSoFar);
        var          options = new ConsumerOptions(1, Integer.MAX_VALUE, 4, linear);

        var policy = RedeliveryPolicy.from(options);

        assertThat(policy.maxAttempts()).isEqualTo(4);
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMillis(30));
    }

    @Test
    void a_caller_supplied_backoff_that_answers_negative_means_no_wait() {
        var policy = new RedeliveryPolicy(3, attemptsSoFar -> Duration.ofMillis(-5));

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void a_caller_supplied_backoff_that_answers_null_is_refused() {
        var policy = new RedeliveryPolicy(3, attemptsSoFar -> null);

        assertThatThrownBy(() -> policy.delayAfter(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equal_settings_give_equal_options() {
        assertThat(ConsumerOptions.defaults()).isEqualTo(ConsumerOptions.defaults());
        assertThat(RetryBackoff.fixed(Duration.ofMillis(20))).isEqualTo(RetryBackoff.exponential(Duration.ofMillis(20), 1.0d, Duration.ofMillis(20)));
    }
}
